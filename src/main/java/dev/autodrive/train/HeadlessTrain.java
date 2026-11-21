package dev.autodrive.train;

import dev.autodrive.ai.BrainIO;
import dev.autodrive.ai.GeneticAlgorithm;
import dev.autodrive.sim.AgentCar;

import java.nio.file.Path;
import java.util.List;

/**
 * Trains without a window, as fast as the CPU allows.
 * <pre>
 *   mvn -q compile exec:java -Dexec.args="40 42 champion.brain"
 *   args: [generations=30] [seed=42] [output brain file]
 * </pre>
 * The saved file can be loaded in the UI with "Load brain…".
 */
public final class HeadlessTrain {
    public static void main(String[] args) throws Exception {
        int generations = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 42;
        Path out = args.length > 2 ? Path.of(args[2]) : null;

        Trainer trainer = new Trainer(new GeneticAlgorithm.Params(), seed);
        System.out.printf("%4s %9s %9s %11s %9s %8s%n", "gen", "best", "avg", "validation", "density", "ms");
        for (int g = 0; g < generations; g++) {
            long t0 = System.nanoTime();
            Trainer.GenerationStats s = trainer.runGenerationBlocking();
            System.out.printf("%4d %9.1f %9.1f %11.1f %9.2f %8d%n",
                    s.generation(), s.best(), s.average(), s.validation(), s.density(),
                    (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.printf("Champion: generation %d, validation score %.1f%n",
                trainer.championGeneration(), trainer.championScore());
        if (trainer.champion() != null) {
            Benchmark.Report test = Benchmark.run(trainer.champion(), trainer.mode(),
                    Benchmark.testSeeds(Benchmark.DEFAULT_EPISODES), Benchmark.DEFAULT_DENSITY);
            System.out.printf("%nTest set (%d highways never used for training or picking the champion):%n",
                    Benchmark.DEFAULT_EPISODES);
            System.out.print(Benchmark.format(List.of("champion"), List.of(test)));
        }
        if (out != null && trainer.champion() != null) {
            BrainIO.save(out, trainer.mode().name(), AgentCar.TOPOLOGY, trainer.champion().genes(),
                    "AutoDrive champion from generation " + trainer.championGeneration()
                            + "\nvalidation score " + trainer.championScore());
            System.out.println("Saved to " + out.toAbsolutePath());
        }
    }
}
