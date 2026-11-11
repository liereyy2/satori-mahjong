package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import cn.satori.mahjong.TableBlockEntity;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Native renderer verification shared by the Fabric and Forge release artifacts. */
final class DiceWallInteractionChecks {
    private final AtomicLong clock = new AtomicLong(0);
    private CloudView previousView;
    private TableScene previousScene;

    private void open() {
        var client = SatoriClient.TABLES.active();
        previousView = client.view;
        previousScene = client.scene;
        try (var input = getClass().getResourceAsStream("/electric-examples.json")) {
            var example =
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                            .getAsJsonObject()
                            .getAsJsonObject("start_kyoku")
                            .getAsJsonObject("update");
            var raw = example.getAsJsonObject("view").deepCopy();
            raw.addProperty("game_id", previousView.gameId.toString());
            client.view = new CloudView(raw, previousView.gameId, 0);
            client.scene = new TableScene(clock::get);
            client.scene.update(client.view, example.getAsJsonArray("events"), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        clock.set(2_010_000_000L);
    }

    private void verify() {
        var mc = Minecraft.getInstance();
        var table = (TableBlockEntity) mc.level.getBlockEntity(SeatedCamera.center());
        var renderer =
                mc.getBlockEntityRenderDispatcher()
                        .<TableBlockEntity, TableRenderer.State>getRenderer(table);
        var state = renderer.createRenderState();
        renderer.extractRenderState(
                table, state, 0, mc.gameRenderer.getMainCamera().position(), null);
        var client = SatoriClient.TABLES.active();
        require(state.dice.size() == 2, "Actual renderer lost the two settled dice");
        require(state.tiles.size() == 136, "Wall must remain full until the dice settle");
        for (int i = 0; i < 2; i++)
            require(
                    state.dice.get(i).top() == client.view.wall.dice.get(i),
                    "Dice differ from Rust roll");
        var settled = state.dice;
        clock.set(2_100_000_000L);
        require(
                client.scene.dice(clock.get()).equals(settled),
                "Dice must stop moving before the deal");
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step(
                        "start dice wall renderer",
                        () -> Minecraft.getInstance().screen instanceof SeatedTableScreen,
                        this::open),
                new HostedInteractionChecks.Step("capture-dice-wall", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "finish dice wall renderer",
                        () -> true,
                        () -> {
                            var client = SatoriClient.TABLES.active();
                            clock.set(4_300_000_000L);
                            require(
                                    client.scene
                                            .sample(clock.get())
                                            .equals(TableScene.layout(client.view)),
                                    "Opening does not converge to Rust wall");
                            require(
                                    client.scene.dice(clock.get()).isEmpty(),
                                    "Dice must be cleared after the opening");
                            client.view = previousView;
                            client.scene = previousScene;
                        }));
    }
}
