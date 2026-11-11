package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.CloudView;
import cn.satori.mahjong.protocol.SettlementLayout;

import com.google.gson.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft inventory-style round summary. Every hand, yaku and score comes from Rust. */
final class SettlementScreen extends Screen implements TablePage {
    private final ClientController client;

    @Override
    public ClientController controller() {
        return client;
    }

    private JsonObject result;
    private final String id;
    private int entry, scroll;
    private float scale;
    private int left, top;
    private Button confirm;

    SettlementScreen(ClientController client, JsonObject result) {
        super(tr("title"));
        this.client = client;
        this.result = result.deepCopy();
        id = result.get("id").getAsString();
    }

    private static Component tr(String key) {
        return Component.translatable("satori.settlement." + key);
    }

    @Override
    protected void init() {
        scale =
                Math.min(
                        1.25f,
                        Math.min(
                                (width - 20f) / SettlementLayout.WIDTH,
                                (height - 20f) / SettlementLayout.HEIGHT));
        left = Math.round((width - SettlementLayout.WIDTH * scale) / 2);
        top = Math.round((height - SettlementLayout.HEIGHT * scale) / 2);
        int buttonWidth =
                Math.min(
                        width - 40,
                        Math.max(
                                Math.round(180 * scale),
                                Math.max(font.width(tr("continue")), font.width(tr("waiting")))
                                        + 20));
        confirm =
                addRenderableWidget(
                        Button.builder(
                                        tr("continue"),
                                        b -> {
                                            if (result.get("finished").getAsBoolean()) {
                                                client.dismissedSettlement = id;
                                                minecraft.setScreen(new MatchResultScreen(client));
                                            } else {
                                                var action = continuation();
                                                if (action != null) client.action(action);
                                            }
                                        })
                                .bounds(
                                        (width - buttonWidth) / 2,
                                        top + Math.round(SettlementLayout.HEIGHT * scale) - 28,
                                        buttonWidth,
                                        20)
                                .build());
        if (result.getAsJsonArray("entries").size() > 1) {
            addRenderableWidget(
                    Button.builder(
                                    Component.literal("<"),
                                    b -> {
                                        entry =
                                                Math.floorMod(
                                                        entry - 1,
                                                        result.getAsJsonArray("entries").size());
                                        scroll = 0;
                                    })
                            .bounds(
                                    left + Math.round(494 * scale),
                                    top + Math.round(8 * scale),
                                    Math.max(18, Math.round(25 * scale)),
                                    Math.max(14, Math.round(18 * scale)))
                            .build());
            addRenderableWidget(
                    Button.builder(
                                    Component.literal(">"),
                                    b -> {
                                        entry =
                                                (entry + 1)
                                                        % result.getAsJsonArray("entries").size();
                                        scroll = 0;
                                    })
                            .bounds(
                                    left + Math.round(551 * scale),
                                    top + Math.round(8 * scale),
                                    Math.max(18, Math.round(25 * scale)),
                                    Math.max(14, Math.round(18 * scale)))
                            .build());
        }
    }

    private CloudView.Action continuation() {
        return client.view == null
                ? null
                : client.view.actions().stream()
                        .filter(a -> a.type().equals("continue"))
                        .findFirst()
                        .orElse(null);
    }

    @Override
    public void tick() {
        if (!SeatedCamera.active()) {
            onClose();
            return;
        }
        if (client.view == null) {
            minecraft.setScreen(new AccountScreen(client));
            return;
        }
        if (client.presentation != null) {
            var next = client.presentation.settlement();
            if (next == null || !next.get("id").getAsString().equals(id)) {
                minecraft.setScreen(new SeatedTableScreen(client));
                return;
            }
            result = next;
        }
        boolean finished = result.get("finished").getAsBoolean();
        confirm.setMessage(
                tr(finished ? "return" : continuation() == null ? "waiting" : "continue"));
        confirm.active = finished || continuation() != null && client.canAct();
    }

