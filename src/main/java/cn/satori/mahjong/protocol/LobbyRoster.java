package cn.satori.mahjong.protocol;

import java.util.*;

/** Public, transient room selection, independent of Minecraft transport and game rules. */
public final class LobbyRoster {
    public record Player(UUID minecraftId, String minecraftName) {}

    public record Seat(String minecraftName, String userId, String name, boolean host) {}

    private record Identity(UUID user, String name) {}

    private Map<Integer, Player> players = Map.of();
    private final Map<UUID, Identity> identities = new LinkedHashMap<>();
    private UUID host;

    public void update(Map<Integer, Player> occupants) {
        if (occupants.keySet().stream().anyMatch(i -> i < 0 || i > 3)
                || occupants.values().stream().map(Player::minecraftId).distinct().count()
                        != occupants.size())
            throw new IllegalArgumentException("Invalid physical seats");
        players = Map.copyOf(occupants);
        var present = new HashSet<UUID>();
        players.values().forEach(p -> present.add(p.minecraftId()));
        identities.keySet().retainAll(present);
        selectHost();
    }

    public boolean claim(UUID minecraftId, String userId, String name) {
        if (players.values().stream().noneMatch(p -> p.minecraftId().equals(minecraftId)))
            return false;
        if (userId.isEmpty()) {
            identities.remove(minecraftId);
        } else {
            UUID id;
            try {
                id = UUID.fromString(userId);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            if (!id.toString().equals(userId)
                    || name.isBlank()
                    || name.length() > 128
                    || name.codePoints().anyMatch(Character::isISOControl)) return false;
            if (identities.entrySet().stream()
                    .anyMatch(
                            e -> !e.getKey().equals(minecraftId) && e.getValue().user().equals(id)))
                return false;
            identities.put(minecraftId, new Identity(id, name));
        }
        selectHost();
        return true;
    }

    private void selectHost() {
        if (!identities.containsKey(host))
            host = identities.keySet().stream().findFirst().orElse(null);
    }

    public UUID host() {
        return host;
    }

    public List<Seat> seats() {
        var result = new ArrayList<Seat>();
        for (int direction = 0; direction < 4; direction++) {
            var player = players.get(direction);
            var identity = player == null ? null : identities.get(player.minecraftId());
            result.add(
                    new Seat(
                            player == null ? "" : player.minecraftName(),
                            identity == null ? "" : identity.user().toString(),
                            identity == null ? "" : identity.name(),
                            player != null && player.minecraftId().equals(host)));
        }
        return List.copyOf(result);
    }
}
