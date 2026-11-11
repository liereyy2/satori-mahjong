package cn.satori.mahjong.protocol;

import com.google.gson.JsonArray;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/** A queue of presentation transitions, never a second mahjong state machine. */
public final class TableScene {
    public record Tile(
            String key,
            int seat,
            int face,
            float x,
            float y,
            float z,
            float yaw,
            boolean standing,
            boolean sideways,
            float pitch,
            float scale,
            float roll,
            TileViewTransform viewTransform) {
        public Tile(
                String key,
                int seat,
                int face,
                float x,
                float y,
                float z,
                float yaw,
                boolean standing,
                boolean sideways,
                float pitch,
                float scale,
                float roll) {
            this(key, seat, face, x, y, z, yaw, standing, sideways, pitch, scale, roll, null);
        }

        public Tile(
                String key,
                int seat,
                int face,
                float x,
                float y,
                float z,
                float yaw,
                boolean standing,
                boolean sideways,
                float pitch,
                float scale) {
            this(key, seat, face, x, y, z, yaw, standing, sideways, pitch, scale, 0);
        }

        public Tile(
                String key,
                int seat,
                int face,
                float x,
                float y,
                float z,
                float yaw,
                boolean standing,
                boolean sideways,
                float pitch) {
            this(key, seat, face, x, y, z, yaw, standing, sideways, pitch, TileGeometry.scale(key));
        }

        public Tile(
                String key,
                int seat,
                int face,
                float x,
                float y,
                float z,
                float yaw,
                boolean standing,
                boolean sideways) {
            this(
                    key,
                    seat,
                    face,
                    x,
                    y,
                    z,
                    yaw + (sideways ? 90 : 0),
                    standing,
                    sideways,
                    standing ? 0 : -90);
        }

        public Tile projected(TileViewTransform value) {
            return new Tile(
                    key, seat, face, x, y, z, yaw, standing, sideways, pitch, scale, roll, value);
        }

        public Tile pose(float x, float y, float z, float yaw, float pitch, int face) {
            return new Tile(
                    key,
                    seat,
                    face,
                    x,
                    y,
                    z,
                    yaw,
                    standing,
                    sideways,
                    pitch,
                    scale,
                    roll,
                    viewTransform);
        }

        public Tile rolled(float value) {
            return new Tile(
                    key,
                    seat,
                    face,
                    x,
                    y,
                    z,
                    yaw,
                    standing,
                    sideways,
                    pitch,
                    scale,
                    value,
                    viewTransform);
        }

        public Tile scaled(float value) {
            return new Tile(
                    key,
                    seat,
                    face,
                    x,
                    y,
                    z,
                    yaw,
                    standing,
                    sideways,
                    pitch,
                    value,
                    roll,
                    viewTransform);
        }
    }

    private final LongSupplier clock;

    public TableScene() {
        this(System::nanoTime);
    }

