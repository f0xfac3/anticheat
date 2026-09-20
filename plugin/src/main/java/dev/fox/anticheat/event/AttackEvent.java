/**
 * AttackEvent.java contains copied ATTACK request data, not a confirmed hit.
 */

package dev.fox.anticheat.event;

import dev.fox.anticheat.packet.PacketInfo;

public final class AttackEvent{
    public final int targetId;
    public final PacketInfo packet;

    public AttackEvent(int targetId, PacketInfo packet){
        this.targetId = targetId;
        this.packet = packet;
    }
}
