package cn.satori.mahjong.client;

import cn.satori.mahjong.HostedPackets;

import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.core.BlockPos;

/** Administrator UI. The MC server checks OP permission and distance for every operation. */
final class ControlScreen extends SatoriScreen {
    final BlockPos position;
    private JsonObject state;
    private EditBox email, password;
    private int page, matchPage;
    private boolean matches;
    private final java.util.List<net.minecraft.client.gui.components.Button> highlights =
            new java.util.ArrayList<>();
    private long poll;

    ControlScreen(ClientController client, BlockPos position, JsonObject state) {
        super(client, "control.title");
        this.position = position;
        this.state = state;
    }

    void update(JsonObject value) {
        boolean structureChanged =
                !state.get("logged_in").equals(value.get("logged_in"))
                        || !state.get("keys").equals(value.get("keys"));
        state = value;
        client.busy = value.get("busy").getAsBoolean();
        client.message = value.get("error").getAsString();
        if (structureChanged && (!matches || !logged())) {
            if (!logged()) matches = false;
            init();
        } else refreshButtons();
    }

    private void send(String kind, JsonObject data) {
        if (!kind.equals("refresh") && (client.busy || client.account.authenticating)) return;
        data.addProperty("kind", kind);
        SatoriClient.sendHosted(
                new HostedPackets.Request(
                        minecraft.level.dimension(), position, true, data.toString()));
        if (!kind.equals("refresh")) client.busy = true;
    }

    private boolean logged() {
        return state.get("logged_in").getAsBoolean();
    }

    @Override
    protected void build() {
        highlights.clear();
        if (!logged()) {
            email =
                    input(
                            "account.email",
                            12,
                            54,
                            panelWidth - 24,
                            254,
                            email == null ? state.get("username").getAsString() : email.getValue(),
                            false);
            password =
                    input(
                            "account.password",
                            12,
                            84,
                            panelWidth - 24,
                            1024,
                            password == null ? "" : password.getValue(),
                            true);
            button(
                    tr("account.login"),
                    12,
                    116,
                    panelWidth - 24,
                    () -> !email.getValue().isBlank() && !password.getValue().isEmpty(),
                    () -> {
                        var body = new JsonObject();
                        body.addProperty("username", email.getValue());
                        body.addProperty("password", password.getValue());
                        send("login", body);
                        password.setValue("");
                    });
            button("website.login", 12, 145, panelWidth - 24, () -> SatoriWebsite.openLogin(this));
        } else if (matches) {
            buildMatches();
        } else {
            var keys = state.getAsJsonArray("keys");
            int visible = panelHeight < 250 ? 2 : 3;
            int pages = Math.max(1, (keys.size() + visible - 1) / visible);
            page = Math.min(page, pages - 1);
            for (int i = 0; i < visible && page * visible + i < keys.size(); i++) {
                var key = keys.get(page * visible + i).getAsJsonObject();
                String id = key.get("id").getAsString();
                java.util.function.Supplier<net.minecraft.network.chat.Component> label =
                        () ->
                                net.minecraft.network.chat.Component.literal(
                                        (id.equals(state.get("key_id").getAsString())
                                                        ? "[x] "
                                                        : "[ ] ")
                                                + key.get("name").getAsString()
                                                + " · "
                                                + key.get("masked").getAsString());
                button(
                        label,
                        12,
                        64 + i * 22,
                        panelWidth - 24,
                        () -> key.get("available").getAsBoolean(),
                        () -> {
                            var body = new JsonObject();
                            body.addProperty("key_id", id);
                            send("select", body);
                        });
            }
            int third = (panelWidth - 32) / 3;
            int navigation = 68 + visible * 22;
            button(
                    net.minecraft.network.chat.Component.literal("<"),
                    12,
                    navigation,
                    third,
                    () -> page > 0,
                    () -> {
                        page--;
                        init();
                    });
            button(
                    "keys.refresh",
                    16 + third,
                    navigation,
                    third,
                    () -> send("keys", new JsonObject()));
            button(
                    net.minecraft.network.chat.Component.literal(">"),
                    20 + third * 2,
                    navigation,
                    third,
                    () -> page + 1 < pages,
                    () -> {
                        page++;
                        init();
                    });
            button(
                    "control.no_ai",
                    12,
                    navigation + 26,
                    (panelWidth - 28) / 2,
                    () -> {
                        var body = new JsonObject();
                        body.addProperty("key_id", "");
                        send("select", body);
                    });
            button(
                    "keys.create_web",
                    16 + (panelWidth - 28) / 2,
                    navigation + 26,
                    (panelWidth - 28) / 2,
                    () ->
                            ConfirmLinkScreen.confirmLinkNow(
                                    this, "https://mj.satori-ai.cn/profile?view=api-key"));
            button(
                    "account.logout",
                    12,
                    panelHeight - 66,
                    (panelWidth - 28) / 2,
                    () -> send("logout", new JsonObject()));
            button(
                    "credits.open",
                    16 + (panelWidth - 28) / 2,
                    panelHeight - 66,
                    (panelWidth - 28) / 2,
                    () -> SatoriWebsite.openCredits(this));
        }
        if (logged())
            button(
                    () ->
                            matches
                                    ? tr("control.account")
                                    : net.minecraft.network.chat.Component.translatable(
                                            "satori.control.matches",
                                            state.getAsJsonArray("matches").size()),
                    panelWidth - 112,
                    7,
                    100,
                    () -> true,
                    () -> {
                        matches = !matches;
                        init();
                    });
        button("close", 12, panelHeight - 40, panelWidth - 24, this::onClose);
    }

