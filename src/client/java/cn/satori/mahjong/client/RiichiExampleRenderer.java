package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.RiichiExampleLayout;
import cn.satori.mahjong.protocol.RiichiHelpExamples;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** The normal tile artwork with annotated, responsive static rulebook examples. */
final class RiichiExampleRenderer {
    record Prepared(
            List<FormattedCharSequence> heading,
            List<FormattedCharSequence> note,
            RiichiExampleLayout.Layout layout,
            int height) {}

    static Prepared prepare(Font font, RiichiHelpExamples.Example example, int width) {
        var heading =
                Component.translatable(example.winMethod().labelKey())
                        .append(" · ")
                        .append(
                                Component.translatable(
                                        "satori.help.example."
                                                + (example.open() ? "open" : "closed")));
        var headingLines = font.split(heading, width);
        var note = font.split(Component.translatable(example.labelKey()), width);
        var layout = RiichiExampleLayout.layout(example, width);
        return new Prepared(
                headingLines,
                note,
                layout,
                8 + headingLines.size() * 12 + layout.height() + 5 + note.size() * 12 + 8);
    }

    static void draw(GuiGraphics g, Font font, Prepared example, int x, int y, int width) {
        g.fill(x, y + 2, x + width, y + 3, 0xFF35504C);
        y += 8;
        for (var line : example.heading) {
            g.drawString(font, line, x, y, 0xFFE9D3AC, false);
            y += 12;
        }
        for (var tile : example.layout.tiles()) {
            int tx = x + tile.x(), ty = y + tile.y();
            if (tile.winning()) {
                g.fill(tx - 2, ty - 2, tx + tile.width() + 2, ty + tile.height() + 2, 0xFF77E0C8);
            }
            if (tile.turned()) {
                g.pose().pushMatrix();
                g.pose().translate(tx + tile.width(), ty);
                g.pose().rotate((float) (Math.PI / 2));
                GuiTile.draw(
                        g,
                        tile.id(),
                        0,
                        0,
                        RiichiExampleLayout.TILE_WIDTH,
                        RiichiExampleLayout.TILE_HEIGHT);
                g.pose().popMatrix();
            } else GuiTile.draw(g, tile.id(), tx, ty, tile.width(), tile.height());
        }
        for (var label : example.layout.labels())
            g.drawCenteredString(
                    font,
                    Component.translatable(label.key()),
                    x + label.x() + label.width() / 2,
                    y + label.y(),
                    0xFFB6C8C2);
        y += example.layout.height() + 5;
        for (var line : example.note) {
            g.drawString(font, line, x, y, 0xFFB6C8C2, false);
            y += 12;
        }
    }
}
