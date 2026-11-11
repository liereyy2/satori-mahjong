package cn.satori.mahjong.client.mixin;

import cn.satori.mahjong.client.TableVisibility;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Use the same prepared frustum as vanilla's current world frame. */
@Mixin(LevelRenderer.class)
abstract class TableFrustumMixin {
    @Inject(method = "prepareCullFrustum", at = @At("RETURN"))
    private void satori$prepared(CallbackInfoReturnable<Frustum> result) {
        TableVisibility.prepared(result.getReturnValue());
    }
}
