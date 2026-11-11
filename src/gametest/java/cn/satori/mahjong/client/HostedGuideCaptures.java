package cn.satori.mahjong.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Current Chinese user-guide captures from real widgets in the production-JAR test. */
final class HostedGuideCaptures {
    static void prepare(ClientGameTestContext context) {
        var language =
                context.computeOnClient(
                        mc -> {
                            mc.options.pauseOnLostFocus = false;
                            mc.getWindow().setWindowed(1280, 800);
                            mc.options.guiScale().set(2);
                            mc.resizeDisplay();
                            mc.getLanguageManager().setSelected("zh_cn");
                            mc.options.languageCode = "zh_cn";
                            return mc.reloadResourcePacks();
                        });
        context.waitFor(mc -> language.isDone() && mc.getOverlay() == null);
        language.join();
    }

    static void capture(ClientGameTestContext context, String name) {
        context.waitTicks(8);
        context.takeScreenshot("guide-" + name);
    }

    static void afterStep(ClientGameTestContext context, String step) {
        switch (step) {
            case "capture-control" -> capture(context, "control-key");
            case "capture-ready-lobby" -> {
                capture(context, "ready-lobby");
                click(context, "feedback.title");
                context.waitFor(mc -> mc.screen instanceof FeedbackScreen);
                capture(context, "feedback");
                click(context, "back");
            }
            case "capture-ready-ai" -> capture(context, "ready-ai");
            case "capture-hosted-game" -> {
                context.waitFor(
                        mc -> {
                            var client = SatoriClient.TABLES.active();
                            return !client.scene.animating(client.scene.now());
                        });
                capture(context, "table");
                click(context, "menu.open");
                capture(context, "menu");
                click(context, "camera.title");
                context.waitFor(mc -> mc.screen instanceof CameraSettingsScreen);
                capture(context, "camera");
                click(context, "camera.done");
            }
            case "capture-outside-table" -> capture(context, "outside-table");
            case "capture-control-matches" -> capture(context, "control-matches");
            case "capture-table-highlight" -> {
                context.waitFor(
                        mc ->
                                SatoriClient.TABLES.worldTables.highlighted(
                                        (cn.satori.mahjong.TableBlockEntity)
                                                mc.level.getBlockEntity(
                                                        cn.satori.mahjong.HostedNativeFixture
                                                                .CENTER)));
                context.takeScreenshot("guide-table-highlight");
            }
            case "capture-hosted-result" -> capture(context, "final");
            default -> {}
        }
    }

    private static void click(ClientGameTestContext context, String key) {
        context.runOnClient(
                mc -> {
                    var screen = mc.screen;
                    String label = Component.translatable("satori." + key).getString();
                    var button =
                            screen.children().stream()
                                    .filter(Button.class::isInstance)
                                    .map(Button.class::cast)
                                    .filter(b -> b.getMessage().getString().equals(label))
                                    .findFirst()
                                    .orElseThrow();
                    if (!button.active) throw new AssertionError("Guide button disabled: " + key);
                    var event =
                            new MouseButtonEvent(
                                    button.getX() + button.getWidth() * .5,
                                    button.getY() + button.getHeight() * .5,
                                    new MouseButtonInfo(0, 0));
                    if (!screen.mouseClicked(event, false))
                        throw new AssertionError("Guide click failed: " + key);
                    screen.mouseReleased(event);
                });
        context.waitTicks(4);
    }
}
