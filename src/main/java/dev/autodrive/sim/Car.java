package dev.autodrive.sim;

/** Common state for every vehicle. (x, y) is the centre of the car. */
public abstract class Car {
    public static final double W = Config.CAR_WIDTH;
    public static final double L = Config.CAR_LENGTH;

    public double x;
    public double y;
    public double speed;

    /** Axis-aligned overlap test with a 2px forgiveness margin. */
    public boolean overlaps(Car o) {
        return Math.abs(x - o.x) < W - 2 && Math.abs(y - o.y) < L - 2;
    }
}
