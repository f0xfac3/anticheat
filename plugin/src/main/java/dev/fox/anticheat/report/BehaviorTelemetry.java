package dev.fox.anticheat.report;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Bounded receive-time summaries. This mirrors analytics/windows.py; it assigns no labels. */
public final class BehaviorTelemetry implements Consumer<ByteBuffer> {
    private static final long SPAN = 30_000_000_000L, GUARD = 5_000_000_000L;
    private final Map<Long, State> sessions = new HashMap<>();
    private final Consumer<Window> output;
    public BehaviorTelemetry(Consumer<Window> output) {
        this.output = output;
    }

    public static final class Window {
        public final String player, world;
        public final long session, event, epoch, segment;
        public final Map<String, Double> values;
        Window(State s, long event, long epoch) {
            player = s.player;
            world = s.world;
            session = s.id;
            this.event = event;
            this.epoch = epoch;
            segment = s.segment;
            Map<String, Double> v = new LinkedHashMap<>();
            v.put("movement_pps", s.moves / 30.0);
            v.put("movement_cv", s.movement.cv());
            v.put("attack_pps", s.attacks / 30.0);
            v.put("attack_cv", s.attack.cv());
            v.put("attack_repeat", s.pairs == 0 ? 0 : s.repeats / (double) s.pairs);
            v.put("ground_fraction", s.moves == 0 ? 0 : s.ground / (double) s.moves);
            v.put("turn_mean", s.turns == 0 ? 0 : s.turnTotal / s.turns);
            v.put("attacks", (double) s.attacks);
            v.put("segment", (double) s.segment);
            values = Collections.unmodifiableMap(v);
        }
        public String json() {
            StringBuilder b = new StringBuilder("{");
            for (Map.Entry<String, Double> e : values.entrySet()) {
                if (b.length() > 1)
                    b.append(',');
                b.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            return b.append('}').toString();
        }
        public String finding() {
            StringBuilder b =
                new StringBuilder(
                    "{\"level\":\"telemetry\",\"check\":\"behavior.window.v1\",\"player\":\"")
                    .append(player)
                    .append("\",\"session\":\"")
                    .append(session)
                    .append("\",\"event\":\"")
                    .append(event)
                    .append("\",\"epoch_ms\":\"")
                    .append(epoch)
                    .append("\",\"message\":\"eligible_behavior_window\",\"evidence\":{");
            for (Map.Entry<String, Double> e : values.entrySet()) {
                if (b.charAt(b.length() - 1) != '{')
                    b.append(',');
                b.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            }
            return b.append("}}").toString();
        }
    }
    private static final class Moments {
        long n;
        double sum, squares;
        void add(double x) {
            n++;
            sum += x;
            squares += x * x;
        }
        double cv() {
            if (n == 0 || sum <= 0)
                return 0;
            double mean = sum / n;
            return Math.sqrt(Math.max(0, squares / n - mean * mean)) / mean;
        }
    }
    private static final class State {
        final long id;
        final String player;
        String world = "";
        long ready = -1, previous = -1, packet = -1, segment;
        long lastMove = -1, lastAttack = -1, moves, attacks, ground, pairs, repeats, turns;
        double lastInterval = Double.NaN, yaw = Double.NaN, turnTotal;
        Moments movement = new Moments(), attack = new Moments();
        State(long id, String player) {
            this.id = id;
            this.player = player;
            segment = 1;
            reset();
        }
        void clear() {
            lastMove = lastAttack = -1;
            moves = attacks = ground = pairs = repeats = turns = 0;
            lastInterval = yaw = Double.NaN;
            turnTotal = 0;
            movement = new Moments();
            attack = new Moments();
        }
        void reset() {
            segment++;
            ready = previous = packet = -1;
            clear();
        }
    }
    private static String text(ByteBuffer b) {
        int n = Short.toUnsignedInt(b.getShort());
        if (n > 1024 || n > b.remaining())
            throw new IllegalArgumentException("text");
        byte[] value = new byte[n];
        b.get(value);
        return new String(value, StandardCharsets.US_ASCII);
    }
    @Override
    public void accept(ByteBuffer source) {
        ByteBuffer b = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 48 || b.getInt() != 0x43415846 || b.getShort() != 3)
            throw new IllegalArgumentException("observation schema");
        int kind = Short.toUnsignedInt(b.getShort());
        long id = b.getLong(), ordinal = b.getLong(), ns = b.getLong(), epoch = b.getLong();
        b.getLong();
        if (kind == 1) {
            String player = text(b);
            UUID.fromString(player);
            if (b.getInt() == 47 && b.getInt() == 10808 && sessions.size() < 4096)
                sessions.put(id, new State(id, player));
            return;
        }
        State s = sessions.get(id);
        if (s == null)
            return;
        if (kind == 2) {
            sessions.remove(id);
            return;
        }
        if (kind == 3 || kind == 10 || kind == 14) {
            s.reset();
            return;
        }
        if (kind != 7 && kind != 11)
            return;
        long packet = b.getLong();
        b.getLong();
        long age = b.getLong() - ns;
        if (kind == 7) {
            String world = text(b);
            text(b);
            text(b);
            text(b);
            b.position(b.position() + 4 + 72 + 4);
            boolean available = b.get() != 0;
            if (!available || world.isEmpty() || age < 0 || age > 100_000_000L) {
                s.reset();
                return;
            }
            if (s.ready < 0 || ns < s.ready || ns >= s.ready + SPAN)
                return;
            if (s.attacks >= 10000 || (s.lastAttack >= 0 && ns <= s.lastAttack)) {
                s.reset();
                return;
            }
            s.attacks++;
            if (s.lastAttack >= 0) {
                double dt = (ns - s.lastAttack) / 1e6;
                s.attack.add(dt);
                if (!Double.isNaN(s.lastInterval)) {
                    s.pairs++;
                    if (Math.abs(dt - s.lastInterval) <= 1)
                        s.repeats++;
                }
                s.lastInterval = dt;
            }
            s.lastAttack = ns;
            return;
        }
        b.position(b.position() + 24);
        double yaw = b.getDouble();
        b.getDouble();
        b.get();
        boolean look = b.get() != 0, ground = b.get() != 0;
        String world = text(b);
        text(b);
        boolean available = b.get() != 0;
        if (!available || world.isEmpty() || !Double.isFinite(yaw) || age < 0
            || age > 100_000_000L) {
            s.reset();
            return;
        }
        if (s.packet >= 0 && packet <= s.packet) {
            s.reset();
            return;
        }
        if (s.previous >= 0 && (ns < s.previous || ns - s.previous >= 750_000_000L))
            s.reset();
        if (s.ready < 0)
            s.ready = ns + GUARD;
        s.previous = ns;
        s.packet = packet;
        s.world = world;
        if (ns < s.ready)
            return;
        if (ns >= s.ready + SPAN) {
            output.accept(new Window(s, ordinal, epoch));
            s.ready += SPAN;
            s.clear();
        }
        if (s.moves >= 20000) {
            s.reset();
            return;
        }
        s.moves++;
        if (ground)
            s.ground++;
        if (s.lastMove >= 0)
            s.movement.add((ns - s.lastMove) / 1e6);
        s.lastMove = ns;
        if (look) {
            if (!Double.isNaN(s.yaw)) {
                double d = (yaw - s.yaw + 180) % 360;
                if (d < 0)
                    d += 360;
                s.turnTotal += Math.abs(d - 180);
                s.turns++;
            }
            s.yaw = yaw;
        }
    }
}
