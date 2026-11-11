package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Direct 3D hand interaction with a translucent decision dock above the hand. */
final class SeatedTableScreen extends Screen implements TablePage {
    private final ClientController client;

    @Override
    public ClientController controller() {
        return client;
    }

    private final List<Gate> gates = new ArrayList<>();
    private final List<DockEntry> dock = new ArrayList<>();
    private final List<HandTileButton> hand = new ArrayList<>();

    private record Gate(Button button, BooleanSupplier enabled) {}

    private record DockEntry(Button button, int x, int y) {}

    private long version = -1;
    private UUID gameId;
    private int page, dockWidth, dockHeight, handTop, menuBottom;
    private boolean menuExpanded;
    final ExchangeSelection exchange = new ExchangeSelection();

    SeatedTableScreen(ClientController client) {
        super(tr("table.title"));
        this.client = client;
    }

    private static Component tr(String key) {
        return Component.translatable("satori." + key);
    }

    private Button button(
            Component name, int x, int y, int w, BooleanSupplier enabled, Runnable press) {
        Button b = addRenderableWidget(new DockButton(x, y, w, name, press));
        gates.add(new Gate(b, enabled));
        b.active = enabled.getAsBoolean();
        return b;
    }

    private void dockButton(
            Component name, int x, int y, int w, BooleanSupplier enabled, Runnable press) {
        var control = button(name, 0, 0, w, enabled, press);
        control.visible = false;
        dock.add(new DockEntry(control, x, y));
    }

