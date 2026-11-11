package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.TableOrientation;

import net.minecraft.client.gui.GuiGraphics;

import java.util.UUID;

/** Physical gathering before cloud creation; cloud readiness stays in GameRoomScreen. */
final class TableLobbyScreen extends SatoriScreen {
    private String attempted = "";

    TableLobbyScreen(ClientController client) {
        super(client, "lobby.title");
    }

    @Override
    protected int panelTopInset() {
        return 40;
    }

    @Override
    protected void build() {
        tableActions();
        int half = (panelWidth - 28) / 2;
        button(
                tr("create.title"),
                12,
                panelHeight - 72,
                half,
                () ->
                        client.user() != null
                                && client.table != null
                                && client.table.editable()
                                && client.table.gameId().isEmpty(),
                () -> minecraft.setScreen(new CreateScreen(client)));
        button(
                tr("games.enter"),
                16 + half,
                panelHeight - 72,
                half,
                () -> client.table != null && !client.table.gameId().isEmpty(),
                this::enter);
        button(
                "games.title",
                12,
                panelHeight - 44,
                half,
                () -> minecraft.setScreen(new TableScreen(client)));
        button(
                "games.main",
                16 + half,
                panelHeight - 44,
                half,
                () -> minecraft.setScreen(new AccountScreen(client)));
    }

    private void enter() {
        attempted = client.table.gameId();
        client.enterGame(UUID.fromString(attempted));
    }

    @Override
    public void tick() {
        super.tick();
        if (client.user() != null
                && !client.busy
                && client.lobby != null
                && client.table != null
                && !client.table.gameId().isEmpty()
                && !client.table.gameId().equals(attempted)) enter();
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        int row = Math.min(36, Math.max(12, (panelHeight - 126) / 4));
        for (int seat = 0; seat < 4; seat++) {
            String text = seat + " · " + tr("lobby.empty").getString();
            if (client.lobby != null) {
                var player = client.lobby.seats().get(TableOrientation.chairForSeat(seat));
                if (!player.minecraftName().isEmpty())
                    text =
                            seat
                                    + " · "
                                    + player.minecraftName()
                                    + " · "
                                    + (player.userId().isEmpty()
                                            ? tr("lobby.login").getString()
                                            : player.name())
                                    + (player.host() ? " · " + tr("lobby.host").getString() : "");
            }
            fittedLabel(graphics, text, 12, 38 + row * seat, panelWidth - 24, 0xFFE7EBDE);
        }
    }
}
