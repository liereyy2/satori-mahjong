package cn.satori.mahjong.filming.mixin;

import cn.satori.mahjong.HostedPackets;
import cn.satori.mahjong.client.ClientTables;
import cn.satori.mahjong.client.VideoCaptureSession;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the isolated button take uses the Rust filming bridge as its sole projection input. */
@Mixin(ClientTables.class)
abstract class VideoButtonTransportMixin {
    @Inject(method = "hosted", at = @At("HEAD"), cancellable = true, remap = false)
    private void filmingInput(HostedPackets.State packet, CallbackInfo info) {
        if (!VideoCaptureSession.acceptButtonPacket(packet)) info.cancel();
    }
}
