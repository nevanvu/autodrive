# AutoDrive: neuroevolution highway autopilot

A 2D self-driving car simulator in Java 21 and JavaFX. A population of AI cars learns to drive a
3-lane highway and overtake traffic. Each car has a neural network for a brain, and a genetic
algorithm evolves those brains. You never write any driving rules.

![AutoDrive screenshot](docs/screenshot.png)

## Run it

You only need a JDK 21+. The Maven wrapper downloads Maven itself.

```bash
./mvnw javafx:run                 # the visual simulator
./mvnw package && java -jar target/autodrive.jar   # or as a single jar
./mvnw compile exec:java -Dexec.args="60 42 champion.brain"   # headless: 60 gens, seed 42, save champion
./mvnw test
```

Headless training runs about 300 ms per generation. You can load the saved `.brain` file in the UI.

### Controls

| | |
|---|---|
| **Speed** 1× … Max | Max runs as many physics steps as fit in each frame (≈100× real time) |
| **Control mode** | *Planner* (default) or *Direct*. Changing it restarts training |
| **Robust training** | Also score every brain on 4 unseen highways per generation |
| **Traffic changes lanes** | Traffic signals for about 1 s, then merges |
| **Traffic density** | Scales traffic. Early generations also get a curriculum ramp from 55% |
| **Watch champion drive** | Endless highway with the best *validated* brain. Restarts on crash |
| **Save / Load brain** | Plain-text weights file. Loading restarts evolution seeded from that brain |
| Keys | `Space` pause · `D` demo · `S` sensor rays |

## How it works

```
sense ─────────────▶ think ──────────────▶ act ─────────────────▶ score
11 ray sensors       19 → 14 → 9 → 2       planner: lane + speed   distance, overtakes,
3 doppler (Δv)       tanh MLP              → lane-keep + cruise     survival, smoothness,
speed, lane pos,                           direct: steer+throttle   time-to-collision
lat. vel, blinkers
```

**Sensors** (`AgentCar.sense`): 11 rays from −150° to +150° are cast against traffic and the road
edges. Each returns 0 for nothing in range and 1 for touching. The three forward rays also report
closing speed (a radar "doppler"). A camera-style detector reports when a neighbouring car's turn
signal points into your path.

**Brain** (`NeuralNetwork`): a fixed-topology feed-forward net. All weights live in one flat array
(the genome), stored neuron by neuron.

**Control** (`ControlMode`):
- *Planner*: the network acts as a behaviour planner. It requests lane changes and sets a target
  speed, and a lane-keeping controller plus cruise control carry out the plan. This mirrors how
  real autonomous-driving stacks split planning from control. It learns much faster and drives
  smoothly.
- *Direct*: end-to-end steering and throttle. It's harder to learn, so it's kept for comparison.

**Evolution** (`GeneticAlgorithm`): elitism, tournament selection and *neuron-level* crossover. A
child takes each neuron's whole weight set from one parent, so learned feature detectors aren't
scrambled. Gaussian mutation grows when progress stalls, and a few random immigrants are added
each generation for diversity.

**Fitness** (`AgentCar.fitness`):
`distance/100 + 8·overtakes + 10 if alive at 60 s`, minus small penalties for twitchy steering,
straddling lane lines and low time-to-collision (tailgating). A crash multiplies the score by 0.75
and stalling by 0.5.

## Fighting overfitting (the generalization problem)

A naive setup learns to beat *one* traffic arrangement and then fails when the arrangement changes.
This project counters that in several ways (see `Trainer`):

1. **New scenario every generation.** Start lane, traffic layout, speeds and lane changes are all
   re-seeded, so there is no single layout to memorize.
2. **Multi-scenario fitness.** Every genome also drives 4 unseen highways in the background.
   Score = ½·mean + ½·worst, so a brain that's brilliant once and crashes elsewhere loses.
3. **Held-out validation.** The top 5 brains are tested on 10 fixed validation highways they never
   train on. The best of those becomes the **champion**: the brain you watch and save.
   This is the green line on the chart.
4. **Sensor noise during training** makes brittle, hair-trigger strategies fail.
5. **Curriculum.** Traffic density ramps from 55% to 100% over the first ~15 generations.
6. **Fair comparisons.** Traffic ignores the AI cars, so every genome in an episode faces the same
   traffic, and any seed reproduces the same scenario exactly.

Two problems came up during tuning, and how they were fixed:
- With direct control and survival-heavy fitness, evolution got stuck on a "timid" driver that
  trails traffic forever and never overtakes. The fixes were the planner control mode and a higher
  weight on overtakes.
- Most remaining crashes were traffic merging into the car from the side. Adding turn-signal
  detection as an input gave the network a fair warning.

After ~60 generations, champions typically finish 83–100% of brand-new 60-second highways without
crashing. Results vary from run to run, as they always do with a genetic algorithm.

## Project layout

```
src/main/java/dev/autodrive/
  App.java, Launcher.java     JavaFX window, main loop, control panel
  sim/   Config, World, Traffic (IDM car-following + lane changes), AgentCar, TrafficCar, ControlMode
  ai/    NeuralNetwork, Genome, GeneticAlgorithm, BrainIO
  train/ Trainer (generations, robustness eval, validation), HeadlessTrain
  ui/    RoadRenderer, NetworkView, ChartView, Theme
```

The simulation core (`sim`, `ai`, `train`) has no JavaFX dependency, so it runs headless and is unit tested.

## Ideas to extend

- NEAT (evolving the topology as well as the weights)
- Recurrent inputs (feed the previous outputs back in) for smoother plans
- On-ramps, slower trucks and variable lane counts to stress generalization further
- Replace the GA with policy-gradient RL (PPO) and compare sample efficiency
