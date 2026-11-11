package cn.satori.mahjong;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** One serialized cloud transport per physical table; all readiness decisions come from Rust. */
final class HostedTableBridge {
    private final HostedServer host;
    private final UUID id;
    private final ResourceKey<Level> dimension;
    private final BlockPos pos;
    private final Deque<Intent> pending = new ArrayDeque<>();
    private final Map<UUID, JsonObject> sessions = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();
    private JsonObject state;
    private String synced = "", error = "";
    private boolean busy, needsRefresh, retired, rosterDirty;
    private final boolean restoringGame;
    private long nextPoll;
    private JsonObject publicView;
    private String renderCursor;
    private final Map<UUID, SentView> sentViews = new HashMap<>();

    private record SentView(int seat, long revision, String cursor) {}

    private record Intent(UUID player, JsonObject body) {}

    HostedTableBridge(HostedServer host, TableBlockEntity block) {
        this(
                host,
                block.cloudTableId(),
                block.getLevel().dimension(),
                block.getBlockPos(),
                !block.gameId().isEmpty());
    }

    HostedTableBridge(
            HostedServer host,
            UUID id,
            ResourceKey<Level> dimension,
            BlockPos pos,
            boolean restoringGame) {
        this.host = host;
        this.restoringGame = restoringGame;
        this.id = id;
        this.dimension = dimension;
        this.pos = pos.immutable();
    }

    JsonObject overview() {
        if (retired
                || state == null
                || !Set.of("playing", "paused").contains(state.get("phase").getAsString()))
            return null;
        var row = new JsonObject();
        row.addProperty("table_id", id.toString());
        row.addProperty("game_id", gameId());
        row.addProperty("dimension", dimension.identifier().toString());
        row.addProperty("x", pos.getX());
        row.addProperty("y", pos.getY());
        row.addProperty("z", pos.getZ());
        row.add("phase", state.get("phase"));
        var seats = publicView.getAsJsonObject("public").getAsJsonArray("seats").deepCopy();
        var names = publicView.getAsJsonObject("display").getAsJsonArray("seat_names");
        for (var raw : seats) {
            var seat = raw.getAsJsonObject();
            if (seat.get("kind").getAsString().equals("human"))
                seat.add("name", names.get(seat.get("seat").getAsInt()));
        }
        row.add("seats", seats);
        return row;
    }

