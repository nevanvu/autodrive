package dev.autodrive.ai;

/** A candidate brain: the flat weight vector plus the score it earned. */
public final class Genome {
    private final double[] genes;
    private double fitness;

    public Genome(double[] genes) {
        this.genes = genes;
    }

    public double[] genes() { return genes; }
    public double fitness() { return fitness; }
    public void setFitness(double fitness) { this.fitness = fitness; }

    /** Deep copy with fitness reset. */
    public Genome copy() {
        return new Genome(genes.clone());
    }
}
