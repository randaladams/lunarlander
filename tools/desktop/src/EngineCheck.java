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
        meteorWarningCheck();
        padPayoutCheck();
        padReachCheck();
        pickupCount();
        landability(false);
        landability(true);
        screenshots(out);
    }

    // ------------------------------------------------------------ autopilot

    /** Simple pilot: fly to the widest pad, null horizontal speed, descend gently. */
    /** false: fly to the nearest pad. true: go for the top-paying (farthest) pad. */
    static boolean goForBest;

    static void pilot(Game g) {
        Terrain.Pad best = null;
        for (Terrain.Pad p : g.terrain.pads) {
            if (goForBest) {
                if (best == null || p.multiplier > best.multiplier) best = p;
            } else if (best == null || Math.abs(p.centerX() - g.x) < Math.abs(best.centerX() - g.x)) best = p;
        }
        // Going for X5: grab the fuel tank placed on the way first.
        Game.Pickup route = null;
        if (goForBest) for (Game.Pickup p : g.pickups) if (p.onRoute && !p.taken) route = p;
        if (route != null) {
            float rdx = route.x - g.x;
            float wantVx = Math.max(-35, Math.min(35, rdx * 0.4f));
            float wantVy = Math.max(-25, Math.min(25, (route.y - g.centerY()) * 0.8f));
            steer(g, wantVx, wantVy);
            return;
        }
        float dx = best.centerX() - g.x;
        float clearance = g.y - 11 - g.terrain.maxHeight(Math.min(g.x, best.centerX()) - 20, Math.max(g.x, best.centerX()) + 20);
        boolean over = Math.abs(dx) < best.width() / 2f - 14f;
        float wantVx = over ? 0 : Math.max(-35, Math.min(35, dx * 0.4f));
        float alt = g.y - 11 - best.y;
        float wantVy;
        if (!over) wantVy = clearance < 50 ? 15 : (clearance > 120 ? -10 : 0);
        else wantVy = -Math.max(5f, Math.min(40f, alt * 0.35f));
        steer(g, wantVx, wantVy);
    }

    static void steer(Game g, float wantVx, float wantVy) {
        float exVx = wantVx - g.vx, exVy = wantVy - g.vy;
        // Desired thrust vector = needed accel + gravity compensation.
        float ax = exVx * 1.5f, ay = exVy * 1.5f + g.level.gravity - g.windNow * 0;
        float ang = (float) Math.toDegrees(Math.atan2(ax, Math.max(0.1f, ay)));
        ang = Math.max(-60, Math.min(60, ang));
        g.setTilt(ang / Game.TILT_GAIN + Math.signum(ang) * Game.TILT_DEADZONE);
        float mag = (float) Math.hypot(ax, ay);
        g.setThrust(ay > 0 && mag > Level.THRUST * 0.45f && Math.abs(g.angle - ang) < 25);
    }

    static void landability(boolean best) {
        goForBest = best;
        System.out.println(best ? "-- autopilot going for the X5 pad (report only)"
                : "-- autopilot going for the nearest pad");
        System.out.println("level  landed  crashed  avgFuelLeft  avgO2Left  causes");
        for (int lv = 1; lv <= 16; lv++) {
            int landed = 0, crashed = 0, dry = 0;
            float fuelSum = 0, o2Sum = 0;
            java.util.Map<String, Integer> causes = new java.util.TreeMap<>();
            for (int seed = 0; seed < 60; seed++) {
                Game g = new Game(seed * 7919L + lv);
                g.setViewAspect(20f / 9f); // a typical phone
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
                    if (g.fuel <= 0) dry++;
                    causes.merge(g.message, 1, Integer::sum);
                }
            }
            if (dry > 0) causes.put("(out of fuel)", dry);
            // Regression guard: a pilot this clumsy (no power-ups, no
            // anti-gravity) must still land most of the time on every level.
            if (!best && landed < 36) throw new AssertionError("FAILED: level " + lv + " landable only " + landed + "/60");
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

    static void meteorWarningCheck() {
        Game g = flying(3, 6);
        g.vx = 0;
        g.vy = 0;
        java.util.Random r = new java.util.Random(2);
        Game.Meteor incoming = new Game.Meteor(r, g.centerX() - 150, g.centerY() + 300, 50, -100);
        Game.Meteor miss = new Game.Meteor(r, g.centerX() + 400, g.centerY() + 300, 60, -100);
        Game.Meteor far = new Game.Meteor(r, g.centerX(), g.centerY() + 900, 0, -100);
        float t = g.meteorThreat(incoming);
        check(t > 2.5f && t < 3.5f, "meteor on a collision course is flagged ~3 s out");
        check(g.meteorThreat(miss) < 0, "meteor that will miss is not flagged");
        check(g.meteorThreat(far) < 0, "meteor more than 4 s away is not flagged yet");
    }

    static void padPayoutCheck() {
        int games = 0;
        for (int lv = 1; lv <= 15; lv++) {
            for (int seed = 0; seed < 40; seed++) {
                Game g = flying(seed * 17L + lv, lv);
                Terrain.Pad near = g.nearestPad(g.x);
                int lowest = 99, highest = 0;
                for (Terrain.Pad p : g.terrain.pads) {
                    lowest = Math.min(lowest, p.multiplier);
                    highest = Math.max(highest, p.multiplier);
                }
                if (near.multiplier != lowest)
                    throw new AssertionError("FAILED: level " + lv + " spawn is nearest to a X" + near.multiplier + " pad");
                // Payout never drops as pads get further from the spawn.
                java.util.List<Terrain.Pad> byDist = new java.util.ArrayList<>(g.terrain.pads);
                final float sx = g.x;
                byDist.sort((a, b) -> Float.compare(Math.abs(a.centerX() - sx), Math.abs(b.centerX() - sx)));
                for (int i = 1; i < byDist.size(); i++)
                    if (byDist.get(i).multiplier < byDist.get(i - 1).multiplier)
                        throw new AssertionError("FAILED: level " + lv + " payouts not increasing with distance");
                if (highest != 5) throw new AssertionError("FAILED: no X5 pad on level " + lv);
                games++;
            }
        }
        check(games == 600, "spawn is nearest the lowest pad; X5 is always the farthest (600 maps)");
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

        // Zoomed in near the ground, meteor incoming from off screen.
        Game wg = flying(13, 6);
        wg.setViewAspect(1600f / 720f);
        wg.startLevel(true);
        wg.tap(); wg.update(0.5f); wg.tap();
        Terrain.Pad wp = wg.terrain.pads.get(0);
        wg.x = wp.centerX();
        wg.y = wp.y + 60f;
        wg.vx = 0;
        wg.vy = -5f;
        wg.meteors.clear();
        wg.meteors.add(new Game.Meteor(new java.util.Random(4), wg.x + 160, wg.y + 260, -55, -95));
        wg.update(1f / 120f);
        wg.time = 0.01f;
        AwtGfx wgfx = new AwtGfx(1600, 720);
        Renderer wr = new Renderer();
        wr.draw(wgfx, wg);
        boolean first = wr.takeWarningSound();
        wr.draw(wgfx, wg);
        boolean second = wr.takeWarningSound();
        check(first && !second, "warning sound triggers once per meteor, not every frame");
        ImageIO.write(wgfx.img, "png", new File(dir, "07-meteor-warning.png"));

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