    @Override
    protected void init() {
        clearWidgets();
        gates.clear();
        dock.clear();
        hand.clear();
        CloudView view = client.view;
        version = view == null ? -1 : view.version;
        gameId = view == null ? null : view.gameId;
        dockWidth = 0;
        dockHeight = 0;
        menuBottom = 30;
        addRenderableWidget(
                new TableHelpButton(
                        width - 35,
                        8,
                        () -> minecraft.setScreen(new MahjongHelpScreen(client, this))));
        int menuWidth = Math.min(144, width - 24);
        addRenderableWidget(
                new DockButton(
                        10,
                        9,
                        menuWidth,
                        tr(menuExpanded ? "menu.close" : "menu.open"),
                        () -> {
                            menuExpanded = !menuExpanded;
                            init();
                        }));
        if (menuExpanded) {
            menu(
                    tr(client.hosted != null ? "camera.title" : "games.main"),
                    0,
                    menuWidth,
                    () -> true,
                    () ->
                            minecraft.setScreen(
                                    client.hosted != null
                                            ? new CameraSettingsScreen(client)
                                            : new AccountScreen(client)));
            if (client.hosted == null)
                menu(
                        tr("games.title"),
                        1,
                        menuWidth,
                        () -> true,
                        () -> minecraft.setScreen(new TableScreen(client)));
            menu(
                    tr("feedback.title"),
                    client.hosted != null ? 1 : 2,
                    menuWidth,
                    () ->
                            client.hosted != null
                                    ? client.hosted.configured()
                                    : client.user() != null,
                    () -> minecraft.setScreen(new FeedbackScreen(client, this)));
            menu(
                    tr("table.copy_id"),
                    client.hosted != null ? 2 : 3,
                    menuWidth,
                    () -> client.game != null,
                    () -> {
                        minecraft.keyboardHandler.setClipboard(client.game.gameId.toString());
                        client.message = "id_copied";
                    });
            menu(
                    tr("seat.leave"),
                    client.hosted != null ? 3 : 4,
                    menuWidth,
                    () -> true,
                    SatoriClient::leaveSeat);
        }
        if (view == null) {
            dockWidth = 140;
            dockHeight = 24;
            dockButton(
                    tr("table.open"),
                    0,
                    0,
                    140,
                    () -> true,
                    () -> minecraft.setScreen(new TableScreen(client)));
            return;
        }
        exchange.synchronize(view);
        if (view.status().equals("playing")) {
            for (var tile : TableScene.layout(view)) {
                if (tile.seat() != view.viewerSeat || !tile.key().startsWith("hand-")) continue;
                hand.add(
                        addRenderableWidget(
                                new HandTileButton(
                                        tile.key(),
                                        tile.face(),
                                        () -> {
                                            if (client.view == null
                                                    || client.view.version != view.version
                                                    || !client.view.gameId.equals(view.gameId)
                                                    || !client.canAct()) return;
                                            if (exchange.active(client.view))
                                                exchange.toggle(
                                                        client.view, tile.key(), tile.face());
                                            else
                                                client.view
                                                        .discardFor(tile.key())
                                                        .ifPresent(client::action);
                                        })));
            }
            if (exchange.active(view)) {
                dockWidth = Math.min(200, width - 32);
                dockHeight = 30;
                Button confirm =
                        addRenderableWidget(
                                new DockButton(
                                        0,
                                        0,
                                        dockWidth,
                                        tr("action.exchange_three"),
                                        () ->
                                                exchange.action(client.view)
                                                        .ifPresent(client::action),
                                        true));
                gates.add(
                        new Gate(
                                confirm,
                                () ->
                                        client.hasActionContext()
                                                && exchange.action(client.view).isPresent()));
                dock.add(new DockEntry(confirm, 0, 0));
                confirm.visible = false;
                return;
            }
            List<CloudView.Action> actions =
                    view.buttonActions().stream()
                            .filter(a -> !a.type().equals("continue"))
                            .toList();
            int columns = Math.max(1, Math.min(5, (width - 32) / 110));
            int capacity = columns * 2;
            int pages = Math.max(1, (actions.size() + capacity - 1) / capacity);
            page = Math.min(page, pages - 1);
            int count = Math.min(capacity, actions.size() - page * capacity);
            int usedColumns = Math.min(columns, count);
            int cell = Math.min(130, (width - 32) / columns);
            int paging = pages > 1 ? 24 : 0;
            dockWidth = usedColumns * cell;
            dockHeight = ((count + columns - 1) / columns) * 30 + paging;
            for (int n = 0; n < count; n++) {
                CloudView.Action action = actions.get(page * capacity + n);
                Button choice =
                        addRenderableWidget(
                                new TileActionButton(
                                        cell - 4, action, () -> client.action(action)));
                gates.add(new Gate(choice, client::hasActionContext));
                choice.visible = false;
                dock.add(new DockEntry(choice, n % columns * cell, paging + n / columns * 30));
            }
            if (pages > 1) {
                dockButton(
                        Component.literal("<"),
                        dockWidth / 2 - 50,
                        0,
                        34,
                        () -> page > 0,
                        () -> {
                            page--;
                            init();
                        });
                dockButton(
                        Component.literal((page + 1) + "/" + pages + " >"),
                        dockWidth / 2 - 10,
                        0,
                        60,
                        () -> page < pages - 1,
                        () -> {
                            page++;
                            init();
                        });
            }
        }
    }

    private void menu(Component name, int row, int w, BooleanSupplier enabled, Runnable action) {
        int step = Math.min(24, Math.max(14, (height - 40) / 5));
        Button item = button(name, 10, 34 + row * step, w, enabled, action);
        item.setHeight(Math.min(21, step - 2));
        menuBottom = Math.max(menuBottom, item.getBottom() + 4);
    }

