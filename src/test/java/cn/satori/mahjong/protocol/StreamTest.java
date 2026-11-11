package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real loopback WebSocket wire tests, including masked credentials and cursor recovery. */
class StreamTest {
    private static final UUID GAME = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fragmentsReconnectAndDuplicateUpdatesPreserveCursor(boolean newlyCreated)
            throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                SatoriApi api =
                        new SatoriApi(URI.create("http://127.0.0.1:" + server.getLocalPort()))) {
            server.setSoTimeout(8000);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread worker =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try {
                                            try (Socket socket = server.accept()) {
                                                handshake(socket);
                                                JsonObject sub =
                                                        JsonParser.parseString(read(socket))
                                                                .getAsJsonObject();
                                                assertEquals(
                                                        "private-seat",
                                                        sub.get("seat_token").getAsString());
                                                if (newlyCreated)
                                                    assertEquals(
                                                            GAME + ":0:0",
                                                            sub.get("after_cursor").getAsString());
                                                else assertFalse(sub.has("after_cursor"));
                                                var envelope = new JsonObject();
                                                envelope.addProperty(
                                                        "type",
                                                        newlyCreated ? "update" : "snapshot");
                                                envelope.add("view", ProtocolTest.projection(0));
                                                if (newlyCreated) {
                                                    envelope.addProperty(
                                                            "from_cursor", GAME + ":0:0");
                                                    envelope.addProperty("cursor", "cursor-1");
                                                    envelope.add(
                                                            "events",
                                                            JsonParser.parseString(
                                                                    "[{\"type\":\"start_kyoku\"}]"));
                                                }
                                                String snapshot = envelope.toString();
                                                int mid = snapshot.length() / 2;
                                                frame(socket, snapshot.substring(0, mid), 1, false);
                                                frame(socket, snapshot.substring(mid), 0, true);
                                                Thread.sleep(100);
                                            }
                                            try (Socket socket = server.accept()) {
                                                handshake(socket);
                                                JsonObject sub =
                                                        JsonParser.parseString(read(socket))
                                                                .getAsJsonObject();
                                                assertEquals(
                                                        "cursor-1",
                                                        sub.get("after_cursor").getAsString());
                                                JsonObject next = ProtocolTest.projection(0);
                                                next.addProperty("cursor", "cursor-2");
                                                next.addProperty("view_version", 2);
                                                String update =
                                                        "{\"type\":\"update\",\"from_cursor\":\"cursor-1\",\"cursor\":\"cursor-2\",\"events\":[],\"view\":"
                                                                + next
                                                                + "}";
                                                frame(socket, update, 1, true);
                                                frame(socket, update, 1, true);
                                                done.await(5, TimeUnit.SECONDS);
                                            }
                                        } catch (Throwable error) {
                                            failure.set(error);
                                        }
                                    });
            List<Long> versions = new CopyOnWriteArrayList<>();
            List<String> eventTypes = new CopyOnWriteArrayList<>();
            CountDownLatch received = new CountDownLatch(2);
            try (CloudStream stream =
                    new CloudStream(
                            api,
                            GAME,
                            "private-seat",
                            0,
                            (view, events) -> {
                                versions.add(view.version);
                                events.forEach(
                                        e ->
                                                eventTypes.add(
                                                        e.getAsJsonObject()
                                                                .get("type")
                                                                .getAsString()));
                                received.countDown();
                            },
                            status -> {})) {
                stream.start(newlyCreated ? GAME + ":0:0" : null);
                assertTrue(received.await(7, TimeUnit.SECONDS));
                assertEquals(newlyCreated ? List.of("start_kyoku") : List.of(), eventTypes);
                Thread.sleep(100);
                assertEquals(List.of(1L, 2L), versions);
                done.countDown();
            }
            worker.join(2000);
            assertNull(failure.get());
        }
    }

    @Test
    void wrongSeatProjectionStopsWithoutPublishing() throws Exception {
        assertProjectionRejected(ProtocolTest.projection(1), "invalid_stream_projection");
    }

    @Test
    void missingDisplayContractStopsBeforePublishingCreationSnapshot() throws Exception {
        var created = ProtocolTest.projection(0);
        created.addProperty("status", "waiting_players");
        created.getAsJsonObject("display").remove("decision_seat");
        assertProjectionRejected(created, "server_update_required");
    }

    private void assertProjectionRejected(JsonObject projection, String expectedStatus)
            throws Exception {
        assertEnvelopeRejected(
                "{\"type\":\"snapshot\",\"view\":" + projection + "}", expectedStatus);
    }

    @Test
    void deletedGameIsTerminalAndNeverPublishesOldView() throws Exception {
        assertEnvelopeRejected(
                "{\"type\":\"error\",\"error\":{\"code\":\"game_not_found\"}}", "game_not_found");
    }

    private void assertEnvelopeRejected(String envelope, String expectedStatus) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                SatoriApi api =
                        new SatoriApi(URI.create("http://127.0.0.1:" + server.getLocalPort()))) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try (Socket socket = server.accept()) {
                                            handshake(socket);
                                            read(socket);
                                            frame(socket, envelope, 1, true);
                                            Thread.sleep(200);
                                        } catch (Throwable error) {
                                            failure.set(error);
                                        }
                                    });
            CountDownLatch rejected = new CountDownLatch(1);
            AtomicInteger published = new AtomicInteger();
            try (CloudStream stream =
                    new CloudStream(
                            api,
                            GAME,
                            "private-seat",
                            0,
                            (v, e) -> published.incrementAndGet(),
                            status -> {
                                if (status.equals(expectedStatus)) rejected.countDown();
                            })) {
                stream.start(null);
                assertTrue(rejected.await(5, TimeUnit.SECONDS));
                assertEquals(0, published.get());
            }
            worker.join(2000);
            assertNull(failure.get());
        }
    }

    private static void handshake(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (!bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            bytes.write(b);
        }
        String request = bytes.toString(StandardCharsets.US_ASCII);
        assertFalse(request.toLowerCase().contains("cookie:"));
        assertFalse(request.contains("private-seat"));
        assertFalse(request.split("\r\n")[0].contains("?"));
        String key =
                Arrays.stream(request.split("\r\n"))
                        .filter(line -> line.toLowerCase().startsWith("sec-websocket-key:"))
                        .findFirst()
                        .orElseThrow()
                        .split(":", 2)[1]
                        .trim();
        String accept =
                Base64.getEncoder()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-1")
                                        .digest(
                                                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                                        .getBytes(StandardCharsets.US_ASCII)));
        socket.getOutputStream()
                .write(
                        ("HTTP/1.1 101 Switching Protocols\r\n"
                                        + "Upgrade: websocket\r\n"
                                        + "Connection: Upgrade\r\n"
                                        + "Sec-WebSocket-Accept: "
                                        + accept
                                        + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String read(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        assertEquals(0x81, in.readUnsignedByte());
        int marker = in.readUnsignedByte();
        assertTrue((marker & 128) != 0);
        int size = marker & 127;
        if (size == 126) size = in.readUnsignedShort();
        assertTrue(size < 4096);
        byte[] mask = in.readNBytes(4), data = in.readNBytes(size);
        for (int i = 0; i < size; i++) data[i] ^= mask[i % 4];
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void frame(Socket socket, String message, int opcode, boolean last)
            throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeByte((last ? 128 : 0) | opcode);
        if (bytes.length < 126) out.writeByte(bytes.length);
        else {
            out.writeByte(126);
            out.writeShort(bytes.length);
        }
        out.write(bytes);
        out.flush();
    }
}
