package cn.satori.mahjong.performance;

import cn.satori.mahjong.client.TablePerformanceRun;
import cn.satori.mahjong.client.TableTilePass;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-mod-only timing; never included in an installable mod. */
@Mixin(TableTilePass.class)
abstract class TilePassProbe {
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private static void begin(CallbackInfo ci) {
        TablePerformanceRun.beginPass();
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private static void end(CallbackInfo ci) {
        TablePerformanceRun.endPass();
    }
}
