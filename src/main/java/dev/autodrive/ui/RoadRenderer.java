package dev.autodrive.ui;

import dev.autodrive.sim.AgentCar;
import dev.autodrive.sim.Config;
import dev.autodrive.sim.Traffic;
import dev.autodrive.sim.TrafficCar;
import dev.autodrive.sim.World;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import static dev.autodrive.sim.Car.L;
import static dev.autodrive.sim.Car.W;
import static dev.autodrive.sim.Config.ROAD_WIDTH;

/** Draws the highway, traffic, AI cars and sensor rays, with a camera that follows one car. */
public final class RoadRenderer {

    public static final class Options {
        public boolean showSensors = true;
        public boolean showGhosts = true;
    }

    /** Text shown in the heads-up display. */
    public record Hud(String mode, String line1, String line2, String banner, String bannerSub) {}

    private World lastWorld;
    private AgentCar lastFocus;
    private double camY;
    private double camOffset;

    // per-frame projection
    private double s, roadLeft, anchorY, viewH;

    public void render(GraphicsContext g, double w, double h, World world, AgentCar focus,
                       Options opt, Hud hud, double frameDt) {
        s = Math.max(0.75, Math.min(2.2, Math.min(h / 640.0, w / 430.0)));
        anchorY = h * 0.70;
        viewH = h;
        roadLeft = Math.round(w / 2 - ROAD_WIDTH * s / 2);

        updateCamera(world, focus, frameDt);
        double top = camY + anchorY / s;            // world y at top of screen
        double bottom = camY - (h - anchorY) / s;   // world y at bottom of screen

        drawGround(g, w, h, bottom, top);
        drawRoad(g, h, bottom, top);

        boolean blinkOn = ((int) (world.time() * 3.2)) % 2 == 0;
        Traffic traffic = world.traffic(focus);
        for (int i = traffic.lowerBound(bottom - L), end = traffic.lowerBound(top + L); i < end; i++) {
            TrafficCar c = traffic.get(i);
            double angle = c.lane != c.targetLane ? Math.signum(c.targetLane - c.lane) * 0.12 : 0;
            drawCar(g, sx(c.x), sy(c.y), angle, Theme.TRAFFIC[c.colorIndex], 1, c.braking, c.signal, blinkOn, false);
        }

        double now = world.time();
        for (AgentCar a : world.agents()) {
            if (a == focus || a.y < bottom - L || a.y > top + L) continue;
            if (a.alive) {
                if (opt.showGhosts) drawGhost(g, sx(a.x), sy(a.y), a.heading, Theme.GHOST, 0.22);
            } else if (a.crashed) {
                double age = now - a.deathTime;
                if (age < 1.2) drawGhost(g, sx(a.x), sy(a.y), a.heading, Theme.DANGER, 0.55 * (1 - age / 1.2));
            }
        }

        if (focus != null) {
            if (opt.showSensors && focus.alive) drawSensors(g, focus);
            if (focus.alive) drawHalo(g, sx(focus.x), sy(focus.y));
            drawCar(g, sx(focus.x), sy(focus.y), focus.heading,
                    focus.alive ? Theme.EV_BODY : Theme.DANGER.interpolate(Theme.EV_BODY, 0.4),
                    1, focus.throttle < -0.15, 0, false, true);
        }

        drawHud(g, w, h, focus, hud);
    }

    // ---- Camera -------------------------------------------------------------

    private void updateCamera(World world, AgentCar focus, double dt) {
        double target = focus != null ? focus.y : 0;
        if (world != lastWorld) {
            lastWorld = world;
            camOffset = 0;
        } else if (focus != lastFocus && lastFocus != null) {
            camOffset = camY - target;              // glide to the newly followed car
        }
        lastFocus = focus;
        camOffset *= Math.exp(-dt * 5);
        if (Math.abs(camOffset) > 3000) camOffset = 0;
        camY = target + camOffset;
    }

    private double sx(double x) { return roadLeft + x * s; }
    private double sy(double y) { return anchorY - (y - camY) * s; }

    // ---- Scenery ------------------------------------------------------------

