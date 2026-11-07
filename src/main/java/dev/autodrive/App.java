package dev.autodrive;

import dev.autodrive.ai.BrainIO;
import dev.autodrive.ai.GeneticAlgorithm;
import dev.autodrive.ai.Genome;
import dev.autodrive.ai.NeuralNetwork;
import dev.autodrive.sim.AgentCar;
import dev.autodrive.sim.Config;
import dev.autodrive.sim.ControlMode;
import dev.autodrive.sim.World;
import dev.autodrive.train.Trainer;
import dev.autodrive.ui.ChartView;
import dev.autodrive.ui.NetworkView;
import dev.autodrive.ui.RoadRenderer;
import dev.autodrive.ui.SnapshotUtil;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.List;
import java.util.Random;

/** JavaFX front end: the highway view on the left, stats / network / controls on the right. */
public final class App extends Application {

    private static final double[] SPEEDS = {1, 3, 10, 30, Double.POSITIVE_INFINITY};
    private static final String[] SPEED_LABELS = {"1×", "3×", "10×", "30×", "Max"};

    private Trainer trainer;
    private final RoadRenderer road = new RoadRenderer();
    private final RoadRenderer.Options viewOptions = new RoadRenderer.Options();
    private final NetworkView networkView = new NetworkView();
    private final ChartView chartView = new ChartView();
    private Canvas roadCanvas, netCanvas, chartCanvas;
    private Stage stage;

    private double speed = 1;
    private boolean paused;
    private long lastFrame;
    private double accumulator;

    // demo ("watch the champion") state
    private boolean demo;
    private World demoWorld;
    private Genome demoGenome;
    private ControlMode demoMode;
    private double demoRestartIn = -1;
    private int demoRuns, demoCrashes;
    private double demoBestKm;
    private final Random demoSeeds = new Random();

    private AgentCar focus;
    private World focusWorld;

    private final Label genValue = value(), phaseValue = value(), aliveValue = value(), densityValue = value();
    private final Label bestValue = value(), avgValue = value(), championValue = value();
    private final Label distValue = value(), overtakeValue = value(), speedValue = value();
    private final Label densitySliderValue = new Label();
    private Button pauseButton, demoButton, saveButton;
    private ComboBox<ControlMode> modeBox;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        trainer = new Trainer(new GeneticAlgorithm.Params(), Long.getLong("autodrive.seed", System.nanoTime()));
        int pretrain = Integer.getInteger("autodrive.pretrain", 0);
        for (int i = 0; i < pretrain; i++) trainer.runGenerationBlocking();

        roadCanvas = new Canvas(900, 800);
        Pane roadPane = new Pane(roadCanvas);
        roadPane.setMinSize(0, 0);
        roadPane.setPrefSize(900, 800);
        roadCanvas.widthProperty().bind(roadPane.widthProperty());
        roadCanvas.heightProperty().bind(roadPane.heightProperty());

        ScrollPane side = new ScrollPane(buildPanel());
        side.setFitToWidth(true);
        side.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        side.getStyleClass().add("side");
        side.setPrefWidth(388);
        side.setMinWidth(388);

        BorderPane root = new BorderPane();
        root.setCenter(roadPane);
        root.setRight(side);

