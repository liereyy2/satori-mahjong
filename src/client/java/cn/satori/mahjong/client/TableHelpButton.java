package cn.satori.mahjong.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Circle and question mark share the center of their actual visible pixel bounds. */
final class TableHelpButton extends Button {
    private static final String[] QUESTION = {
        "0111110", "1100011", "1100011", "0000011", "0000110", "0001110", "0011100", "0011100",
        "0000000", "0011100", "0011100"
    };

    TableHelpButton(int x, int y, Runnable open) {
        super(
                x,
                y,
                24,
                24,
                Component.translatable("satori.help.title"),
                b -> open.run(),
                DEFAULT_NARRATION);
        setTooltip(Tooltip.create(getMessage()));
    }

    @Override
    protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int ink = isHoveredOrFocused() ? 0xFF75E6D0 : 0xFFF3EBDC;
        int cx = getX() + 12, cy = getY() + 12;
        for (int y = -11; y <= 11; y++) {
            for (int x = -11; x <= 11; x++) {
                int d = x * x + y * y;
                if (d <= 121)
                    g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, d >= 90 ? ink : 0xE514242A);
            }
        }
        for (int y = 0; y < QUESTION.length; y++)
            for (int x = 0; x < QUESTION[y].length(); x++)
                if (QUESTION[y].charAt(x) == '1')
                    g.fill(cx - 3 + x, cy - 5 + y, cx - 2 + x, cy - 4 + y, ink);
    }
}
