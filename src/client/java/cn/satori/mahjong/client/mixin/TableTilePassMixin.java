package cn.satori.mahjong.client.mixin;

import cn.satori.mahjong.client.TableTilePass;
import cn.satori.mahjong.client.TableVisibility;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs outside LevelRenderer, after shader composition and before hand depth clearing. */
@Mixin(GameRenderer.class)
abstract class TableTilePassMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void satori$beginTiles(CallbackInfo ci) {
        TableVisibility.beginFrame();
        TableTilePass.beginFrame();
    }

    @Inject(
            method = "renderLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                            shift = At.Shift.AFTER))
    private void satori$drawTiles(CallbackInfo ci) {
        TableTilePass.render();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void satori$closeTiles(CallbackInfo ci) {
        TableTilePass.close();
    }
}