    public TableScene(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public long now() {
        return clock.getAsLong();
    }

    private record Frame(
            TableTransition motion, TableCenter center, int previousActive, String reaction) {}

    private final Deque<Frame> queue = new ArrayDeque<>();
    private final Deque<Sound> sounds = new ArrayDeque<>();
    private long soundSerial;
    private List<Tile> settled = List.of(), target = List.of();
    private TableCenter settledCenter, targetCenter;
    private int indicatorSeat = -1;
    private long indicatorSince;
    private UUID game;
    private long version = -1;
    private String settledReaction, pulsingReaction;
    private long reactionSince;

    public static List<Tile> layout(CloudView view) {
        return TableLayout.layout(view);
    }

    public void update(CloudView view, boolean animate, long now) {
        JsonArray events = new JsonArray();
        if (animate) {
            var event = new com.google.gson.JsonObject();
            event.addProperty("type", "settle");
            events.add(event);
        }
        update(view, events, now);
    }

    public void update(CloudView view, JsonArray events, long now) {
        update(view, events, now, true);
    }

    /** Append a continuous frame; metadata-only updates preserve queued tile motion. */
    public void append(CloudView view, JsonArray events, long now) {
        update(view, events, now, false);
    }

    private void update(CloudView view, JsonArray events, long now, boolean snapshot) {
        if (view.gameId.equals(game)
                && (view.version < version || view.version == version && !events.isEmpty())) return;
        if (!view.gameId.equals(game)) {
            queue.clear();
            sounds.clear();
            settled = List.of();
            target = List.of();
            settledCenter = targetCenter = null;
            indicatorSeat = -1;
            settledReaction = pulsingReaction = null;
        }
        game = view.gameId;
        version = view.version;
        List<Tile> next = layout(view);
        var center = TableCenter.from(view);
        if (events.isEmpty() && (snapshot || queue.isEmpty())) {
            queue.clear();
            if (snapshot) sounds.clear();
            settled = next;
            settledCenter = center;
            settledReaction = view.reactionDiscard();
        } else {
            long start = queue.isEmpty() ? now : Math.max(now, queue.getLast().motion().endsAt());
            var transition = new TableTransition(target, next, events, start, view.wall);
            for (var sound : transition.sounds())
                sounds.add(new Sound(++soundSerial, sound.name(), sound.actor(), sound.at()));
            queue.add(
                    new Frame(
                            transition,
                            center,
                            targetCenter == null ? -1 : targetCenter.activeSeat(),
                            view.reactionDiscard()));
        }
        target = next;
        targetCenter = center;
    }

    private void advance(long now) {
        while (!queue.isEmpty() && now >= queue.getFirst().motion().endsAt()) {
            var frame = queue.removeFirst();
            settled = frame.motion().target();
            settledCenter = frame.center();
            settledReaction = frame.reaction();
        }
    }

    public List<Tile> sample(long now) {
        advance(now);
        return queue.isEmpty() ? settled : queue.getFirst().motion().sample(now);
    }

    /** Apply the current view to endpoints before interpolation, including discard/meld origins. */
    public List<Tile> sample(long now, UnaryOperator<Tile> viewTransform) {
        advance(now);
        return queue.isEmpty()
                ? settled.stream().map(viewTransform).toList()
                : queue.getFirst().motion().sample(now, viewTransform);
    }

    public boolean animating(long now) {
        advance(now);
        return !queue.isEmpty();
    }

    public float shutter(long now) {
        advance(now);
        return queue.isEmpty() ? 0 : queue.getFirst().motion().shutter(now);
    }

    public List<TableDice.Die> dice(long now) {
        advance(now);
        return queue.isEmpty() ? List.of() : queue.getFirst().motion().dice(now);
    }

    /** Center information is frozen beside the same queued frame as its tile animation. */
    public TableCenter center(long now) {
        advance(now);
        if (queue.isEmpty()) return settledCenter;
        var frame = queue.getFirst();
        var center =
                frame.center()
                        .withActiveSeat(frame.motion().decisionSeat(now, frame.previousActive()));
        return frame.motion().cue(now).equals("exchange_three_completed")
                ? center.withOpeningPhase("exchange_three")
                : center;
    }

    /** Each newly displayed player starts with a visible pulse, including short AI turns. */
    public boolean indicatorLit(long now) {
        var center = center(now);
        int seat = center == null ? -1 : center.activeSeat();
        if (seat != indicatorSeat) {
            indicatorSeat = seat;
            indicatorSince = now;
        }
        return seat >= 0 && Math.floorMod(now - indicatorSince, 650_000_000L) < 380_000_000L;
    }

    public record Call(long id, String voice, int actor) {}

    public record Sound(long id, String name, int actor, long at) {}

    /** Completed contacts survive animation advancement; each listener consumes serials once. */
    public List<Sound> sounds(long afterId, long now) {
        while (!sounds.isEmpty() && sounds.getFirst().at() < now - 10_000_000_000L)
            sounds.removeFirst();
        return sounds.stream().filter(s -> s.id() > afterId && s.at() <= now).toList();
    }

    /**
     * The same queued projection as the visible river; never infer reactions from local actions.
     */
    public String reactionDiscard(long now) {
        advance(now);
        if (queue.isEmpty()) return settledReaction;
        var frame = queue.getFirst();
        return frame.motion().cue(now).equals("dahai") ? null : frame.reaction();
    }

    public boolean reactionLit(long now) {
        String key = reactionDiscard(now);
        if (!Objects.equals(key, pulsingReaction)) {
            pulsingReaction = key;
            reactionSince = now;
        }
        return key != null && Math.floorMod(now - reactionSince, 600_000_000L) < 360_000_000L;
    }

    public Call call(long now) {
        advance(now);
        return queue.isEmpty() ? null : queue.getFirst().motion().call(now);
    }

    public String cue(long now) {
        advance(now);
        return queue.isEmpty() ? "" : queue.getFirst().motion().cue(now);
    }
}