    @Override
    public void tick() {
        super.tick();
        if (System.nanoTime() >= poll) {
            poll = System.nanoTime() + 1_000_000_000L;
            send("refresh", new JsonObject());
        }
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float dt) {
        super.render(g, x, y, dt);
        fittedLabel(
                g,
                logged() ? state.get("name").getAsString() : tr("control.help").getString(),
                12,
                34,
                panelWidth - 24,
                0xFFBED2D4);
        if (logged())
            fittedLabel(
                    g,
                    creditsLabel().getString(),
                    12,
                    48,
                    panelWidth - 24,
                    state.get("credits_error").getAsString().isEmpty() ? 0xFF62DCC2 : 0xFFFFD78C);
        if (logged() && !matches && state.getAsJsonArray("keys").isEmpty())
            label(g, tr("keys.empty"), 12, 78, 0xFFFFD78C);
        if (logged() && matches) renderMatches(g);
    }

    private int matchRows() {
        return Math.max(1, (panelHeight - 144) / 38);
    }

    private JsonObject match(int row) {
        var list = state.getAsJsonArray("matches");
        int index = matchPage * matchRows() + row;
        return index < list.size() ? list.get(index).getAsJsonObject() : null;
    }

    private void buildMatches() {
        for (int row = 0; row < matchRows(); row++) {
            final int index = row;
            highlights.add(
                    button(
                            tr("control.highlight"),
                            panelWidth - 82,
                            69 + row * 38,
                            70,
                            () ->
                                    match(index) != null
                                            && match(index)
                                                    .get("dimension")
                                                    .getAsString()
                                                    .equals(
                                                            minecraft
                                                                    .level
                                                                    .dimension()
                                                                    .identifier()
                                                                    .toString()),
                            () -> {
                                var body = new JsonObject();
                                body.add("table_id", match(index).get("table_id"));
                                send("highlight", body);
                            }));
        }
        button(
                net.minecraft.network.chat.Component.literal("<"),
                12,
                panelHeight - 66,
                80,
                () -> matchPage > 0,
                () -> {
                    matchPage--;
                    refreshButtons();
                });
        button(
                net.minecraft.network.chat.Component.literal(">"),
                panelWidth - 92,
                panelHeight - 66,
                80,
                () -> (matchPage + 1) * matchRows() < state.getAsJsonArray("matches").size(),
                () -> {
                    matchPage++;
                    refreshButtons();
                });
    }

    @Override
    public void refreshButtons() {
        if (matches) {
            int count = state.getAsJsonArray("matches").size();
            matchPage = Math.min(matchPage, Math.max(0, (count - 1) / matchRows()));
        }
        super.refreshButtons();
        for (int row = 0; row < highlights.size(); row++)
            highlights.get(row).visible = matches && match(row) != null;
    }

    private void renderMatches(GuiGraphics g) {
        var list = state.getAsJsonArray("matches");
        if (list.isEmpty()) label(g, tr("control.no_matches"), 12, 78, 0xFFBED2D4);
        label(
                g,
                (matchPage + 1)
                        + " / "
                        + Math.max(1, (list.size() + matchRows() - 1) / matchRows()),
                panelWidth / 2 - 14,
                panelHeight - 60,
                0xFFBED2D4);
        for (int row = 0; row < matchRows(); row++) {
            var item = match(row);
            if (item == null) continue;
            var names = new java.util.ArrayList<String>();
            for (var raw : item.getAsJsonArray("seats")) {
                var seat = raw.getAsJsonObject();
                names.add(
                        seat.get("kind").getAsString().equals("ai")
                                ? "AI"
                                : seat.get("name").getAsString());
            }
            fittedLabel(
                    g, String.join(" / ", names), 12, 69 + row * 38, panelWidth - 106, 0xFFFFFFFF);
            String address =
                    item.get("dimension").getAsString()
                            + "  "
                            + item.get("x").getAsInt()
                            + ", "
                            + item.get("y").getAsInt()
                            + ", "
                            + item.get("z").getAsInt();
            fittedLabel(
                    g,
                    tr("control.phase." + item.get("phase").getAsString()).getString()
                            + " · "
                            + address,
                    12,
                    83 + row * 38,
                    panelWidth - 106,
                    0xFFBED2D4);
        }
    }

    net.minecraft.network.chat.Component creditsLabel() {
        if (!state.get("credits_error").getAsString().isEmpty()) return tr("control.credits_error");
        if (state.get("credits").isJsonNull()) return tr("control.credits_loading");
        return net.minecraft.network.chat.Component.translatable(
                "satori.control.credits", state.get("credits").getAsString());
    }
}
