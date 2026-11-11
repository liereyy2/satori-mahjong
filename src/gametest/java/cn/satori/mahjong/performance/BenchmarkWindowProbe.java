package cn.satori.mahjong.performance;

import com.mojang.blaze3d.platform.Window;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional hidden native benchmark window; never changes the user's desktop focus. */
@Mixin(Window.class)
abstract class BenchmarkWindowProbe {
    @Inject(
            method = "<init>",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",
                            remap = false))
    private void hidden(CallbackInfo ci) {
        if (Boolean.getBoolean("satori.performance.hidden")) {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        }
    }
}
