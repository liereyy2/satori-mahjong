package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.TableCameraFraming;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/** Live world preview; this screen owns input so slider gestures cannot discard a tile. */
final class CameraSettingsScreen extends Screen implements TablePage {
    private final ClientController client;

    @Override
    public ClientController controller() {
        return client;
    }

    private int left, panelWidth;

    CameraSettingsScreen(ClientController client) {
        super(tr("title"));
        this.client = client;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("satori.camera." + key, args);
    }

    @Override
    protected void init() {
        clearWidgets();
        panelWidth = Math.min(200, width - 24);
        left = width - panelWidth - 12;
        var settings = SeatedCamera.settings();
        slider(
                "zoom",
                40,
                70,
                98,
                settings.coverage() * 100,
                value -> SeatedCamera.settings(SeatedCamera.settings().withCoverage(value / 100)));
        slider(
                "pitch",
                66,
                40,
                65,
                settings.pitch(),
                value -> SeatedCamera.settings(SeatedCamera.settings().withPitch(value)));
        slider(
                "yaw",
                92,
                -20,
                20,
                settings.yaw(),
                value -> SeatedCamera.settings(SeatedCamera.settings().withYaw(value)));
        int cell = (panelWidth - 24) / 2;
        addRenderableWidget(
                Button.builder(
                                tr("reset"),
                                b -> {
                                    SeatedCamera.settings(TableCameraFraming.DEFAULT);
                                    rebuildWidgets();
                                })
                        .bounds(left + 10, 122, cell, 20)
                        .build());
        addRenderableWidget(
                Button.builder(
                                tr("done"),
                                b ->
                                        minecraft.setScreen(
                                                SeatedCamera.active()
                                                        ? new SeatedTableScreen(client)
                                                        : null))
                        .bounds(left + 14 + cell, 122, cell, 20)
                        .build());
    }

    private void slider(
            String name, int y, double min, double max, double current, DoubleConsumer set) {
        addRenderableWidget(
                new CameraSlider(left + 10, y, panelWidth - 20, name, min, max, current, set));
    }

    @Override
    public void tick() {
        if (!SeatedCamera.active()) minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        SatoriClient.leaveSeat();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float delta) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float delta) {
        g.fill(left, 12, left + panelWidth, 154, 0xB510211D);
        g.renderOutline(left, 12, panelWidth, 142, 0x997E8971);
        g.drawString(font, title, left + 10, 23, 0xFFE1C69B, false);
        super.render(g, x, y, delta);
    }

    private static final class CameraSlider extends AbstractSliderButton {
        private final String name;
        private final double min, max;
        private final DoubleConsumer set;

        CameraSlider(
                int x,
                int y,
                int width,
                String name,
                double min,
                double max,
                double current,
                DoubleConsumer set) {
            super(x, y, width, 20, Component.empty(), (current - min) / (max - min));
            this.name = name;
            this.min = min;
            this.max = max;
            this.set = set;
            setTooltip(Tooltip.create(tr(name + "_help")));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(tr(name, Math.round(min + value * (max - min))));
        }

        @Override
        protected void applyValue() {
            set.accept(min + value * (max - min));
        }

        @Override
        public void renderWidget(GuiGraphics g, int x, int y, float delta) {
            g.fill(getX(), getY(), getRight(), getBottom(), 0xA019302A);
            g.renderOutline(
                    getX(),
                    getY(),
                    getWidth(),
                    getHeight(),
                    isHoveredOrFocused() ? 0xFFDCC58F : 0x8864786B);
            int handle = getX() + 3 + (int) Math.round(value * (getWidth() - 10));
            g.fill(handle, getY() + 2, handle + 4, getBottom() - 2, 0xA0DCC58F);
            g.drawCenteredString(
                    Minecraft.getInstance().font,
                    getMessage(),
                    getX() + getWidth() / 2,
                    getY() + 6,
                    0xFFECE7DA);
        }
    }
}
