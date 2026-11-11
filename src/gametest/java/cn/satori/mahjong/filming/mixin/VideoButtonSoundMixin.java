package cn.satori.mahjong.filming.mixin;

import cn.satori.mahjong.client.VideoCaptureSession;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe native decision silence and confirmed call/tile playback levels. */
@Mixin(SoundManager.class)
abstract class VideoButtonSoundMixin {
    @Inject(method = "play", at = @At("RETURN"))
    private void capture(
            SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> result) {
        VideoCaptureSession.captureSound(sound, result.getReturnValue());
    }
}
