import com.randaladams.lunarlander.engine.Game;
import com.randaladams.lunarlander.engine.Level;
import com.randaladams.lunarlander.engine.Renderer;
import com.randaladams.lunarlander.engine.Terrain;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * Desktop checks for the engine:
 *  1. An autopilot flies every level on many random maps and reports how often
 *     it can land with the fuel/oxygen available (sanity check on the tuning).
 *  2. Renders a few screenshots to PNG.
 *
 * Usage: java EngineCheck <outputDir>
 */
public final class EngineCheck {

    static final float DT = 1f / 120f;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "preview");
        out.mkdirs();
        landability();
        screenshots(out);
    }

    // ------------------------------------------------------------ autopilot

    /** Simple pilot: fly to the widest pad, null horizontal speed, descend gently. */
    static void pilot(Game g) {
        Terrain.Pad best = null;
        for (Terrain.Pad p : g.terrain.pads) if (best == null || p.width() > best.width()) best = p;
        float dx = best.centerX() - g.x;
        float clearance = g.y - 11 - g.terrain.maxHeight(Math.min(g.x, best.centerX()) - 20, Math.max(g.x, best.centerX()) + 20);
        boolean over = Math.abs(dx) < best.width() / 2f - 14f;
        float wantVx = over ? 0 : Math.max(-35, Math.min(35, dx * 0.4f));
        float alt = g.y - 11 - best.y;
        float wantVy;
        if (!over) wantVy = clearance < 50 ? 15 : (clearance > 120 ? -10 : 0);
        else wantVy = -Math.max(5f, Math.min(40f, alt * 0.35f));
        float exVx = wantVx - g.vx, exVy = wantVy - g.vy;
        // Desired thrust vector = needed accel + gravity compensation.
        float ax = exVx * 1.5f, ay = exVy * 1.5f + g.level.gravity - g.windNow * 0;
        float ang = (float) Math.toDegrees(Math.atan2(ax, Math.max(0.1f, ay)));
        ang = Math.max(-60, Math.min(60, ang));
        g.setTilt(ang / Game.TILT_GAIN + Math.signum(ang) * Game.TILT_DEADZONE);
        float mag = (float) Math.hypot(ax, ay);
        g.setThrust(ay > 0 && mag > Level.THRUST * 0.45f && Math.abs(g.angle - ang) < 25);
    }

    static void landability() {
        System.out.println("level  landed  crashed  avgFuelLeft  avgO2Left  causes");
        for (int lv = 1; lv <= 10; lv++) {
            int landed = 0, crashed = 0;
            float fuelSum = 0, o2Sum = 0;
            java.util.Map<String, Integer> causes = new java.util.TreeMap<>();
            for (int seed = 0; seed < 60; seed++) {
                Game g = new Game(seed * 7919L + lv);
                g.newGame();
                g.levelNumber = lv;
                g.startLevel(true);
                g.tap(); // briefing
                for (int i = 0; i < 60; i++) g.update(DT);
                g.tap();
                int steps = 0;
                while (g.state == Game.State.PLAYING && steps++ < 120 * 120) {
                    pilot(g);
                    g.update(DT);
                }
                if (g.state == Game.State.LANDED) {
                    landed++;
                    fuelSum += g.fuel;
                    o2Sum += g.oxygen;
                } else {
                    crashed++;
                    causes.merge(g.message, 1, Integer::sum);
                }
            }
            System.out.printf("%5d  %6d  %7d  %11.0f  %9.1f  %s%n", lv, landed, crashed,
                    landed > 0 ? fuelSum / landed : 0, landed > 0 ? o2Sum / landed : 0, causes);
        }
    }

    // ------------------------------------------------------------ screenshots

    static void screenshots(File dir) throws Exception {
        Game title = new Game(3);
        title.setViewAspect(1600f / 720f);
        title.update(2.3f);
        save(r, title, 1600, 720, new File(dir, "01-title.png"));

        // Mid-flight on level 4 (wind + meteors), thrusting.
        Game g = new Game(11);
        g.setViewAspect(1600f / 720f);
        g.newGame();
        g.levelNumber = 4;
        g.startLevel(true);
        g.tap();
        g.update(0.5f);
        g.tap();
        for (int i = 0; i < 120 * 4; i++) {
            pilot(g);
            g.update(DT);
            if (g.state != Game.State.PLAYING) break;
        }
        g.setThrust(true);
        g.update(DT);
        save(r, g, 1600, 720, new File(dir, "02-flight-level4.png"));

        // Fly to a landing on level 2.
        Game l = new Game(5);
        l.setViewAspect(1600f / 720f);
        l.newGame();
        l.levelNumber = 2;
        l.startLevel(true);
        l.tap();
        l.update(0.5f);
        l.tap();
        boolean shotClose = false;
        while (l.state == Game.State.PLAYING) {
            pilot(l);
            l.update(DT);
            if (!shotClose && l.altitude() < 45 && l.thrusting) {
                save(r, l, 1600, 720, new File(dir, "05-zoomed-approach.png"));
                shotClose = true;
            }
        }
        l.update(1.2f);
        save(r, l, 1600, 720, new File(dir, "03-after-landing.png"));

        // Tablet aspect, briefing screen.
        Game b = new Game(9);
        b.setViewAspect(1200f / 900f);
        b.newGame();
        save(r, b, 1200, 900, new File(dir, "04-tablet-briefing.png"));
        System.out.println("screenshots written to " + dir.getAbsolutePath());
    }

    static Renderer r;

    static void save(Renderer unused, Game g, int w, int h, File f) throws Exception {
        Renderer rr = new Renderer();
        AwtGfx gfx = new AwtGfx(w, h);
        // Freeze blinking text in its visible phase.
        g.time = Math.round(g.time);
        rr.draw(gfx, g);
        ImageIO.write(gfx.img, "png", f);
    }
}
