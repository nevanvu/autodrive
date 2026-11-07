package dev.autodrive;

import dev.autodrive.ai.BrainIO;
import dev.autodrive.ai.GeneticAlgorithm;
import dev.autodrive.ai.Genome;
import dev.autodrive.ai.NeuralNetwork;
import dev.autodrive.sim.AgentCar;
import dev.autodrive.sim.ControlMode;
import dev.autodrive.sim.World;
import dev.autodrive.train.Trainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {

    @Test
    void networkOutputsAreBounded() {
        int[] topo = AgentCar.TOPOLOGY;
        double[] w = new double[NeuralNetwork.geneCount(topo)];
        Random r = new Random(1);
        for (int i = 0; i < w.length; i++) w[i] = r.nextGaussian() * 3;
        double[] out = new NeuralNetwork(topo, w).feed(new double[topo[0]]);
        assertEquals(topo[topo.length - 1], out.length);
        for (double v : out) assertTrue(v >= -1 && v <= 1);
    }

    @Test
    void sameSeedGivesSameEpisode() {
        GeneticAlgorithm ga = new GeneticAlgorithm(AgentCar.TOPOLOGY, new GeneticAlgorithm.Params(), 3);
        List<Genome> pop = ga.population().subList(0, 10);
        double[] a = run(pop, 99), b = run(pop, 99);
        assertArrayEquals(a, b, 1e-12);
    }

    private static double[] run(List<Genome> pop, long seed) {
        World w = Trainer.createWorld(pop, seed, 1.0, true, 0.02, 20, false, ControlMode.PLANNER);
        while (!w.isOver()) w.step();
        return w.agents().stream().mapToDouble(AgentCar::fitness).toArray();
    }

    @Test
    void evolutionKeepsPopulationSizeAndElites() {
        GeneticAlgorithm.Params p = new GeneticAlgorithm.Params();
        GeneticAlgorithm ga = new GeneticAlgorithm(AgentCar.TOPOLOGY, p, 5);
        for (int i = 0; i < ga.population().size(); i++) ga.population().get(i).setFitness(i);
        double[] best = ga.population().get(p.populationSize - 1).genes().clone();
        ga.evolve();
        assertEquals(p.populationSize, ga.population().size());
        assertArrayEquals(best, ga.population().get(0).genes());
    }

    @Test
    void trainingImprovesOverRandom() {
        Trainer t = new Trainer(new GeneticAlgorithm.Params(), 7);
        double first = t.runGenerationBlocking().validation();
        double last = first;
        for (int i = 0; i < 9; i++) last = t.runGenerationBlocking().validation();
        assertTrue(t.championScore() >= last);
        assertTrue(t.championScore() > first || first > 100, "champion should improve on generation 1");
    }

    @Test
    void brainRoundTrip(@TempDir Path dir) throws Exception {
        double[] w = new double[NeuralNetwork.geneCount(AgentCar.TOPOLOGY)];
        for (int i = 0; i < w.length; i++) w[i] = Math.sin(i);
        Path f = dir.resolve("x.brain");
        BrainIO.save(f, "PLANNER", AgentCar.TOPOLOGY, w, "test");
        BrainIO.Brain b = BrainIO.load(f);
        assertEquals("PLANNER", b.mode());
        assertArrayEquals(AgentCar.TOPOLOGY, b.topology());
        assertArrayEquals(w, b.weights());
    }
}
