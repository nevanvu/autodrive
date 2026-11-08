package dev.autodrive.sim;

import dev.autodrive.ai.NeuralNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * One episode: a highway full of traffic plus any number of AI cars. AI cars don't collide
 * with each other. Each one drives in its own copy of the traffic (same seed, so the same
 * scenario) that reacts to it, which lets a whole population share one world.
 */
public final class World {
    private final List<Traffic> traffic;
    private final List<AgentCar> agents;
    private final double maxTime;
    private final boolean streaming;
    private final long seed;
    private final double density;
    private double time;
    private int alive;

    /**
     * @param seed        determines start lane and the whole traffic scenario
     * @param density     traffic density multiplier (1.0 = normal)
     * @param laneChanges whether traffic cars change lanes
     * @param brains      one AI car per brain
     * @param maxTime     episode length in seconds ({@code Double.POSITIVE_INFINITY} for endless)
     * @param sensorNoise std-dev of Gaussian noise added to ray readings (0 = none)
     * @param streaming   spawn traffic on the fly around the agents (endless mode)
     * @param mode        how network outputs drive the car
     */
    public World(long seed, double density, boolean laneChanges, List<NeuralNetwork> brains,
                 double maxTime, double sensorNoise, boolean streaming, ControlMode mode) {
        Random r = new Random(seed);
        this.seed = seed;
        this.density = density;
        this.maxTime = maxTime;
        this.streaming = streaming;
        int startLane = r.nextInt(Config.LANES);
        // Traffic moves forward too, so the agent can only ever reach cars that start within
        // (top speed - slowest traffic) * time of it. Spawning beyond that would be wasted work.
        double topSpeed = mode == ControlMode.PLANNER ? Config.SPEED_LIMIT : Config.AGENT_MAX_SPEED;
        double extent = streaming || Double.isInfinite(maxTime)
                ? 4000
                : (topSpeed - Config.TRAFFIC_MIN_SPEED) * maxTime + 3000;
        long trafficSeed = r.nextLong();
        List<AgentCar> list = new ArrayList<>(brains.size());
        List<Traffic> tl = new ArrayList<>(Math.max(1, brains.size()));
        for (int i = 0; i < brains.size(); i++) {
            list.add(new AgentCar(i, brains.get(i), mode, Config.laneCenter(startLane), 0, new Random(r.nextLong()), sensorNoise));
            tl.add(new Traffic(trafficSeed, density, laneChanges, streaming, extent));
        }
        if (tl.isEmpty()) tl.add(new Traffic(trafficSeed, density, laneChanges, streaming, extent));
        this.agents = Collections.unmodifiableList(list);
        this.traffic = Collections.unmodifiableList(tl);
        this.alive = list.size();
    }

    public void step() {
        if (isOver()) return;
        double dt = Config.DT;
        time += dt;
        int count = 0;
        for (AgentCar a : agents) {
            if (!a.alive) continue;            // a crashed car's traffic is frozen; nobody drives in it any more
            Traffic t = traffic.get(a.index);
            t.update(dt, a);
            a.update(t, dt, time);
            if (a.alive) {
                count++;
                t.maintain(a.y, a.y);
            }
        }
        alive = count;
        if (time >= maxTime) {
            for (AgentCar a : agents) if (a.alive) a.survived = true;
        }
    }

    public boolean isOver() {
        return alive == 0 || time >= maxTime;
    }

    /** The alive car furthest down the road, or null when everyone has crashed. */
    public AgentCar leader() {
        AgentCar best = null;
        for (AgentCar a : agents) {
            if (a.alive && (best == null || a.y > best.y)) best = a;
        }
        return best;
    }

    /** The traffic {@code agent} drives in (agent 0's when null). */
    public Traffic traffic(AgentCar agent) { return traffic.get(agent != null ? agent.index : 0); }
    public List<AgentCar> agents() { return agents; }
    public int aliveCount() { return alive; }
    public double time() { return time; }
    public double maxTime() { return maxTime; }
    public long seed() { return seed; }
    public double density() { return density; }
}
