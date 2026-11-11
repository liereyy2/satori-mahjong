package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Real seated controls, native mouse events and normal HTTP commands into a Rust-only film game.
 */
final class VideoButtonShots {
    static final double DURATION = 13.5;
    private final Path directory;
    private final BlockPos center;
    private final LongSupplier clock;
    private final URI origin;
    private final HttpClient http = HttpClient.newHttpClient();
    private final SatoriApi api;
    private ClientController client;
    private JsonObject shot;
    private int index = -1;
    private boolean clicked, applied, released;
    private int soundCount, observerControlCount;
    private boolean checkingSoundObserver, soundObserverVerified;
    private double cursorX, cursorY, shotTime;
    private TileActionButton choice;
    private boolean registering;
    private final List<JsonObject> playedSounds = new ArrayList<>();

    boolean accepts(HostedPackets.State packet) {
        return registering || !packet.pos().equals(center);
    }

    VideoButtonShots(Path directory, BlockPos center, LongSupplier clock) throws Exception {
        this.directory = directory;
        this.center = center;
        this.clock = clock;
        origin =
                URI.create(
                        JsonParser.parseString(
                                        Files.readString(directory.resolve("button-bridge.json")))
                                .getAsJsonObject()
                                .get("origin")
                                .getAsString());
        api = VideoApiOrigin.create(origin);
    }

