package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.CloudView;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** An illegal AI response is a saved pause, not a request to top up the account. */
final class PausedLobbyInteractionChecks {
    private ClientController active;
    private ClientController client;

    private ClientController client() {
        return client;
    }

    private void open() {
        active = SatoriClient.TABLES.active();
        client = new ClientController(active.account);
        client.message = "";
        client.table = active.table;
        client.dimension = active.dimension;
        client.hosted = new HostedClient(client);
        client.hosted.packet = active.hosted.packet.deepCopy();
        try (var reader =
                new InputStreamReader(
                        java.util.Objects.requireNonNull(
                                getClass().getResourceAsStream("/cloud-view.json")),
                        StandardCharsets.UTF_8)) {
            var raw = JsonParser.parseReader(reader).getAsJsonObject();
            raw.addProperty("game_id", active.view.gameId.toString());
            raw.addProperty("status", "paused");
            raw.addProperty("pause_reason", "ai_terminal_invalid_action");
            client().view =
                    new CloudView(raw, active.view.gameId, raw.get("viewer_seat").getAsInt());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        client().hosted.table().addProperty("phase", "paused");
        Minecraft.getInstance().setScreen(new HostedLobbyScreen(client()));
    }

    private void verify() {
        var screen = Minecraft.getInstance().screen;
        var buttons =
                screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .toList();
        var resume = Component.translatable("satori.manage.resume").getString();
        var recharge = Component.translatable("satori.credits.resume").getString();
        if (!(screen instanceof HostedLobbyScreen)
                || client().view.needsCredits()
                || buttons.stream()
                        .noneMatch(b -> b.active && b.getMessage().getString().equals(resume))
                || buttons.stream().anyMatch(b -> b.getMessage().getString().equals(recharge))) {
            throw new AssertionError(
                    "Non-credits pause: "
                            + screen.getClass().getSimpleName()
                            + " / "
                            + client.hosted.phase()
                            + " / "
                            + buttons.stream()
                                    .map(b -> b.getMessage().getString() + ":" + b.active)
                                    .toList());
        }
        if (!((HostedLobbyScreen) screen)
                .phaseHelp()
                .getString()
                .equals(Component.translatable("satori.hosted.invalid_ai_help").getString()))
            throw new AssertionError("Saved AI pause explanation is missing");
    }

    List<HostedInteractionChecks.Step> steps() {
        return List.of(
                new HostedInteractionChecks.Step(
                        "open illegal-action pause", () -> true, this::open),
                new HostedInteractionChecks.Step("capture-paused-lobby", () -> true, this::verify),
                new HostedInteractionChecks.Step(
                        "restore paused-lobby fixture",
                        () -> true,
                        () -> {
                            client.account.controllers.remove(client);
                            Minecraft.getInstance().setScreen(new SeatedTableScreen(active));
                        }));
    }
}
