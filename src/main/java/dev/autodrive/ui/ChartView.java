package dev.autodrive.ui;

import dev.autodrive.train.Trainer.GenerationStats;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.function.ToDoubleFunction;

/** Fitness-per-generation line chart: best, average and held-out validation score. */
public final class ChartView {

    public void render(GraphicsContext g, double w, double h, List<GenerationStats> hist) {
        g.setFill(Theme.CARD);
        g.fillRect(0, 0, w, h);
        double l = 34, r = 10, t = 10, b = 20;
        double pw = w - l - r, ph = h - t - b;

        g.setTextBaseline(VPos.CENTER);
        g.setFont(Theme.font(10));
        if (hist.isEmpty()) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(Theme.MUTED);
            g.fillText("Chart fills in after the first generation", w / 2, h / 2);
            return;
        }

        double max = 10;
        for (GenerationStats s : hist) max = Math.max(max, Math.max(s.best(), s.validation()));
        max = niceCeil(max * 1.08);

        g.setTextAlign(TextAlignment.RIGHT);
        g.setLineWidth(1);
        for (int i = 0; i <= 4; i++) {
            double v = max * i / 4, y = t + ph - ph * i / 4;
            g.setStroke(Theme.GRID);
            g.strokeLine(l, y, l + pw, y);
            g.setFill(Theme.MUTED);
            g.fillText(String.format("%.0f", v), l - 5, y);
        }

        int n = hist.size();
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("gen " + hist.get(0).generation(), l, h - 8);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("gen " + hist.get(n - 1).generation(), l + pw, h - 8);

        series(g, hist, GenerationStats::average, Color.web("#64748b"), 1.4, l, t, pw, ph, max);
        series(g, hist, GenerationStats::validation, Theme.GOOD, 1.8, l, t, pw, ph, max);
        series(g, hist, GenerationStats::best, Theme.ACCENT, 2.0, l, t, pw, ph, max);
    }

    private void series(GraphicsContext g, List<GenerationStats> hist, ToDoubleFunction<GenerationStats> f,
                        Color c, double width, double l, double t, double pw, double ph, double max) {
        int n = hist.size();
        g.setStroke(c);
        g.setFill(c);
        g.setLineWidth(width);
        double px = 0, py = 0;
        for (int i = 0; i < n; i++) {
            double x = l + (n == 1 ? pw / 2 : pw * i / (n - 1));
            double y = t + ph - ph * Math.max(0, f.applyAsDouble(hist.get(i))) / max;
            if (i > 0) g.strokeLine(px, py, x, y);
            px = x;
            py = y;
        }
        g.fillOval(px - 3, py - 3, 6, 6);
    }

    private static double niceCeil(double v) {
        double mag = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[]{1, 2, 2.5, 5, 10}) if (m * mag >= v) return m * mag;
        return 10 * mag;
    }
}
