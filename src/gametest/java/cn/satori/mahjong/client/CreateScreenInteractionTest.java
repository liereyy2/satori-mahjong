package cn.satori.mahjong.client;

import cn.satori.mahjong.TablePackets;
import cn.satori.mahjong.protocol.LocalAccountFixture;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Exercises the real mouse/keyboard route instead of invoking button callbacks directly. */
final class CreateScreenInteractionTest {
    static void run(ClientGameTestContext context) {
        testMissingSession(context);
        try (LocalAccountFixture fixture = new LocalAccountFixture()) {
            fixture.api.session().get(5, TimeUnit.SECONDS);
            ClientController controller = new ClientController(fixture.api, fixture.config);
            controller.dimension = SatoriClient.TABLES.active().dimension;
            controller.table = SatoriClient.TABLES.active().table;
            controller.table = new TablePackets.Open(new BlockPos(0, -60, 0), "", true);
            for (int[] display :
                    new int[][] {
                        {854, 480, 1}, {854, 480, 2}, {1280, 720, 3}, {1920, 1080, 0}, {640, 480, 0}
                    }) {
                context.getInput().resizeWindow(display[0], display[1]);
                context.runOnClient(
                        mc -> {
                            mc.options.guiScale().set(display[2]);
                            mc.resizeDisplay();
                        });
                context.runOnClient(mc -> controller.account.selectedKey = null);
                context.setScreen(() -> new TableScreen(controller));
                context.waitTicks(2);
                click(context, button(context, "create.title", 0));
                context.waitForScreen(CreateScreen.class);
                context.waitFor(mc -> !controller.account.keysLoading);
                context.waitTicks(2);
                click(context, button(context, "create.submit", 0));
                context.runOnClient(
                        mc ->
                                require(
                                        controller.message.equals(
                                                "seat_requires_satori_uuid_or_ai"),
                                        "Create button did not validate empty seats"));
                for (int seat = 1; seat < 4; seat++) {
                    click(context, button(context, "create.ai", seat));
                    int index = seat;
                    context.runOnClient(
                            mc ->
                                    require(
                                            fields(mc.screen.children())
                                                    .get(index)
                                                    .getValue()
                                                    .equals("AI"),
                                            "AI click did not fill seat " + index));
                }
                context.runOnClient(
                        mc ->
                                require(
                                        ((CreateScreen) mc.screen)
                                                .aiSeats()
                                                .equals(List.of(1, 2, 3)),
                                        "AI robot draft seats"));
                click(context, button(context, "keys.choose", 0));
                context.waitForScreen(KeyScreen.class);
                click(
                        context,
                        context.computeOnClient(
                                mc ->
                                        mc.screen.children().stream()
                                                .filter(Button.class::isInstance)
                                                .map(Button.class::cast)
                                                .filter(
                                                        btn ->
                                                                btn.getMessage()
                                                                        .getString()
                                                                        .startsWith("Test key"))
                                                .findFirst()
                                                .orElseThrow()));
                context.waitForScreen(CreateScreen.class);
                context.runOnClient(
                        mc -> {
                            require(controller.account.selectedKey != null, "No key selected");
                            require(
                                    fields(mc.screen.children()).size() == 4,
                                    "Manual key input remains");
                            require(
                                    fixture.reveals.get() == 0,
                                    "Selection fetched plaintext eagerly");
                        });
                click(context, button(context, "create.east", 0));
                context.getInput().resizeWindow(display[0] + 20, display[1]);
                context.waitTicks(2);
                context.runOnClient(
                        mc -> {
                            require(
                                    controller.account.selectedKey != null,
                                    "Resize cleared selection");
                            require(
                                    fields(mc.screen.children()).get(1).getValue().equals("AI"),
                                    "Resize cleared seats");
                        });
                context.takeScreenshot("satori-key-create-" + display[0] + "-scale-" + display[2]);
                click(context, button(context, "back", 0));
                context.waitForScreen(TableScreen.class);
            }
            context.runOnClient(
                    mc ->
                            controller.create(
                                    List.of(controller.user().id().toString(), "AI", "AI", "AI"),
                                    true));
            context.waitFor(mc -> !controller.busy);
            require(
                    fixture.reveals.get() == 1,
                    "Submitting AI did not reveal exactly the selected key");
            require(
                    fixture.submitted.getAsJsonArray("ai_seats").size() == 3
                            && fixture.submitted
                                    .get("api_key")
                                    .getAsString()
                                    .equals("synthetic-selected-key"),
                    "AI request missing selected key");
            context.runOnClient(
                    mc ->
                            controller.create(
                                    List.of(
                                            controller.user().id().toString(),
                                            "00000000-0000-0000-0000-000000000002",
                                            "00000000-0000-0000-0000-000000000003",
                                            "00000000-0000-0000-0000-000000000004"),
                                    true));
            context.waitFor(mc -> !controller.busy);
            require(
                    fixture.reveals.get() == 1 && !fixture.submitted.has("api_key"),
                    "Human game accessed AI key");
            fixture.emptyKeys = true;
            context.setScreen(() -> new KeyScreen(controller, new TableScreen(controller)));
            click(context, button(context, "keys.refresh", 0));
            context.waitFor(mc -> !controller.account.keysLoading);
            context.waitTicks(2);
            context.runOnClient(
                    mc ->
                            require(
                                    controller.account.selectedKey == null
                                            && controller.account.keys.isEmpty(),
                                    "Refresh retained deleted selection"));
            context.takeScreenshot("satori-keys-empty");
            click(context, button(context, "keys.website", 0));
            context.waitForScreen(net.minecraft.client.gui.screens.ConfirmLinkScreen.class);
            require(
                    KeyScreen.WEBSITE.equals("https://mj.satori-ai.cn/profile?view=api-key"),
                    "Incorrect creation URL");
            context.runOnClient(mc -> controller.disconnect());
            require(
                    controller.account.keys.isEmpty() && controller.account.selectedKey == null,
                    "Disconnect retained keys");
            context.setScreen(() -> null);
            context.restoreDefaultGameOptions();
            context.getInput().resizeWindow(854, 480);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void testMissingSession(ClientGameTestContext context) {
        try (LocalAccountFixture fixture = new LocalAccountFixture(false)) {
            ClientController controller = new ClientController(fixture.api, fixture.config);
            controller.dimension = SatoriClient.TABLES.active().dimension;
            controller.table = SatoriClient.TABLES.active().table;
            controller.table = new TablePackets.Open(new BlockPos(0, -60, 0), "", true);
            context.setScreen(() -> new AccountScreen(controller));
            context.waitTick();
            click(context, context.computeOnClient(mc -> fields(mc.screen.children()).get(0)));
            context.getInput().typeChars("test@example.org");
            click(context, context.computeOnClient(mc -> fields(mc.screen.children()).get(1)));
            context.getInput().typeChars("synthetic-password");
            click(context, button(context, "account.login", 0));
            context.waitFor(mc -> !controller.busy);
            context.runOnClient(
                    mc -> {
                        require(
                                mc.screen instanceof AccountScreen,
                                "Anonymous login advanced past the account screen");
                        require(
                                controller.user() == null
                                        && controller.message.equals("login_session_missing"),
                                "Missing session was reported as login success");
                    });
            context.takeScreenshot("satori-login-session-missing");
            context.setScreen(() -> new TableScreen(controller));
            Button create = button(context, "create.title", 0);
            context.runOnClient(mc -> require(!create.active, "Anonymous user can open creation"));
            context.setScreen(() -> null);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static List<EditBox> fields(List<?> children) {
        return children.stream()
                .filter(EditBox.class::isInstance)
                .map(EditBox.class::cast)
                .toList();
    }

    private static Button button(ClientGameTestContext context, String key, int index) {
        return context.computeOnClient(
                mc -> {
                    String label = Component.translatable("satori." + key).getString();
                    List<Button> buttons =
                            mc.screen.children().stream()
                                    .filter(Button.class::isInstance)
                                    .map(Button.class::cast)
                                    .filter(b -> b.getMessage().getString().equals(label))
                                    .toList();
                    require(
                            buttons.size() > index,
                            "Missing button " + key + ": " + buttons.size());
                    return buttons.get(index);
                });
    }

    private static void click(ClientGameTestContext context, AbstractWidget widget) {
        double[] point =
                context.computeOnClient(
                        mc -> {
                            require(
                                    widget.visible && widget.active,
                                    "Inactive widget " + widget.getMessage().getString());
                            require(
                                    widget.getX() >= 0
                                            && widget.getY() >= 0
                                            && widget.getRight() <= mc.screen.width
                                            && widget.getBottom() <= mc.screen.height,
                                    "Widget outside screen " + widget.getMessage().getString());
                            return new double[] {
                                (widget.getX() + widget.getWidth() / 2.0)
                                        * mc.getWindow().getScreenWidth()
                                        / mc.screen.width,
                                (widget.getY() + widget.getHeight() / 2.0)
                                        * mc.getWindow().getScreenHeight()
                                        / mc.screen.height
                            };
                        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
