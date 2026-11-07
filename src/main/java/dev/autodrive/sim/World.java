package dev.autodrive.sim;

import dev.autodrive.ai.NeuralNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * One episode: a highway full of traffic plus any number of AI cars. AI cars don't collide
 * with each other, so a whole population can share one world and see identical traffic.
 */
public final class World {
    private final Traffic traffic;
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
        double extent = streaming || Double.isInfinite(maxTime)
                ? 4000
                : Config.AGENT_MAX_SPEED * maxTime + 2000;
        this.traffic = new Traffic(r.nextLong(), density, laneChanges, streaming, extent);
        List<AgentCar> list = new ArrayList<>(brains.size());
        for (int i = 0; i < brains.size(); i++) {
            list.add(new AgentCar(i, brains.get(i), mode, Config.laneCenter(startLane), 0, new Random(r.nextLong()), sensorNoise));
        }
        this.agents = Collections.unmodifiableList(list);
        this.alive = list.size();
    }

    public void step() {
        if (isOver()) return;
        double dt = Config.DT;
        time += dt;
        traffic.update(dt);
        int count = 0;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (AgentCar a : agents) {
            if (!a.alive) continue;
            a.update(traffic, dt, time);
            if (a.alive) {
                count++;
                minY = Math.min(minY, a.y);
                maxY = Math.max(maxY, a.y);
            }
        }
        alive = count;
        if (time >= maxTime) {
            for (AgentCar a : agents) if (a.alive) a.survived = true;
        }
        if (streaming && count > 0) traffic.maintain(minY, maxY);
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

    public Traffic traffic() { return traffic; }
    public List<AgentCar> agents() { return agents; }
    public int aliveCount() { return alive; }
    public double time() { return time; }
    public double maxTime() { return maxTime; }
    public long seed() { return seed; }
    public double density() { return density; }
}