    private void drawGround(GraphicsContext g, double w, double h, double bottom, double top) {
        g.setFill(Theme.GRASS);
        g.fillRect(0, 0, w, h);

        double band = 160;
        g.setFill(Theme.GRASS_BAND);
        for (long k = (long) Math.floor(bottom / band); k * band < top; k++) {
            if ((k & 1) == 0) {
                double y0 = sy((k + 1) * band), y1 = sy(k * band);
                g.fillRect(0, y0, w, y1 - y0);
            }
        }

        // Trees and bushes, placed by a hash of the tile index so they scroll with the world.
        double tile = 70;
        for (long k = (long) Math.floor((bottom - 60) / tile); k * tile < top + 60; k++) {
            for (int side = 0; side < 2; side++) {
                long hsh = mix(k * 2 + side);
                if ((hsh & 7) >= 3) continue;
                double offset = 48 + ((hsh >>> 3) & 255) / 255.0 * 170;
                double wx = side == 0 ? -offset : ROAD_WIDTH + offset;
                double wy = k * tile + ((hsh >>> 12) & 63);
                double px = sx(wx), py = sy(wy);
                if (px < -40 || px > w + 40) continue;
                double r = (9 + ((hsh >>> 20) & 7)) * s;
                boolean bush = ((hsh >>> 24) & 3) == 0;
                if (bush) r *= 0.6;
                g.setFill(Color.rgb(0, 0, 0, 0.30));
                g.fillOval(px - r + 4 * s, py - r + 5 * s, r * 2, r * 2);
                g.setFill(bush ? Color.web("#24432c") : Color.web("#1d3a26"));
                g.fillOval(px - r, py - r, r * 2, r * 2);
                g.setFill(bush ? Color.web("#2f5a39") : Color.web("#28503a"));
                g.fillOval(px - r * 0.65, py - r * 0.75, r * 1.1, r * 1.1);
            }
        }
    }

    private void drawRoad(GraphicsContext g, double h, double bottom, double top) {
        double sh = 16;
        g.setFill(Theme.SHOULDER);
        g.fillRect(sx(-sh), 0, (ROAD_WIDTH + 2 * sh) * s, h);
        g.setFill(Theme.ASPHALT);
        g.fillRect(sx(0), 0, ROAD_WIDTH * s, h);

        // Guard rails with posts
        g.setStroke(Theme.RAIL);
        g.setLineWidth(Math.max(1.5, 2 * s));
        g.strokeLine(sx(-sh - 4), 0, sx(-sh - 4), h);
        g.strokeLine(sx(ROAD_WIDTH + sh + 4), 0, sx(ROAD_WIDTH + sh + 4), h);
        g.setFill(Theme.RAIL.darker());
        double post = 50;
        for (double y = Math.floor(bottom / post) * post; y < top; y += post) {
            double py = sy(y);
            g.fillRect(sx(-sh - 6), py - 1.5 * s, 4 * s, 3 * s);
            g.fillRect(sx(ROAD_WIDTH + sh + 2), py - 1.5 * s, 4 * s, 3 * s);
        }

        // Solid edge lines
        g.setFill(Theme.LINE);
        double lw = Math.max(2, 2.6 * s);
        g.fillRect(sx(3) - lw / 2, 0, lw, h);
        g.fillRect(sx(ROAD_WIDTH - 3) - lw / 2, 0, lw, h);

        // Dashed lane lines
        double dash = 30, period = 72;
        g.setFill(Color.rgb(232, 236, 241, 0.85));
        for (int lane = 1; lane < Config.LANES; lane++) {
            double x = sx(lane * Config.LANE_WIDTH) - lw / 2;
            for (double y = Math.floor(bottom / period) * period; y < top; y += period) {
                double y0 = sy(y + dash), y1 = sy(y);
                g.fillRect(x, y0, lw, y1 - y0);
            }
        }
    }

    // ---- Cars ---------------------------------------------------------------

