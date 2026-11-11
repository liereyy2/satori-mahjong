package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.RiichiHelp;
import cn.satori.mahjong.protocol.SichuanHelp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Native widgets, real text measurements, seat preservation and both loader input routes. */
final class HelpInteractionChecks {
    private final Minecraft mc;
    private Screen parent;
    private Object camera;
    private Object view;

    HelpInteractionChecks(Minecraft mc) {
        this.mc = mc;
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private Button button(String key) {
        String text = Component.translatable(key).getString();
        return mc.screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing help control " + key));
    }

    private void click(Button b) {
        var screen = mc.screen;
        var event =
                new MouseButtonEvent(
                        b.getX() + b.getWidth() / 2.0,
                        b.getY() + b.getHeight() / 2.0,
                        new MouseButtonInfo(0, 0));
        require(screen.mouseClicked(event, false), "Help click not consumed");
        screen.mouseReleased(event);
    }

    private AbstractScrollArea content() {
        return mc.screen.children().stream()
                .filter(AbstractScrollArea.class::isInstance)
                .map(AbstractScrollArea.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private void open() {
        parent = mc.screen;
        camera = mc.getCameraEntity();
        view = SatoriClient.TABLES.active().view;
        var b = button("satori.help.title");
        require(b.getX() >= mc.screen.width - 36 && b.getY() <= 10, "Help is not top-right");
        click(b);
        require(mc.screen instanceof MahjongHelpScreen, "Help did not open");
        require(!mc.screen.isPauseScreen(), "Help pauses the game");
    }

    private void verifyBounds() {
        for (var child : mc.screen.children()) {
            if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget w)
                    || !w.visible) continue;
            require(
                    w.getX() >= 0
                            && w.getY() >= 0
                            && w.getRight() <= mc.screen.width
                            && w.getBottom() <= mc.screen.height,
                    "Help widget outside viewport");
            if (w instanceof Button)
                require(
                        mc.font.width(w.getMessage()) <= w.getWidth() - 4,
                        "Help tab text overflows");
        }
        require(mc.getCameraEntity() == camera, "Help reset the camera");
        require(
                SatoriClient.TABLES.active().view == view,
                "Help changed the authoritative projection");
        require(SatoriClient.TABLES.active().seated(), "Help left the chair");
    }

    private void sichuanHelp() {
        click(button("satori.rules.sichuan"));
        for (var page : RiichiHelp.Page.values()) {
            click(button(SichuanHelp.titleKey(page)));
            verifyBounds();
        }
        click(button(SichuanHelp.titleKey(RiichiHelp.Page.YAKU)));
        require(content().maxScrollAmount() > 800, "Sichuan patterns are missing");
        var field =
                mc.screen.children().stream()
                        .filter(EditBox.class::isInstance)
                        .map(EditBox.class::cast)
                        .findFirst()
                        .orElseThrow();
        field.setValue(
                Component.translatable("satori.help.sichuan.yaku.longqidui.title").getString());
        require(content().maxScrollAmount() < 300, "Sichuan search did not filter");
        content().setScrollAmount(content().maxScrollAmount());
    }

    private void search() {
        for (var page : RiichiHelp.Page.values()) {
            click(button(page.titleKey()));
            verifyBounds();
        }
        click(button(RiichiHelp.Page.YAKU.titleKey()));
        var field =
                mc.screen.children().stream()
                        .filter(EditBox.class::isInstance)
                        .map(EditBox.class::cast)
                        .findFirst()
                        .orElseThrow();
        require(field.visible, "Yaku search is hidden");
        require(content().maxScrollAmount() > 1000, "Complete yaku list is missing");
        int fullHeight = content().maxScrollAmount();
        field.setValue(Component.translatable("satori.help.yaku.chiitoitsu.title").getString());
        require(
                content().maxScrollAmount() < fullHeight / 4,
                "Yaku search did not narrow the list");
        field.setValue("");
        require(content().maxScrollAmount() > 1000, "Clearing search did not restore yaku");
        var area = content();
        mc.screen.mouseScrolled(area.getX() + 8, area.getY() + 8, 0, -4);
        require(area.scrollAmount() > 0, "Rulebook wheel scrolling did not work");
        mc.screen.keyPressed(new KeyEvent(269, 0, 0));
        require(area.scrollAmount() == area.maxScrollAmount(), "End did not reach final yaku");
        mc.screen.keyPressed(new KeyEvent(268, 0, 0));
        require(area.scrollAmount() == 0, "Home did not return to first yaku");
        var down =
                new MouseButtonEvent(
                        area.getRight() + 2, area.getY() + 4, new MouseButtonInfo(0, 0));
        require(mc.screen.mouseClicked(down, false), "Rulebook scrollbar is not clickable");
        var drag =
                new MouseButtonEvent(
                        area.getRight() + 2, area.getBottom() - 4, new MouseButtonInfo(0, 0));
        mc.screen.mouseDragged(drag, 0, area.getHeight() - 8);
        mc.screen.mouseReleased(drag);
        require(area.scrollAmount() > 0, "Rulebook scrollbar cannot be dragged");
        mc.screen.keyPressed(new KeyEvent(268, 0, 0));
        verifyBounds();
    }

    private void close() {
        mc.screen.keyPressed(new KeyEvent(256, 0, 0));
        require(mc.screen == parent, "Escape did not return to the original table");
        require(SatoriClient.TABLES.active().seated(), "Escape from help left the chair");
        require(mc.getCameraEntity() == camera, "Closing help reset the camera");
    }

    private void closeButton() {
        var close =
                mc.screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> b.getMessage().getString().equals("×"))
                        .findFirst()
                        .orElseThrow();
        click(close);
        require(
                mc.screen == parent && SatoriClient.TABLES.active().seated(),
                "Close button left the table");
    }

