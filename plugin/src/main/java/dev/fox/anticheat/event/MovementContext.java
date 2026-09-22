package dev.fox.anticheat.event;

/** Owned geometry/state measurements. C++ selects and evaluates the detection model. */
public final class MovementContext {
    public String world = "", reason = "";
    public boolean available, sourceSupported, destinationSupported, clearPath, flatGround;
    public boolean usingItem, sprinting;
    public double movementSpeed, friction, jumpVelocity;
}
