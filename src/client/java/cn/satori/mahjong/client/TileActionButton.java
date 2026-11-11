package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;

final class TileActionButton extends Button {
    final String actionId;
    final List<Integer> faces;

    TileActionButton(int w, CloudView.Action action, Runnable press) {
        super(
                0,
                0,
                w,
                27,
                Component.translatable(
                        action.type().equals("dingque")
                                ? "satori.action.dingque."
                                        + action.display()
                                                .getAsJsonObject("event")
                                                .get("suit")
                                                .getAsInt()
                                : "satori.action." + action.type()),
                b -> press.run(),
                DEFAULT_NARRATION);
        actionId = action.actionId();
        faces = CloudPresentation.actionTiles(action);
    }

    /** Mahjong decisions speak only after confirmation; menus retain their normal UI click. */
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {}

    @Override
    protected void renderContents(GuiGraphics g, int x, int y, float delta) {
        boolean hover = active && isHoveredOrFocused();
        g.fill(getX(), getY(), getRight(), getBottom(), hover ? 0xC0234B40 : 0xA0101F1C);
        g.renderOutline(getX(), getY(), getWidth(), getHeight(), hover ? 0xFFE3C692 : 0x8864786B);
        var font = Minecraft.getInstance().font;
        int word = font.width(getMessage());
        int tileWidth =
                Math.min(15, Math.max(9, (getWidth() - word - 12) / Math.max(1, faces.size())));
        int tileHeight = Math.round((tileWidth - 2) * 528f / 384) + 2;
        int start =
                getX()
                        + (getWidth() - word - faces.size() * tileWidth - (faces.isEmpty() ? 0 : 5))
                                / 2;
        for (int face : faces) {
            GuiTile.draw(
                    g, face, start, getY() + (getHeight() - tileHeight) / 2, tileWidth, tileHeight);
            start += tileWidth;
        }
        if (!faces.isEmpty()) start += 5;
        g.drawString(
                font, getMessage(), start, getY() + 9, active ? 0xFFF0E9D8 : 0xFF85918C, false);
    }
}