    private void drawCar(GraphicsContext g, double cx, double cy, double angle, Color body, double alpha,
                         boolean braking, int signal, boolean blinkOn, boolean ev) {
        double w = W * s, l = L * s;
        g.save();
        g.translate(cx, cy);
        g.rotate(Math.toDegrees(angle));
        g.setGlobalAlpha(alpha);

        if (braking) {                                    // brake-light glow on the road
            g.setFill(Color.rgb(255, 40, 40, 0.18));
            g.fillOval(-w * 0.8, l * 0.35, w * 1.6, l * 0.5);
        }
        g.setFill(Color.rgb(0, 0, 0, 0.38));
        g.fillRoundRect(-w / 2 + 2.5 * s, -l / 2 + 3.5 * s, w, l, w * 0.55, w * 0.55);

        g.setFill(body);
        g.fillRoundRect(-w / 2, -l / 2, w, l, w * 0.55, w * 0.55);
        g.setFill(body.deriveColor(0, 1, 0.82, 1));       // side shading
        g.fillRoundRect(-w / 2, -l * 0.22, w * 0.1, l * 0.5, 3 * s, 3 * s);
        g.fillRoundRect(w / 2 - w * 0.1, -l * 0.22, w * 0.1, l * 0.5, 3 * s, 3 * s);

        g.setFill(Theme.GLASS);                            // windshield
        g.fillRoundRect(-w * 0.38, -l * 0.27, w * 0.76, l * 0.17, 4 * s, 4 * s);
        g.setFill(body.deriveColor(0, ev ? 0.6 : 1, ev ? 0.93 : 0.88, 1));  // roof
        g.fillRoundRect(-w * 0.35, -l * 0.09, w * 0.70, l * 0.31, 5 * s, 5 * s);
        g.setFill(Theme.GLASS);                            // rear window
        g.fillRoundRect(-w * 0.33, l * 0.24, w * 0.66, l * 0.1, 3 * s, 3 * s);

        if (ev) {                                          // lidar puck + accent stripe
            g.setFill(Theme.ACCENT);
            g.fillRect(-0.8 * s, -l / 2 + 2 * s, 1.6 * s, l * 0.2);
            g.setFill(Color.web("#1a2129"));
            g.fillOval(-3.6 * s, l * 0.03, 7.2 * s, 7.2 * s);
            g.setFill(Theme.ACCENT);
            g.fillOval(-1.6 * s, l * 0.03 + 2 * s, 3.2 * s, 3.2 * s);
        }

        g.setFill(Color.web("#fff6d5"));                   // headlights
        g.fillRoundRect(-w * 0.42, -l / 2 + 1.2 * s, w * 0.24, 2.8 * s, 2 * s, 2 * s);
        g.fillRoundRect(w * 0.18, -l / 2 + 1.2 * s, w * 0.24, 2.8 * s, 2 * s, 2 * s);
        g.setFill(braking ? Color.web("#ff3434") : Color.web("#7d1d1d"));   // tail lights
        g.fillRect(-w * 0.42, l / 2 - 3.8 * s, w * 0.24, 2.6 * s);
        g.fillRect(w * 0.18, l / 2 - 3.8 * s, w * 0.24, 2.6 * s);

        if (signal != 0 && blinkOn) {
            g.setFill(Color.web("#ffb020"));
            double bx = signal < 0 ? -w / 2 - 1 * s : w / 2 - 3 * s;
            g.fillOval(bx, -l / 2 + 1 * s, 4 * s, 4 * s);
            g.fillOval(bx, l / 2 - 5 * s, 4 * s, 4 * s);
            g.setFill(Color.rgb(255, 176, 32, 0.25));
            g.fillOval(bx - 4 * s, -l / 2 - 3 * s, 12 * s, 12 * s);
            g.fillOval(bx - 4 * s, l / 2 - 9 * s, 12 * s, 12 * s);
        }
        g.restore();
    }

    private void drawGhost(GraphicsContext g, double cx, double cy, double angle, Color color, double alpha) {
        double w = W * s, l = L * s;
        g.save();
        g.translate(cx, cy);
        g.rotate(Math.toDegrees(angle));
        g.setGlobalAlpha(alpha);
        g.setFill(color);
        g.fillRoundRect(-w / 2, -l / 2, w, l, w * 0.55, w * 0.55);
        g.setGlobalAlpha(Math.min(1, alpha * 2.2));
        g.setStroke(color);
        g.setLineWidth(1);
        g.strokeRoundRect(-w / 2, -l / 2, w, l, w * 0.55, w * 0.55);
        g.restore();
    }

    private void drawHalo(GraphicsContext g, double cx, double cy) {
        double r = L * s * 0.95;
        for (int i = 3; i >= 1; i--) {
            g.setFill(Color.rgb(76, 201, 240, 0.045 * i));
            double rr = r * (0.6 + i * 0.22);
            g.fillOval(cx - rr * 0.75, cy - rr, rr * 1.5, rr * 2);
        }
    }

    private void drawSensors(GraphicsContext g, AgentCar a) {
        double cx = sx(a.x), cy = sy(a.y);
        g.setLineWidth(Math.max(1, 1.3 * s));
        for (int r = 0; r < AgentCar.RAYS; r++) {
            double ang = Config.SENSOR_ANGLES[r] + a.heading;
            double d = a.hitDistance[r];
            double ex = sx(a.x + Math.sin(ang) * d), ey = sy(a.y + Math.cos(ang) * d);
            double v = a.readings[r];
            boolean hit = d < Config.SENSOR_RANGE - 0.5;
            Color c = hit ? Color.hsb(130 * (1 - v), 0.85, 1.0, 0.85) : Color.rgb(76, 201, 240, 0.16);
            g.setStroke(c);
            g.strokeLine(cx, cy, ex, ey);
            if (hit) {
                g.setFill(c);
                double rr = 3.2 * s;
                g.fillOval(ex - rr, ey - rr, rr * 2, rr * 2);
            }
        }
    }

