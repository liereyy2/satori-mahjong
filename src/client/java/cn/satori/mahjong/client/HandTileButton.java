package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.CloudView;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Invisible, keyboard-accessible hit target over the actual 3D tile silhouette. */
final class HandTileButton extends Button {
    final String tileKey;
    final int face;
    private HandTilePicker.Region region;

    HandTileButton(String key, int face, Runnable discard) {
        super(
                0,
                0,
                1,
                1,
                Component.translatable("satori.table.discard_tile", CloudView.tileName(face)),
                b -> discard.run(),
                DEFAULT_NARRATION);
        this.tileKey = key;
        this.face = face;
        visible = false;
    }

    void project(HandTilePicker.Region region, boolean enabled) {
        this.region = region;
        visible = region != null;
        active = enabled && visible;
        if (visible)
            setRectangle(
                    region.right() - region.left(),
                    region.bottom() - region.top(),
                    region.left(),
                    region.top());
    }

    @Override
    public boolean isMouseOver(double x, double y) {
        return visible && region != null && region.contains(x, y);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return isMouseOver(event.x(), event.y()) && super.mouseClicked(event, doubleClick);
    }

    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {}

    @Override
    protected void renderContents(GuiGraphics g, int x, int y, float delta) {}
}
