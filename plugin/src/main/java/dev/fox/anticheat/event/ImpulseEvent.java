package dev.fox.anticheat.event;

import dev.fox.anticheat.packet.PacketInfo;

public final class ImpulseEvent {
    public final PacketInfo packet;
    public final long token;
    public final double x, y, z;
    public final boolean additive;
    public ImpulseEvent(PacketInfo packet, long token, double x, double y, double z, boolean additive) {
        this.packet = packet;
        this.token = token;
        this.x = x;
        this.y = y;
        this.z = z;
        this.additive = additive;
    }
}
