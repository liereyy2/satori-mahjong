package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.SavedGames;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** The account catalog is a destination: live snapshots never redirect it to the table. */
final class TableScreen extends SatoriScreen {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final List<String> cursors = new ArrayList<>();
    private SavedGames.Page data;
    private int page, capacity, request;
    private boolean loading;
    private String error = "";

    TableScreen(ClientController client) {
        super(client, "games.title");
        cursors.add(null);
    }

    boolean loading() {
        return loading;
    }

    @Override
    protected void build() {
        int count = Math.max(1, (panelHeight - 130) / 48);
        if (count != capacity) {
            capacity = count;
            page = 0;
            cursors.clear();
            cursors.add(null);
            data = null;
            loading = false;
            request++;
        }
        int half = (panelWidth - 28) / 2;
        button(
                tr("create.title"),
                12,
                30,
                half,
                () -> client.user() != null && client.table != null && client.table.editable(),
                () -> minecraft.setScreen(new CreateScreen(client)));
        button(
                tr("games.refresh"),
                16 + half,
                30,
                half,
                () -> client.user() != null && !loading,
                () -> load(true));
        if (data != null)
            for (int i = 0; i < data.items().size(); i++) {
                var item = data.items().get(i);
                int y = 76 + i * 48, cell = (panelWidth - 32) / 3;
                button(
                        tr("table.copy_id"),
                        12,
                        y,
                        cell,
                        () -> !loading,
                        () -> {
                            minecraft.keyboardHandler.setClipboard(item.id().toString());
                            client.message = "id_copied";
                        });
                var enter =
                        button(
                                tr("games.enter"),
                                16 + cell,
                                y,
                                cell,
                                () -> !loading && client.canEnterGame(item.id()),
                                () -> client.enterGame(item.id()));
                enter.setTooltip(Tooltip.create(tr("games.enter_help")));
                var delete =
                        button(
                                tr("games.delete"),
                                20 + cell * 2,
                                y,
                                cell,
                                () -> !loading && item.canDelete(),
                                () ->
                                        minecraft.setScreen(
                                                new ConfirmScreen(
                                                        confirmed -> {
                                                            minecraft.setScreen(this);
                                                            if (confirmed)
                                                                client.deleteGame(
                                                                        item.id(),
                                                                        () -> load(true));
                                                        },
                                                        tr("games.delete"),
                                                        Component.translatable(
                                                                "satori.games.delete_confirm",
                                                                TIME.format(item.created())))));
                if (!item.canDelete()) delete.setTooltip(Tooltip.create(tr("games.owner_only")));
            }
        int footer = panelHeight - 72;
        button(
                tr("games.previous"),
                12,
                footer,
                70,
                () -> page > 0 && !loading,
                () -> {
                    page--;
                    load(false);
                });
        button(
                tr("games.next"),
                panelWidth - 82,
                footer,
                70,
                () -> data != null && data.nextCursor() != null && !loading,
                () -> {
                    String next = data.nextCursor();
                    while (cursors.size() > page + 1) cursors.removeLast();
                    cursors.add(next);
                    page++;
                    load(false);
                });
        button(
                "games.main",
                12,
                panelHeight - 44,
                half,
                () -> minecraft.setScreen(new AccountScreen(client)));
        button(
                tr("games.current"),
                16 + half,
                panelHeight - 44,
                half,
                () -> client.table != null,
                () -> {
                    if (client.game == null) client.openLobby();
                    else client.showGame();
                });
        if (data == null && !loading && error.isEmpty() && client.user() != null) load(false);
    }

    private void load(boolean reset) {
        if (reset) {
            page = 0;
            cursors.clear();
            cursors.add(null);
        }
        loading = true;
        error = "";
        int active = ++request;
        client.loadGames(
                cursors.get(page),
                capacity,
                value -> {
                    if (active != request) return;
                    data = value;
                    loading = false;
                    if (minecraft.screen == this) init();
                },
                code -> {
                    if (active != request) return;
                    loading = false;
                    error = code;
                    data = null;
                    if (minecraft.screen == this) init();
                });
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float delta) {
        super.render(g, x, y, delta);
        if (loading) label(g, tr("games.loading"), 12, 62, 0xFFBED2D4);
        else if (!error.isEmpty()) label(g, tr("games.load_failed"), 12, 62, 0xFFFFD78C);
        else if (data == null || data.items().isEmpty())
            label(g, tr("games.empty"), 12, 62, 0xFFBED2D4);
        else
            for (int i = 0; i < data.items().size(); i++) {
                var item = data.items().get(i);
                String description =
                        TIME.format(item.created())
                                + " · "
                                + tr("create." + item.length()).getString()
                                + " · "
                                + tr("status." + item.status()).getString();
                label(
                        g,
                        font.plainSubstrByWidth(description, panelWidth - 24),
                        12,
                        62 + 48 * i,
                        0xFFE7EBDE);
            }
        String number = Integer.toString(page + 1);
        label(g, number, (panelWidth - font.width(number)) / 2, panelHeight - 66, 0xFFBED2D4);
    }
}
