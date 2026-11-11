package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;
import cn.satori.mahjong.protocol.*;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.IntStream;

/** One immutable GPU mesh, per-tile matrices, and instanced draws grouped by real texture. */
final class TableTileInstances implements AutoCloseable {
    private static final int CAPACITY = 128;
    private static final RenderPipeline PIPELINE =
            RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                    .withLocation(SatoriMahjong.id("pipeline/table_tile_instances"))
                    .withVertexShader(SatoriMahjong.id("core/table_tile_instances"))
                    .withFragmentShader(SatoriMahjong.id("core/table_tiles"))
                    .withSampler("Sampler0")
                    .withSampler("Sampler1")
                    .withUniform(
                            "TileInstances", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                    .withUniform(
                            "TileVisibility", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES)
                    .withoutBlend()
                    .withDepthWrite(true)
                    .withCull(true)
                    .build();

    private record Key(int side, Identifier texture) {}

    private static final Key[] SIDES = {
        side(0, "px"),
        side(1, "nx"),
        side(2, "py"),
        side(3, "ny"),
        side(4, "blank"),
        side(5, "back")
    };
    private static final Key[] FACES = new Key[38], VOID_FACES = new Key[27];

    static {
        for (int i = 0; i < FACES.length; i++)
            FACES[i] = new Key(4, SatoriMahjong.id("textures/tiles/" + i + ".png"));
        for (int i = 0; i < VOID_FACES.length; i++)
            VOID_FACES[i] = new Key(4, SatoriMahjong.id("textures/tiles/void/" + i + ".png"));
    }

    private static Key side(int side, String name) {
        return new Key(
                side, SatoriMahjong.id("textures/block/furniture/web_tile_" + name + ".png"));
    }

    private record Mesh(GpuBuffer vertices, GpuBuffer indices, int count) implements AutoCloseable {
        public void close() {
            vertices.close();
            indices.close();
        }
    }

    private record TilePose(Matrix4f model, Matrix4f normal, TileOcclusion.Visibility visibility) {}

    private record Transforms(List<TilePose> matrices, int first, int count)
            implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buffer) {
            int base = buffer.position();
            for (int i = 0; i < count; i++) {
                var pose = matrices.get(first + i);
                pose.model.get(base + i * 64, buffer);
                pose.normal.get(base + CAPACITY * 64 + i * 64, buffer);
            }
        }
    }

    private record Lighting(Transforms transforms) implements DynamicUniformStorage.DynamicUniform {
        public void write(ByteBuffer buffer) {
            int base = buffer.position();
            for (int i = 0; i < transforms.count; i++) {
                var v = transforms.matrices.get(transforms.first + i).visibility;
                for (int corner = 0; corner < 4; corner++) {
                    buffer.putFloat(base + i * 16 + corner * 4, v.ambient()[corner]);
                    buffer.putFloat(base + CAPACITY * 16 + i * 16 + corner * 4, v.direct()[corner]);
                }
            }
        }
    }

    private record Draw(Key key, AbstractTexture texture, AbstractTexture surface, int count) {}

    private final List<Mesh> meshes =
            IntStream.range(0, 6)
                    .mapToObj(side -> upload(IndexedTileMesh.faces().get(side), side == 4))
                    .toList();
    private final DynamicUniformStorage<Transforms> uniforms =
            new DynamicUniformStorage<>("Satori tile instances", CAPACITY * 128, 64);
    private final DynamicUniformStorage<Lighting> lighting =
            new DynamicUniformStorage<>("Satori tile visibility", CAPACITY * 32, 64);
    private final Map<Key, ArrayList<TilePose>> groups = new LinkedHashMap<>();

    private static Mesh upload(IndexedTileMesh.Face face, boolean printedFace) {
        var device = RenderSystem.getDevice();
        try (var storage = new ByteBufferBuilder(16384)) {
            var builder =
                    new BufferBuilder(
                            storage, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            for (var v : face.vertices())
                builder.addVertex(v.x(), v.y(), v.z())
                        // This private instance mesh reserves red for its surface role.
                        .setColor(printedFace ? -1 : 0xFF000000)
                        .setUv(v.u(), v.v())
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(v.nx(), v.ny(), v.nz());
            try (var mesh = builder.buildOrThrow()) {
                var vertices =
                        device.createBuffer(
                                () -> "Satori unit tile vertices",
                                GpuBuffer.USAGE_VERTEX,
                                mesh.vertexBuffer());
                var data = MemoryUtil.memAlloc(face.indices().size() * Short.BYTES);
                try {
                    for (int index : face.indices()) data.putShort((short) index);
                    data.flip();
                    return new Mesh(
                            vertices,
                            device.createBuffer(
                                    () -> "Satori unit tile indices", GpuBuffer.USAGE_INDEX, data),
                            face.indices().size());
                } finally {
                    MemoryUtil.memFree(data);
                }
            }
        }
    }

    void beginFrame() {
        groups.values().forEach(List::clear);
        uniforms.endFrame();
        lighting.endFrame();
    }

    void add(
            TableScene.Tile tile,
            Matrix4f matrix,
            Vector3f lamp,
            Vector3f up,
            boolean voidTile,
            TileOcclusion visibility) {
        var face =
                tile.face() >= 0 && !(tile.face() == 37 && tile.key().startsWith("meld-"))
                        ? (voidTile ? VOID_FACES[tile.face()] : FACES[tile.face()])
                        : SIDES[4];
        var lens = TileLens.of(tile);
        // mat3 consumes only the normal block; its unused fourth row carries camera optics.
        var normal = TileLighting.instanceNormal(matrix, lens, lamp, up);
        for (int side = 0; side < 6; side++)
            groups.computeIfAbsent(side == 4 ? face : SIDES[side], key -> new ArrayList<>())
                    .add(new TilePose(matrix, normal, visibility.faceValues(tile.key(), side)));
    }

    void draw() {
        List<Draw> draws = new ArrayList<>();
        List<Transforms> uploads = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            var matrices = entry.getValue();
            for (int first = 0; first < matrices.size(); first += CAPACITY) {
                int count = Math.min(CAPACITY, matrices.size() - first);
                // TextureManager may upload an unloaded resource; resolve it before opening a pass.
                draws.add(
                        new Draw(
                                entry.getKey(),
                                Minecraft.getInstance()
                                        .getTextureManager()
                                        .getTexture(entry.getKey().texture),
                                Minecraft.getInstance()
                                        .getTextureManager()
                                        .getTexture(
                                                Identifier.fromNamespaceAndPath(
                                                        entry.getKey().texture.getNamespace(),
                                                        entry.getKey()
                                                                .texture
                                                                .getPath()
                                                                .replace(".png", "_surface.png"))),
                                count));
                uploads.add(new Transforms(matrices, first, count));
            }
        }
        if (draws.isEmpty()) return;
        // One mapping/upload per frame, rather than a driver mapping for every texture batch.
        var slices = uniforms.writeUniforms(uploads.toArray(Transforms[]::new));
        var lightSlices =
                lighting.writeUniforms(
                        uploads.stream().map(Lighting::new).toArray(Lighting[]::new));
        var mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        var color =
                RenderSystem.outputColorTextureOverride != null
                        ? RenderSystem.outputColorTextureOverride
                        : target.getColorTextureView();
        var depth =
                RenderSystem.outputDepthTextureOverride != null
                        ? RenderSystem.outputDepthTextureOverride
                        : target.getDepthTextureView();
        var transform =
                RenderSystem.getDynamicUniforms()
                        .writeTransform(
                                RenderSystem.getModelViewMatrix(),
                                new Vector4f(1),
                                new Vector3f(),
                                new Matrix4f());
        try (var pass =
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(
                                () -> "Satori instanced tiles",
                                color,
                                OptionalInt.empty(),
                                depth,
                                OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissor.enabled())
                pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            for (int index = 0; index < draws.size(); index++) {
                var draw = draws.get(index);
                var mesh = meshes.get(draw.key.side);
                var texture = draw.texture;
                pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
                pass.bindTexture(
                        "Sampler1", draw.surface.getTextureView(), draw.surface.getSampler());
                pass.setUniform("TileInstances", slices[index]);
                pass.setUniform("TileVisibility", lightSlices[index]);
                pass.setVertexBuffer(0, mesh.vertices);
                pass.setIndexBuffer(mesh.indices, VertexFormat.IndexType.SHORT);
                pass.drawIndexed(0, 0, mesh.count, draw.count);
            }
        }
    }

    @Override
    public void close() {
        meshes.forEach(Mesh::close);
        uniforms.close();
        lighting.close();
        groups.clear();
    }
}
