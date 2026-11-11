package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.*;

/** Recoverable seat stream. Cursor advances only after validating a complete server view. */
public final class CloudStream implements AutoCloseable {
    private final SatoriApi api;
    private final UUID game;
    private final String token;
    private final int seat;
    private final BiConsumer<CloudView, JsonArray> onView;
    private final Consumer<String> onStatus;
    private final ScheduledExecutorService clock =
            Executors.newSingleThreadScheduledExecutor(
                    r -> Thread.ofPlatform().daemon().name("satori-cloud-stream").unstarted(r));
    private volatile WebSocket socket;
    private volatile boolean closed;
    private String cursor;
    private long version = -1;
    private int failures;
    private long lastMessage;
    private ScheduledFuture<?> retry;

    public CloudStream(
            SatoriApi api,
            UUID game,
            String token,
            int seat,
            BiConsumer<CloudView, JsonArray> onView,
            Consumer<String> onStatus) {
        this.api = api;
        this.game = game;
        this.token = token;
        this.seat = seat;
        this.onView = onView;
        this.onStatus = onStatus;
    }

    public void start(String afterCursor) {
        cursor = afterCursor;
        clock.execute(this::connect);
        clock.scheduleAtFixedRate(
                () -> {
                    WebSocket current = socket;
                    if (current == null || closed) return;
                    if (System.nanoTime() - lastMessage > TimeUnit.SECONDS.toNanos(45)) {
                        current.abort();
                        socket = null;
                        reconnect();
                        return;
                    }
                    current.sendText("{\"type\":\"ping\"}", true)
                            .exceptionally(
                                    error -> {
                                        clock.execute(
                                                () -> {
                                                    if (socket == current) {
                                                        socket = null;
                                                        reconnect();
                                                    }
                                                });
                                        return null;
                                    });
                },
                15,
                15,
                TimeUnit.SECONDS);
    }

    private void connect() {
        if (closed) return;
        onStatus.accept(failures == 0 ? "connecting" : "reconnecting");
        api.connect(game, new Listener())
                .whenComplete(
                        (connection, error) -> {
                            if (closed) {
                                if (connection != null) connection.abort();
                                return;
                            }
                            if (error != null) clock.execute(this::reconnect);
                        });
    }

    private void reconnect() {
        if (closed || retry != null && !retry.isDone()) return;
        onStatus.accept("reconnecting");
        int delay = Math.min(15, 1 << Math.min(failures++, 4));
        retry =
                clock.schedule(
                        () -> {
                            retry = null;
                            connect();
                        },
                        delay,
                        TimeUnit.SECONDS);
    }

    private void fatal(String code) {
        onStatus.accept(code);
        close();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder frame = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            if (closed) {
                ws.abort();
                return;
            }
            clock.execute(
                    () -> {
                        if (closed) {
                            ws.abort();
                            return;
                        }
                        socket = ws;
                        lastMessage = System.nanoTime();
                        JsonObject sub = new JsonObject();
                        sub.addProperty("type", "subscribe");
                        sub.addProperty("seat_token", token);
                        if (cursor != null) sub.addProperty("after_cursor", cursor);
                        ws.sendText(sub.toString(), true)
                                .exceptionally(
                                        error -> {
                                            ws.abort();
                                            return null;
                                        });
                        ws.request(1);
                    });
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            if (closed) return null;
            if (frame.length() + text.length() > 1_048_576) {
                clock.execute(() -> fatal("stream_message_too_large"));
                return null;
            }
            frame.append(text);
            if (last) {
                String raw = frame.toString();
                frame.setLength(0);
                clock.execute(() -> accept(ws, raw));
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            if (!closed)
                clock.execute(
                        () -> {
                            if (socket != ws) return;
                            socket = null;
                            if (code == 1008) fatal("seat_session_expired");
                            else reconnect();
                        });
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!closed)
                clock.execute(
                        () -> {
                            if (socket == ws) {
                                socket = null;
                                reconnect();
                            }
                        });
        }
    }

    private void accept(WebSocket ws, String raw) {
        if (closed || socket != ws) return;
        try {
            JsonObject envelope = JsonParser.parseString(raw).getAsJsonObject();
            String type = envelope.get("type").getAsString();
            lastMessage = System.nanoTime();
            if (type.equals("pong")) return;
            if (type.equals("sync_required")) {
                cursor = null;
                return;
            }
            if (type.equals("error")) {
                String code = envelope.getAsJsonObject("error").get("code").getAsString();
                fatal(code.matches("[a-z_]{1,100}") ? code : "stream_error");
                return;
            }
            if (!type.equals("snapshot") && !type.equals("update"))
                throw new IllegalArgumentException();
            CloudView next = new CloudView(envelope.getAsJsonObject("view"), game, seat);
            if (next.version < version) throw new IllegalArgumentException();
            if (next.version == version) {
                cursor = next.cursor;
                failures = 0;
                onStatus.accept("connected");
                return;
            }
            if (type.equals("update")
                    && (cursor == null
                            || !cursor.equals(envelope.get("from_cursor").getAsString())))
                throw new IllegalArgumentException();
            cursor = next.cursor;
            version = next.version;
            failures = 0;
            onView.accept(
                    next,
                    type.equals("update")
                            ? envelope.getAsJsonArray("events").deepCopy()
                            : new JsonArray());
            onStatus.accept("connected");
        } catch (CloudView.ServerUpdateRequired error) {
            fatal("server_update_required");
        } catch (RuntimeException error) {
            fatal("invalid_stream_projection");
        }
    }

    @Override
    public void close() {
        closed = true;
        WebSocket current = socket;
        socket = null;
        if (current != null) current.abort();
        clock.shutdownNow();
    }
}
