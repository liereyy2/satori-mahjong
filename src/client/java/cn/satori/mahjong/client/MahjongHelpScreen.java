package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.RiichiHelp;
import cn.satori.mahjong.protocol.RiichiHelp.Page;
import cn.satori.mahjong.protocol.RiichiHelpExamples;
import cn.satori.mahjong.protocol.SichuanHelp;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only rulebook: the seat, camera, authoritative stream and animations stay active. */
final class MahjongHelpScreen extends Screen implements TablePage {
    private static final int TEXT = 0xFFF3EBDC, MUTED = 0xFFB6C8C2, ACCENT = 0xFF77E0C8;
    private final ClientController client;
    private final Screen parent;
    private final List<Button> tabs = new ArrayList<>();
    private Page page = Page.BASICS;
    private String ruleMode;
    private HelpText content;
    private EditBox search;
    private String query = "";
    private int left, top, panelWidth, panelHeight;

    MahjongHelpScreen(ClientController client, Screen parent) {
        super(Component.translatable("satori.help.title"));
        this.client = client;
        this.parent = parent;
        ruleMode =
                client.hosted != null && client.hosted.table() != null
                        ? client.hosted.table().get("rule_mode").getAsString()
                        : client.view != null ? client.view.ruleMode() : "riichi";
    }

    @Override
    public ClientController controller() {
        return client;
    }

    private static Component tr(String key) {
        return Component.translatable("satori.help." + key);
    }

    @Override
    protected void init() {
        double scroll = content == null ? 0 : content.scrollAmount();
        panelWidth = Math.min(760, width - 24);
        panelHeight = Math.min(560, height - 20);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        tabs.clear();
        addRenderableWidget(
                new HelpButton(
                        left + panelWidth - 30,
                        top + 7,
                        22,
                        Component.literal("×"),
                        this::onClose,
                        false));
        for (int i = 0; i < 2; i++) {
            final String mode = i == 0 ? "riichi" : "sichuan";
            addRenderableWidget(
                    new HelpButton(
                            left + panelWidth - 190 + i * 76,
                            top + 7,
                            72,
                            Component.translatable("satori.rules." + mode),
                            () -> {
                                ruleMode = mode;
                                query = "";
                                content = null;
                                clearWidgets();
                                init();
                            },
                            ruleMode.equals(mode)));
        }
        int usable = panelWidth - 24;
        for (Page p : Page.values()) {
            int x = left + 12 + p.ordinal() * usable / Page.values().length;
            int end = left + 12 + (p.ordinal() + 1) * usable / Page.values().length;
            tabs.add(
                    addRenderableWidget(
                            new HelpButton(
                                    x,
                                    top + 34,
                                    end - x - 3,
                                    Component.translatable(pageTitle(p)),
                                    () -> select(p),
                                    p == page)));
        }
        search =
                addRenderableWidget(
                        new EditBox(
                                font,
                                left + 14,
                                top + 62,
                                panelWidth - 28,
                                18,
                                tr(ruleMode.equals("sichuan") ? "sichuan.search" : "search")));
        search.setMaxLength(100);
        search.setHint(tr(ruleMode.equals("sichuan") ? "sichuan.search" : "search"));
        search.setValue(query);
        search.setResponder(
                value -> {
                    query = value;
                    if (content != null) content.rebuild();
                });
        content =
                addRenderableWidget(
                        new HelpText(left + 12, top + 62, panelWidth - 30, panelHeight - 90));
        layoutContent();
        content.setScrollAmount(scroll);
        setInitialFocus(content);
    }

    private String pageTitle(Page p) {
        return ruleMode.equals("sichuan") ? SichuanHelp.titleKey(p) : p.titleKey();
    }

    private void select(Page next) {
        page = next;
        for (int i = 0; i < tabs.size(); i++)
            ((HelpButton) tabs.get(i)).selected = Page.values()[i] == page;
        layoutContent();
        setFocused(content);
    }

    private void layoutContent() {
        search.visible = search.active = page == Page.YAKU;
        int y = top + (search.visible ? 88 : 62);
        content.setY(y);
        content.setHeight(top + panelHeight - 27 - y);
        content.rebuild();
    }

