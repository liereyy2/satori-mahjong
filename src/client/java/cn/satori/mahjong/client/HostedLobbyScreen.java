package cn.satori.mahjong.client;

import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;

/** Players join by sitting, then operate only their own readiness. */
final class HostedLobbyScreen extends SatoriScreen {
    private final net.minecraft.client.gui.components.Button[] seatButtons =
            new net.minecraft.client.gui.components.Button[4];

    HostedLobbyScreen(ClientController client) {
        super(client, "hosted.title");
    }

    private boolean preparing() {
        return client.hosted.configured() && client.hosted.phase().equals("preparing");
    }

    private String kind(int seat) {
        var state = client.hosted.table();
        return state == null
                ? "empty"
                : state.getAsJsonArray("seats")
                        .get(seat)
                        .getAsJsonObject()
                        .get("kind")
                        .getAsString();
    }

    private boolean sichuan() {
        var state = client.hosted.table();
        return state != null && state.get("rule_mode").getAsString().equals("sichuan");
    }

    private String length() {
        var state = client.hosted.table();
        return state == null ? "hanchan" : state.get("length").getAsString();
    }

    private String nextLength() {
        return switch (length()) {
            case "east" -> "hanchan";
            case "hanchan" -> "east";
            case "4" -> "8";
            case "8" -> "16";
            case "16" -> "4";
            default -> throw new IllegalStateException("unsupported_rules");
        };
    }

    private boolean ready() {
        var self = client.hosted.self();
        return self != null && self.get("ready").getAsBoolean();
    }

    private boolean paused() {
        return client.hosted.phase().equals("paused");
    }

    net.minecraft.network.chat.Component phaseHelp() {
        if (paused()) {
            if (client.view != null
                    && client.view
                            .gameId
                            .toString()
                            .equals(client.hosted.table().get("game_id").getAsString())
                    && client.view.status().equals("paused")) {
                if (client.view.needsCredits()) return tr("hosted.credits_help");
                if (client.view.pauseReason().equals("ai_terminal_invalid_action"))
                    return tr("hosted.invalid_ai_help");
            }
            return tr("hosted.paused_help");
        }
        return tr(
                client.hosted.phase().equals("finished")
                        ? "hosted.confirm_wait"
                        : "hosted.start_help");
    }

    @Override
    protected int panelTopInset() {
        return 40;
    }

    private int seatRowHeight() {
        return Math.min(36, Math.max(16, (panelHeight - 102) / 4));
    }

    @Override
    protected void build() {
        tableActions();
        int row = seatRowHeight();
        for (int seat = 0; seat < 4; seat++) {
            final int position = seat;
            seatButtons[seat] =
                    button(
                            () ->
                                    tr(
                                            kind(position).equals("ai")
                                                    ? "hosted.remove_ai"
                                                    : "hosted.add_ai"),
                            panelWidth - 94,
                            38 + seat * row,
                            82,
                            () ->
                                    preparing()
                                            && client.hosted.self() != null
                                            && (kind(position).equals("ai")
                                                    || client.hosted
                                                            .packet
                                                            .get("ai_enabled")
                                                            .getAsBoolean()),
                            () -> {
                                var body = new JsonObject();
                                body.addProperty("seat", position);
                                body.addProperty("enabled", !kind(position).equals("ai"));
                                client.hosted.command("ai", body);
                            });
            seatButtons[seat].setHeight(Math.min(20, row - 2));
        }
        button(
                () -> tr(sichuan() ? "rules.sichuan" : "rules.riichi"),
                panelWidth - 208,
                7,
                80,
                () -> preparing() && client.hosted.self() != null,
                () -> {
                    var body = new JsonObject();
                    body.addProperty("rule_mode", sichuan() ? "riichi" : "sichuan");
                    client.hosted.command("rules", body);
                });
        button(
                () ->
                        sichuan()
                                ? net.minecraft.network.chat.Component.translatable(
                                        "satori.rules.rounds", length())
                                : tr(length().equals("east") ? "create.east" : "create.hanchan"),
                panelWidth - 124,
                7,
                112,
                () -> preparing() && client.hosted.self() != null,
                () -> {
                    var body = new JsonObject();
                    body.addProperty("length", nextLength());
                    client.hosted.command("length", body);
                });
        button(
                () -> tr(paused() ? "manage.resume" : ready() ? "hosted.unready" : "hosted.ready"),
                (panelWidth - 160) / 2,
                panelHeight - 44,
                160,
                () -> (preparing() || paused()) && client.hosted.self() != null,
                () -> {
                    var body = new JsonObject();
                    if (paused()) client.hosted.command("resume", body);
                    else {
                        body.addProperty("ready", !ready());
                        client.hosted.command("ready", body);
                    }
                });
        updateSeatButtons();
    }

    @Override
    public void tick() {
        super.tick();
        updateSeatButtons();
    }

    private void updateSeatButtons() {
        for (int seat = 0; seat < 4; seat++)
            seatButtons[seat].visible =
                    client.hosted.table() != null && !kind(seat).equals("human");
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float dt) {
        super.render(g, x, y, dt);
        var state = client.hosted.table();
        if (state == null) {
            label(g, tr("hosted.config_help"), 12, 40, 0xFFFFD78C);
            return;
        }
        int row = seatRowHeight();
        for (int seat = 0; seat < 4; seat++) {
            var member = state.getAsJsonArray("seats").get(seat).getAsJsonObject();
            String kind = member.get("kind").getAsString();
            String text =
                    seat
                            + " · "
                            + (kind.equals("human")
                                    ? member.get("name").getAsString()
                                    : tr(kind.equals("ai") ? "create.ai" : "lobby.empty")
                                            .getString());
            if (kind.equals("human"))
                text +=
                        "  "
                                + (member.get("ready").getAsBoolean() ? "[x]" : "[ ]")
                                + (member.get("connected").getAsBoolean()
                                        ? ""
                                        : " · " + tr("hosted.offline").getString());
            fittedLabel(g, text, 12, 43 + seat * row, panelWidth - 116, 0xFFE7EBDE);
        }
        fittedLabel(g, phaseHelp().getString(), 12, panelHeight - 66, panelWidth - 24, 0xFFBED2D4);
    }
}
