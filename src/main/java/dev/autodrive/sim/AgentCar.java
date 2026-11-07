package dev.autodrive.sim;

import dev.autodrive.ai.NeuralNetwork;

import java.util.Random;

import static dev.autodrive.sim.Config.*;

/**
 * The self-driving car. Each tick it: senses (ray casts + doppler), thinks (neural network),
 * acts (steer + throttle/brake), then checks for collisions and updates its score.
 */
public final class AgentCar extends Car {
    public static final int RAYS = SENSOR_ANGLES.length;
    public static final int INPUTS = RAYS + DOPPLER_RAYS.length + 5;
    /** Network shape: inputs -> 14 -> 9 -> 2 outputs (steer, throttle). */
    public static final int[] TOPOLOGY = {INPUTS, 14, 9, 2};

    public static final String[] INPUT_LABELS = buildInputLabels();
    public static String[] outputLabels(ControlMode mode) {
        return mode == ControlMode.PLANNER ? new String[]{"lane", "speed"} : new String[]{"steer", "throttle"};
    }

    public final int index;
    public final NeuralNetwork brain;

    /** 0 = nothing within range, 1 = touching. One value per ray, noise-free (for drawing). */
    public final double[] readings = new double[RAYS];
    /** Distance to the hit point per ray (for drawing). */
    public final double[] hitDistance = new double[RAYS];
    private final double[] hitSpeed = new double[RAYS];
    private final double[] inputs = new double[INPUTS];
    /** Strength (0..1) of a turn signal on the left/right of us pointing into our path. */
    public double blinkLeft, blinkRight;

    private static final double LANE_CMD_THRESHOLD = 0.4;
    private static final double BLINK_RANGE = 220;
    private static final double TTC_LIMIT = 1.2;

    private final Random noise;
    private final double noiseLevel;
    private final double startY;
    public final ControlMode mode;
    /** Planner mode: lane the car is heading for, and the speed it wants. */
    public int targetLane;
    public double targetSpeed;
    public double vx;
    public double heading;
    public double steer;
    public double throttle;

    public boolean alive = true;
    public boolean crashed;
    public boolean stalled;
    public boolean survived;

    public double timeAlive;
    public double deathTime;
    public double distance;
    public int overtakes;

    private double emaSpeed;
    private double jerk;
    private double laneOffsetIntegral;
    private double riskIntegral;

    AgentCar(int index, NeuralNetwork brain, ControlMode mode, double x, double y, Random noise, double noiseLevel) {
        this.index = index;
        this.brain = brain;
        this.mode = mode;
        this.x = x;
        this.targetLane = nearestLane();
        this.targetSpeed = AGENT_START_SPEED;
        this.y = y;
        this.startY = y;
        this.speed = AGENT_START_SPEED;
        this.emaSpeed = AGENT_START_SPEED;
        this.noise = noise;
        this.noiseLevel = noiseLevel;
    }

