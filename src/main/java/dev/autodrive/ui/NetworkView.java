package dev.autodrive.ui;

import dev.autodrive.ai.NeuralNetwork;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/** Live drawing of a network: edges coloured by weight sign, brightness by signal flowing through. */
public final class NetworkView {

    public void render(GraphicsContext g, double w, double h, NeuralNetwork net, String[] inLabels, String[] outLabels) {
        g.setFill(Theme.CARD);
        g.fillRect(0, 0, w, h);
        if (net == null) return;

        int[] layers = net.layers();
        double[][] act = net.activations();
        double[] wt = net.weights();
        double left = 62, right = w - 74, top = 8, bottom = h - 8;

        double[][] nx = new double[layers.length][];
        double[][] ny = new double[layers.length][];
        for (int l = 0; l < layers.length; l++) {
            int n = layers[l];
            nx[l] = new double[n];
            ny[l] = new double[n];
            double maxSpacing = l == layers.length - 1 ? 64 : 20;   // outputs need room for labels
            double spacing = Math.min(maxSpacing, (bottom - top) / Math.max(1, n - 1));
            double y0 = (top + bottom) / 2 - spacing * (n - 1) / 2;
            double x = left + (right - left) * l / (layers.length - 1);
            for (int i = 0; i < n; i++) {
                nx[l][i] = x;
                ny[l][i] = y0 + i * spacing;
            }
        }

        int k = 0;
        for (int l = 1; l < layers.length; l++) {
            for (int j = 0; j < layers[l]; j++) {
                for (int i = 0; i < layers[l - 1]; i++) {
                    double wv = wt[k++];
                    double strength = Math.abs(wv * act[l - 1][i]);
                    double alpha = Math.max(0.035, Math.min(0.85, 0.035 + strength * 0.4));
                    Color c = wv >= 0 ? Theme.ACCENT : Theme.WARM;
                    g.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
                    g.setLineWidth(0.5 + Math.min(1.8, strength * 0.9));
                    g.strokeLine(nx[l - 1][i], ny[l - 1][i], nx[l][j], ny[l][j]);
                }
                k++; // bias
            }
        }

        for (int l = 0; l < layers.length; l++) {
            for (int i = 0; i < layers[l]; i++) {
                double a = act[l][i];
                Color c = Theme.mix(Color.web("#1c242e"), a >= 0 ? Theme.ACCENT : Theme.WARM, Math.abs(a));
                double r = l == layers.length - 1 ? 6 : 4.5;
                g.setFill(c);
                g.fillOval(nx[l][i] - r, ny[l][i] - r, r * 2, r * 2);
                g.setStroke(Color.rgb(255, 255, 255, 0.18));
                g.setLineWidth(1);
                g.strokeOval(nx[l][i] - r, ny[l][i] - r, r * 2, r * 2);
            }
        }

        g.setTextBaseline(VPos.CENTER);
        g.setFont(Theme.font(9.5));
        g.setTextAlign(TextAlignment.RIGHT);
        for (int i = 0; i < layers[0]; i++) {
            g.setFill(Theme.mix(Theme.MUTED, Theme.TEXT, Math.abs(act[0][i])));
            g.fillText(inLabels[i], nx[0][i] - 8, ny[0][i]);
        }
        g.setTextAlign(TextAlignment.LEFT);
        int last = layers.length - 1;
        for (int i = 0; i < layers[last]; i++) {
            g.setFill(Theme.TEXT);
            g.setFont(Theme.bold(10.5));
            g.fillText(outLabels[i], nx[last][i] + 11, ny[last][i] - 6);
            g.setFill(act[last][i] >= 0 ? Theme.ACCENT : Theme.WARM);
            g.setFont(Theme.font(10.5));
            g.fillText(String.format("%+.2f", act[last][i]), nx[last][i] + 11, ny[last][i] + 7);
        }
    }
}
