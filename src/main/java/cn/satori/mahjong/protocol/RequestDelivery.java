package cn.satori.mahjong.protocol;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Delivers an idempotent request until its receipt or a definitive rejection arrives. */
final class RequestDelivery implements AutoCloseable {
    private final ScheduledExecutorService clock =
            Executors.newSingleThreadScheduledExecutor(
                    r -> Thread.ofPlatform().daemon().name("satori-command-delivery").unstarted(r));
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    <T> CompletableFuture<T> send(Supplier<CompletableFuture<T>> request) {
        var result = new CompletableFuture<T>();
        var flight = new AtomicReference<CompletableFuture<T>>();
        active.add(result);
        result.whenComplete(
                (value, error) -> {
                    active.remove(result);
                    var transmission = flight.getAndSet(null);
                    if (result.isCancelled() && transmission != null) transmission.cancel(true);
                });
        if (closed) result.cancel(true);
        else attempt(request, result, flight, 0);
        return result;
    }

    private <T> void attempt(
            Supplier<CompletableFuture<T>> request,
            CompletableFuture<T> result,
            AtomicReference<CompletableFuture<T>> flight,
            int retries) {
        if (closed || result.isDone()) return;
        CompletableFuture<T> transmission;
        try {
            transmission = request.get();
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
            return;
        }
        flight.set(transmission);
        if (result.isDone()) {
            transmission.cancel(true);
            return;
        }
        transmission.whenComplete(
                (value, error) -> {
                    flight.compareAndSet(transmission, null);
                    if (closed || result.isDone()) return;
                    if (error == null) result.complete(value);
                    else if (retryable(error)) {
                        try {
                            clock.schedule(
                                    () ->
                                            attempt(
                                                    request,
                                                    result,
                                                    flight,
                                                    Math.min(retries + 1, 4)),
                                    Math.min(1000L << retries, 10000),
                                    TimeUnit.MILLISECONDS);
                        } catch (RejectedExecutionException stopped) {
                            result.cancel(true);
                        }
                    } else result.completeExceptionally(error);
                });
    }

    static boolean retryable(Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException)
            error = error.getCause();
        if (error instanceof SatoriApi.ApiException ex)
            return !ex.code.equals("protocol_error")
                    && (ex.status == 408 || ex.status == 429 || ex.status >= 500);
        return error instanceof IOException || error instanceof TimeoutException;
    }

    @Override
    public void close() {
        closed = true;
        for (var future : Set.copyOf(active)) future.cancel(true);
        clock.shutdownNow();
    }
}
