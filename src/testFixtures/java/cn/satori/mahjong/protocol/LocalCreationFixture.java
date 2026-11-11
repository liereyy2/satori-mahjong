package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Isolated HTTP creation and real WebSocket snapshot, with no production credentials or AI. */
public final class LocalCreationFixture implements AutoCloseable {
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Path directory;
    private final JsonObject snapshot;
    public final SatoriApi api;
    public final AccountConfig config;
    public volatile boolean missingDecisionSeat;
    public volatile Throwable failure;
    public volatile int creations, subscriptions;

    public LocalCreationFixture(UUID game) throws Exception {
        try (var input = getClass().getResourceAsStream("/cloud-created.json")) {
            snapshot =
                    JsonParser.parseReader(
                                    new InputStreamReader(
                                            Objects.requireNonNull(input), StandardCharsets.UTF_8))
                            .getAsJsonObject();
        }
        snapshot.addProperty("game_id", game.toString());
        directory = Files.createTempDirectory("satori-create-test-");
        config = new AccountConfig(directory.resolve("account.json"));
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        api = new SatoriApi(URI.create("http://127.0.0.1:" + server.getLocalPort()));
        Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            while (closed.getCount() != 0) {
                                try {
                                    Socket socket = server.accept();
                                    sockets.add(socket);
                                    Thread.ofPlatform().daemon().start(() -> serve(socket));
                                } catch (IOException error) {
                                    if (closed.getCount() != 0) failure = error;
                                }
                            }
                        });
    }

    public List<String> players() {
        var players = new ArrayList<String>();
        for (var seat : snapshot.getAsJsonObject("public").getAsJsonArray("seats"))
            players.add(seat.getAsJsonObject().get("user_id").getAsString());
        return players;
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            var header = new ByteArrayOutputStream();
            while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                int value = in.read();
                if (value < 0) throw new EOFException();
                header.write(value);
            }
            var lines = header.toString(StandardCharsets.US_ASCII).split("\r\n");
            var headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                var pair = lines[i].split(":", 2);
                headers.put(pair[0].toLowerCase(Locale.ROOT), pair[1].trim());
            }
            byte[] body =
                    in.readNBytes(Integer.parseInt(headers.getOrDefault("content-length", "0")));
            String path = lines[0].split(" ")[1];
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
                        "HTTP/1.1 101 Switching Protocols\r\n"
                                + "Upgrade: websocket\r\n"
                                + "Connection: Upgrade\r\n"
                                + "Sec-WebSocket-Accept: "
                                + accept
                                + "\r\n\r\n");
                out.flush();
                if (in.readUnsignedByte() != 0x81) throw new IOException("Expected subscribe text");
                int marker = in.readUnsignedByte(), size = marker & 127;
                if ((marker & 128) == 0) throw new IOException("Expected masked subscribe");
                if (size == 126) size = in.readUnsignedShort();
                byte[] mask = in.readNBytes(4), payload = in.readNBytes(size);
                for (int i = 0; i < size; i++) payload[i] ^= mask[i % 4];
                var sub =
                        JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                if (!sub.get("seat_token").getAsString().equals("synthetic-created-seat"))
                    throw new IOException("Wrong seat token");
                subscriptions++;
                var projection = snapshot.deepCopy();
                if (missingDecisionSeat)
                    projection.getAsJsonObject("display").remove("decision_seat");
                byte[] frame =
                        ("{\"type\":\"snapshot\",\"view\":" + projection + "}")
                                .getBytes(StandardCharsets.UTF_8);
                out.writeByte(0x81);
                out.writeByte(126);
                out.writeShort(frame.length);
                out.write(frame);
                out.flush();
                closed.await();
                return;
            }
            String response;
            if (path.equals("/api/account/session")) {
                response =
                        "{\"logged_in\":true,\"csrf_token\":\"synthetic-csrf\",\"user\":{\"id\":\""
                                + players().getFirst()
                                + "\",\"username\":\"Creation test\",\"public_id\":\"123456789\"}}";
            } else if (path.equals("/api/account/api-keys")) {
                response = "{\"items\":[]}";
            } else if (lines[0].startsWith("POST ") && path.equals("/api/game/v1/games")) {
                var draft =
                        JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                if (draft.has("api_key") || !draft.getAsJsonArray("ai_seats").isEmpty())
                    throw new IOException("Unexpected AI request");
                creations++;
                response =
                        "{\"game_id\":\""
                                + snapshot.get("game_id").getAsString()
                                + "\",\"self_session\":{\"seat\":0,\"seat_token\":\"synthetic-created-seat\",\"start_cursor\":\""
                                + snapshot.get("game_id").getAsString()
                                + ":0:0\"}}";
            } else throw new IOException("Unexpected fixture request " + path);
            byte[] encoded = response.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Connection: close\r\n"
                            + "Content-Length: "
                            + encoded.length
                            + "\r\n\r\n");
            out.write(encoded);
            out.flush();
        } catch (Exception error) {
            if (closed.getCount() != 0) failure = error;
        } finally {
            sockets.remove(socket);
        }
    }

    @Override
    public void close() {
        closed.countDown();
        api.close();
        try {
            server.close();
            for (Socket socket : sockets) socket.close();
            config.clear();
            Files.delete(directory);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
