package dev.autodrive.train;

import dev.autodrive.ai.BrainIO;
import dev.autodrive.ai.Genome;
import dev.autodrive.sim.AgentCar;
import dev.autodrive.sim.Config;
import dev.autodrive.sim.ControlMode;
import dev.autodrive.sim.World;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Scores a brain on a held-out <b>test set</b> of highways and reports how it actually drives.
 * <p>
 * The validation seeds in {@link Trainer} pick the champion, so after many generations the
 * champion is partly tuned to them and its validation score is optimistic. The test seeds
 * here are never used for training or for picking the champion, so they give an honest
 * estimate of how a brain copes with highways it has never seen.
 * <pre>
 *   ./mvnw compile exec:java@benchmark -Dexec.args="champion.brain"
 *   args: [--episodes N] [--density D] brain [more brains, shown side by side]
 * </pre>
 */
public final class Benchmark {
    private Benchmark() {}

    /**
     * Test seeds are {@code TEST_SEED_BASE + i}. They don't overlap {@link Trainer#VALIDATION_SEEDS},
     * and training draws random 64-bit seeds, which practically never land in this range.
     */
    public static final long TEST_SEED_BASE = 1_000_000;
    public static final int DEFAULT_EPISODES = 50;
    public static final double DEFAULT_DENSITY = 1.0;

    /** The first {@code n} test highways. */
    public static long[] testSeeds(int n) {
        long[] s = new long[n];
        for (int i = 0; i < n; i++) s[i] = TEST_SEED_BASE + i;
        return s;
    }

    /**
     * Driving statistics over a set of episodes. Shares are fractions of the time the car was on the
     * road; headway and gap figures only count the time a car was ahead within radar range
     * (NaN if that never happened).
     */
    public record Report(int episodes, int crashes, int stalls,
                         double avgSpeedKmh, double maxSpeedKmh, double overLimitShare,
                         double overtakes, double laneChanges, double gateBlocks,
                         double followingShare, double headwayP10, double headwayMedian,
                         double gapMedianCarLengths, double tooCloseShare,
                         double missedOvertakeSeconds, double meanFitness, List<Long> crashSeeds) {}

    /** Drives {@code brain} once on each seed (no sensor noise, traffic changes lanes). */
    public static Report run(Genome brain, ControlMode mode, long[] seeds, double density) {
        Episode[] eps = new Episode[seeds.length];
        IntStream.range(0, seeds.length).parallel()
                .forEach(i -> eps[i] = drive(brain, mode, seeds[i], density));
        return summarize(eps);
    }

    /** Per-episode measurements, sampled every physics step while the car is on the road. */
    private static final class Episode {
        final long seed;
        boolean crashed, stalled;
        double distance, time, maxSpeed, overLimitTime, followTime, tooCloseTime;
        double missed, fitness;
        int overtakes, laneChanges, gateBlocks;
        double[] headways = new double[1024], gaps = new double[1024];
        int follows;

        Episode(long seed) { this.seed = seed; }

        void follow(double headway, double gap) {
            if (follows == headways.length) {
                headways = Arrays.copyOf(headways, follows * 2);
                gaps = Arrays.copyOf(gaps, follows * 2);
            }
            headways[follows] = headway;
            gaps[follows] = gap;
            follows++;
        }
    }

    private static Episode drive(Genome brain, ControlMode mode, long seed, double density) {
        World w = Trainer.createWorld(List.of(brain), seed, density, true, 0, Config.EPISODE_MAX_TIME, false, mode);
        AgentCar a = w.agents().get(0);
        Episode e = new Episode(seed);
        double dt = Config.DT;
        while (!w.isOver()) {
            w.step();
            if (!a.alive) break;
            e.maxSpeed = Math.max(e.maxSpeed, a.speed);
            if (a.speed > Config.SPEED_LIMIT) e.overLimitTime += dt;
            if (!Double.isInfinite(a.leadGap)) {
                e.followTime += dt;
                e.follow(a.headway(), a.leadGap / Config.CAR_LENGTH);
            }
            if (a.leadGap < Config.TOO_CLOSE_FRACTION * Config.followGap(a.speed)) e.tooCloseTime += dt;
        }
        e.crashed = a.crashed;
        e.stalled = a.stalled;
        e.distance = a.distance;
        e.time = a.timeAlive;
        e.overtakes = a.overtakes;
        e.laneChanges = a.laneChanges;
        e.gateBlocks = a.gateBlocks;
        e.missed = a.missedOvertakeTime;
        e.fitness = a.fitness();
        return e;
    }

