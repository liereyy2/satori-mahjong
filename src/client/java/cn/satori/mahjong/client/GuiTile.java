package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

/** The same SVG-derived face as the 3D model, framed as an ivory Minecraft inventory tile. */
final class GuiTile {
    static void draw(GuiGraphics g, int id, int x, int y, int w, int h) {
        // The visible body and its artwork share the same centered inset.
        g.fill(x, y, x + w, y + h, id == 37 ? 0xFF52798C : 0xFF304E43);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, id == 37 ? 0xFF9FD5EA : 0xFFF4EEDB);
        if (id == 37) {
            g.fill(x + 2, y + 2, x + w - 2, y + 3, 0xFFD2EDF8);
            return;
        }
        var face = cn.satori.mahjong.protocol.TileFaceBounds.fit(w - 2, h - 2);
        g.blit(
                RenderPipelines.GUI_TEXTURED,
                SatoriMahjong.id("textures/tiles/" + id + ".png"),
                x + 1 + face.x(),
                y + 1 + face.y(),
                0,
                0,
                face.width(),
                face.height(),
                384,
                528,
                384,
                528);
    }
}
