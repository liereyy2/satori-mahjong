package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.TableOrientation;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.*;

final class CreateScreen extends SatoriScreen {
    private final EditBox[] seats = new EditBox[4];
    private final int[] friendIndex = new int[4];
    private Button key;
    private Button submit;
    private Button length;
    private boolean east = true;
    private final String[] seatedUsers = new String[4];
    private cn.satori.mahjong.TablePackets.Lobby roster;

    CreateScreen(ClientController client) {
        super(client, "create.title");
        Arrays.fill(friendIndex, -1);
        client.loadKeys();
    }

    @Override
    protected void build() {
        for (int i = 0; i < 4; i++) {
            int seat = i;
            seats[i] =
                    input(
                            "create.user_id",
                            35,
                            38 + i * 25,
                            panelWidth - 153,
                            36,
                            seats[i] != null ? seats[i].getValue() : initialSeat(i),
                            false);
            button(
                    tr("create.ai"),
                    panelWidth - 112,
                    38 + i * 25,
                    38,
                    () ->
                            client.lobby == null
                                    || client.lobby
                                            .seats()
                                            .get(TableOrientation.chairForSeat(seat))
                                            .minecraftName()
                                            .isEmpty(),
                    () -> seats[seat].setValue("AI"));
            button(
                    tr("create.friend"),
                    panelWidth - 70,
                    38 + i * 25,
                    58,
                    () ->
                            client.lobby == null
                                    || client.lobby
                                            .seats()
                                            .get(TableOrientation.chairForSeat(seat))
                                            .minecraftName()
                                            .isEmpty(),
                    () -> {
                        if (client.account.friends.isEmpty()) {
                            client.loadFriends();
                            return;
                        }
                        friendIndex[seat] = (friendIndex[seat] + 1) % client.account.friends.size();
                        JsonObject friend =
                                client.account.friends.get(friendIndex[seat]).getAsJsonObject();
                        seats[seat].setValue(friend.get("user_id").getAsString());
                        seats[seat].setTooltip(
                                Tooltip.create(
                                        Component.literal(friend.get("username").getAsString())));
                        client.message = "friend_selected";
                    });
        }
        key =
                button(
                        "keys.choose",
                        12,
                        142,
                        panelWidth - 24,
                        () -> minecraft.setScreen(new KeyScreen(client, this)));
        length =
                button(
                        east ? "create.east" : "create.hanchan",
                        12,
                        170,
                        95,
                        () -> {
                            east = !east;
                            length.setMessage(tr(east ? "create.east" : "create.hanchan"));
                        });
        button("back", 113, 170, 70, () -> minecraft.setScreen(new TableScreen(client)));
        submit =
                button(
                        tr("create.submit"),
                        panelWidth - 105,
                        170,
                        93,
                        () -> client.table != null && client.table.editable() && occupantsReady(),
                        () -> {
                            if (client.canRetryCreate()) {
                                client.retryCreate();
                                return;
                            }
                            client.create(
                                    Arrays.stream(seats).map(EditBox::getValue).toList(), east);
                        });
        syncRoster();
    }

    private String initialSeat(int seat) {
        if (client.lobby != null) {
            String id = client.lobby.seats().get(TableOrientation.chairForSeat(seat)).userId();
            if (!id.isEmpty()) return id;
            if (client.lobby.seats().stream()
                    .anyMatch(p -> p.userId().equals(client.user().id().toString()))) return "";
        }
        return seat == 0 ? client.user().id().toString() : "";
    }

    private boolean occupantsReady() {
        if (client.lobby == null) return true;
        for (int seat = 0; seat < 4; seat++) {
            var occupant = client.lobby.seats().get(TableOrientation.chairForSeat(seat));
            if (!occupant.minecraftName().isEmpty()
                    && (occupant.userId().isEmpty()
                            || !occupant.userId().equals(seats[seat].getValue().trim())))
                return false;
        }
        return true;
    }

    private void syncRoster() {
        if (roster == client.lobby) return;
        roster = client.lobby;
        if (roster == null) return;
        for (int seat = 0; seat < 4; seat++) {
            var occupant = roster.seats().get(TableOrientation.chairForSeat(seat));
            String id = occupant.userId();
            if (!id.isEmpty()) {
                seats[seat].setValue(id);
                seats[seat].setTooltip(
                        Tooltip.create(
                                Component.literal(
                                        occupant.name() + " · " + occupant.minecraftName())));
            } else if (Objects.equals(seats[seat].getValue(), seatedUsers[seat])) {
                seats[seat].setValue("");
                seats[seat].setTooltip(null);
            }
            seatedUsers[seat] = id;
            seats[seat].setEditable(occupant.minecraftName().isEmpty());
        }
    }

    @Override
    public void tick() {
        syncRoster();
        super.tick();
    }

    int referenceSeat() {
        if (client.user() != null)
            for (int seat = 0; seat < 4; seat++)
                if (seats[seat] != null
                        && seats[seat].getValue().trim().equals(client.user().id().toString()))
                    return seat;
        // An incomplete draft uses seat zero as its editing coordinate origin.
        return 0;
    }

    List<Integer> aiSeats() {
        var result = new ArrayList<Integer>();
        for (int seat = 0; seat < 4; seat++)
            if (seats[seat] != null && seats[seat].getValue().trim().equalsIgnoreCase("AI"))
                result.add(seat);
        return List.copyOf(result);
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        for (int i = 0; i < 4; i++)
            label(graphics, Integer.toString(i), 14, 44 + i * 25, 0xFF62DCC2);
        if (panelHeight >= 260) label(graphics, tr("create.help"), 12, 202, 0xFF91B8BF);
    }

    @Override
    public void refreshButtons() {
        super.refreshButtons();
        if (key != null) KeyScreen.updateSelection(key, client);
        if (submit != null) submit.setMessage(tr("create.submit"));
    }
}
