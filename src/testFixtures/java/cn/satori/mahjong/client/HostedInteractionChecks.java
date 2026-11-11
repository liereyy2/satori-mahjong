package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Shared real-widget and real-loader packet checks for Fabric and Forge. */
public final class HostedInteractionChecks implements AutoCloseable {
    public record Step(String name, BooleanSupplier ready, Runnable action) {}

    private final Minecraft mc = Minecraft.getInstance();
    private SatoriApi api;
    private CompletableFuture<?> finish;
    private CompletableFuture<HttpResponse<String>> inspection;
    private List<?> stableWidgets;
    private Object stableCamera;
    private String tableId;
    private final Map<Button, Boolean> pendingAppearance = new IdentityHashMap<>();
    private String pendingSelection;
    private long highlightStarted;
    private CompletableFuture<HttpResponse<String>> initialAccountCheck;

    private boolean initialAccountCheckComplete() {
        if (initialAccountCheck == null) {
            initialAccountCheck =
                    HttpClient.newHttpClient()
                            .sendAsync(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:18767/fixture/initial-account-check"))
                                            .timeout(java.time.Duration.ofSeconds(5))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            return false;
        }
        if (!initialAccountCheck.isDone()) return false;
        var response = initialAccountCheck.join();
        require(response.statusCode() == 200, "Initial account check transport failed");
        boolean complete =
                com.google.gson.JsonParser.parseString(response.body())
                        .getAsJsonObject()
                        .get("complete")
                        .getAsBoolean();
        initialAccountCheck = null;
        return complete;
    }

    private JsonObject snapshot() {
        if (inspection == null) {
            inspection =
                    HttpClient.newHttpClient()
                            .sendAsync(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:18767/fixture/hosted"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            return null;
        }
        if (!inspection.isDone()) return null;
        var response =
                com.google.gson.JsonParser.parseString(inspection.join().body()).getAsJsonObject();
        inspection = null;
        return response;
    }

    private boolean cloudOccupant(boolean present) {
        var snapshot = snapshot();
        if (snapshot == null) return false;
        var seat =
                snapshot.getAsJsonObject("tables")
                        .getAsJsonObject(tableId)
                        .getAsJsonArray("seats")
                        .get(0)
                        .getAsJsonObject();
        return present
                ? seat.get("kind").getAsString().equals("human")
                        && seat.get("connected").getAsBoolean()
                : seat.get("kind").getAsString().equals("empty")
                        || !seat.get("connected").getAsBoolean();
    }

    private void sit() {
        var chair = TableSeating.chair(HostedNativeFixture.CENTER, 0);
        mc.gameMode.useItemOn(
                mc.player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(chair.getCenter(), Direction.UP, chair, false));
    }

    private void assertStableWidgets() {
        require(
                stableWidgets.equals(mc.screen.children()),
                "Lobby widgets were recreated by a state update");
        require(stableCamera == mc.getCameraEntity(), "Button reset the table camera");
    }

    private ClientController client() {
        return SatoriClient.TABLES.active();
    }

    private List<Button> buttons(String key) {
        return mc.screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.visible)
                .filter(
                        b ->
                                b.getMessage()
                                        .getString()
                                        .equals(
                                                Component.translatable("satori." + key)
                                                        .getString()))
                .toList();
    }

    private Button button(String key) {
        return buttons(key).getFirst();
    }

    private Button key() {
        return mc.screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.getMessage().getString().contains("Local test key"))
                .findFirst()
                .orElse(null);
    }

    private void click(Button button) {
        require(button.active, "Button disabled: " + button.getMessage());
        var screen = mc.screen;
        var event =
                new net.minecraft.client.input.MouseButtonEvent(
                        button.getX() + button.getWidth() * .5,
                        button.getY() + button.getHeight() * .5,
                        new net.minecraft.client.input.MouseButtonInfo(0, 0));
        require(screen.mouseClicked(event, false), "Click not consumed");
        screen.mouseReleased(event);
    }

