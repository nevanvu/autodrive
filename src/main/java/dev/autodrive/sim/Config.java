package dev.autodrive.sim;

/**
 * All tunable simulation constants in one place.
 * <p>
 * World units are "pixels" at zoom 1. The x axis runs across the road (0 = left edge),
 * the y axis runs forward along the highway (bigger y = further ahead).
 */
public final class Config {
    private Config() {}

    // ---- Road -------------------------------------------------------------
    public static final int LANES = 3;
    public static final double LANE_WIDTH = 64;
    public static final double ROAD_WIDTH = LANES * LANE_WIDTH;

    // ---- Cars -------------------------------------------------------------
    public static final double CAR_WIDTH = 30;
    public static final double CAR_LENGTH = 54;
    /** Only used for the km/h read-out (a 54px car is about 5.4 m long). */
    public static final double PX_PER_METER = 10;

    // ---- Physics ----------------------------------------------------------
    public static final double DT = 1.0 / 60.0;

    public static final double AGENT_START_SPEED = 220;
    public static final double AGENT_MAX_SPEED = 520;
    public static final double AGENT_ACCEL = 240;
    public static final double AGENT_BRAKE = 560;
    public static final double AGENT_MAX_LATERAL = 160;
    /** How quickly lateral velocity follows the steering command (1/s). */
    public static final double AGENT_LATERAL_RESPONSE = 6.0;

    /** Traffic is a mix of normal cars cruising near the limit and slow vehicles worth overtaking. */
    public static final double TRAFFIC_MIN_SPEED = 170;
    public static final double TRAFFIC_MAX_SPEED = 330;
    public static final double SLOW_VEHICLE_FRACTION = 0.25;
    public static final double SLOW_VEHICLE_MAX_SPEED = 220;
    public static final double CAR_MIN_SPEED = 260;

    // ---- Rules of the road --------------------------------------------------
    /** Posted speed limit. The planner can't request more; direct control is penalized above it. */
    public static final double SPEED_LIMIT_KMH = 120;
    public static final double SPEED_LIMIT = kmhToPx(SPEED_LIMIT_KMH);
    /**
     * Following distance = FOLLOW_MIN_GAP + FOLLOW_TIME * speed (bumper to bumper). Adaptive cruise
     * control settles there; at 100 km/h that's about 3.6 car lengths.
     */
    public static final double FOLLOW_MIN_GAP = CAR_LENGTH;
    public static final double FOLLOW_TIME = 0.5;
    /** Following closer than this fraction of the following distance makes the score drop. */
    public static final double TOO_CLOSE_FRACTION = 0.75;
    /** Braking harder than this (px/s²) counts as harsh braking. */
    public static final double HARD_BRAKE = 280;

    // ---- Sensors ----------------------------------------------------------
    /** Ray directions relative to the car's heading, in degrees (negative = left). */
    public static final double[] SENSOR_ANGLES_DEG = {-150, -90, -60, -35, -15, 0, 15, 35, 60, 90, 150};
    public static final double[] SENSOR_ANGLES = toRadians(SENSOR_ANGLES_DEG);
    /** Rays that also report closing speed of what they hit ("radar doppler"). */
    public static final int[] DOPPLER_RAYS = {4, 5, 6};
    public static final double SENSOR_RANGE = 340;

    // ---- Episodes ---------------------------------------------------------
    public static final double EPISODE_MAX_TIME = 60;
    /** Agents whose smoothed speed falls below this are removed (stops "park forever" strategies). */
    public static final double STALL_MIN_SPEED = 140;
    public static final double STALL_TAU = 3.0;
    public static final double STALL_GRACE = 4.0;

    public static double laneCenter(int lane) {
        return (lane + 0.5) * LANE_WIDTH;
    }

    /** Gap (px) to keep to the car ahead when driving at {@code speed} (px/s). */
    public static double followGap(double speed) {
        return FOLLOW_MIN_GAP + FOLLOW_TIME * speed;
    }

    public static double kmhToPx(double kmh) {
        return kmh / 3.6 * PX_PER_METER;
    }

    public static double pxToKmh(double px) {
        return px / PX_PER_METER * 3.6;
    }

    private static double[] toRadians(double[] deg) {
        double[] r = new double[deg.length];
        for (int i = 0; i < deg.length; i++) r[i] = Math.toRadians(deg[i]);
        return r;
    }
}
