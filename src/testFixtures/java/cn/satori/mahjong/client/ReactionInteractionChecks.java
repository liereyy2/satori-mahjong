package cn.satori.mahjong.client;

import cn.satori.mahjong.TableBlockEntity;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Native call anchors and river outlines, using recorded legal Rust projections on both loaders.
 */
final class ReactionInteractionChecks {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private CloudView savedView;
    private TableScene savedScene;
    private JsonObject examples;

    private ClientController client() {
        return SatoriClient.TABLES.active();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void open() {
        savedView = client().view;
        savedScene = client().scene;
        try (var input = getClass().getResourceAsStream("/electric-examples.json")) {
            examples =
                    JsonParser.parseReader(
                                    new InputStreamReader(
                                            Objects.requireNonNull(input), StandardCharsets.UTF_8))
                            .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CloudView view(JsonObject source) {
        var raw = source.deepCopy();
        raw.addProperty("game_id", savedView.gameId.toString());
        return new CloudView(raw, savedView.gameId, 0);
    }

    private void snapshot(JsonObject raw) {
        client().view = view(raw);
        client().scene = new TableScene(clock::get);
        clock.set(1_000_000_000L);
        client().scene.update(client().view, false, clock.get());
    }

    private void call(String exampleName, String voice) {
        var example = examples.getAsJsonObject(exampleName);
        snapshot(example.getAsJsonObject("before"));
        var update = example.getAsJsonObject("update");
        client().view = view(update.getAsJsonObject("view"));
        client().scene.update(client().view, update.getAsJsonArray("events"), clock.get());
        TableScene.Call call = null;
        for (long time = 1_010_000_000L; time < 6_000_000_000L; time += 10_000_000L) {
            clock.set(time);
            var candidate = client().scene.call(time);
            if (candidate != null && candidate.voice().equals(voice)) {
                call = candidate;
                break;
            }
        }
        require(call != null, "Missing legal call " + voice);
        var screen = new SeatedTableScreen(client());
        Minecraft.getInstance().setScreen(screen);
        var position = screen.callPosition(call);
        require(position != null, "Declaring player's call must project into the camera");
        var positions = new ArrayList<HandTilePicker.Point>();
        for (int actor = 0; actor < 4; actor++) {
            var point = screen.callPosition(new TableScene.Call(0, voice, actor));
            require(point != null, "Missing call projection for seat " + actor);
            positions.add(point);
        }
        require(
                positions.get(1).x() > positions.get(0).x(),
                "Right player's call is in front of viewer");
        require(
                positions.get(3).x() < positions.get(0).x(),
                "Left player's call is in front of viewer");
        require(positions.get(2).y() < positions.get(0).y(), "Opposite call is in front of viewer");
        require(position.equals(positions.get(call.actor())), "Call ignored confirmed actor");
    }

    private TableRenderer.State rendered() {
        var mc = Minecraft.getInstance();
        var table = (TableBlockEntity) mc.level.getBlockEntity(SeatedCamera.center());
        var renderer =
                mc.getBlockEntityRenderDispatcher()
                        .<TableBlockEntity, TableRenderer.State>getRenderer(table);
        var state = renderer.createRenderState();
        renderer.extractRenderState(
                table, state, 0, mc.gameRenderer.getMainCamera().position(), null);
        return state;
    }

    private void reaction() {
        snapshot(examples.getAsJsonObject("chi").getAsJsonObject("before"));
        require(client().view.reactionDiscard() != null, "Legal chi must follow a pending discard");
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
        var state = rendered();
        require(
                state.reaction != null
                        && state.reaction.key().equals(client().view.reactionDiscard()),
                "Renderer did not outline the reaction source");
        require(!state.reactionEdges.isEmpty(), "Reaction source has no visible outline edges");
    }

    private void reactionOff() {
        clock.addAndGet(400_000_000L);
        require(rendered().reaction == null, "Pending discard must blink off");
    }

    private void resolved() {
        snapshot(examples.getAsJsonObject("chi").getAsJsonObject("update").getAsJsonObject("view"));
        require(rendered().reaction == null, "Called discard still highlighted");
    }

    private void close() {
        client().view = savedView;
        client().scene = savedScene;
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step("reaction display setup", () -> true, this::open),
                new HostedInteractionChecks.Step(
                        "capture-call-chi", () -> true, () -> call("chi", "chi")),
                new HostedInteractionChecks.Step(
                        "capture-call-pon", () -> true, () -> call("pon", "pon")),
                new HostedInteractionChecks.Step(
                        "capture-call-kan", () -> true, () -> call("daiminkan", "kan")),
                new HostedInteractionChecks.Step(
                        "capture-call-reach", () -> true, () -> call("reach", "reach")),
                new HostedInteractionChecks.Step(
                        "capture-reaction-discard-on", () -> true, this::reaction),
                new HostedInteractionChecks.Step(
                        "capture-reaction-discard-off", () -> true, this::reactionOff),
                new HostedInteractionChecks.Step(
                        "resolved discard clears outline", () -> true, this::resolved),
                new HostedInteractionChecks.Step(
                        "restore live display after reaction checks", () -> true, this::close));
    }
}
