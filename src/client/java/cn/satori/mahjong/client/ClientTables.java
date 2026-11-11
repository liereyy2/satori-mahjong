package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;

/** Main-thread table sessions, indexed by dimension and immutable controller position. */
public final class ClientTables {
    record Address(ResourceKey<Level> dimension, BlockPos pos) {
        Address {
            pos = pos.immutable();
        }
    }

    private final ClientIdentity identity;
    private final Map<Address, ClientController> tables = new LinkedHashMap<>();
    private final ClientController accountPage;
    final WorldTables worldTables = new WorldTables();
    private TablePackets.Presence announced;
    private int announcedVehicle = -1;

    public ClientTables(AccountConfig config) {
        this(new SatoriApi(), config);
    }

    ClientTables(SatoriApi api, AccountConfig config) {
        identity = new ClientIdentity(api, config);
        accountPage = new ClientController(identity);
    }

    public ClientController active() {
        return identity.focused;
    }

    public void hosted(HostedPackets.State packet) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(packet.dimension())) return;
        var json = com.google.gson.JsonParser.parseString(packet.json()).getAsJsonObject();
        if (json.has("kind")) {
            worldTables.accept(packet, json);
            return;
        }
        if (packet.control()) {
            if (json.has("denied")) {
                if (mc.screen instanceof ControlScreen) mc.setScreen(null);
                return;
            }
            if (packet.open()) mc.setScreen(new ControlScreen(accountPage, packet.pos(), json));
            else if (mc.screen instanceof ControlScreen screen
                    && screen.position.equals(packet.pos())) screen.update(json);
            return;
        }
        var address = new Address(packet.dimension(), packet.pos());
        var controller = tables.computeIfAbsent(address, ignored -> new ClientController(identity));
        if (controller.hosted == null) controller.hosted = new HostedClient(controller);
        if (packet.open()) identity.focused = controller;
        controller.hosted.accept(packet, json);
    }

    public void open(TablePackets.Open packet) {
        var level = Objects.requireNonNull(Minecraft.getInstance().level);
        var address = new Address(level.dimension(), packet.pos());
        var controller = tables.computeIfAbsent(address, ignored -> new ClientController(identity));
        identity.focused = controller;
        controller.open(packet);
        announced = null;
    }

    public void linked(TablePackets.Linked packet) {
        var controller = tables.get(new Address(packet.dimension(), packet.pos()));
        if (controller != null) controller.acknowledgeLink(packet);
    }

    public void lobby(TablePackets.Lobby packet) {
        var controller = tables.get(new Address(packet.dimension(), packet.pos()));
        if (controller != null && controller.hosted == null) controller.updateLobby(packet);
    }

    public ClientController forTable(TableBlockEntity table) {
        if (table.getLevel() == null) return null;
        return tables.get(new Address(table.getLevel().dimension(), table.getBlockPos()));
    }

    public void tick() {
        for (var controller : List.copyOf(identity.controllers)) controller.tick();
        var mc = Minecraft.getInstance();
        if (mc.level == null
                || mc.player == null
                || !(mc.player.getVehicle() instanceof TableSeatEntity seat)) {
            announced = null;
            announcedVehicle = -1;
            return;
        }
        if (!tables.containsKey(new Address(mc.level.dimension(), seat.center()))) return;
        if (tables.get(new Address(mc.level.dimension(), seat.center())).hosted != null) return;
        var user = identity.api.user();
        var packet =
                new TablePackets.Presence(
                        mc.level.dimension(),
                        seat.center(),
                        user == null ? "" : user.id().toString(),
                        user == null ? "" : user.name());
        if (!packet.equals(announced) || seat.getId() != announcedVehicle) {
            announced = packet;
            announcedVehicle = seat.getId();
            SatoriClient.sendPresence(packet);
        }
    }

    public void openAccount() {
        var mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("satori.hosted.config_help"),
                    false);
    }

    public void disconnect() {
        announced = null;
        announcedVehicle = -1;
        active().disconnect();
        tables.clear();
        worldTables.clear();
        identity.controllers.clear();
        identity.controllers.add(accountPage);
        identity.focused = accountPage;
    }
}
