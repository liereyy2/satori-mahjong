package cn.satori.mahjong.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Cull the complete multiblock presentation, including chairs and standing robots. */
public final class TableVisibility {
    private static Frustum frustum;

    private TableVisibility() {}

    public static void beginFrame() {
        frustum = null;
    }

    public static void prepared(Frustum current) {
        frustum = current;
    }

    public static boolean visible(BlockPos center) {
        return frustum != null
                && frustum.isVisible(
                        new AABB(
                                center.getX() - 2,
                                center.getY(),
                                center.getZ() - 2,
                                center.getX() + 3,
                                center.getY() + 4,
                                center.getZ() + 3));
    }
}
