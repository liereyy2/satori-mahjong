package cn.satori.mahjong.protocol;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Display-only account snapshot. Calls and completions run on the MC server thread. */
public final class AccountCredits {
    private final Supplier<CompletableFuture<String>> request;
    private final Executor executor;
    private final LongSupplier clock;
    private final Consumer<Throwable> failed;
    private long generation, next;
    private boolean pending, scheduled;
    private String value, error = "";

    public AccountCredits(SatoriApi api, Executor executor, Consumer<Throwable> failed) {
        this(api::credits, executor, System::nanoTime, failed);
    }

    AccountCredits(
            Supplier<CompletableFuture<String>> request,
            Executor executor,
            LongSupplier clock,
            Consumer<Throwable> failed) {
        this.request = request;
        this.executor = executor;
        this.clock = clock;
        this.failed = failed;
    }

    public String value() {
        return value;
    }

    public String error() {
        return error;
    }

    public void reset() {
        generation++;
        value = null;
        error = "";
        pending = scheduled = false;
    }

    /** Called only by authenticated controller requests, so closed screens cause no traffic. */
    public void refresh() {
        long now = clock.getAsLong();
        if (pending || (scheduled && now - next < 0)) return;
        pending = scheduled = true;
        next = now + 5_000_000_000L;
        long expected = generation;
        request.get()
                .whenComplete(
                        (result, failure) ->
                                executor.execute(
                                        () -> {
                                            if (generation != expected) return;
                                            pending = false;
                                            value = failure == null ? result : null;
                                            error =
                                                    failure == null
                                                            ? ""
                                                            : SatoriApi.errorCode(failure);
                                            if (failure != null) failed.accept(failure);
                                        }));
    }
}
