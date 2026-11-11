package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.protocol.TableCameraFraming;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Shared native camera/frustum assertions for Fabric production and Forge client runs. */
public final class CameraInteractionChecks {
    public static boolean settled() {
        if (!SeatedCamera.active()) return false;
        var mc = Minecraft.getInstance();
        var center = SeatedCamera.center();
        var pose =
                TableCameraFraming.fit(
                        SeatedCamera.settings(),
                        SeatedCamera.direction(),
                        (double) mc.getWindow().getWidth() / mc.getWindow().getHeight());
        return mc.gameRenderer
                        .getMainCamera()
                        .position()
                        .distanceTo(
                                new Vec3(
                                        center.getX() + .5 + pose.eye().x(),
                                        center.getY() + pose.eye().y(),
                                        center.getZ() + .5 + pose.eye().z()))
                < .0001;
    }

    public static void verifyFraming() {
        var mc = Minecraft.getInstance();
        var center = SeatedCamera.center();
        double coverage = SeatedCamera.settings().coverage();
        var client = SatoriClient.TABLES.active();
        var camera = mc.gameRenderer.getMainCamera().position();
        float rotation =
                cn.satori.mahjong.protocol.TableOrientation.sceneDegrees(
                        client.view.viewerSeat, SeatedCamera.direction());
        org.joml.Vector3f rowNormal = null;
        Float rowHeight = null;
        for (var tile : TableViewTiles.sample(client, center, camera, client.scene.now())) {
            if (tile.seat() != client.view.viewerSeat || !tile.key().startsWith("hand-")) continue;
            var transform = cn.satori.mahjong.protocol.TileGeometry.transform(tile, rotation);
            var position = transform.transformPosition(new org.joml.Vector3f());
            var normal =
                    new org.joml.Matrix3f(transform)
                            .invert()
                            .transpose()
                            .transform(new org.joml.Vector3f(0, 0, 1))
                            .normalize();
            var ray =
                    new org.joml.Vector3f(
                                    (float) (camera.x - center.getX()),
                                    (float) (camera.y - center.getY()),
                                    (float) (camera.z - center.getZ()))
                            .sub(position)
                            .normalize();
            require(normal.dot(ray) > .7, "Own hand face is unreadable at the real camera angle");
            if (rowNormal == null) {
                rowNormal = new org.joml.Vector3f(normal);
                rowHeight = tile.y();
            }
            require(
                    normal.dot(rowNormal) > .99999 && Math.abs(tile.y() - rowHeight) < .00001,
                    "Own hand does not form one parallel straight row");
            require(
                    tile.roll() == 0 && tile.yaw() == client.view.viewerSeat * 90,
                    "Individual hand tile was turned toward the camera");
            var faceUp = transform.transformDirection(new org.joml.Vector3f(0, 1, 0)).normalize();
            require(faceUp.dot(ray) > .03, "Own hand top edge is not visible");
        }
        double edge = 0;
        for (var point : TableCameraFraming.boundsPoints()) {
            var projected =
                    mc.gameRenderer.projectPointToScreen(
                            new Vec3(
                                    center.getX() + .5 + point.x(),
                                    center.getY() + point.y(),
                                    center.getZ() + .5 + point.z()));
            require(
                    Math.abs(projected.x) <= coverage + .012
                            && Math.abs(projected.y) <= coverage + .012,
                    "Real camera crops the table: " + projected + " target=" + coverage);
            edge = Math.max(edge, Math.max(Math.abs(projected.x), Math.abs(projected.y)));
        }
        require(
                Math.abs(edge - coverage) < .012,
                "Table does not fill requested viewport: " + edge);
        System.out.println(
                "SATORI_CAMERA: side="
                        + SeatedCamera.direction()
                        + " framing="
                        + edge
                        + " settings="
                        + SeatedCamera.settings());
    }

    public static Button button(String key) {
        String text = Component.translatable("satori.camera." + key).getString();
        return Minecraft.getInstance().screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(text))
                .findFirst()
                .orElseThrow();
    }

    public static AbstractSliderButton slider(int index) {
        return Minecraft.getInstance().screen.children().stream()
                .filter(AbstractSliderButton.class::isInstance)
                .map(AbstractSliderButton.class::cast)
                .skip(index)
                .findFirst()
                .orElseThrow();
    }

    public static void click(AbstractWidget widget, double fraction) {
        var mc = Minecraft.getInstance();
        require(
                widget.getX() >= 0
                        && widget.getRight() <= mc.screen.width
                        && widget.getY() >= 0
                        && widget.getBottom() <= mc.screen.height,
                "Camera widget outside screen");
        var event =
                new MouseButtonEvent(
                        widget.getX() + 4 + fraction * (widget.getWidth() - 8),
                        widget.getY() + widget.getHeight() / 2.0,
                        new MouseButtonInfo(0, 0));
        require(mc.screen.mouseClicked(event, false), "Camera control not clickable");
        mc.screen.mouseReleased(event);
    }

    public static void openMenu() {
        String menu = Component.translatable("satori.menu.open").getString();
        var toggle =
                Minecraft.getInstance().screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> b.getMessage().getString().equals(menu))
                        .findFirst()
                        .orElseThrow();
        require(toggle.getX() == 10 && toggle.getY() == 9, "Menu is not at upper left");
        click(toggle, .5);
        for (var widget : Minecraft.getInstance().screen.children())
            if (widget instanceof Button b && b.getY() >= 34 && b.getX() == 10)
                require(
                        b.getRight() <= Minecraft.getInstance().screen.width
                                && b.getBottom() <= Minecraft.getInstance().screen.height,
                        "Expanded menu overflow");
    }

    public static void openMainPage() {
        String text = Component.translatable("satori.games.main").getString();
        var button =
                Minecraft.getInstance().screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> b.getMessage().getString().equals(text))
                        .findFirst()
                        .orElseThrow();
        click(button, .5);
        require(Minecraft.getInstance().screen instanceof AccountScreen, "Main page did not open");
    }

    public static void changeView() {
        openMenu();
        openMainPage();
        click(button("title"), .5);
        require(
                Minecraft.getInstance().screen instanceof CameraSettingsScreen,
                "Camera panel not opened");
        click(slider(0), .80);
        click(slider(1), .72);
        click(slider(2), .80);
        require(
                SeatedCamera.settings().coverage() > .91
                        && SeatedCamera.settings().pitch() > 57
                        && SeatedCamera.settings().yaw() > 10,
                "Sliders did not update camera");
    }

    public static void reset() {
        click(button("reset"), .5);
        require(
                SeatedCamera.settings().equals(TableCameraFraming.DEFAULT),
                "Reset did not restore defaults");
    }
}
