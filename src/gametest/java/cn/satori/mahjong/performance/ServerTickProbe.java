package cn.satori.mahjong.performance;

import cn.satori.mahjong.benchmark.TickSamples;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Integrated-server wall time, excluding the tick scheduler's sleep. */
@Mixin(MinecraftServer.class)
abstract class ServerTickProbe {
    @Unique private long satori$start;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void begin(CallbackInfo ci) {
        satori$start = System.nanoTime();
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void end(CallbackInfo ci) {
        TickSamples.record(System.nanoTime() - satori$start);
    }
}
