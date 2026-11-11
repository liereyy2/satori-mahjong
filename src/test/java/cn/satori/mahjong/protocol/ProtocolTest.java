package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class ProtocolTest {
    @Test
    void creditsUsesAccountCookieAndPreservesExactCredits() throws Exception {
        var amount = new AtomicReference<>("999999999999999999");
        var status = new AtomicInteger(200);
        serve(
                x -> {
                    if (x.getRequestURI().getPath().equals("/api/account/session")) {
                        x.getResponseHeaders()
                                .add("Set-Cookie", "test_session=account; Path=/; HttpOnly");
                        respond(x, 200, "{\"logged_in\":false,\"csrf_token\":\"credits-csrf\"}");
                        return;
                    }
                    assertEquals("GET", x.getRequestMethod());
                    assertEquals("/api/account/credits", x.getRequestURI().getPath());
                    assertNull(x.getRequestHeaders().getFirst("Authorization"));
                    assertTrue(
                            x.getRequestHeaders()
                                    .getFirst("Cookie")
                                    .contains("test_session=account"));
                    respond(x, status.get(), "{\"credits\":\"" + amount.get() + "\"}");
                });
        api.session().get(5, TimeUnit.SECONDS);
        assertEquals(amount.get(), api.credits().get(5, TimeUnit.SECONDS));
        amount.set("1");
        assertEquals("1", api.credits().get(5, TimeUnit.SECONDS));
        amount.set("invalid");
        assertThrows(ExecutionException.class, () -> api.credits().get(5, TimeUnit.SECONDS));
        status.set(401);
        var failure =
                assertThrows(
                        ExecutionException.class, () -> api.credits().get(5, TimeUnit.SECONDS));
        assertTrue(SatoriApi.accountExpired(failure));
    }

    @Test
    void bugTicketsUseAccountSessionAndNeverAutomaticallyRepeatAnUncertainPost() throws Exception {
        var requests = new AtomicInteger();
        var valid = new AtomicBoolean();
        serve(
                x -> {
                    if (x.getRequestURI().getPath().equals("/api/account/session")) {
                        x.getResponseHeaders()
                                .add("Set-Cookie", "test_session=account; Path=/; HttpOnly");
                        respond(x, 200, "{\"logged_in\":false,\"csrf_token\":\"test-csrf\"}");
                        return;
                    }
                    var payload =
                            JsonParser.parseString(
                                            new String(
                                                    x.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                    valid.set(
                            x.getRequestMethod().equals("POST")
                                    && x.getRequestURI().getPath().equals("/api/messages/tickets")
                                    && x.getRequestHeaders().getFirst("Authorization") == null
                                    && x.getRequestHeaders()
                                            .getFirst("Cookie")
                                            .contains("test_session=account")
                                    && x.getRequestHeaders()
                                            .getFirst("X-CSRF-Token")
                                            .equals("test-csrf")
                                    && payload.get("subject")
                                            .getAsString()
                                            .equals("[SatoriMahjong Bug] Title")
                                    && payload.size() == 2);
                    if (requests.incrementAndGet() == 1) respond(x, 503, "{}");
                    else respond(x, 200, "{\"id\":\"" + GAME + "\"}");
                });
        api.session().get(5, TimeUnit.SECONDS);
        var report = new BugReport("test", "forge", "1.21.11", GAME);
        assertThrows(
                ExecutionException.class,
                () -> api.reportBug(report, "Title", "Details").get(5, TimeUnit.SECONDS));
        assertEquals(1, requests.get());
        assertTrue(valid.get());
        assertEquals(GAME, api.reportBug(report, "Title", "Details").get(5, TimeUnit.SECONDS));
        assertEquals(2, requests.get());
    }

    @Test
    void catalogAndDeleteUseAccountCookiesAndStableCommandWithoutAiKey() throws Exception {
        var observed = new CopyOnWriteArrayList<String>();
        serve(
                x -> {
                    String path = x.getRequestURI().getPath();
                    if (path.equals("/api/account/session")) {
                        x.getResponseHeaders()
                                .add("Set-Cookie", "test_session=account; Path=/; HttpOnly");
                        respond(x, 200, "{\"logged_in\":false,\"csrf_token\":\"catalog-csrf\"}");
                        return;
                    }
                    assertNull(x.getRequestHeaders().getFirst("Authorization"));
                    assertTrue(
                            x.getRequestHeaders()
                                    .getFirst("Cookie")
                                    .contains("test_session=account"));
                    observed.add(x.getRequestMethod() + " " + x.getRequestURI());
                    if (x.getRequestMethod().equals("GET"))
                        respond(x, 200, "{\"items\":[],\"next_cursor\":null}");
                    else {
                        assertEquals(
                                "catalog-csrf", x.getRequestHeaders().getFirst("X-CSRF-Token"));
                        assertEquals(
                                "",
                                new String(
                                        x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        respond(x, 200, "{\"accepted\":true}");
                    }
                });
        api.session().get(5, TimeUnit.SECONDS);
        assertTrue(api.games("cursor=+", 3).get(5, TimeUnit.SECONDS).items().isEmpty());
        api.deleteGame(GAME).get(5, TimeUnit.SECONDS);
        assertEquals(
                List.of(
                        "GET /api/game/v1/games?limit=3&cursor=cursor%3D%2B",
                        "DELETE /api/game/v1/games/" + GAME),
                observed);
    }

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID GAME = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private HttpServer server;
    private SatoriApi api;

    @AfterEach
    void close() {
        if (api != null) api.close();
        if (server != null) server.stop(0);
    }

    private void serve(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        api = new SatoriApi(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange x, int code, String value)
            throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    @Test
    void keyListContainsMetadataAndRevealsOnlyTheRequestedKey() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        serve(
                x -> {
                    String path = x.getRequestURI().getPath();
                    requests.add(x.getRequestMethod() + " " + path);
                    if (path.endsWith("/plaintext"))
                        respond(
                                x,
                                200,
                                "{\"key_id\":\"" + GAME + "\",\"api_key\":\"selected-secret\"}");
                    else
                        respond(
                                x,
                                200,
                                "{\"items\":[{\"id\":\""
                                        + GAME
                                        + "\",\"name\":\"AI\",\"key_masked\":\"sk_***\",\"status\":\"active\"},"
                                        + "{\"id\":\""
                                        + OWNER
                                        + "\",\"name\":\"Old\",\"key_masked\":\"sk_***\",\"status\":\"revoked\"}]}");
                });
        List<AccountKey> keys = api.apiKeys().get(5, TimeUnit.SECONDS);
        assertEquals(2, keys.size());
        assertTrue(keys.getFirst().available());
        assertFalse(keys.getLast().available());
        assertEquals(List.of("GET /api/account/api-keys"), requests);
        assertEquals("selected-secret", api.apiKey(GAME).get(5, TimeUnit.SECONDS));
        assertEquals("GET /api/account/api-keys/" + GAME + "/plaintext", requests.getLast());
    }

    @Test
    void mismatchedOrDeletedKeyIsRejected() throws Exception {
        AtomicInteger attempt = new AtomicInteger();
        serve(
                x -> {
                    if (attempt.getAndIncrement() == 0)
                        respond(
                                x,
                                200,
                                "{\"key_id\":\"" + OWNER + "\",\"api_key\":\"must-not-use\"}");
                    else respond(x, 404, "{\"detail\":\"not found\"}");
                });
        ExecutionException mismatch =
                assertThrows(
                        ExecutionException.class, () -> api.apiKey(GAME).get(5, TimeUnit.SECONDS));
        assertEquals("protocol_error", SatoriApi.errorCode(mismatch));
        ExecutionException deleted =
                assertThrows(
                        ExecutionException.class, () -> api.apiKey(GAME).get(5, TimeUnit.SECONDS));
        assertEquals("key_unavailable", SatoriApi.errorCode(deleted));
        assertEquals(2, attempt.get());
    }

    @Test
    void accountCookiesAndCsrfNeverEnterSeatRequests() throws Exception {
        AtomicInteger stage = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        serve(
                x -> {
                    try {
                        String path = x.getRequestURI().getPath();
                        assertNotNull(x.getRequestHeaders().getFirst("Origin"));
                        if (path.endsWith("/session")) {
                            boolean authenticated = stage.get() > 0;
                            x.getResponseHeaders()
                                    .add(
                                            "Set-Cookie",
                                            authenticated
                                                    ? "sid=private; Path=/; HttpOnly"
                                                    : "preauth=nonce; Path=/; HttpOnly");
                            respond(
                                    x,
                                    200,
                                    "{\"logged_in\":"
                                            + authenticated
                                            + ",\"csrf_token\":\"csrf\",\"user\":{"
                                            + "\"id\":\""
                                            + OWNER
                                            + "\",\"username\":\"玩家\",\"public_id\":123456789}}");
                        } else if (path.endsWith("/login")) {
                            assertTrue(
                                    x.getRequestHeaders()
                                            .getFirst("Cookie")
                                            .contains("preauth=nonce"));
                            assertEquals("csrf", x.getRequestHeaders().getFirst("X-CSRF-Token"));
                            JsonObject body =
                                    JsonParser.parseString(
                                                    new String(
                                                            x.getRequestBody().readAllBytes(),
                                                            StandardCharsets.UTF_8))
                                            .getAsJsonObject();
                            assertEquals("secret", body.get("password").getAsString());
                            stage.incrementAndGet();
                            respond(x, 200, "{}");
                        } else if (path.endsWith("/games")) {
                            assertTrue(
                                    x.getRequestHeaders()
                                            .getFirst("Cookie")
                                            .contains("sid=private"));
                            assertNull(x.getRequestHeaders().getFirst("Authorization"));
                            respond(x, 201, "{}");
                        } else {
                            assertNull(x.getRequestHeaders().getFirst("Cookie"));
                            assertEquals(
                                    "Bearer seat-only",
                                    x.getRequestHeaders().getFirst("Authorization"));
                            assertNull(x.getRequestHeaders().getFirst("X-CSRF-Token"));
                            respond(x, 200, "{}");
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        respond(x, 500, "{}");
                    }
                });
        assertEquals(
                OWNER,
                api.login("person@example.org", "secret", false).get(5, TimeUnit.SECONDS).id());
        api.create(new JsonObject()).get(5, TimeUnit.SECONDS);
        api.game(GAME, "seat-only", "GET", "/state", null).get(5, TimeUnit.SECONDS);
        assertNull(failure.get());
    }

    @Test
    void successfulLoginPostRequiresAnAuthenticatedSession() throws Exception {
        serve(
                x ->
                        respond(
                                x,
                                200,
                                x.getRequestURI().getPath().endsWith("/session")
                                        ? "{\"logged_in\":false,\"user\":null,\"csrf_token\":\"anonymous\"}"
                                        : "{}"));
        var error =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                api.login("person@example.org", "secret", false)
                                        .get(5, TimeUnit.SECONDS));
        assertEquals("login_session_missing", SatoriApi.errorCode(error));
        assertNull(api.user());
    }

    @Test
    void serverErrorDoesNotEchoCredentialAndRedirectIsNotFollowed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        serve(
                x -> {
                    calls.incrementAndGet();
                    x.getResponseHeaders().set("Location", "https://example.org/steal");
                    respond(x, 302, "{\"detail\":\"api_key=secret\"}");
                });
        var error =
                assertThrows(
                        ExecutionException.class,
                        () -> api.create(new JsonObject()).get(5, TimeUnit.SECONDS));
        assertEquals("http_302", SatoriApi.errorCode(error));
        assertEquals(1, calls.get());
    }

    @Test
    void humanDraftHasNoAiCredentialAndAiRequiresExplicitSeat() {
        List<String> seats =
                List.of(
                        OWNER.toString(),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString());
        JsonObject body = GameDraft.create(OWNER, seats, "", true, UUID.randomUUID());
        assertFalse(body.has("api_key"));
        assertEquals(0, body.getAsJsonArray("ai_seats").size());
        assertThrows(
                IllegalArgumentException.class,
                () -> GameDraft.create(OWNER, seats, "unneeded", true, UUID.randomUUID()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GameDraft.create(
                                OWNER,
                                List.of(OWNER.toString(), "AI", "AI", ""),
                                "key",
                                true,
                                UUID.randomUUID()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GameDraft.create(
                                OWNER,
                                List.of(OWNER.toString(), "AI", "AI", "AI"),
                                "",
                                true,
                                UUID.randomUUID()));
    }

    @Test
    void duplicateHumansAndMissingOwnerAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GameDraft.create(
                                OWNER,
                                List.of(OWNER.toString(), OWNER.toString(), "AI", "AI"),
                                "key",
                                true,
                                UUID.randomUUID()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GameDraft.create(
                                OWNER,
                                List.of(UUID.randomUUID().toString(), "AI", "AI", "AI"),
                                "key",
                                true,
                                UUID.randomUUID()));
    }

    static TableWall wall() {
        var raw = projection(0).getAsJsonObject("display").getAsJsonObject("wall");
        raw.add("drawn_slots", JsonParser.parseString("[54,54,54,54]"));
        return new TableWall(raw);
    }

    static JsonObject projection(int seat) {
        var result =
                JsonParser.parseString(
                                """
{"game_id":"%s","owner_user_id":"%s","status":"playing","viewer_seat":%d,
"view_version":1,"cursor":"cursor-1","decision":null,
"rules":{"rule_mode":"riichi","n_players":4,"game_length_mode":"hanchan"},
"display":{"reaction_discard":null,"seat_names":[null,null,null,null],"decision_seat":null,"drawn_seats":[false,false,false,false]},
"public":{"oya":0,"bakaze":27,"kyoku":1,"tiles_left":70,"honba":0,"kyotaku":0,
"scores":[25000,25000,25000,25000],"riichi_accepted":[false,false,false,false],
"hand_counts":[13,13,13,13],"revealed_hands":[null,null,null,null],
"discards":[[],[],[],[]],"melds":[[],[],[],[]],"dora_indicators":[4]},
"private":{"hand":[{"slot_id":"h-1","tile":"5mr","tile_id":34}]}}
"""
                                        .formatted(GAME, OWNER, seat))
                        .getAsJsonObject();
        try (var input = ProtocolTest.class.getResourceAsStream("/cloud-view.json")) {
            var fixture =
                    JsonParser.parseReader(
                                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            result.getAsJsonObject("display")
                    .add("wall", fixture.getAsJsonObject("display").get("wall").deepCopy());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (seat < 4) result.getAsJsonObject("display").add("standing_hint", JsonNull.INSTANCE);
        return result;
    }

    @Test
    void everyViewerOnlyGetsTheirOwnConcealedFacesIndependentOfCoordinates() {
        for (int viewer = 0; viewer < 4; viewer++) {
            JsonObject raw = projection(viewer);
            CloudView view = new CloudView(raw, GAME, viewer);
            raw.getAsJsonObject("private")
                    .getAsJsonArray("hand")
                    .get(0)
                    .getAsJsonObject()
                    .addProperty("tile_id", 9);
            assertEquals(List.of(34), view.hand(viewer)); // No mutable JSON aliases.
            for (int other = 0; other < 4; other++)
                if (other != viewer) assertEquals(Collections.nCopies(13, 37), view.hand(other));
            for (TableScene.Tile tile : TableScene.layout(view))
                if (tile.key().startsWith("hand") && tile.seat() != viewer)
                    assertEquals(37, tile.face());
            assertThrows(
                    IllegalArgumentException.class, () -> new CloudView(projection(0), GAME, 1));
        }
    }

    @Test
    void onlyExplicitCloudRevelationShowsOtherHands() {
        JsonObject raw = projection(0);
        raw.getAsJsonObject("public")
                .getAsJsonArray("revealed_hands")
                .set(2, JsonParser.parseString("[1,2,3]"));
        CloudView view = new CloudView(raw, GAME, 0);
        assertEquals(List.of(1, 2, 3), view.hand(2));
        assertEquals(Collections.nCopies(13, 37), view.hand(1));
    }

    @Test
    void animationConvergesExactlyToLatestProjection() {
        TableScene scene = new TableScene();
        CloudView view = new CloudView(projection(0), GAME, 0);
        scene.update(view, true, 1_000_000_000L);
        assertEquals(TableScene.layout(view), scene.sample(1_400_000_000L));
        for (TableScene.Tile tile : scene.sample(1_100_000_000L))
            if (tile.key().startsWith("hand") && tile.seat() != 0) assertEquals(37, tile.face());
    }

    @Test
    void calledTileAndAddedKanUseSameSidewaysStackGeometry() {
        JsonObject raw = projection(0);
        raw.getAsJsonObject("public")
                .getAsJsonArray("melds")
                .get(0)
                .getAsJsonArray()
                .add(
                        JsonParser.parseString(
                                "{\"type\":\"kakan\",\"tiles\":[4,4,34,4],\"called_tile\":34,\"target\":3}"));
        List<TableScene.Tile> tiles = TableScene.layout(new CloudView(raw, GAME, 0));
        TableScene.Tile called =
                tiles.stream().filter(t -> t.key().equals("meld-0-0-2")).findFirst().orElseThrow();
        TableScene.Tile added =
                tiles.stream().filter(t -> t.key().equals("meld-0-0-3")).findFirst().orElseThrow();
        assertTrue(called.sideways());
        assertTrue(added.sideways());
        assertEquals(called.x(), added.x());
        assertEquals(called.z(), added.z());
        assertEquals(TileGeometry.THICKNESS, added.y() - called.y(), .0001f);
        assertEquals(34, called.face());
        assertEquals(4, added.face());
    }

    @Test
    void uncertainCommandRetriesExactlySameIdAndPayload() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        serve(
                x -> {
                    requests.add(
                            new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(
                            x,
                            requests.size() == 1 ? 503 : 200,
                            requests.size() == 1
                                    ? "{\"error\":{\"code\":\"unavailable\"}}"
                                    : "{\"status\":\"accepted\"}");
                });
        JsonObject session =
                JsonParser.parseString("{\"seat\":0,\"seat_token\":\"private\"}").getAsJsonObject();
        try (GameConnection game =
                new GameConnection(api, GAME, session, (view, events) -> {}, status -> {})) {
            JsonObject args = new JsonObject();
            args.addProperty("ready", true);
            var receipt = game.command("PUT", "/ready", args);
            assertTrue(game.hasPending());
            receipt.get(5, TimeUnit.SECONDS);
            assertFalse(game.hasPending());
            assertEquals(requests.get(0), requests.get(1));
        }
    }
}
