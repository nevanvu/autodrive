package dev.autodrive.sim;

/** A non-AI car. Driven by the {@link Traffic} system (IDM car-following + occasional lane changes). */
public final class TrafficCar extends Car {
    public static final int PALETTE_SIZE = 10;

    /** Lane the car is in (or leaving, while changing lanes). */
    public int lane;
    /** Lane the car is moving into; equals {@link #lane} when not changing lanes. */
    public int targetLane;
    public final double desiredSpeed;
    public final int colorIndex;
    public boolean braking;
    /** -1 = left blinker, +1 = right blinker, 0 = off. */
    public int signal;

    double signalTimer;
    double cooldown;

    TrafficCar(int lane, double y, double speed, int colorIndex) {
        this.lane = lane;
        this.targetLane = lane;
        this.x = Config.laneCenter(lane);
        this.y = y;
        this.speed = speed;
        this.desiredSpeed = speed;
        this.colorIndex = colorIndex;
        this.cooldown = 2;
    }

    public boolean occupies(int l) {
        return lane == l || targetLane == l;
    }
}
