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
        antiGravityCheck();
        bubbleShieldCheck();
        padReachCheck();
        pickupCount();
        landability();
        screenshots(out);
    }

    // ------------------------------------------------------------ autopilot

    /** Simple pilot: fly to the widest pad, null horizontal speed, descend gently. */
    static void pilot(Game g) {
        Terrain.Pad best = null;
        for (Terrain.Pad p : g.terrain.pads)
            if (best == null || Math.abs(p.centerX() - g.x) < Math.abs(best.centerX() - g.x)) best = p;
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

    // ------------------------------------------------------------ anti-gravity

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("ok   " + what);
    }

    static Game flying(long seed, int level) {
        Game g = new Game(seed);
        g.newGame();
        g.levelNumber = level;
        g.startLevel(true);
        g.tap();
        g.update(0.5f);
        g.tap();
        return g;
    }

    static void antiGravityCheck() {
        Game g = flying(42, 3); // level 3 has wind, to prove it's ignored too
        g.update(0.5f);
        g.vx = 30;
        float vy0 = g.vy;
        check(g.antiGravLeft == 3, "3 anti-gravity charges at start of game");
        check(g.antiGravity(), "anti-gravity activates");
        check(!g.antiGravity(), "can't stack a second activation while active");
        float y0 = g.y;
        float maxAbsVx = 0;
        for (int i = 0; i < 100; i++) { // 0.83 s
            g.update(DT);
            maxAbsVx = Math.max(maxAbsVx, Math.abs(g.vx));
        }
        check(maxAbsVx == 0f, "sideways movement stays at zero while active (wind level)");
        check(Math.abs(g.vy - vy0) < 1e-3, "gravity switched off while active (vertical speed constant)");
        for (int i = 0; i < 40; i++) g.update(DT); // past 1 s
        check(g.antiGravTime == 0f, "field ends after 1 second");
        float vyBefore = g.vy;
        g.update(0.1f);
        check(g.vy < vyBefore - 0.5f, "gravity takes over again");
        g.antiGravity();
        g.update(1.2f);
        g.antiGravity();
        g.update(1.2f);
        check(g.antiGravLeft == 0 && !g.antiGravity(), "only 3 per game");
        // Losing a ship or finishing a level must not recharge it.
        g.state = Game.State.CRASHED;
        g.lives = 2;
        g.stateTime = 5;
        g.tap();
        check(g.antiGravLeft == 0, "not recharged after losing a ship");
        g.levelNumber++;
        g.startLevel(true);
        check(g.antiGravLeft == 0, "not recharged on a new level");
        g.newGame();
        check(g.antiGravLeft == 3, "a new game gets 3 again");
    }

    static void bubbleShieldCheck() {
        // Meteor straight into the ship while anti-gravity is on: no shield cost.
        Game g = flying(7, 5);
        g.update(0.3f);
        g.antiGravity();
        float before = g.shield;
        g.meteors.clear();
        Game.Meteor m = new Game.Meteor(new java.util.Random(1), g.centerX(), g.centerY() + 15, 0, -100);
        g.meteors.add(m);
        g.update(DT);
        check(g.meteors.isEmpty() && g.state == Game.State.PLAYING && g.shield == before,
                "meteor hit during anti-gravity costs no shield");
        // Same hit with anti-gravity off costs shield.
        g.update(1.2f);
        float b2 = g.shield;
        g.meteors.add(new Game.Meteor(new java.util.Random(1), g.centerX(), g.centerY() + 15, 0, -100));
        g.update(DT);
        check(g.shield < b2, "meteor hit without anti-gravity drains the shield");

        // Slam into a pad far too fast with an empty shield, inside the bubble: survives.
        Game l = flying(9, 2);
        Terrain.Pad p = l.terrain.pads.get(0);
        l.x = p.centerX();
        l.y = p.y + 13f;
        l.vy = -45f;
        l.shield = 0;
        l.antiGravity();
        for (int i = 0; i < 30 && l.state == Game.State.PLAYING; i++) l.update(DT);
        check(l.state == Game.State.LANDED && l.shield == 0, "hard landing inside the bubble is absorbed for free");
        // Same landing without the bubble: crash.
        Game c = flying(9, 2);
        c.x = p.centerX();
        c.y = p.y + 13f;
        c.vy = -45f;
        c.shield = 0;
        for (int i = 0; i < 30 && c.state == Game.State.PLAYING; i++) c.update(DT);
        check(c.state == Game.State.CRASHED, "same landing without the bubble crashes");
    }

    static void padReachCheck() {
        float worst = 0;
        int minPads = 99;
        for (int lv = 1; lv <= 15; lv++) {
            for (int seed = 0; seed < 50; seed++) {
                Game g = flying(seed * 31L + lv, lv);
                float best = 1e9f;
                for (Terrain.Pad p : g.terrain.pads) best = Math.min(best, Math.abs(p.centerX() - g.x));
                worst = Math.max(worst, best);
                minPads = Math.min(minPads, g.terrain.pads.size());
            }
        }
        System.out.printf("pads: at least %d per level; nearest pad from spawn at most %.0f units away%n", minPads, worst);
        check(minPads >= 2 && worst <= 230, "every level has 2+ pads and one within reach of the spawn");
    }

    static void pickupCount() {
        int base = 0, actual = 0;
        for (int seed = 0; seed < 400; seed++) {
            int lv = 1 + seed % 10;
            Game g = flying(seed, lv);
            base += g.level.fuelPickups + g.level.oxygenPickups;
            actual += g.pickups.size();
        }
        float pct = (actual / (float) base - 1f) * 100f;
        System.out.printf("power-ups: %d base, %d placed (%+.1f%%)%n", base, actual, pct);
        check(pct > 2f && pct < 8f, "about 5% more power-ups");
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

        // Anti-gravity active, mid-flight.
        Game ag = flying(21, 2);
        ag.setViewAspect(1600f / 720f);
        ag.startLevel(true);
        ag.tap(); ag.update(0.5f); ag.tap();
        ag.update(1.5f);
        ag.antiGravity();
        ag.antiGravity();
        ag.update(1.5f);
        ag.antiGravity();
        ag.update(0.35f);
        ag.time = 0.1f;
        AwtGfx agfx = new AwtGfx(1600, 720);
        new Renderer().draw(agfx, ag);
        ImageIO.write(agfx.img, "png", new File(dir, "06-anti-gravity.png"));

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
