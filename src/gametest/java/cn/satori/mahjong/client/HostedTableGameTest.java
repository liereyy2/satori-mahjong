package cn.satori.mahjong.client;

import cn.satori.mahjong.*;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Production-remapped Fabric artifact; local-only native Control/table lifecycle. */
public final class HostedTableGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (System.getProperty("satori.performance.report") != null) {
            TablePerformanceRun.run(context);
            return;
        }
        HostedGuideCaptures.prepare(context);
        context.runOnClient(mc -> TableVisibilityChecks.verify());
        context.runOnClient(
                mc -> {
                    mc.options.pauseOnLostFocus = false;
                    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
                            .register(HostedNativeFixture::install);
                });
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            world.getServer().runCommand("gamemode creative @a");
            world.getServer()
                    .runOnServer(
                            server -> {
                                var player = server.getPlayerList().getPlayers().getFirst();
                                server.getPlayerList()
                                        .op(
                                                player.nameAndId(),
                                                java.util.Optional.of(
                                                        net.minecraft.server.permissions
                                                                .LevelBasedPermissionSet.OWNER),
                                                java.util.Optional.empty());
                                player.teleportTo(3.5, -59, 2.5);
                                HostedServer.get(server)
                                        .openControl(player, HostedNativeFixture.CONTROL);
                            });
            context.waitFor(mc -> mc.screen instanceof ControlScreen);
            HostedGuideCaptures.capture(context, "control-login");
            var checks = context.computeOnClient(mc -> new HostedInteractionChecks());
            try {
                for (var step : checks.steps()) {
                    System.out.println("HOSTED_NATIVE: " + step.name());
                    try {
                        context.waitFor(mc -> step.ready().getAsBoolean());
                    } catch (Throwable e) {
                        context.runOnClient(
                                mc -> {
                                    var c = SatoriClient.TABLES.active();
                                    System.out.println(
                                            "HOSTED_FAILURE: screen="
                                                    + (mc.screen == null
                                                            ? "none"
                                                            : mc.screen.getClass().getSimpleName())
                                                    + " message="
                                                    + c.message
                                                    + " connection="
                                                    + c.connection
                                                    + " phase="
                                                    + (c.hosted == null
                                                            ? "none"
                                                            : c.hosted.phase()));
                                });
                        context.takeScreenshot("hosted-failure");
                        throw e;
                    }
                    context.runOnClient(mc -> step.action().run());
                    context.waitTicks(4);
                    if (step.name().startsWith("capture-"))
                        context.takeScreenshot(step.name().substring(8));
                    HostedGuideCaptures.afterStep(context, step.name());
                }
            } finally {
                context.runOnClient(mc -> checks.close());
            }
            world.getServer()
                    .runOnServer(
                            server -> {
                                if (HostedNativeFixture.http.failure != null)
                                    throw new AssertionError(HostedNativeFixture.http.failure);
                                HostedServer.stop(server);
                                HostedNativeFixture.http.close();
                            });
        }
    }
}
