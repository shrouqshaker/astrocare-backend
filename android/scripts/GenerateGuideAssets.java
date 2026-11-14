import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the five original AstroCare device illustrations bundled in
 * app/src/main/assets/. The asset base names must match
 * `guides[].deviceIllustration.assetName` in contracts/data/guides.v1.json:
 *
 *   guide_heart_rate, guide_blood_pressure, guide_spo2, guide_temperature, guide_sleep
 *
 * Original flat-style artwork, created locally for this project (no downloads,
 * no third-party image assets). Run from the project root:
 *
 *   java scripts/GenerateGuideAssets.java [outputDir]
 */
public class GenerateGuideAssets {

    static final int S = 512;

    // Shared palette (aligned with the AstroCare space identity)
    static final Color DISC = new Color(0xE9, 0xF0, 0xFF);
    static final Color DISC_BORDER = new Color(0xD3, 0xE0, 0xFF);
    static final Color SHADOW = new Color(0x0A, 0x16, 0x30, 32);
    static final Color NAVY = new Color(0x0F, 0x1B, 0x3A);
    static final Color NAVY_CARD = new Color(0x12, 0x22, 0x4A);
    static final Color SCREEN = new Color(0x0B, 0x12, 0x26);
    static final Color CYAN = new Color(0x35, 0xD0, 0xE0);
    static final Color BLUE = new Color(0x2E, 0x6B, 0xE6);
    static final Color BLUE_DARK = new Color(0x1E, 0x4C, 0xB0);
    static final Color CORAL = new Color(0xFF, 0x5A, 0x6E);
    static final Color GREEN = new Color(0x3D, 0xDC, 0x97);
    static final Color AMBER = new Color(0xFF, 0xD1, 0x66);
    static final Color TEAL = new Color(0x14, 0xB8, 0xA6);
    static final Color TEAL_DARK = new Color(0x0B, 0x7A, 0x70);
    static final Color SKIN = new Color(0xF2, 0xC6, 0xA0);
    static final Color STEEL = new Color(0x9F, 0xB3, 0xC8);
    static final Color STEEL_DARK = new Color(0x6E, 0x82, 0x9C);
    static final Color PANEL = new Color(0xF5, 0xF8, 0xFF);
    static final Color STRAP = new Color(0x2A, 0x35, 0x50);
    static final Color WHITE = Color.WHITE;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "app/src/main/assets");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + outDir);
        }

        save(heartRate(), outDir, "guide_heart_rate.png");
        save(bloodPressure(), outDir, "guide_blood_pressure.png");
        save(spo2(), outDir, "guide_spo2.png");
        save(temperature(), outDir, "guide_temperature.png");
        save(sleep(), outDir, "guide_sleep.png");

        System.out.println("Wrote 5 illustrations to " + outDir.getAbsolutePath());
    }

    // ------------------------------------------------------------------
    // 1. guide_heart_rate — wrist pulse monitor (alt: "Pulse oximeter or manual wrist pulse")
    // ------------------------------------------------------------------
    static BufferedImage heartRate() {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        style(g);

        shadow(g, 76, 392, 360, 34);

        // Wrist strap
        rr(g, 36, 226, 440, 62, 31, STRAP);
        rr(g, 36, 244, 60, 26, 13, new Color(0x1E, 0x27, 0x3D));

        // Watch body + screen
        rr(g, 176, 126, 160, 260, 36, NAVY);
        rr(g, 194, 148, 124, 216, 24, SCREEN);

        // Heart
        heart(g, 256, 224, 40, CORAL);

        // ECG trace
        Polygon ecg = new Polygon();
        int[] xs = {204, 226, 236, 246, 254, 262, 272, 308};
        int[] ys = {316, 316, 296, 340, 304, 316, 316, 316};
        for (int i = 0; i < xs.length; i++) {
            ecg.addPoint(xs[i], ys[i]);
        }
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(GREEN);
        g.draw(ecg);

        // Reading label
        label(g, "72 bpm", 256, 350, 24, WHITE);

        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------
    // 2. guide_blood_pressure — arm cuff + digital monitor
    // ------------------------------------------------------------------
    static BufferedImage bloodPressure() {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        style(g);

        shadow(g, 60, 384, 400, 32);

        // Cuff
        rr(g, 44, 168, 214, 178, 26, BLUE);
        rr(g, 44, 168, 44, 178, 26, BLUE_DARK);
        rr(g, 96, 196, 148, 34, 17, new Color(0x6E, 0x9B, 0xF5));
        rr(g, 96, 254, 148, 34, 17, new Color(0x6E, 0x9B, 0xF5));

        // Tube from cuff to monitor
        g.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(STEEL);
        g.draw(new QuadCurve2D.Double(254, 300, 292, 348, 300, 386));

        // Monitor unit
        rr(g, 286, 112, 184, 288, 28, PANEL);
        rr(g, 306, 138, 144, 104, 14, SCREEN);
        label(g, "120/80", 378, 190, 34, WHITE);
        label(g, "mmHg", 378, 224, 18, CYAN);

        // Controls
        rr(g, 322, 266, 112, 40, 20, BLUE);
        label(g, "START", 378, 293, 18, WHITE);
        dot(g, 338, 344, 11, CYAN);
        dot(g, 378, 344, 11, AMBER);
        dot(g, 418, 344, 11, STEEL);

        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------
    // 3. guide_spo2 — finger pulse oximeter
    // ------------------------------------------------------------------
    static BufferedImage spo2() {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        style(g);

        shadow(g, 120, 380, 300, 30);

        // Finger inserted from the left
        rr(g, 24, 256, 400, 100, 50, SKIN);
        rr(g, 24, 316, 400, 40, 20, new Color(0xE3, 0xB4, 0x8C));

        // Oximeter body over the fingertip
        rr(g, 206, 212, 224, 144, 30, TEAL);
        rr(g, 206, 320, 224, 36, 18, TEAL_DARK);
        dot(g, 234, 284, 15, STEEL_DARK);

        // Screen: waveform + saturation value
        rr(g, 252, 232, 158, 76, 14, SCREEN);
        Polygon wave = new Polygon();
        int[] xs = {264, 284, 294, 302, 312, 322, 332, 344, 366, 396};
        int[] ys = {266, 266, 250, 282, 258, 266, 266, 258, 266, 266};
        for (int i = 0; i < xs.length; i++) {
            wave.addPoint(xs[i], ys[i]);
        }
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(GREEN);
        g.draw(wave);
        label(g, "98%", 331, 300, 26, WHITE);

        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------
    // 4. guide_temperature — digital thermometer
    // ------------------------------------------------------------------
    static BufferedImage temperature() {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        style(g);

        shadow(g, 70, 320, 380, 30);

        // Probe tip (right) and body
        rr(g, 56, 222, 330, 80, 40, PANEL);
        g.setColor(STEEL);
        g.fill(new Ellipse2D.Double(366, 220, 84, 84));
        g.setColor(STEEL_DARK);
        g.fill(new Ellipse2D.Double(424, 244, 28, 36));

        // Display + controls
        rr(g, 86, 242, 150, 42, 12, SCREEN);
        label(g, "36.7°C", 161, 272, 26, WHITE);
        dot(g, 272, 262, 17, CYAN);
        dot(g, 322, 262, 17, AMBER);

        // Heat waves at the tip
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(AMBER);
        g.draw(new Arc2D.Double(452, 226, 44, 44, -60, 120, Arc2D.OPEN));
        g.draw(new Arc2D.Double(462, 250, 30, 30, -60, 120, Arc2D.OPEN));

        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------
    // 5. guide_sleep — sleep diary with moon and stars
    // ------------------------------------------------------------------
    static BufferedImage sleep() {
        BufferedImage img = canvas();
        Graphics2D g = img.createGraphics();
        style(g);

        shadow(g, 96, 436, 320, 30);

        // Night-sky diary card
        rr(g, 96, 74, 320, 362, 34, NAVY_CARD);

        // Crescent moon (amber disc carved by a card-colored disc)
        g.setColor(AMBER);
        g.fill(new Ellipse2D.Double(240, 130, 124, 124));
        g.setColor(NAVY_CARD);
        g.fill(new Ellipse2D.Double(206, 116, 124, 124));

        // Stars
        star(g, 174, 146, 18, WHITE);
        star(g, 384, 132, 14, CYAN);
        star(g, 158, 232, 12, new Color(0xC9, 0xD6, 0xFF));
        star(g, 362, 244, 10, WHITE);

        // Diary lines
        rr(g, 140, 312, 232, 16, 8, new Color(0x2E, 0x43, 0x72));
        rr(g, 140, 348, 196, 16, 8, new Color(0x2E, 0x43, 0x72));
        rr(g, 140, 384, 150, 16, 8, new Color(0x2E, 0x43, 0x72));

        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------
    // Drawing helpers
    // ------------------------------------------------------------------
    static BufferedImage canvas() {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        style(g);
        g.setColor(DISC_BORDER);
        g.fill(new Ellipse2D.Double(12, 12, S - 24, S - 24));
        g.setColor(DISC);
        g.fill(new Ellipse2D.Double(16, 16, S - 32, S - 32));
        g.dispose();
        return img;
    }

    static void style(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    static void rr(Graphics2D g, double x, double y, double w, double h, double arc, Color c) {
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
    }

    static void dot(Graphics2D g, double cx, double cy, double r, Color c) {
        g.setColor(c);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    static void shadow(Graphics2D g, double x, double y, double w, double h) {
        g.setColor(SHADOW);
        g.fill(new Ellipse2D.Double(x, y, w, h));
    }

    static void label(Graphics2D g, String text, double cx, double baseline, int size, Color c) {
        g.setColor(c);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        FontMetricsHolder.draw(g, text, cx, baseline);
    }

    static void heart(Graphics2D g, double cx, double cy, double s, Color c) {
        Path2D p = new Path2D.Double();
        p.moveTo(cx, cy + s * 0.78);
        p.curveTo(cx - s * 1.15, cy - s * 0.10, cx - s * 0.62, cy - s * 1.00, cx, cy - s * 0.34);
        p.curveTo(cx + s * 0.62, cy - s * 1.00, cx + s * 1.15, cy - s * 0.10, cx, cy + s * 0.78);
        g.setColor(c);
        g.fill(p);
    }

    static void star(Graphics2D g, double cx, double cy, double r, Color c) {
        Polygon p = new Polygon();
        double inner = r * 0.34;
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI / 2 * i - Math.PI / 2;
            double rad = (i % 2 == 0) ? r : inner;
            p.addPoint((int) Math.round(cx + rad * Math.cos(ang)),
                    (int) Math.round(cy + rad * Math.sin(ang)));
        }
        g.setColor(c);
        g.fill(p);
    }

    static void save(BufferedImage img, File dir, String name) throws Exception {
        File out = new File(dir, name);
        ImageIO.write(img, "png", out);
        System.out.println("  " + out.getPath() + " (" + out.length() + " bytes)");
    }

    /** Tiny helper so we never depend on sun.awt metric APIs. */
    static class FontMetricsHolder {
        static void draw(Graphics2D g, String text, double cx, double baseline) {
            int w = g.getFontMetrics().stringWidth(text);
            g.drawString(text, (float) (cx - w / 2.0), (float) baseline);
        }
    }
}