    private void projectControls() {
        var regions = HandTilePicker.project(client, width, height);
        Map<String, HandTilePicker.Region> byKey = new HashMap<>();
        regions.forEach(region -> byKey.put(region.tile().key(), region));
        for (var tile : hand)
            tile.project(
                    byKey.get(tile.tileKey),
                    !menuExpanded
                            && client.hasActionContext()
                            && (exchange.active(client.view)
                                    ? exchange.canToggle(client.view, tile.tileKey, tile.face)
                                    : client.view.discardFor(tile.tileKey).isPresent()));
        boolean visible = !regions.isEmpty();
        int centerX = width / 2;
        handTop = height * 2 / 3;
        if (visible) {
            handTop = regions.stream().mapToInt(HandTilePicker.Region::top).min().orElseThrow();
            int left = regions.stream().mapToInt(HandTilePicker.Region::left).min().orElseThrow();
            int right = regions.stream().mapToInt(HandTilePicker.Region::right).max().orElseThrow();
            centerX = (left + right) / 2;
        }
        // Lobby/result controls have no dealt hand to anchor to. Playing controls require a visible
        // hand.
        boolean phaseHasNoHand =
                client.view == null
                        || Set.of("waiting_players", "ready", "finished")
                                .contains(client.view.status());
        int left = Math.clamp(centerX - dockWidth / 2, 8, Math.max(8, width - dockWidth - 8));
        int top = handTop - 20 - dockHeight;
        boolean animationComplete = !client.scene.animating(client.scene.now());
        for (var item : dock) {
            item.button.visible = animationComplete && (visible || phaseHasNoHand) && top >= 65;
            item.button.setX(left + item.x);
            item.button.setY(top + item.y);
        }
    }

    String selectedTile() {
        if (menuExpanded || !client.hasActionContext() || !client.seated()) return null;
        double x = minecraft.mouseHandler.xpos() * width / minecraft.getWindow().getScreenWidth();
        double y = minecraft.mouseHandler.ypos() * height / minecraft.getWindow().getScreenHeight();
        for (var tile : hand) if (tile.active && tile.isMouseOver(x, y)) return tile.tileKey;
        for (var tile : hand) if (tile.active && tile.isFocused()) return tile.tileKey;
        return null;
    }

    @Override
    public void tick() {
        if (!client.seated()) {
            onClose();
            return;
        }
        if ((client.view == null ? -1 : client.view.version) != version
                || !Objects.equals(client.view == null ? null : client.view.gameId, gameId)) {
            page = 0;
            init();
        }
        for (Gate gate : gates) gate.button.active = gate.enabled.getAsBoolean();
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float delta) {}

    @Override
    public void render(GuiGraphics g, int x, int y, float delta) {
        projectControls();
        for (Gate gate : gates) gate.button.active = gate.enabled.getAsBoolean();
        if (menuExpanded) {
            g.fill(7, 6, Math.min(157, width - 11), Math.min(height - 4, menuBottom), 0xD0151D1B);
        }
        if (client.view != null) {
            if (exchange.active(client.view)) {
                g.drawCenteredString(
                        font,
                        Component.translatable("satori.table.exchange_select", exchange.size()),
                        width / 2,
                        handTop - 12,
                        0xFFFFD78C);
                for (var tile : hand)
                    if (tile.visible && exchange.selected(tile.tileKey)) {
                        g.renderOutline(
                                tile.getX(),
                                tile.getY(),
                                tile.getWidth(),
                                tile.getHeight(),
                                0xFFFFD15C);
                        g.fill(
                                tile.getX(),
                                tile.getY() - 3,
                                tile.getRight(),
                                tile.getY(),
                                0xFFFFD15C);
                    }
            }
            if (client.hasActionContext()
                    && !hand.isEmpty()
                    && client.view.actions().stream().anyMatch(a -> a.type().equals("dahai")))
                g.drawCenteredString(
                        font, tr("table.click_hand"), width / 2, handTop - 12, 0xFFE1C69B);
        }
        renderHint(g, x, y);
        var call = client.scene.call(client.scene.now());
        if (call != null) {
            var label = tr("call." + call.voice());
            var position = callPosition(call);
            if (position != null) {
                int cx = (int) position.x(), cy = (int) position.y();
                int half = font.width(label) + 14;
                g.fill(cx - half, cy - 7, cx + half, cy + 25, 0xB0122332);
                g.renderOutline(cx - half, cy - 7, half * 2, 32, 0xCCD7B97C);
                g.pose().pushMatrix();
                g.pose().translate(cx, cy);
                g.pose().scale(2f, 2f);
                g.drawCenteredString(font, label, 0, 0, 0xFFFFD7A0);
                g.pose().popMatrix();
            }
        }
        if (!client.statusMessage().isBlank())
            g.drawCenteredString(
                    font,
                    tr("message." + client.statusMessage()),
                    width / 2,
                    height - 12,
                    0xFFC4CEC8);
        super.render(g, x, y, delta);
    }

