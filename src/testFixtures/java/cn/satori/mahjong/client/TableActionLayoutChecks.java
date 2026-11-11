package cn.satori.mahjong.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Real-widget bounds and collision checks on desktop and minimum GUI dimensions. */
final class TableActionLayoutChecks {
    static void resize(boolean small) {
        var mc = Minecraft.getInstance();
        mc.getWindow().setWindowed(small ? 640 : 1280, small ? 480 : 800);
        mc.options.guiScale().set(2);
        mc.resizeDisplay();
    }

    static void verify() {
        var mc = Minecraft.getInstance();
        var screen = mc.screen;
        List<Button> buttons =
                screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> b.visible && !(b instanceof HandTileButton))
                        .toList();
        if (screen instanceof SeatedTableScreen) {
            verifyPlayingMenu(buttons);
            return;
        }
        var report = find(buttons, "feedback.title");
        var leave = find(buttons, "seat.leave");
        require(
                report.getY() == leave.getY() && report.getRight() < leave.getX(),
                "Top-left actions overlap");
        require(
                report.getX() < screen.width / 4 && report.getY() < screen.height / 4,
                "Actions left top-left corner");
        if (screen instanceof SatoriScreen panel) {
            require(
                    report.getBottom() < panel.top && leave.getBottom() < panel.top,
                    "Actions overlap the dialog");
            if (screen instanceof HostedLobbyScreen) {
                var length =
                        buttons.stream()
                                .filter(b -> named(b, "create.east") || named(b, "create.hanchan"))
                                .findFirst()
                                .orElseThrow();
                require(
                        length.getX() > panel.left + panel.panelWidth / 2,
                        "Length switch is not on header right");
                require(length.getY() < panel.top + 30, "Length switch left the title row");
            }
        }
        for (int i = 0; i < buttons.size(); i++) {
            var a = buttons.get(i);
            require(
                    a.getX() >= 0
                            && a.getY() >= 0
                            && a.getRight() <= screen.width
                            && a.getBottom() <= screen.height,
                    "Button outside screen");
            for (int j = i + 1; j < buttons.size(); j++) {
                var b = buttons.get(j);
                require(
                        a.getRight() <= b.getX()
                                || b.getRight() <= a.getX()
                                || a.getBottom() <= b.getY()
                                || b.getBottom() <= a.getY(),
                        "Native buttons overlap");
            }
        }
    }

    private static void verifyPlayingMenu(List<Button> closed) {
        var mc = Minecraft.getInstance();
        var table = mc.screen;
        require(
                closed.stream()
                        .noneMatch(b -> named(b, "feedback.title") || named(b, "seat.leave")),
                "Collapsed menu exposed feedback or leave");
        CameraInteractionChecks.openMenu();
        var expanded = menuButtons();
        var toggle = find(expanded, "menu.close");
        var report = find(expanded, "feedback.title");
        var leave = find(expanded, "seat.leave");
        require(
                report.getX() == toggle.getX() && leave.getX() == toggle.getX(),
                "Actions left the menu column");
        require(
                report.getY() >= toggle.getBottom() && leave.getY() >= report.getBottom(),
                "Expanded menu actions overlap");
        for (var button : expanded)
            require(
                    button.getX() >= 0
                            && button.getRight() <= table.width
                            && button.getY() >= 0
                            && button.getBottom() <= table.height,
                    "Expanded menu outside screen");
        CameraInteractionChecks.click(report, .5);
        require(mc.screen instanceof FeedbackScreen, "Menu feedback did not open form");
        var back =
                mc.screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> named(b, "back"))
                        .findFirst()
                        .orElseThrow();
        CameraInteractionChecks.click(back, .5);
        require(mc.screen == table, "Feedback did not return to the same table");
        CameraInteractionChecks.click(find(menuButtons(), "menu.close"), .5);
        require(
                menuButtons().stream()
                        .noneMatch(b -> named(b, "feedback.title") || named(b, "seat.leave")),
                "Closing menu retained action buttons");
    }

    private static List<Button> menuButtons() {
        return Minecraft.getInstance().screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.visible && !(b instanceof HandTileButton))
                .toList();
    }

    private static Button find(List<Button> buttons, String key) {
        var matches = buttons.stream().filter(b -> named(b, key)).toList();
        require(matches.size() == 1, "Missing or duplicated action: " + key);
        return matches.getFirst();
    }

    private static boolean named(Button button, String key) {
        return button.getMessage()
                .getString()
                .equals(Component.translatable("satori." + key).getString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
