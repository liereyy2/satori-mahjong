package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;
import cn.satori.mahjong.protocol.TableScene;
import cn.satori.mahjong.protocol.TileContactShadow;
import cn.satori.mahjong.protocol.TileLens;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Soft contact darkening on the real tabletop, respecting the world's depth buffer. */
final class TableTileShadows {
    static final RenderType TYPE =
            RenderType.create(
                    "satori_tile_contacts",
                    RenderSetup.builder(
                                    RenderPipeline.builder(
                                                    RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                                            .withLocation(
                                                    SatoriMahjong.id("pipeline/tile_contacts"))
                                            .withVertexShader(SatoriMahjong.id("core/tile_contacts"))
                                            .withFragmentShader(
                                                    SatoriMahjong.id("core/tile_contacts"))
                                            .withVertexFormat(
                                                    DefaultVertexFormat.NEW_ENTITY,
                                                    VertexFormat.Mode.QUADS)
                                            .withBlend(BlendFunction.TRANSLUCENT)
                                            .withDepthWrite(false)
                                            .withCull(false)
                                            .build())
                            .createRenderSetup());

    static void render(TableScene.Tile tile, PoseStack pose, MultiBufferSource buffers) {
        var shadow = TileContactShadow.footprint(tile);
        if (shadow.isEmpty()) return;
        var bounds = shadow.get();
        pose.pushPose();
        if (tile.viewTransform() != null) pose.mulPose(tile.viewTransform().matrix());
        var matrix = pose.last();
        var vertices = buffers.getBuffer(TYPE);
        float[] xs = {bounds.minX(), bounds.minX(), bounds.maxX(), bounds.maxX()};
        float[] zs = {bounds.minZ(), bounds.maxZ(), bounds.maxZ(), bounds.minZ()};
        for (int i = 0; i < 4; i++) {
            vertices.addVertex(matrix, xs[i], TileContactShadow.SURFACE, zs[i])
                    .setColor(0, 0, 0, bounds.opacity())
                    .setUv(i < 2 ? 0 : 1, i == 0 || i == 3 ? 0 : 1)
                    .setOverlay(0)
                    .setLight(TileLens.of(tile).packed())
                    .setNormal(matrix, 0, 1, 0);
        }
        pose.popPose();
    }
}
