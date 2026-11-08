package dev.autodrive.sim;

import dev.autodrive.ai.NeuralNetwork;

import java.util.Random;

import static dev.autodrive.sim.Config.*;

/**
 * The self-driving car. Each tick it: senses (ray casts + doppler + lane radar), thinks (neural network),
 * acts (steer + throttle/brake), then checks for collisions and updates its score.
 */
public final class AgentCar extends Car {
    public static final int RAYS = SENSOR_ANGLES.length;
    /**
     * Lane radar: gap to the car ahead in our lane and how much it holds us below the limit; how much
     * faster the left / right lane would be; and the gap and closing speed of the car behind in each.
     */
    public static final int RADAR_INPUTS = 8;
    public static final int INPUTS = RAYS + DOPPLER_RAYS.length + 5 + RADAR_INPUTS;
    /** Network shape: inputs -> 14 -> 9 -> 2 outputs (lane + speed, or steer + throttle). */
    public static final int[] TOPOLOGY = {INPUTS, 14, 9, 2};

    public static final String[] INPUT_LABELS = buildInputLabels();
    public static String[] outputLabels(ControlMode mode) {
        return mode == ControlMode.PLANNER ? new String[]{"lane", "speed"} : new String[]{"steer", "throttle"};
    }

    /** Side index for the lane radar arrays. */
    public static final int LEFT = 0, RIGHT = 1;

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

    /**
     * Lane radar (blind-spot + forward radar on each side). Bumper-to-bumper gap to the nearest car
     * ahead / behind in the left and right lane, and that car's speed (NaN when none in range).
     * A missing lane (road edge) reads as a gap of 0.
     */
    public final double[] sideGapAhead = new double[2], sideGapBehind = new double[2];
    public final double[] sideSpeedAhead = new double[2], sideSpeedBehind = new double[2];
    /** Gap to the car ahead in our own lane (+inf when none in range) and its speed. */
    public double leadGap, leadSpeed;

    private static final double LANE_CMD_THRESHOLD = 0.4;
    private static final double BLINK_RANGE = 220;
    private static final double TTC_LIMIT = 1.2;
    /** Lane radar reach (px). Longer than the rays so it sees past the gap adaptive cruise keeps. */
    static final double RADAR_RANGE = 500;
    /** Look-ahead (s) used to judge how fast a lane is. */
    static final double LANE_HORIZON = 5;

    // Lane-change safety gate (planner mode): minimum gap = GATE_MIN_GAP + GATE_TIME_GAP * speed,
    // and the time-to-collision with the car in front / behind in the target lane must exceed GATE_TTC.
    static final double GATE_MIN_GAP = 20;
    static final double GATE_TIME_GAP = 0.3;
    static final double GATE_TTC = 1.5;
    /** Once a move has started we only abort if the gap gets much worse than what we accepted. */
    static final double GATE_ABORT_FACTOR = 0.6;
    /** Adaptive cruise control: standstill gap (px) and time gap (s) it keeps to the car ahead. */
    static final double ACC_MIN_GAP = 25;
    static final double ACC_HEADWAY = 1.2;
    /** Planner mode: seconds after a lane change before the next one can start (stops weaving). */
    static final double LANE_CHANGE_COOLDOWN = 3.0;

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
    /** Planner mode: the lane the network wants but the safety gate is holding back (-1 = none). */
    public int heldLane = -1;
    /** Number of lane changes the safety gate held back or aborted this episode. */
    public int gateBlocks;
    private boolean wasHolding;
    /** Completed lane changes (times the car crossed a lane line). */
    public int laneChanges;
    private int lastLane;
    private double laneCooldown;

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
    private double headwayIntegral;
    private double hardBrakeIntegral;
    private double overSpeedIntegral;
    /** Seconds spent stuck behind a slow car while a better lane was safely open. */
    public double missedOvertakeTime;

