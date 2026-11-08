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

Headless training runs about 1–2 s per generation. You can load the saved `.brain` file in the UI.

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
sense ─────────────▶ think ──────────────▶ act ──────────────────────▶ score
11 ray sensors       27 → 14 → 9 → 2       planner: lane + speed        distance, survival,
3 doppler (Δv)       tanh MLP              → safety gate + lane-keep    overtakes, following
lane radar (8)                             → adaptive cruise ≤ limit    distance, smoothness,
speed, lane pos,                           direct: steer + throttle     speed limit, crashes
lat. vel, blinkers
```

**Sensors** (`AgentCar.sense`): 11 rays from −150° to +150° are cast against traffic and the road
edges. Each returns 0 for nothing in range and 1 for touching. The three forward rays also report
closing speed (a radar "doppler"). A camera-style detector reports when a neighbouring car's turn
signal points into your path.

The rays leave blind spots in the next lane (a car just behind your rear corner, or more than
~170 px back, is invisible to them), so a **lane radar** (`AgentCar.senseLanes`) adds 8 inputs:
the gap to the car ahead in your lane and how far below the limit it holds you; a *lane gain* for
the left and right lane (how much faster that lane would let you drive, −1 if there is no lane);
and the gap and closing speed of the nearest car behind in each neighbouring lane.

**Brain** (`NeuralNetwork`): a fixed-topology feed-forward net. All weights live in one flat array
(the genome), stored neuron by neuron.

**Control** (`ControlMode`):
- *Planner*: the network acts as a behaviour planner. It requests lane changes and sets a desired
  speed (0 up to the **120 km/h speed limit**, so it can't speed). Controllers carry out the plan,
  as in real autonomous-driving stacks:
  - a **lane-change safety gate** holds a requested change until the target lane has a big enough
    gap ahead and behind (and enough time-to-collision), and aborts a change whose gap closes up.
    The HUD's lane map outlines a held-back lane in amber.
  - **adaptive cruise control** (the IDM car-following law) settles at a gap of **one car length
    + 0.5 s × speed** to the car ahead (`Config.followGap`), about 2.5 car lengths at 60 km/h and
    4 at 120 km/h. It leaves extra room while closing in so it can stop in time.
  - lane-keeping steers to the centre of the target lane, with a 3 s pause between lane changes
    so the car can't weave.

  The network still decides *when* and *where* to change lanes and how fast to go. That's the
  part that has to be learned.
- *Direct*: end-to-end steering and throttle with none of the helpers above. It's harder to learn,
  so it's kept for comparison. Speeding is penalized in the score.

**Evolution** (`GeneticAlgorithm`): elitism, tournament selection and *neuron-level* crossover. A
child takes each neuron's whole weight set from one parent, so learned feature detectors aren't
scrambled. Gaussian mutation grows when progress stalls, and a few random immigrants are added
each generation for diversity.

**Fitness** (`AgentCar.fitness`):
`distance/100 + 2·overtakes + 10 if alive at 60 s − 1·lane changes`, minus penalties for:
- following closer than 75% of that gap (`TOO_CLOSE_FRACTION`), even at matched speed
- closing in too fast (time-to-collision under 1.2 s)
- harsh braking, twitchy steering, straddling lane lines
- driving over the speed limit (direct mode)
- seconds spent stuck behind a slow car while a clearly better lane was safely open. This is a
  coaching signal: the network never sees it, it only shapes the score.

A crash multiplies the score by 0.3 and stalling by 0.5. Because speed is capped, distance
rewards *keeping up a good pace*, and overtaking is a means to that, not a goal of its own.

**Traffic** (`Traffic`): cars follow the Intelligent Driver Model and change lanes after signalling.
About 75% are cars cruising at 94–119 km/h, and 25% are slow vehicles (61–79 km/h) worth
overtaking. Traffic reacts to the AI car: it brakes for it and won't merge into it.

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
6. **Fair comparisons.** Every AI car drives in its own copy of the traffic, built from the same
   seed, so every genome starts from the same scenario, and any seed reproduces an episode exactly.
   Each copy reacts only to its own AI car. The other AI cars are drawn as ghosts.

Problems that came up during tuning, and how they were fixed:
- With direct control and survival-heavy fitness, evolution got stuck on a "timid" driver that
  trails traffic forever and never overtakes. The fix was the planner control mode.
- Most remaining crashes were traffic merging into the car from the side. Adding turn-signal
  detection as an input gave the network a fair warning.
- With no speed limit and a big overtake bonus, champions drove at up to ~170 km/h and tailgated
  (under a 1 s gap ~90% of the time). The cause: traffic used to ignore the AI cars, so a car
  behind would drive straight through you, and the only safe spot was right on the bumper of the
  car ahead. Reactive traffic, the speed cap and the following-distance penalty fixed that.
- Champions then waited behind slow cars instead of passing. The fixes were: slow vehicles in the
  traffic mix (with evenly spread speeds, passing barely paid off), the lane radar's lane-gain
  inputs, the lane-change cooldown (without it, a near-miss policy weaves and its score
  collapses, so evolution never climbs toward good passing), adaptive cruise and the coaching
  term.

After ~80 generations, champions typically drive brand-new 60-second highways at ~100 km/h average
(never above 120), make ~13 overtakes and ~7 deliberate lane changes, follow at ~3 car lengths
(~0.75 s), and finished all 50 test highways without a crash. Results vary from run to run, as
they always do with a genetic algorithm.

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
- On-ramps, variable speed-limit zones (with the limit as a network input) and variable lane
  counts to stress generalization further
- Draw slow vehicles as trucks
- Replace the GA with policy-gradient RL (PPO) and compare sample efficiency