    void update(Traffic traffic, double dt, double worldTime) {
        sense(traffic);
        double[] out = brain.feed(buildInputs());
        double grip = Math.min(1, speed / 200.0);           // can't strafe while standing still
        double acc;
        double targetVx;
        if (mode == ControlMode.PLANNER) {
            planLane(out[0]);
            targetSpeed = (out[1] + 1) / 2 * AGENT_MAX_SPEED;
            acc = Math.max(-AGENT_BRAKE, Math.min(AGENT_ACCEL, (targetSpeed - speed) * 3.0));
            double desiredVx = Math.max(-AGENT_MAX_LATERAL, Math.min(AGENT_MAX_LATERAL, (laneCenter(targetLane) - x) * 4.0));
            targetVx = desiredVx * grip;
            double newSteer = desiredVx / AGENT_MAX_LATERAL;
            jerk += Math.abs(newSteer - steer);
            steer = newSteer;
            throttle = acc >= 0 ? acc / AGENT_ACCEL : acc / AGENT_BRAKE;
        } else {
            double newSteer = out[0];
            jerk += Math.abs(newSteer - steer);
            steer = newSteer;
            throttle = out[1];
            acc = throttle >= 0 ? throttle * AGENT_ACCEL : throttle * AGENT_BRAKE;
            targetVx = steer * AGENT_MAX_LATERAL * grip;
        }

        // --- vehicle dynamics -------------------------------------------
        speed = Math.max(0, Math.min(AGENT_MAX_SPEED, speed + acc * dt));
        vx += (targetVx - vx) * Math.min(1, AGENT_LATERAL_RESPONSE * dt);
        x += vx * dt;
        y += speed * dt;
        heading = Math.atan2(vx, Math.max(speed, 1));

        // --- bookkeeping ------------------------------------------------
        timeAlive += dt;
        distance = y - startY;
        overtakes = traffic.passedCount(y - L);
        emaSpeed += (speed - emaSpeed) * dt / STALL_TAU;
        double laneOffset = Math.abs(x - laneCenter(nearestLane())) / (LANE_WIDTH / 2);
        laneOffsetIntegral += Math.min(1, laneOffset) * dt;
        riskIntegral += collisionRisk() * dt;

        // --- termination ------------------------------------------------
        if (x - W / 2 < 0 || x + W / 2 > ROAD_WIDTH || hitsTraffic(traffic)) {
            die(worldTime);
            crashed = true;
        } else if (timeAlive > STALL_GRACE && emaSpeed < STALL_MIN_SPEED) {
            die(worldTime);
            stalled = true;
        }
    }

    /** 0..1: how close we are to rear-ending the car ahead (time-to-collision under 1.2 s). */
    public double collisionRisk() {
        int ahead = DOPPLER_RAYS[1];
        if (Double.isNaN(hitSpeed[ahead])) return 0;
        double gap = Math.max(0, hitDistance[ahead] - L / 2);
        double closing = speed - hitSpeed[ahead];
        if (closing <= 1) return 0;
        double ttc = gap / closing;
        return ttc >= TTC_LIMIT ? 0 : (TTC_LIMIT - ttc) / TTC_LIMIT;
    }

    /** Planner mode: |cmd| above the threshold requests (or aborts) a one-lane move. */
    private void planLane(double cmd) {
        int cur = nearestLane();
        if (cmd < -LANE_CMD_THRESHOLD) {
            if (targetLane > cur) targetLane = cur;                       // abort a right move
            else if (targetLane == cur && cur > 0) targetLane = cur - 1;
        } else if (cmd > LANE_CMD_THRESHOLD) {
            if (targetLane < cur) targetLane = cur;                       // abort a left move
            else if (targetLane == cur && cur < LANES - 1) targetLane = cur + 1;
        }
    }

    private void die(double worldTime) {
        alive = false;
        deathTime = worldTime;
    }

    public int nearestLane() {
        return (int) Math.max(0, Math.min(LANES - 1, Math.floor(x / LANE_WIDTH)));
    }

    private boolean hitsTraffic(Traffic t) {
        int from = t.lowerBound(y - L), to = t.lowerBound(y + L);
        for (int i = from; i < to; i++) {
            if (overlaps(t.get(i))) return true;
        }
        return false;
    }

    // ---- Sensors ----------------------------------------------------------

    private void sense(Traffic t) {
        int from = t.lowerBound(y - SENSOR_RANGE - L);
        int to = t.lowerBound(y + SENSOR_RANGE + L);
        for (int r = 0; r < RAYS; r++) {
            double a = SENSOR_ANGLES[r] + heading;
            double dx = Math.sin(a), dy = Math.cos(a);
            double best = SENSOR_RANGE;
            double hs = Double.NaN;
            // road edges behave like walls
            if (dx < -1e-6) best = Math.min(best, -x / dx);
            else if (dx > 1e-6) best = Math.min(best, (ROAD_WIDTH - x) / dx);
            for (int i = from; i < to; i++) {
                TrafficCar c = t.get(i);
                double d = rayBox(x, y, dx, dy, c.x - W / 2, c.y - L / 2, c.x + W / 2, c.y + L / 2);
                if (d < best) {
                    best = d;
                    hs = c.speed;
                }
            }
            hitDistance[r] = best;
            readings[r] = 1 - best / SENSOR_RANGE;
            hitSpeed[r] = hs;
        }
        // Camera-style turn-signal detection for cars beside / just ahead of us.
        blinkLeft = 0;
        blinkRight = 0;
        for (int i = t.lowerBound(y - 1.5 * L), end = t.lowerBound(y + BLINK_RANGE); i < end; i++) {
            TrafficCar c = t.get(i);
            int dir = c.lane != c.targetLane ? Integer.signum(c.targetLane - c.lane) : c.signal;
            if (dir == 0 || Math.abs(c.x - x) > LANE_WIDTH * 1.6 || Math.abs(c.x - x) < W * 0.5) continue;
            double strength = 1 - Math.max(0, c.y - y) / BLINK_RANGE;
            if (c.x < x && dir > 0) blinkLeft = Math.max(blinkLeft, strength);
            if (c.x > x && dir < 0) blinkRight = Math.max(blinkRight, strength);
        }
    }

