package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.*;

/** Command receipts and subscription transport. A pending command retains its original identity. */
public final class GameConnection implements AutoCloseable {
    public final UUID gameId;
    public final int seat;
    private final String token;
    private final SatoriApi api;
    private final CloudStream stream;
    private final JsonObject session;
    private CompletableFuture<JsonObject> pending;
    private boolean closed;

    public GameConnection(
            SatoriApi api,
            UUID gameId,
            JsonObject session,
            BiConsumer<CloudView, JsonArray> view,
            Consumer<String> status) {
        this.api = api;
        this.gameId = gameId;
        this.session = session.deepCopy();
        token = session.get("seat_token").getAsString();
        seat = session.get("seat").getAsInt();
        stream = new CloudStream(api, gameId, token, seat, view, status);
    }

    public void start(boolean newlyCreated) {
        stream.start(newlyCreated ? session.get("start_cursor").getAsString() : null);
    }

    public synchronized boolean hasPending() {
        return pending != null;
    }

    public synchronized CompletableFuture<JsonObject> command(
            String method, String path, JsonObject arguments) {
        if (closed || pending != null)
            return CompletableFuture.failedFuture(
                    new SatoriApi.ApiException(409, "command_pending"));
        JsonObject body = arguments.deepCopy();
        body.addProperty("command_id", UUID.randomUUID().toString());
        var request = api.game(gameId, token, method, path, body);
        pending = request;
        return request.whenComplete(
                (result, error) -> {
                    synchronized (this) {
                        if (pending == request) pending = null;
                    }
                });
    }

    public CompletableFuture<JsonObject> result() {
        return api.game(gameId, token, "GET", "/result", null);
    }

    public CompletableFuture<JsonObject> presentation() {
        return api.game(gameId, token, "GET", "/presentation", null);
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) pending.cancel(true);
        pending = null;
        stream.close();
    }
}
