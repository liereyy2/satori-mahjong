package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real Rust frames, including an own draw, with presentation unavailable throughout. */
final class ReadyHintLifecycleChecks {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private CloudView savedView;
    private CloudPresentation savedPresentation;
    private TableScene savedScene;
    private CloudPresentation.Hint expected;
    private JsonArray updates;
    private int position;

    private ClientController client() {
        return SatoriClient.TABLES.active();
    }

    private SeatedTableScreen screen() {
        return (SeatedTableScreen) Minecraft.getInstance().screen;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private CloudView view(JsonObject source) {
        var raw = source.deepCopy();
        raw.addProperty("game_id", savedView.gameId.toString());
        return new CloudView(raw, savedView.gameId, 0);
    }

    private void open(String mode) {
        savedView = client().view;
        savedPresentation = client().presentation;
        savedScene = client().scene;
        JsonObject data;
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/ready-hint-lifecycle.json")),
                        StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject(mode);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        updates = data.getAsJsonArray("updates");
        position = 0;
        client().view = view(data.getAsJsonObject("before"));
        client().presentation = null;
        client().scene = new TableScene(clock::get);
        client().scene.update(client().view, false, clock.get());
        expected = client().view.standingHint();
        require(expected != null && expected.shanten() == 0, "Missing real ready hand");
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    private void advance(boolean untilOwnDraw) {
        int checked = 0;
        while (position < updates.size()) {
            var frame = updates.get(position++).getAsJsonObject();
            var next = view(frame.getAsJsonObject("view"));
            client().scene.append(next, frame.getAsJsonArray("events"), clock.get());
            client().view = next;
            client().presentation = null;
            checked++;
            if (untilOwnDraw) {
                require(
                        Objects.equals(next.standingHint(), expected),
                        "Waits changed before own decision");
                require(
                        screen().fixedHint() != null,
                        "Waits flickered while presentation was unavailable");
                if (next.hand(0).size() % 3 == 2) {
                    require(next.decision() != null, "Missing own drawn-tile decision");
                    return;
                }
            }
            clock.addAndGet(1_000_000_000L);
        }
        require(checked > 0 && !untilOwnDraw, "No own draw in contiguous Rust frames");
        require(
                !Objects.equals(client().view.standingHint(), expected),
                "Own discard did not refresh waits");
        require(screen().fixedHint() == null, "Non-tenpai own discard left old waits visible");
    }

    private void restore() {
        client().view = savedView;
        client().presentation = savedPresentation;
        client().scene = savedScene;
        Minecraft.getInstance().setScreen(new SeatedTableScreen(client()));
    }

    List<HostedInteractionChecks.Step> steps() {
        var steps = new ArrayList<HostedInteractionChecks.Step>();
        for (String mode : List.of("riichi", "sichuan")) {
            steps.add(
                    new HostedInteractionChecks.Step(
                            "open " + mode + " hint lifecycle", () -> true, () -> open(mode)));
            steps.add(
                    new HostedInteractionChecks.Step(
                            "capture-hint-" + mode + "-waiting",
                            () -> true,
                            () -> require(screen().fixedHint() != null, "Initial waits missing")));
            steps.add(
                    new HostedInteractionChecks.Step(
                            "advance " + mode + " opponents without presentation",
                            () -> true,
                            () -> advance(true)));
            steps.add(
                    new HostedInteractionChecks.Step(
                            "capture-hint-" + mode + "-own-draw",
                            () -> true,
                            () ->
                                    require(
                                            screen().fixedHint() != null,
                                            "Own draw hid previous waits")));
            steps.add(
                    new HostedInteractionChecks.Step(
                            "refresh " + mode + " own decision", () -> true, () -> advance(false)));
            steps.add(
                    new HostedInteractionChecks.Step(
                            "restore " + mode + " lifecycle", () -> true, this::restore));
        }
        return steps;
    }
}
