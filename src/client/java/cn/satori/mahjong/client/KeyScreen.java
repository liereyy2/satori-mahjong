package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.AccountKey;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Paged account metadata selector. The website is the only key-creation surface. */
final class KeyScreen extends SatoriScreen {
    static final String WEBSITE = "https://mj.satori-ai.cn/profile?view=api-key";
    private final Screen parent;
    private int page;
    private int revision;

    KeyScreen(ClientController client, Screen parent) {
        super(client, "keys.title");
        this.parent = parent;
    }

    static void updateSelection(Button button, ClientController client) {
        AccountKey selected = client.selectedKey();
        Component label =
                selected == null
                        ? tr("keys.choose")
                        : Component.translatable(
                                "satori.keys.selected", selected.name(), selected.masked());
        button.setMessage(label);
        button.setTooltip(Tooltip.create(label));
    }

    @Override
    protected void build() {
        revision = client.account.keysRevision;
        int count = Math.max(1, (panelHeight - 126) / 26);
        int pages = Math.max(1, (client.account.keys.size() + count - 1) / count);
        page = Math.min(page, pages - 1);
        for (int row = 0; row < count && page * count + row < client.account.keys.size(); row++) {
            AccountKey key = client.account.keys.get(page * count + row);
            Component label =
                    Component.literal(key.name() + " · " + key.masked())
                            .append(" · ")
                            .append(tr(key.available() ? "keys.active" : "keys.inactive"));
            Button item =
                    button(
                            label,
                            12,
                            57 + row * 26,
                            panelWidth - 24,
                            () -> !client.account.keysLoading && key.available(),
                            () -> {
                                client.account.selectedKey = key.id();
                                minecraft.setScreen(parent);
                            });
            item.setTooltip(Tooltip.create(label));
        }
        int y = panelHeight - 65;
        button(
                Component.literal("<"),
                12,
                y,
                28,
                () -> page > 0,
                () -> {
                    page--;
                    init();
                });
        button(Component.literal((page + 1) + "/" + pages), 44, y, 48, () -> false, () -> {});
        button(
                Component.literal(">"),
                96,
                y,
                28,
                () -> page + 1 < pages,
                () -> {
                    page++;
                    init();
                });
        button(
                tr("keys.refresh"),
                panelWidth - 92,
                y,
                80,
                () -> !client.account.keysLoading,
                client::loadKeys);
        button(
                "keys.website",
                12,
                panelHeight - 40,
                panelWidth - 112,
                () -> ConfirmLinkScreen.confirmLinkNow(this, WEBSITE));
        button("back", panelWidth - 92, panelHeight - 40, 80, () -> minecraft.setScreen(parent));
    }

    @Override
    public void tick() {
        if (revision != client.account.keysRevision) init();
        super.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        label(graphics, tr(client.account.keysMessage), 12, 36, 0xFF91B8BF);
    }
}
