package cn.satori.mahjong.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import cn.satori.mahjong.*;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;

public final class SatoriClient implements ClientModInitializer {
    public static final ClientTables TABLES =
            new ClientTables(
                    new cn.satori.mahjong.protocol.AccountConfig(
                            net.fabricmc.loader.api.FabricLoader.getInstance()
                                    .getConfigDir()
                                    .resolve("satori-mahjong-account.json")));

    public static String release() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(SatoriMahjong.ID)
                .orElseThrow()
                .getMetadata()
                .getVersion()
                .getFriendlyString();
    }

    public static String loader() {
        return "fabric";
    }

    public static void sendPresence(TablePackets.Presence packet) {
        ClientPlayNetworking.send(packet);
    }

    public static void sendHosted(HostedPackets.Request packet) {
        ClientPlayNetworking.send(packet);
    }

    public static void sendLink(TablePackets.Link packet) {
        ClientPlayNetworking.send(packet);
    }

    public static void leaveSeat() {
        if (SeatedCamera.leave()) ClientPlayNetworking.send(new TablePackets.Leave());
    }

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    if (!ClientPlayNetworking.canSend(HostedPackets.Request.TYPE))
                        handler.getConnection()
                                .disconnect(
                                        net.minecraft.network.chat.Component.translatable(
                                                "satori.message.mod_update_required"));
                });
        ClientPlayNetworking.registerGlobalReceiver(
                HostedPackets.State.TYPE, (packet, context) -> TABLES.hosted(packet));
        EntityRenderers.register(SatoriMahjong.seatEntityType(), NoopRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    TABLES.tick();
                    SeatedCamera.tick();
                });
        BlockEntityRenderers.register(SatoriMahjong.tableEntityType(), TableRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(
                TablePackets.Open.TYPE, (packet, context) -> TABLES.open(packet));
        ClientPlayNetworking.registerGlobalReceiver(
                TablePackets.Lobby.TYPE, (packet, context) -> TABLES.lobby(packet));
        ClientPlayNetworking.registerGlobalReceiver(
                TablePackets.Linked.TYPE, (packet, context) -> TABLES.linked(packet));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TABLES.disconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TABLES.disconnect());
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, access) ->
                        dispatcher.register(
                                literal("satori")
                                        .executes(
                                                ctx -> {
                                                    TABLES.openAccount();
                                                    return 1;
                                                })));
    }
}
