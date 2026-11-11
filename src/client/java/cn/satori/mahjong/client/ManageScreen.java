package cn.satori.mahjong.client;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

final class ManageScreen extends SatoriScreen {
    private Button key, credits;

    ManageScreen(ClientController client) {
        super(client, "table.manage");
        client.loadKeys();
    }

    private boolean owner() {
        return client.view != null
                && client.user() != null
                && client.view.owner().equals(client.user().id());
    }

    @Override
    protected void build() {
        key =
                button(
                        "keys.choose",
                        12,
                        50,
                        panelWidth - 24,
                        () -> minecraft.setScreen(new KeyScreen(client, this)));
        button(tr("manage.authorize"), 12, 80, 120, this::owner, client::authorizeAi);
        button(
                tr("manage.resume"),
                140,
                80,
                120,
                () -> owner() && client.view.status().equals("paused"),
                () -> client.command("POST", "/resume", new JsonObject()));
        button(
                "back",
                panelWidth - 90,
                panelHeight - 40,
                78,
                () -> minecraft.setScreen(new GameRoomScreen(client)));
        credits =
                button(
                        tr("credits.open"),
                        12,
                        134,
                        panelWidth - 24,
                        this::owner,
                        () -> SatoriWebsite.openCredits(this));
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        if (client.view != null)
            label(
                    graphics,
                    client.view.needsCredits()
                            ? tr("credits.title").getString()
                            : client.view.pauseReason(),
                    12,
                    159,
                    0xFFFFD78C);
        label(graphics, tr("manage.private"), 12, 175, 0xFF91B8BF);
    }

    @Override
    public void refreshButtons() {
        super.refreshButtons();
        if (key != null) KeyScreen.updateSelection(key, client);
        if (credits != null) credits.visible = client.view != null && client.view.needsCredits();
    }
}
