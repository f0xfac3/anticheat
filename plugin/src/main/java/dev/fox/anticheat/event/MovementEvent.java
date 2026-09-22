package dev.fox.anticheat.event;

import dev.fox.anticheat.packet.PacketInfo;

/** Owned primitives copied from every 1.8 Flying variant on the channel thread. */
public final class MovementEvent {
    public final PacketInfo packet;
    public final double x, y, z, yaw, pitch;
    public final boolean position, look, ground;
    public MovementEvent(PacketInfo packet, double x, double y, double z, double yaw, double pitch,
                         boolean position, boolean look, boolean ground) {
        this.packet = packet;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.position = position;
        this.look = look;
        this.ground = ground;
    }
}
