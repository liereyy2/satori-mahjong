package cn.satori.mahjong.client;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

import java.util.UUID;

/** Visible paid-AI pause; only the authoritative cloud view can end this notice. */
final class CreditsRequiredScreen extends SatoriScreen {
    private final UUID gameId;
    private Button credits;

    CreditsRequiredScreen(ClientController client) {
        super(client, "credits.title");
        gameId = client.view.gameId;
    }

    private boolean current() {
        return client.view != null && client.view.gameId.equals(gameId);
    }

    private boolean owner() {
        return current()
                && (client.hosted != null
                        || client.user() != null && client.view.owner().equals(client.user().id()));
    }

    @Override
    protected void build() {
        int w = panelWidth - 24;
        credits =
                button(
                        "credits.open",
                        12,
                        panelHeight - 120,
                        w,
                        () -> SatoriWebsite.openCredits(this));
        button(
                tr("credits.resume"),
                12,
                panelHeight - 94,
                w,
                () ->
                        owner()
                                && client.view.needsCredits()
                                && client.game != null
                                && !client.game.hasPending()
                                && client.connection.equals("connected"),
                () -> {
                    if (client.hosted != null) client.hosted.command("resume", new JsonObject());
                    else client.command("POST", "/resume", new JsonObject());
                });
        button(
                client.hosted != null ? "feedback.title" : "table.manage",
                12,
                panelHeight - 68,
                w,
                () ->
                        minecraft.setScreen(
                                client.hosted != null
                                        ? new FeedbackScreen(client, this)
                                        : new ManageScreen(client)));
        button(
                SeatedCamera.active() ? "seat.leave" : "close",
                12,
                panelHeight - 42,
                w,
                this::onClose);
    }

    @Override
    public void refreshButtons() {
        super.refreshButtons();
        if (credits != null) credits.visible = owner();
    }

    @Override
    public void tick() {
        if (!current() || !client.view.needsCredits()) {
            minecraft.setScreen(
                    client.view == null && client.hosted != null
                            ? new HostedLobbyScreen(client)
                            : SeatedCamera.active()
                                    ? new SeatedTableScreen(client)
                                    : new GameRoomScreen(client));
            return;
        }
        super.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFFFFBC67);
        int y = top + 40;
        var message =
                tr(
                        client.hosted != null
                                ? "hosted.credits_help"
                                : owner() ? "credits.owner_help" : "credits.player_help");
        for (var line : font.split(message, panelWidth - 24)) {
            graphics.drawString(font, line, left + 12, y, 0xFFFFD78C, false);
            y += font.lineHeight + 3;
        }
    }
}