    private void clickWhileCheckingPendingFrame(Button button) {
        var screen = (SatoriScreen) mc.screen;
        var states = new IdentityHashMap<Button, Boolean>();
        for (var widget : screen.children())
            if (widget instanceof Button b) states.put(b, b.active);
        var widgets = List.copyOf(screen.children());
        var camera = mc.getCameraEntity();
        require(
                !screen.client.busy,
                "Pending-frame check started before the prior request completed");
        click(button);
        require(screen.client.busy, "Test missed the pending request frame");
        screen.refreshButtons();
        require(widgets.equals(screen.children()), "Pending request rebuilt widgets");
        require(camera == mc.getCameraEntity(), "Pending request reset camera");
        states.forEach(
                (b, active) ->
                        require(
                                b.active == active,
                                "Pending request changed button brightness: "
                                        + b.getMessage().getString()));
        // Both inputs must be consumed without issuing another request.
        click(button);
        button.keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        Button navigation =
                button(screen instanceof ControlScreen ? "credits.open" : "feedback.title");
        navigation.keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        require(mc.screen == screen, "Keyboard bypassed the pending input lock");
    }

    private static void require(boolean ok, String detail) {
        if (!ok) throw new AssertionError(detail);
    }

    private boolean lobby() {
        return mc.screen instanceof HostedLobbyScreen
                && client().hosted != null
                && client().hosted.self() != null
                && !client().busy;
    }

