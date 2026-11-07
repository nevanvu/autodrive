package dev.autodrive.train;

import dev.autodrive.ai.GeneticAlgorithm;
import dev.autodrive.ai.Genome;
import dev.autodrive.ai.NeuralNetwork;
import dev.autodrive.sim.AgentCar;
import dev.autodrive.sim.Config;
import dev.autodrive.sim.ControlMode;
import dev.autodrive.sim.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * Runs the evolution loop. Built to fight over-fitting to one traffic layout:
 * <ol>
 *   <li><b>Fresh scenario every generation.</b> Each generation drives a newly seeded highway
 *       (random start lane, traffic layout, speeds and lane changes).</li>
 *   <li><b>Multi-scenario fitness.</b> Besides the episode you watch, every genome also drives
 *       several unseen scenarios in the background. Score = ½·mean + ½·worst, so a brain
 *       that's great once but crashes elsewhere loses out.</li>
 *   <li><b>Held-out validation.</b> The top candidates are tested on fixed validation seeds they
 *       never train on. The brain with the best validation score is the "champion" you watch
 *       in demo mode and save to disk.</li>
 *   <li><b>Curriculum + sensor noise.</b> Traffic density ramps up over early generations, and
 *       small Gaussian noise on the sensors during training discourages brittle strategies.</li>
 * </ol>
 * The visible episode is stepped by the UI thread; the extra evaluation runs on a worker thread.
 */
public final class Trainer {

    public enum Phase { DRIVING, EVALUATING }

    public record GenerationStats(int generation, double best, double average, double validation, double density) {}

    private record EvalResult(int epoch, double[] fitness, int champion, double validation) {}

    public static final long[] VALIDATION_SEEDS = {101, 202, 303, 404, 505, 606, 707, 808, 909, 1010};
    public static final double VALIDATION_DENSITY = 1.0;
    public static final int VALIDATION_CANDIDATES = 5;
    public static final double TRAIN_SENSOR_NOISE = 0.02;

