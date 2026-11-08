package dev.autodrive.sim;

import java.util.Arrays;
import java.util.Random;

import static dev.autodrive.sim.Car.L;
import static dev.autodrive.sim.Config.LANES;

/**
 * Owns all traffic cars, keeps them sorted by y (for fast range queries) and drives them.
 * <p>
 * Each AI car gets its own Traffic built from the same seed, and that traffic reacts to it
 * (brakes for it, won't merge into it). Every genome starts from the exact same scenario, so
 * fitness comparisons are fair, and a given seed always reproduces the same episode.
 */
public final class Traffic {
    // Intelligent Driver Model parameters
    private static final double IDM_ACCEL = 110;
    private static final double IDM_DECEL = 260;
    private static final double IDM_MIN_GAP = 22;
    private static final double IDM_HEADWAY = 0.9;
    private static final double MAX_BRAKE = 700;

    private static final double LANE_CHANGE_RATE = 0.10;   // attempts per second per car
    private static final double LANE_CHANGE_SPEED = 55;    // px/s sideways
    private static final double SIGNAL_TIME = 1.1;         // blink this long before moving

    private final Random rnd;
    private final double density;
    private final boolean laneChanges;
    private final boolean streaming;
    private final TrafficCar[] aheadInLane = new TrafficCar[LANES];

    private TrafficCar[] cars = new TrafficCar[256];
    private int size;
    private double nextRowY = 560;
    private int removedBehind;
    /** The AI car this traffic reacts to during the current update (null if none / crashed). */
    private AgentCar ego;

    Traffic(long seed, double density, boolean laneChanges, boolean streaming, double initialExtent) {
        this.rnd = new Random(seed);
        this.density = Math.max(0.2, density);
        this.laneChanges = laneChanges;
        this.streaming = streaming;
        generateUntil(initialExtent);
    }

    // ---- Queries ------------------------------------------------------------

    public int size() {
        return size;
    }

    public TrafficCar get(int i) {
        return cars[i];
    }

    /** Index of the first car with {@code car.y >= y}. */
    public int lowerBound(double y) {
        int lo = 0, hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cars[mid].y < y) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Number of traffic cars that are (or were) behind position y. */
    public int passedCount(double y) {
        return removedBehind + lowerBound(y);
    }

    // ---- Simulation ---------------------------------------------------------

    /**
     * Advances all traffic by one step. Traffic reacts to {@code ego} (the AI car this traffic
     * belongs to, or null): it brakes for it like for any other car and won't change lanes into it.
     */
    void update(double dt, AgentCar ego) {
        this.ego = ego != null && ego.alive ? ego : null;
        Arrays.fill(aheadInLane, null);
        // Walk front-to-back so aheadInLane[l] always holds the nearest car in front.
        for (int i = size - 1; i >= 0; i--) {
            TrafficCar c = cars[i];
            Car leader = nearer(aheadInLane[c.lane], aheadInLane[c.targetLane]);
            if (this.ego != null && this.ego.y > c.y && (egoIn(c.lane) || egoIn(c.targetLane))
                    && (leader == null || this.ego.y < leader.y)) {
                leader = this.ego;
            }
            double acc = idm(c, leader);
            c.speed = Math.max(0, c.speed + acc * dt);
            c.braking = acc < -60;
            c.y += c.speed * dt;
            if (laneChanges) laneLogic(c, dt);
            aheadInLane[c.lane] = c;
            aheadInLane[c.targetLane] = c;
        }
        sort();
    }

    /**
     * Drops cars far behind the agent (they can no longer matter) and, in streaming mode
     * (endless demo), spawns new ones ahead of it.
     */
    void maintain(double backY, double frontY) {
        if (streaming) {
            if (nextRowY < frontY + 1500) nextRowY = frontY + 1500;
            generateUntil(frontY + 3500);
        }
        int cut = lowerBound(backY - 1500);
        if (cut > 0) {
            System.arraycopy(cars, cut, cars, 0, size - cut);
            Arrays.fill(cars, size - cut, size, null);
            size -= cut;
            removedBehind += cut;
        }
    }

    private static TrafficCar nearer(TrafficCar a, TrafficCar b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.y < b.y ? a : b;
    }

    /** Is the ego car (partly) in this lane, or merging into it? */
    private boolean egoIn(int lane) {
        if (ego.targetLane == lane) return true;
        double lo = lane * Config.LANE_WIDTH - Car.W / 2, hi = (lane + 1) * Config.LANE_WIDTH + Car.W / 2;
        return ego.x > lo && ego.x < hi;
    }

