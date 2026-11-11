package cn.satori.mahjong.client;

import cn.satori.mahjong.TableBlockEntity;
import cn.satori.mahjong.TablePackets;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Main-thread presentation controller; only explicitly saved account passwords persist locally. */
public final class ClientController {
    final ClientIdentity account;
    net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    int seatDirection;

    public ClientController(AccountConfig config) {
        this(new SatoriApi(), config);
    }

    ClientController(SatoriApi api, AccountConfig config) {
        this(new ClientIdentity(api, config));
    }

    ClientController(ClientIdentity account) {
        this.account = account;
        account.controllers.add(this);
        if (account.focused == null) account.focused = this;
    }

    public TablePackets.Open table;
    HostedClient hosted;
    public TablePackets.Lobby lobby;
    public CloudView view;
    public CloudPresentation presentation;
    String dismissedSettlement;
    private boolean presentationLoading;
    private long nextPresentationCheck;
    public String presentationError = "";
    public TableScene scene = new TableScene();
    public GameConnection game;
    public boolean busy;
    public String message = "welcome";
    public String connection = "disconnected";
    public JsonObject result;
    private JsonObject pendingCreate;
    private int generation;
    private int subscription;
    private TablePackets.Link pendingLink;
    private boolean linkSent;
    private net.minecraft.client.gui.screens.Screen creationSource;

    public SatoriApi.User user() {
        return account.api.user();
    }

    public String statusMessage() {
        return presentationError.isEmpty() ? message : presentationError;
    }

    public boolean canRetryCreate() {
        return pendingCreate != null;
    }

    boolean acknowledgeLink(TablePackets.Linked target) {
        if (pendingLink != null
                && pendingLink.dimension().equals(target.dimension())
                && pendingLink.pos().equals(target.pos())
                && pendingLink.gameId().equals(target.gameId())) {
            pendingLink = null;
            linkSent = false;
            table = new TablePackets.Open(target.pos(), target.gameId(), table.editable());
            // A link acknowledgement updates metadata, not the user's chosen page.
            return true;
        }
        return false;
    }

    public void open(TablePackets.Open target) {
        account.focused = this;
        dimension = Objects.requireNonNull(Minecraft.getInstance().level).dimension();
        boolean awaitingBinding =
                pendingLink != null
                        && pendingLink.pos().equals(target.pos())
                        && pendingLink.expected().equals(target.gameId());
        if (!awaitingBinding) {
            pendingLink = null;
            if (game != null && !game.gameId.toString().equals(target.gameId())) closeGame();
        }
        linkSent = false;
        table = target;
        flushLink();
        Minecraft.getInstance()
                .setScreen(user() == null ? new AccountScreen(this) : new TableLobbyScreen(this));
        restoreLogin();
    }

    void updateLobby(TablePackets.Lobby snapshot) {
        if (table == null
                || !snapshot.dimension().equals(dimension)
                || !snapshot.pos().equals(table.pos())) return;
        lobby = snapshot;
        boolean bindingChanged = !table.gameId().equals(snapshot.gameId());
        table = new TablePackets.Open(snapshot.pos(), snapshot.gameId(), snapshot.editable());
        if (bindingChanged
                && pendingLink == null
                && game != null
                && !game.gameId.toString().equals(snapshot.gameId())) closeGame();
    }

    public void openLobby() {
        Minecraft.getInstance()
                .setScreen(user() == null ? new AccountScreen(this) : new TableLobbyScreen(this));
    }

    public void openAccount() {
        account.focused = this;
        Minecraft.getInstance().setScreen(new AccountScreen(this));
        restoreLogin();
    }

    private void restoreLogin() {
        if (account.restoreAttempted || user() != null || busy) return;
        account.restoreAttempted = true;
        try {
            account.config
                    .read()
                    .ifPresent(saved -> login(saved.username(), saved.password(), false));
        } catch (IOException invalid) {
            message = "config_read_error";
        }
    }

