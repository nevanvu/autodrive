package dev.autodrive.ui;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/** Shared colours and fonts for everything drawn on canvases. */
final class Theme {
    private Theme() {}

    static final Color BG = Color.web("#0d1014");
    static final Color CARD = Color.web("#161c24");
    static final Color GRID = Color.web("#232c37");
    static final Color TEXT = Color.web("#e6edf5");
    static final Color MUTED = Color.web("#7d8a99");

    static final Color ACCENT = Color.web("#4cc9f0");     // AI / positive
    static final Color WARM = Color.web("#f4a261");       // negative weights
    static final Color GOOD = Color.web("#5fd38d");
    static final Color DANGER = Color.web("#ff5d5d");

    static final Color GRASS = Color.web("#142019");
    static final Color GRASS_BAND = Color.web("#17251c");
    static final Color SHOULDER = Color.web("#2f3338");
    static final Color ASPHALT = Color.web("#23262b");
    static final Color LINE = Color.web("#e8ecf1");
    static final Color RAIL = Color.web("#7d8792");
    static final Color GLASS = Color.web("#11161d");

    static final Color EV_BODY = Color.web("#f2f6fb");
    static final Color GHOST = Color.web("#4cc9f0");

    static final Color[] TRAFFIC = {
            Color.web("#d94f4f"), Color.web("#e3a33b"), Color.web("#4f7fd9"), Color.web("#58b368"),
            Color.web("#9b6ad6"), Color.web("#c9ced6"), Color.web("#3f4a5a"), Color.web("#c96f3a"),
            Color.web("#2f7f9f"), Color.web("#b8324f")
    };

    static final String FAMILY = "Helvetica Neue";

    static Font font(double size) {
        return Font.font(FAMILY, FontWeight.NORMAL, size);
    }

    static Font bold(double size) {
        return Font.font(FAMILY, FontWeight.BOLD, size);
    }

    static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return a.interpolate(b, t);
    }
}
