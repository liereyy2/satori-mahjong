package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.BugReport;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;

import java.util.UUID;

/** A manual report using the same inbox that ops-admin staff already operate. */
final class FeedbackScreen extends SatoriScreen {
    private final Screen parent;
    private final BugReport report;
    private EditBox subject;
    private MultiLineEditBox description;
    private UUID ticket;
    private boolean uncertain;

    FeedbackScreen(ClientController client, Screen parent) {
        super(client, "feedback.title");
        this.parent = parent;
        report =
                new BugReport(
                        SatoriClient.release(),
                        SatoriClient.loader(),
                        SharedConstants.getCurrentVersion().name(),
                        client.game == null ? null : client.game.gameId);
    }

    @Override
    protected void build() {
        subject =
                input(
                        "feedback.subject",
                        12,
                        34,
                        panelWidth - 24,
                        200,
                        subject == null ? "" : subject.getValue(),
                        false);
        String saved = description == null ? "" : description.getValue();
        description =
                addRenderableWidget(
                        MultiLineEditBox.builder()
                                .setX(left + 12)
                                .setY(top + 60)
                                .setPlaceholder(tr("feedback.description"))
                                .build(
                                        font,
                                        panelWidth - 24,
                                        Math.max(32, panelHeight - 182),
                                        tr("feedback.description")));
        description.setCharacterLimit(3500);
        description.setValue(saved);
        int half = (panelWidth - 28) / 2;
        button(
                tr("feedback.send"),
                12,
                panelHeight - 76,
                half,
                () ->
                        (client.user() != null
                                        || client.hosted != null && client.hosted.configured())
                                && ticket == null
                                && !uncertain
                                && !subject.getValue().isBlank()
                                && !description.getValue().isBlank(),
                () ->
                        client.reportBug(
                                report,
                                subject.getValue(),
                                description.getValue(),
                                id -> ticket = id,
                                error -> {
                                    if (error.equals("network_error")
                                            || error.equals("invalid_server_response")
                                            || error.startsWith("http_5")) {
                                        uncertain = true;
                                        client.message = "feedback_uncertain";
                                    }
                                }));
        button("back", 16 + half, panelHeight - 76, half, () -> minecraft.setScreen(parent));
        if (client.hosted == null)
            button(
                    "feedback.messages",
                    12,
                    panelHeight - 48,
                    half,
                    () -> SatoriWebsite.openMessages(this));
        button(
                tr("feedback.copy"),
                16 + half,
                panelHeight - 48,
                half,
                () -> ticket != null,
                () -> minecraft.keyboardHandler.setClipboard(ticket.toString()));
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        String[] lines = report.context().split("\n");
        for (int i = 0; i < lines.length; i++)
            fittedLabel(
                    graphics,
                    lines[i],
                    12,
                    panelHeight - 116 + i * 12,
                    panelWidth - 24,
                    0xFFBED2D4);
    }
}
