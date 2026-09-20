/**
 * EventWriter.java serializes normalized observations for the C++ decoder in wire.cpp.
 * One server-thread writer reuses this buffer; submit it before writing another event.
 */

package dev.fox.anticheat.bridge;

import dev.fox.anticheat.event.DigEvent;
import dev.fox.anticheat.event.AttackEvent;
import dev.fox.anticheat.event.CombatContext;
import dev.fox.anticheat.event.MiningContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class EventWriter{
    public static final int START = 1;
    public static final int END = 2;
    public static final int RESET = 3;
    public static final int TICK = 4;
    public static final int DIG = 5;
    public static final int CONTEXT = 6;

    public static final int ATTACK = 7;
    public static final int COMBAT_CONTEXT = 8;
    public static final int SWING = 9;
    public static final int TELEPORT = 10;

    private static final int MAGIC = 0x43415846;
    private static final int VERSION = 2;

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192)
        .order(ByteOrder.LITTLE_ENDIAN);

    // Start a serialized observation with the schema and shared EventHeader fields.
    public EventWriter begin(
        int type,
        long session,
        long ordinal,
        long observedNanos,
        long epochMillis,
        long serverTick
    ){
        buffer.clear();
        buffer.putInt(MAGIC);
        buffer.putShort((short) VERSION);
        buffer.putShort((short) type);
        buffer.putLong(session);
        buffer.putLong(ordinal);
        buffer.putLong(observedNanos);
        buffer.putLong(epochMillis);
        buffer.putLong(serverTick);
        return this;
    }

    // Write the SessionStart payload used to create a native player session.
    public EventWriter session(String uuid, int protocol, int model){
        text(uuid);
        buffer.putInt(protocol);
        buffer.putInt(model);
        return this;
    }

    // Schema v2 stores bounded ASCII identifiers, not arbitrary Unicode text.
    public EventWriter text(String value){
        if(value == null || value.length() > 1024)
            throw new IllegalArgumentException("Invalid identifier size");

        buffer.putShort((short) value.length());

        for(int i = 0; i < value.length(); ++i){
            char character = value.charAt(i);

            if(character < 32 || character > 126)
                throw new IllegalArgumentException("Bridge v2 identifiers must be ASCII");

            buffer.put((byte) character);
        }

        return this;
    }

    // Write a block position and its sampled MiningContext.
    public EventWriter context(int x, int y, int z, MiningContext context){
        buffer.putInt(x);
        buffer.putInt(y);
        buffer.putInt(z);
        snapshot(context);
        return this;
    }

    // Write the digging request together with the associated snapshot timestamp.
    public EventWriter dig(DigEvent event, MiningContext context, long sampledNanos){
        buffer.putLong(event.packetSequence);
        buffer.putLong(event.readBatch);
        buffer.putLong(sampledNanos);
        buffer.put((byte) event.action.ordinal());
        buffer.put((byte) event.face);

        return context(
            event.x,
            event.y,
            event.z,
            context
        );
    }

    private void snapshot(MiningContext context){
        text(context.world);
        text(context.stateKey);
        text(context.block);
        text(context.tool);
        text(context.unavailableReason);
        buffer.putDouble(context.damagePerTick);
        buffer.put((byte) (context.available ? 1 : 0));
    }

    public EventWriter attack(AttackEvent event, CombatContext context, long sampledNanos){
        buffer.putLong(event.packet.sequence);
        buffer.putLong(event.packet.readBatch);
        buffer.putLong(sampledNanos);
        combatSnapshot(context);
        return this;
    }

    public EventWriter combatContext(CombatContext context, long sampledNanos){
        buffer.putLong(sampledNanos);
        combatSnapshot(context);
        return this;
    }

    private void combatSnapshot(CombatContext context){
        text(context.world);
        text(context.targetUuid);
        text(context.targetKind);
        text(context.unavailableReason);
        buffer.putInt(context.targetId);
        vector(context.eyeX, context.eyeY, context.eyeZ);
        vector(context.minX, context.minY, context.minZ);
        vector(context.maxX, context.maxY, context.maxZ);
        buffer.putInt(context.pingMillis);
        buffer.put((byte) (context.available ? 1 : 0));
    }

    public EventWriter swing(long packetSequence, long readBatch){
        buffer.putLong(packetSequence);
        buffer.putLong(readBatch);
        return this;
    }

    // The source is a server-recognized teleport, not a client movement claim.
    public EventWriter teleport(
        String cause,
        String fromWorld,
        String toWorld,
        double fromX,
        double fromY,
        double fromZ,
        double toX,
        double toY,
        double toZ
    ){
        text(cause);
        text(fromWorld);
        text(toWorld);
        vector(fromX, fromY, fromZ);
        vector(toX, toY, toZ);
        return this;
    }

    private void vector(double x, double y, double z){
        buffer.putDouble(x);
        buffer.putDouble(y);
        buffer.putDouble(z);
    }

    // Prepare the written bytes for a synchronous JNI submission.
    public ByteBuffer finish(){
        buffer.flip();
        return buffer;
    }
}
