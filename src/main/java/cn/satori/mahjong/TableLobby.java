package cn.satori.mahjong;

import cn.satori.mahjong.protocol.LobbyRoster;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Ephemeral public seating metadata. Cloud sessions alone authenticate game seats. */
public final class TableLobby {
    private final TableBlockEntity table;
    private final LobbyRoster roster = new LobbyRoster();
    private List<TablePackets.Occupant> last = List.of();
    private String lastGame = "";
    private UUID lastHost;

    TableLobby(TableBlockEntity table) {
        this.table = table;
    }

    public boolean canManage(UUID player) {
        return roster.host() == null ? table.canEdit(player) : roster.host().equals(player);
    }

    private Map<Integer, ServerPlayer> players() {
        var result = new TreeMap<Integer, ServerPlayer>();
        var level = table.getLevel();
        if (level == null || level.isClientSide()) return result;
        for (var seat :
                level.getEntitiesOfClass(
                        TableSeatEntity.class, new AABB(table.getBlockPos()).inflate(3))) {
            if (!seat.center().equals(table.getBlockPos()) || seat.isRemoved()) continue;
            if (seat.getFirstPassenger() instanceof ServerPlayer player)
                result.put(seat.direction(), player);
        }
        return result;
    }

    public static void announce(ServerPlayer player, TablePackets.Presence packet) {
        var level = player.level();
        if (!level.dimension().equals(packet.dimension())
                || !(player.getVehicle() instanceof TableSeatEntity seat)
                || !seat.center().equals(packet.pos())
                || !level.hasChunkAt(packet.pos())
                || !(level.getBlockEntity(packet.pos()) instanceof TableBlockEntity table)) return;
        table.lobby.claim(player, packet.userId(), packet.name());
    }

    private void claim(ServerPlayer player, String userId, String name) {
        refresh();
        if (!roster.claim(player.getUUID(), userId, name)) {
            player.displayClientMessage(Component.translatable("satori.lobby.duplicate"), true);
            send(player);
            return;
        }
        refresh();
    }

    public void refresh() {
        var seated = players();
        var physical = new HashMap<Integer, LobbyRoster.Player>();
        seated.forEach(
                (direction, player) ->
                        physical.put(
                                direction,
                                new LobbyRoster.Player(
                                        player.getUUID(), player.getGameProfile().name())));
        roster.update(physical);
        var occupants = new ArrayList<TablePackets.Occupant>();
        for (var seat : roster.seats()) {
            occupants.add(
                    new TablePackets.Occupant(
                            seat.minecraftName(), seat.userId(), seat.name(), seat.host()));
        }
        if (last.equals(occupants)
                && lastGame.equals(table.gameId())
                && Objects.equals(roster.host(), lastHost)) return;
        last = List.copyOf(occupants);
        lastGame = table.gameId();
        lastHost = roster.host();
        seated.values().forEach(this::send);
    }

    public void send(ServerPlayer player) {
        if (last.size() != 4) refresh();
        SatoriMahjong.tableLobby(
                player,
                new TablePackets.Lobby(
                        player.level().dimension(),
                        table.getBlockPos(),
                        table.gameId(),
                        canManage(player.getUUID()),
                        last));
    }
}
