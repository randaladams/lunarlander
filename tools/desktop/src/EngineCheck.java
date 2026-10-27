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
        thrustCheck();
        antiGravBonusCheck();
        rescueCheck();
        explainCheck();
        meteorSmashCheck();
        bonusShipCheck();
        crashNewFieldCheck();
        splashCheck();
        padReachCheck();
        pickupCount();
        landability(false);
        landability(true);
        rescueReport();
        screenshots(out);
    }

    // ------------------------------------------------------------ autopilot

    /** Simple pilot: fly to the widest pad, null horizontal speed, descend gently. */
    /** false: fly to the nearest pad. true: go for the top-paying (farthest) pad. */
    static boolean goForBest;
    /** true: on rescue levels, pick up the astronaut before landing. */
    static boolean doRescue;

    static void pilot(Game g) {
        Terrain.Pad best = null;
        for (Terrain.Pad p : g.terrain.pads) {
            if (p.rescue) {
                // Rescue run: the SOS pad comes first.
                if (doRescue && !g.astronautAboard) { best = p; break; }
                continue;
            }
            if (goForBest) {
                if (best == null || p.multiplier > best.multiplier) best = p;
            } else if (best == null || Math.abs(p.centerX() - g.x) < Math.abs(best.centerX() - g.x)) best = p;
        }
        // Rescue: after the pickup, lift straight up before heading off.
        if (doRescue && g.astronautAboard && g.altitude() < 45f && Math.abs(g.vx) < 3f
                && Math.abs(best.centerX() - g.x) > best.width() / 2f) {
            steer(g, 0, 20);
            return;
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

    static void rescueReport() {
        goForBest = false;
        doRescue = true;
        System.out.println("-- autopilot on rescue levels: picks up the astronaut first (report only)");
        System.out.println("level  rescued  landedWithout  crashed  causes");
        for (int lv = 10; lv <= 16; lv += 2) {
            int rescued = 0, skipped = 0, crashed = 0;
            java.util.Map<String, Integer> causes = new java.util.TreeMap<>();
            for (int seed = 0; seed < 60; seed++) {
                Game g = new Game(seed * 7919L + lv);
                g.setViewAspect(20f / 9f);
                g.newGame();
                g.levelNumber = lv;
                g.startLevel(true);
                g.tap();
                for (int i = 0; i < 60; i++) g.update(DT);
                g.tap();
                int steps = 0;
                while (g.state == Game.State.PLAYING && steps++ < 120 * 150) {
                    pilot(g);
                    g.update(DT);
                }
                if (g.state == Game.State.LANDED) {
                    if (g.astronautAboard) rescued++; else skipped++;
                } else {
                    crashed++;
                    causes.merge(g.state == Game.State.PLAYING ? "(timed out)" : g.message, 1, Integer::sum);
                }
            }
            System.out.printf("%5d  %7d  %13d  %7d  %s%n", lv, rescued, skipped, crashed, causes);
        }
        doRescue = false;
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
                    if (p.rescue) continue;
                    lowest = Math.min(lowest, p.multiplier);
                    highest = Math.max(highest, p.multiplier);
                }
                if (near.multiplier != lowest)
                    throw new AssertionError("FAILED: level " + lv + " spawn is nearest to a X" + near.multiplier + " pad");
                // Payout never drops as pads get further from the spawn.
                java.util.List<Terrain.Pad> byDist = new java.util.ArrayList<>(g.terrain.pads);
                byDist.removeIf(pp -> pp.rescue);
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
            public void onExtraShip() {}
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

    static void thrustCheck() {
        check(Math.abs(Level.THRUST / 24f - 1.1f * 1.05f) < 0.001f && Level.FUEL_BURN == 70f,
                "thrust +10% then +5% more, fuel burn per second unchanged");
    }

    /** Lands the ship gently on the given pad (test helper). */
    static void landOn(Game g, Terrain.Pad p) {
        g.x = p.centerX();
        g.y = p.y + 12f;
        g.vx = 0;
        g.vy = -3;
        g.angle = 0;
        g.setTilt(0);
        g.setThrust(false);
        for (int i = 0; i < 40 && g.state == Game.State.PLAYING; i++) {
            g.update(DT);
            if (g.astronautAboard && p.rescue && i > 5) break;
        }
    }

    static Terrain.Pad scoringPad(Game g) {
        for (Terrain.Pad p : g.terrain.pads) if (!p.rescue) return p;
        return null;
    }

    static void antiGravBonusCheck() {
        Game g = flying(1, 5);
        g.antiGravLeft = 1;
        landOn(g, scoringPad(g));
        check(g.state == Game.State.LANDED && g.antiGravLeft == 2, "finishing level 5 awards an anti-grav charge");
        Game h = flying(1, 10);
        h.antiGravLeft = 3;
        landOn(h, scoringPad(h));
        check(h.antiGravLeft == 3, "the bonus never goes above 3");
        Game k = flying(1, 6);
        k.antiGravLeft = 1;
        landOn(k, scoringPad(k));
        check(k.antiGravLeft == 1, "no bonus on other levels");
    }

    static void rescueCheck() {
        for (int lv = 1; lv <= 20; lv++) {
            Game g = flying(lv, lv);
            boolean sos = false;
            for (Terrain.Pad p : g.terrain.pads) sos |= p.rescue;
            boolean want = lv >= 10 && (lv - 10) % 2 == 0;
            if (sos != want || g.level.rescue != want)
                throw new AssertionError("FAILED: rescue on level " + lv + " is " + sos);
        }
        check(true, "rescue missions on levels 10, 12, 14... only");

        Game g = flying(77, 10);
        Terrain.Pad sos = null;
        for (Terrain.Pad p : g.terrain.pads) if (p.rescue) sos = p;
        check(sos.multiplier == 0 && sos != g.terrain.homePad, "SOS pad is extra, not the home pad, pays nothing itself");
        g.fuel = 100;
        g.shield = 30;
        landOn(g, sos);
        check(g.state == Game.State.PLAYING && g.astronautAboard, "landing at SOS picks up the astronaut; flight continues");
        check(g.fuel == Level.FUEL_MAX && g.shield == Level.SHIELD_MAX && g.oxygen == Level.O2_MAX,
                "astronaut pickup refuels, repairs the shield and tops up oxygen");
        g.setTilt(45 / Game.TILT_GAIN + Game.TILT_DEADZONE);
        for (int i = 0; i < 120; i++) g.update(DT);
        check(g.state == Game.State.PLAYING && g.shield == Level.SHIELD_MAX, "tilting while parked on the SOS pad is safe");
        g.setTilt(0);
        for (int i = 0; i < 120; i++) g.update(DT);
        for (int i = 0; i < 120; i++) g.update(DT);
        check(g.state == Game.State.PLAYING && Math.abs(g.y - (sos.y + 11f)) < 1f, "the ship rests on the SOS pad until you take off");
        g.setThrust(true);
        for (int i = 0; i < 240; i++) g.update(DT);
        g.setThrust(false);
        check(g.y > sos.y + 25f, "thrust lifts off the SOS pad again");
        int before = g.score;
        landOn(g, scoringPad(g));
        check(g.state == Game.State.LANDED && g.score - before >= Level.RESCUE_BONUS && g.strikes == 0,
                "second landing completes the mission with the rescue bonus");

        // Skipping the astronaut: strikes, and the third costs a ship.
        Game s = new Game(5);
        s.newGame();
        for (int k = 1; k <= 3; k++) {
            s.levelNumber = 10 + (k - 1) * 2;
            s.startLevel(true);
            s.tap(); s.update(0.5f); s.tap();
            int livesBefore = s.lives;
            landOn(s, scoringPad(s));
            if (k < 3) check(s.state == Game.State.LANDED && s.strikes == k && s.lives == livesBefore,
                    "skipping the astronaut gives strike " + k);
            else check(s.strikes == 0 && s.lives == livesBefore - 1, "third strike loses a ship (strikes reset)");
        }
        // Third strike on the last ship ends the game.
        Game last = new Game(6);
        last.newGame();
        last.lives = 1;
        last.strikes = 2;
        last.levelNumber = 12;
        last.startLevel(true);
        last.tap(); last.update(0.5f); last.tap();
        landOn(last, scoringPad(last));
        last.update(1.5f);
        last.tap();
        check(last.state == Game.State.GAME_OVER, "third strike on the last ship is game over");
        // Crashing on a rescue level is a normal crash: no strike.
        Game c = flying(8, 10);
        Terrain.Pad cp = scoringPad(c);
        c.x = cp.centerX(); c.y = cp.y + 13; c.vy = -60; c.shield = 0;
        for (int i = 0; i < 30 && c.state == Game.State.PLAYING; i++) c.update(DT);
        check(c.state == Game.State.CRASHED && c.strikes == 0, "crashing on a rescue level isn't a strike");
    }

    static void explainCheck() {
        Game g = flying(3, 2);
        landOn(g, scoringPad(g));
        check(g.state == Game.State.LANDED && g.explain.size() >= 2 && g.explain.get(0).startsWith("TOUCHDOWN"),
                "a landing explains its numbers: " + g.explain);
        Game c = flying(3, 2);
        Terrain.Pad p = scoringPad(c);
        c.x = p.centerX(); c.y = p.y + 13; c.vy = -40; c.shield = 20;
        for (int i = 0; i < 30 && c.state == Game.State.PLAYING; i++) c.update(DT);
        check(c.state == Game.State.CRASHED && c.explain.size() == 3, "a crash landing explains why: " + c.explain);
    }

    static void meteorSmashCheck() {
        Game g = flying(12, 6);
        g.update(0.2f);
        Game.Pickup target = null;
        for (Game.Pickup p : g.pickups) if (!p.onRoute && !p.taken) { target = p; break; }
        int untakenBefore = 0;
        for (Game.Pickup p : g.pickups) if (!p.taken) untakenBefore++;
        float tx = target.x, ty = target.y;
        int type = target.type;
        int tries = 0;
        while (!target.destroyed && tries++ < 60) {
            g.meteors.clear();
            g.meteors.add(new Game.Meteor(new java.util.Random(tries), tx, ty + 8, 0, -100));
            g.x = tx + 300 < g.worldW - 50 ? tx + 300 : tx - 300; // keep the ship well away
            g.y = 450; g.vx = g.vy = 0;
            g.update(DT);
        }
        check(target.destroyed && tries > 1, "a meteor only sometimes destroys a power-up (took " + tries + " tries)");
        Game.Pickup repl = g.pickups.get(g.pickups.size() - 1);
        check(repl.type == type && !repl.taken && repl.appear > 0 && (repl.x != tx || repl.y != ty),
                "a replacement of the same kind appears somewhere else");
        int untakenAfter = 0;
        for (Game.Pickup p : g.pickups) if (!p.taken) untakenAfter++;
        check(untakenAfter == untakenBefore, "the level keeps the same number of power-ups");
        // Can't be grabbed while it's still materializing.
        g.meteors.clear();
        g.x = repl.x; g.y = repl.y - 2; g.vx = g.vy = 0;
        g.update(DT);
        check(!repl.taken, "a replacement can't be grabbed while it's appearing");
        for (int i = 0; i < 160; i++) { g.x = repl.x; g.y = repl.y - 2; g.vx = g.vy = 0; g.update(DT); }
        check(repl.taken, "...and can be once it has appeared");
    }

    static void bonusShipCheck() {
        Game g = flying(2, 3);
        check(g.nextBonusShip == 1500, "first extra ship target is 1500");
        g.score = 1490;
        int lives = g.lives;
        landOn(g, scoringPad(g));
        check(g.state == Game.State.LANDED && g.lives == lives + 1 && g.breakdown.contains("EXTRA SHIP!")
                && g.nextBonusShip == 4500, "extra ship at 1500; next at 4500");
        Game h = flying(2, 3);
        h.lives = Game.MAX_LIVES;
        h.score = 1490;
        landOn(h, scoringPad(h));
        check(h.lives == Game.MAX_LIVES && h.nextBonusShip == 4500,
                "no more than " + Game.MAX_LIVES + " ships; the bonus just passes");
        Game k = flying(2, 3);
        k.score = 4490;
        k.nextBonusShip = 4500;
        int kl = k.lives;
        landOn(k, scoringPad(k));
        check(k.lives == kl + 1 && k.nextBonusShip == 7500, "then every 3000 (4500, 7500, ...)");
    }

    static void crashNewFieldCheck() {
        int different = 0;
        for (int seed = 0; seed < 20; seed++) {
            Game g = flying(seed, 6);
            float[] before = g.terrain.xs.clone();
            Terrain.Pad p = scoringPad(g);
            g.x = p.centerX(); g.y = p.y + 13; g.vy = -60; g.shield = 0;
            for (int i = 0; i < 30 && g.state == Game.State.PLAYING; i++) g.update(DT);
            g.update(2f);
            g.tap();
            if (g.state == Game.State.BRIEFING && !java.util.Arrays.equals(before, g.terrain.xs)) different++;
        }
        check(different == 20, "after a crash the next try gets a new random playfield (20/20)");
    }

    static void splashCheck() {
        Game g = new Game(3);
        g.assetsReady = false;
        g.showSplash();
        check(g.state == Game.State.SPLASH, "the start screen shows at launch");
        g.update(Game.SPLASH_SECONDS + 1f);
        check(g.state == Game.State.SPLASH && g.splashProgress() < 1f, "it waits while sounds are still loading");
        g.assetsReady = true;
        g.update(0.1f);
        check(g.state == Game.State.TITLE, "then moves on to the title menu by itself");
        Game k = new Game(4);
        k.showSplash();
        k.update(0.7f);
        k.tap();
        check(k.state == Game.State.TITLE, "a tap skips it");
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

        // Rescue level: SOS pad with the astronaut, plus a strike on the HUD.
        Game rs = new Game(41);
        rs.setViewAspect(1600f / 720f);
        rs.newGame();
        rs.strikes = 1;
        rs.levelNumber = 10;
        rs.startLevel(true);
        rs.tap(); rs.update(0.5f); rs.tap();
        rs.update(1.0f);
        save(r, rs, 1600, 720, new File(dir, "12-rescue-level.png"));

        // Crash screen with the explanation.
        Game cr = new Game(42);
        cr.setViewAspect(1600f / 720f);
        cr.newGame();
        cr.levelNumber = 3;
        cr.startLevel(true);
        cr.tap(); cr.update(0.5f); cr.tap();
        Terrain.Pad cpad = scoringPad(cr);
        cr.x = cpad.centerX(); cr.y = cpad.y + 13; cr.vy = -31; cr.vx = 4; cr.shield = 45;
        for (int i = 0; i < 30 && cr.state == Game.State.PLAYING; i++) cr.update(DT);
        cr.update(2f);
        save(r, cr, 1600, 720, new File(dir, "13-crash-explained.png"));

        // Hard landing explained.
        Game hl = new Game(43);
        hl.setViewAspect(1600f / 720f);
        hl.newGame();
        hl.levelNumber = 5;
        hl.startLevel(true);
        hl.tap(); hl.update(0.5f); hl.tap();
        Terrain.Pad hpad = scoringPad(hl);
        hl.x = hpad.centerX(); hl.y = hpad.y + 13; hl.vy = -16; hl.vx = 2; hl.antiGravLeft = 2;
        for (int i = 0; i < 30 && hl.state == Game.State.PLAYING; i++) hl.update(DT);
        hl.update(1.5f);
        save(r, hl, 1600, 720, new File(dir, "14-landing-explained.png"));

        // A power-up just smashed, its replacement materializing.
        Game ms = new Game(44);
        ms.setViewAspect(1600f / 720f);
        ms.newGame();
        ms.levelNumber = 6;
        ms.startLevel(true);
        ms.tap(); ms.update(0.5f); ms.tap();
        Game.Pickup mt = null;
        for (Game.Pickup p : ms.pickups) if (!p.onRoute) { mt = p; break; }
        for (int k = 0; k < 60 && !mt.destroyed; k++) {
            ms.meteors.clear();
            ms.meteors.add(new Game.Meteor(new java.util.Random(k), mt.x, mt.y + 8, 0, -100));
            ms.update(DT);
        }
        ms.meteors.clear();
        for (int i = 0; i < 50; i++) ms.update(DT);
        save(r, ms, 1600, 720, new File(dir, "15-powerup-replaced.png"));

        // Start screen, mid-descent and landed.
        for (float st : new float[]{1.2f, 3.9f}) {
            Game sp = new Game(45);
            sp.setViewAspect(1600f / 720f);
            sp.showSplash();
            sp.update(st);
            save(r, sp, 1600, 720, new File(dir, st < 2 ? "16-start-screen-a.png" : "17-start-screen-b.png"));
        }

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