    private final GeneticAlgorithm.Params params;
    private final Random seedRng;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "autodrive-evaluator");
        t.setDaemon(true);
        return t;
    });

    private GeneticAlgorithm ga;
    private World world;
    private double worldDensity;
    private Phase phase = Phase.DRIVING;
    private CompletableFuture<EvalResult> pending;
    private int epoch;
    private final List<GenerationStats> history = new ArrayList<>();

    private volatile boolean robust = true;
    private volatile boolean laneChanges = true;
    private volatile double densityScale = 1.0;
    private final int extraScenarios = 4;
    private ControlMode mode = ControlMode.PLANNER;

    private Genome champion;
    private double championScore = -1;
    private int championGeneration = -1;

    public Trainer(GeneticAlgorithm.Params params, long seed) {
        this.params = params;
        this.seedRng = new Random(seed);
        this.ga = new GeneticAlgorithm(AgentCar.TOPOLOGY, params, seedRng.nextLong());
        startGeneration();
    }

    // ---- Main loop ----------------------------------------------------------

    /**
     * Advances training by up to {@code maxSteps} physics steps, stopping early at the deadline
     * or while waiting for background evaluation. Returns the number of steps simulated.
     */
    public int tick(int maxSteps, long deadlineNanos) {
        int done = 0;
        while (done < maxSteps && System.nanoTime() < deadlineNanos) {
            if (phase == Phase.DRIVING) {
                world.step();
                done++;
                if (world.isOver()) beginEvaluation();
            } else if (pending.isDone()) {
                applyResult(pending.join());
            } else {
                break;
            }
        }
        return done;
    }

    /** Runs one full generation synchronously (used by the headless trainer). */
    public GenerationStats runGenerationBlocking() {
        while (!world.isOver()) world.step();
        beginEvaluation();
        applyResult(pending.join());
        return history.get(history.size() - 1);
    }

    private void startGeneration() {
        worldDensity = currentDensity();
        world = createWorld(ga.population(), seedRng.nextLong(), worldDensity, laneChanges,
                TRAIN_SENSOR_NOISE, Config.EPISODE_MAX_TIME, false, mode);
        phase = Phase.DRIVING;
        pending = null;
    }

    private void beginEvaluation() {
        double[] primary = world.agents().stream().mapToDouble(AgentCar::fitness).toArray();
        List<Genome> pop = List.copyOf(ga.population());
        long[] seeds = new long[robust ? extraScenarios : 0];
        for (int i = 0; i < seeds.length; i++) seeds[i] = seedRng.nextLong();
        double density = worldDensity;
        boolean lc = laneChanges;
        int myEpoch = epoch;
        ControlMode m = mode;
        phase = Phase.EVALUATING;
        pending = CompletableFuture.supplyAsync(() -> evaluate(myEpoch, pop, primary, seeds, density, lc, m), executor);
    }

    private static EvalResult evaluate(int epoch, List<Genome> pop, double[] primary, long[] seeds,
                                       double density, boolean laneChanges, ControlMode mode) {
        int n = pop.size();
        double[][] scores = new double[seeds.length + 1][];
        scores[0] = primary;
        IntStream.range(0, seeds.length).parallel().forEach(k ->
                scores[k + 1] = runEpisode(pop, seeds[k], density, laneChanges, TRAIN_SENSOR_NOISE, mode));

        double[] combined = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0, min = Double.POSITIVE_INFINITY;
            for (double[] s : scores) {
                sum += s[i];
                min = Math.min(min, s[i]);
            }
            double mean = sum / scores.length;
            combined[i] = scores.length == 1 ? mean : 0.5 * mean + 0.5 * min;
        }

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(combined[b], combined[a]));
        int k = Math.min(VALIDATION_CANDIDATES, n);
        List<Genome> candidates = new ArrayList<>(k);
        for (int i = 0; i < k; i++) candidates.add(pop.get(order[i]));
        double[] val = validate(candidates, mode);
        int bestCandidate = 0;
        for (int i = 1; i < k; i++) if (val[i] > val[bestCandidate]) bestCandidate = i;
        return new EvalResult(epoch, combined, order[bestCandidate], val[bestCandidate]);
    }

    private void applyResult(EvalResult r) {
        pending = null;
        if (r.epoch() != epoch) {          // stale result from before a reset/import
            startGeneration();
            return;
        }
        List<Genome> pop = ga.population();
        double best = Double.NEGATIVE_INFINITY, sum = 0;
        for (int i = 0; i < pop.size(); i++) {
            double f = r.fitness()[i];
            pop.get(i).setFitness(f);
            best = Math.max(best, f);
            sum += f;
        }
        if (r.validation() > championScore) {
            championScore = r.validation();
            champion = pop.get(r.champion()).copy();
            championGeneration = ga.generation() + 1;
        }
        history.add(new GenerationStats(ga.generation() + 1, best, sum / pop.size(), r.validation(), worldDensity));
        ga.evolve();
        startGeneration();
    }

    // ---- Evaluation helpers ------------------------------------------------

    public static World createWorld(List<Genome> genomes, long seed, double density, boolean laneChanges,
                                    double noise, double maxTime, boolean streaming, ControlMode mode) {
        List<NeuralNetwork> brains = new ArrayList<>(genomes.size());
        for (Genome g : genomes) brains.add(new NeuralNetwork(AgentCar.TOPOLOGY, g.genes()));
        return new World(seed, density, laneChanges, brains, maxTime, noise, streaming, mode);
    }

    static double[] runEpisode(List<Genome> genomes, long seed, double density, boolean laneChanges,
                               double noise, ControlMode mode) {
        World w = createWorld(genomes, seed, density, laneChanges, noise, Config.EPISODE_MAX_TIME, false, mode);
        while (!w.isOver()) w.step();
        return w.agents().stream().mapToDouble(AgentCar::fitness).toArray();
    }

    /** Mean fitness of each candidate over the held-out validation scenarios (no sensor noise). */
    public static double[] validate(List<Genome> candidates, ControlMode mode) {
        double[][] per = new double[VALIDATION_SEEDS.length][];
        IntStream.range(0, VALIDATION_SEEDS.length).parallel().forEach(k ->
                per[k] = runEpisode(candidates, VALIDATION_SEEDS[k], VALIDATION_DENSITY, true, 0, mode));
        double[] mean = new double[candidates.size()];
        for (double[] s : per) for (int i = 0; i < mean.length; i++) mean[i] += s[i] / per.length;
        return mean;
    }

    // ---- Control ------------------------------------------------------------

    /** Start evolution over from random brains, optionally switching control mode. */
    public void reset(long seed, ControlMode newMode) {
        epoch++;
        mode = newMode;
        seedRng.setSeed(seed);
        ga = new GeneticAlgorithm(AgentCar.TOPOLOGY, params, seedRng.nextLong());
        history.clear();
        champion = null;
        championScore = -1;
        championGeneration = -1;
        startGeneration();
    }

    /** Restart evolution from a saved brain; it also becomes the champion. */
    public void importBrain(double[] genes, ControlMode brainMode) {
        epoch++;
        mode = brainMode;
        ga.seedFrom(genes);
        history.clear();
        champion = new Genome(genes.clone());
        championScore = validate(List.of(champion), mode)[0];
        championGeneration = 0;
        startGeneration();
    }

    /** Traffic density for the next generation: ramps from 55% to 100% over ~15 generations. */
    public double currentDensity() {
        return densityScale * Math.min(1.0, 0.55 + 0.03 * ga.generation());
    }

    // ---- Accessors ----------------------------------------------------------

    public World world() { return world; }
    public ControlMode mode() { return mode; }
    public Phase phase() { return phase; }
    public int generation() { return ga.generation() + 1; }
    public int populationSize() { return ga.population().size(); }
    public List<GenerationStats> history() { return Collections.unmodifiableList(history); }
    public Genome champion() { return champion; }
    public double championScore() { return championScore; }
    public int championGeneration() { return championGeneration; }
    public int extraScenarios() { return robust ? extraScenarios : 0; }
    public boolean isRobust() { return robust; }
    public void setRobust(boolean robust) { this.robust = robust; }
    public boolean isLaneChanges() { return laneChanges; }
    public void setLaneChanges(boolean laneChanges) { this.laneChanges = laneChanges; }
    public double densityScale() { return densityScale; }
    public void setDensityScale(double densityScale) { this.densityScale = densityScale; }
}
