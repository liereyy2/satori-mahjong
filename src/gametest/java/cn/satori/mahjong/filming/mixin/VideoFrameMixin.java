package cn.satori.mahjong.filming.mixin;

import cn.satori.mahjong.client.VideoCaptureSession;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Filming JAR only: read the ordinary finished MC framebuffer once per exported frame. */
@Mixin(Minecraft.class)
abstract class VideoFrameMixin {
    @Inject(
            method = "runTick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
    private void satoriVideoAfterFrame(boolean render, CallbackInfo ci) {
        if (render) VideoCaptureSession.afterFrame((Minecraft) (Object) this);
    }
}
