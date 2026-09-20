/**
 * CombatContext.java holds copied server state for an attack or watched target.
 * Geometry and timing decisions belong to C++, not this data object.
 */

package dev.fox.anticheat.event;

public final class CombatContext{
    public final String world;
    public final String targetUuid;
    public final String targetKind;
    public final String unavailableReason;
    public final int targetId;
    public final double eyeX;
    public final double eyeY;
    public final double eyeZ;
    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;
    public final int pingMillis;
    public final boolean available;

    public CombatContext(
        String world,
        String targetUuid,
        String targetKind,
        String unavailableReason,
        int targetId,
        double eyeX,
        double eyeY,
        double eyeZ,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int pingMillis,
        boolean available
    ){
        this.world = world;
        this.targetUuid = targetUuid;
        this.targetKind = targetKind;
        this.unavailableReason = unavailableReason;
        this.targetId = targetId;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.pingMillis = pingMillis;
        this.available = available;
    }

    public static CombatContext unavailable(String world, int targetId, String targetUuid, String reason){
        return new CombatContext(
            world,
            targetUuid,
            "UNKNOWN",
            reason,
            targetId,
            0, 0, 0,
            0, 0, 0,
            0, 0, 0,
            0,
            false
        );
    }
}
