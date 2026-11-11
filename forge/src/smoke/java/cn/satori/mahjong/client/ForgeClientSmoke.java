package cn.satori.mahjong.client;

import cn.satori.mahjong.ForgeSmoke;

import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real Forge client drives the same Control/table UI checks as Fabric. */
@Mod.EventBusSubscriber(modid = "satori_mahjong_smoke", value = Dist.CLIENT)
public final class ForgeClientSmoke {
    private static boolean started, connected, finished;
    private static HostedInteractionChecks checks;
    private static final Queue<HostedInteractionChecks.Step> steps = new ArrayDeque<>();
    private static long deadline;
    private static int pause;
    private static CompletableFuture<Void> capture = CompletableFuture.completedFuture(null);

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent.Post event) {
        if (finished) return;
        var mc = Minecraft.getInstance();
        try {
            if (!started) {
                if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
                started = true;
                deadline = System.nanoTime() + 120_000_000_000L;
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString("127.0.0.1:25596"),
                        new ServerData(
                                "Local hosted smoke", "127.0.0.1:25596", ServerData.Type.OTHER),
                        false,
                        null);
                return;
            }
            if (System.nanoTime() > deadline)
                throw new AssertionError(
                        "Timeout: " + (steps.peek() == null ? "connect" : steps.peek().name()));
            if (mc.screen instanceof DisconnectedScreen) throw new AssertionError("Disconnected");
            if (!connected) {
                if (!(mc.screen instanceof ControlScreen)) return;
                connected = true;
                checks = new HostedInteractionChecks();
                steps.addAll(checks.steps());
            }
            if (pause-- > 0 || !capture.isDone()) return;
            capture.join();
            var step = steps.peek();
            if (step == null) {
                ForgeSmoke.report(
                        "passed",
                        "Control login/key, ordinary player readiness, automatic start, recipient"
                                + " privacy, final confirmation and camera exit");
                finished = true;
                checks.close();
                mc.stop();
                return;
            }
            if (!step.ready().getAsBoolean()) return;
            steps.remove();
            System.out.println("HOSTED_NATIVE: " + step.name());
            step.action().run();
            pause = 8;
            deadline = System.nanoTime() + 120_000_000_000L;
            if (step.name().startsWith("capture-")) {
                var done = new CompletableFuture<Void>();
                capture = done;
                var path =
                        Path.of(System.getProperty("satori.smoke.report"))
                                .getParent()
                                .resolve(step.name() + ".png");
                Screenshot.takeScreenshot(
                        mc.getMainRenderTarget(),
                        image -> {
                            try (image) {
                                image.writeToFile(path);
                                done.complete(null);
                            } catch (Exception e) {
                                done.completeExceptionally(e);
                            }
                        });
            }
        } catch (Throwable e) {
            e.printStackTrace();
            ForgeSmoke.report(
                    "failed",
                    e
                            + "; screen="
                            + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()));
            finished = true;
            mc.stop();
        }
    }
}