    private static double idm(TrafficCar c, Car leader) {
        double v = c.speed;
        double ratio = v / c.desiredSpeed;
        ratio *= ratio;
        double free = 1 - ratio * ratio;
        double interaction = 0;
        if (leader != null) {
            double gap = Math.max(1, leader.y - c.y - L);
            double dv = v - leader.speed;
            double sStar = IDM_MIN_GAP + Math.max(0, v * IDM_HEADWAY + v * dv / (2 * Math.sqrt(IDM_ACCEL * IDM_DECEL)));
            double r = sStar / gap;
            interaction = r * r;
        }
        return Math.max(-MAX_BRAKE, IDM_ACCEL * (free - interaction));
    }

    private void laneLogic(TrafficCar c, double dt) {
        if (c.lane != c.targetLane) {
            double tx = Config.laneCenter(c.targetLane);
            double step = LANE_CHANGE_SPEED * dt;
            if (Math.abs(tx - c.x) <= step) {
                c.x = tx;
                c.lane = c.targetLane;
                c.signal = 0;
                c.cooldown = 5 + rnd.nextDouble() * 5;
            } else {
                c.x += Math.signum(tx - c.x) * step;
            }
            return;
        }
        if (c.signal != 0) {
            c.signalTimer -= dt;
            if (c.signalTimer <= 0) {
                int t = c.lane + c.signal;
                if (laneFree(c, t)) {
                    c.targetLane = t;
                } else {
                    c.signal = 0;
                    c.cooldown = 3;
                }
            }
            return;
        }
        c.cooldown -= dt;
        if (c.cooldown <= 0 && rnd.nextDouble() < LANE_CHANGE_RATE * dt) {
            int dir = c.lane == 0 ? 1 : c.lane == LANES - 1 ? -1 : (rnd.nextBoolean() ? 1 : -1);
            if (laneFree(c, c.lane + dir)) {
                c.signal = dir;
                c.signalTimer = SIGNAL_TIME;
            } else {
                c.cooldown = 2;
            }
        }
    }

    private boolean laneFree(TrafficCar c, int lane) {
        if (ego != null && egoIn(lane) && ego.y > c.y - 150 && ego.y < c.y + 120) return false;
        for (int i = 0; i < size; i++) {
            TrafficCar o = cars[i];
            if (o != c && o.occupies(lane) && o.y > c.y - 150 && o.y < c.y + 120) return false;
        }
        return true;
    }

    // ---- Spawning -----------------------------------------------------------

    /**
     * Traffic is laid out in "rows". A row has one car, or two cars in different lanes, so
     * there is always at least one open lane when a row is first placed.
     */
    private void generateUntil(double limit) {
        while (nextRowY < limit) {
            double y = nextRowY;
            boolean pair = rnd.nextDouble() < 0.30 + 0.25 * Math.min(1, density);
            int lane = rnd.nextInt(LANES);
            if (pair) {
                for (int l = 0; l < LANES; l++) {
                    if (l != lane) spawn(l, y + rnd.nextDouble() * 30);
                }
            } else {
                spawn(lane, y);
            }
            nextRowY += (190 + rnd.nextDouble() * 300) / density;
        }
        sort();
    }

    private void spawn(int lane, double y) {
        boolean slow = rnd.nextDouble() < Config.SLOW_VEHICLE_FRACTION;
        double lo = slow ? Config.TRAFFIC_MIN_SPEED : Config.CAR_MIN_SPEED;
        double hi = slow ? Config.SLOW_VEHICLE_MAX_SPEED : Config.TRAFFIC_MAX_SPEED;
        double speed = lo + rnd.nextDouble() * (hi - lo);
        int color = rnd.nextInt(TrafficCar.PALETTE_SIZE);
        for (int i = 0; i < size; i++) {
            TrafficCar o = cars[i];
            if (o.occupies(lane) && Math.abs(o.y - y) < L * 2) return;
        }
        if (size == cars.length) cars = Arrays.copyOf(cars, size * 2);
        cars[size++] = new TrafficCar(lane, y, speed, color);
    }

    /** Insertion sort: the array is almost always already sorted, so this is ~O(n). */
    private void sort() {
        for (int i = 1; i < size; i++) {
            TrafficCar c = cars[i];
            int j = i - 1;
            while (j >= 0 && cars[j].y > c.y) {
                cars[j + 1] = cars[j];
                j--;
            }
            cars[j + 1] = c;
        }
    }
}
