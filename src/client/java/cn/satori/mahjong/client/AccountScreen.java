package cn.satori.mahjong.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;

final class AccountScreen extends SatoriScreen {
    private EditBox email, secret;
    private boolean code;
    private boolean loggedIn;

    AccountScreen(ClientController client) {
        super(client, "account.title");
    }

    @Override
    protected void build() {
        loggedIn = client.user() != null;
        if (SeatedCamera.active())
            button(
                    "camera.title",
                    panelWidth - 122,
                    8,
                    110,
                    () -> minecraft.setScreen(new CameraSettingsScreen(client)));
        button("account.website", 12, 168, panelWidth - 24, () -> SatoriWebsite.openLogin(this));
        if (client.user() != null) {
            button(
                    "feedback.title",
                    160,
                    108,
                    110,
                    () -> minecraft.setScreen(new FeedbackScreen(client, this)));
            button(
                    "account.copy_id",
                    12,
                    80,
                    140,
                    () -> minecraft.keyboardHandler.setClipboard(client.user().id().toString()));
            button("table.open", 12, 108, 140, () -> minecraft.setScreen(new TableScreen(client)));
            button("account.web_profile", 12, 142, 140, () -> SatoriWebsite.openProfile(this));
            button("account.logout", 160, 80, 110, client::logout);
            button("close", panelWidth - 90, 142, 78, this::onClose);
            return;
        }
        email =
                input(
                        "account.email",
                        12,
                        48,
                        panelWidth - 24,
                        128,
                        email == null ? "" : email.getValue(),
                        false);
        secret =
                input(
                        code ? "account.code" : "account.password",
                        12,
                        88,
                        panelWidth - 24,
                        128,
                        "",
                        true);
        button(
                code ? "account.use_password" : "account.use_code",
                12,
                116,
                135,
                () -> {
                    code = !code;
                    init();
                });
        button(
                tr("account.send_code"),
                155,
                116,
                110,
                () -> code,
                () -> client.sendCode(email.getValue()));
        button(
                "account.login",
                12,
                142,
                135,
                () -> {
                    String value = secret.getValue();
                    secret.setValue("");
                    client.login(email.getValue(), value, code);
                });
        button("close", panelWidth - 90, 142, 78, this::onClose);
        setInitialFocus(email);
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        if (client.user() != null) {
            label(
                    graphics,
                    client.user().name() + " · " + client.user().publicId(),
                    12,
                    40,
                    0xFFFFFFFF);
            label(graphics, client.user().id().toString(), 12, 56, 0xFF91B8BF);
        } else {
            label(graphics, tr("account.email"), 12, 34, 0xFFBED2D4);
            label(graphics, tr(code ? "account.code" : "account.password"), 12, 74, 0xFFBED2D4);
            label(graphics, tr("account.private"), 12, 194, 0xFF91B8BF);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (loggedIn != (client.user() != null)) init();
    }

    @Override
    public void removed() {
        if (secret != null) secret.setValue("");
    }
}
