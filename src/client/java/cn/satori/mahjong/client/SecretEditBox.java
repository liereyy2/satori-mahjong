package cn.satori.mahjong.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;

/** Both visible text and accessibility narration conceal credentials. */
final class SecretEditBox extends EditBox {
    SecretEditBox(Font font, int x, int y, int width, Component label) {
        super(font, x, y, width, 20, label);
        addFormatter(
                (text, offset) ->
                        FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return getMessage().copy();
    }
}
