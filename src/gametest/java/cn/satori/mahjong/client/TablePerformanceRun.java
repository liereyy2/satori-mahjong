package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.benchmark.TickSamples;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Reproducible native benchmark, using authorized Rust observer projections, no cloud calls. */
public final class TablePerformanceRun {
    private static final List<BlockPos> POSITIONS = new ArrayList<>();
    private static final List<Double> CPU = new ArrayList<>(),
            GPU = new ArrayList<>(),
            FRAME = new ArrayList<>();

    private record Query(int start, int end, boolean sampled) {}

    private static final ArrayDeque<Query> QUERIES = new ArrayDeque<>();
    private static List<TableScene.Tile> tiles;
    private static PerformanceMotion motion;
    private static boolean animate, sample;
    private static volatile boolean active;
    private static int count, startQuery, endQuery;
    private static long passStart, previousFrame, started;

    private static JsonObject resource(String name) {
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                TablePerformanceRun.class.getResourceAsStream("/" + name)),
                        java.nio.charset.StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void beginPass() {
        if (!active) return;
        var draw = animate ? motion.sample(System.nanoTime() - started) : tiles;
        for (int i = 0; i < count; i++) TableTilePass.collect(POSITIONS.get(i), draw, 0, null);
        while (!QUERIES.isEmpty()
                && GL15.glGetQueryObjecti(QUERIES.peek().end, GL15.GL_QUERY_RESULT_AVAILABLE)
                        != 0) {
            var query = QUERIES.remove();
            long duration =
                    GL33.glGetQueryObjectui64(query.end, GL15.GL_QUERY_RESULT)
                            - GL33.glGetQueryObjectui64(query.start, GL15.GL_QUERY_RESULT);
            if (query.sampled) GPU.add(duration / 1e6);
            GL15.glDeleteQueries(query.start);
            GL15.glDeleteQueries(query.end);
        }
        long now = System.nanoTime();
        if (sample && previousFrame != 0) FRAME.add((now - previousFrame) / 1e6);
        previousFrame = now;
        startQuery = GL15.glGenQueries();
        endQuery = GL15.glGenQueries();
        GL33.glQueryCounter(startQuery, GL33.GL_TIMESTAMP);
        passStart = System.nanoTime();
    }

    public static void endPass() {
        if (!active) return;
        long end = System.nanoTime();
        GL33.glQueryCounter(endQuery, GL33.GL_TIMESTAMP);
        QUERIES.add(new Query(startQuery, endQuery, sample));
        if (sample) CPU.add((end - passStart) / 1e6);
    }

    private static JsonObject stats(Collection<Double> source) {
        var values = source.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (values.length == 0) throw new AssertionError("Missing performance samples");
        var result = new JsonObject();
        result.addProperty("samples", values.length);
        result.addProperty("mean_ms", Arrays.stream(values).average().orElseThrow());
        result.addProperty("p50_ms", values[(int) ((values.length - 1) * .5)]);
        result.addProperty("p95_ms", values[(int) ((values.length - 1) * .95)]);
        return result;
    }