    AgentCar(int index, NeuralNetwork brain, ControlMode mode, double x, double y, Random noise, double noiseLevel) {
        this.index = index;
        this.brain = brain;
        this.mode = mode;
        this.x = x;
        this.targetLane = nearestLane();
        this.lastLane = targetLane;
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
            targetSpeed = (out[1] + 1) / 2 * SPEED_LIMIT;    // the planner can't ask for more than the limit
            acc = Math.min((targetSpeed - speed) * 3.0, accFollow());
            acc = Math.max(-AGENT_BRAKE, Math.min(AGENT_ACCEL, acc));
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
        double before = speed;
        speed = Math.max(0, Math.min(AGENT_MAX_SPEED, speed + acc * dt));
        double decel = (before - speed) / dt;
        vx += (targetVx - vx) * Math.min(1, AGENT_LATERAL_RESPONSE * dt);
        x += vx * dt;
        y += speed * dt;
        heading = Math.atan2(vx, Math.max(speed, 1));

        // --- bookkeeping ------------------------------------------------
        timeAlive += dt;
        distance = y - startY;
        overtakes = traffic.passedCount(y - L);
        emaSpeed += (speed - emaSpeed) * dt / STALL_TAU;
        int lane = nearestLane();
        if (lane != lastLane) {
            laneChanges++;
            lastLane = lane;
            laneCooldown = LANE_CHANGE_COOLDOWN;
        }
        laneCooldown = Math.max(0, laneCooldown - dt);
        double laneOffset = Math.abs(x - laneCenter(lane)) / (LANE_WIDTH / 2);
        laneOffsetIntegral += Math.min(1, laneOffset) * dt;
        riskIntegral += collisionRisk() * dt;
        double headway = headway();
        if (headway < SAFE_HEADWAY) headwayIntegral += (SAFE_HEADWAY - headway) / SAFE_HEADWAY * dt;
        if (decel > HARD_BRAKE) hardBrakeIntegral += (decel - HARD_BRAKE) / (AGENT_BRAKE - HARD_BRAKE) * dt;
        if (speed > SPEED_LIMIT) overSpeedIntegral += (speed - SPEED_LIMIT) / SPEED_LIMIT * dt;
        if (missedOvertake()) missedOvertakeTime += dt;

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

    /**
     * Adaptive cruise control (planner mode): the acceleration that keeps a safe gap to the car
     * ahead, using the interaction term of the Intelligent Driver Model. While changing lanes it
     * also respects the car ahead in the target lane. Returns +inf when nothing is ahead.
     */
    private double accFollow() {
        double a = accFollow(leadGap, leadSpeed);
        int cur = nearestLane();
        if (targetLane != cur) {
            int side = targetLane < cur ? LEFT : RIGHT;
            a = Math.min(a, accFollow(sideGapAhead[side], sideSpeedAhead[side]));
        }
        return a;
    }

    private double accFollow(double gap, double carSpeed) {
        if (Double.isNaN(carSpeed)) return Double.POSITIVE_INFINITY;
        double dv = speed - carSpeed;
        double desired = ACC_MIN_GAP + speed * ACC_HEADWAY + speed * dv / (2 * Math.sqrt(AGENT_ACCEL * HARD_BRAKE));
        double r = Math.max(0, desired) / Math.max(1, gap);
        return AGENT_ACCEL * (1 - r * r);
    }

    /**
     * Coaching signal for the score: we're held well under the limit by a slow car ahead, we're
     * not changing lanes, and a neighbouring lane is safe to enter and clearly better (more room,
     * or a faster car in front). The network never sees this; it only shapes the fitness.
     */
    public boolean missedOvertake() {
        if (Double.isNaN(leadSpeed) || leadSpeed > SPEED_LIMIT - 40 || speed > SPEED_LIMIT - 40) return false;
        if (leadGap > 2.0 * Math.max(speed, 1) || targetLane != nearestLane() || laneCooldown > 0) return false;
        for (int s = 0; s < 2; s++) {
            if (!sideSafe(s, 1.0)) continue;
            boolean moreRoom = sideGapAhead[s] > leadGap + 150;
            boolean faster = !Double.isNaN(sideSpeedAhead[s]) && sideSpeedAhead[s] > leadSpeed + 40;
            if (moreRoom || faster) return true;
        }
        return false;
    }

    /** Time gap (s) to the car ahead in our lane; +inf when the lane ahead is clear. */
    public double headway() {
        if (Double.isInfinite(leadGap)) return Double.POSITIVE_INFINITY;
        return leadGap / Math.max(speed, 1);
    }

    /**
     * Planner mode: |cmd| above the threshold requests (or aborts) a one-lane move.
     * A safety gate checks the lane radar first, like the lane-change assist in a real car:
     * it holds a move until the gap is big enough and aborts one whose gap closes up.
     */
    private void planLane(double cmd) {
        int cur = nearestLane();
        wasHolding = heldLane >= 0;
        heldLane = -1;
        if (cmd < -LANE_CMD_THRESHOLD) {
            if (targetLane > cur) targetLane = cur;                       // abort a right move
            else if (targetLane == cur && cur > 0) requestMove(LEFT, cur - 1);
        } else if (cmd > LANE_CMD_THRESHOLD) {
            if (targetLane < cur) targetLane = cur;                       // abort a left move
            else if (targetLane == cur && cur < LANES - 1) requestMove(RIGHT, cur + 1);
        }
        // A move in progress (we haven't crossed the lane line yet): abort if the gap closed up.
        if (targetLane != cur) {
            int side = targetLane < cur ? LEFT : RIGHT;
            if (!sideSafe(side, GATE_ABORT_FACTOR)) {
                targetLane = cur;
                gateBlocks++;
            }
        }
    }

    private void requestMove(int side, int lane) {
        if (laneCooldown > 0) return;                    // settle in before the next change
        if (sideSafe(side, 1.0)) {
            targetLane = lane;
        } else {
            heldLane = lane;
            if (!wasHolding) gateBlocks++;
        }
    }

    private boolean sideSafe(int side, double factor) {
        return laneGapSafe(speed, sideGapAhead[side], sideSpeedAhead[side],
                sideGapBehind[side], sideSpeedBehind[side], factor);
    }

    /**
     * Is there room to merge? Needs a minimum gap that grows with speed to the car ahead and behind
     * in the target lane, and enough time-to-collision if either of them is closing in.
     * {@code factor} below 1 relaxes the check (used to decide whether to abort a started move).
     */
    static boolean laneGapSafe(double speed, double gapAhead, double speedAhead,
                               double gapBehind, double speedBehind, double factor) {
        if (gapAhead < (GATE_MIN_GAP + GATE_TIME_GAP * speed) * factor) return false;
        if (!Double.isNaN(speedAhead)) {
            double closing = speed - speedAhead;
            if (closing > 0 && gapAhead / closing < GATE_TTC * factor) return false;
        }
        if (!Double.isNaN(speedBehind)) {
            if (gapBehind < (GATE_MIN_GAP + GATE_TIME_GAP * speedBehind) * factor) return false;
            double closing = speedBehind - speed;
            if (closing > 0 && gapBehind / closing < GATE_TTC * factor) return false;
        } else if (gapBehind < GATE_MIN_GAP * factor) {
            return false;                                                  // road edge
        }
        return true;
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
        senseLanes(t);
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

    /**
     * Lane radar: nearest car ahead and behind in each neighbouring lane, plus the car ahead in our
     * own lane. Unlike the rays it has no blind spots. A car that is merging counts for both lanes.
     */
    private void senseLanes(Traffic t) {
        int cur = nearestLane();
        for (int s = 0; s < 2; s++) {
            boolean exists = s == LEFT ? cur > 0 : cur < LANES - 1;
            sideGapAhead[s] = exists ? Double.POSITIVE_INFINITY : 0;
            sideGapBehind[s] = exists ? Double.POSITIVE_INFINITY : 0;
            sideSpeedAhead[s] = Double.NaN;
            sideSpeedBehind[s] = Double.NaN;
        }
        leadGap = Double.POSITIVE_INFINITY;
        leadSpeed = Double.NaN;
        for (int i = t.lowerBound(y - RADAR_RANGE - L), end = t.lowerBound(y + RADAR_RANGE + L); i < end; i++) {
            TrafficCar c = t.get(i);
            double dy = c.y - y;
            double gap = Math.max(0, Math.abs(dy) - L);
            if (gap > RADAR_RANGE) continue;
            if (dy > 0 && c.occupies(cur) && gap < leadGap) {
                leadGap = gap;
                leadSpeed = c.speed;
            }
            for (int s = 0; s < 2; s++) {
                int lane = s == LEFT ? cur - 1 : cur + 1;
                if (lane < 0 || lane >= LANES || !c.occupies(lane)) continue;
                if (dy >= 0) {
                    if (gap < sideGapAhead[s]) {
                        sideGapAhead[s] = gap;
                        sideSpeedAhead[s] = c.speed;
                    }
                } else if (gap < sideGapBehind[s]) {
                    sideGapBehind[s] = gap;
                    sideSpeedBehind[s] = c.speed;
                }
            }
        }
    }

    private double[] buildInputs() {
        int k = 0;
        for (int r = 0; r < RAYS; r++) {
            inputs[k++] = noisy(readings[r]);
        }
        for (int r : DOPPLER_RAYS) {
            inputs[k++] = Double.isNaN(hitSpeed[r]) ? 0 : clamp((speed - hitSpeed[r]) / 300.0, -1, 1);
        }
        inputs[k++] = speed / SPEED_LIMIT;
        inputs[k++] = x / ROAD_WIDTH * 2 - 1;
        inputs[k++] = vx / AGENT_MAX_LATERAL;
        inputs[k++] = blinkLeft;
        inputs[k++] = blinkRight;
        // Lane radar. Gap readings use the ray convention: 0 = clear, 1 = touching.
        inputs[k++] = gapReading(leadGap);
        inputs[k++] = slowdown(leadSpeed);
        inputs[k++] = noisySigned(laneAdvantage(LEFT));
        inputs[k++] = noisySigned(laneAdvantage(RIGHT));
        for (int s = 0; s < 2; s++) {
            inputs[k++] = gapReading(sideGapBehind[s]);
            inputs[k++] = Double.isNaN(sideSpeedBehind[s]) ? 0 : clamp((sideSpeedBehind[s] - speed) / 300.0, -1, 1);
        }
        return inputs;
    }

    private double gapReading(double gap) {
        return noisy(1 - Math.min(gap, RADAR_RANGE) / RADAR_RANGE);
    }

    /** How far below the speed limit a car ahead would hold us (0 = no car, or it's at the limit). */
    private static double slowdown(double carSpeed) {
        return Double.isNaN(carSpeed) ? 0 : clamp((SPEED_LIMIT - carSpeed) / SPEED_LIMIT, 0, 1);
    }

    /**
     * Roughly how fast a lane lets us drive over the next few seconds: the speed limit if it's clear,
     * the speed of the car ahead if we'd catch it right away, and in between if it's some way off.
     */
    static double laneSpeed(double gap, double carSpeed) {
        if (Double.isNaN(carSpeed)) return SPEED_LIMIT;
        double slower = Math.max(1, SPEED_LIMIT - carSpeed);
        return carSpeed + (SPEED_LIMIT - carSpeed) * clamp(gap / slower / LANE_HORIZON, 0, 1);
    }

    /**
     * How much faster the lane on this side would let us drive than our own lane, scaled to -1..1
     * (+1 = a quarter of the speed limit or more). -1 when there is no lane on that side.
     */
    public double laneAdvantage(int side) {
        int cur = nearestLane();
        if (side == LEFT ? cur == 0 : cur == LANES - 1) return -1;
        double gain = laneSpeed(sideGapAhead[side], sideSpeedAhead[side]) - laneSpeed(leadGap, leadSpeed);
        return clamp(gain / (0.25 * SPEED_LIMIT), -1, 1);
    }

    private double noisy(double v) {
        return noiseLevel > 0 ? clamp(v + noise.nextGaussian() * noiseLevel, 0, 1) : v;
    }

    private double noisySigned(double v) {
        return noiseLevel > 0 ? clamp(v + noise.nextGaussian() * noiseLevel, -1, 1) : v;
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
     * Reward progress (which is capped by the speed limit), with a small bonus per overtake and for
     * finishing the episode alive. Penalties for twitchy steering, straddling lane lines,
     * closing in too fast (low time-to-collision), following closer than {@link Config#SAFE_HEADWAY},
     * harsh braking and speeding. A crash wipes out most of the score; stalling halves it.
     */
    public double fitness() {
        double f = distance / 100.0 + overtakes * 2.0 + (survived ? 10 : 0);
        f -= laneChanges * 1.0;
        f -= jerk * 0.02;
        f -= laneOffsetIntegral * 0.25;
        f -= riskIntegral * 3.0;
        f -= headwayIntegral * 2.0;
        f -= hardBrakeIntegral * 2.0;
        f -= overSpeedIntegral * 20.0;
        f -= missedOvertakeTime * 1.0;
        if (crashed) f *= 0.3;
        if (stalled) f *= 0.5;
        return Math.max(0, f);
    }

    public double speedKmh() {
        return pxToKmh(speed);
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
        labels[k++] = "blink R";
        labels[k++] = "gap ahead";
        labels[k++] = "slow car";
        labels[k++] = "L lane gain";
        labels[k++] = "R lane gain";
        for (String side : new String[]{"L", "R"}) {
            labels[k++] = side + " gap behind";
            labels[k++] = side + " Δv behind";
        }
        return labels;
    }
}
