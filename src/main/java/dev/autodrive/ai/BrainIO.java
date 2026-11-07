package dev.autodrive.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Plain-text brain format:
 * <pre>
 * # comment lines
 * mode=PLANNER
 * topology=19,14,9,2
 * weights=0.12,-0.5,...
 * </pre>
 */
public final class BrainIO {
    private BrainIO() {}

    /** {@code mode} is the name of the control mode the brain was trained with (may be null). */
    public record Brain(String mode, int[] topology, double[] weights) {}

    public static void save(Path path, String mode, int[] topology, double[] weights, String comment) throws IOException {
        List<String> lines = new ArrayList<>();
        if (comment != null) for (String c : comment.split("\n")) lines.add("# " + c);
        if (mode != null) lines.add("mode=" + mode);
        lines.add("topology=" + Arrays.stream(topology).mapToObj(Integer::toString).collect(Collectors.joining(",")));
        lines.add("weights=" + Arrays.stream(weights).mapToObj(d -> Double.toString(d)).collect(Collectors.joining(",")));
        Files.write(path, lines);
    }

    public static Brain load(Path path) throws IOException {
        String mode = null;
        int[] topology = null;
        double[] weights = null;
        for (String raw : Files.readAllLines(path)) {
            String line = raw.trim();
            if (line.startsWith("mode=")) {
                mode = line.substring(5).trim();
            } else if (line.startsWith("topology=")) {
                topology = Arrays.stream(line.substring(9).split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
            } else if (line.startsWith("weights=")) {
                weights = Arrays.stream(line.substring(8).split(",")).map(String::trim).mapToDouble(Double::parseDouble).toArray();
            }
        }
        if (topology == null || weights == null) throw new IOException("Not a brain file: missing topology or weights");
        if (weights.length != NeuralNetwork.geneCount(topology)) throw new IOException("Weight count does not match topology");
        return new Brain(mode, topology, weights);
    }
}
