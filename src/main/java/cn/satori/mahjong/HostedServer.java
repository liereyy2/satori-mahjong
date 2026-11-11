package cn.satori.mahjong;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Main-thread server coordinator. Stores credentials and transport state, never mahjong rules. */
public final class HostedServer implements AutoCloseable {
    private static final Map<MinecraftServer, HostedServer> INSTANCES = new WeakHashMap<>();

    public static HostedServer get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, HostedServer::new);
    }

    public static void stop(MinecraftServer server) {
        var value = INSTANCES.remove(server);
        if (value != null) value.close();
    }

    final MinecraftServer server;
    final SatoriApi api;
    final Map<UUID, HostedTableBridge> tables = new HashMap<>();
    private ControlConfig config;
    private final HostedReleaseQueue releases;
    private final HostedTableDirectory directory;
    private boolean releaseBusy;
    private long nextRelease;
    private List<AccountKey> keys = List.of();
    private boolean accountBusy, closed;
    private String accountError = "";
    private long nextCheck;
    private final AccountCredits credits;

    private HostedServer(MinecraftServer server) {
        this(server, new SatoriApi());
    }

    HostedServer(MinecraftServer server, SatoriApi api) {
        this.server = server;
        this.api = api;
        credits =
                new AccountCredits(
                        api,
                        server::execute,
                        failure -> {
                            if (SatoriApi.accountExpired(failure)) expire();
                        });
        try {
            directory =
                    new HostedTableDirectory(
                            server.getWorldPath(
                                            net.minecraft.world.level.storage.LevelResource.ROOT)
                                    .resolve("data/satori-tables.json"));
            for (var entry : directory.entries()) {
                var dimension =
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION,
                                net.minecraft.resources.Identifier.parse(entry.dimension()));
                tables.put(
                        entry.id(),
                        new HostedTableBridge(
                                this,
                                entry.id(),
                                dimension,
                                new BlockPos(entry.x(), entry.y(), entry.z()),
                                true));
            }
            releases =
                    new HostedReleaseQueue(
                            SatoriMahjong.configPath().resolve("satori-table-releases.json"));
            for (UUID id : releases.pending()) {
                var table = tables.get(id);
                if (table != null) table.retire();
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try {
            config =
                    new ControlConfig(
                            SatoriMahjong.configPath().resolve("satori-mahjong-control.json"));
            if (!config.username().isBlank())
                account(
                        api.login(config.username(), config.password(), false),
                        user -> loadKeys(),
                        ignored -> {});
        } catch (Exception error) {
            accountError = "invalid_account_config";
        }
    }

    public void tick() {
        if (closed) return;
        if (api.user() != null && !accountBusy && System.nanoTime() >= nextCheck) {
            nextCheck = System.nanoTime() + 60_000_000_000L;
            account(
                    api.session(),
                    user -> {
                        if (user == null) expire();
                    },
                    ignored -> {});
        }
        for (var table : List.copyOf(tables.values())) table.tick();
        releaseTick();
    }

    void locate(
            UUID id,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            BlockPos pos,
            String game) {
        if (game.isEmpty()) directory.remove(id);
        else
            directory.put(
                    new HostedTableDirectory.Entry(
                            id,
                            dimension.identifier().toString(),
                            pos.getX(),
                            pos.getY(),
                            pos.getZ()));
    }

    public void observe(TableBlockEntity block) {
        if (!block.gameId().isEmpty() && !releases.pending().contains(block.cloudTableId())) {
            locate(
                    block.cloudTableId(),
                    block.getLevel().dimension(),
                    block.getBlockPos(),
                    block.gameId());
            tables.computeIfAbsent(block.cloudTableId(), id -> new HostedTableBridge(this, block));
        }
    }

    public void destroyTable(TableBlockEntity block) {
        UUID id = block.cloudTableId();
        releases.add(id);
        var bridge = tables.get(id);
        if (bridge != null) bridge.retire();
        nextRelease = 0;
    }

    public void occupantsChanged(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            BlockPos pos) {
        var level = server.getLevel(dimension);
        if (level != null && level.getBlockEntity(pos) instanceof TableBlockEntity block) {
            var bridge = tables.get(block.cloudTableId());
            if (bridge != null) bridge.occupantsChanged();
        }
    }

    private boolean hasActiveTables() {
        return !releases.pending().isEmpty()
                || tables.values().stream().anyMatch(HostedTableBridge::active);
    }

    private void releaseTick() {
        if (!authenticated() || releaseBusy || System.nanoTime() < nextRelease) return;
        for (UUID id : releases.pending()) {
            var bridge = tables.get(id);
            if (bridge != null && !bridge.retiredIdle()) continue;
            var body = new JsonObject();
            body.addProperty("instance_id", instance().toString());
            releaseBusy = true;
            api.hosted(id, "DELETE", "", body, null)
                    .whenComplete(
                            (value, failure) ->
                                    server.execute(
                                            () -> {
                                                if (closed) return;
                                                releaseBusy = false;
                                                if (failure == null) {
                                                    releases.acknowledge(id);
                                                    tables.remove(id);
                                                    directory.remove(id);
                                                    nextRelease = 0;
                                                } else {
                                                    nextRelease =
                                                            System.nanoTime() + 5_000_000_000L;
                                                    org.slf4j.LoggerFactory.getLogger(
                                                                    HostedServer.class)
                                                            .warn(
                                                                    "Cloud table release pending:"
                                                                            + " table={} error={}",
                                                                    id,
                                                                    SatoriApi.errorCode(failure));
                                                }
                                            }));
            return;
        }
    }

    boolean closed() {
        return closed;
    }

    boolean authenticated() {
        return api.user() != null && config != null;
    }

    boolean aiEnabled() {
        return config != null
                && config.keyId() != null
                && keys.stream().anyMatch(k -> k.id().equals(config.keyId()) && k.available());
    }

    UUID instance() {
        return config.instance;
    }

    String error() {
        return accountError.isEmpty()
                ? accountBusy ? "control_connecting" : "control_required"
                : accountError;
    }

    CompletableFuture<String> key() {
        return aiEnabled()
                ? api.apiKey(config.keyId())
                : CompletableFuture.failedFuture(
                        new SatoriApi.ApiException(422, "ai_key_required"));
    }

    private void expire() {
        credits.reset();
        api.forget();
        keys = List.of();
        accountError = "control_required";
        try {
            config.clear();
        } catch (Exception e) {
            accountError = "account_config_error";
        }
        tables.values().forEach(HostedTableBridge::forgetSessions);
    }

    private <T> void account(
            CompletableFuture<T> request, Consumer<T> success, Consumer<String> done) {
        accountBusy = true;
        request.whenComplete(
                (result, error) ->
                        server.execute(
                                () -> {
                                    if (closed) return;
                                    accountBusy = false;
                                    if (error == null) {
                                        accountError = "";
                                        success.accept(result);
                                        done.accept(accountError);
                                    } else {
                                        accountError = SatoriApi.errorCode(error);
                                        if (SatoriApi.accountExpired(error)) expire();
                                        done.accept(accountError);
                                    }
                                }));
    }

    private void loadKeys() {
        account(api.apiKeys(), value -> keys = value, ignored -> {});
    }

    private boolean permitted(ServerPlayer p, BlockPos pos) {
        return (server.isSingleplayerOwner(p.nameAndId())
                        || p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                && p.distanceToSqr(pos.getCenter()) <= 64
                && p.level().hasChunkAt(pos)
                && p.level().getBlockState(pos).is(SatoriMahjong.control());
    }

    public void openControl(ServerPlayer p, BlockPos pos) {
        if (!permitted(p, pos)) {
            p.displayClientMessage(Component.translatable("satori.control.denied"), true);
            return;
        }
        controlState(p, pos, true, "");
    }

    private void controlState(ServerPlayer p, BlockPos pos, boolean open, String error) {
        if (!permitted(p, pos)) return;
        if (authenticated() && !accountBusy) credits.refresh();
        var json = new JsonObject();
        json.addProperty("logged_in", authenticated());
        json.addProperty("busy", accountBusy);
        json.addProperty("username", config == null ? "" : config.username());
        json.addProperty("name", api.user() == null ? "" : api.user().name());
        json.addProperty("credits", authenticated() ? credits.value() : null);
        json.addProperty("credits_error", authenticated() ? credits.error() : "");
        json.addProperty("error", error.isEmpty() ? accountError : error);
        json.addProperty(
                "key_id",
                config == null || config.keyId() == null ? "" : config.keyId().toString());
        var list = new JsonArray();
        for (var key : keys) {
            var item = new JsonObject();
            item.addProperty("id", key.id().toString());
            item.addProperty("name", key.name());
            item.addProperty("masked", key.masked());
            item.addProperty("available", key.available());
            list.add(item);
        }
        json.add("keys", list);
        var matches = new JsonArray();
        tables.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            var row = entry.getValue().overview();
                            if (row != null) matches.add(row);
                        });
        json.add("matches", matches);
        SatoriMahjong.hostedState(
                p,
                new HostedPackets.State(p.level().dimension(), pos, true, open, json.toString()));
    }

    boolean allowsSeat(ServerPlayer p, TableBlockEntity block, int chair) {
        if (releases.pending().contains(block.cloudTableId())) {
            p.displayClientMessage(
                    Component.translatable("satori.message.table_release_pending"), true);
            return false;
        }
        var table = tables.get(block.cloudTableId());
        if (table == null && !block.gameId().isEmpty()) {
            table = new HostedTableBridge(this, block);
            tables.put(block.cloudTableId(), table);
        }
        return table == null || table.allowsSeat(p, chair);
    }

    public void openTable(ServerPlayer p, TableBlockEntity block) {
        if (releases.pending().contains(block.cloudTableId())) return;
        block.setChanged();
        var table =
                tables.computeIfAbsent(
                        block.cloudTableId(), id -> new HostedTableBridge(this, block));
        table.open(p);
    }

    public void request(ServerPlayer p, HostedPackets.Request packet) {
        if (!p.level().dimension().equals(packet.dimension())
                || !p.level().hasChunkAt(packet.pos())) return;
        JsonObject data;
        try {
            data = JsonParser.parseString(packet.json()).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (packet.control()) {
            if (!permitted(p, packet.pos())) {
                p.displayClientMessage(Component.translatable("satori.control.denied"), true);
                var denied = new JsonObject();
                denied.addProperty("denied", true);
                SatoriMahjong.hostedState(
                        p,
                        new HostedPackets.State(
                                p.level().dimension(),
                                packet.pos(),
                                true,
                                false,
                                denied.toString()));
                return;
            }
            control(p, packet.pos(), data);
            return;
        }
        if (!(p.getVehicle() instanceof TableSeatEntity seat)
                || !seat.center().equals(packet.pos())
                || !(p.level().getBlockEntity(packet.pos()) instanceof TableBlockEntity block))
            return;
        tables.computeIfAbsent(block.cloudTableId(), id -> new HostedTableBridge(this, block))
                .request(p, data);
    }

    private void control(ServerPlayer p, BlockPos pos, JsonObject data) {
        try {
            String kind = data.get("kind").getAsString();
            if (kind.equals("refresh")) {
                controlState(p, pos, false, "");
                return;
            }
            if (kind.equals("highlight")) {
                var table = tables.get(UUID.fromString(data.get("table_id").getAsString()));
                controlState(
                        p,
                        pos,
                        false,
                        table != null && table.highlight(p) ? "" : "table_highlight_unavailable");
                return;
            }
            if (accountBusy || config == null) {
                controlState(p, pos, false, error());
                return;
            }
            Consumer<String> done = error -> controlState(p, pos, false, error);
            switch (kind) {
                case "login" -> {
                    if (authenticated() && hasActiveTables()) {
                        done.accept("control_active_games");
                        return;
                    }
                    String email = data.get("username").getAsString(),
                            secret = data.get("password").getAsString();
                    if (email.length() > 254 || secret.isEmpty() || secret.length() > 1024)
                        throw new IllegalArgumentException();
                    credits.reset();
                    account(
                            api.login(email, secret, false),
                            user -> {
                                try {
                                    config.credentials(email, secret);
                                } catch (Exception e) {
                                    api.forget();
                                    accountError = "account_config_error";
                                    return;
                                }
                                loadKeys();
                            },
                            done);
                }
                case "keys" -> account(api.apiKeys(), value -> keys = value, done);
                case "select" -> {
                    if (!authenticated()) throw new IllegalArgumentException();
                    String id = data.get("key_id").getAsString();
                    UUID selected = id.isEmpty() ? null : UUID.fromString(id);
                    if (selected != null
                            && !keys.stream()
                                    .anyMatch(k -> k.id().equals(selected) && k.available())) {
                        done.accept("key_unavailable");
                        return;
                    }
                    config.select(selected);
                    done.accept("");
                }
                case "logout" -> {
                    if (hasActiveTables()) {
                        done.accept("control_active_games");
                        return;
                    }
                    credits.reset();
                    account(api.logout(), ignored -> expire(), done);
                }
                default -> done.accept("invalid_command");
            }
        } catch (Exception e) {
            controlState(p, pos, false, "invalid_command");
        }
    }

    @Override
    public void close() {
        closed = true;
        credits.reset();
        tables.clear();
        api.close();
    }
}
