package cn.satori.mahjong.client;

import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

abstract class SatoriScreen extends Screen implements TablePage {
    protected final ClientController client;

    @Override
    public ClientController controller() {
        return client;
    }

    protected int left, top, panelWidth, panelHeight;
    private final List<Gate> gates = new ArrayList<>();

    private record Gate(Button button, BooleanSupplier enabled, Supplier<Component> label) {}

    protected SatoriScreen(ClientController client, String title) {
        super(tr(title));
        this.client = client;
    }

    protected static Component tr(String key) {
        return Component.translatable("satori." + key);
    }

    @Override
    protected void init() {
        clearWidgets();
        gates.clear();
        panelWidth = Math.min(500, width - 16);
        panelHeight = Math.min(300, height - panelTopInset() - 12);
        left = (width - panelWidth) / 2;
        top = Math.max(panelTopInset(), (height - panelHeight) / 2);
        build();
        refreshButtons();
    }

    protected abstract void build();

    protected int panelTopInset() {
        return 0;
    }

    protected void tableActions() {
        addRenderableWidget(
                new TableHelpButton(
                        width - 35,
                        8,
                        () -> minecraft.setScreen(new MahjongHelpScreen(client, this))));
        int actionWidth = Math.min(112, (width - 24) / 2);
        button(
                tr("feedback.title"),
                8 - left,
                8 - top,
                actionWidth,
                () -> client.hosted == null || client.hosted.configured(),
                () -> minecraft.setScreen(new FeedbackScreen(client, this)));
        button(
                "seat.leave",
                12 + actionWidth - left,
                8 - top,
                actionWidth,
                SatoriClient::leaveSeat);
    }

    protected Button button(String label, int x, int y, int w, Runnable action) {
        return button(tr(label), x, y, w, () -> true, action);
    }

    protected Button button(
            Component label, int x, int y, int w, BooleanSupplier enabled, Runnable action) {
        return button(() -> label, x, y, w, enabled, action);
    }

    protected Button button(
            Supplier<Component> label,
            int x,
            int y,
            int w,
            BooleanSupplier enabled,
            Runnable action) {
        Button b =
                addRenderableWidget(
                        Button.builder(
                                        label.get(),
                                        ignored -> {
                                            // Transport locks prevent duplicate submission, without
                                            // switching
                                            // every native button to its dark disabled sprite for
                                            // one frame.
                                            if (!client.busy
                                                    && !client.account.authenticating
                                                    && enabled.getAsBoolean()) action.run();
                                        })
                                .bounds(left + x, top + y, w, 20)
                                .build());
        gates.add(new Gate(b, enabled, label));
        return b;
    }

    protected EditBox input(
            String hint, int x, int y, int w, int limit, String value, boolean secret) {
        EditBox field =
                secret
                        ? new SecretEditBox(font, left + x, top + y, w, tr(hint))
                        : new EditBox(font, left + x, top + y, w, 20, tr(hint));
        field.setMaxLength(limit);
        field.setValue(value);
        field.setHint(tr(hint));
        addRenderableWidget(field);
        return field;
    }

    public void refreshButtons() {
        for (Gate gate : gates) {
            gate.button.active = gate.enabled.getAsBoolean();
            gate.button.setMessage(gate.label.get());
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // This UI draws its own translucent panel. Vanilla blur/dimming would process the
        // entire world again whenever a button opens another table page.
    }

    protected void label(GuiGraphics graphics, Component text, int x, int y, int color) {
        graphics.drawString(font, text, left + x, top + y, color, false);
    }

    protected void label(GuiGraphics graphics, String text, int x, int y, int color) {
        label(graphics, Component.literal(text), x, y, color);
    }

    protected void fittedLabel(
            GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        float scale = Math.min(1f, (float) maxWidth / Math.max(1, font.width(text)));
        graphics.pose().pushMatrix();
        graphics.pose().translate(left + x, top + y);
        graphics.pose().scale(scale, scale);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x99081014);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xEE14242A);
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFF62DCC2);
        label(graphics, title, 12, 12, 0xFFF3EBDC);
        if (!client.statusMessage().isBlank())
            label(
                    graphics,
                    tr("message." + client.statusMessage()),
                    12,
                    panelHeight - 14,
                    0xFFFFD78C);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (SeatedCamera.active()) SatoriClient.leaveSeat();
        else super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        refreshButtons();
    }
}