    private static String wind(int seat) {
        return new String[] {"東", "南", "西", "北"}[Math.floorMod(seat, 4)];
    }

    private String seat(int value) {
        return wind(value - result.get("oya").getAsInt())
                + " "
                + (client.view != null && value == client.view.viewerSeat
                        ? tr("you").getString()
                        : tr("seat").getString());
    }

    private void text(GuiGraphics g, Component value, int x, int y, int color) {
        g.drawString(font, value, x, y, color, false);
    }

    private void text(GuiGraphics g, String value, int x, int y, int color) {
        text(g, Component.literal(value), x, y, color);
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float delta) {
        renderBackground(g, x, y, delta);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, scale);
        int panelWidth = SettlementLayout.WIDTH, panelHeight = SettlementLayout.HEIGHT;
        g.fill(0, 0, panelWidth, panelHeight, 0xFF161616);
        g.fill(2, 2, panelWidth - 2, panelHeight - 2, 0xFFC6C6C6);
        g.fill(3, 3, panelWidth - 3, 5, 0xFFFFFFFF);
        g.fill(3, 3, 5, panelHeight - 3, 0xFFFFFFFF);
        g.fill(panelWidth - 5, 5, panelWidth - 3, panelHeight - 3, 0xFF555555);
        g.fill(5, panelHeight - 5, panelWidth - 5, panelHeight - 3, 0xFF555555);
        g.fill(9, 9, panelWidth - 9, 35, 0xFF343C36);
        text(g, tr("title"), 18, 18, 0xFFF4E6BC);
        text(
                g,
                client.view.ruleMode().equals("sichuan")
                        ? Component.translatable(
                                        "satori.settlement.sichuan_round",
                                        result.get("round").getAsInt())
                                .getString()
                        : wind(result.get("bakaze").getAsInt() - 27)
                                + result.get("kyoku").getAsInt()
                                + "局  "
                                + result.get("honba").getAsInt()
                                + "本场",
                112,
                18,
                0xFFE4E4DB);
        var detail = result.getAsJsonArray("entries").get(entry).getAsJsonObject();
        boolean hora = detail.get("kind").getAsString().equals("hora");
        text(
                g,
                hora
                        ? seat(detail.get("actor").getAsInt())
                                + "  "
                                + tr(detail.get("actor").equals(detail.get("target"))
                                                ? "tsumo"
                                                : "ron")
                                        .getString()
                        : tr("draw." + detail.get("reason").getAsString()).getString(),
                18,
                46,
                0xFF242424);
        g.fill(14, 62, 320, 258, 0xFF444B45);
        g.fill(15, 63, 319, 257, 0xFF242F2B);
        g.enableScissor(16, 64, 318, 256);
        g.pose().pushMatrix();
        g.pose().translate(0, -scroll);
        if (hora) drawWin(g, detail);
        else drawDraw(g, detail);
        g.pose().popMatrix();
        g.disableScissor();
        drawScores(g);
        if (contentBottom(detail) > 252) text(g, tr("scroll"), 20, 260, 0xFF4F4F4F);
        g.pose().popMatrix();
        super.render(g, x, y, delta);
    }

    private void centered(GuiGraphics g, String value, int center, int y, int color) {
        text(g, value, center - font.width(value) / 2, y, color);
    }

    private void drawScores(GuiGraphics g) {
        centered(g, tr("scores").getString(), 460, 44, 0xFF242424);
        centered(g, tr("score_order").getString(), 460, 58, 0xFF4F4F4F);
        for (var card : SettlementLayout.scores(result, client.view.viewerSeat)) {
            int xx = card.x(), yy = card.y();
            int w = SettlementLayout.CARD_WIDTH, h = SettlementLayout.CARD_HEIGHT;
            boolean own = card.seat() == client.view.viewerSeat;
            g.fill(xx, yy, xx + w, yy + h, own ? 0xFF807044 : 0xFF777C76);
            g.fill(xx + 1, yy + 1, xx + w - 1, yy + h - 1, 0xFFF1F0E8);
            g.fill(xx + 2, yy + 2, xx + w - 2, yy + 3, 0xFFFFFFFF);
            g.fill(xx + 2, yy + h - 3, xx + w - 2, yy + h - 2, 0xFFB0B0A8);
            centered(g, seat(card.seat()), xx + w / 2, yy + 7, 0xFF444A43);
            centered(g, Integer.toString(card.before()), xx + w / 2, yy + 23, 0xFF202520);
            centered(
                    g,
                    card.changeText(),
                    xx + w / 2,
                    yy + 40,
                    card.delta() > 0 ? 0xFF145532 : card.delta() < 0 ? 0xFF9A2727 : 0xFF555B55);
            if (!result.get("finished").getAsBoolean() && card.confirmed()) {
                g.fill(xx + w - 13, yy + 5, xx + w - 4, yy + 14, 0xFF24583D);
                g.fill(xx + w - 11, yy + 9, xx + w - 9, yy + 11, 0xFFFFFFFF);
                g.fill(xx + w - 9, yy + 7, xx + w - 7, yy + 11, 0xFFFFFFFF);
            }
        }
    }

    private void drawHandRow(
            GuiGraphics g, java.util.List<Integer> hand, JsonArray melds, int top, boolean win) {
        var sizes = new java.util.ArrayList<Integer>();
        for (var raw : melds) sizes.add(raw.getAsJsonObject().getAsJsonArray("tiles").size());
        var row = SettlementLayout.handRow(hand.size(), sizes, win);
        g.pose().pushMatrix();
        g.pose().translate(25, top);
        g.pose().scale(row.scale(), row.scale());
        for (int i = 0; i < hand.size(); i++)
            GuiTile.draw(
                    g,
                    hand.get(i),
                    row.handX(i),
                    0,
                    SettlementLayout.HAND_TILE_WIDTH,
                    SettlementLayout.HAND_TILE_HEIGHT);
        for (int i = 0; i < melds.size(); i++) {
            int x = row.meldStarts().get(i);
            for (var tile : melds.get(i).getAsJsonObject().getAsJsonArray("tiles")) {
                GuiTile.draw(
                        g,
                        tile.getAsInt(),
                        x,
                        SettlementLayout.HAND_TILE_HEIGHT - SettlementLayout.MELD_TILE_HEIGHT,
                        SettlementLayout.MELD_TILE_WIDTH,
                        SettlementLayout.MELD_TILE_HEIGHT);
                x += SettlementLayout.MELD_TILE_WIDTH;
            }
        }
        g.pose().popMatrix();
    }

    private int winScoreTop() {
        return client.view.ruleMode().equals("riichi") ? 134 : 108;
    }

    private void drawWin(GuiGraphics g, JsonObject detail) {
        var hand = new java.util.ArrayList<Integer>();
        detail.getAsJsonArray("hand").forEach(tile -> hand.add(tile.getAsInt()));
        drawHandRow(g, hand, detail.getAsJsonArray("melds"), 72, true);
        boolean hasDora = client.view.ruleMode().equals("riichi");
        if (hasDora) {
            text(g, tr("dora"), 25, 113, 0xFFE4D6B7);
            int xx = 55;
            for (var tile : detail.getAsJsonArray("dora")) {
                GuiTile.draw(g, tile.getAsInt(), xx, 106, 14, 20);
                xx += 14;
            }
            text(g, tr("ura"), 172, 113, 0xFFE4D6B7);
            xx = 199;
            for (var tile : detail.getAsJsonArray("ura")) {
                GuiTile.draw(g, tile.getAsInt(), xx, 106, 14, 20);
                xx += 14;
            }
        }
        int scoreTop = winScoreTop();
        text(
                g,
                detail.get("point_label").getAsString()
                        + "  "
                        + (detail.get("fu").getAsInt() > 0
                                ? detail.get("fu").getAsInt() + "符  "
                                : "")
                        + detail.get("han_text").getAsString(),
                25,
                scoreTop,
                0xFFFFD689);
        int n = 0;
        for (var raw : detail.getAsJsonArray("yakus")) {
            var yaku = raw.getAsJsonObject();
            text(
                    g,
                    yaku.get("name").getAsString() + "  " + yaku.get("han_text").getAsString(),
                    25 + (n % 2) * 146,
                    scoreTop + 22 + (n / 2) * 16,
                    0xFFEFEDDF);
            n++;
        }
    }

    private int drawHeight(JsonObject detail) {
        if (!detail.get("kind").getAsString().equals("ryukyoku")) return 0;
        int height = detail.getAsJsonArray("hands").size() * 50;
        if (detail.has("sichuan"))
            height +=
                    24 + detail.getAsJsonObject("sichuan").getAsJsonArray("transfers").size() * 28;
        return height;
    }

    private int contentBottom(JsonObject detail) {
        if (detail.get("kind").getAsString().equals("ryukyoku")) return 74 + drawHeight(detail);
        int rows = (detail.getAsJsonArray("yakus").size() + 1) / 2;
        return winScoreTop() + 22 + rows * 16;
    }

    private void drawDraw(GuiGraphics g, JsonObject detail) {
        int yy = 74;
        var hands = detail.getAsJsonArray("hands");
        boolean exhaustive = !detail.get("tenpai").isJsonNull();
        for (int s = 0; s < 4; s++) {
            JsonObject revealed = null;
            for (var raw : hands)
                if (raw.getAsJsonObject().get("seat").getAsInt() == s)
                    revealed = raw.getAsJsonObject();
            if (revealed == null) continue;
            String state =
                    detail.has("sichuan") && detail.getAsJsonArray("won").get(s).getAsBoolean()
                            ? tr("won").getString()
                            : detail.has("sichuan")
                                            && detail.getAsJsonObject("sichuan")
                                                    .getAsJsonArray("huazhu")
                                                    .get(s)
                                                    .getAsBoolean()
                                    ? tr("huazhu").getString()
                                    : exhaustive ? "" : tr("declarer").getString();
            text(g, seat(s) + (state.isEmpty() ? "" : "  " + state), 25, yy, 0xFFEAE6D8);
            yy += 20;
            var sorted = new java.util.ArrayList<Integer>();
            revealed.getAsJsonArray("hand").forEach(t -> sorted.add(t.getAsInt()));
            sorted.sort(java.util.Comparator.comparingInt(CloudView::handOrder));
            drawHandRow(g, sorted, revealed.getAsJsonArray("melds"), yy, false);
            yy += 30;
        }
        if (detail.has("sichuan")) {
            text(g, tr("transfers"), 25, yy, 0xFFFFD689);
            yy += 20;
            for (var raw : detail.getAsJsonObject("sichuan").getAsJsonArray("transfers")) {
                var transfer = raw.getAsJsonObject();
                text(g, tr(transfer.get("kind").getAsString()), 25, yy, 0xFFE4D6B7);
                text(
                        g,
                        Component.translatable(
                                "satori.settlement.transfer",
                                seat(transfer.get("payer").getAsInt()),
                                seat(transfer.get("payee").getAsInt()),
                                transfer.get("amount").getAsInt()),
                        25,
                        yy + 12,
                        0xFFEFEDDF);
                yy += 28;
            }
        }
        if (!exhaustive && hands.isEmpty()) text(g, tr("draw_help"), 25, yy, 0xFFEAE6D8);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        var detail = result.getAsJsonArray("entries").get(entry).getAsJsonObject();
        int max = Math.max(0, contentBottom(detail) - 252);
        scroll = Math.clamp(scroll - (int) (vertical * 16), 0, max);
        return true;
    }

    @Override
    public void onClose() {
        SatoriClient.leaveSeat();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