    public void login(String email, String secret, boolean code) {
        if (busy || account.authenticating) return;
        account.authenticating = true;
        account.restoreAttempted = true;
        var source = Minecraft.getInstance().screen;
        run(
                account.api.login(email, secret, code),
                user -> {
                    account.authenticating = false;
                    message = "logged_in";
                    try {
                        if (code) account.config.clear();
                        else
                            account.config.save(
                                    new AccountConfig.Credentials(email.trim(), secret));
                    } catch (IOException storage) {
                        message = "config_write_error";
                    }
                    account.nextSessionCheck = System.nanoTime() + 60_000_000_000L;
                    if (Minecraft.getInstance().screen == source && ownsPage(source))
                        Minecraft.getInstance()
                                .setScreen(
                                        table == null
                                                ? new AccountScreen(this)
                                                : new TableLobbyScreen(this));
                },
                error -> account.authenticating = false);
    }

    public void sendCode(String email) {
        run(account.api.sendCode(email), response -> message = "code_sent");
    }

    public void reportBug(
            BugReport report,
            String title,
            String description,
            Consumer<UUID> success,
            Consumer<String> failure) {
        if (hosted != null) {
            if (!busy) hosted.feedback(report, title, description, success, failure);
            return;
        }
        if (busy || user() == null) return;
        run(
                account.api.reportBug(report, title, description),
                id -> {
                    message = "feedback_sent";
                    success.accept(id);
                },
                failure);
    }

    public void logout() {
        SatoriApi previous = account.api;
        var request = previous.logout();
        clearIdentity("logged_out");
        account.api = previous.fresh();
        account.authenticating = true;
        busy = true;
        int active = account.generation;
        request.whenComplete(
                (ignored, error) -> {
                    previous.close();
                    Minecraft.getInstance()
                            .execute(
                                    () -> {
                                        if (active != account.generation) return;
                                        account.authenticating = false;
                                        busy = false;
                                        if (error != null && !SatoriApi.accountExpired(error))
                                            message = "logout_network_error";
                                    });
                });
    }

    void resetLocal(String status) {
        generation++;
        busy = false;
        closeGame();
        pendingCreate = null;
        creationSource = null;
        pendingLink = null;
        linkSent = false;
        message = status;
    }

    private void clearIdentity(String status) {
        account.clear(true, status);
        Minecraft.getInstance().setScreen(new AccountScreen(account.focused));
    }

    private void accountExpired() {
        accountExpired("session_expired");
    }

    private void accountExpired(String status) {
        var previous = account.api;
        account.api = previous.fresh();
        previous.close();
        clearIdentity(status);
    }

    private final TableCalls calls = new TableCalls();

    public void tick() {
        if (seated()) seatDirection = SeatedCamera.direction();
        calls.tick(scene, seated());
        flushLink();
        if (user() != null
                && !account.authenticating
                && !account.checkingSession
                && System.nanoTime() >= account.nextSessionCheck) validateSession();
        loadPresentation();
        if (hosted != null) hosted.tick();
        var screen = Minecraft.getInstance().screen;
        if (!(screen instanceof TablePage owned) || owned.controller() != this) return;
        if (view != null
                && view.needsCredits()
                && (screen instanceof SeatedTableScreen || screen instanceof GameRoomScreen)) {
            Minecraft.getInstance().setScreen(new CreditsRequiredScreen(this));
            return;
        }
        if (seated()
                && (screen instanceof SeatedTableScreen || screen instanceof GameRoomScreen)
                && presentation != null
                && !scene.animating(scene.now())) {
            var result = presentation.settlement();
            if (result != null && !result.get("id").getAsString().equals(dismissedSettlement)) {
                Minecraft.getInstance().setScreen(new SettlementScreen(this, result));
                return;
            }
        }
        if (view != null
                && (screen instanceof SeatedTableScreen || screen instanceof GameRoomScreen)) {
            if (view.status().equals("finished")) {
                if (presentation != null && !scene.animating(scene.now()))
                    Minecraft.getInstance().setScreen(new MatchResultScreen(this));
            } else if (view.status().equals("playing")
                    && screen instanceof GameRoomScreen
                    && seated()) {
                Minecraft.getInstance().setScreen(new SeatedTableScreen(this));
            } else if (!view.status().equals("playing") && screen instanceof SeatedTableScreen) {
                Minecraft.getInstance()
                        .setScreen(
                                hosted != null
                                        ? new HostedLobbyScreen(this)
                                        : new GameRoomScreen(this));
            }
        }
    }

