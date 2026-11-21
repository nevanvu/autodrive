package dev.autodrive.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Evolves a population of fixed-topology networks.
 * <ul>
 *   <li>Elitism: the top few genomes survive unchanged (and are re-tested next generation).</li>
 *   <li>Tournament selection picks parents.</li>
 *   <li>Neuron-level crossover: a child inherits each neuron's full weight set from one parent,
 *       so useful "feature detectors" aren't torn apart.</li>
 *   <li>Gaussian mutation that grows when progress stalls.</li>
 *   <li>A few random immigrants each generation to keep diversity.</li>
 * </ul>
 */
public final class GeneticAlgorithm {

    public static final class Params {
        public int populationSize = 150;
        public int elites = 4;
        public int tournamentSize = 4;
        public double crossoverRate = 0.75;
        public double mutationRate = 0.07;
        public double mutationSigma = 0.22;
        public double immigrantFraction = 0.03;
        public double weightLimit = 4.0;
    }

    private final int[] topology;
    private final Params p;
    private final Random rnd;
    private final int geneCount;

    private List<Genome> population = new ArrayList<>();
    private int generation;
    private double bestEver = Double.NEGATIVE_INFINITY;
    private int stagnation;

    public GeneticAlgorithm(int[] topology, Params params, long seed) {
        this.topology = topology.clone();
        this.p = params;
        this.rnd = new Random(seed);
        this.geneCount = NeuralNetwork.geneCount(topology);
        for (int i = 0; i < p.populationSize; i++) population.add(randomGenome());
    }

    /** Replaces the population with a loaded brain plus mutated variants of it. */
    public void seedFrom(double[] genes) {
        if (genes.length != geneCount) throw new IllegalArgumentException("Gene count mismatch");
        population = new ArrayList<>();
        population.add(new Genome(genes.clone()));
        while (population.size() < p.populationSize) {
            Genome g = new Genome(genes.clone());
            mutate(g, 0.15, 0.3);
            population.add(g);
        }
        generation = 0;
        bestEver = Double.NEGATIVE_INFINITY;
        stagnation = 0;
    }

    /**
     * Builds the next generation from the current fitness values, judging progress on the best
     * fitness. Only meaningful when every generation is scored on the same task; otherwise use
     * {@link #evolve(double)}.
     */
    public void evolve() {
        double best = Double.NEGATIVE_INFINITY;
        for (Genome g : population) best = Math.max(best, g.fitness());
        evolve(best);
    }

    /**
     * Builds the next generation from the current fitness values. Mutation grows while
     * {@code progressScore} fails to beat its best by 2%, so it must be comparable from one
     * generation to the next (e.g. a score on fixed validation highways). Fitness from a fresh
     * scenario each generation isn't: one lucky, easy scenario would set a record that later,
     * harder ones never beat, and mutation would stay boosted for the rest of the run.
     */
    public void evolve(double progressScore) {
        List<Genome> sorted = new ArrayList<>(population);
        sorted.sort(Comparator.comparingDouble(Genome::fitness).reversed());

        if (progressScore > bestEver * 1.02 || bestEver == Double.NEGATIVE_INFINITY) {
            bestEver = Math.max(bestEver, progressScore);
            stagnation = 0;
        } else {
            stagnation++;
        }
        double boost = 1 + 0.06 * Math.min(stagnation, 15);

        List<Genome> next = new ArrayList<>(p.populationSize);
        for (int i = 0; i < p.elites && i < sorted.size(); i++) next.add(sorted.get(i).copy());
        int immigrants = (int) Math.round(p.populationSize * p.immigrantFraction);
        for (int i = 0; i < immigrants && next.size() < p.populationSize; i++) next.add(randomGenome());
        while (next.size() < p.populationSize) {
            Genome a = tournament(sorted);
            Genome b = tournament(sorted);
            Genome child = rnd.nextDouble() < p.crossoverRate ? crossover(a, b) : a.copy();
            mutate(child, p.mutationRate * boost, p.mutationSigma * boost);
            next.add(child);
        }
        population = next;
        generation++;
    }

    private Genome tournament(List<Genome> pool) {
        Genome best = null;
        for (int i = 0; i < p.tournamentSize; i++) {
            Genome g = pool.get(rnd.nextInt(pool.size()));
            if (best == null || g.fitness() > best.fitness()) best = g;
        }
        return best;
    }

    private Genome crossover(Genome a, Genome b) {
        double[] ga = a.genes(), gb = b.genes();
        double[] c = new double[geneCount];
        int k = 0;
        for (int l = 1; l < topology.length; l++) {
            int block = topology[l - 1] + 1;
            for (int j = 0; j < topology[l]; j++) {
                System.arraycopy(rnd.nextBoolean() ? ga : gb, k, c, k, block);
                k += block;
            }
        }
        return new Genome(c);
    }

    private void mutate(Genome g, double rate, double sigma) {
        double[] w = g.genes();
        for (int i = 0; i < w.length; i++) {
            if (rnd.nextDouble() >= rate) continue;
            if (rnd.nextDouble() < 0.05) w[i] = rnd.nextDouble() * 2 - 1;      // occasional reset
            else w[i] += rnd.nextGaussian() * sigma;
            w[i] = Math.max(-p.weightLimit, Math.min(p.weightLimit, w[i]));
        }
    }

    private Genome randomGenome() {
        double[] w = new double[geneCount];
        for (int i = 0; i < w.length; i++) w[i] = rnd.nextDouble() * 2 - 1;
        return new Genome(w);
    }

    public List<Genome> population() { return population; }
    public int generation() { return generation; }
    public int stagnation() { return stagnation; }
    public int[] topology() { return topology.clone(); }
}