    private JsonObject get(String path) throws Exception {
        var response =
                http.send(
                        HttpRequest.newBuilder(origin.resolve(path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IllegalStateException("Filming Rust bridge HTTP " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    void prepare(Minecraft mc) {
        var packet =
                JsonParser.parseString(
                                "{\"configured\":true,\"player_id\":\"filming\",\"error\":\"\",\"table\":{\"game_id\":\"10000000-0000-4000-8000-000000000017\",\"seats\":[]}}")
                        .getAsJsonObject();
        registering = true;
        SatoriClient.TABLES.hosted(
                new HostedPackets.State(
                        mc.level.dimension(), center, false, false, packet.toString()));
        registering = false;
        client = SatoriClient.TABLES.forTable((TableBlockEntity) mc.level.getBlockEntity(center));
        new VideoCast().seat(mc, center, List.of(1, 2, 3));
        mc.getSingleplayerServer()
                .execute(
                        () -> {
                            var server = mc.getSingleplayerServer();
                            var player = server.getPlayerList().getPlayers().getFirst();
                            player.setGameMode(GameType.CREATIVE);
                            var chair =
                                    new TableSeatEntity(
                                            SatoriMahjong.seatEntityType(), server.overworld());
                            chair.configure(center, 0);
                            server.overworld().addFreshEntity(chair);
                            if (!player.startRiding(chair))
                                throw new IllegalStateException("Filming player did not sit");
                        });
    }

    void warmup(Minecraft mc) throws Exception {
        if (!soundObserverVerified && SeatedCamera.active()) {
            checkingSoundObserver = true;
            net.minecraft.client.gui.components.Button.builder(
                            net.minecraft.network.chat.Component.literal("sound observer control"),
                            b -> {})
                    .build()
                    .playDownSound(mc.getSoundManager());
            checkingSoundObserver = false;
            if (observerControlCount != 1)
                throw new IllegalStateException("Sound observer did not see the ordinary UI click");
            soundObserverVerified = true;
        }
        if (index < 0 && SeatedCamera.active()) load(mc, 0);
    }

    private void load(Minecraft mc, int next) throws Exception {
        if (index >= 0 && (!applied || soundCount != 0))
            throw new IllegalStateException("Button shot must submit without a UI click sound");
        client.closeGame();
        index = next;
        shot = get("/filming/load/" + next);
        var raw = shot.getAsJsonObject("view");
        client.view =
                new CloudView(
                        raw,
                        UUID.fromString(raw.get("game_id").getAsString()),
                        raw.get("viewer_seat").getAsInt());
        client.scene = new TableScene(clock);
        client.scene.update(client.view, false, clock.getAsLong());
        client.presentation =
                new CloudPresentation(shot.getAsJsonObject("presentation"), client.view);
        var session = new JsonObject();
        session.addProperty("seat", client.view.viewerSeat);
        session.addProperty("seat_token", "filming-seat");
        client.game = new GameConnection(api, client.view.gameId, session, (v, e) -> {}, s -> {});
        client.connection = "connected";
        client.message = "";
        client.busy = false;
        clicked = applied = released = false;
        soundCount = 0;
        mc.setScreen(new SeatedTableScreen(client));
    }

    void frame(Minecraft mc, double seconds) throws Exception {
        int next = Math.min(2, (int) (seconds / 4.5));
        if (next != index) load(mc, next);
        shotTime = seconds - next * 4.5;
        var screen = mc.screen;
        if (!(screen instanceof SeatedTableScreen))
            throw new IllegalStateException(
                    "Native seated UI disappeared: "
                            + screen
                            + ", seated="
                            + client.seated()
                            + ", connection="
                            + client.connection);
        String action = shot.getAsJsonObject("action").get("action_id").getAsString();
        if (!clicked) {
            choice =
                    screen.children().stream()
                            .filter(TileActionButton.class::isInstance)
                            .map(TileActionButton.class::cast)
                            .filter(b -> b.actionId.equals(action))
                            .findFirst()
                            .orElseThrow();
            double t = Math.clamp((shotTime - .3) / .8, 0, 1);
            t = t * t * (3 - 2 * t);
            cursorX =
                    screen.width * .78
                            + (choice.getX() + choice.getWidth() / 2.0 - screen.width * .78) * t;
            cursorY =
                    screen.height * .76
                            + (choice.getY() + choice.getHeight() / 2.0 - screen.height * .76) * t;
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(
                    mc.getWindow().handle(),
                    cursorX * mc.getWindow().getScreenWidth() / screen.width,
                    cursorY * mc.getWindow().getScreenHeight() / screen.height);
            if (shotTime >= 1.5) {
                if (!choice.active || !choice.visible || !client.canAct())
                    throw new IllegalStateException("Real Rust action button unavailable");
                clicked = true;
                if (!screen.mouseClicked(mouse(), false))
                    throw new IllegalStateException("Native click was not handled");
                if (soundCount != 0)
                    throw new IllegalStateException("Decision button played an unwanted UI click");
            }
        } else if (!released && shotTime >= 1.6) {
            screen.mouseReleased(mouse());
            released = true;
        }
        if (clicked && !applied && shotTime >= 1.7 && !client.busy && !client.game.hasPending()) {
            var result = get("/filming/current");
            if (!result.get("received").getAsBoolean())
                throw new IllegalStateException("Real action POST was not received");
            var raw = result.getAsJsonObject("view");
            client.view = new CloudView(raw, client.view.gameId, client.view.viewerSeat);
            client.scene.append(client.view, result.getAsJsonArray("events"), clock.getAsLong());
            client.presentation =
                    new CloudPresentation(result.getAsJsonObject("presentation"), client.view);
            applied = true;
        }
        if (shotTime > 3 && !applied)
            throw new IllegalStateException("Rust command did not finish within shot");
    }

    private MouseButtonEvent mouse() {
        return new MouseButtonEvent(cursorX, cursorY, new MouseButtonInfo(0, 0));
    }

    void captureUiSound() {
        if (checkingSoundObserver) {
            observerControlCount++;
            return;
        }
        if (index >= 0) {
            soundCount++;
            throw new IllegalStateException("Decision button played an unwanted UI click");
        }
    }

    void captureSceneSound(String id, float volume, float pitch) {
        if (index < 0) return;
        String name = id.substring("satori_mahjong:".length());
        float expected = 1.0f;
        if (volume != expected || pitch != 1)
            throw new IllegalStateException(
                    "Unexpected sound level: " + id + " " + volume + "/" + pitch);
        var entry = new JsonObject();
        entry.addProperty("sound", name);
        entry.addProperty("volume", volume);
        entry.addProperty("pitch", pitch);
        entry.addProperty("play_result", "STARTED");
        playedSounds.add(entry);
    }

    TableScene scene() {
        return client.scene;
    }

    void cursor(GuiGraphics g) {
        int x = (int) cursorX, y = (int) cursorY;
        for (int row = 0; row < 13; row++) {
            int width = Math.min(7, 1 + row / 2);
            g.fill(x - 1, y + row - 1, x + width + 1, y + row + 1, 0xFF15201C);
            g.fill(x, y + row, x + width, y + row + 1, 0xFFF8F2E4);
        }
        if (clicked && shotTime < 1.75) g.renderOutline(x - 5, y - 5, 17, 17, 0xFFFFD787);
    }

    void finish() {
        if (!applied || soundCount != 0)
            throw new IllegalStateException("Final button shot incomplete");
        if (!soundObserverVerified || observerControlCount != 1)
            throw new IllegalStateException("Missing positive sound-observer control");
        var names = playedSounds.stream().map(s -> s.get("sound").getAsString()).toList();
        if (!names.equals(List.of("call.chi", "call.pon", "call.kan", "tile.draw")))
            throw new IllegalStateException("Missing confirmed native sounds: " + names);
        var report = new JsonObject();
        report.addProperty("status", "passed");
        report.addProperty("observer_control_starts", observerControlCount);
        report.addProperty("decision_ui_click_starts", soundCount);
        report.addProperty("confirmed_actions", 3);
        try {
            Files.writeString(
                    directory.resolve("button-sound-verification.json"), report.toString());
            Files.writeString(
                    directory.resolve("native-sound-levels.json"), new Gson().toJson(playedSounds));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
        client.closeGame();
        api.close();
    }
}
