package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

import java.util.List;

/** Seat-private Rust waits in one screen-space panel shared by standing and discard previews. */
final class SeatedTableHints {
    private static final int PAD = 6, TILE_W = 15, TILE_H = 22, ROW = 24;

    record Panel(
            CloudPresentation.Hint hint,
            List<Integer> waits,
            List<String> labelLines,
            int left,
            int top,
            int width,
            int height,
            int columns) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }
    }

    static Component label(CloudPresentation.Hint hint) {
        return Component.translatable(
                "satori."
                        + (hint.noYaku()
                                ? "hint.no_yaku"
                                : hint.furiten() ? "hint.furiten" : "hint.tenpai"));
    }

    static List<Integer> waits(CloudPresentation.Hint hint) {
        return hint.noYaku() ? hint.waits() : hint.yakuWaits();
    }

    static Panel fixed(ClientController client, Font font, int width, int height) {
        return panel(client.view.standingHint(), font, width, height);
    }

    static Panel panel(CloudPresentation.Hint hint, Font font, int width, int height) {
        if (hint == null || hint.shanten() != 0) return null;
        var waits = waits(hint);
        if (waits.isEmpty()) return null;
        int available = width / 4 - 1 - 2 * PAD;
        int columns = Math.min(waits.size(), Math.max(1, available / TILE_W));
        int rows = (waits.size() + columns - 1) / columns;
        var lines =
                font.splitIgnoringLanguage(label(hint), available).stream()
                        .map(FormattedText::getString)
                        .toList();
        int labelWidth = lines.stream().mapToInt(font::width).max().orElseThrow();
        int panelWidth = Math.max(labelWidth, columns * TILE_W) + 2 * PAD;
        int panelHeight = lines.size() * 12 + rows * ROW - 2 + 2 * PAD;
        // Preserve the original single-row lower edge; extra text grows away from the hand.
        int top = Math.max(36, height / 2 + 24 - panelHeight);
        return new Panel(hint, waits, lines, width / 4, top, panelWidth, panelHeight, columns);
    }

    static void draw(GuiGraphics g, Font font, Panel panel) {
        g.fill(panel.left, panel.top, panel.right(), panel.bottom(), 0xCC101F1C);
        for (int line = 0; line < panel.labelLines.size(); line++)
            g.drawString(
                    font,
                    panel.labelLines.get(line),
                    panel.left + PAD,
                    panel.top + PAD + line * 12,
                    panel.hint.noYaku() ? 0xFFFFAB83 : 0xFF98DFC1,
                    false);
        for (int i = 0; i < panel.waits.size(); i++)
            GuiTile.draw(
                    g,
                    panel.waits.get(i),
                    panel.left + PAD + (i % panel.columns) * TILE_W,
                    panel.top + PAD + panel.labelLines.size() * 12 + (i / panel.columns) * ROW,
                    TILE_W,
                    TILE_H);
    }
}