    public void showGame() {
        if (hosted != null && view == null) {
            Minecraft.getInstance().setScreen(new HostedLobbyScreen(this));
            return;
        }
        Minecraft.getInstance()
                .setScreen(
                        view != null && view.status().equals("finished")
                                ? new MatchResultScreen(this)
                                : view != null && view.status().equals("playing") && seated()
                                        ? new SeatedTableScreen(this)
                                        : new GameRoomScreen(this));
    }

    public void enterGame(UUID id) {
        if (game != null && game.gameId.equals(id) && connection.equals("connected")) showGame();
        else join(id.toString(), true);
    }

    public boolean canEnterGame(UUID id) {
        if (table == null) return false;
        if (table.editable()) return true;
        var level = Minecraft.getInstance().level;
        return level != null
                && level.dimension().equals(dimension)
                && level.getBlockEntity(table.pos()) instanceof TableBlockEntity current
                && current.gameId().equals(id.toString());
    }

    public void loadGames(
            String cursor, int limit, Consumer<SavedGames.Page> success, Consumer<String> failure) {
        if (user() == null) {
            failure.accept("session_expired");
            return;
        }
        int active = generation;
        account.api
                .games(cursor, limit)
                .whenComplete(
                        (page, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (active != generation) return;
                                                    if (error != null
                                                            && SatoriApi.accountExpired(error)) {
                                                        accountExpired(SatoriApi.errorCode(error));
                                                    } else if (error != null) {
                                                        message = SatoriApi.errorCode(error);
                                                        failure.accept(message);
                                                    } else success.accept(page);
                                                }));
    }

    public void deleteGame(UUID id, Runnable refreshed) {
        if (busy || user() == null) return;
        run(
                account.api.deleteGame(id),
                response -> {
                    for (var controller : account.controllers) {
                        if (controller.game != null && controller.game.gameId.equals(id))
                            controller.closeGame();
                        if (controller.table != null
                                && controller.table.editable()
                                && (controller.table.gameId().equals(id.toString())
                                        || controller.pendingLink != null
                                                && controller
                                                        .pendingLink
                                                        .gameId()
                                                        .equals(id.toString())))
                            controller.unlink();
                    }
                    message = "game_deleted";
                    refreshed.run();
                });
    }

    private void loadPresentation() {
        if (view == null
                || game == null
                || !connection.equals("connected")
                || presentationLoading
                || presentation != null && presentation.version == view.version
                || System.nanoTime() < nextPresentationCheck) return;
        presentationLoading = true;
        var destination = game;
        int active = subscription;
        destination
                .presentation()
                .whenComplete(
                        (data, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (active != subscription
                                                            || game != destination) return;
                                                    presentationLoading = false;
                                                    nextPresentationCheck =
                                                            System.nanoTime()
                                                                    + (error == null
                                                                            ? 400_000_000L
                                                                            : 5_000_000_000L);
                                                    if (error != null) {
                                                        presentationError =
                                                                CloudPresentation.errorMessage(
                                                                        error);
                                                        return;
                                                    }
                                                    try {
                                                        if (view == null) return;
                                                        if (!data.has("view_version"))
                                                            throw new IllegalArgumentException(
                                                                    "missing_view_version");
                                                        if (data.get("view_version").getAsLong()
                                                                != view.version) return;
                                                        presentation =
                                                                new CloudPresentation(data, view);
                                                        presentationError = "";
                                                    } catch (IllegalArgumentException
                                                            | IllegalStateException
                                                            | NullPointerException invalid) {
                                                        presentationError = "protocol_error";
                                                    }
                                                }));
    }

    void validateSession() {
        account.checkingSession = true;
        account.nextSessionCheck = System.nanoTime() + 60_000_000_000L;
        int active = account.generation;
        account.api
                .session()
                .whenComplete(
                        (user, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (active != account.generation) return;
                                                    account.checkingSession = false;
                                                    if (error == null && user == null
                                                            || error != null
                                                                    && SatoriApi.accountExpired(
                                                                            error))
                                                        accountExpired();
                                                }));
    }

    public void loadFriends() {
        run(
                account.api.friends(),
                response -> {
                    account.friends = response.getAsJsonArray("items");
                    message = "friends_loaded";
                });
    }

    public AccountKey selectedKey() {
        return account.keys.stream()
                .filter(k -> k.id().equals(account.selectedKey) && k.available())
                .findFirst()
                .orElse(null);
    }

    public void loadKeys() {
        if (user() == null || account.keysLoading) return;
        account.keysLoading = true;
        account.keysMessage = "keys.loading";
        account.keysRevision++;
        int request = ++account.keysRequest;
        account.api
                .apiKeys()
                .whenComplete(
                        (items, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (request != account.keysRequest) return;
                                                    if (error != null
                                                            && SatoriApi.accountExpired(error)) {
                                                        accountExpired();
                                                        return;
                                                    }
                                                    account.keysLoading = false;
                                                    account.keys =
                                                            error == null ? items : List.of();
                                                    if (selectedKey() == null)
                                                        account.selectedKey = null;
                                                    account.keysMessage =
                                                            error == null
                                                                    ? (account.keys.stream()
                                                                                    .anyMatch(
                                                                                            AccountKey
                                                                                                    ::available)
                                                                            ? "keys.help"
                                                                            : "keys.empty")
                                                                    : "message."
                                                                            + SatoriApi.errorCode(
                                                                                    error);
                                                    account.keysRevision++;
                                                }));
    }

    public void create(List<String> seats, boolean east) {
        if (busy || user() == null || table == null || !table.editable()) return;
        if (pendingCreate != null) {
            message = "creation_pending";
            return;
        }
        JsonObject draft;
        creationSource = Minecraft.getInstance().screen;
        try {
            draft = GameDraft.plan(user().id(), seats, east, UUID.randomUUID());
        } catch (IllegalArgumentException error) {
            message = error.getMessage();
            return;
        }
        if (draft.getAsJsonArray("ai_seats").isEmpty()) {
            pendingCreate = draft;
            retryCreate();
        } else if (selectedKey() == null || account.keysLoading) {
            message = "ai_key_required";
        } else {
            TablePackets.Open destination = table;
            run(
                    account.api.apiKey(account.selectedKey),
                    key -> {
                        if (!Objects.equals(destination, table)) return;
                        draft.addProperty("api_key", key);
                        pendingCreate = draft;
                        retryCreate();
                    });
        }
    }

    public void authorizeAi() {
        if (busy
                || game == null
                || view == null
                || user() == null
                || !view.owner().equals(user().id())
                || !connection.equals("connected")) return;
        if (selectedKey() == null || account.keysLoading) {
            message = "ai_key_required";
            return;
        }
        GameConnection destination = game;
        run(
                account.api.apiKey(account.selectedKey),
                key -> {
                    if (game != destination) return;
                    JsonObject body = new JsonObject();
                    body.addProperty("api_key", key);
                    command("PUT", "/ai-authorization", body);
                });
    }

    public void retryCreate() {
        if (busy || pendingCreate == null) return;
        TablePackets.Open destination = table;
        JsonObject submitted = pendingCreate;
        run(
                account.api
                        .create(submitted)
                        .whenComplete(
                                (response, error) -> {
                                    // Only release the draft on an unambiguous response. No secret
                                    // is printed or persisted.
                                    if (error != null
                                            && unwrap(error) instanceof SatoriApi.ApiException ex
                                            && ex.status >= 400
                                            && ex.status < 500)
                                        Minecraft.getInstance()
                                                .execute(
                                                        () -> {
                                                            if (pendingCreate == submitted)
                                                                pendingCreate = null;
                                                        });
                                }),
                response -> {
                    var source = creationSource;
                    pendingCreate = null;
                    creationSource = null;
                    attach(response, false);
                    if (destination != null && destination.equals(table) && destination.editable())
                        link(destination, game.gameId.toString());
                    message = "game_created";
                    if (Minecraft.getInstance().screen == source && ownsPage(source))
                        Minecraft.getInstance().setScreen(new GameRoomScreen(this));
                });
    }

    public void join(String id, boolean link) {
        if (busy || user() == null) return;
        UUID gameId;
        try {
            gameId = UUID.fromString(id.trim());
        } catch (IllegalArgumentException error) {
            message = "invalid_game_id";
            return;
        }
        TablePackets.Open destination = table;
        var source = Minecraft.getInstance().screen;
        run(
                account.api.join(gameId),
                response -> {
                    if (destination != null
                            && (table == null
                                    || !destination.pos().equals(table.pos())
                                    || !destination.gameId().equals(table.gameId()))) {
                        message = "table_changed";
                        return;
                    }
                    attach(response, false);
                    if (link
                            && destination != null
                            && destination.equals(table)
                            && destination.editable()) link(destination, gameId.toString());
                    message = "joined";
                    if (Minecraft.getInstance().screen == source && ownsPage(source))
                        Minecraft.getInstance().setScreen(new GameRoomScreen(this));
                });
    }

    void attach(JsonObject response, boolean opening) {
        closeGame();
        int active = subscription;
        game =
                new GameConnection(
                        account.api,
                        UUID.fromString(response.get("game_id").getAsString()),
                        response.getAsJsonObject("self_session"),
                        (next, events) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (active != subscription) return;
                                                    scene.update(next, events, scene.now());
                                                    view = next;
                                                    presentation = null;
                                                }),
                        status ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (active != subscription) return;
                                                    connection = status;
                                                    if (status.equals("game_not_found")) {
                                                        gameRemoved();
                                                        return;
                                                    }
                                                    if (!Set.of(
                                                                    "connected",
                                                                    "connecting",
                                                                    "reconnecting")
                                                            .contains(status)) {
                                                        view = null;
                                                        presentation = null;
                                                        scene = new TableScene();
                                                        message = status;
                                                    }
                                                }));
        game.start(opening);
    }

    public boolean canAct() {
        return hasActionContext() && !busy && !game.hasPending();
    }

    /** Confirmed action presentation; a pending HTTP receipt does not change the visible hand. */
    boolean hasActionContext() {
        return view != null
                && view.status().equals("playing")
                && game != null
                && game.gameId.equals(view.gameId)
                && game.seat == view.viewerSeat
                && connection.equals("connected")
                && !scene.animating(scene.now());
    }

    public void action(CloudView.Action action) {
        if (view == null
                || !view.actions().stream()
                        .anyMatch(
                                a ->
                                        a.actionId().equals(action.actionId())
                                                && a.decisionId().equals(action.decisionId()))) {
            message = "decision_expired";
            return;
        }
        if (!canAct()) return;
        JsonObject body = new JsonObject();
        body.addProperty("decision_id", action.decisionId());
        body.addProperty("action_id", action.actionId());
        if (action.display().has("consume_slot_ids"))
            body.add("consume_slot_ids", action.display().getAsJsonArray("consume_slot_ids").deepCopy());
        command("POST", "/actions", body);
    }

    public void command(String method, String path, JsonObject body) {
        if (busy || game == null || !connection.equals("connected")) return;
        run(game.command(method, path, body), response -> message = "");
    }

    public void fetchResult() {
        var destination = game;
        if (destination != null && !busy)
            run(
                    destination.result(),
                    response -> {
                        if (game != destination) return;
                        MatchResult.parse(response, destination.gameId);
                        result = response;
                        message = "result_received";
                    });
    }

    public void unlink() {
        if (table == null || !table.editable()) return;
        // MC packets are ordered: an already-sent bind precedes this unbind.
        var destination =
                pendingLink != null && linkSent
                        ? new TablePackets.Open(table.pos(), pendingLink.gameId(), table.editable())
                        : table;
        link(destination, "");
    }

    void link(TablePackets.Open destination, String id) {
        pendingLink = new TablePackets.Link(dimension, destination.pos(), destination.gameId(), id);
        linkSent = false;
        flushLink();
    }

    private void flushLink() {
        var mc = Minecraft.getInstance();
        if (pendingLink == null
                || linkSent
                || mc.level == null
                || mc.player == null
                || !mc.level.dimension().equals(dimension)
                || mc.player.distanceToSqr(pendingLink.pos().getCenter()) > 64
                || !mc.level.hasChunkAt(pendingLink.pos())) return;
        linkSent = true;
        SatoriClient.sendLink(pendingLink);
    }

    private boolean ownsPage(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof TablePage page && page.controller() == this;
    }

    boolean seated() {
        var level = Minecraft.getInstance().level;
        return table != null
                && level != null
                && level.dimension().equals(dimension)
                && SeatedCamera.active()
                && table.pos().equals(SeatedCamera.center());
    }

    void gameRemoved() {
        if (hosted != null && game != null) hosted.removedGame = game.gameId.toString();
        closeGame();
        busy = false;
        message = "game_not_found";
        var mc = Minecraft.getInstance();
        if (ownsPage(mc.screen))
            mc.setScreen(hosted != null ? new HostedLobbyScreen(this) : new TableLobbyScreen(this));
    }

    public void closeGame() {
        subscription++;
        if (game != null) game.close();
        game = null;
        view = null;
        presentation = null;
        presentationLoading = false;
        nextPresentationCheck = 0;
        presentationError = "";
        dismissedSettlement = null;
        result = null;
        scene = new TableScene();
        connection = "disconnected";
    }

    public void disconnect() {
        SeatedCamera.reset();
        var previous = account.api;
        account.api = previous.fresh();
        previous.close();
        account.clear(false, "welcome");
        for (var controller : account.controllers) {
            controller.table = null;
            controller.dimension = null;
        }
    }

    private <T> void run(CompletableFuture<T> future, Consumer<T> success) {
        run(future, success, ignored -> {});
    }

    private <T> void run(
            CompletableFuture<T> future, Consumer<T> success, Consumer<String> failure) {
        busy = true;
        message = "requesting";
        int active = generation;
        future.whenComplete(
                (value, error) ->
                        Minecraft.getInstance()
                                .execute(
                                        () -> {
                                            if (active != generation) return;
                                            busy = false;
                                            if (error != null && SatoriApi.accountExpired(error))
                                                accountExpired(SatoriApi.errorCode(error));
                                            else if (error != null) {
                                                message = SatoriApi.errorCode(error);
                                                if (message.equals("game_not_found")) gameRemoved();
                                                failure.accept(message);
                                            } else {
                                                try {
                                                    success.accept(value);
                                                } catch (RuntimeException invalid) {
                                                    message = "protocol_error";
                                                }
                                            }
                                            if (Minecraft.getInstance().screen
                                                    instanceof SatoriScreen screen)
                                                screen.refreshButtons();
                                        }));
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException) error = error.getCause();
        return error;
    }
}
