package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.TableCameraFraming;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;

final class CameraInteractionTest {
    static void run(ClientGameTestContext context, HandInteractionFixture fixture) {
        context.runOnClient(mc -> CameraInteractionChecks.verifyFraming());
        context.runOnClient(
                mc -> {
                    CameraInteractionChecks.openMenu();
                    CameraInteractionChecks.openMainPage();
                });
        click(context, context.computeOnClient(mc -> CameraInteractionChecks.button("title")), .5);
        context.waitForScreen(CameraSettingsScreen.class);
        for (int i = 0; i < 3; i++) {
            int index = i;
            click(
                    context,
                    context.computeOnClient(mc -> CameraInteractionChecks.slider(index)),
                    i == 1 ? .72 : .80);
        }
        context.waitTicks(10);
        context.runOnClient(
                mc -> {
                    HandInteractionFixture.require(
                            fixture.http.submitted == null, "Camera adjustment sent a game action");
                    CameraInteractionChecks.verifyFraming();
                });
        click(context, context.computeOnClient(mc -> CameraInteractionChecks.button("done")), .5);
        context.waitForScreen(SeatedTableScreen.class);
        context.waitTicks(2);
        context.runOnClient(
                mc -> {
                    fixture.verifyLayout();
                    fixture.click(34, 0);
                });
        context.waitFor(mc -> fixture.received(34));
        context.runOnClient(
                mc -> {
                    fixture.verifyReceipt(34);
                    double before = SeatedCamera.settings().coverage();
                    HandInteractionFixture.require(
                            mc.screen.mouseScrolled(0, 0, 0, -2), "Wheel zoom ignored");
                    HandInteractionFixture.require(
                            SeatedCamera.settings().coverage() < before,
                            "Wheel zoom did not move camera");
                });
        context.waitTicks(10);
        context.runOnClient(
                mc -> {
                    CameraInteractionChecks.verifyFraming();
                    fixture.verifyLayout();
                    fixture.click(4, 1);
                });
        context.waitFor(mc -> fixture.received(4));
        context.runOnClient(mc -> fixture.verifyReceipt(4));
        context.runOnClient(
                mc -> {
                    CameraInteractionChecks.openMenu();
                    CameraInteractionChecks.openMainPage();
                });
        click(context, context.computeOnClient(mc -> CameraInteractionChecks.button("title")), .5);
        context.waitForScreen(CameraSettingsScreen.class);
        click(context, context.computeOnClient(mc -> CameraInteractionChecks.button("reset")), .5);
        context.runOnClient(
                mc ->
                        HandInteractionFixture.require(
                                SeatedCamera.settings().equals(TableCameraFraming.DEFAULT),
                                "Native reset failed"));
        click(context, context.computeOnClient(mc -> CameraInteractionChecks.button("done")), .5);
        context.waitTicks(10);
        context.runOnClient(mc -> CameraInteractionChecks.verifyFraming());
    }

    private static void click(
            ClientGameTestContext context, AbstractWidget widget, double fraction) {
        var point =
                context.computeOnClient(
                        mc ->
                                new double[] {
                                    (widget.getX() + 4 + fraction * (widget.getWidth() - 8))
                                            * mc.getWindow().getScreenWidth()
                                            / mc.screen.width,
                                    (widget.getY() + widget.getHeight() / 2.0)
                                            * mc.getWindow().getScreenHeight()
                                            / mc.screen.height
                                });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(0);
        context.waitTicks(2);
    }
}
