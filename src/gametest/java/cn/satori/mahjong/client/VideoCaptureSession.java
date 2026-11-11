package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.TableScene;

import com.google.gson.*;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Ordinary real-client filming: rendering never waits for a GameTest thread. */
public final class VideoCaptureSession implements ClientModInitializer {
    private static final BlockPos CENTER = new BlockPos(0, -59, 0);
    private final Path directory = Path.of(System.getProperty("satori.video.directory"));
    private final List<JsonObject> marks = new ArrayList<>();
    private final List<Integer> fps = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final List<JsonObject> audioCues = new ArrayList<>();
    private static VideoCaptureSession active;
    private final boolean materialPreview =
            Files.isRegularFile(directory.resolve("material-preview.json"));
    private Process encoder;
    private VideoButtonShots buttonShots;
    private WritableByteChannel pixels;
    private java.nio.ByteBuffer rgba;
    private boolean framePending;
    private int videoFrames, preparedFrame = -1;
    private TableScene audioScene;
    private long lastCall = Long.MIN_VALUE;
    private final VideoCast cast = new VideoCast();
    private JsonObject multiplayer;
    private JsonObject record, current;
    private UUID tableId;
    private int frameIndex, warmup, applied, highlight = -1;
    private boolean opened, initialized, ready, finished, highlightPlayed;
    private long started, stopped;
    private String phase = "";

