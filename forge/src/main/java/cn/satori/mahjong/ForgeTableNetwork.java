package cn.satori.mahjong;

import cn.satori.mahjong.client.SatoriClient;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

/**
 * Required, versioned Forge channel. Public metadata and recipient-scoped state use distinct
 * messages.
 */
public final class ForgeTableNetwork {
    private static final SimpleChannel CHANNEL =
            ChannelBuilder.named(SatoriMahjong.id("table"))
                    .networkProtocolVersion(9)
                    .simpleChannel();

    private ForgeTableNetwork() {}

    public static void initialize() {
        CHANNEL.messageBuilder(HostedPackets.Request.class, 6, NetworkDirection.PLAY_TO_SERVER)
                .codec(HostedPackets.Request.CODEC)
                .consumerMainThread(
                        (packet, context) ->
                                HostedServer.get(context.getSender().level().getServer())
                                        .request(context.getSender(), packet))
                .add();
        CHANNEL.messageBuilder(HostedPackets.State.class, 7, NetworkDirection.PLAY_TO_CLIENT)
                .codec(HostedPackets.State.CODEC)
                .consumerMainThread((packet, context) -> SatoriClient.TABLES.hosted(packet))
                .add();
        CHANNEL.messageBuilder(TablePackets.Link.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .codec(TablePackets.Link.CODEC)
                .consumerMainThread(
                        (packet, context) -> TableLinkHandler.handle(context.getSender(), packet))
                .add();
        CHANNEL.messageBuilder(TablePackets.Open.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .codec(TablePackets.Open.CODEC)
                .consumerMainThread((packet, context) -> SatoriClient.TABLES.open(packet))
                .add();
        CHANNEL.messageBuilder(TablePackets.Leave.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .codec(TablePackets.Leave.CODEC)
                .consumerMainThread(
                        (packet, context) -> {
                            if (context.getSender().getVehicle() instanceof TableSeatEntity)
                                context.getSender().stopRiding();
                        })
                .add();
        CHANNEL.messageBuilder(TablePackets.Linked.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .codec(TablePackets.Linked.CODEC)
                .consumerMainThread((packet, context) -> SatoriClient.TABLES.linked(packet))
                .add();
        CHANNEL.messageBuilder(TablePackets.Presence.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .codec(TablePackets.Presence.CODEC)
                .consumerMainThread(
                        (packet, context) -> TableLobby.announce(context.getSender(), packet))
                .add();
        CHANNEL.messageBuilder(TablePackets.Lobby.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .codec(TablePackets.Lobby.CODEC)
                .consumerMainThread((packet, context) -> SatoriClient.TABLES.lobby(packet))
                .add();
        CHANNEL.build();
    }

    public static void open(ServerPlayer player, TablePackets.Open packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    public static void hosted(ServerPlayer player, HostedPackets.State packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    public static void hosted(HostedPackets.Request packet) {
        CHANNEL.send(packet, PacketDistributor.SERVER.noArg());
    }

    public static void linked(ServerPlayer player, TablePackets.Linked packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    public static void lobby(ServerPlayer player, TablePackets.Lobby packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    public static void presence(TablePackets.Presence packet) {
        CHANNEL.send(packet, PacketDistributor.SERVER.noArg());
    }

    public static void leave() {
        CHANNEL.send(new TablePackets.Leave(), PacketDistributor.SERVER.noArg());
    }

    public static void link(TablePackets.Link packet) {
        CHANNEL.send(packet, PacketDistributor.SERVER.noArg());
    }
}
