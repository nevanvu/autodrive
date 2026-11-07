package dev.autodrive.sim;

/** How the neural network's two outputs are turned into vehicle motion. */
public enum ControlMode {
    /**
     * Behaviour-planner style (like real AV stacks): output 0 requests a lane change
     * (left / keep / right), output 1 sets a target speed. A lane-keeping controller and
     * cruise control execute the plan. Learns much faster and drives smoothly.
     */
    PLANNER("Planner: lane + target speed"),
    /** End-to-end: output 0 is the steering command, output 1 is throttle (+) / brake (-). */
    DIRECT("Direct: steering + throttle");

    private final String label;

    ControlMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
