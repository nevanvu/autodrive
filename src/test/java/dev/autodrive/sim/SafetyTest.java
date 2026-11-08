package dev.autodrive.sim;

import dev.autodrive.ai.GeneticAlgorithm;
import dev.autodrive.ai.Genome;
import dev.autodrive.ai.NeuralNetwork;
import dev.autodrive.train.Trainer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static dev.autodrive.sim.Config.*;
import static org.junit.jupiter.api.Assertions.*;

class SafetyTest {

    private static final double NONE = Double.NaN;
    private static final double CLEAR = Double.POSITIVE_INFINITY;

    @Test
    void gateAcceptsAnEmptyLane() {
        assertTrue(AgentCar.laneGapSafe(300, CLEAR, NONE, CLEAR, NONE, 1.0));
    }

    @Test
    void gateRefusesACarRightAhead() {
        assertFalse(AgentCar.laneGapSafe(300, 40, 300, CLEAR, NONE, 1.0));
    }

    @Test
    void gateRefusesAFastCarComingFromBehind() {
        // 200 px behind but closing at 150 px/s: only 1.3 s to impact
        assertFalse(AgentCar.laneGapSafe(180, CLEAR, NONE, 200, 330, 1.0));
        // same gap, same speed as us: fine
        assertTrue(AgentCar.laneGapSafe(180, CLEAR, NONE, 200, 180, 1.0));
    }

    @Test
    void gateRefusesACarAlongside() {
        assertFalse(AgentCar.laneGapSafe(250, 0, 250, CLEAR, NONE, 1.0));
        assertFalse(AgentCar.laneGapSafe(250, CLEAR, NONE, 0, 250, 1.0));
    }

    @Test
    void plannerNeverExceedsTheSpeedLimit() {
        GeneticAlgorithm ga = new GeneticAlgorithm(AgentCar.TOPOLOGY, new GeneticAlgorithm.Params(), 11);
        List<Genome> pop = ga.population().subList(0, 30);
        World w = Trainer.createWorld(pop, 5, 0.5, true, 0, 20, false, ControlMode.PLANNER);
        while (!w.isOver()) {
            w.step();
            for (AgentCar a : w.agents()) assertTrue(a.speed <= SPEED_LIMIT + 1e-9, "speed " + a.speedKmh());
        }
    }

    @Test
    void trafficBrakesForTheAgentInsteadOfDrivingThroughIt() {
        Traffic t = new Traffic(3, 1.0, false, false, 4000);
        TrafficCar follower = t.get(0);
        AgentCar parked = agentAt(laneCenter(follower.lane), follower.y + 250);
        parked.speed = 0;
        for (int i = 0; i < 10 * 60; i++) {
            t.update(DT, parked);
            assertFalse(follower.overlaps(parked), "traffic drove into the agent");
        }
        assertTrue(follower.speed < 5, "traffic should have stopped behind the agent");
    }

    @Test
    void radarSeesACarInTheBlindSpot() {
        Traffic t = new Traffic(3, 1.0, false, false, 4000);
        TrafficCar c = t.get(0);
        int lane = c.lane == 0 ? 1 : c.lane - 1;
        int side = lane < c.lane ? AgentCar.RIGHT : AgentCar.LEFT;
        // The traffic car is 40 px behind us in the next lane: the classic mirror blind spot.
        AgentCar a = agentAt(laneCenter(lane), c.y + 40);
        a.speed = c.speed;
        a.update(t, DT, DT);
        assertEquals(0, a.sideGapBehind[side], 1e-9);
        assertFalse(AgentCar.laneGapSafe(a.speed, a.sideGapAhead[side], a.sideSpeedAhead[side],
                a.sideGapBehind[side], a.sideSpeedBehind[side], 1.0));
    }

    private static AgentCar agentAt(double x, double y) {
        NeuralNetwork idle = new NeuralNetwork(AgentCar.TOPOLOGY, new double[NeuralNetwork.geneCount(AgentCar.TOPOLOGY)]);
        return new AgentCar(0, idle, ControlMode.PLANNER, x, y, new Random(1), 0);
    }
}
