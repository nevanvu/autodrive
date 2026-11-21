package dev.autodrive.train;

import dev.autodrive.ai.GeneticAlgorithm;
import dev.autodrive.ai.Genome;
import dev.autodrive.sim.AgentCar;
import dev.autodrive.sim.ControlMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTest {

    @Test
    void testSeedsNeverOverlapValidationSeeds() {
        long[] test = Benchmark.testSeeds(Benchmark.DEFAULT_EPISODES);
        assertEquals(Benchmark.DEFAULT_EPISODES, test.length);
        for (long t : test) {
            for (long v : Trainer.VALIDATION_SEEDS) assertNotEquals(v, t);
        }
    }

    @Test
    void sameBrainGivesSameReport() {
        Genome brain = randomBrain(3);
        long[] seeds = Benchmark.testSeeds(4);
        assertEquals(Benchmark.run(brain, ControlMode.PLANNER, seeds, 1.0),
                Benchmark.run(brain, ControlMode.PLANNER, seeds, 1.0));
    }

    @Test
    void reportIsConsistent() {
        Benchmark.Report r = Benchmark.run(randomBrain(5), ControlMode.PLANNER, Benchmark.testSeeds(6), 1.0);
        assertEquals(6, r.episodes());
        assertTrue(r.crashes() + r.stalls() <= r.episodes());
        assertEquals(r.crashes(), r.crashSeeds().size());
        for (double share : new double[]{r.overLimitShare(), r.followingShare(), r.tooCloseShare()}) {
            assertTrue(share >= 0 && share <= 1, "share " + share);
        }
        assertTrue(r.maxSpeedKmh() >= r.avgSpeedKmh());
        String table = Benchmark.format(List.of("x"), List.of(r));
        assertTrue(table.contains("crashes") && table.contains(r.crashes() + " / 6"), table);
    }

    @Test
    void percentileUsesNearestRank() {
        double[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(1, Benchmark.percentile(sorted, 0.10));
        assertEquals(5, Benchmark.percentile(sorted, 0.50));
        assertEquals(10, Benchmark.percentile(sorted, 1.0));
        assertTrue(Double.isNaN(Benchmark.percentile(new double[0], 0.5)));
    }

    private static Genome randomBrain(long seed) {
        return new GeneticAlgorithm(AgentCar.TOPOLOGY, new GeneticAlgorithm.Params(), seed).population().get(0);
    }
}
