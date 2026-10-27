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
        shieldPickupCheck();
        pickupPlacementCheck();
        scoreboardCheck();
        pauseCheck();
        tutorialCheck();
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

    static void shieldPickupCheck() {
        int withShield = 0;
        for (int seed = 0; seed < 100; seed++) {
            Game g = flying(seed, 1 + seed % 12);
            for (Game.Pickup p : g.pickups) if (p.type == Game.Pickup.SHIELD) { withShield++; break; }
        }
        check(withShield >= 95, "a shield power-up on (nearly) every level (" + withShield + "/100)");
        Game g = flying(5, 4);
        Game.Pickup sp = null;
        for (Game.Pickup p : g.pickups) if (p.type == Game.Pickup.SHIELD) sp = p;
        g.shield = 40;
        g.x = sp.x;
        g.y = sp.y - 2;
        g.vx = g.vy = 0;
        g.update(DT);
        check(sp.taken && Math.abs(g.shield - (40 + Level.SHIELD_PICKUP)) < 0.01f, "shield power-up adds " + (int) Level.SHIELD_PICKUP);
        g.shield = 90;
        Game.Pickup sp2 = new Game.Pickup(Game.Pickup.SHIELD, g.centerX(), g.centerY(), 0);
        g.pickups.add(sp2);
        g.update(DT);
        check(sp2.taken && g.shield == Level.SHIELD_MAX, "shield never goes above max");
    }

    static void pickupPlacementCheck() {
        int maps = 0;
        for (int lv = 1; lv <= 15; lv++) {
            for (int seed = 0; seed < 40; seed++) {
                Game g = flying(seed * 13L + lv, lv);
                float lo = g.x, hi = g.x;
                for (Terrain.Pad p : g.terrain.pads) {
                    lo = Math.min(lo, p.x1);
                    hi = Math.max(hi, p.x2);
                }
                for (Game.Pickup p : g.pickups) {
                    if (p.x < lo - 31 || p.x > hi + 31)
                        throw new AssertionError("FAILED: level " + lv + " power-up at x=" + p.x
                                + " outside the useful strip " + lo + ".." + hi);
                }
                maps++;
            }
        }
        check(maps == 600, "every power-up lies between the spawn and the far pad (600 maps)");
    }

    static void scoreboardCheck() {
        Game g = new Game(1);
        // Play and lose 22 games with different scores.
        for (int i = 0; i < 22; i++) {
            g.newGame();
            g.score = 100 * (i + 1);
            g.levelNumber = 1 + i % 7;
            g.lives = 0;
            g.state = Game.State.CRASHED;
            g.stateTime = 5;
            g.tap(); // -> GAME_OVER, recorded
        }
        check(g.state == Game.State.GAME_OVER, "game over screen after the last ship");
        check(g.history.size() == Game.HISTORY_SIZE, "scoreboard keeps the last 20 games");
        check(g.history.get(0).score == 2200, "newest game listed first");
        check(g.highScore == 2200 && g.highScoreLevel == 1 + 21 % 7, "high score and its level tracked");
        g.state = Game.State.GAME_OVER;
        g.tap(); // already recorded: must not add again
        check(g.history.size() == 20 && g.history.get(0).score == 2200, "a game is recorded only once");
        Game h = new Game(2);
        h.loadHistory(g.historyToString());
        check(h.history.size() == 20 && h.history.get(5).score == g.history.get(5).score
                && h.history.get(5).level == g.history.get(5).level, "scoreboard survives save/load");
        h.loadHistory("12,3,99;garbage;7");
        check(h.history.size() == 1, "damaged saved data is skipped, not fatal");
    }

    static void pauseCheck() {
        Game g = flying(4, 2);
        g.update(0.2f);
        g.pause();
        float y0 = g.y;
        g.update(1f);
        check(g.paused && g.y == y0, "paused: the ship doesn't move");
        g.tap();
        check(g.paused, "a stray tap doesn't unpause (only RESUME does)");
        g.resume();
        g.update(0.2f);
        check(!g.paused && g.y != y0, "resume continues the game");
        int before = g.history.size();
        g.score = 777;
        g.pause();
        g.quitToMenu();
        check(g.state == Game.State.TITLE && g.history.size() == before + 1 && g.history.get(0).score == 777,
                "QUIT returns to the title and records the game");
    }

    static void tutorialCheck() {
        Game g = new Game(8);
        boolean[] done = {false};
        g.setListener(new Game.Listener() {
            public void onLanded(boolean p) {}
            public void onCrashed() {}
            public void onPickup() {}
            public void onShieldHit() {}
            public void onHighScore(int s, int l) {}
            public void onAntiGravity() {}
            public void onGameRecorded() { throw new AssertionError("FAILED: tutorial recorded a game"); }
            public void onTutorialDone() { done[0] = true; }
        });
        g.startTutorial();
        check(g.state == Game.State.BRIEFING && g.tutorial, "tutorial opens with its intro");
        g.update(0.5f);
        g.tap();
        float y0 = g.y;
        for (int i = 0; i < 240; i++) g.update(DT);
        check(Math.abs(g.y - y0) < 1f, "practice steps: no gravity, ship holds still");
        g.setTilt(-20);
        for (int i = 0; i < 60; i++) g.update(DT);
        g.setTilt(20);
        for (int i = 0; i < 60; i++) g.update(DT);
        g.setTilt(0);
        for (int i = 0; i < 60; i++) g.update(DT);
        check(g.tutorialStep == 1, "step 1 done after tilting both ways");
        g.setThrust(true);
        for (int i = 0; i < 200; i++) g.update(DT);
        g.setThrust(false);
        check(g.tutorialStep == 2, "step 2 done after holding thrust");
        g.antiGravity();
        check(g.tutorialStep == 3 && g.pickups.size() == 1, "step 3 done after anti-gravity; a fuel tank appears");
        Game.Pickup p = g.pickups.get(0);
        g.x = p.x;
        g.y = p.y - 2;
        g.vx = g.vy = 0;
        g.update(DT);
        check(g.tutorialStep == 4, "step 4 done after collecting the tank");
        // Crash on purpose: no lives lost, same step.
        Terrain.Pad pad = g.terrain.pads.get(0);
        g.x = pad.centerX();
        g.y = pad.y + 14;
        g.vy = -60;
        g.shield = 0;
        g.antiGravTime = 0;
        for (int i = 0; i < 20 && g.state == Game.State.PLAYING; i++) g.update(DT);
        check(g.state == Game.State.CRASHED, "you can crash in the tutorial");
        g.update(1.2f);
        g.tap();
        check(g.state == Game.State.PLAYING && g.tutorialStep == 4, "tutorial crash: respawn on the same step");
        // Now land gently.
        pad = g.terrain.pads.get(0);
        g.x = pad.centerX();
        g.y = pad.y + 13f;
        g.vx = 0;
        g.vy = -4;
        g.angle = 0;
        g.setTilt(0);
        for (int i = 0; i < 60 && g.state == Game.State.PLAYING; i++) g.update(DT);
        check(g.state == Game.State.LANDED && done[0] && g.tutorialDone, "landing completes the tutorial");
        check(g.score == 0 && g.history.isEmpty(), "tutorial doesn't touch score or scoreboard");
        g.update(1.2f);
        g.tap();
        check(g.state == Game.State.TITLE && !g.tutorial, "then back to the title");
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
            for (Game.Pickup pk : g.pickups) if (pk.type != Game.Pickup.SHIELD) actual++;
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

        // Title menu.
        Game tm = new Game(31);
        tm.setViewAspect(1600f / 720f);
        tm.highScore = 4310;
        tm.highScoreLevel = 9;
        tm.update(2f);
        save(r, tm, 1600, 720, new File(dir, "08-title-menu.png"));

        // Scoreboard with 20 games.
        Game sb = new Game(32);
        sb.setViewAspect(1600f / 720f);
        java.util.Random hr = new java.util.Random(3);
        StringBuilder hist = new StringBuilder();
        long t0 = 1793000000000L;
        for (int i = 0; i < 20; i++) {
            if (i > 0) hist.append(';');
            int sc = 200 + hr.nextInt(4000);
            hist.append(sc).append(',').append(1 + sc / 450).append(',').append(t0 - i * 86400000L / 3);
        }
        sb.loadHistory(hist.toString());
        for (Game.Record rec : sb.history) if (rec.score > sb.highScore) { sb.highScore = rec.score; sb.highScoreLevel = rec.level; }
        sb.showScores();
        sb.update(1f);
        save(r, sb, 1600, 720, new File(dir, "09-scoreboard.png"));

        // Pause menu mid-flight.
        Game pm = flying(33, 5);
        pm.setViewAspect(1600f / 720f);
        pm.startLevel(true);
        pm.tap(); pm.update(0.5f); pm.tap();
        pm.update(1.2f);
        pm.pause();
        save(r, pm, 1600, 720, new File(dir, "10-paused.png"));

        // Tutorial, thrust step.
        Game tu = new Game(34);
        tu.setViewAspect(1600f / 720f);
        tu.startTutorial();
        tu.update(0.5f);
        tu.tap();
        tu.setTilt(-20); for (int i = 0; i < 60; i++) tu.update(DT);
        tu.setTilt(20); for (int i = 0; i < 60; i++) tu.update(DT);
        tu.setTilt(0); for (int i = 0; i < 60; i++) tu.update(DT);
        tu.setThrust(true); for (int i = 0; i < 100; i++) tu.update(DT);
        save(r, tu, 1600, 720, new File(dir, "11-tutorial.png"));

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
