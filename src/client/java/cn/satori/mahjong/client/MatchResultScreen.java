package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.MatchResult;

import net.minecraft.client.gui.GuiGraphics;

/** Whole-match results, separate from the four-direction per-round settlement. */
final class MatchResultScreen extends SatoriScreen {
    private MatchResult result;
    private boolean requested;
    private long nextRequest;

    MatchResultScreen(ClientController client) {
        super(client, "match.title");
    }

    @Override
    protected void build() {
        result =
                client.result == null || client.game == null
                        ? null
                        : MatchResult.parse(client.result, client.game.gameId);
        int half = (panelWidth - 28) / 2;
        if (client.hosted != null) {
            button(
                    tr("hosted.confirm"),
                    12,
                    panelHeight - 44,
                    half,
                    () -> client.result != null && !client.busy,
                    client.hosted::confirm);
            button("seat.leave", 16 + half, panelHeight - 44, half, SatoriClient::leaveSeat);
            return;
        }
        if (result == null) {
            button(
                    tr("games.refresh"),
                    12,
                    64,
                    panelWidth - 24,
                    () -> !client.busy,
                    () -> {
                        requested = true;
                        client.fetchResult();
                    });
        }
        button(
                "games.main",
                12,
                panelHeight - 44,
                half,
                () -> minecraft.setScreen(new AccountScreen(client)));
        button(
                "games.title",
                16 + half,
                panelHeight - 44,
                half,
                () -> minecraft.setScreen(new TableScreen(client)));
    }

    @Override
    public void tick() {
        super.tick();
        if (result == null
                && (!requested || client.hosted != null && System.nanoTime() >= nextRequest)
                && !client.busy
                && client.game != null) {
            requested = true;
            nextRequest = System.nanoTime() + 3_000_000_000L;
            client.fetchResult();
        }
        if (result == null && client.result != null) init();
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float delta) {
        super.render(g, x, y, delta);
        if (result == null) {
            label(g, tr("match.loading"), 12, 38, 0xFFBED2D4);
            return;
        }
        int row = Math.min(45, (panelHeight - 94) / 4);
        label(g, tr("match.player"), 12, 34, 0xFFB4D7CE);
        label(g, tr("match.rank"), panelWidth - 164, 34, 0xFFB4D7CE);
        label(g, tr("match.score"), panelWidth - 116, 34, 0xFFB4D7CE);
        label(g, tr("match.pt"), panelWidth - 52, 34, 0xFFB4D7CE);
        for (int i = 0; i < 4; i++) {
            var player = result.players().get(i);
            int yy = 49 + i * row;
            boolean own = client.view != null && player.seat() == client.view.viewerSeat;
            g.fill(
                    left + 10,
                    top + yy - 3,
                    left + panelWidth - 10,
                    top + yy + row - 6,
                    own ? 0x88466758 : 0x662C3838);
            int idWidth = panelWidth - 186;
            if (!player.name().isEmpty()) {
                fittedLabel(g, player.name(), 14, yy, idWidth, 0xFFFFFFFF);
                fittedLabel(g, player.userId(), 14, yy + 10, idWidth, 0xFFBED2D4);
            } else if (player.ai()) label(g, player.userId(), 14, yy + 4, 0xFFFFFFFF);
            else if (player.userId().isEmpty()) label(g, tr("lobby.empty"), 14, yy + 4, 0xFFFFFFFF);
            else {
                String id = player.userId();
                fittedLabel(g, id.substring(0, 18), 14, yy, idWidth, 0xFFFFFFFF);
                fittedLabel(g, id.substring(18), 14, yy + 10, idWidth, 0xFFFFFFFF);
            }
            label(g, "#" + player.rank(), panelWidth - 164, yy + 4, 0xFFFFD78C);
            label(g, Integer.toString(player.score()), panelWidth - 116, yy + 4, 0xFFFFFFFF);
            label(
                    g,
                    (player.pt() > 0 ? "+" : "") + player.pt(),
                    panelWidth - 52,
                    yy + 4,
                    player.pt() < 0 ? 0xFFFFA69A : 0xFF9BE3B4);
        }
    }
}
