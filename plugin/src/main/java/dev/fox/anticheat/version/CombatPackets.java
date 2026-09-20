/**
 * CombatPackets.java copies the Spigot 1.8.8 attack target ID without accessing a world.
 * NMS exposes entity lookup but not a target-ID getter, so only this class uses reflection.
 */

package dev.fox.anticheat.version;

import dev.fox.anticheat.event.AttackEvent;
import dev.fox.anticheat.packet.PacketInfo;
import java.lang.reflect.Field;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;

public final class CombatPackets{
    private final Field targetId;

    public CombatPackets(){
        try{
            targetId = PacketPlayInUseEntity.class.getDeclaredField("a");

            if(targetId.getType() != int.class)
                throw new IllegalStateException("Unexpected 1.8.8 attack packet layout");

            targetId.setAccessible(true);
        }catch(ReflectiveOperationException error){
            throw new IllegalStateException("Cannot access the 1.8.8 attack target ID", error);
        }
    }

    // Run on the network thread. Copy only primitive packet fields and existing metadata.
    public AttackEvent copyAttack(PacketPlayInUseEntity packet, PacketInfo info){
        if(packet.a() != PacketPlayInUseEntity.EnumEntityUseAction.ATTACK)
            return null;

        try{
            return new AttackEvent(targetId.getInt(packet), info);
        }catch(IllegalAccessException error){
            throw new IllegalStateException("Cannot copy attack target ID", error);
        }
    }
}
