package cn.satori.mahjong.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

final class HandTileInteractionTest {
    static void run(ClientGameTestContext context, boolean resize) {
        var fixture =
                context.computeOnClient(
                        mc -> {
                            try {
                                return new HandInteractionFixture();
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        });
        try {
            int[][] displays =
                    resize
                            ? new int[][] {{640, 480, 2}, {1920, 1080, 3}, {1600, 900, 2}}
                            : new int[][] {{1600, 900, 2}};
            for (var display : displays) {
                context.getInput().resizeWindow(display[0], display[1]);
                context.runOnClient(
                        mc -> {
                            mc.options.guiScale().set(display[2]);
                            mc.resizeDisplay();
                            fixture.mount();
                        });
                context.waitTicks(10);
                context.runOnClient(mc -> fixture.verifyLayout());
                for (int id : new int[] {34, 4, 35, 13, 36, 22}) {
                    var point =
                            context.computeOnClient(
                                    mc -> {
                                        var b = fixture.tile(id, id == 4 ? 1 : 0);
                                        return new double[] {
                                            (b.getX() + b.getWidth() / 2.0)
                                                    * mc.getWindow().getScreenWidth()
                                                    / mc.screen.width,
                                            (b.getY() + b.getHeight() / 2.0)
                                                    * mc.getWindow().getScreenHeight()
                                                    / mc.screen.height
                                        };
                                    });
                    context.getInput().setCursorPos(point[0], point[1]);
                    context.runOnClient(mc -> fixture.verifySelection(id));
                    if (resize && display[0] == 1600 && id == 34) {
                        context.runOnClient(mc -> fixture.client.message = "joined");
                        context.waitTicks(3);
                        context.takeScreenshot("electric-selection");
                    }
                    context.getInput().pressMouse(0);
                    context.waitFor(mc -> fixture.received(id));
                    context.runOnClient(mc -> fixture.verifyReceipt(id));
                    context.waitTicks(2);
                }
                context.runOnClient(mc -> fixture.client.busy = true);
                context.waitTicks(2);
                context.runOnClient(
                        mc -> {
                            HandInteractionFixture.require(
                                    !fixture.tile(34, 0).active, "Busy tile enabled");
                            fixture.click(34, 0);
                        });
                context.waitTicks(2);
                context.runOnClient(
                        mc -> {
                            HandInteractionFixture.require(
                                    fixture.http.submitted == null, "Busy click submitted");
                            fixture.client.busy = false;
                            fixture.client.connection = "reconnecting";
                        });
                context.waitTicks(2);
                context.runOnClient(
                        mc -> {
                            HandInteractionFixture.require(
                                    !fixture.tile(34, 0).active, "Disconnected tile enabled");
                            fixture.client.connection = "connected";
                        });
                CameraInteractionTest.run(context, fixture);
            }
        } finally {
            context.runOnClient(mc -> fixture.close());
        }
    }
}
