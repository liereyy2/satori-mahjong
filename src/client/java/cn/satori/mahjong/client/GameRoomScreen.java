package cn.satori.mahjong.client;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;

import java.util.ArrayList;
import java.util.List;

/** Central lobby and recovery controls; readiness is always confirmed by a cloud snapshot. */
final class GameRoomScreen extends SatoriScreen {
    private final List<Checkbox> ready = new ArrayList<>();
    private long version = -1;
    private boolean wasBusy;
    private boolean readinessPending;

    GameRoomScreen(ClientController client) {
        super(client, "room.title");
    }

    private boolean owner() {
        return client.view != null
                && client.user() != null
                && client.view.owner().equals(client.user().id());
    }

    @Override
    protected void build() {
        ready.clear();
        var view = client.view;
        version = view == null ? -1 : view.version;
        int half = (panelWidth - 28) / 2;
        int row = Math.min(40, (panelHeight - 122) / 4);
        if (view != null) {
            var seats = view.publicData().getAsJsonArray("seats");
            for (int i = 0; i < 4; i++) {
                var seat = seats.get(i).getAsJsonObject();
                int index = i;
                var box =
                        Checkbox.builder(tr("room.ready"), font)
                                .pos(left + panelWidth - 84, top + 49 + row * i)
                                .selected(seat.get("ready").getAsBoolean())
                                .onValueChange(
                                        (widget, checked) -> {
                                            if (client.view == null
                                                    || client.view.version != version) return;
                                            JsonObject body = new JsonObject();
                                            body.addProperty("ready", checked);
                                            readinessPending = true;
                                            client.command("PUT", "/ready", body);
                                            // Do not optimistically show a readiness value that
                                            // Rust has not accepted.
                                            widget.active = false;
                                            init();
                                        })
                                .build();
                box.setTooltip(
                        Tooltip.create(
                                tr(
                                        index == view.viewerSeat
                                                ? "room.ready_help"
                                                : "room.other_ready")));
                ready.add(addRenderableWidget(box));
            }
        }
        String status = view == null ? "connecting" : view.status();
        int y = panelHeight - 74;
        if (status.equals("waiting_players") || status.equals("ready"))
            button(
                    tr("table.start"),
                    12,
                    y,
                    half,
                    () -> owner() && client.view.status().equals("ready") && connected(),
                    () -> client.command("POST", "/start", new JsonObject()));
        else if (status.equals("paused"))
            button(
                    tr("table.manage"),
                    12,
                    y,
                    half,
                    this::owner,
                    () -> minecraft.setScreen(new ManageScreen(client)));
        else if (status.equals("finished"))
            button(
                    "table.result",
                    12,
                    y,
                    half,
                    () -> minecraft.setScreen(new MatchResultScreen(client)));
        else
            button(
                    tr("room.table"),
                    12,
                    y,
                    half,
                    () ->
                            client.view != null
                                    && client.view.status().equals("playing")
                                    && SeatedCamera.active(),
                    client::showGame);
        button(
                tr("table.reconnect"),
                16 + half,
                y,
                half,
                () -> client.game != null,
                () -> client.join(client.game.gameId.toString(), false));
        button(
                "games.title",
                12,
                panelHeight - 46,
                half,
                () -> minecraft.setScreen(new TableScreen(client)));
        button(
                tr("table.copy_id"),
                16 + half,
                panelHeight - 46,
                half,
                () -> client.game != null,
                () -> {
                    minecraft.keyboardHandler.setClipboard(client.game.gameId.toString());
                    client.message = "id_copied";
                });
    }

    private boolean connected() {
        return client.game != null
                && client.connection.equals("connected")
                && !client.game.hasPending();
    }

    @Override
    public void tick() {
        if ((client.view == null ? -1 : client.view.version) != version
                || wasBusy && !client.busy
                || readinessPending && !client.busy) {
            readinessPending = false;
            init();
        }
        wasBusy = client.busy;
        super.tick();
    }

    @Override
    public void refreshButtons() {
        super.refreshButtons();
        for (int i = 0; i < ready.size(); i++)
            ready.get(i).active =
                    !client.busy
                            && connected()
                            && client.view != null
                            && i == client.view.viewerSeat
                            && (client.view.status().equals("waiting_players")
                                    || client.view.status().equals("ready"));
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float delta) {
        super.render(g, x, y, delta);
        var view = client.view;
        label(
                g,
                tr(view == null ? "games.loading" : "status." + view.status()),
                12,
                32,
                0xFFFFD78C);
        if (view == null) return;
        int row = Math.min(40, (panelHeight - 122) / 4);
        var seats = view.publicData().getAsJsonArray("seats");
        for (int i = 0; i < 4; i++) {
            var seat = seats.get(i).getAsJsonObject();
            int yy = 49 + row * i;
            String id =
                    seat.get("kind").getAsString().equals("ai")
                            ? "AI " + (i + 1)
                            : seat.get("user_id").getAsString();
            String prefix =
                    new String[] {"東", "南", "西", "北"}[i]
                            + (i == view.viewerSeat ? " · " + tr("room.you").getString() : "");
            label(g, prefix, 12, yy, 0xFFB4D7CE);
            fittedLabel(g, id, 12, yy + 11, panelWidth - 108, 0xFFFFFFFF);
        }
    }
}
