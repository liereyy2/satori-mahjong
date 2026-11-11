package cn.satori.mahjong.filming.mixin;

import cn.satori.mahjong.client.VideoCaptureSession;

import net.minecraft.client.gui.GuiGraphics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Include the filming input cursor in the native framebuffer, where OS cursors are absent. */
@Mixin(targets = "cn.satori.mahjong.client.SeatedTableScreen")
abstract class VideoButtonCursorMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void cursor(GuiGraphics graphics, int x, int y, float delta, CallbackInfo info) {
        VideoCaptureSession.drawButtonCursor(graphics);
    }
}
