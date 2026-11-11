package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Real Minecraft captures with isolated Rust game records and explicitly local preview players. */
final class ElectricTablePreview {
    private static final AtomicLong CLOCK = new AtomicLong();

    static void decorate(ServerLevel level, BlockPos center) {
        for (int x = -5; x <= 5; x++)
            for (int z = -5; z <= 5; z++) {
                level.setBlockAndUpdate(
                        center.offset(x, -1, z),
                        (Math.abs(x) == 5 || Math.abs(z) == 5
                                        ? Blocks.POLISHED_DEEPSLATE
                                        : Blocks.DARK_OAK_PLANKS)
                                .defaultBlockState());
                for (int y = 0; y <= 5; y++)
                    if (Math.abs(x) == 5 || z == -5)
                        level.setBlockAndUpdate(
                                center.offset(x, y, z),
                                (y == 0 || y == 4
                                                ? Blocks.DARK_OAK_PLANKS
                                                : Blocks.LIGHT_GRAY_CONCRETE)
                                        .defaultBlockState());
            }
        for (int x : new int[] {-4, 4})
            for (int y = 1; y <= 3; y++)
                level.setBlockAndUpdate(
                        center.offset(x, y, -4), Blocks.SEA_LANTERN.defaultBlockState());
        if (!TableSeating.furnish(level, center)) throw new AssertionError("Chair clearance");
    }

    private static void server(ClientGameTestContext context, Consumer<ServerLevel> action) {
        var ready =
                context.computeOnClient(
                        mc -> {
                            CompletableFuture<Void> result = new CompletableFuture<>();
                            mc.getSingleplayerServer()
                                    .execute(
                                            () -> {
                                                try {
                                                    action.accept(
                                                            mc.getSingleplayerServer().overworld());
                                                    result.complete(null);
                                                } catch (Throwable error) {
                                                    result.completeExceptionally(error);
                                                }
                                            });
                            return result;
                        });
        context.waitFor(mc -> ready.isDone());
        ready.join();
    }