    private double[] buildInputs() {
        int k = 0;
        for (int r = 0; r < RAYS; r++) {
            double v = readings[r];
            if (noiseLevel > 0) v = clamp(v + noise.nextGaussian() * noiseLevel, 0, 1);
            inputs[k++] = v;
        }
        for (int r : DOPPLER_RAYS) {
            inputs[k++] = Double.isNaN(hitSpeed[r]) ? 0 : clamp((speed - hitSpeed[r]) / 300.0, -1, 1);
        }
        inputs[k++] = speed / AGENT_MAX_SPEED;
        inputs[k++] = x / ROAD_WIDTH * 2 - 1;
        inputs[k++] = vx / AGENT_MAX_LATERAL;
        inputs[k++] = blinkLeft;
        inputs[k] = blinkRight;
        return inputs;
    }

    /** Slab-method ray vs axis-aligned box. Returns distance along the ray, or +inf on miss. */
    static double rayBox(double ox, double oy, double dx, double dy,
                         double minX, double minY, double maxX, double maxY) {
        double tNear = Double.NEGATIVE_INFINITY, tFar = Double.POSITIVE_INFINITY;
        if (Math.abs(dx) < 1e-9) {
            if (ox < minX || ox > maxX) return Double.POSITIVE_INFINITY;
        } else {
            double t1 = (minX - ox) / dx, t2 = (maxX - ox) / dx;
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
        }
        if (Math.abs(dy) < 1e-9) {
            if (oy < minY || oy > maxY) return Double.POSITIVE_INFINITY;
        } else {
            double t1 = (minY - oy) / dy, t2 = (maxY - oy) / dy;
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
        }
        if (tFar < 0 || tNear > tFar) return Double.POSITIVE_INFINITY;
        return Math.max(0, tNear);
    }

    // ---- Fitness ----------------------------------------------------------

    /**
     * Reward progress and overtakes; finishing the episode alive earns a bonus.
     * Small penalties for twitchy steering, straddling lane lines and tailgating
     * (low time-to-collision) make the driving smoother and safer. Crashing and stalling scale the score down.
     */
    public double fitness() {
        double f = distance / 100.0 + overtakes * 8.0 + (survived ? 10 : 0);
        f -= jerk * 0.004;
        f -= laneOffsetIntegral * 0.25;
        f -= riskIntegral * 3.0;
        if (crashed) f *= 0.75;
        if (stalled) f *= 0.5;
        return Math.max(0, f);
    }

    public double speedKmh() {
        return speed / PX_PER_METER * 3.6;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static String[] buildInputLabels() {
        String[] labels = new String[INPUTS];
        int k = 0;
        for (double d : SENSOR_ANGLES_DEG) labels[k++] = String.format("ray %+.0f°", d).replace("+0°", "0°");
        for (int r : DOPPLER_RAYS) labels[k++] = String.format("Δv %+.0f°", SENSOR_ANGLES_DEG[r]).replace("+0°", "0°");
        labels[k++] = "speed";
        labels[k++] = "lane pos";
        labels[k++] = "lat vel";
        labels[k++] = "blink L";
        labels[k] = "blink R";
        return labels;
    }
}
