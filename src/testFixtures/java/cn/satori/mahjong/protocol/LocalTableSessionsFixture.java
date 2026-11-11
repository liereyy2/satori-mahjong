package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Two independent games behind one loopback account; no real accounts or inference. */
public final class LocalTableSessionsFixture implements AutoCloseable {
    public static final UUID A = new UUID(0, 701), B = new UUID(0, 702);
    private final ServerSocket server;
    private final Path directory;
    private final Map<UUID, JsonObject> views = new ConcurrentHashMap<>();
    private final Map<UUID, DataOutputStream> streams = new ConcurrentHashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    public final CountDownLatch releaseCreation = new CountDownLatch(1);
    public final CountDownLatch releaseSession = new CountDownLatch(1);
    private final CountDownLatch releaseReady = new CountDownLatch(1);
    public final AtomicInteger sessions = new AtomicInteger();
    public volatile boolean delaySession;
    public final AtomicInteger creations = new AtomicInteger(), logins = new AtomicInteger();
    public final List<String> commands = new CopyOnWriteArrayList<>();
    public final SatoriApi api;
    public final AccountConfig config;
    public volatile Throwable failure;
    public volatile boolean authenticated;
    public volatile java.util.function.Supplier<CompletableFuture<Boolean>> initialAccountCheck;
    private final AtomicInteger creditsReads = new AtomicInteger();
    private final AtomicInteger keyReads = new AtomicInteger();
    private volatile boolean closed;

    private final HostedTransportFixture hosted;
    private final HostedRenderFixture render = new HostedRenderFixture();

    public static SatoriApi hostedApi() {
        return new SatoriApi(URI.create("http://127.0.0.1:18767"));
    }

    public LocalTableSessionsFixture() throws Exception {
        this(false);
    }

    public LocalTableSessionsFixture(boolean managed) throws Exception {
        hosted = managed ? new HostedTransportFixture() : null;
        try (var input = getClass().getResourceAsStream("/cloud-view.json")) {
            var base =
                    JsonParser.parseReader(
                                    new InputStreamReader(
                                            Objects.requireNonNull(input), StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (var id : List.of(A, B)) {
                var view = base.deepCopy();
                view.addProperty("game_id", id.toString());
                view.addProperty("cursor", id + ":0:6");
                view.getAsJsonObject("public")
                        .getAsJsonArray("scores")
                        .set(0, new JsonPrimitive(id.equals(A) ? 25000 : 31000));
                views.put(id, view);
            }
        }
        directory = Files.createTempDirectory("satori-multi-table-");
        config = new AccountConfig(directory.resolve("account.json"));
        server = new ServerSocket(managed ? 18767 : 0, 8, InetAddress.getByName("127.0.0.1"));
        api = new SatoriApi(URI.create("http://127.0.0.1:" + server.getLocalPort()));
        Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            while (!closed) {
                                try {
                                    var socket = server.accept();
                                    sockets.add(socket);
                                    Thread.ofPlatform().daemon().start(() -> serve(socket));
                                } catch (IOException error) {
                                    if (!closed) failure = error;
                                }
                            }
                        });
    }

    public List<String> players() {
        var players = new ArrayList<String>();
        for (var raw : views.get(A).getAsJsonObject("public").getAsJsonArray("seats"))
            players.add(raw.getAsJsonObject().get("user_id").getAsString());
        return players;
    }

    public int liveStreams() {
        return streams.size();
    }