    static void run(ClientGameTestContext context, BlockPos center, UUID game) {
        context.runOnClient(
                mc -> {
                    mc.getWindow().setWindowed(1600, 900);
                    mc.options.guiScale().set(2);
                    mc.resizeDisplay();
                });
        server(
                context,
                level -> {
                    decorate(level, center);
                    var player = level.getServer().getPlayerList().getPlayers().getFirst();
                    player.setNoGravity(true);
                    player.teleportTo(
                            center.getX() + 4.2, center.getY() + 2.5, center.getZ() + 4.6);
                });
        context.waitTicks(6);
        context.runOnClient(
                mc -> {
                    mc.player.setYRot(138);
                    mc.player.setXRot(30);
                    mc.options.fov().set(40);
                    mc.options.hideGui = true;
                });
        context.waitTicks(4);
        context.takeScreenshot("electric-overview");
        server(
                context,
                level -> {
                    if (!level.getBlockState(center).getValue(TableBlock.LIT))
                        throw new AssertionError("Table lights must remain on in daylight");
                    TableLightingChecks.verify(level, center);
                    level.setDayTime(18000);
                    level.updateSkyBrightness();
                });
        context.waitTicks(35);
        var lighting =
                context.computeOnClient(
                        mc -> {
                            CompletableFuture<Void> result = new CompletableFuture<>();
                            mc.getSingleplayerServer()
                                    .execute(
                                            () -> {
                                                var engine =
                                                        mc.getSingleplayerServer()
                                                                .overworld()
                                                                .getChunkSource()
                                                                .getLightEngine();
                                                CompletableFuture.allOf(
                                                                engine.waitForPendingTasks(-1, -1),
                                                                engine.waitForPendingTasks(-1, 0),
                                                                engine.waitForPendingTasks(0, -1),
                                                                engine.waitForPendingTasks(0, 0))
                                                        .whenComplete(
                                                                (done, error) -> {
                                                                    if (error == null)
                                                                        result.complete(null);
                                                                    else
                                                                        result
                                                                                .completeExceptionally(
                                                                                        error);
                                                                });
                                            });
                            return result;
                        });
        context.waitFor(mc -> lighting.isDone());
        lighting.join();
        context.waitTicks(5);
        server(
                context,
                level -> {
                    int previous = 16;
                    for (int distance = 0; distance <= 10; distance++) {
                        int brightness =
                                level.getBrightness(
                                        net.minecraft.world.level.LightLayer.BLOCK,
                                        center.offset(0, 1, distance + 1));
                        if (brightness >= previous || brightness < 0)
                            throw new AssertionError(
                                    "Table light must decay: " + distance + " = " + brightness);
                        previous = brightness;
                    }
                    if (!level.getBlockState(center).getValue(TableBlock.LIT)
                            || level.getBrightness(
                                            net.minecraft.world.level.LightLayer.BLOCK,
                                            center.offset(0, 0, 2))
                                    < 11)
                        throw new AssertionError(
                                "Native table light: state="
                                        + level.getBlockState(center)
                                        + " center="
                                        + level.getBrightness(
                                                net.minecraft.world.level.LightLayer.BLOCK, center)
                                        + " edge="
                                        + level.getBrightness(
                                                net.minecraft.world.level.LightLayer.BLOCK,
                                                center.offset(0, 0, 1))
                                        + " chair="
                                        + level.getBrightness(
                                                net.minecraft.world.level.LightLayer.BLOCK,
                                                center.offset(0, 0, 2))
                                        + " aboveChair="
                                        + level.getBrightness(
                                                net.minecraft.world.level.LightLayer.BLOCK,
                                                center.offset(0, 1, 2))
                                        + " daylight="
                                        + level.getBrightness(
                                                net.minecraft.world.level.LightLayer.SKY,
                                                center.above())
                                        + " skyDarken="
                                        + level.getSkyDarken());
                });
        context.takeScreenshot("electric-night");
        server(
                context,
                level -> {
                    level.setDayTime(6000);
                    level.updateSkyBrightness();
                });
        context.waitTicks(30);
        server(
                context,
                level -> {
                    if (!level.getBlockState(center).getValue(TableBlock.LIT))
                        throw new AssertionError("Table lights switched off after sunrise");
                });
        context.runOnClient(mc -> mc.options.fov().set(70));
        sit(context, center, 0, false);
        context.runOnClient(
                mc -> {
                    for (int side = 1; side < 4; side++) {
                        TableSeatEntity seat =
                                new TableSeatEntity(SatoriMahjong.seatEntityType(), mc.level);
                        seat.configure(center, side);
                        seat.setId(-200 - side);
                        mc.level.addEntity(seat);
                        RemotePlayer player =
                                new RemotePlayer(
                                        mc.level,
                                        new GameProfile(new UUID(0, 700 + side), "Preview" + side));
                        player.setId(-100 - side);
                        player.setPos(seat.position());
                        player.setYRot(180 + side * 90);
                        player.setYHeadRot(player.getYRot());
                        player.setYBodyRot(player.getYRot());
                        mc.level.addEntity(player);
                        player.startRiding(seat);
                        player.setOldPosAndRot();
                    }
                    mc.setScreen(null);
                    mc.options.hideGui = true;
                });
        context.waitTicks(8);
        context.takeScreenshot("electric-seated");
        HandTileInteractionTest.run(context, true);
        var multiTables = context.computeOnClient(mc -> new MultiTableChecks());
        try {
            for (var step : multiTables.steps()) {
                context.waitFor(mc -> step.ready().getAsBoolean());
                context.runOnClient(mc -> step.action().run());
            }
        } finally {
            context.runOnClient(mc -> multiTables.close());
        }
        var lobbyChecks = context.computeOnClient(mc -> new LobbyInteractionChecks());
        try {
            for (var step : lobbyChecks.steps()) {
                context.waitFor(mc -> step.ready().getAsBoolean());
                context.runOnClient(mc -> step.action().run());
                context.waitTicks(3);
                if (step.name().startsWith("capture-"))
                    context.takeScreenshot(step.name().substring(8));
            }
        } finally {
            context.runOnClient(mc -> lobbyChecks.close());
        }
        var creationChecks = context.computeOnClient(mc -> new CreationInteractionChecks());
        try {
            for (var step : creationChecks.steps()) {
                context.waitFor(mc -> step.ready().getAsBoolean());
                context.runOnClient(mc -> step.action().run());
                context.waitTicks(3);
                if (step.name().startsWith("capture-"))
                    context.takeScreenshot(step.name().substring(8));
            }
        } finally {
            context.runOnClient(mc -> creationChecks.close());
        }
        var navigationChecks = context.computeOnClient(mc -> new NavigationInteractionChecks());
        try {
            for (var step : navigationChecks.steps()) {
                context.waitFor(mc -> step.ready().getAsBoolean());
                context.runOnClient(mc -> step.action().run());
                context.waitTicks(3);
                if (step.name().startsWith("capture-"))
                    context.takeScreenshot(step.name().substring(8));
            }
        } finally {
            context.runOnClient(mc -> navigationChecks.close());
        }
        var presentationChecks = context.computeOnClient(mc -> new PresentationInteractionChecks());
        try {
            for (var step : presentationChecks.steps()) {
                context.waitFor(mc -> step.ready().getAsBoolean());
                context.runOnClient(mc -> step.action().run());
                context.waitTicks(3);
                if (step.name().startsWith("capture-"))
                    context.takeScreenshot(step.name().substring(8));
            }
        } finally {
            context.runOnClient(mc -> presentationChecks.close());
        }
        var atmosphere = context.computeOnClient(mc -> new TableAtmosphereChecks());
        try {
            for (var step : atmosphere.steps()) {
                context.waitFor(mc -> step.ready().getAsBoolean());
                context.runOnClient(mc -> step.action().run());
                context.waitTicks(3);
                if (step.name().startsWith("capture-"))
                    context.takeScreenshot(step.name().substring(8));
            }
        } finally {
            context.runOnClient(mc -> atmosphere.close());
        }
        JsonObject examples;
        var creditsChecks = context.computeOnClient(mc -> new CreditsInteractionChecks());
        try {
            for (var step : creditsChecks.steps()) {
                context.waitFor(mc -> step.ready().getAsBoolean());
                context.runOnClient(mc -> step.action().run());
                context.waitTicks(3);
                if (step.name().startsWith("capture-"))
                    context.takeScreenshot(step.name().substring(8));
            }
        } finally {
            context.runOnClient(mc -> creditsChecks.close());
        }
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                ElectricTablePreview.class.getResourceAsStream(
                                        "/electric-examples.json")),
                        StandardCharsets.UTF_8)) {
            examples = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        show(context, examples.getAsJsonObject("kakan"), false);
        context.setScreen(() -> new SeatedTableScreen(SatoriClient.TABLES.active()));
        context.waitTicks(3);
        context.takeScreenshot("electric-controls");
        context.runOnClient(mc -> CameraInteractionChecks.changeView());
        context.waitTicks(10);
        context.takeScreenshot("electric-camera-controls");
        context.runOnClient(
                mc -> {
                    CameraInteractionChecks.verifyFraming();
                    CameraInteractionChecks.reset();
                });
        context.waitTicks(10);
        context.setScreen(() -> null);
        show(context, examples.getAsJsonObject("own_draw"), false);
        show(context, examples.getAsJsonObject("own_draw"), true);
        frame(context, "electric-drawn", 4.0);
        show(context, examples.getAsJsonObject("own_hand_discard"), false);
        show(context, examples.getAsJsonObject("own_hand_discard"), true);
        frame(context, "electric-discard-fast", .085);
        frame(context, "electric-hand-tidy", .50);
        var opening = examples.getAsJsonObject("start_kyoku");
        show(context, opening, false);
        show(context, opening, true);
        frame(context, "electric-shutters", .65);
        frame(context, "electric-wall-lift", 1.15);
        frame(context, "electric-dealing", 2.10);
        frame(context, "electric-dealt", 4.0);
        for (String type :
                List.of(
                        "dahai",
                        "chi",
                        "pon",
                        "daiminkan",
                        "ankan",
                        "ankan_red",
                        "kakan",
                        "reach",
                        "riichi_replacement")) {
            if (!examples.has(type)) throw new AssertionError("Missing Rust example: " + type);
            var example = examples.getAsJsonObject(type);
            show(context, example, false);
            show(context, example, true);
            frame(context, "electric-action-" + type, type.equals("dahai") ? .085 : .28);
            frame(context, "electric-result-" + type, 4.0);
        }
        show(context, examples.getAsJsonObject("reach_accepted"), false);
        show(context, examples.getAsJsonObject("pon"), false);
        show(context, examples.getAsJsonObject("pon"), true);
        context.setScreen(() -> new SeatedTableScreen(SatoriClient.TABLES.active()));
        frame(context, "electric-call-pon", .28);
        context.runOnClient(
                mc -> {
                    for (String voice :
                            List.of("chi", "pon", "kan", "reach", "ron", "tsumo", "ryukyoku"))
                        if (mc.getSoundManager().getSoundEvent(SatoriMahjong.id("call." + voice))
                                == null)
                            throw new AssertionError("Missing native call sound " + voice);
                });
        context.setScreen(() -> null);
        show(context, examples.getAsJsonObject("reach_accepted"), true);
        frame(context, "electric-center", 4.0);
        context.runOnClient(
                mc -> {
                    var c = SatoriClient.TABLES.active();
                    long sticks =
                            cn.satori.mahjong.protocol.TableCenter.from(c.view).seats().stream()
                                    .filter(cn.satori.mahjong.protocol.TableCenter.Seat::riichi)
                                    .count();
                    if (sticks == 0
                            || c.scene.sample(c.scene.now()).stream()
                                            .filter(tile -> tile.key().startsWith("stick-"))
                                            .count()
                                    != sticks)
                        throw new AssertionError(
                                "Center preview did not reach the accepted-riichi snapshot");
                });
        leave(context);
        context.runOnClient(
                mc -> {
                    for (int i = 1; i < 4; i++) {
                        mc.level.getEntity(-100 - i).discard();
                        mc.level.getEntity(-200 - i).discard();
                    }
                });
        context.runOnClient(
                mc -> {
                    var raw =
                            examples.getAsJsonObject("own_draw")
                                    .getAsJsonObject("update")
                                    .getAsJsonObject("view")
                                    .deepCopy();
                    for (int ai = 1; ai < 4; ai++) {
                        var seat =
                                raw.getAsJsonObject("public")
                                        .getAsJsonArray("seats")
                                        .get(ai)
                                        .getAsJsonObject();
                        seat.addProperty("kind", "ai");
                        seat.add("user_id", JsonNull.INSTANCE);
                    }
                    var c = SatoriClient.TABLES.active();
                    c.view = new CloudView(raw, c.view.gameId, 0);
                    c.scene = new TableScene();
                    c.scene.update(c.view, false, c.scene.now());
                });
        sit(context, center, 0, false);
        context.setScreen(() -> new SeatedTableScreen(SatoriClient.TABLES.active()));
        context.waitTicks(4);
        context.takeScreenshot("electric-robots");
        leave(context);
        server(
                context,
                level -> {
                    var player = level.getServer().getPlayerList().getPlayers().getFirst();
                    var seat = new TableSeatEntity(SatoriMahjong.seatEntityType(), level);
                    seat.configure(center, 1);
                    level.addFreshEntity(seat);
                    var occupant = new ArmorStand(level, seat.getX(), seat.getY(), seat.getZ());
                    level.addFreshEntity(occupant);
                    if (!occupant.startRiding(seat)) throw new AssertionError("Occupancy setup");
                    if (TableSeating.sit(player, center, 1) || player.isPassenger())
                        throw new AssertionError("Occupied seat accepted");
                    occupant.discard();
                    seat.discard();
                });
        for (int side = 1; side < 4; side++) {
            sit(context, center, side, side == 2);
            HandTileInteractionTest.run(context, false);
            if (side < 3) leave(context);
        }
        server(
                context,
                level ->
                        level.setBlockAndUpdate(
                                center.offset(1, 0, 1), Blocks.AIR.defaultBlockState()));
        context.waitFor(mc -> !mc.player.isPassenger() && !SeatedCamera.active());
        server(
                context,
                level -> {
                    if (!level.getEntitiesOfClass(
                                    TableSeatEntity.class, new AABB(center).inflate(4))
                            .isEmpty())
                        throw new AssertionError("Orphan seat after broken structure");
                    level.setBlockAndUpdate(center, SatoriMahjong.table().defaultBlockState());
                    cn.satori.mahjong.TableAssembly.assemble(level, center);
                    ((TableBlockEntity) level.getBlockEntity(center))
                            .link(SatoriClient.TABLES.active().view.gameId.toString());
                });
        context.runOnClient(
                mc -> {
                    if (mc.getCameraEntity() != mc.player)
                        throw new AssertionError("Camera not restored");
                    if (mc.options.fov().get() != 70)
                        throw new AssertionError("Original FOV not restored");
                    if (SatoriClient.TABLES.active().view.viewerSeat != 0
                            || SatoriClient.TABLES.active().view.hand(2).stream()
                                    .anyMatch(t -> t != 37))
                        throw new AssertionError("Chair changed cloud authorization");
                });
        context.takeScreenshot("electric-restored-camera");
    }

    static void armDisconnect(ClientGameTestContext context, BlockPos center) {
        sit(context, center, 0, false);
        context.setScreen(() -> new SeatedTableScreen(SatoriClient.TABLES.active()));
        context.waitTicks(3);
    }

    private static void sit(
            ClientGameTestContext context, BlockPos center, int side, boolean viaTable) {
        server(
                context,
                level -> {
                    var player = level.getServer().getPlayerList().getPlayers().getFirst();
                    var direction = TableSeating.outward(side);
                    player.teleportTo(
                            center.getX() + .5 + direction.getStepX() * 3,
                            center.getY(),
                            center.getZ() + .5 + direction.getStepZ() * 3);
                });
        context.waitTicks(5);
        context.runOnClient(
                mc -> {
                    mc.player.getInventory().clearContent();
                    mc.setScreen(null);
                    var block =
                            viaTable
                                    ? center.relative(TableSeating.outward(side))
                                    : TableSeating.chair(center, side);
                    mc.gameMode.useItemOn(
                            mc.player,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(
                                    block.getCenter(), TableSeating.outward(side), block, false));
                });
        context.waitFor(
                mc ->
                        mc.player.getVehicle() instanceof TableSeatEntity seat
                                && seat.direction() == side
                                && SeatedCamera.active());
        context.waitTicks(20);
    }

    private static void leave(ClientGameTestContext context) {
        context.setScreen(() -> new SeatedTableScreen(SatoriClient.TABLES.active()));
        context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(mc -> !mc.player.isPassenger() && !SeatedCamera.active());
        context.runOnClient(
                mc -> {
                    if (mc.getCameraEntity() != mc.player)
                        throw new AssertionError("Camera not restored");
                });
    }

    private static void frame(ClientGameTestContext context, String name, double seconds) {
        context.runOnClient(mc -> CLOCK.set(1_000_000_000L + (long) (seconds * 1_000_000_000L)));
        context.waitTicks(3);
        context.takeScreenshot(name);
    }

    private static void show(ClientGameTestContext context, JsonObject example, boolean animate) {
        context.runOnClient(
                mc -> {
                    var c = SatoriClient.TABLES.active();
                    JsonObject update = example.getAsJsonObject("update");
                    JsonObject raw =
                            animate
                                    ? update.getAsJsonObject("view")
                                    : example.getAsJsonObject("before");
                    var view =
                            new CloudView(
                                    raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
                    if (!animate) {
                        CLOCK.set(1_000_000_000L);
                        c.scene = new TableScene(CLOCK::get);
                    }
                    c.view = view;
                    c.scene.update(
                            view,
                            animate ? update.getAsJsonArray("events") : new JsonArray(),
                            CLOCK.get());
                });
    }
}
