package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/** Resin lighting in view space, isolated from shader-pack exposure and fog. */
final class TableTileMaterial {
    private static final RenderPipeline PIPELINE =
            RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                    .withLocation(SatoriMahjong.id("pipeline/table_tiles"))
                    .withVertexShader(SatoriMahjong.id("core/table_tiles"))
                    .withFragmentShader(SatoriMahjong.id("core/table_tiles"))
                    .withSampler("Sampler0")
                    .withSampler("Sampler1")
                    .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
                    .withoutBlend()
                    .withDepthWrite(true)
                    .withCull(false)
                    .build();
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();

    static RenderType furniture(String material) {
        return type(SatoriMahjong.id("textures/block/furniture/" + material + ".png"));
    }

    static RenderType type(Identifier texture) {
        return TYPES.computeIfAbsent(
                texture,
                key ->
                        RenderType.create(
                                "satori_table_tiles",
                                RenderSetup.builder(PIPELINE)
                                        .withTexture("Sampler0", key)
                                        .withTexture(
                                                "Sampler1",
                                                SatoriMahjong.id(
                                                        "textures/block/furniture/web_tile_px_surface.png"))
                                        .createRenderSetup()));
    }
}