    // ---- HUD ----------------------------------------------------------------

    private void drawHud(GraphicsContext g, double w, double h, AgentCar focus, Hud hud) {
        if (hud == null) return;
        double x = 18, y = 18, cw = 230, ch = 112;
        g.setFill(Color.rgb(13, 16, 20, 0.78));
        g.fillRoundRect(x, y, cw, ch, 14, 14);
        g.setStroke(Color.rgb(76, 201, 240, 0.25));
        g.setLineWidth(1);
        g.strokeRoundRect(x + 0.5, y + 0.5, cw - 1, ch - 1, 14, 14);

        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        g.setFill(Theme.ACCENT);
        g.setFont(Theme.bold(11));
        g.fillText(hud.mode(), x + 14, y + 12);

        String speed = focus == null ? "--" : String.format("%.0f", focus.speedKmh());
        boolean speeding = focus != null && focus.speedKmh() > Config.SPEED_LIMIT_KMH + 0.5;
        g.setFill(speeding ? Theme.DANGER : Theme.TEXT);
        g.setFont(Theme.bold(38));
        g.fillText(speed, x + 12, y + 26);
        g.setFont(Theme.font(13));
        g.setFill(Theme.MUTED);
        g.fillText("km/h", x + 16 + speed.length() * 22, y + 45);
        drawLimitSign(g, x + 140, y + 46, 16);

        g.setFont(Theme.font(12));
        g.setFill(Theme.TEXT);
        g.fillText(hud.line1(), x + 14, y + 74);
        g.setFill(Theme.MUTED);
        g.fillText(hud.line2(), x + 14, y + 91);

        if (focus != null && focus.alive) drawLaneIndicator(g, x + cw - 64, y + 30, focus);

        if (hud.banner() != null) {
            double bw = Math.min(w - 40, 470), bh = hud.bannerSub() != null ? 58 : 38;
            double bx = (w - bw) / 2, by = h - bh - 26;
            g.setFill(Color.rgb(13, 16, 20, 0.85));
            g.fillRoundRect(bx, by, bw, bh, 12, 12);
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(Theme.TEXT);
            g.setFont(Theme.bold(14));
            g.fillText(hud.banner(), w / 2, by + 11);
            if (hud.bannerSub() != null) {
                g.setFill(Theme.MUTED);
                g.setFont(Theme.font(12));
                g.fillText(hud.bannerSub(), w / 2, by + 33);
            }
            g.setTextAlign(TextAlignment.LEFT);
        }
    }

    /** Round speed-limit sign: red ring, white face, the limit in km/h. */
    private void drawLimitSign(GraphicsContext g, double cx, double cy, double r) {
        g.setFill(Color.web("#e5484d"));
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        double inner = r * 0.74;
        g.setFill(Color.WHITE);
        g.fillOval(cx - inner, cy - inner, inner * 2, inner * 2);
        g.setFill(Color.web("#111418"));
        g.setFont(Theme.bold(11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(String.format("%.0f", Config.SPEED_LIMIT_KMH), cx, cy + 0.5);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
    }

    /**
     * Tiny top-down lane map: which lane we're in and where the planner is heading. A lane the
     * planner wants but the safety gate is holding back (no safe gap yet) gets an amber outline.
     */
    private void drawLaneIndicator(GraphicsContext g, double x, double y, AgentCar a) {
        double lw = 14, hgt = 40;
        for (int l = 0; l < Config.LANES; l++) {
            boolean target = l == a.targetLane && a.mode == dev.autodrive.sim.ControlMode.PLANNER;
            g.setFill(target ? Color.rgb(76, 201, 240, 0.28) : Color.rgb(255, 255, 255, 0.06));
            g.fillRoundRect(x + l * (lw + 3), y, lw, hgt, 4, 4);
            if (l == a.heldLane) {
                g.setStroke(Theme.WARM);
                g.setLineWidth(1.5);
                g.strokeRoundRect(x + l * (lw + 3) + 0.75, y + 0.75, lw - 1.5, hgt - 1.5, 4, 4);
            }
        }
        double px = x + a.x / Config.LANE_WIDTH * (lw + 3) - 3.5;
        g.setFill(Theme.EV_BODY);
        g.fillRoundRect(px, y + hgt / 2 - 6, 7, 12, 3, 3);
        double thr = Math.max(-1, Math.min(1, a.throttle));
        g.setFill(thr >= 0 ? Theme.GOOD : Theme.DANGER);
        double bh = Math.abs(thr) * (hgt / 2);
        g.fillRect(x + 3 * (lw + 3) + 2, thr >= 0 ? y + hgt / 2 - bh : y + hgt / 2, 4, bh);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
