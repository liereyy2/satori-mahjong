package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class LobbyRosterTest {
    @Test
    void automaticSeatsGiveEveryPlayerTheSameWorldOrientation() {
        var seen = new HashSet<Integer>();
        for (int chair = 0; chair < 4; chair++) {
            int seat = TableOrientation.cloudSeat(chair);
            seen.add(seat);
            assertEquals(chair, TableOrientation.chairForSeat(seat));
            assertEquals(0, Math.floorMod((int) TableOrientation.sceneDegrees(seat, chair), 360));
        }
        assertEquals(Set.of(0, 1, 2, 3), seen);
        assertEquals(
                List.of(0, 3, 2, 1),
                java.util.stream.IntStream.range(0, 4)
                        .map(TableOrientation::chairForSeat)
                        .boxed()
                        .toList());
    }

    private static UUID id(int value) {
        return new UUID(0, value);
    }

    private static LobbyRoster.Player player(int value) {
        return new LobbyRoster.Player(id(value), "MC" + value);
    }

    @Test
    void fourPlayersKeepTheirPhysicalSeatsAndTransferHostOnDeparture() {
        var roster = new LobbyRoster();
        var players = new HashMap<Integer, LobbyRoster.Player>();
        for (int seat = 0; seat < 4; seat++) players.put(seat, player(seat + 1));
        roster.update(players);
        for (int seat : List.of(2, 0, 1, 3))
            assertTrue(roster.claim(id(seat + 1), id(100 + seat).toString(), "Satori" + seat));
        assertEquals(id(3), roster.host());
        for (int seat = 0; seat < 4; seat++) {
            assertEquals(id(100 + seat).toString(), roster.seats().get(seat).userId());
            assertEquals(seat == 2, roster.seats().get(seat).host());
        }
        players.remove(2);
        roster.update(players);
        assertEquals(id(1), roster.host());
        assertEquals("", roster.seats().get(2).userId());
        assertTrue(roster.claim(id(1), "", ""));
        assertEquals(id(2), roster.host());
        roster.update(Map.of());
        assertNull(roster.host());
        assertTrue(roster.seats().stream().allMatch(s -> s.userId().isEmpty()));
    }

    @Test
    void anotherTableAndAReplacementOccupantDoNotInheritIdentity() {
        var a = new LobbyRoster();
        var b = new LobbyRoster();
        a.update(Map.of(0, player(1), 1, player(2)));
        b.update(Map.of(0, player(3)));
        assertTrue(a.claim(id(1), id(100).toString(), "A"));
        assertTrue(b.claim(id(3), id(100).toString(), "A"));
        assertFalse(a.claim(id(2), id(100).toString(), "Duplicate"));
        assertFalse(a.claim(id(3), id(101).toString(), "Not seated"));
        a.update(Map.of(0, player(4)));
        assertNull(a.host());
        assertEquals("", a.seats().getFirst().userId());
        assertEquals(id(100).toString(), b.seats().getFirst().userId());
    }

    @Test
    void invalidClaimsDoNotDestroyTheExistingPublicRoster() {
        var roster = new LobbyRoster();
        roster.update(Map.of(3, player(1)));
        assertTrue(roster.claim(id(1), id(100).toString(), "Player"));
        var before = roster.seats();
        for (String user : List.of("bad", "1-1-1-1-1"))
            assertFalse(roster.claim(id(1), user, "Player"));
        for (String name : List.of("", "x\ny", "x".repeat(129)))
            assertFalse(roster.claim(id(1), id(101).toString(), name));
        assertEquals(before, roster.seats());
        assertThrows(
                IllegalArgumentException.class,
                () -> roster.update(Map.of(0, player(1), 1, player(1))));
    }
}