    HandTilePicker.Point callPosition(TableScene.Call call) {
        if (client.view == null || !client.seated()) return null;
        var anchor =
                TableOrientation.callAnchor(
                        call.actor(),
                        TableOrientation.sceneDegrees(
                                client.view.viewerSeat, SeatedCamera.direction()));
        var center = SeatedCamera.center();
        var projected =
                minecraft.gameRenderer.projectPointToScreen(
                        new net.minecraft.world.phys.Vec3(
                                center.getX() + anchor.x,
                                center.getY() + anchor.y,
                                center.getZ() + anchor.z));
        if (projected.z < -1 || projected.z >= 1 || !Double.isFinite(projected.x + projected.y))
            return null;
        return new HandTilePicker.Point(
                (projected.x + 1) * width / 2, (1 - projected.y) * height / 2);
    }

    private boolean hasCurrentPresentation() {
        return client.seated()
                && client.view != null
                && client.presentation != null
                && client.presentation.version == client.view.version;
    }

    SeatedTableHints.Panel fixedHint() {
        return client.seated() && client.view != null
                ? SeatedTableHints.fixed(client, font, width, height)
                : null;
    }

    private void renderHint(GuiGraphics g, int mouseX, int mouseY) {
        if (!client.seated() || client.view == null) return;
        // A hovered legal discard previews the current decision. Otherwise show Rust's last
        // resolved own decision, independently of asynchronous presentation requests.
        if (hasCurrentPresentation() && !client.scene.animating(client.scene.now()))
            for (var tile : hand)
                if (tile.active && (tile.isMouseOver(mouseX, mouseY) || tile.isFocused())) {
                    var preview =
                            SeatedTableHints.panel(
                                    client.presentation.hint(tile.face), font, width, height);
                    if (preview != null) SeatedTableHints.draw(g, font, preview);
                    return;
                }
        var panel = fixedHint();
        if (panel != null) SeatedTableHints.draw(g, font, panel);
    }

    @Override
    public void onClose() {
        SatoriClient.leaveSeat();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!client.seated() || vertical == 0) return false;
        SeatedCamera.zoom(vertical);
        return true;
    }

    private static final class DockButton extends Button {
        private final boolean quiet;

        DockButton(int x, int y, int w, Component text, Runnable action) {
            this(x, y, w, text, action, false);
        }

        DockButton(int x, int y, int w, Component text, Runnable action, boolean quiet) {
            super(x, y, w, 21, text, b -> action.run(), DEFAULT_NARRATION);
            this.quiet = quiet;
            setTooltip(Tooltip.create(text));
        }

        @Override
        public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
            if (!quiet) super.playDownSound(sounds);
        }

        @Override
        protected void renderContents(GuiGraphics g, int x, int y, float delta) {
            boolean hover = isHoveredOrFocused() && active;
            g.fill(getX(), getY(), getRight(), getBottom(), hover ? 0xB3234B40 : 0x8C101F1C);
            g.renderOutline(
                    getX(),
                    getY(),
                    getWidth(),
                    getHeight(),
                    !active ? 0x50414B48 : hover ? 0xCFE3C692 : 0x8864786B);
            renderDefaultLabel(g.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.NONE));
        }
    }
}
