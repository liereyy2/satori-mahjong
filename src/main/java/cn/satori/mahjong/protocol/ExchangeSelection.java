package cn.satori.mahjong.protocol;

import java.util.*;

/** UI selection only: every permitted combination comes from the Rust decision. */
public final class ExchangeSelection {
    private String decisionId = "";
    private final LinkedHashMap<String, Integer> selected = new LinkedHashMap<>();

    public void synchronize(CloudView view) {
        String id = view.decision() == null ? "" : view.decision().get("decision_id").getAsString();
        if (!id.equals(decisionId)) {
            selected.clear();
            decisionId = id;
        }
    }

    public boolean active(CloudView view) {
        return view.actions().stream().anyMatch(a -> a.type().equals("exchange_three"));
    }

    private static List<Integer> tiles(CloudView.Action action) {
        return CloudPresentation.actionTiles(action).stream().sorted().toList();
    }

    private static boolean contains(List<Integer> available, Collection<Integer> picked) {
        var remaining = new ArrayList<>(available);
        for (int tile : picked) if (!remaining.remove((Integer) tile)) return false;
        return true;
    }

    public boolean canToggle(CloudView view, String key, int face) {
        if (selected.containsKey(key)) return true;
        var next = new ArrayList<>(selected.values());
        next.add(face);
        return view.actions().stream()
                .filter(a -> a.type().equals("exchange_three"))
                .anyMatch(a -> contains(tiles(a), next));
    }

    public void toggle(CloudView view, String key, int face) {
        if (selected.containsKey(key)) selected.remove(key);
        else if (canToggle(view, key, face)) selected.put(key, face);
    }

    public Optional<CloudView.Action> action(CloudView view) {
        if (selected.size() != 3) return Optional.empty();
        var picked = selected.values().stream().sorted().toList();
        return view.actions().stream()
                .filter(a -> a.type().equals("exchange_three"))
                .filter(a -> tiles(a).equals(picked))
                .findFirst().map(a -> {
                    var slots = view.handSlots();
                    return a.consumeSlots(selected.keySet().stream().map(key -> Objects.requireNonNull(slots.get(key), "selected_hand_slot_missing")).toList());
                });
    }

    public boolean selected(String key) {
        return selected.containsKey(key);
    }

    public int size() {
        return selected.size();
    }
}
