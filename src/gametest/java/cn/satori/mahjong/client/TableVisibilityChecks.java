package cn.satori.mahjong.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;

/** The full table remains visible when its center has crossed the frustum edge. */
final class TableVisibilityChecks {
    static void verify() {
        var projection = new Matrix4f().setPerspective((float) Math.toRadians(90), 1, .1f, 100);
        var frustum = new Frustum(new Matrix4f(), projection);
        frustum.prepare(0, 2, 0);
        try {
            TableVisibility.prepared(frustum);
            if (!TableVisibility.visible(new BlockPos(0, 0, -10)))
                throw new AssertionError("Visible table culled");
            if (TableVisibility.visible(new BlockPos(0, 0, 10)))
                throw new AssertionError("Table behind camera still visible");
            var edge = new BlockPos(13, 0, -10);
            if (frustum.isVisible(new AABB(edge)))
                throw new AssertionError("Edge check did not cross center block");
            if (!TableVisibility.visible(edge))
                throw new AssertionError("Multiblock clipped at center boundary");
            TableVisibility.beginFrame();
            if (TableVisibility.visible(new BlockPos(0, 0, -10)))
                throw new AssertionError("Stale frustum survived frame boundary");
        } finally {
            TableVisibility.beginFrame();
        }
        System.out.println(
                "SATORI_VISIBILITY: front, behind, partial multiblock, frame reset passed");
    }
}
