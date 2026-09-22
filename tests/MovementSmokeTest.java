package dev.fox.anticheat.bridge;

import dev.fox.anticheat.event.*;
import dev.fox.anticheat.packet.PacketInfo;
import java.io.File;
import java.nio.ByteBuffer;

/** Schema-3 Java -> JNI -> native checks, including malformed and old recordings. */
public final class MovementSmokeTest {
    static int passed;
    static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
        passed++;
        System.out.println("PASS " + why);
    }
    static boolean has(String[] records, String s) {
        if (records != null)
            for (String r : records)
                if (r.contains(s))
                    return true;
        return false;
    }
    static boolean rejects(NativeBridge e, ByteBuffer b) {
        try {
            e.submit(b);
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }
    public static void main(String[] args) throws Exception {
        NativeBridge.load(new File(args[0]));
        EventWriter w = new EventWriter();
        MovementContext c = new MovementContext();
        c.world = "world";
        c.reason = "";
        c.available = c.clearPath = true;
        c.movementSpeed = .1;
        c.friction = .6;
        c.jumpVelocity = .42;
        try (NativeBridge e = new NativeBridge("trace=false")) {
            e.submit(w.begin(EventWriter.START, 1, 1, 0, 0, 0).session("test", 47, 10808).finish());
            int timer = 0, nofall = 0;
            long ordinal = 1;
            for (int i = 0; i < 700; i++) {
                long t = 1_000_000_000L + i * 25_000_000L;
                MovementEvent m = new MovementEvent(new PacketInfo(i + 1, i + 1, t, 0), 0, 100 - i * .1, 0, 0,
                                                    0, true, true, true);
                String[] result =
                    e.submit(w.begin(EventWriter.MOVEMENT, 1, ++ordinal, t, 0, i).movement(m, c, t).finish());
                if (has(result, "timer.budget.v1"))
                    timer++;
                if (has(result, "nofall.ground_claim.v1"))
                    nofall++;
            }
            check(timer > 0, "movement fields reach Timer through JNI");
            check(nofall > 0, "ground/context fields reach NoFall through JNI");
            long t = 20_000_000_000L;
            e.submit(w.begin(EventWriter.IMPULSE, 1, ++ordinal, t, 0, 800)
                         .impulse(new ImpulseEvent(new PacketInfo(800, 800, t, 0), 42, .6, .4, 0, false))
                         .finish());
            e.submit(w.begin(EventWriter.IMPULSE_ACK, 1, ++ordinal, t, 0, 800).impulseAck(42).finish());
            e.submit(w.begin(EventWriter.CORRECTION, 1, ++ordinal, t, 0, 800).finish());
            check(true, "impulse, acknowledgement and correction bodies accepted");
            ByteBuffer valid = w.begin(EventWriter.MOVEMENT, 1, ++ordinal, t, 0, 800)
                                   .movement(new MovementEvent(new PacketInfo(801, 801, t, 0), 0, 64, 0, 0, 0,
                                                               true, false, true),
                                             c, t)
                                   .finish();
            for (int i = 0; i < valid.limit(); i++) {
                ByteBuffer b = valid.duplicate();
                b.limit(i);
                check(rejects(e, b), "movement truncation " + i);
            }
            valid.put(112, (byte)2);
            check(rejects(e, valid), "invalid movement boolean rejected");
            valid.put(112, (byte)1);
            valid.put(4, (byte)2);
            check(rejects(e, valid), "schema 2 cannot label movement as old telemetry");
        }
        try (NativeBridge e = new NativeBridge("trace=true")) {
            ByteBuffer start = w.begin(EventWriter.START, 2, 1, 0, 0, 0).session("old", 47, 10808).finish();
            start.put(4, (byte)2);
            e.submit(start);
            CombatContext old = new CombatContext("world", "target", "ZOMBIE", "", 7, 0, 65.62, 0, 2, 64, -.3,
                                                  2.6, 65.8, .3, 10, true);
            ByteBuffer v2 = w.begin(EventWriter.COMBAT_CONTEXT, 2, 2, 1, 0, 0).combatContext(old, 1).finish();
            v2.put(4, (byte)2);
            v2.limit(v2.limit() - 19);
            e.submit(v2);
            check(true, "schema-2 combat body remains readable without v3 rotation suffix");
        }
        System.out.println(passed + " movement JNI checks passed");
    }
}
