package cn.satori.mahjong.client;

import cn.satori.mahjong.ForgeTableNetwork;
import cn.satori.mahjong.HostedPackets;
import cn.satori.mahjong.SatoriMahjong;
import cn.satori.mahjong.TablePackets;

import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge loads these listeners exclusively on the physical client. */
@Mod.EventBusSubscriber(modid = SatoriMahjong.ID, value = Dist.CLIENT)
public final class SatoriClient {
    public static final ClientTables TABLES =
            new ClientTables(
                    new cn.satori.mahjong.protocol.AccountConfig(
                            net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR
                                    .get()
                                    .resolve("satori-mahjong-account.json")));

    public static String release() {
        return net.minecraftforge.fml.ModList.get()
                .getModContainerById(SatoriMahjong.ID)
                .orElseThrow()
                .getModInfo()
                .getVersion()
                .toString();
    }

    public static String loader() {
        return "forge";
    }

    public static void sendPresence(TablePackets.Presence packet) {
        ForgeTableNetwork.presence(packet);
    }

    public static void sendHosted(HostedPackets.Request packet) {
        ForgeTableNetwork.hosted(packet);
    }

    public static void sendLink(TablePackets.Link packet) {
        ForgeTableNetwork.link(packet);
    }

    public static void leaveSeat() {
        if (SeatedCamera.leave()) ForgeTableNetwork.leave();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent.Post event) {
        TABLES.tick();
        SeatedCamera.tick();
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SatoriMahjong.seatEntityType(), NoopRenderer::new);
        event.registerBlockEntityRenderer(SatoriMahjong.tableEntityType(), TableRenderer::new);
    }

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("satori")
                                .executes(
                                        context -> {
                                            TABLES.openAccount();
                                            return 1;
                                        }));
    }

    @SubscribeEvent
    public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        TABLES.disconnect();
    }

    @SubscribeEvent
    public static void stopping(GameShuttingDownEvent event) {
        TABLES.disconnect();
    }
}