    public List<Step> steps() {
        var steps = new ArrayList<Step>();
        steps.add(
                new Step(
                        "control login form",
                        () -> mc.screen instanceof ControlScreen,
                        () -> {
                            mc.options.pauseOnLostFocus = false;
                            mc.getWindow().setWindowed(1280, 800);
                            mc.options.guiScale().set(2);
                            mc.resizeDisplay();
                            api = LocalTableSessionsFixture.hostedApi();
                            client().account.api = api;
                            var fields =
                                    mc.screen.children().stream()
                                            .filter(EditBox.class::isInstance)
                                            .map(EditBox.class::cast)
                                            .toList();
                            require(fields.size() == 2, "Control login fields missing");
                            fields.get(0).setValue("shared@example.test");
                            fields.get(1).setValue("synthetic-password");
                            ((SatoriScreen) mc.screen).refreshButtons();
                            click(button("account.login"));
                        }));
        steps.add(
                new Step(
                        "select shared AI key",
                        () ->
                                mc.screen instanceof ControlScreen screen
                                        && key() != null
                                        && key().active
                                        && !screen.client.busy
                                        && !screen.client.account.authenticating
                                        && initialAccountCheckComplete(),
                        () -> clickWhileCheckingPendingFrame(key())));
        steps.add(
                new Step(
                        "initial shared account credits",
                        () ->
                                mc.screen instanceof ControlScreen screen
                                        && screen.creditsLabel().getString().contains("123456")
                                        && key() != null
                                        && key().active,
                        () -> stableWidgets = List.copyOf(mc.screen.children())));
        steps.add(
                new Step(
                        "automatic credits refresh preserves widgets",
                        () ->
                                mc.screen instanceof ControlScreen screen
                                        && screen.creditsLabel().getString().contains("98765"),
                        () -> {
                            require(
                                    stableWidgets.equals(mc.screen.children()),
                                    "Credits refresh rebuilt widgets");
                            require(key().active, "Credits refresh disabled account controls");
                        }));
        steps.add(
                new Step(
                        "capture-control",
                        () ->
                                key() != null
                                        && key().getMessage().getString().startsWith("[x]")
                                        && key().active,
                        () -> {
                            for (var widget : mc.screen.children())
                                if (widget instanceof AbstractWidget w)
                                    require(
                                            w.getY() >= 0 && w.getBottom() <= mc.screen.height,
                                            "Control widget outside screen");
                        }));
        steps.add(
                new Step(
                        "controller refresh stays bright while pending",
                        () -> mc.screen instanceof ControlScreen screen && !screen.client.busy,
                        () -> clickWhileCheckingPendingFrame(button("keys.refresh"))));
        steps.add(
                new Step(
                        "sit without a personal login",
                        () -> mc.screen instanceof ControlScreen screen && !screen.client.busy,
                        () -> {
                            require(
                                    api.user() == null,
                                    "Ordinary client acquired shared account identity");
                            click(button("close"));
                            var chair = TableSeating.chair(HostedNativeFixture.CENTER, 0);
                            mc.player.getInventory().clearContent();
                            mc.gameMode.useItemOn(
                                    mc.player,
                                    InteractionHand.MAIN_HAND,
                                    new BlockHitResult(
                                            chair.getCenter(), Direction.UP, chair, false));
                        }));
        steps.add(
                new Step(
                        "capture-ready-lobby",
                        this::lobby,
                        () -> {
                            TableActionLayoutChecks.verify();
                            var packet = client().hosted.packet;
                            String json = packet.toString();
                            require(
                                    !json.contains("synthetic-password")
                                            && !json.contains("synthetic-selected-key")
                                            && !json.contains("shared@example.test"),
                                    "Shared secret in ordinary player packet");
                            require(
                                    client().hosted
                                            .self()
                                            .get("player_id")
                                            .getAsString()
                                            .equals(mc.player.getUUID().toString()),
                                    "MC identity not assigned from real connection");
                            require(
                                    client().user() == null,
                                    "Ordinary player was logged into shared account");
                            var malformed = new JsonObject();
                            malformed.addProperty("kind", "ai");
                            SatoriClient.sendHosted(
                                    new HostedPackets.Request(
                                            mc.level.dimension(),
                                            HostedNativeFixture.CENTER,
                                            false,
                                            malformed.toString()));
                        }));
        steps.add(
                new Step(
                        "switch physical table to Sichuan",
                        () -> lobby() && !client().busy,
                        () -> clickWhileCheckingPendingFrame(button("rules.riichi"))));
        steps.add(
                new Step(
                        "open help for Sichuan table",
                        () ->
                                lobby()
                                        && client().hosted
                                                .table()
                                                .get("rule_mode")
                                                .getAsString()
                                                .equals("sichuan"),
                        () -> {
                            require(
                                    client().hosted.table().get("length").getAsString().equals("8"),
                                    "Sichuan length is not eight rounds");
                            click(button("help.title"));
                        }));
        steps.add(
                new Step(
                        "capture-help-default-sichuan",
                        () -> mc.screen instanceof MahjongHelpScreen,
                        () -> {
                            require(
                                    button("help.sichuan.tab.basics").visible,
                                    "Help did not default to table Sichuan rules");
                        }));
        steps.add(
                new Step(
                        "close Sichuan table help",
                        () -> mc.screen instanceof MahjongHelpScreen,
                        () -> mc.screen.onClose()));
        steps.add(
                new Step(
                        "restore physical table Riichi",
                        () -> lobby() && !client().busy,
                        () -> clickWhileCheckingPendingFrame(button("rules.sichuan"))));
        steps.add(
                new Step(
                        "wait for Riichi table",
                        () ->
                                lobby()
                                        && client().hosted
                                                .table()
                                                .get("rule_mode")
                                                .getAsString()
                                                .equals("riichi"),
                        () -> {}));
        steps.addAll(new HelpInteractionChecks(mc).preparationSteps());
        steps.add(
                new Step(
                        "small table lobby",
                        this::lobby,
                        () -> TableActionLayoutChecks.resize(true)));
        steps.add(
                new Step(
                        "capture-small-table-lobby", this::lobby, TableActionLayoutChecks::verify));
        steps.add(
                new Step(
                        "restore table lobby size",
                        this::lobby,
                        () -> TableActionLayoutChecks.resize(false)));
        steps.add(
                new Step(
                        "feedback through shared server account",
                        this::lobby,
                        () -> {
                            click(button("feedback.title"));
                            for (var widget : mc.screen.children()) {
                                if (widget instanceof EditBox field)
                                    field.setValue("Hosted table fixture");
                                if (widget instanceof MultiLineEditBox field)
                                    field.setValue("Local test: ready then start.");
                            }
                            ((SatoriScreen) mc.screen).refreshButtons();
                            click(button("feedback.send"));
                        }));
        steps.add(
                new Step(
                        "guest feedback receipt",
                        () -> mc.screen instanceof FeedbackScreen && button("feedback.copy").active,
                        () -> {
                            require(
                                    buttons("feedback.messages").isEmpty(),
                                    "Guest cannot manage the shared account inbox");
                            click(button("feedback.copy"));
                            require(
                                    mc.keyboardHandler
                                            .getClipboard()
                                            .equals("00000000-0000-0000-0000-000000000777"),
                                    "Wrong feedback receipt");
                            click(button("back"));
                        }));
        steps.add(
                new Step(
                        "leave preparation",
                        this::lobby,
                        () -> {
                            tableId = client().hosted.packet.get("table_id").getAsString();
                            click(button("seat.leave"));
                        }));
        steps.add(
                new Step(
                        "preparation removes departed player",
                        () -> !mc.player.isPassenger() && cloudOccupant(false),
                        this::sit));
        steps.add(
                new Step(
                        "rejoin preparation",
                        this::lobby,
                        () -> {
                            require(
                                    !client().hosted.self().get("ready").getAsBoolean(),
                                    "Rejoined player remained ready");
                            stableWidgets = List.copyOf(mc.screen.children());
                            stableCamera = mc.getCameraEntity();
                            clickWhileCheckingPendingFrame(button("create.hanchan"));
                        }));
        steps.add(
                new Step(
                        "length changes without flicker",
                        () -> lobby() && !buttons("create.east").isEmpty(),
                        () -> {
                            assertStableWidgets();
                            clickWhileCheckingPendingFrame(button("create.east"));
                        }));
        for (int count = 0; count < 3; count++) {
            int expected = count;
            steps.add(
                    new Step(
                            "add AI " + count,
                            () -> lobby() && buttons("hosted.remove_ai").size() == expected,
                            () -> {
                                assertStableWidgets();
                                clickWhileCheckingPendingFrame(button("hosted.add_ai"));
                            }));
        }
        steps.add(
                new Step(
                        "capture-ready-ai",
                        () -> lobby() && buttons("hosted.remove_ai").size() == 3,
                        this::assertStableWidgets));
        steps.add(
                new Step(
                        "ready automatically starts",
                        () -> lobby() && button("hosted.ready").active,
                        () -> clickWhileCheckingPendingFrame(button("hosted.ready"))));
        steps.add(
                new Step(
                        "capture-hosted-game",
                        () ->
                                mc.screen instanceof SeatedTableScreen
                                        && client().view != null
                                        && client().connection.equals("connected"),
                        () -> {
                            TableActionLayoutChecks.verify();
                            require(
                                    client().scene.animating(client().scene.now()),
                                    "Preparation-to-game snapshot skipped the first round's dice"
                                            + " and deal");
                            require(
                                    client().view.viewerSeat == 0
                                            && client().view.hand(0).size() == 14,
                                    "Wrong personal view");
                            require(
                                    client().hosted.phase().equals("playing"),
                                    "Ready did not create game");
                            require(
                                    client().user() == null,
                                    "Seat stream must not log in client account");
                        }));
        steps.add(
                new Step(
                        "small seated table",
                        () -> mc.screen instanceof SeatedTableScreen,
                        () -> TableActionLayoutChecks.resize(true)));
        steps.add(
                new Step(
                        "capture-small-seated-table",
                        () -> mc.screen instanceof SeatedTableScreen,
                        TableActionLayoutChecks::verify));
        steps.add(
                new Step(
                        "restore seated table size",
                        () -> mc.screen instanceof SeatedTableScreen,
                        () -> TableActionLayoutChecks.resize(false)));
        steps.addAll(new HelpInteractionChecks(mc).steps());
        steps.addAll(new PausedLobbyInteractionChecks().steps());
        steps.addAll(new ReadyHintInteractionChecks().steps());
        steps.addAll(new ReadyHintLifecycleChecks().steps());
        steps.addAll(new RoundedTileInteractionChecks().steps());
        steps.addAll(new DecisionAnimationInteractionChecks().steps());
        steps.addAll(new SettlementRowInteractionChecks().steps());
        steps.addAll(new SichuanInteractionChecks().steps());
        steps.addAll(new DiceWallInteractionChecks().steps());
        steps.addAll(new ReactionInteractionChecks().steps());
        steps.add(
                new Step(
                        "deleted match returns to preparation with a clear notice",
                        () ->
                                mc.screen instanceof SeatedTableScreen
                                        && client().game != null
                                        && client().hosted.packet.has("session"),
                        DeletedGameInteractionChecks::verify));
        steps.add(
                new Step(
                        "active hand and decisions stay stable while pending",
                        () -> mc.screen instanceof SeatedTableScreen && client().canAct(),
                        () -> {
                            var screen = (SeatedTableScreen) mc.screen;
                            var tiles =
                                    screen.children().stream()
                                            .filter(HandTileButton.class::isInstance)
                                            .map(HandTileButton.class::cast)
                                            .filter(b -> b.active)
                                            .toList();
                            require(!tiles.isEmpty(), "No selectable hand to verify");
                            var hand = tiles.getFirst();
                            hand.setFocused(true);
                            String selected = screen.selectedTile();
                            pendingSelection = selected;
                            require(selected != null, "Tile highlight missing before request");
                            var widgets = List.copyOf(screen.children());
                            var states = new IdentityHashMap<Button, Boolean>();
                            for (var widget : screen.children())
                                if (widget instanceof Button b) states.put(b, b.active);
                            pendingAppearance.putAll(states);
                            client().command("POST", "/ready", new JsonObject());
                            require(
                                    client().busy && client().game.hasPending(),
                                    "Missing command in flight");
                            screen.tick();
                            require(
                                    !client().canAct() && client().hasActionContext(),
                                    "Submission/display lock mixed");
                            require(
                                    selected.equals(screen.selectedTile()),
                                    "Pending request removed tile highlight");
                            require(
                                    widgets.equals(screen.children()),
                                    "Pending request rebuilt hand");
                            states.forEach(
                                    (b, active) ->
                                            require(
                                                    b.active == active,
                                                    "Pending command dimmed dock"));
                            // An illicit second action would reach the fixture's unexpected-request
                            // assertion.
                            hand.keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                        }));
        steps.add(
                new Step(
                        "capture-pending-table",
                        () -> mc.screen instanceof SeatedTableScreen,
                        () -> {
                            require(client().game.hasPending(), "Missed pending render frame");
                            pendingAppearance.forEach(
                                    (b, active) ->
                                            require(
                                                    b.active == active,
                                                    "Rendering changed pending button brightness"));
                            require(
                                    pendingSelection.equals(
                                            ((SeatedTableScreen) mc.screen).selectedTile()),
                                    "Rendering removed the pending tile highlight");
                        }));
        steps.add(
                new Step(
                        "release pending UI receipt after rendered frame",
                        () -> true,
                        () ->
                                finish =
                                        HttpClient.newHttpClient()
                                                .sendAsync(
                                                        HttpRequest.newBuilder(
                                                                        URI.create(
                                                                                "http://127.0.0.1:18767/fixture/release-ready"))
                                                                .POST(
                                                                        HttpRequest.BodyPublishers
                                                                                .noBody())
                                                                .build(),
                                                        HttpResponse.BodyHandlers.ofString())));
        steps.add(
                new Step(
                        "leave active game",
                        () ->
                                mc.screen instanceof SeatedTableScreen
                                        && finish.isDone()
                                        && !client().busy
                                        && !client().game.hasPending(),
                        () -> {
                            CameraInteractionChecks.openMenu();
                            click(button("seat.leave"));
                        }));
        steps.add(
                new Step(
                        "off-chair participant retains only own hand without commands",
                        () -> !mc.player.isPassenger() && cloudOccupant(false),
                        () -> {
                            require(
                                    client().hosted
                                            .table()
                                            .get("game_id")
                                            .getAsString()
                                            .equals(LocalTableSessionsFixture.A.toString()),
                                    "Departure destroyed active game");
                            require(
                                    client().game == null && client().view == null,
                                    "Departed seat kept private subscription");
                            var block =
                                    (TableBlockEntity)
                                            mc.level.getBlockEntity(HostedNativeFixture.CENTER);
                            var publicTable = SatoriClient.TABLES.worldTables.forTable(block);
                            require(
                                    publicTable != null
                                            && publicTable.view.viewerSeat == 0
                                            && !publicTable.view.tableObserver(),
                                    "Participant view missing after departure");
                            require(
                                    !publicTable.scene.sample(publicTable.scene.now()).isEmpty(),
                                    "Public tiles disappeared");
                            require(
                                    publicTable.scene.center(publicTable.scene.now()) != null,
                                    "Public scores disappeared");
                            require(
                                    publicTable.view.hand(0).stream()
                                            .allMatch(tile -> tile >= 0 && tile < 37),
                                    "Departed own hand became unknown");
                            require(
                                    publicTable.view.actions().isEmpty(),
                                    "World view retained commands");
                            require(
                                    !client().hosted.table().has("render_stream"),
                                    "Lobby packet exposed other render grants");
                            require(
                                    publicTable.scene.sample(publicTable.scene.now()).stream()
                                            .filter(
                                                    t ->
                                                            t.key().startsWith("hand-")
                                                                    && t.seat() != 0)
                                            .allMatch(t -> t.face() == 37),
                                    "Off-chair participant received opponent hand geometry");
                            for (int seat = 1; seat < 4; seat++)
                                require(
                                        publicTable.view.hand(seat).stream()
                                                .allMatch(tile -> tile == 37),
                                        "Off-chair participant sees opponent hand");
                            var renderer =
                                    mc.getBlockEntityRenderDispatcher()
                                            .<TableBlockEntity, TableRenderer.State>getRenderer(
                                                    block);
                            var rendered = renderer.createRenderState();
                            renderer.extractRenderState(
                                    block,
                                    rendered,
                                    0,
                                    mc.gameRenderer.getMainCamera().position(),
                                    null);
                            require(
                                    rendered.tiles.stream()
                                            .filter(
                                                    t ->
                                                            t.key().startsWith("hand-")
                                                                    && t.seat() != 0)
                                            .allMatch(t -> t.face() == 37),
                                    "Native world renderer exposed opponent hands to participant");
                            var delta =
                                    HostedNativeFixture.CENTER
                                            .getCenter()
                                            .add(0, .5, 0)
                                            .subtract(mc.player.getEyePosition());
                            mc.player.setYRot(
                                    (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
                            mc.player.setXRot(
                                    (float)
                                            -Math.toDegrees(
                                                    Math.atan2(
                                                            delta.y,
                                                            Math.hypot(delta.x, delta.z))));
                        }));
        steps.add(new Step("capture-outside-table", () -> mc.screen == null, () -> {}));
        steps.add(
                new Step(
                        "open controller after spectator capture",
                        () -> true,
                        () -> {
                            mc.gameMode.useItemOn(
                                    mc.player,
                                    InteractionHand.MAIN_HAND,
                                    new BlockHitResult(
                                            HostedNativeFixture.CONTROL.getCenter(),
                                            Direction.UP,
                                            HostedNativeFixture.CONTROL,
                                            false));
                        }));
        steps.add(
                new Step(
                        "controller lists current match",
                        () -> mc.screen instanceof ControlScreen,
                        () ->
                                click(
                                        mc.screen.children().stream()
                                                .filter(Button.class::isInstance)
                                                .map(Button.class::cast)
                                                .filter(
                                                        b ->
                                                                b.getMessage()
                                                                        .getString()
                                                                        .equals(
                                                                                Component
                                                                                        .translatable(
                                                                                                "satori.control.matches",
                                                                                                1)
                                                                                        .getString()))
                                                .findFirst()
                                                .orElseThrow())));
        steps.add(
                new Step(
                        "expired shared session restores login form",
                        () -> !buttons("control.highlight").isEmpty(),
                        () -> {
                            var screen = (ControlScreen) mc.screen;
                            try {
                                var field = ControlScreen.class.getDeclaredField("state");
                                field.setAccessible(true);
                                var active = ((JsonObject) field.get(screen)).deepCopy();
                                var expired = active.deepCopy();
                                expired.addProperty("logged_in", false);
                                expired.add("keys", new com.google.gson.JsonArray());
                                screen.update(expired);
                                require(
                                        screen.children().stream()
                                                        .filter(EditBox.class::isInstance)
                                                        .count()
                                                == 2,
                                        "Expired shared session left the matches controls visible");
                                require(
                                        buttons("control.highlight").isEmpty(),
                                        "Expired session retained highlight controls");
                                screen.update(active);
                                click(
                                        screen.children().stream()
                                                .filter(Button.class::isInstance)
                                                .map(Button.class::cast)
                                                .filter(
                                                        b ->
                                                                b.getMessage()
                                                                        .getString()
                                                                        .equals(
                                                                                Component
                                                                                        .translatable(
                                                                                                "satori.control.matches",
                                                                                                1)
                                                                                        .getString()))
                                                .findFirst()
                                                .orElseThrow());
                            } catch (ReflectiveOperationException error) {
                                throw new AssertionError(error);
                            }
                        }));
        steps.add(
                new Step(
                        "capture-control-matches",
                        () -> !buttons("control.highlight").isEmpty(),
                        () -> {
                            try {
                                var field = ControlScreen.class.getDeclaredField("state");
                                field.setAccessible(true);
                                var state = (JsonObject) field.get(mc.screen);
                                var seat =
                                        state.getAsJsonArray("matches")
                                                .get(0)
                                                .getAsJsonObject()
                                                .getAsJsonArray("seats")
                                                .get(0)
                                                .getAsJsonObject();
                                require(
                                        seat.get("name")
                                                .getAsString()
                                                .equals(mc.player.getGameProfile().name()),
                                        "Controller lost departed player's match name");
                            } catch (ReflectiveOperationException error) {
                                throw new AssertionError(error);
                            }
                        }));
        steps.add(
                new Step(
                        "highlight active physical table",
                        () -> button("control.highlight").active,
                        () -> {
                            var delta =
                                    HostedNativeFixture.CENTER
                                            .getCenter()
                                            .add(0, .5, 0)
                                            .subtract(mc.player.getEyePosition());
                            mc.player.setYRot(
                                    (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
                            mc.player.setXRot(
                                    (float)
                                            -Math.toDegrees(
                                                    Math.atan2(
                                                            delta.y,
                                                            Math.hypot(delta.x, delta.z))));
                            click(button("control.highlight"));
                        }));
        steps.add(
                new Step(
                        "capture-table-highlight",
                        () ->
                                mc.screen == null
                                        && SatoriClient.TABLES.worldTables.highlighted(
                                                (TableBlockEntity)
                                                        mc.level.getBlockEntity(
                                                                HostedNativeFixture.CENTER)),
                        () -> highlightStarted = System.nanoTime()));
        steps.add(
                new Step(
                        "highlight expires after five seconds",
                        () -> System.nanoTime() - highlightStarted > 5_100_000_000L,
                        () -> {
                            require(
                                    !SatoriClient.TABLES.worldTables.highlighted(
                                            (TableBlockEntity)
                                                    mc.level.getBlockEntity(
                                                            HostedNativeFixture.CENTER)),
                                    "Highlight did not expire");
                            sit();
                        }));
        steps.add(
                new Step(
                        "rejoin same active game",
                        () -> mc.screen instanceof SeatedTableScreen && cloudOccupant(true),
                        () -> {
                            require(
                                    client().view.gameId.equals(LocalTableSessionsFixture.A),
                                    "Rejoin changed game");
                            require(
                                    !client().view.tableObserver(),
                                    "Seated client retained omniscient view");
                            for (int seat = 1; seat < 4; seat++)
                                require(
                                        client().view.hand(seat).stream().allMatch(t -> t == 37),
                                        "Seated client received opponent hand");
                        }));
        steps.add(
                new Step(
                        "active rejoin advances without a polling sleep",
                        () -> {
                            var state = snapshot();
                            if (state == null || state.get("entry_sessions").getAsInt() == 0)
                                return false;
                            long wait = state.get("entry_wait_ms").getAsLong();
                            System.out.println("HOSTED_ENTRY_WAIT_MS: " + wait);
                            require(wait < 900, "Active rejoin waited for the idle poll: " + wait);
                            return true;
                        },
                        () -> {}));
        steps.add(
                new Step(
                        "finish fixture match",
                        () -> true,
                        () -> {
                            finish =
                                    HttpClient.newHttpClient()
                                            .sendAsync(
                                                    HttpRequest.newBuilder(
                                                                    URI.create(
                                                                            "http://127.0.0.1:18767/fixture/finish"))
                                                            .POST(
                                                                    HttpRequest.BodyPublishers
                                                                            .noBody())
                                                            .build(),
                                                    HttpResponse.BodyHandlers.ofString());
                        }));
        steps.add(
                new Step(
                        "capture-hosted-result",
                        () ->
                                finish.isDone()
                                        && mc.screen instanceof MatchResultScreen
                                        && client().result != null
                                        && !client().busy,
                        () -> {}));
        steps.add(
                new Step(
                        "confirm whole match",
                        () -> button("hosted.confirm").active,
                        () -> click(button("hosted.confirm"))));
        steps.add(
                new Step(
                        "back to preparation",
                        () -> lobby() && client().hosted.phase().equals("preparing"),
                        () -> {
                            require(
                                    client().game == null
                                            && !client().hosted.self().get("ready").getAsBoolean(),
                                    "Finished credentials or readiness leaked into next game");
                            click(button("seat.leave"));
                        }));
        steps.add(
                new Step(
                        "escape and camera released",
                        () -> !mc.player.isPassenger() && !SeatedCamera.active(),
                        () ->
                                require(
                                        mc.getCameraEntity() == mc.player,
                                        "Camera was not restored")));
        steps.add(
                new Step(
                        "destroy physical table edge",
                        () -> !mc.player.isPassenger() && cloudOccupant(false),
                        () -> {
                            mc.gameMode.startDestroyBlock(
                                    HostedNativeFixture.CENTER.offset(1, 0, 0), Direction.UP);
                        }));
        steps.add(
                new Step(
                        "cloud table released",
                        () -> {
                            var state = snapshot();
                            if (state == null) return false;
                            require(
                                    state.get("key_reads").getAsInt() == 2,
                                    "Duplicate key refresh");
                            require(
                                    state.get("seat_commands").getAsInt() == 1,
                                    "Duplicate seat command");
                            var counts = state.getAsJsonObject("command_counts");
                            require(
                                    counts.get("length").getAsInt() == 2,
                                    "Duplicate length command");
                            require(counts.get("ai").getAsInt() == 3, "Duplicate AI command");
                            require(counts.get("ready").getAsInt() == 1, "Duplicate ready command");
                            return !state.getAsJsonObject("tables").has(tableId)
                                    && state.getAsJsonArray("released").asList().stream()
                                            .anyMatch(id -> id.getAsString().equals(tableId));
                        },
                        () ->
                                require(
                                        !mc.level
                                                .getBlockState(HostedNativeFixture.CENTER)
                                                .is(SatoriMahjong.table()),
                                        "Center survived edge destruction")));
        return steps;
    }

    @Override
    public void close() {
        if (api != null) api.close();
    }
}
