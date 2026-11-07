package dev.autodrive.ai;

/**
 * Fully connected feed-forward network with tanh activations.
 * <p>
 * Weights live in one flat array (the genome), laid out neuron by neuron:
 * for every layer l &gt; 0, for every neuron j: {@code layers[l-1]} input weights, then a bias.
 * Keeping each neuron's weights contiguous lets the genetic algorithm cross over whole neurons.
 */
public final class NeuralNetwork {
    private final int[] layers;
    private final double[] weights;
    private final double[][] activations;

    public NeuralNetwork(int[] layers, double[] weights) {
        if (layers.length < 2) throw new IllegalArgumentException("Need at least an input and output layer");
        if (weights.length != geneCount(layers)) {
            throw new IllegalArgumentException("Expected " + geneCount(layers) + " weights, got " + weights.length);
        }
        this.layers = layers.clone();
        this.weights = weights;
        this.activations = new double[layers.length][];
        for (int l = 0; l < layers.length; l++) activations[l] = new double[layers[l]];
    }

    public static int geneCount(int[] layers) {
        int n = 0;
        for (int l = 1; l < layers.length; l++) n += layers[l] * (layers[l - 1] + 1);
        return n;
    }

    /** Runs the network. The returned array is reused between calls. */
    public double[] feed(double[] input) {
        System.arraycopy(input, 0, activations[0], 0, layers[0]);
        int k = 0;
        for (int l = 1; l < layers.length; l++) {
            double[] prev = activations[l - 1];
            double[] cur = activations[l];
            int fanIn = layers[l - 1];
            for (int j = 0; j < layers[l]; j++) {
                double sum = 0;
                for (int i = 0; i < fanIn; i++) sum += weights[k++] * prev[i];
                sum += weights[k++];
                cur[j] = Math.tanh(sum);
            }
        }
        return activations[layers.length - 1];
    }

    public int[] layers() { return layers; }
    public double[] weights() { return weights; }
    /** Last activations per layer (read-only use, e.g. for visualisation). */
    public double[][] activations() { return activations; }
}