    private void resizeOpen() {
        var screen = mc.screen;
        var area = content();
        area.setScrollAmount(120);
        TableActionLayoutChecks.resize(true);
        require(mc.screen == screen, "Resize replaced help");
        require(content().scrollAmount() == 120, "Resize lost rulebook scroll position");
        require(
                mc.screen.children().stream()
                        .anyMatch(w -> w instanceof EditBox field && field.visible),
                "Resize lost the yaku page");
        verifyBounds();
        TableActionLayoutChecks.resize(false);
    }

    private void example(String id) {
        var field =
                mc.screen.children().stream()
                        .filter(EditBox.class::isInstance)
                        .map(EditBox.class::cast)
                        .findFirst()
                        .orElseThrow();
        field.setValue(
                id.isEmpty()
                        ? ""
                        : Component.translatable("satori.help.yaku." + id + ".title").getString());
        verifyBounds();
    }

    List<HostedInteractionChecks.Step> preparationSteps() {
        return List.of(
                new HostedInteractionChecks.Step(
                        "open help before starting",
                        () -> mc.screen instanceof HostedLobbyScreen,
                        this::open),
                new HostedInteractionChecks.Step(
                        "capture-help-lobby",
                        () -> mc.screen instanceof MahjongHelpScreen,
                        this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "read Sichuan help without changing table", () -> true, this::sichuanHelp),
                new HostedInteractionChecks.Step(
                        "capture-help-sichuan", () -> true, this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "restore Riichi guide",
                        () -> true,
                        () -> click(button("satori.rules.riichi"))),
                new HostedInteractionChecks.Step(
                        "close preparation help", () -> true, this::closeButton));
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step(
                        "capture-help-entry",
                        () -> mc.screen instanceof SeatedTableScreen,
                        () -> {}),
                new HostedInteractionChecks.Step(
                        "open native rulebook",
                        () -> mc.screen instanceof SeatedTableScreen,
                        this::open),
                new HostedInteractionChecks.Step(
                        "capture-help-basics",
                        () -> mc.screen instanceof MahjongHelpScreen,
                        this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "search and scroll complete yaku", () -> true, this::search),
                new HostedInteractionChecks.Step(
                        "capture-help-yaku", () -> true, this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "show pairs illustration", () -> true, () -> example("chiitoitsu")),
                new HostedInteractionChecks.Step(
                        "capture-help-pairs", () -> true, this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "show kans illustration", () -> true, () -> example("suukantsu")),
                new HostedInteractionChecks.Step(
                        "capture-help-kans", () -> true, this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "show kokushi illustration", () -> true, () -> example("kokushi")),
                new HostedInteractionChecks.Step(
                        "capture-help-kokushi", () -> true, this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "show counted illustration", () -> true, () -> example("counted")),
                new HostedInteractionChecks.Step(
                        "capture-help-counted", () -> true, this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "restore full illustrated catalog", () -> true, () -> example("")),
                new HostedInteractionChecks.Step(
                        "resize open help preserves page and scroll", () -> true, this::resizeOpen),
                new HostedInteractionChecks.Step(
                        "help keyboard return keeps seat", () -> true, this::close),
                new HostedInteractionChecks.Step(
                        "small rulebook window",
                        () -> mc.screen instanceof SeatedTableScreen,
                        () -> {
                            TableActionLayoutChecks.resize(true);
                            open();
                        }),
                new HostedInteractionChecks.Step(
                        "capture-help-small",
                        () -> mc.screen instanceof MahjongHelpScreen,
                        this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "small rulebook tabs and search", () -> true, this::search),
                new HostedInteractionChecks.Step(
                        "small Sichuan guide and search", () -> true, this::sichuanHelp),
                new HostedInteractionChecks.Step(
                        "capture-help-sichuan-small", () -> true, this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "return to small Riichi patterns",
                        () -> true,
                        () -> click(button("satori.rules.riichi"))),
                new HostedInteractionChecks.Step(
                        "show small kans illustration",
                        () -> true,
                        () -> {
                            example("suukantsu");
                            content().setScrollAmount(content().maxScrollAmount() / 2.0);
                        }),
                new HostedInteractionChecks.Step(
                        "capture-help-kans-small", () -> true, this::verifyBounds),
                new HostedInteractionChecks.Step(
                        "restore rulebook window",
                        () -> true,
                        () -> {
                            close();
                            TableActionLayoutChecks.resize(false);
                        }));
    }
}