        Scene scene = new Scene(root, 1280, 820, Color.web("#0d1014"));
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) togglePause();
            else if (e.getCode() == KeyCode.D) toggleDemo();
            else if (e.getCode() == KeyCode.S) viewOptions.showSensors = !viewOptions.showSensors;
        });

        stage.setTitle("AutoDrive — Neuroevolution Highway Autopilot");
        stage.setScene(scene);
        stage.show();
        roadPane.requestFocus();

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                frame(now);
            }
        }.start();

        scheduleSnapshotIfRequested(scene);
    }

    // ---- Main loop ----------------------------------------------------------

    private void frame(long now) {
        double dt = lastFrame == 0 ? 1 / 60.0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;

        if (!paused) {
            long deadline = System.nanoTime() + 12_000_000L;   // keep the UI responsive
            int steps;
            if (Double.isInfinite(speed)) {
                steps = Integer.MAX_VALUE;
            } else {
                accumulator += dt * speed / Config.DT;
                steps = (int) accumulator;
                accumulator -= steps;
            }
            int done = demo ? stepDemo(steps, deadline) : trainer.tick(steps, deadline);
            if (done < steps) accumulator = 0;
        }

        World world = demo ? demoWorld : trainer.world();
        updateFocus(world);

        double w = roadCanvas.getWidth(), h = roadCanvas.getHeight();
        if (w > 0 && h > 0) road.render(roadCanvas.getGraphicsContext2D(), w, h, world, focus, viewOptions, hud(world), dt);
        NeuralNetwork net = focus != null ? focus.brain : null;
        ControlMode mode = demo ? demoMode : trainer.mode();
        networkView.render(netCanvas.getGraphicsContext2D(), netCanvas.getWidth(), netCanvas.getHeight(),
                net, AgentCar.INPUT_LABELS, AgentCar.outputLabels(mode));
        chartView.render(chartCanvas.getGraphicsContext2D(), chartCanvas.getWidth(), chartCanvas.getHeight(), trainer.history());
        updateLabels(world);
    }

    private void updateFocus(World w) {
        if (w != focusWorld) {
            focusWorld = w;
            focus = null;
        }
        AgentCar lead = w.leader();
        if (focus == null) {
            focus = lead != null ? lead : w.agents().get(0);
        } else if (lead != null && lead != focus && (!focus.alive || lead.y > focus.y + 150)) {
            focus = lead;   // sticky: only switch when clearly overtaken, so the camera doesn't jitter
        }
    }

    private RoadRenderer.Hud hud(World w) {
        if (demo) {
            String champ = trainer.championGeneration() > 0 ? "gen " + trainer.championGeneration() : "loaded brain";
            String banner = demoWorld.isOver() ? "Crashed — restarting on a new random highway" : null;
            return new RoadRenderer.Hud("AUTOPILOT DEMO · " + champ,
                    String.format("%.2f km · %d overtakes", focus.distance / Config.PX_PER_METER / 1000, focus.overtakes),
                    String.format("run %d · %d crashes · best %.2f km", demoRuns, demoCrashes, demoBestKm),
                    banner, null);
        }
        String line1 = String.format("Gen %d · %d / %d alive", trainer.generation(), w.aliveCount(), w.agents().size());
        String line2 = String.format("t = %.0f / %.0f s · density %.0f%%", w.time(), w.maxTime(), w.density() * 100);
        String banner = null, sub = null;
        if (trainer.phase() == Trainer.Phase.EVALUATING) {
            banner = trainer.extraScenarios() > 0
                    ? "Testing every brain on " + trainer.extraScenarios() + " unseen highways…"
                    : "Validating the best brains…";
            sub = "then selection, crossover and mutation build generation " + (trainer.generation() + 1);
        }
        return new RoadRenderer.Hud("TRAINING · " + trainer.mode().name().toLowerCase() + " control", line1, line2, banner, sub);
    }

    private int stepDemo(int steps, long deadline) {
        int done = 0;
        while (done < steps && System.nanoTime() < deadline) {
            if (demoWorld.isOver()) {
                if (demoRestartIn < 0) {
                    demoRestartIn = 1.5;
                    AgentCar a = demoWorld.agents().get(0);
                    if (a.crashed || a.stalled) demoCrashes++;
                }
                demoRestartIn -= Config.DT;
                if (demoRestartIn <= 0) newDemoWorld();
            } else {
                demoWorld.step();
                AgentCar a = demoWorld.agents().get(0);
                demoBestKm = Math.max(demoBestKm, a.distance / Config.PX_PER_METER / 1000);
            }
            done++;
        }
        return done;
    }

    private void newDemoWorld() {
        demoRuns++;
        demoRestartIn = -1;
        demoWorld = Trainer.createWorld(List.of(demoGenome), demoSeeds.nextLong(), trainer.densityScale(),
                trainer.isLaneChanges(), 0, Double.POSITIVE_INFINITY, true, demoMode);
    }

    // ---- Actions ------------------------------------------------------------

    private void togglePause() {
        paused = !paused;
        pauseButton.setText(paused ? "▶  Resume" : "❚❚  Pause");
    }

    private void toggleDemo() {
        if (!demo && trainer.champion() == null) return;
        demo = !demo;
        if (demo) {
            demoGenome = trainer.champion().copy();
            demoMode = trainer.mode();
            demoRuns = 0;
            demoCrashes = 0;
            demoBestKm = 0;
            newDemoWorld();
        }
        demoButton.setText(demo ? "■  Back to training" : "▶  Watch champion drive");
        demoButton.getStyleClass().remove("primary");
        if (!demo) demoButton.getStyleClass().add("primary");
    }

    private void resetTraining(ControlMode mode) {
        if (demo) toggleDemo();
        trainer.reset(System.nanoTime(), mode);
        focus = null;
    }

    private void saveBrain() {
        if (trainer.champion() == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Save champion brain");
        fc.setInitialFileName("champion-gen" + trainer.championGeneration() + ".brain");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("AutoDrive brain", "*.brain"));
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            BrainIO.save(f.toPath(), trainer.mode().name(), AgentCar.TOPOLOGY, trainer.champion().genes(),
                    String.format("AutoDrive champion, generation %d, validation score %.1f",
                            trainer.championGeneration(), trainer.championScore()));
        } catch (Exception ex) {
            error("Could not save brain", ex.getMessage());
        }
    }

    private void loadBrain() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Load brain");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("AutoDrive brain", "*.brain"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            BrainIO.Brain b = BrainIO.load(f.toPath());
            if (!java.util.Arrays.equals(b.topology(), AgentCar.TOPOLOGY)) {
                error("Incompatible brain", "This brain's network shape doesn't match the current sensors.");
                return;
            }
            ControlMode mode = b.mode() != null ? ControlMode.valueOf(b.mode()) : ControlMode.PLANNER;
            if (demo) toggleDemo();
            trainer.importBrain(b.weights(), mode);
            modeBox.setValue(mode);
            focus = null;
        } catch (Exception ex) {
            error("Could not load brain", ex.getMessage());
        }
    }

    private void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg);
        a.setHeaderText(title);
        a.initOwner(stage);
        a.showAndWait();
    }

    // ---- Panel --------------------------------------------------------------

    private VBox buildPanel() {
        Label title = new Label("AutoDrive");
        title.getStyleClass().add("title");
        Label subtitle = new Label("Neural network + genetic algorithm learning to drive a 3-lane highway");
        subtitle.getStyleClass().add("subtitle");
        subtitle.setWrapText(true);

        GridPane stats = grid();
        row(stats, 0, "Generation", genValue, "Phase", phaseValue);
        row(stats, 1, "Alive", aliveValue, "Traffic", densityValue);
        row(stats, 2, "Gen best", bestValue, "Gen avg", avgValue);
        Label champKey = key("Champion");
        Tooltip.install(champKey, new Tooltip("Best score on 10 held-out validation highways it never trains on"));
        stats.add(champKey, 0, 3);
        stats.add(championValue, 1, 3, 3, 1);

        GridPane car = grid();
        row(car, 0, "Speed", speedValue, "Distance", distValue);
        row(car, 1, "Overtakes", overtakeValue, "", new Label());

        chartCanvas = new Canvas(340, 150);
        HBox legend = new HBox(14, legendItem("best", "#4cc9f0"), legendItem("average", "#64748b"), legendItem("validation", "#5fd38d"));
        legend.setAlignment(Pos.CENTER_LEFT);

        netCanvas = new Canvas(340, 330);

        pauseButton = button("❚❚  Pause", e -> togglePause());
        HBox speeds = new HBox(4);
        ToggleGroup tg = new ToggleGroup();
        for (int i = 0; i < SPEEDS.length; i++) {
            ToggleButton tb = new ToggleButton(SPEED_LABELS[i]);
            double sp = SPEEDS[i];
            tb.setToggleGroup(tg);
            tb.setOnAction(e -> {
                speed = sp;
                if (tg.getSelectedToggle() == null) tb.setSelected(true);
            });
            tb.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tb, Priority.ALWAYS);
            speeds.getChildren().add(tb);
        }
        int initial = Math.max(0, Math.min(SPEEDS.length - 1, Integer.getInteger("autodrive.speedIndex", 0)));
        speed = SPEEDS[initial];
        tg.selectToggle(tg.getToggles().get(initial));
        HBox playback = new HBox(8, pauseButton, speeds);
        HBox.setHgrow(speeds, Priority.ALWAYS);

        modeBox = new ComboBox<>();
        modeBox.getItems().addAll(ControlMode.values());
        modeBox.setValue(trainer.mode());
        modeBox.setMaxWidth(Double.MAX_VALUE);
        modeBox.setOnAction(e -> {
            if (modeBox.getValue() != trainer.mode()) resetTraining(modeBox.getValue());
        });
        Tooltip.install(modeBox, new Tooltip("Changing the control mode restarts training"));

        CheckBox robust = check("Robust training (score on 4 extra unseen highways)", trainer.isRobust(),
                v -> trainer.setRobust(v));
        CheckBox laneChanges = check("Traffic changes lanes (with blinkers)", trainer.isLaneChanges(),
                v -> trainer.setLaneChanges(v));
        Slider density = new Slider(0.5, 1.4, trainer.densityScale());
        density.setBlockIncrement(0.1);
        density.valueProperty().addListener((o, a, b) -> {
            trainer.setDensityScale(b.doubleValue());
            densitySliderValue.setText(String.format("%.0f%%", b.doubleValue() * 100));
        });
        densitySliderValue.setText(String.format("%.0f%%", trainer.densityScale() * 100));
        densitySliderValue.getStyleClass().add("value");
        Label densityLabel = new Label("Traffic density");
        HBox densityRow = new HBox(8, densityLabel, density, densitySliderValue);
        densityRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(density, Priority.ALWAYS);

        CheckBox sensors = check("Show sensor rays", viewOptions.showSensors, v -> viewOptions.showSensors = v);
        CheckBox ghosts = check("Show whole population", viewOptions.showGhosts, v -> viewOptions.showGhosts = v);

        demoButton = button("▶  Watch champion drive", e -> toggleDemo());
        demoButton.getStyleClass().add("primary");
        demoButton.setMaxWidth(Double.MAX_VALUE);
        saveButton = button("Save brain…", e -> saveBrain());
        Button load = button("Load brain…", e -> loadBrain());
        Button reset = button("Reset", e -> resetTraining(trainer.mode()));
        HBox files = new HBox(6, saveButton, load, reset);
        for (Node n : files.getChildren()) {
            ((Region) n).setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(n, Priority.ALWAYS);
        }

        Label keys = new Label("Space pause · D demo · S sensors");
        keys.getStyleClass().add("subtitle");

        VBox panel = new VBox(12,
                new VBox(3, title, subtitle),
                card("Training", stats),
                card("Followed car", car),
                card("Fitness per generation", new VBox(8, legend, chartCanvas)),
                card("Neural network · live activations", netCanvas),
                card("Simulation", new VBox(10, playback, modeBox, robust, laneChanges, densityRow, sensors, ghosts)),
                card("Champion", new VBox(8, demoButton, files)),
                keys);
        panel.getStyleClass().add("panel");
        return panel;
    }

    private void updateLabels(World w) {
        List<Trainer.GenerationStats> hist = trainer.history();
        genValue.setText(Integer.toString(trainer.generation()));
        phaseValue.setText(demo ? "Demo" : trainer.phase() == Trainer.Phase.DRIVING ? "Driving" : "Evaluating");
        aliveValue.setText(w.aliveCount() + " / " + w.agents().size());
        densityValue.setText(String.format("%.0f%%", w.density() * 100));
        if (!hist.isEmpty()) {
            Trainer.GenerationStats last = hist.get(hist.size() - 1);
            bestValue.setText(String.format("%.0f", last.best()));
            avgValue.setText(String.format("%.0f", last.average()));
        } else {
            bestValue.setText("–");
            avgValue.setText("–");
        }
        if (trainer.champion() != null) {
            String from = trainer.championGeneration() > 0 ? "gen " + trainer.championGeneration() : "loaded";
            championValue.setText(String.format("%.0f  (%s)", trainer.championScore(), from));
        } else {
            championValue.setText("none yet");
        }
        if (focus != null) {
            speedValue.setText(String.format("%.0f km/h", focus.speedKmh()));
            distValue.setText(String.format("%.2f km", focus.distance / Config.PX_PER_METER / 1000));
            overtakeValue.setText(Integer.toString(focus.overtakes));
        }
        demoButton.setDisable(!demo && trainer.champion() == null);
        saveButton.setDisable(trainer.champion() == null);
    }

    // ---- Small UI helpers ---------------------------------------------------

    private static VBox card(String title, Node content) {
        Label t = new Label(title.toUpperCase());
        t.getStyleClass().add("card-title");
        VBox box = new VBox(10, t, content);
        box.getStyleClass().add("card");
        return box;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(6);
        g.setPadding(new Insets(0));
        javafx.scene.layout.ColumnConstraints k = new javafx.scene.layout.ColumnConstraints(78);
        javafx.scene.layout.ColumnConstraints v = new javafx.scene.layout.ColumnConstraints(84);
        g.getColumnConstraints().addAll(k, v, k, v);
        return g;
    }

    private static void row(GridPane g, int r, String k1, Label v1, String k2, Label v2) {
        g.add(key(k1), 0, r);
        g.add(v1, 1, r);
        g.add(key(k2), 2, r);
        g.add(v2, 3, r);
    }

    private static Label key(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("key");
        return l;
    }

    private static Label value() {
        Label l = new Label("–");
        l.getStyleClass().add("value");
        return l;
    }

    private static HBox legendItem(String name, String color) {
        Circle c = new Circle(4, Color.web(color));
        Label l = new Label(name);
        l.getStyleClass().add("key");
        HBox h = new HBox(6, c, l);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Button button(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button b = new Button(text);
        b.setOnAction(handler);
        b.setFocusTraversable(false);
        return b;
    }

    private static CheckBox check(String text, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        CheckBox c = new CheckBox(text);
        c.setSelected(initial);
        c.setFocusTraversable(false);
        c.selectedProperty().addListener((o, a, b) -> onChange.accept(b));
        return c;
    }

    /** Dev hook: -Dautodrive.snapshot=out.png [-Dautodrive.snapshotDelay=6] saves a screenshot and exits. */
    private void scheduleSnapshotIfRequested(Scene scene) {
        String path = System.getProperty("autodrive.snapshot");
        if (path == null) return;
        if (Boolean.getBoolean("autodrive.snapshotDemo") && trainer.champion() != null) toggleDemo();
        double delay = Double.parseDouble(System.getProperty("autodrive.snapshotDelay", "6"));
        PauseTransition pt = new PauseTransition(Duration.seconds(delay));
        pt.setOnFinished(e -> {
            try {
                SnapshotUtil.savePng(scene.snapshot(null), new File(path));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            Platform.exit();
        });
        pt.play();
    }
}
