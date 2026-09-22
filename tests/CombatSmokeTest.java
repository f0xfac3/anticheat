/**
 * CombatSmokeTest.java exercises real combat serialization, JNI, and C++ checks.
 * It does not require a running Minecraft server.
 */

package dev.fox.anticheat.bridge;

import dev.fox.anticheat.event.AttackEvent;
import dev.fox.anticheat.event.CombatContext;
import dev.fox.anticheat.packet.PacketInfo;
import java.io.File;
import java.nio.ByteBuffer;

public final class CombatSmokeTest{
    private static int passed;

    private static void check(boolean value, String name){
        if(!value)
            throw new AssertionError(name);

        ++passed;
        System.out.println("PASS " + name);
    }

    private static boolean has(String[] records, String value){
        if(records == null)
            return false;

        for(String record : records){
            if(record.contains(value))
                return true;
        }
        return false;
    }

    private static CombatContext context(double distance){
        return new CombatContext(
            "world", "target", "ZOMBIE", "", 7,
            0, 65.62, 0,
            distance + 0.1, 64, -0.3,
            distance + 0.7, 65.8, 0.3,
            10, true
        );
    }

    private static void start(NativeBridge engine, EventWriter writer){
        engine.submit(writer.begin(EventWriter.START, 1, 1, 0, 0, 0)
            .session("player", 47, 10808).finish());
    }

    private static boolean rejects(NativeBridge engine, ByteBuffer buffer){
        try{
            engine.submit(buffer);
            return false;
        }catch(IllegalStateException expected){
            return true;
        }
    }

    public static void main(String[] args) throws Exception{
        NativeBridge.load(new File(args[0]));
        EventWriter writer = new EventWriter();

        try(NativeBridge engine = new NativeBridge("fastbreak.enabled=false\nautoclicker.enabled=false\nreach.alert_after=1")){
            start(engine, writer);
            long ordinal = 1;
            long time = 1_000_000_000L;

            for(int i = 0; i <= 16; ++i){
                time = 1_000_000_000L + i * 50_000_000L;
                engine.submit(writer.begin(EventWriter.COMBAT_CONTEXT, 1, ++ordinal, time, 0, i)
                    .combatContext(context(3.4), time).finish());
            }

            time += 80_000_000L;
            AttackEvent attack = new AttackEvent(7, new PacketInfo(10, 10, time, 0));
            String[] records = engine.submit(writer.begin(EventWriter.ATTACK, 1, ++ordinal, time, 0, 20)
                .attack(attack, context(3.4), time).finish());
            check(has(records, "\"level\":\"suspicious\""), "Reach finding crosses real JNI");
            check(has(records, "3.369"), "combat box and double fields decode correctly");
            check(has(records, "NOT_RECONSTRUCTED"), "reach limitation remains in returned evidence");

            engine.submit(writer.begin(EventWriter.TELEPORT, 1, ++ordinal, time, 0, 20)
                .teleport("ENDER_PEARL", "world", "world", 0, 64, 0, 4, 64, 0).finish());
            time += 80_000_000L;
            attack = new AttackEvent(7, new PacketInfo(11, 11, time, 0));
            records = engine.submit(writer.begin(EventWriter.ATTACK, 1, ++ordinal, time, 0, 21)
                .attack(attack, context(3.4), time).finish());
            check(has(records, "history_warming"), "teleport clears Reach history through JNI");

            records = engine.submit(writer.begin(EventWriter.SWING, 1, ++ordinal, time, 0, 21)
                .swing(12, 12).finish());
            check(records == null, "arm swings are not counted as attacks");

            ByteBuffer valid = writer.begin(EventWriter.ATTACK, 1, ++ordinal, time, 0, 22)
                .attack(attack, context(3.4), time).finish();
            boolean truncated = true;
            for(int i = 0; i < valid.limit(); ++i){
                ByteBuffer cut = valid.duplicate();
                cut.limit(i);
                truncated &= rejects(engine, cut);
            }
            check(truncated, "every attack-payload truncation rejected safely");

            valid.put(valid.limit() - 1, (byte) 2);
            check(rejects(engine, valid), "invalid combat target-player flag rejected");
            valid.put(valid.limit() - 1, (byte) 1);
            valid.put(4, (byte) 1);
            check(rejects(engine, valid), "v1 cannot silently interpret v2 combat fields");
        }

        try(NativeBridge engine = new NativeBridge("fastbreak.enabled=false\nreach.enabled=false")){
            start(engine, writer);
            long time = 1_000_000_000L;
            int alerts = 0;
            for(int i = 0; i <= 120; ++i){
                time += 75_500_000L;
                AttackEvent attack = new AttackEvent(7, new PacketInfo(i + 1, i + 1, time, 0));
                String[] records = engine.submit(writer.begin(EventWriter.ATTACK, 1, i + 2, time, 0, i)
                    .attack(attack, context(2), time).finish());
                if(has(records, "\"level\":\"suspicious\"")){
                    ++alerts;
                    check(has(records, "attack_requests"), "cadence evidence identifies its observed source");
                    check(has(records, "NOT_CALIBRATED"), "no fabricated probability in cadence output");
                }
            }
            check(alerts == 1, "Autoclicker three-window finding crosses real JNI");
        }

        try(NativeBridge engine = new NativeBridge("fastbreak.enabled=false\nreach.enabled=false\nautoclicker.enabled=false")){
            ByteBuffer v1 = writer.begin(EventWriter.START, 1, 1, 0, 0, 0)
                .session("old-recording", 47, 10808).finish();
            v1.put(4, (byte) 1);
            check(has(engine.submit(v1), "session_open"), "old schema-1 recordings remain readable");
        }

        System.out.println(passed + " combat JNI checks passed");
    }
}