    private static Report summarize(Episode[] eps) {
        int n = eps.length, crashes = 0, stalls = 0, follows = 0;
        double distance = 0, time = 0, maxSpeed = 0, overLimit = 0, following = 0, tooClose = 0;
        double overtakes = 0, laneChanges = 0, gateBlocks = 0, missed = 0, fitness = 0;
        List<Long> crashSeeds = new ArrayList<>();
        for (Episode e : eps) {
            if (e.crashed) {
                crashes++;
                crashSeeds.add(e.seed);
            }
            if (e.stalled) stalls++;
            distance += e.distance;
            time += e.time;
            maxSpeed = Math.max(maxSpeed, e.maxSpeed);
            overLimit += e.overLimitTime;
            following += e.followTime;
            tooClose += e.tooCloseTime;
            overtakes += e.overtakes;
            laneChanges += e.laneChanges;
            gateBlocks += e.gateBlocks;
            missed += e.missed;
            fitness += e.fitness;
            follows += e.follows;
        }
        double[] headways = new double[follows], gaps = new double[follows];
        int k = 0;
        for (Episode e : eps) {
            System.arraycopy(e.headways, 0, headways, k, e.follows);
            System.arraycopy(e.gaps, 0, gaps, k, e.follows);
            k += e.follows;
        }
        Arrays.sort(headways);
        Arrays.sort(gaps);
        double t = Math.max(time, 1e-9);
        return new Report(n, crashes, stalls,
                Config.pxToKmh(distance / t), Config.pxToKmh(maxSpeed), overLimit / t,
                overtakes / n, laneChanges / n, gateBlocks / n,
                following / t, percentile(headways, 0.10), percentile(headways, 0.50),
                percentile(gaps, 0.50), tooClose / t,
                missed / n, fitness / n, List.copyOf(crashSeeds));
    }

    /** Nearest-rank percentile of a sorted array (NaN when empty). */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return Double.NaN;
        int i = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    // ---- Output -------------------------------------------------------------

    private record Row(String label, Function<Report, String> value) {}

    private static final List<Row> ROWS = List.of(
            new Row("crashes", r -> r.crashes() + " / " + r.episodes()),
            new Row("stalls", r -> r.stalls() + " / " + r.episodes()),
            new Row("avg speed (km/h)", r -> f("%.1f", r.avgSpeedKmh())),
            new Row("max speed (km/h)", r -> f("%.1f", r.maxSpeedKmh())),
            new Row("time over limit", r -> pct(r.overLimitShare())),
            new Row("overtakes / episode", r -> f("%.1f", r.overtakes())),
            new Row("lane changes / episode", r -> f("%.1f", r.laneChanges())),
            new Row("gate holds / episode", r -> f("%.1f", r.gateBlocks())),
            new Row("time following a car", r -> pct(r.followingShare())),
            new Row("headway p10 (s)", r -> f("%.2f", r.headwayP10())),
            new Row("headway median (s)", r -> f("%.2f", r.headwayMedian())),
            new Row("gap median (car lengths)", r -> f("%.1f", r.gapMedianCarLengths())),
            new Row("time too close", r -> pct(r.tooCloseShare())),
            new Row("missed overtakes (s / ep)", r -> f("%.1f", r.missedOvertakeSeconds())),
            new Row("mean fitness", r -> f("%.1f", r.meanFitness())));

    /** A table with one column per report. */
    public static String format(List<String> names, List<Report> reports) {
        int labelWidth = ROWS.stream().mapToInt(r -> r.label().length()).max().orElse(0);
        int colWidth = 12;
        for (String name : names) colWidth = Math.max(colWidth, name.length());
        StringBuilder sb = new StringBuilder();
        sb.append(" ".repeat(labelWidth));
        for (String name : names) sb.append("  ").append(pad(name, colWidth));
        sb.append('\n');
        for (Row row : ROWS) {
            sb.append(String.format("%-" + labelWidth + "s", row.label()));
            for (Report r : reports) sb.append("  ").append(pad(row.value().apply(r), colWidth));
            sb.append('\n');
        }
        for (int i = 0; i < reports.size(); i++) {
            if (reports.get(i).crashSeeds().isEmpty()) continue;
            sb.append("crash seeds");
            if (reports.size() > 1) sb.append(" (").append(names.get(i)).append(')');
            sb.append(": ").append(reports.get(i).crashSeeds()).append('\n');
        }
        return sb.toString();
    }

    private static String pad(String s, int width) {
        return " ".repeat(Math.max(0, width - s.length())) + s;
    }

    private static String f(String fmt, double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, fmt, v);
    }

    private static String pct(double share) {
        return f("%.1f%%", share * 100);
    }

    // ---- Command line -------------------------------------------------------

    public static void main(String[] args) throws Exception {
        int episodes = DEFAULT_EPISODES;
        double density = DEFAULT_DENSITY;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--episodes" -> episodes = Integer.parseInt(args[++i]);
                case "--density" -> density = Double.parseDouble(args[++i]);
                default -> files.add(Path.of(args[i]));
            }
        }
        if (files.isEmpty() || episodes < 1) {
            System.err.println("usage: Benchmark [--episodes N] [--density D] brain [more brains]");
            System.exit(2);
        }
        long[] seeds = testSeeds(episodes);
        List<String> names = new ArrayList<>();
        List<Report> reports = new ArrayList<>();
        for (Path file : files) {
            BrainIO.Brain b = BrainIO.load(file);
            if (!Arrays.equals(b.topology(), AgentCar.TOPOLOGY)) {
                System.err.println(file + ": network shape doesn't match the current sensors");
                System.exit(1);
            }
            ControlMode mode = b.mode() != null ? ControlMode.valueOf(b.mode()) : ControlMode.PLANNER;
            names.add(file.getFileName().toString());
            reports.add(run(new Genome(b.weights()), mode, seeds, density));
        }
        System.out.printf(Locale.ROOT, "Test set: %d unseen highways (seeds %d-%d), density %.2f, %.0f s each%n%n",
                episodes, seeds[0], seeds[seeds.length - 1], density, Config.EPISODE_MAX_TIME);
        System.out.print(format(names, reports));
    }
}