    @Override
    public void tick() {
        if (!client.seated()) minecraft.setScreen(null);
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float delta) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0x75050E10);
        g.fill(left, top, left + panelWidth, top + panelHeight, 0xFA14242A);
        g.renderOutline(left, top, panelWidth, panelHeight, 0xFF52736C);
        g.drawString(font, title, left + 13, top + 13, TEXT, false);
        g.fill(
                left + 12,
                top + panelHeight - 24,
                left + panelWidth - 12,
                top + panelHeight - 23,
                0xFF35504C);
        g.drawString(font, tr("live"), left + 13, top + panelHeight - 16, MUTED, false);
        super.render(g, x, y, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(client.seated() ? parent : null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class HelpButton extends Button {
        private boolean selected;

        HelpButton(int x, int y, int w, Component title, Runnable press, boolean selected) {
            super(x, y, w, 22, title, b -> press.run(), DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override
        public boolean shouldTakeFocusAfterInteraction() {
            // Page selection explicitly gives the text area the reading focus.
            return false;
        }

        @Override
        protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(
                    getX(),
                    getY(),
                    getRight(),
                    getBottom(),
                    selected ? 0xFF30574D : isHoveredOrFocused() ? 0xFF314941 : 0xFF203832);
            if (selected || isHoveredOrFocused())
                g.renderOutline(getX(), getY(), width, height, selected ? ACCENT : MUTED);
            g.drawCenteredString(
                    font, getMessage(), getX() + width / 2, getY() + 7, selected ? ACCENT : TEXT);
        }
    }

    private record Card(
            RiichiHelp.Entry entry,
            List<FormattedCharSequence> title,
            List<FormattedCharSequence> body,
            List<RiichiExampleRenderer.Prepared> examples,
            int y,
            int height) {}

    private final class HelpText extends AbstractTextAreaWidget {
        private List<Card> cards = List.of();
        private int innerHeight;

        HelpText(int x, int y, int w, int h) {
            super(x, y, w, h, Component.translatable(pageTitle(page)), false, false);
        }

        void rebuild() {
            var next = new ArrayList<Card>();
            int y = 4;
            String needle = query.strip().toLowerCase(Locale.ROOT);
            for (var entry :
                    ruleMode.equals("sichuan") ? SichuanHelp.entries(page) : page.entries()) {
                var heading = Component.translatable(entry.titleKey());
                var body = Component.translatable(entry.bodyKey());
                var illustrations = RiichiHelpExamples.forEntry(entry);
                var searchable =
                        new StringBuilder(heading.getString()).append(' ').append(body.getString());
                for (var example : illustrations)
                    searchable
                            .append(' ')
                            .append(Component.translatable(example.labelKey()).getString());
                if (page == Page.YAKU
                        && !needle.isEmpty()
                        && !searchable.toString().toLowerCase(Locale.ROOT).contains(needle))
                    continue;
                var titleLines = font.split(heading, width - 24);
                var bodyLines = font.split(body, width - 24);
                var examples =
                        illustrations.stream()
                                .map(e -> RiichiExampleRenderer.prepare(font, e, width - 24))
                                .toList();
                int height =
                        20
                                + titleLines.size() * 12
                                + bodyLines.size() * 12
                                + examples.stream()
                                        .mapToInt(RiichiExampleRenderer.Prepared::height)
                                        .sum();
                next.add(new Card(entry, titleLines, bodyLines, examples, y, height));
                y += height + 7;
            }
            cards = List.copyOf(next);
            innerHeight = y;
            setMessage(Component.translatable(pageTitle(page)));
            setScrollAmount(0);
        }

        @Override
        protected int getInnerHeight() {
            return innerHeight;
        }

        @Override
        protected double scrollRate() {
            return 24;
        }

        @Override
        protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float delta) {
            for (var card : cards) {
                if (card.y + card.height < scrollAmount() || card.y > scrollAmount() + height)
                    continue;
                int x = getX() + 2, y = getY() + card.y;
                g.fill(
                        x,
                        y,
                        getRight() - 3,
                        y + card.height,
                        card.entry.highlighted() ? 0xFF273D38 : 0xFF1C3031);
                g.fill(
                        x,
                        y,
                        x + 2,
                        y + card.height,
                        card.entry.highlighted() ? 0xFFE1C69B : 0xFF4F7B70);
                y += 8;
                for (var line : card.title) {
                    g.drawString(
                            font,
                            line,
                            x + 10,
                            y,
                            card.entry.highlighted() ? 0xFFE9D3AC : ACCENT,
                            false);
                    y += 12;
                }
                y += 4;
                for (var line : card.body) {
                    g.drawString(font, line, x + 10, y, TEXT, false);
                    y += 12;
                }
                for (var example : card.examples) {
                    RiichiExampleRenderer.draw(g, font, example, x + 10, y, width - 24);
                    y += example.height();
                }
            }
            if (cards.isEmpty())
                g.drawString(font, tr("no_results"), getX() + 10, getY() + 12, MUTED, false);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            switch (event.key()) {
                case 266 -> setScrollAmount(scrollAmount() - height + 24);
                case 267 -> setScrollAmount(scrollAmount() + height - 24);
                case 268 -> setScrollAmount(0);
                case 269 -> setScrollAmount(maxScrollAmount());
                default -> {
                    return super.keyPressed(event);
                }
            }
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, getMessage());
            out.add(NarratedElementType.USAGE, tr("scroll"));
        }
    }
}