    public static void run(ClientGameTestContext context) {
        var view =
                PerformanceMotion.view(
                        resource("table-stream-examples.json")
                                .getAsJsonObject("opening")
                                .getAsJsonArray("views")
                                .get(4)
                                .getAsJsonObject());
        tiles = TableScene.layout(view);
        var exchange = resource("sichuan-exchange-motion-1-observer.json");
        motion = new PerformanceMotion(exchange);
        var report = new JsonObject();
        var cases = new JsonArray();
        report.add("cases", cases);
        context.runOnClient(
                mc -> {
                    mc.options.pauseOnLostFocus = false;
                    mc.options.enableVsync().set(false);
                    mc.options.framerateLimit().set(260);
                    mc.options.fov().set(85);
                    mc.getWindow().setWindowed(1920, 1080);
                    report.addProperty("gpu", GL11.glGetString(GL11.GL_RENDERER));
                    report.addProperty("vendor", GL11.glGetString(GL11.GL_VENDOR));
                    report.addProperty("opengl", GL11.glGetString(GL11.GL_VERSION));
                });
        report.addProperty("fixture", "Rust opening observer; 16 moving Sichuan exchange replays");
        report.addProperty(
                "server_scope",
                "integrated server, physical tables idle, no hosted sessions or inference");
        report.addProperty(
                "source_vertices_per_tile",
                RoundedTileMesh.faces().stream().mapToInt(List::size).sum());
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("weather clear");
            world.getServer()
                    .runOnServer(
                            server ->
                                    server.getPlayerList()
                                            .getPlayers()
                                            .getFirst()
                                            .teleportTo(.5, -38, .5));
            context.runOnClient(
                    mc -> {
                        mc.player.setXRot(90);
                        mc.player.setYRot(0);
                        mc.options.hideGui = true;
                    });
            context.waitTicks(40);
            for (int n : new int[] {0, 1, 4, 16, 1, 4, 16}) {
                boolean moving = cases.size() >= 4;
                context.runOnClient(mc -> active = false);
                world.getServer()
                        .runOnServer(
                                server -> {
                                    for (var pos : POSITIONS)
                                        TableAssembly.removed(server.overworld(), pos, true);
                                    for (var pos : POSITIONS)
                                        server.overworld()
                                                .setBlockAndUpdate(
                                                        pos, Blocks.AIR.defaultBlockState());
                                    POSITIONS.clear();
                                    int side = (int) Math.ceil(Math.sqrt(n));
                                    for (int i = 0; i < n; i++) {
                                        var pos =
                                                new BlockPos(
                                                        (i % side) * 6 - (side - 1) * 3,
                                                        -60,
                                                        (i / side) * 6 - (side - 1) * 3);
                                        POSITIONS.add(pos);
                                        server.overworld()
                                                .setBlockAndUpdate(
                                                        pos,
                                                        SatoriMahjong.table().defaultBlockState());
                                        TableAssembly.assemble(server.overworld(), pos);
                                    }
                                });
                context.waitTicks(30);
                context.runOnClient(
                        mc -> {
                            count = n;
                            animate = moving;
                            sample = false;
                            active = true;
                            started = System.nanoTime();
                            previousFrame = 0;
                        });
                context.waitTicks(40);
                context.runOnClient(
                        mc -> {
                            CPU.clear();
                            GPU.clear();
                            FRAME.clear();
                            TickSamples.VALUES.clear();
                            sample = true;
                            TickSamples.active = true;
                        });
                long start = System.nanoTime();
                context.waitFor(
                        mc ->
                                System.nanoTime() - start > 4_000_000_000L
                                        && CPU.size() >= 40
                                        && GPU.size() >= 30,
                        2400);
                context.runOnClient(
                        mc -> {
                            sample = false;
                            TickSamples.active = false;
                            var row = new JsonObject();
                            row.addProperty("tables", n);
                            row.addProperty("moving", moving);
                            row.addProperty(
                                    "tiles_per_table", moving ? motion.size() : tiles.size());
                            row.add("tile_pass_cpu", stats(CPU));
                            row.add("tile_pass_gpu", stats(GPU));
                            row.add("frame", stats(FRAME));
                            row.add("idle_server_tick", stats(TickSamples.VALUES));
                            row.addProperty("width", mc.getWindow().getWidth());
                            row.addProperty("height", mc.getWindow().getHeight());
                            cases.add(row);
                            System.out.println("SATORI_PERFORMANCE: " + row);
                        });
                if (n == 1 || moving) context.takeScreenshot("performance-" + n + "-" + moving);
            }
        } finally {
            context.runOnClient(
                    mc -> {
                        active = false;
                        TickSamples.active = false;
                        for (var q : QUERIES) {
                            GL15.glDeleteQueries(q.start);
                            GL15.glDeleteQueries(q.end);
                        }
                        QUERIES.clear();
                    });
        }
        try {
            var path = Path.of(System.getProperty("satori.performance.report"));
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