    public void update(UUID game) {
        var old = views.get(game);
        var next = old.deepCopy();
        next.addProperty("view_version", old.get("view_version").getAsLong() + 1);
        next.addProperty("cursor", game + ":0:" + next.get("view_version").getAsLong());
        var envelope = new JsonObject();
        envelope.addProperty("type", "update");
        envelope.add("from_cursor", old.get("cursor"));
        envelope.add("events", new JsonArray());
        envelope.add("view", next);
        views.put(game, next);
        try {
            frame(Objects.requireNonNull(streams.get(game)), envelope);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private void serve(Socket socket) {
        UUID streamedGame = null;
        try (socket) {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            var header = new ByteArrayOutputStream();
            while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                int value = in.read();
                if (value < 0) return;
                if (header.size() > 16384) throw new IOException("Oversized request header");
                header.write(value);
            }
            var lines = header.toString(StandardCharsets.US_ASCII).split("\r\n");
            var request = lines[0].split(" ");
            var headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                var parts = lines[i].split(":", 2);
                headers.put(parts[0].toLowerCase(Locale.ROOT), parts[1].trim());
            }
            byte[] raw =
                    in.readNBytes(Integer.parseInt(headers.getOrDefault("content-length", "0")));
            String path = URI.create(request[1]).getPath();
            UUID game =
                    path.startsWith("/api/game/v1/games/")
                            ? UUID.fromString(path.split("/")[5])
                            : null;
            if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                String accept =
                        Base64.getEncoder()
                                .encodeToString(
                                        MessageDigest.getInstance("SHA-1")
                                                .digest(
                                                        (headers.get("sec-websocket-key")
                                                                        + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                                                .getBytes(
                                                                        StandardCharsets
                                                                                .US_ASCII)));
                out.writeBytes(
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                                + "Connection: Upgrade\r\nSec-WebSocket-Accept: "
                                + accept
                                + "\r\n\r\n");
                out.flush();
                JsonObject subscribe = readFrame(in);
                require(
                        subscribe.get("seat_token").getAsString().equals(token(game)),
                        "Wrong stream token");
                streams.put(game, out);
                streamedGame = game;
                var first = new JsonObject();
                boolean beginning =
                        subscribe.has("after_cursor")
                                && subscribe
                                        .get("after_cursor")
                                        .getAsString()
                                        .equals(game + ":0:0");
                first.addProperty("type", beginning ? "update" : "snapshot");
                if (beginning) {
                    first.addProperty("from_cursor", game + ":0:0");
                    first.add("cursor", views.get(game).get("cursor"));
                    try (var events = getClass().getResourceAsStream("/electric-examples.json")) {
                        first.add(
                                "events",
                                JsonParser.parseReader(
                                                new InputStreamReader(
                                                        events, StandardCharsets.UTF_8))
                                        .getAsJsonObject()
                                        .getAsJsonObject("start_kyoku")
                                        .getAsJsonObject("update")
                                        .getAsJsonArray("events")
                                        .deepCopy());
                    }
                }
                first.add("view", views.get(game));
                frame(out, first);
                while (!closed) {
                    var message = readFrame(in);
                    if (message.get("type").getAsString().equals("ping")) {
                        var pong = new JsonObject();
                        pong.addProperty("type", "pong");
                        frame(out, pong);
                    }
                }
                return;
            }
            JsonObject response = new JsonObject();
            if (path.equals("/api/account/session")) {
                response.addProperty("logged_in", authenticated);
                response.addProperty("csrf_token", "multi-csrf");
                var user = new JsonObject();
                user.add("id", views.get(A).get("owner_user_id"));
                user.addProperty("username", "Multi table");
                user.addProperty("public_id", "123456789");
                response.add("user", authenticated ? user : JsonNull.INSTANCE);
            } else if (path.equals("/api/account/login")) {
                require(
                        headers.getOrDefault("cookie", "").contains("multi=shared"),
                        "Login cookie missing");
                authenticated = true;
                logins.incrementAndGet();
            } else if (path.equals("/api/account/logout")) {
                authenticated = false;
            } else if (path.equals("/api/account/credits")) {
                require(
                        authenticated
                                && headers.getOrDefault("cookie", "").contains("multi=shared"),
                        "Credits request missing shared authentication");
                response.addProperty(
                        "credits", creditsReads.incrementAndGet() == 1 ? "123456" : "98765");
            } else if (hosted != null && path.endsWith("/plaintext")) {
                response.addProperty("key_id", "00000000-0000-0000-0000-000000000010");
                response.addProperty("api_key", "synthetic-selected-key");
            } else if (path.equals("/api/account/api-keys")) {
                keyReads.incrementAndGet();
                response.add(
                        "items",
                        hosted == null
                                ? new JsonArray()
                                : JsonParser.parseString(
                                        "[{\"id\":\"00000000-0000-0000-0000-000000000010\",\"name\":\"Local"
                                            + " test"
                                            + " key\",\"key_masked\":\"sk_***0010\",\"status\":\"active\"}]"));
            } else if (hosted != null && path.startsWith("/api/game/v1/tables/")) {
                require(
                        authenticated
                                && headers.getOrDefault("cookie", "").contains("multi=shared"),
                        "Missing shared account authentication");
                response = hosted.request(path, request[0], raw);
                if (response.has("seats")) {
                    require(
                            URI.create(request[1]).getRawQuery().startsWith("render_stream=true"),
                            "Missing explicit render projection request");
                    response.add("render_stream", JsonNull.INSTANCE);
                    response.add("public_view", JsonNull.INSTANCE);
                }
                if (response.has("seats") && !response.get("game_id").isJsonNull()) {
                    var rows = new JsonArray();
                    var names =
                            views.get(A).getAsJsonObject("display").getAsJsonArray("seat_names");
                    for (var entry : response.getAsJsonArray("seats")) {
                        var row = entry.getAsJsonObject().deepCopy();
                        if (row.has("name")) names.set(row.get("seat").getAsInt(), row.get("name"));
                        if (row.get("kind").getAsString().equals("empty")) {
                            row.addProperty("kind", "human");
                            row.add("user_id", JsonNull.INSTANCE);
                        }
                        if (row.has("player_id")) {
                            row.add("user_id", row.remove("player_id"));
                        }
                        row.addProperty("ready", true);
                        rows.add(row);
                    }
                    views.get(A).getAsJsonObject("public").add("seats", rows);
                    var observer = views.get(A).deepCopy();
                    observer.addProperty("viewer_seat", 4);
                    observer.getAsJsonObject("display").remove("standing_hint");
                    observer.addProperty(
                            "view_version", response.get("revision").getAsLong() + 100);
                    observer.addProperty(
                            "cursor",
                            A + ":public:" + (response.get("revision").getAsLong() + 100));
                    observer.add("private", JsonNull.INSTANCE);
                    observer.add("decision", JsonNull.INSTANCE);
                    response.add("public_view", observer);
                    render.attach(response, views.get(A), URI.create(request[1]).getRawQuery());
                }
            } else if (hosted != null && path.equals("/fixture/initial-account-check")) {
                response = new JsonObject();
                response.addProperty(
                        "complete",
                        Objects.requireNonNull(initialAccountCheck)
                                .get()
                                .get(10, TimeUnit.SECONDS));
            } else if (hosted != null && path.equals("/fixture/hosted")) {
                response = hosted.snapshot();
                response.addProperty("key_reads", keyReads.get());
                response.addProperty("seat_commands", commands.size());
            } else if (hosted != null && path.equals("/fixture/release-ready")) {
                releaseReady.countDown();
            } else if (hosted != null && path.equals("/fixture/finish")) {
                hosted.finish();
                var view = views.get(A);
                view.addProperty("status", "finished");
                view.add("decision", JsonNull.INSTANCE);
                update(A);
            } else if (hosted != null && path.endsWith("/result")) {
                response = hosted.result(views.get(game));
            } else if (hosted != null && path.equals("/api/messages/tickets")) {
                require(
                        authenticated
                                && headers.getOrDefault("cookie", "").contains("multi=shared")
                                && "multi-csrf".equals(headers.get("x-csrf-token")),
                        "Feedback account authentication missing");
                var report =
                        JsonParser.parseString(new String(raw, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                require(
                        report.size() == 2
                                && report.get("body").getAsString().contains("MC player:")
                                && !report.toString().contains("synthetic-selected-key"),
                        "Invalid feedback metadata");
                response.addProperty("id", "00000000-0000-0000-0000-000000000777");
            } else if (path.equals("/api/game/v1/games") && request[0].equals("GET")) {
                response.add("items", new JsonArray());
                response.add("next_cursor", JsonNull.INSTANCE);
            } else if (path.equals("/api/game/v1/games") && request[0].equals("POST")) {
                require(
                        authenticated
                                && headers.getOrDefault("cookie", "").contains("multi=shared"),
                        "Creation identity missing");
                require("multi-csrf".equals(headers.get("x-csrf-token")), "Creation CSRF missing");
                var draft =
                        JsonParser.parseString(new String(raw, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                require(!draft.has("api_key"), "Unexpected AI call");
                int ordinal = creations.incrementAndGet();
                require(ordinal <= 2, "Duplicate creation");
                game = ordinal == 1 ? A : B;
                if (ordinal == 1 && !releaseCreation.await(30, TimeUnit.SECONDS))
                    throw new IOException("First creation was never released");
                response = joined(game);
            } else if (path.endsWith("/sessions")) {
                sessions.incrementAndGet();
                if (delaySession && !releaseSession.await(30, TimeUnit.SECONDS))
                    throw new IOException("Delayed session was never released");
                response = joined(game);
            } else if (request[0].equals("DELETE") && game != null) {
                require(
                        authenticated && "multi-csrf".equals(headers.get("x-csrf-token")),
                        "Deletion identity missing");
            } else if (path.endsWith("/presentation")) {
                require(
                        ("Bearer " + token(game)).equals(headers.get("authorization")),
                        "Wrong presentation token");
                var view = views.get(game);
                for (String key : List.of("game_id", "viewer_seat", "view_version", "cursor"))
                    response.add(key, view.get(key));
                response.add("settlement", JsonNull.INSTANCE);
                response.add("hand_hint", JsonNull.INSTANCE);
                response.add("discard_hints", new JsonArray());
            } else if (hosted != null && path.endsWith("/state")) {
                require(
                        ("Bearer " + token(game)).equals(headers.get("authorization")),
                        "Wrong state token");
                response = views.get(game);
            } else if (path.endsWith("/ready")) {
                require(
                        ("Bearer " + token(game)).equals(headers.get("authorization")),
                        "Wrong action token");
                commands.add(game.toString());
                if (hosted != null && !releaseReady.await(30, TimeUnit.SECONDS))
                    throw new IOException("Pending UI receipt was never released");
            } else throw new IOException("Unexpected request " + path);
            byte[] data = response.toString().getBytes(StandardCharsets.UTF_8);
            out.writeBytes(
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Set-Cookie: multi=shared; Path=/; HttpOnly\r\n"
                            + "Connection: close\r\n"
                            + "Content-Length: "
                            + data.length
                            + "\r\n\r\n");
            out.write(data);
            out.flush();
        } catch (EOFException | SocketException disconnected) {
            // Closing a table subscription is an expected lifecycle operation in this fixture.
        } catch (Exception error) {
            if (!closed) failure = error;
        } finally {
            if (streamedGame != null) streams.remove(streamedGame);
            sockets.remove(socket);
        }
    }

    private static String token(UUID game) {
        return "synthetic-seat-" + game;
    }

    private static JsonObject joined(UUID game) {
        var result = new JsonObject();
        result.addProperty("game_id", game.toString());
        var self = new JsonObject();
        self.addProperty("seat", 0);
        self.addProperty("start_cursor", game + ":0:0");
        self.addProperty("seat_token", token(game));
        result.add("self_session", self);
        return result;
    }

    private static JsonObject readFrame(DataInputStream input) throws IOException {
        int opcode = input.readUnsignedByte() & 15;
        if (opcode == 8) throw new EOFException();
        require(opcode == 1, "Expected text frame");
        int flags = input.readUnsignedByte(), size = flags & 127;
        require((flags & 128) != 0, "Expected client mask");
        if (size == 126) size = input.readUnsignedShort();
        require(size < 16384, "Oversized client frame");
        byte[] mask = input.readNBytes(4), bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        for (int i = 0; i < size; i++) bytes[i] ^= mask[i % 4];
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void frame(DataOutputStream out, JsonObject envelope) throws IOException {
        byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.writeByte(0x81);
            if (bytes.length < 126) out.writeByte(bytes.length);
            else {
                out.writeByte(126);
                out.writeShort(bytes.length);
            }
            out.write(bytes);
            out.flush();
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    @Override
    public void close() {
        closed = true;
        releaseCreation.countDown();
        releaseSession.countDown();
        releaseReady.countDown();
        api.close();
        try {
            server.close();
            for (var socket : sockets) socket.close();
            Files.deleteIfExists(directory.resolve("account.json"));
            Files.deleteIfExists(directory);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