    boolean highlight(ServerPlayer player) {
        if (overview() == null || !player.level().dimension().equals(dimension)) return false;
        var level = host.server.getLevel(dimension);
        if (level == null
                || !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof TableBlockEntity table)
                || !table.cloudTableId().equals(id)) return false;
        var packet = new JsonObject();
        packet.addProperty("kind", "highlight");
        packet.addProperty("table_id", id.toString());
        SatoriMahjong.hostedState(
                player, new HostedPackets.State(dimension, pos, false, false, packet.toString()));
        return true;
    }

    boolean active() {
        return busy
                || state == null && restoringGame
                || state != null && !state.get("game_id").isJsonNull();
    }

    void retire() {
        retired = true;
        pending.clear();
        sessions.clear();
        opening.clear();
    }

    boolean retiredIdle() {
        return retired && !busy;
    }

    void occupantsChanged() {
        rosterDirty = true;
        nextPoll = 0;
        if (state != null) broadcast();
    }

    boolean allowsSeat(ServerPlayer player, int chair) {
        if (state == null && restoringGame) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("satori.hosted.loading"),
                    true);
            return false;
        }
        if (gameId().isEmpty()) return true;
        for (var row : state.getAsJsonArray("seats")) {
            var seat = row.getAsJsonObject();
            if (seat.get("seat").getAsInt() == TableOrientation.cloudSeat(chair)
                    && seat.get("kind").getAsString().equals("ai")) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("satori.hosted.ai_seat"),
                        true);
                return false;
            }
        }
        return true;
    }

    private Map<Integer, ServerPlayer> players() {
        var result = new TreeMap<Integer, ServerPlayer>();
        var level = host.server.getLevel(dimension);
        if (level == null
                || !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof TableBlockEntity table)
                || !table.cloudTableId().equals(id)) return result;
        for (var seat : level.getEntitiesOfClass(TableSeatEntity.class, new AABB(pos).inflate(3)))
            if (seat.center().equals(pos)
                    && !seat.isRemoved()
                    && seat.getFirstPassenger() instanceof ServerPlayer p)
                result.put(TableOrientation.cloudSeat(seat.direction()), p);
        return result;
    }

    private JsonArray roster(Map<Integer, ServerPlayer> players) {
        var rows = new JsonArray();
        players.forEach(
                (seat, p) -> {
                    var row = new JsonObject();
                    row.addProperty("player_id", p.getUUID().toString());
                    row.addProperty("name", p.getGameProfile().name());
                    row.addProperty("seat", seat);
                    rows.add(row);
                });
        return rows;
    }

    void open(ServerPlayer p) {
        opening.add(p.getUUID());
        send(p, true, null);
        occupantsChanged();
    }

    void forgetSessions() {
        sessions.clear();
        for (var p : players().values()) send(p, false, null);
    }

    private boolean connected(UUID player) {
        return players().values().stream().anyMatch(p -> p.getUUID().equals(player));
    }

    void request(ServerPlayer p, JsonObject data) {
        if (!connected(p.getUUID())) return;
        String kind;
        try {
            kind = data.get("kind").getAsString();
        } catch (RuntimeException e) {
            return;
        }
        if (kind.equals("session")) {
            sessions.remove(p.getUUID());
            nextPoll = 0;
            return;
        }
        if (!Set.of("ready", "ai", "length", "rules", "confirm", "resume", "feedback")
                        .contains(kind)
                || pending.size() >= 32) return;
        try {
            switch (kind) {
                case "ready" -> requireBoolean(data, "ready");
                case "ai" -> {
                    requireBoolean(data, "enabled");
                    int seat = data.get("seat").getAsInt();
                    if (seat < 0 || seat > 3) throw new IllegalArgumentException();
                }
                case "length" -> {
                    if (!Set.of("east", "hanchan", "4", "8", "16")
                            .contains(data.get("length").getAsString()))
                        throw new IllegalArgumentException();
                }
                case "rules" -> {
                    if (!Set.of("riichi", "sichuan").contains(data.get("rule_mode").getAsString()))
                        throw new IllegalArgumentException();
                }
                case "confirm" -> UUID.fromString(data.get("game_id").getAsString());
                case "feedback" -> {
                    if (!data.get("subject").isJsonPrimitive()
                            || !data.get("description").isJsonPrimitive()
                            || !data.get("context").isJsonObject())
                        throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException invalid) {
            error = "invalid_command";
            send(p, false, null);
            return;
        }
        var body = new JsonObject();
        body.addProperty("kind", kind);
        body.addProperty("command_id", UUID.randomUUID().toString());
        body.addProperty("player_id", p.getUUID().toString());
        // The client can choose an action, never a player identity or occupancy list.
        for (String field :
                List.of(
                        "ready",
                        "seat",
                        "enabled",
                        "length",
                        "rule_mode",
                        "game_id",
                        "subject",
                        "description",
                        "context"))
            if (data.has(field)) body.add(field, data.get(field).deepCopy());
        pending.add(new Intent(p.getUUID(), body));
        nextPoll = 0;
    }

    private static void requireBoolean(JsonObject body, String name) {
        if (!body.has(name)
                || !body.get(name).isJsonPrimitive()
                || !body.getAsJsonPrimitive(name).isBoolean()) throw new IllegalArgumentException();
    }

    void tick() {
        if (retired || busy || System.nanoTime() < nextPoll) return;
        nextPoll = System.nanoTime() + 1_000_000_000L;
        var people = players();
        sessions.keySet()
                .removeIf(
                        player ->
                                people.entrySet().stream()
                                        .noneMatch(
                                                e ->
                                                        e.getValue().getUUID().equals(player)
                                                                && e.getKey()
                                                                        == sessions.get(player)
                                                                                .getAsJsonObject(
                                                                                        "self_session")
                                                                                .get("seat")
                                                                                .getAsInt()));
        if (!host.authenticated()) {
            for (var p : people.values()) send(p, false, null);
            return;
        }
        if (state == null) {
            var body = new JsonObject();
            body.addProperty("instance_id", host.instance().toString());
            run(request("PUT", "", body), this::accept);
            return;
        }
        if (needsRefresh) {
            run(
                    request("GET", "", null),
                    value -> {
                        needsRefresh = false;
                        synced = "";
                        accept(value);
                    });
            return;
        }
        JsonArray current = roster(people);
        rosterDirty = false;
        if (!current.toString().equals(synced)) {
            var body = command("sync");
            body.add("players", current);
            String sent = current.toString();
            run(
                    request("POST", "/commands", body),
                    value -> {
                        synced = sent;
                        accept(value);
                    });
            return;
        }
        while (!pending.isEmpty()) {
            var intent = pending.removeFirst();
            if (!connected(intent.player)) continue;
            submit(intent);
            return;
        }
        var joining = sessionNeeded(people);
        if (joining != null) {
            var body = new JsonObject();
            body.addProperty("player_id", joining.getUUID().toString());
            run(
                    request("POST", "/sessions", body),
                    value -> {
                        int issuedSeat =
                                value.getAsJsonObject("self_session").get("seat").getAsInt();
                        var occupant = players().get(issuedSeat);
                        if (occupant != null
                                && occupant.getUUID().equals(joining.getUUID())
                                && value.get("game_id").getAsString().equals(gameId())) {
                            sessions.put(joining.getUUID(), value);
                            send(joining, false, null);
                        }
                    });
            return;
        }
        if (!people.isEmpty() || !gameId().isEmpty()) run(request("GET", "", null), this::accept);
    }

    private ServerPlayer sessionNeeded(Map<Integer, ServerPlayer> people) {
        if (gameId().isEmpty()) return null;
        for (var player : people.values()) {
            var member = member(player.getUUID());
            if (member != null
                    && !member.get("confirmed").getAsBoolean()
                    && !sessions.containsKey(player.getUUID())) return player;
        }
        return null;
    }

    /** Only idle observation is polled. Pending transport work advances on the next server tick. */
    private boolean workWaiting() {
        if (state == null || needsRefresh || rosterDirty || !pending.isEmpty()) return true;
        var people = players();
        return !roster(people).toString().equals(synced) || sessionNeeded(people) != null;
    }

    private CompletableFuture<JsonObject> request(String method, String endpoint, JsonObject body) {
        return host.api.hosted(id, method, endpoint, body, renderCursor);
    }

    private JsonObject command(String kind) {
        var body = new JsonObject();
        body.addProperty("kind", kind);
        body.addProperty("command_id", UUID.randomUUID().toString());
        body.add("revision", state.get("revision"));
        return body;
    }

    private void submit(Intent intent) {
        var body = intent.body.deepCopy();
        body.add("revision", state.get("revision"));
        String kind = body.get("kind").getAsString();
        if (kind.equals("feedback")) {
            try {
                var report = body.getAsJsonObject("context");
                String game = gameId();
                var metadata =
                        new BugReport(
                                report.get("version").getAsString(),
                                report.get("loader").getAsString(),
                                "1.21.11",
                                game.isEmpty() ? null : UUID.fromString(game));
                String description =
                        body.get("description").getAsString() + "\nMC player: " + intent.player;
                run(
                        host.api.reportBug(
                                metadata, body.get("subject").getAsString(), description),
                        ticket -> {
                            var response = new JsonObject();
                            response.addProperty("ticket_id", ticket.toString());
                            var p = host.server.getPlayerList().getPlayer(intent.player);
                            if (p != null && connected(intent.player)) send(p, false, response);
                        });
            } catch (RuntimeException e) {
                error = "feedback_invalid";
                broadcast();
            }
            return;
        }
        if (kind.equals("resume")) {
            var resume = new JsonObject();
            resume.add("command_id", body.get("command_id"));
            run(
                    host.key()
                            .thenCompose(
                                    key -> {
                                        resume.addProperty("api_key", key);
                                        return request("POST", "/resume", resume);
                                    }),
                    ignored -> {
                        nextPoll = 0;
                    });
            return;
        }
        boolean needsKey =
                kind.equals("ready")
                        && body.has("ready")
                        && body.get("ready").getAsBoolean()
                        && state.getAsJsonArray("seats").asList().stream()
                                .anyMatch(
                                        s ->
                                                s.getAsJsonObject()
                                                        .get("kind")
                                                        .getAsString()
                                                        .equals("ai"));
        if (kind.equals("ai") && body.get("enabled").getAsBoolean() && !host.aiEnabled()) {
            error = "ai_key_required";
            broadcast();
            return;
        }
        CompletableFuture<JsonObject> request =
                needsKey
                        ? host.key()
                                .thenCompose(
                                        key -> {
                                            body.addProperty("api_key", key);
                                            return request("POST", "/commands", body);
                                        })
                        : request("POST", "/commands", body);
        run(request, this::accept);
    }

    private <T> void run(CompletableFuture<T> future, Consumer<T> success) {
        busy = true;
        future.whenComplete(
                (value, failure) ->
                        host.server.execute(
                                () -> {
                                    if (host.closed()) return;
                                    busy = false;
                                    if (retired) return;
                                    nextPoll = rosterDirty ? 0 : System.nanoTime() + 1_000_000_000L;
                                    if (failure == null) {
                                        error = "";
                                        success.accept(value);
                                        if (workWaiting()) nextPoll = 0;
                                    } else {
                                        error = SatoriApi.errorCode(failure);
                                        nextPoll = System.nanoTime() + 2_000_000_000L;
                                        if (error.equals("table_revision_conflict"))
                                            needsRefresh = true;
                                        broadcast();
                                    }
                                }));
    }

    private void accept(JsonObject value) {
        String old = gameId();
        String deleted =
                value.has("deleted_game_id") && !value.get("deleted_game_id").isJsonNull()
                        ? value.get("deleted_game_id").getAsString()
                        : "";
        boolean removed =
                !deleted.isEmpty()
                        && (deleted.equals(old)
                                || state == null && value.get("game_id").isJsonNull());
        state = value;
        var render = value.get("render_stream");
        renderCursor =
                render == null || render.isJsonNull()
                        ? null
                        : render.getAsJsonObject().get("cursor").getAsString();
        if (removed) {
            pending.clear();
            error = "game_deleted";
            for (var player : players().values())
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "satori.message.game_deleted"),
                        false);
        }
        if (!old.equals(gameId())) {
            sessions.clear();
            publicView = null;
        }
        if (value.has("public_view"))
            publicView =
                    value.get("public_view").isJsonNull()
                            ? null
                            : value.getAsJsonObject("public_view").deepCopy();
        if (!gameId().isEmpty())
            for (var entry : players().entrySet())
                for (var raw : state.getAsJsonArray("seats")) {
                    var seat = raw.getAsJsonObject();
                    if (seat.get("seat").getAsInt() == entry.getKey()
                            && seat.get("kind").getAsString().equals("ai")) {
                        sessions.remove(entry.getValue().getUUID());
                        entry.getValue().stopRiding();
                        entry.getValue()
                                .displayClientMessage(
                                        net.minecraft.network.chat.Component.translatable(
                                                "satori.hosted.ai_seat"),
                                        true);
                    }
                }
        host.locate(id, dimension, pos, gameId());
        var level = host.server.getLevel(dimension);
        if (level != null
                && level.hasChunkAt(pos)
                && level.getBlockEntity(pos) instanceof TableBlockEntity block
                && block.cloudTableId().equals(id)
                && !block.gameId().equals(gameId())) block.link(gameId());
        broadcast();
    }

    private String gameId() {
        return state == null || state.get("game_id").isJsonNull()
                ? ""
                : state.get("game_id").getAsString();
    }

    private JsonObject member(UUID player) {
        if (state != null)
            for (var row : state.getAsJsonArray("seats")) {
                var p = row.getAsJsonObject();
                if (p.has("player_id")
                        && p.get("player_id").getAsString().equals(player.toString())) return p;
            }
        return null;
    }

    private void broadcast() {
        var seated = players();
        for (var p : seated.values()) send(p, false, null);
        var nearby = new HashSet<UUID>();
        for (var p : host.server.getPlayerList().getPlayers()) {
            if (!p.level().dimension().equals(dimension)
                    || p.distanceToSqr(pos.getCenter()) > 64 * 64) continue;
            nearby.add(p.getUUID());
            int physicalSeat = -1;
            for (var entry : seated.entrySet())
                if (entry.getValue().getUUID().equals(p.getUUID())) physicalSeat = entry.getKey();
            var access = HostedTableViews.access(state, p.getUUID(), physicalSeat);
            int role = access.role();
            var last = sentViews.get(p.getUUID());
            if (last != null
                    && last.seat == role
                    && last.revision == access.revision()
                    && Objects.equals(last.cursor, renderCursor)) continue;
            boolean reset = last == null || last.seat != role || last.revision != access.revision();
            for (var packet : HostedTableViews.packets(state, role, reset)) {
                packet.addProperty("kind", "table_view");
                packet.addProperty("table_id", id.toString());
                SatoriMahjong.hostedState(
                        p,
                        new HostedPackets.State(dimension, pos, false, false, packet.toString()));
            }
            sentViews.put(p.getUUID(), new SentView(role, access.revision(), renderCursor));
        }
        sentViews.keySet().retainAll(nearby);
    }

    private void send(ServerPlayer p, boolean open, JsonObject reply) {
        var packet = new JsonObject();
        packet.addProperty("configured", host.authenticated());
        packet.addProperty("ai_enabled", host.aiEnabled());
        packet.addProperty("error", host.authenticated() ? error : host.error());
        packet.addProperty("table_id", id.toString());
        packet.addProperty("player_id", p.getUUID().toString());
        if (state != null) packet.add("table", HostedTableViews.publicState(state));
        if (sessions.containsKey(p.getUUID()))
            packet.add("session", sessions.get(p.getUUID()).deepCopy());
        if (reply != null) packet.add("reply", reply);
        boolean requested = opening.remove(p.getUUID());
        SatoriMahjong.hostedState(
                p,
                new HostedPackets.State(
                        dimension, pos, false, open || requested, packet.toString()));
    }
}