    @Override
    public void onInitializeClient() {
        if (System.getProperty("fabric.client.gametest") != null) return;
        active = this;
        try {
            record =
                    JsonParser.parseString(Files.readString(directory.resolve("game.json")))
                            .getAsJsonObject();
            multiplayer =
                    JsonParser.parseString(Files.readString(directory.resolve("multiplayer.json")))
                            .getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        try {
            if (finished) {
                if (System.nanoTime() - stopped > 1_000_000_000L) mc.stop();
                return;
            }
            if (!opened && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                opened = true;
                mc.execute(
                        () ->
                                mc.createWorldOpenFlows()
                                        .openWorld(
                                                "New World",
                                                () ->
                                                        fail(
                                                                new IllegalStateException(
                                                                        "Filming world could not be"
                                                                                + " opened"))));
                return;
            }
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (!initialized) {
                var block = mc.level.getBlockEntity(CENTER);
                if (!(block instanceof TableBlockEntity table)) return;
                if (!table.gameId().equals(record.get("game_id").getAsString()))
                    throw new IllegalStateException("Filming world belongs to a different game");
                initialized = true;
                tableId = table.cloudTableId();
                mc.options.pauseOnLostFocus = false;
                mc.options.guiScale().set(2);
                mc.options.hideGui = true;
                mc.options.bobView().set(false);
                mc.options.framerateLimit().set(60);
                mc.getFramerateLimitTracker().setFramerateLimit(60);
                mc.options
                        .inactivityFpsLimit()
                        .set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                mc.options.enableVsync().set(false);
                mc.options.renderDistance().set(4);
                mc.getWindow().setWindowed(1600, 900);
                mc.resizeDisplay();
                org.lwjgl.glfw.GLFW.glfwRestoreWindow(mc.getWindow().handle());
                mc.getSingleplayerServer()
                        .execute(
                                () -> {
                                    var server = mc.getSingleplayerServer();
                                    server.getPlayerList()
                                            .getPlayers()
                                            .getFirst()
                                            .setGameMode(GameType.SPECTATOR);
                                    server.overworld().setDayTime(12700);
                                });
                if (materialPreview) record = multiplayer;
                current =
                        record.getAsJsonObject("initial")
                                .getAsJsonArray("views")
                                .get(4)
                                .getAsJsonObject();
                SatoriClient.TABLES.worldTables.clock(clock::get);
                accept(mc, null);
                if (!Files.isRegularFile(directory.resolve("button-bridge.json")))
                    cast.seat(mc, CENTER, materialPreview);
                if (materialPreview) next(mc);
                camera(mc, new Vec3(5.8, 3.3, 6.8), new Vec3(.5, .65, .5), 50);
                if (Files.isRegularFile(directory.resolve("button-bridge.json"))) {
                    buttonShots = new VideoButtonShots(directory, CENTER, clock::get);
                    buttonShots.prepare(mc);
                }
            }
            if (!ready) {
                if (buttonShots != null) buttonShots.warmup(mc);
                if (++warmup < 100) return;
                var info = new JsonObject();
                info.addProperty("ready", true);
                info.addProperty("pid", ProcessHandle.current().pid());
                info.addProperty("fps", mc.getFps());
                info.addProperty(
                        "throttle", mc.getFramerateLimitTracker().getThrottleReason().name());
                Files.writeString(directory.resolve("ready.json"), info.toString());
                startEncoder();
                ready = true;
            }
            if (started == 0) {
                started = System.nanoTime();
            }
            if (framePending || preparedFrame == videoFrames) return;
            preparedFrame = videoFrames;
            double seconds = videoFrames / 30.0;
            clock.set(
                    (materialPreview ? 11_000_000_000L : 1_000_000_000L)
                            + videoFrames * 1_000_000_000L / 30);
            fps.add(mc.getFps());
            if (materialPreview && seconds < 6) {
                enter(
                        "material_preview",
                        "four physical seats and post-composite depth-tested tiles");
                if (seconds < 2) camera(mc, new Vec3(3.7, 1.4, 4.6), new Vec3(.5, .7, .5), 49);
                else if (seconds < 4)
                    camera(mc, new Vec3(2.2, .05, 2.2), new Vec3(.65, 1.035, 1.1), 40);
                else camera(mc, new Vec3(1.2, .9, 1.7), new Vec3(.5, 1.03, .5), 48);
            } else if (buttonShots != null && seconds < VideoButtonShots.DURATION) {
                enter("buttons", "native seated buttons and real Rust action commands");
                buttonShots.frame(mc, seconds);
            } else if (!materialPreview && buttonShots == null && seconds < 8) {
                enter("room", "ordinary Minecraft client, real room and shader rendering");
                move(mc, seconds / 8, new Vec3(5.8, 3.3, 6.8), new Vec3(3.5, 2.1, 5.1), 50);
            } else if (!materialPreview && buttonShots == null && seconds < 15) {
                if (enter("opening", "original Rust dice, wall and deal events")) next(mc);
                camera(mc, new Vec3(3.4, 2, 3.7), new Vec3(.5, .5, .5), 48);
            } else if (!materialPreview && buttonShots == null && seconds < 36) {
                if (enter(
                        "play", "one human seat and three actual public-cloud Satori AI opponents"))
                    applied = 0;
                play(
                        mc,
                        (seconds - 15) / 21,
                        30,
                        new Vec3(3.4, 2, 3.7),
                        new Vec3(-2.4, 2, 3.7),
                        48);
            } else if (!materialPreview && buttonShots == null && seconds < 46) {
                if (enter("close", "real draw/discard animation details")) applied = 0;
                play(
                        mc,
                        (seconds - 36) / 10,
                        12,
                        new Vec3(-1.8, 1.15, 3.2),
                        new Vec3(-.85, 1.15, 3.2),
                        45);
            } else if (!materialPreview && buttonShots == null && seconds < 56.8) {
                enter("calls", "separate shots from the same real game");
                int index = Math.min(2, (int) ((seconds - 46) / 3.6));
                if (index != highlight) {
                    highlight = index;
                    highlightPlayed = false;
                    String kind = List.of("pon", "reach", "hora").get(index);
                    seek(mc, kind);
                    mark("highlight_" + kind, "exact before/after projections from the real game");
                    camera(mc, new Vec3(3.4, 2.3, -2.8), new Vec3(.5, .5, .5), 47);
                }
                if (!highlightPlayed && seconds >= 46 + index * 3.6 + .4) {
                    next(mc);
                    highlightPlayed = true;
                }
            } else if (!materialPreview && buttonShots == null && seconds < 63.8) {
                if (enter(
                        "friends", "four human seats, seated Minecraft demo actors, no AI seats")) {
                    record = multiplayer;
                    frameIndex = 0;
                    current =
                            record.getAsJsonObject("initial")
                                    .getAsJsonArray("views")
                                    .get(4)
                                    .getAsJsonObject();
                    accept(mc, null);
                    cast.seat(mc, CENTER, true);
                    next(mc);
                }
                camera(mc, new Vec3(3.7, 2.7, 4.6), new Vec3(.5, .6, .5), 54);
            } else if (!materialPreview && buttonShots == null && seconds < 83.8) {
                if (enter("multiplayer", "continuous four-human-seat gameplay demonstration"))
                    applied = 0;
                play(
                        mc,
                        (seconds - 63.8) / 20,
                        28,
                        new Vec3(3.7, 2.7, 4.6),
                        new Vec3(-3.7, 2.7, 4.6),
                        54);
            } else if (!materialPreview && buttonShots == null && seconds < 91.8) {
                enter("outro", "real camera movement through the block-built room");
                move(
                        mc,
                        (seconds - 83.8) / 8,
                        new Vec3(3.1, 2.5, 4.1),
                        new Vec3(6.5, 3.7, 8.5),
                        53);
            } else {
                if (buttonShots != null) buttonShots.finish();
                mark("end", "ordinary-client filming completed");
                Files.writeString(
                        directory.resolve("capture-timeline.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(marks));
                Files.writeString(directory.resolve("render-fps.json"), new Gson().toJson(fps));
                Files.writeString(
                        directory.resolve("audio-cues.json"), new Gson().toJson(audioCues));
                pixels.close();
                org.lwjgl.system.MemoryUtil.memFree(rgba);
                if (encoder.waitFor() != 0) throw new IllegalStateException("Frame encoder failed");
                var metadata = new JsonObject();
                metadata.addProperty("fps", 30);
                metadata.addProperty("frames", videoFrames);
                metadata.addProperty("offline_frame_export", true);
                metadata.addProperty("video_to_scene_seconds", 0.0);
                metadata.addProperty("audio_to_scene_seconds", 0.0);
                metadata.addProperty("wall_seconds", (System.nanoTime() - started) / 1e9);
                var size = new JsonArray();
                size.add(1600);
                size.add(900);
                metadata.add("size", size);
                Files.writeString(directory.resolve("recording.json"), metadata.toString());
                Files.writeString(directory.resolve("capture.complete"), "complete");
                mc.options.save();
                finished = true;
                stopped = System.nanoTime();
                return;
            }
            var table =
                    SatoriClient.TABLES.worldTables.forTable(
                            (TableBlockEntity) mc.level.getBlockEntity(CENTER));
            var soundScene = buttonShots == null ? table.scene : buttonShots.scene();
            if (audioScene != soundScene) {
                audioScene = soundScene;
                lastCall = Long.MIN_VALUE;
            }
            for (var sound : soundScene.sounds(lastCall, clock.get())) {
                lastCall = sound.id();
                var cue = new JsonObject();
                cue.addProperty("seconds", (sound.at() - 1_000_000_000L) / 1e9);
                cue.addProperty("sound", sound.name());
                cue.addProperty("actor", sound.actor());
                audioCues.add(cue);
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    public static boolean acceptButtonPacket(HostedPackets.State packet) {
        return active == null || active.buttonShots == null || active.buttonShots.accepts(packet);
    }

    public static void captureSound(
            net.minecraft.client.resources.sounds.SoundInstance sound,
            net.minecraft.client.sounds.SoundEngine.PlayResult result) {
        var session = active;
        if (session == null || session.finished || session.buttonShots == null) return;
        String id = sound.getIdentifier().toString();
        boolean uiClick = id.equals("minecraft:ui.button.click");
        boolean tableSound =
                id.startsWith("satori_mahjong:call.") || id.startsWith("satori_mahjong:tile.");
        if (!uiClick && !tableSound) return;
        if (result != net.minecraft.client.sounds.SoundEngine.PlayResult.STARTED)
            throw new IllegalStateException("Observed sound did not start: " + id + ": " + result);
        if (uiClick) session.buttonShots.captureUiSound();
        else session.buttonShots.captureSceneSound(id, sound.getVolume(), sound.getPitch());
    }

    public static void drawButtonCursor(net.minecraft.client.gui.GuiGraphics graphics) {
        if (active != null && active.ready && !active.finished && active.buttonShots != null)
            active.buttonShots.cursor(graphics);
    }

    private void fail(Throwable error) {
        error.printStackTrace();
        try {
            Files.writeString(directory.resolve("capture-error.txt"), error.toString());
        } catch (IOException writeError) {
            error.addSuppressed(writeError);
        }
        finished = true;
        stopped = System.nanoTime();
    }

    private boolean enter(String name, String note) {
        if (phase.equals(name)) return false;
        phase = name;
        mark(name, note);
        return true;
    }

    private void mark(String name, String note) {
        var row = new JsonObject();
        row.addProperty("name", name);
        row.addProperty("seconds", videoFrames / 30.0);
        row.addProperty("source_frame", frameIndex);
        row.addProperty("render_fps", Minecraft.getInstance().getFps());
        row.addProperty(
                "throttle",
                Minecraft.getInstance().getFramerateLimitTracker().getThrottleReason().name());
        row.addProperty("note", note);
        marks.add(row);
        System.out.println("SATORI_VIDEO " + row);
    }

    private void accept(Minecraft mc, JsonObject frame) {
        var packet = new JsonObject();
        packet.addProperty("kind", "table_view");
        packet.addProperty("table_id", tableId.toString());
        packet.addProperty("reset", frame == null);
        packet.add("view", current);
        var updates = new JsonArray();
        if (frame != null) {
            var update = new JsonObject();
            update.add("from_cursor", frame.get("from_cursor"));
            update.add("cursor", frame.get("cursor"));
            update.add("view", current);
            update.add("events", frame.getAsJsonArray("events").get(4));
            updates.add(update);
        }
        packet.add("updates", updates);
        SatoriClient.TABLES.hosted(
                new HostedPackets.State(
                        mc.level.dimension(), CENTER, false, false, packet.toString()));
    }

    private void next(Minecraft mc) {
        var frames = record.getAsJsonArray("frames");
        if (frameIndex >= frames.size()) throw new IllegalStateException("Recorded game exhausted");
        var frame = frames.get(frameIndex++).getAsJsonObject();
        current = frame.getAsJsonArray("views").get(4).getAsJsonObject();
        accept(mc, frame);
    }

    private void seek(Minecraft mc, String kind) {
        var frames = record.getAsJsonArray("frames");
        for (int i = 1; i < frames.size(); i++) {
            var frame = frames.get(i).getAsJsonObject();
            for (var raw : frame.getAsJsonArray("events").get(4).getAsJsonArray()) {
                if (!raw.getAsJsonObject().get("type").getAsString().equals(kind)) continue;
                current =
                        frames.get(i - 1)
                                .getAsJsonObject()
                                .getAsJsonArray("views")
                                .get(4)
                                .getAsJsonObject();
                frameIndex = i;
                accept(mc, null);
                return;
            }
        }
        throw new IllegalStateException("Recorded highlight missing: " + kind);
    }

    private void play(Minecraft mc, double t, int count, Vec3 from, Vec3 to, int fov) {
        int wanted = Math.min(count, (int) (t * count) + 1);
        while (applied < wanted) {
            next(mc);
            applied++;
        }
        move(mc, t, from, to, fov);
    }

    private void move(Minecraft mc, double t, Vec3 from, Vec3 to, int fov) {
        t = Math.clamp(t, 0, 1);
        camera(mc, from.lerp(to, t * t * (3 - 2 * t)), new Vec3(.5, .6, .5), fov);
    }

    private void camera(Minecraft mc, Vec3 position, Vec3 target, int fov) {
        mc.setScreen(null);
        mc.player.setNoGravity(true);
        mc.player.setPos(
                CENTER.getX() + position.x, CENTER.getY() + position.y, CENTER.getZ() + position.z);
        double dx = target.x - position.x, dz = target.z - position.z;
        double dy = target.y - position.y - mc.player.getEyeHeight();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setYHeadRot(yaw);
        mc.player.setYBodyRot(yaw);
        mc.options.fov().set(fov);
        mc.options.hideGui = true;
    }

    private void startEncoder() throws IOException {
        Files.createDirectories(directory.resolve("raw"));
        var binaryDirectory = directory.resolve("tools/python/imageio_ffmpeg/binaries");
        Path binary;
        try (var files = Files.list(binaryDirectory)) {
            binary =
                    files.filter(p -> p.getFileName().toString().startsWith("ffmpeg-linux"))
                            .findFirst()
                            .orElseThrow();
        }
        encoder =
                new ProcessBuilder(
                                binary.toString(),
                                "-hide_banner",
                                "-nostdin",
                                "-y",
                                "-f",
                                "rawvideo",
                                "-pixel_format",
                                "rgba",
                                "-video_size",
                                "1600x900",
                                "-framerate",
                                "30",
                                "-i",
                                "pipe:0",
                                "-an",
                                "-vf",
                                "vflip",
                                "-c:v",
                                "libx264",
                                "-preset",
                                "veryfast",
                                "-crf",
                                "17",
                                "-threads",
                                "4",
                                "-pix_fmt",
                                "yuv420p",
                                directory.resolve("raw/game.mkv").toString())
                        .redirectError(directory.resolve("frame-encoder.log").toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        pixels = Channels.newChannel(encoder.getOutputStream());
        rgba = org.lwjgl.system.MemoryUtil.memAlloc(1600 * 900 * 4);
    }

    public static void afterFrame(Minecraft mc) {
        var session = active;
        if (session == null
                || !session.ready
                || session.finished
                || session.framePending
                || session.preparedFrame != session.videoFrames) return;
        session.framePending = true;
        int oldFramebuffer =
                org.lwjgl.opengl.GL11.glGetInteger(
                        org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldBuffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_READ_BUFFER);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, 0);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_BACK);
            session.rgba.clear();
            org.lwjgl.opengl.GL11.glReadPixels(
                    0,
                    0,
                    1600,
                    900,
                    org.lwjgl.opengl.GL11.GL_RGBA,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    session.rgba);
            if (session.videoFrames == 0) {
                long energy = 0;
                for (int i = 0; i < 1600 * 900; i += 1024)
                    energy += session.rgba.getInt(i * 4) & 0xFFFFFF;
                if (energy == 0) throw new IllegalStateException("Captured framebuffer is black");
                try (var image = new com.mojang.blaze3d.platform.NativeImage(1600, 900, false)) {
                    for (int y = 0; y < 900; y++)
                        org.lwjgl.system.MemoryUtil.memCopy(
                                org.lwjgl.system.MemoryUtil.memAddress(session.rgba)
                                        + (long) y * 1600 * 4,
                                image.getPointer() + (long) (899 - y) * 1600 * 4,
                                1600 * 4);
                    image.writeToFile(session.directory.resolve("first-frame.png"));
                }
            }
            while (session.rgba.hasRemaining()) session.pixels.write(session.rgba);
            session.videoFrames++;
        } catch (Throwable error) {
            session.fail(error);
        } finally {
            org.lwjgl.opengl.GL30.glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, oldFramebuffer);
            org.lwjgl.opengl.GL11.glReadBuffer(oldBuffer);
            session.framePending = false;
        }
    }
}
