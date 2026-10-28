package com.adamselite.lunarlander.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * All game rules and simulation. No Android code in here, so it can be
 * unit-tested and previewed on a desktop JVM.
 *
 * World coordinates: x right, y UP, ground near y=0, sky top at WORLD_H.
 * Ship angle in degrees: 0 = upright, positive = rotated clockwise (nose right).
 */
public final class Game {

    /** Playfield width on a 16:9 screen; wider screens get a wider field. */
    public static final float MIN_WORLD_W = 1000f;
    public float worldW = MIN_WORLD_W;
    public static final float WORLD_H = 560f;

    public enum State { SPLASH, TITLE, SCORES, BRIEFING, PLAYING, LANDED, CRASHED, GAME_OVER }

    /** How long the start screen shows (it can be tapped away). 0 = skip it. */
    public static final float SPLASH_SECONDS = 4.0f;
    /** Set by the app once sounds etc. have loaded; the start screen waits for it. */
    public volatile boolean assetsReady = true;

    /** Show the animated start screen (call once at launch). */
    public void showSplash() {
        if (SPLASH_SECONDS > 0) setState(State.SPLASH);
    }

    /** Loading progress for the start screen, 0..1. */
    public float splashProgress() {
        float t = Math.min(1f, stateTime / SPLASH_SECONDS);
        return assetsReady ? t : Math.min(t, 0.9f);
    }

    /** Hooks for the platform layer (vibration, sound, saving the high score). */
    public interface Listener {
        void onLanded(boolean perfect);

        void onCrashed();

        void onPickup();

        void onShieldHit();

        void onHighScore(int score, int level);

        void onAntiGravity();

        /** A game ended and was added to {@link #history}: save it. */
        void onGameRecorded();

        void onTutorialDone();

        void onExtraShip();
    }

    // ------------------------------------------------------------ ship geometry
    // Local coordinates, y up, origin at the middle of the descent stage.

    /** Line segments (x1,y1,x2,y2) making up the lander. */
    public static final float[] SHIP_LINES = {
            // cabin (octagon)
            -3, 13, 3, 13, 3, 13, 6, 10, 6, 10, 6, 4, 6, 4, 3, 1,
            3, 1, -3, 1, -3, 1, -6, 4, -6, 4, -6, 10, -6, 10, -3, 13,
            // window
            -2, 9, 2, 9,
            // descent stage
            -8, 1, 8, 1, 8, 1, 8, -4, 8, -4, -8, -4, -8, -4, -8, 1,
            // nozzle
            -3, -4, -5, -8, -5, -8, 5, -8, 5, -8, 3, -4,
            // legs and feet
            -7, -4, -12, -11, 7, -4, 12, -11,
            -14, -11, -10, -11, 10, -11, 14, -11,
    };
    public static final float FOOT_X = 12f;
    public static final float FOOT_Y = -11f;
    /** Hull points tested against the ground (besides the two feet). */
    private static final float[] HULL_POINTS = {
            -8, -4, 8, -4, -8, 1, 8, 1, -6, 10, 6, 10, 0, 13, -5, -8, 5, -8,
    };
    public static final float SHIP_RADIUS = 14f;
    /** Ship center used for pick-ups and meteor hits. */
    private static final float CENTER_Y = 2f;

    // --------------------------------------------------------------- tuning
    /** Ship angle per degree of device tilt. */
    public static final float TILT_GAIN = 2.2f;
    public static final float TILT_DEADZONE = 2f;
    public static final float MAX_ANGLE = 100f;
    public static final float ROTATE_SPEED = 220f; // degrees/second

    // --------------------------------------------------------------- objects

    public static final class Pickup {
        public static final int FUEL = 0, OXYGEN = 1, SHIELD = 2;
        public final int type;
        public final float x, y;
        public boolean taken;
        /** The fuel tank placed on the way to the top-paying pad. */
        public boolean onRoute;
        /** Smashed by a meteor (as opposed to collected). */
        public boolean destroyed;
        /** Seconds left of the fade-in for a replacement; can't be grabbed until 0. */
        public float appear;
        public static final float APPEAR_SECONDS = 1.2f;
        public float phase;

        public Pickup(int type, float x, float y, float phase) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.phase = phase;
        }
    }

    public static final class Meteor {
        public float x, y, vx, vy, angle, spin, radius;
        public final float[] shape; // radii for each vertex
        /** Set once a collision warning has been raised for this meteor. */
        public boolean warned;
        /** The power-up this meteor last clipped (so it only rolls once for it). */
        Pickup grazed;

        public Meteor(Random r, float x, float y, float vx, float vy) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            radius = 5f + r.nextFloat() * 5f;
            spin = (r.nextFloat() * 2 - 1) * 200f;
            shape = new float[9];
            for (int i = 0; i < shape.length; i++) shape[i] = radius * (0.7f + r.nextFloat() * 0.45f);
        }
    }

    /** A spinning line segment (wreckage) or a spark (length 0). */
    public static final class Debris {
        public float x, y, vx, vy, angle, spin, halfLen, life, maxLife;
    }

    /** Floating text, e.g. "+FUEL". */
    public static final class Popup {
        public String text;
        public float x, y, life;
    }

    // --------------------------------------------------------------- state

    public State state = State.TITLE;
    public float stateTime;
    public boolean paused;

    public int levelNumber = 1;
    public Level level = Level.get(1);
    public Terrain terrain;
    private long levelSeed;
    public final List<Pickup> pickups = new ArrayList<>();
    public final List<Meteor> meteors = new ArrayList<>();
    public final List<Debris> debris = new ArrayList<>();
    public final List<Popup> popups = new ArrayList<>();
    public final float[] stars = new float[160]; // x,y pairs

    public float x, y, vx, vy, angle;
    public float targetAngle;
    public boolean thrustInput;

    /** Anti-gravity: freezes sideways drift and switches off gravity for a moment. */
    public static final int ANTI_GRAV_CHARGES = 3;      // per game, never recharged
    public static final float ANTI_GRAV_SECONDS = 1f;
    public int antiGravLeft;
    public float antiGravTime;                          // >0 while active

    /** While anti-gravity is on, a free shield takes every hit instead of yours. */
    public boolean bubbleShield() {
        return antiGravTime > 0;
    }
    public boolean thrusting;
    public float fuel, oxygen, shield;
    public float noOxygenTime;
    public float shieldFlash;    // >0 while the shield glows after absorbing a hit
    private float hullCooldown;
    public float windNow;
    private float windPhase;
    private float meteorClock;
    public boolean shipVisible;

    public int score;
    /** First extra ship at BONUS_SHIP_FIRST points, then every BONUS_SHIP_EVERY, up to MAX_LIVES held. */
    public static final int BONUS_SHIP_FIRST = 1500;
    public static final int BONUS_SHIP_EVERY = 3000;
    /** Chance that a meteor clipping a power-up destroys it. */
    public static final float METEOR_SMASH_CHANCE = 0.4f;
    public static final int MAX_LIVES = 5;
    /** Score at which the next extra ship is awarded. */
    public int nextBonusShip = BONUS_SHIP_FIRST;
    public int highScore;
    /** Level reached in the game that set the high score. */
    public int highScoreLevel;
    public int lives;

    // ---------------------------------------------------------- scoreboard
    public static final int HISTORY_SIZE = 20;

    /** One finished game. */
    public static final class Record {
        public final int score, level;
        public final long time; // epoch millis

        public Record(int score, int level, long time) {
            this.score = score;
            this.level = level;
            this.time = time;
        }
    }

    /** The last {@link #HISTORY_SIZE} games, newest first. */
    public final List<Record> history = new ArrayList<>();
    private boolean gameOpen; // a real game is in progress and not yet recorded

    // ---------------------------------------------------------- tutorial
    public static final int TUTORIAL_STEPS = 5;
    public boolean tutorial;
    public boolean tutorialDone;
    public int tutorialStep;
    public boolean tutTiltLeft, tutTiltRight;
    public float tutThrustTime;

    // Result of the last landing / crash, for the message screens.
    public String message = "";
    public String detail = "";
    public int lastLandingPoints;
    public final List<String> breakdown = new ArrayList<>();
    /** Plain-language reasons for the last landing or crash, with the numbers. */
    public final List<String> explain = new ArrayList<>();

    // ---------------------------------------------------------- rescue missions
    public static final int STRIKES_TO_LOSE_SHIP = 3;
    /** Rescue missions skipped this game (resets after costing a ship). */
    public int strikes;
    public boolean astronautAboard;
    /** Parked on the SOS pad after the pickup: safe until the ship lifts off. */
    private Terrain.Pad parkedOn;

    public float time; // global clock for blinking / animation

    private final Random rng;
    private Listener listener;

    public Game(long seed) {
        rng = new Random(seed);
        resetBackdrop();
    }

    /** Stars and the attract-mode terrain behind the title screen. */
    private void resetBackdrop() {
        worldW = nextWorldW;
        for (int i = 0; i < stars.length; i += 2) {
            stars[i] = rng.nextFloat() * worldW;
            stars[i + 1] = rng.nextFloat() * WORLD_H;
        }
        level = Level.get(1);
        terrain = Terrain.generate(rng, level, worldW);
    }

    /**
     * Tell the game the screen's width/height ratio. Wide phones get a wider
     * playfield so the ground reaches both screen edges. Takes effect at once
     * on the title screen, otherwise from the next level.
     */
    public void setViewAspect(float aspect) {
        float w = Math.max(MIN_WORLD_W, Math.round(WORLD_H * aspect));
        if (w == nextWorldW) return;
        nextWorldW = w;
        if (state == State.TITLE || state == State.GAME_OVER) resetBackdrop();
    }

    private float nextWorldW = MIN_WORLD_W;

    public void setListener(Listener l) {
        listener = l;
    }

    // ============================================================ input

    /**
     * Device roll in degrees: positive when the right side of the screen
     * is tilted down. Called whenever the sensor updates.
     */
    public void setTilt(float rollDegrees) {
        float r = rollDegrees;
        if (Math.abs(r) < TILT_DEADZONE) r = 0;
        else r -= Math.signum(r) * TILT_DEADZONE;
        targetAngle = clamp(r * TILT_GAIN, -MAX_ANGLE, MAX_ANGLE);
    }

    public void setThrust(boolean on) {
        thrustInput = on;
    }

    /**
     * Fire the anti-gravity field. Returns false if none are left, it is
     * already running, or the ship isn't flying.
     */
    public boolean antiGravity() {
        if (state != State.PLAYING || paused || antiGravLeft <= 0 || antiGravTime > 0) return false;
        antiGravLeft--;
        antiGravTime = ANTI_GRAV_SECONDS;
        vx = 0;
        popup("ANTI-GRAV", x, y + 22f);
        if (listener != null) listener.onAntiGravity();
        if (tutorial) {
            antiGravLeft = ANTI_GRAV_CHARGES; // unlimited while learning
            if (tutorialStep == 2) advanceTutorial();
        }
        return true;
    }

    /** A tap that isn't a thrust or anti-gravity control. */
    public void tap() {
        if (paused) return; // the pause menu's buttons handle this
        switch (state) {
            case SPLASH:
                if (stateTime > 0.5f) setState(State.TITLE); // tap to skip
                break;
            case TITLE:
                newGame();
                break;
            case SCORES:
                setState(State.TITLE);
                break;
            case BRIEFING:
                if (stateTime > 0.4f) setState(State.PLAYING);
                break;
            case LANDED:
                if (tutorial) {
                    if (stateTime > 1.0f) {
                        tutorial = false;
                        toTitle();
                    }
                } else if (stateTime > 1.0f && lives <= 0) {
                    recordGame(); // three strikes took the last ship
                    setState(State.GAME_OVER);
                } else if (stateTime > 1.0f) {
                    levelNumber++;
                    shield = Math.min(Level.SHIELD_MAX, shield + Level.SHIELD_LEVEL_BONUS);
                    startLevel(true);
                }
                break;
            case CRASHED:
                if (tutorial) {
                    if (stateTime > 1.0f) respawnTutorial();
                } else if (stateTime > 1.5f) {
                    if (lives > 0) {
                        shield = Level.SHIELD_MAX;
                        startLevel(true); // a fresh, random playfield
                    } else {
                        recordGame();
                        setState(State.GAME_OVER);
                    }
                }
                break;
            case GAME_OVER:
                if (stateTime > 1.5f) toTitle();
                break;
            default:
                break;
        }
    }

    public void pause() {
        if (state == State.PLAYING) paused = true;
        thrustInput = false;
    }

    public void resume() {
        paused = false;
    }

    /** Leave the current game (or tutorial) from the pause menu. */
    public void quitToMenu() {
        paused = false;
        thrustInput = false;
        thrusting = false;
        if (!tutorial) recordGame();
        tutorial = false;
        toTitle();
    }

    public void showScores() {
        setState(State.SCORES);
    }

    private void toTitle() {
        meteors.clear();
        debris.clear();
        popups.clear();
        setState(State.TITLE);
        resetBackdrop();
    }

    // ============================================================ scoreboard

    /** Adds the game just finished to the history (once per game). */
    private void recordGame() {
        if (!gameOpen) return;
        gameOpen = false;
        updateHighScore();
        history.add(0, new Record(score, levelNumber, System.currentTimeMillis()));
        while (history.size() > HISTORY_SIZE) history.remove(history.size() - 1);
        if (listener != null) listener.onGameRecorded();
    }

    /** "score,level,time;..." newest first, for saving. */
    public String historyToString() {
        StringBuilder sb = new StringBuilder();
        for (Record r : history) {
            if (sb.length() > 0) sb.append(';');
            sb.append(r.score).append(',').append(r.level).append(',').append(r.time);
        }
        return sb.toString();
    }

    public void loadHistory(String saved) {
        history.clear();
        if (saved == null || saved.isEmpty()) return;
        for (String item : saved.split(";")) {
            String[] f = item.split(",");
            if (f.length != 3) continue;
            try {
                history.add(new Record(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Long.parseLong(f[2])));
            } catch (NumberFormatException ignored) {
                // skip a damaged entry
            }
            if (history.size() >= HISTORY_SIZE) break;
        }
    }

    // ============================================================ tutorial

    public void startTutorial() {
        tutorial = true;
        gameOpen = false;
        score = 0;
        lives = 99;
        antiGravLeft = ANTI_GRAV_CHARGES;
        levelNumber = 1;
        shield = Level.SHIELD_MAX;
        tutorialStep = 0;
        tutTiltLeft = tutTiltRight = false;
        tutThrustTime = 0;
        startLevel(true); // shows the briefing ("TUTORIAL")
        pickups.clear();
        centerTutorialShip();
    }

    /** Tutorial: start mid-screen, below the instructions, clear of the gauges. */
    private void centerTutorialShip() {
        x = worldW / 2f;
        y = Math.max(290f, terrain.maxHeight(x - 40f, x + 40f) + 90f);
        vx = vy = 0;
    }

    /** After a crash in the tutorial: back in the air, same step, no lives lost. */
    private void respawnTutorial() {
        int step = tutorialStep;
        shield = Level.SHIELD_MAX;
        startLevel(false);
        pickups.clear();
        centerTutorialShip();
        tutorialStep = step;
        if (step == 3) placeTutorialPickup();
        setState(State.PLAYING);
    }

    private void advanceTutorial() {
        tutorialStep++;
        popup("GOOD!", x, y + 30f);
        if (listener != null) listener.onPickup();
        if (tutorialStep == 3) placeTutorialPickup();
    }

    private void placeTutorialPickup() {
        float dir = x < worldW / 2f ? 1f : -1f;
        float px = clamp(x + dir * 110f, 60f, worldW - 60f);
        float py = Math.max(y, terrain.maxHeight(px - 20f, px + 20f) + 70f);
        pickups.add(new Pickup(Pickup.FUEL, px, py, 0f));
    }

    /** Per-frame tutorial bookkeeping: progress checks and a forgiving ship. */
    private void tutorialTick(float dt) {
        fuel = Level.FUEL_MAX;
        oxygen = Level.O2_MAX;
        switch (tutorialStep) {
            case 0:
                if (angle < -20f) tutTiltLeft = true;
                if (angle > 20f) tutTiltRight = true;
                if (tutTiltLeft && tutTiltRight) advanceTutorial();
                break;
            case 1:
                if (thrusting) tutThrustTime += dt;
                if (tutThrustTime >= 1.5f) advanceTutorial();
                break;
            default:
                break;
        }
    }

    /** Practice steps fly without gravity, with gentle drag, so nothing falls out from under you. */
    public boolean tutorialFloat() {
        return tutorial && tutorialStep < 4;
    }

    // ============================================================ flow

    public void newGame() {
        tutorial = false;
        gameOpen = true;
        score = 0;
        lives = 3;
        strikes = 0;
        nextBonusShip = BONUS_SHIP_FIRST;
        antiGravLeft = ANTI_GRAV_CHARGES;
        levelNumber = 1;
        shield = Level.SHIELD_MAX;
        startLevel(true);
    }

    /** Begins the current level. newTerrain=false replays the same layout. */
    public void startLevel(boolean newTerrain) {
        level = Level.get(levelNumber);
        if (newTerrain) levelSeed = rng.nextLong();
        Random lr = new Random(levelSeed);
        if (worldW != nextWorldW) {
            worldW = nextWorldW;
            for (int i = 0; i < stars.length; i += 2) stars[i] = rng.nextFloat() * worldW;
        }
        terrain = Terrain.generate(lr, level, worldW);
        meteors.clear();
        debris.clear();
        popups.clear();

        fuel = level.startFuel;
        oxygen = level.startOxygen;
        noOxygenTime = 0;
        // Start high up near the home pad (the easy, low-paying one), not
        // right on top of it. The high multipliers are further away.
        // Offset toward the middle of the field and start below the HUD, so
        // the ship isn't hidden behind the score or gauges in a top corner.
        Terrain.Pad home = terrain.homePad;
        float inward = home.centerX() < worldW / 2f ? 1f : -1f;
        x = clamp(home.centerX() + inward * (60f + lr.nextFloat() * 110f), 200f, worldW - 200f);
        if (nearestPad(x) != home) x = clamp(home.centerX() + inward * 40f, 200f, worldW - 200f);
        y = WORLD_H - 115f;
        placePickups(lr);
        vx = (lr.nextFloat() * 2 - 1) * 12f;
        vy = 0;
        angle = 0;
        targetAngle = 0;
        thrusting = false;
        astronautAboard = false;
        parkedOn = null;
        antiGravTime = 0;
        shieldFlash = 0;
        hullCooldown = 0;
        windPhase = lr.nextFloat() * 10f;
        windNow = 0;
        meteorClock = 0;
        shipVisible = true;
        setState(State.BRIEFING);
    }

    private void setState(State s) {
        state = s;
        stateTime = 0;
    }

    /**
     * Power-ups go only where they're worth the trip: between the spawn point
     * and the pads (never out past the last pad, where nothing could be
     * reached afterwards), off the pads themselves, and high enough to grab
     * on the way down.
     */
    private void placePickups(Random r) {
        pickups.clear();
        int fuelCount = level.fuelPickups, o2Count = level.oxygenPickups;
        // POWERUP_BONUS more on average: the fractional part is a chance of one extra.
        float extra = (fuelCount + o2Count) * (Level.POWERUP_BONUS - 1f);
        int extras = (int) extra + (r.nextFloat() < extra - (int) extra ? 1 : 0);
        for (int i = 0; i < extras; i++) {
            if (r.nextBoolean()) fuelCount++;
            else o2Count++;
        }
        int shieldCount = level.shieldPickups;
        int placed = 0;
        pickupsReplaced = 0;

        // The useful strip: from the spawn/home pad to the far pad.
        stripLo = x;
        stripHi = x;
        for (Terrain.Pad p : terrain.pads) {
            stripLo = Math.min(stripLo, p.x1);
            stripHi = Math.max(stripHi, p.x2);
        }
        stripLo = Math.max(40f, stripLo - 30f);
        stripHi = Math.min(worldW - 40f, stripHi + 30f);

        // One fuel tank always sits on the way to the top-paying pad, so going
        // for X5 is a gamble you can win if you pick it up.
        float[] route = routeSpot(r);
        if (route != null && fuelCount > 0) {
            Pickup p = new Pickup(Pickup.FUEL, route[0], route[1], r.nextFloat() * 6f);
            p.onRoute = true;
            pickups.add(p);
            placed++;
            fuelCount--;
        }
        // What's left to place, most important first (a crowded strip may not fit all).
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < shieldCount; i++) todo.add(Pickup.SHIELD);
        for (int i = 0; i < Math.max(fuelCount, o2Count); i++) {
            if (i < fuelCount) todo.add(Pickup.FUEL);
            if (i < o2Count) todo.add(Pickup.OXYGEN);
        }
        for (int type : todo) {
            float[] spot = findSpot(r, 40f);
            if (spot == null) break;
            pickups.add(new Pickup(type, spot[0], spot[1], r.nextFloat() * 6f));
        }
    }

    /** Edges of the strip where power-ups are worth having (set per level). */
    private float stripLo, stripHi;

    /** A spot partway along the trip from the home pad to the top-paying pad, or null. */
    private float[] routeSpot(Random r) {
        Terrain.Pad best = terrain.homePad;
        for (Terrain.Pad p : terrain.pads) if (p.multiplier > best.multiplier) best = p;
        if (best == terrain.homePad) return null;
        float px = terrain.homePad.centerX()
                + (best.centerX() - terrain.homePad.centerX()) * (0.4f + r.nextFloat() * 0.2f);
        float ground = terrain.maxHeight(px - 40f, px + 40f);
        float py = Math.min(ground + 90f + r.nextFloat() * 40f, WORLD_H - 150f);
        return new float[]{px, py};
    }

    /**
     * A free spot for a power-up inside the useful strip: off the pads, well
     * above the rocks, below the gauges, spaced from other power-ups and at
     * least avoidRadius from the ship. Returns {x, y} or null if none fits.
     */
    private float[] findSpot(Random r, float avoidRadius) {
        for (int tries = 0; tries < 1500; tries++) {
            float px = stripLo + r.nextFloat() * (stripHi - stripLo);
            if (terrain.overPad(px, 25f)) continue;
            float ground = terrain.maxHeight(px - 16f, px + 16f);
            float clearance = level.pickupMinClearance
                    + r.nextFloat() * (level.pickupMaxClearance - level.pickupMinClearance);
            // Stay clear of the gauges: lower it over tall ground, as long as
            // it still floats well above the rocks.
            float py = Math.min(ground + clearance, WORLD_H - 150f);
            if (py - ground < 45f) continue;
            float sdx = px - centerX(), sdy = py - centerY();
            if (sdx * sdx + sdy * sdy < avoidRadius * avoidRadius) continue;
            boolean crowded = false;
            for (Pickup p : pickups) {
                if (p.taken) continue;
                // Prefer some spacing; tighten it if the strip is too crowded to fit them all.
                if (Math.abs(p.x - px) < (tries < 750 ? 55f : 30f)) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) return new float[]{px, py};
        }
        return null;
    }

    /**
     * A meteor smashed this power-up: it's gone, and a replacement of the same
     * kind appears somewhere else useful (the X5 route tank stays on the route).
     */
    private void destroyPickup(Pickup p) {
        p.taken = true;
        p.destroyed = true;
        sparks(p.x, p.y, 14);
        // Named by the icon letter, so "S" isn't mistaken for your ship's shield.
        String name = p.type == Pickup.FUEL ? "F" : p.type == Pickup.OXYGEN ? "O2" : "S";
        popup(name + " DESTROYED", p.x, p.y);
        float[] spot = p.onRoute ? routeSpot(rng) : null;
        if (spot == null) spot = findSpot(rng, 90f);
        if (spot == null) return;
        Pickup n = new Pickup(p.type, spot[0], spot[1], rng.nextFloat() * 6f);
        n.onRoute = p.onRoute;
        n.appear = Pickup.APPEAR_SECONDS;
        pickups.add(n);
        pickupsReplaced++;
    }

    /** How many power-ups meteors have destroyed (and were replaced) this level. */
    public int pickupsReplaced;

    // ============================================================ update

    public void update(float dt) {
        time += dt;
        if (paused) return;
        stateTime += dt;
        if (state == State.SPLASH && stateTime >= SPLASH_SECONDS && assetsReady) setState(State.TITLE);
        updateDebris(dt);
        updatePopups(dt);
        if (state == State.PLAYING) {
            updateShip(dt);
            updateMeteors(dt);
        } else if (state == State.CRASHED || state == State.LANDED) {
            updateMeteors(dt);
        }
    }

    private void updateShip(float dt) {
        // Rotate toward the tilt angle.
        float diff = targetAngle - angle;
        float step = ROTATE_SPEED * dt;
        angle += clamp(diff, -step, step);

        boolean antiGrav = antiGravTime > 0;
        if (antiGrav) antiGravTime = Math.max(0, antiGravTime - dt);
        boolean floating = tutorialFloat();
        float ax = 0, ay = antiGrav || floating ? 0 : -level.gravity;
        if (floating) {
            float drag = Math.max(0f, 1f - 1.2f * dt);
            vx *= drag;
            vy *= drag;
        }
        thrusting = thrustInput && fuel > 0;
        if (thrusting) {
            double a = Math.toRadians(angle);
            ax += Level.THRUST * (float) Math.sin(a);
            ay += Level.THRUST * (float) Math.cos(a);
            fuel = Math.max(0, fuel - Level.FUEL_BURN * dt);
        }
        if (level.wind > 0) {
            windPhase += dt;
            windNow = level.wind * (0.6f * (float) Math.sin(windPhase * 0.35f)
                    + 0.4f * (float) Math.sin(windPhase * 1.3f + 1.7f));
            ax += windNow;
        }
        if (antiGrav) {
            ax = 0;  // sideways movement stays locked for the whole second
            vx = 0;
        }

        vx += ax * dt;
        vy += ay * dt;
        x += vx * dt;
        y += vy * dt;

        // Side walls and ceiling.
        if (x < 16f) {
            x = 16f;
            vx = Math.abs(vx) * 0.3f;
        } else if (x > worldW - 16f) {
            x = worldW - 16f;
            vx = -Math.abs(vx) * 0.3f;
        }
        if (y > WORLD_H - 14f) {
            y = WORLD_H - 14f;
            if (vy > 0) vy = 0;
        }

        // Oxygen.
        oxygen -= dt;
        if (oxygen <= 0) {
            oxygen = 0;
            noOxygenTime += dt;
            if (noOxygenTime >= Level.O2_GRACE) {
                crash("OUT OF OXYGEN", "PILOT LOST CONSCIOUSNESS",
                        "THE TANK RAN DRY, THEN THE " + (int) Level.O2_GRACE + " SECOND RESERVE",
                        "GRAB O2 POWER-UPS ON LONG FLIGHTS");
                return;
            }
        } else {
            noOxygenTime = 0;
        }

        if (shieldFlash > 0) shieldFlash -= dt;
        if (hullCooldown > 0) hullCooldown -= dt;

        // Pick-ups.
        float cx = centerX(), cy = centerY();
        for (int i = 0; i < pickups.size(); i++) {
            Pickup p = pickups.get(i);
            if (p.appear > 0) p.appear = Math.max(0, p.appear - dt);
            if (p.taken || p.appear > 0) continue;
            float dx = p.x - cx, dy = p.y - cy;
            if (dx * dx + dy * dy < 20f * 20f) {
                p.taken = true;
                if (p.type == Pickup.FUEL) {
                    fuel = Math.min(Level.FUEL_MAX, fuel + Level.FUEL_PICKUP);
                    popup("+FUEL", p.x, p.y);
                } else if (p.type == Pickup.OXYGEN) {
                    oxygen = Math.min(Level.O2_MAX, oxygen + Level.O2_PICKUP);
                    noOxygenTime = 0;
                    popup("+O2", p.x, p.y);
                } else {
                    shield = Math.min(Level.SHIELD_MAX, shield + Level.SHIELD_PICKUP);
                    shieldFlash = 0.5f;
                    popup("+SHIELD", p.x, p.y);
                }
                if (!tutorial) addScore(25);
                if (listener != null) listener.onPickup();
                if (tutorial && tutorialStep == 3) advanceTutorial();
            }
        }

        if (tutorial && state == State.PLAYING) tutorialTick(dt);
        if (state == State.PLAYING) checkGround();
    }

    private void checkGround() {
        if (parkedOn != null) {
            float restY = parkedOn.y - FOOT_Y;
            if (y > restY + 3f) {
                parkedOn = null; // lifted off
            } else {
                if (y < restY) y = restY;
                vx = 0;
                vy = Math.max(0, vy);
                return;
            }
        }
        double a = Math.toRadians(angle);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);

        // Feet.
        float lfx = x + FOOT_X * -1 * cos + FOOT_Y * sin;
        float lfy = y - FOOT_X * -1 * sin + FOOT_Y * cos;
        float rfx = x + FOOT_X * cos + FOOT_Y * sin;
        float rfy = y - FOOT_X * sin + FOOT_Y * cos;
        boolean leftDown = lfy <= terrain.heightAt(lfx);
        boolean rightDown = rfy <= terrain.heightAt(rfx);

        // Hull.
        boolean hullHit = false;
        float hitX = 0, hitDepth = 0;
        for (int i = 0; i < HULL_POINTS.length; i += 2) {
            float px = x + HULL_POINTS[i] * cos + HULL_POINTS[i + 1] * sin;
            float py = y - HULL_POINTS[i] * sin + HULL_POINTS[i + 1] * cos;
            float g = terrain.heightAt(px);
            if (py <= g && g - py >= hitDepth) {
                hullHit = true;
                hitX = px;
                hitDepth = g - py;
            }
        }

        if (!hullHit && !leftDown && !rightDown) return;

        Terrain.Pad pad = terrain.padSpanning(Math.min(lfx, rfx), Math.max(lfx, rfx));
        if (!hullHit && pad != null && Math.abs(angle) < Level.TIP_OVER_ANGLE) {
            touchDown(pad);
            return;
        }

        // Rough terrain contact: the shield may bounce us off.
        if (!hullHit) {
            if (leftDown) {
                hitX = lfx;
                hitDepth = terrain.heightAt(lfx) - lfy;
            } else {
                hitX = rfx;
                hitDepth = terrain.heightAt(rfx) - rfy;
            }
        }
        float speed = (float) Math.hypot(vx, vy);
        float damage = 10f + Math.max(0, speed - 6f) * 5f;
        if (hullCooldown > 0) damage = 0;
        if (bubbleShield() && damage > 0) {
            // The anti-gravity bubble absorbs it: bounce, no cost.
            shieldFlash = 0.5f;
            hullCooldown = 0.3f;
            if (listener != null) listener.onShieldHit();
            sparks(hitX, terrain.heightAt(hitX), 8);
            damage = 0;
        }
        if (damage > 0 && damage >= shield) {
            String cost = "THAT NEEDED " + Math.round(damage) + " SHIELD. YOU HAD " + Math.round(shield);
            if (pad != null || terrain.overPad(hitX, 0)) {
                crash("TIPPED OVER", "THE HULL HIT THE PAD BEFORE THE LEGS",
                        "THE SHIP WAS TILTED " + Math.round(Math.abs(angle)) + " DEG",
                        "LAND LEGS DOWN, UNDER " + (int) Level.TIP_OVER_ANGLE + " DEG OF TILT",
                        cost);
            } else {
                crash("CRASHED", "HIT THE ROCKS OFF THE PADS",
                        "IMPACT SPEED " + Math.round(speed),
                        cost,
                        "ONLY THE FLAT PADS ARE SAFE TO TOUCH");
            }
            return;
        }
        shield -= damage;
        if (damage > 0) {
            shieldFlash = 0.5f;
            hullCooldown = 0.3f;
            if (listener != null) listener.onShieldHit();
            sparks(hitX, terrain.heightAt(hitX), 8);
        }
        // Bounce along the ground normal.
        float slope = (terrain.heightAt(hitX + 2f) - terrain.heightAt(hitX - 2f)) / 4f;
        float nx = -slope, ny = 1f;
        float len = (float) Math.hypot(nx, ny);
        nx /= len;
        ny /= len;
        float vn = vx * nx + vy * ny;
        if (vn < 0) {
            float e = 0.45f;
            vx -= (1 + e) * vn * nx;
            vy -= (1 + e) * vn * ny;
        }
        // Push out of the ground.
        x += nx * (hitDepth + 0.5f);
        y += ny * (hitDepth + 0.5f);
    }

    private void touchDown(Terrain.Pad pad) {
        if (tutorialFloat()) {
            // Practice steps: just bob back up off the pad.
            y = pad.y - FOOT_Y + 1f;
            vy = Math.abs(vy) * 0.2f + 4f;
            return;
        }
        if (pad.rescue && astronautAboard && -vy < 2f && Math.abs(vx) < 2f) {
            // Landing back on the SOS pad gently: just park again.
            parkedOn = pad;
            vx = 0;
            vy = Math.max(0, vy);
            return;
        }
        float down = -vy;
        float side = Math.abs(vx);
        float tilt = Math.abs(angle);
        float damage = Math.max(0, down - Level.SAFE_VSPEED) * 6f
                + Math.max(0, side - Level.SAFE_HSPEED) * 5f
                + Math.max(0, tilt - Level.SAFE_ANGLE) * 1.2f;
        String stats = Math.round(down) + " DOWN, " + Math.round(side) + " SIDEWAYS, " + Math.round(tilt) + " DEG TILT";
        String over = overLimits(down, side, tilt);
        boolean bubble = bubbleShield() && damage > 0;
        if (bubble) damage = 0; // anti-gravity bubble takes the impact
        if (damage > shield) {
            String why = down - Level.SAFE_VSPEED > side - Level.SAFE_HSPEED
                    ? "COMING DOWN TOO FAST" : "MOVING SIDEWAYS TOO FAST";
            crash("CRASH LANDING", why,
                    "TOUCHDOWN: " + stats,
                    "OVER THE SAFE LIMIT: " + over,
                    "THAT NEEDED " + Math.round(damage) + " SHIELD. YOU HAD " + Math.round(shield));
            return;
        }
        boolean perfect = damage == 0 && !bubble;
        shield -= damage;

        if (pad.rescue) {
            // The SOS pad: pick up the astronaut (once), refuel and repair, then
            // sit here until the pilot takes off again.
            if (y < pad.y - FOOT_Y) y = pad.y - FOOT_Y; // sit on the pad, but let thrust lift off
            vx = 0;
            vy = Math.max(0, vy);
            if (!astronautAboard && !tutorial) {
                astronautAboard = true;
                fuel = Level.FUEL_MAX;
                shield = Level.SHIELD_MAX;
                oxygen = Level.O2_MAX;
                noOxygenTime = 0;
                vy = 0;
                parkedOn = pad; // sit safely (tilt freely) until lifting off
                popup("ASTRONAUT ABOARD", x, y + 30f);
                popup("REFUELED. SHIELD REPAIRED", x, y + 10f);
                if (listener != null) listener.onPickup();
            }
            return;
        }
        // Settle the lander on the pad.
        y = pad.y - FOOT_Y;
        vx = vy = 0;
        angle = 0;
        thrusting = false;

        explain.clear();
        explain.add("TOUCHDOWN: " + stats);
        if (perfect) explain.add("ALL UNDER THE SAFE LIMITS (" + (int) Level.SAFE_VSPEED + " DOWN, "
                + (int) Level.SAFE_HSPEED + " SIDEWAYS, " + (int) Level.SAFE_ANGLE + " DEG)");
        else if (bubble) explain.add("OVER THE LIMIT: " + over + ". THE ANTI-GRAV SHIELD TOOK IT");
        else explain.add("OVER THE LIMIT: " + over + ". YOUR SHIELD TOOK " + Math.round(damage));

        breakdown.clear();
        int pts = 50 * pad.multiplier;
        breakdown.add("PAD X" + pad.multiplier + "  " + pts);
        if (perfect) {
            pts += 50;
            breakdown.add("SOFT LANDING  50");
        }
        if (level.rescue && !tutorial) {
            if (astronautAboard) {
                pts += Level.RESCUE_BONUS;
                breakdown.add("ASTRONAUT RESCUED  " + Level.RESCUE_BONUS);
            } else {
                strikes++;
                breakdown.add("ASTRONAUT LEFT BEHIND: STRIKE " + strikes + " OF " + STRIKES_TO_LOSE_SHIP);
                if (strikes >= STRIKES_TO_LOSE_SHIP) {
                    strikes = 0;
                    lives--;
                    breakdown.add(STRIKES_TO_LOSE_SHIP + " STRIKES: YOU LOSE A SHIP");
                }
            }
        }
        if (!tutorial && levelNumber % Level.ANTI_GRAV_BONUS_EVERY == 0 && antiGravLeft < ANTI_GRAV_CHARGES) {
            antiGravLeft++;
            breakdown.add("BONUS: +1 ANTI-GRAV CHARGE");
        }
        int fuelBonus = Math.round(fuel / 10f);
        int o2Bonus = Math.round(oxygen) * 2;
        breakdown.add("FUEL LEFT  " + fuelBonus);
        breakdown.add("OXYGEN LEFT  " + o2Bonus);
        pts += fuelBonus + o2Bonus;
        if (tutorial) {
            message = "TUTORIAL COMPLETE";
            detail = "YOU'RE READY TO FLY";
            breakdown.clear();
            tutorialStep = TUTORIAL_STEPS;
            tutorialDone = true;
            setState(State.LANDED);
            if (listener != null) {
                listener.onLanded(perfect);
                listener.onTutorialDone();
            }
            return;
        }
        lastLandingPoints = pts;
        if (addScore(pts)) breakdown.add("EXTRA SHIP!");
        message = perfect ? "PERFECT LANDING" : "HARD LANDING";
        if (level.rescue && !astronautAboard && !tutorial) message = "ASTRONAUT LEFT BEHIND";
        detail = "LEVEL " + levelNumber + " COMPLETE";
        updateHighScore();
        setState(State.LANDED);
        if (listener != null) listener.onLanded(perfect);
    }

    private void crash(String msg, String why, String... reasons) {
        message = msg;
        detail = why;
        explain.clear();
        for (String r : reasons) explain.add(r);
        lives--;
        thrusting = false;
        shipVisible = false;
        explode();
        updateHighScore();
        setState(State.CRASHED);
        if (listener != null) listener.onCrashed();
    }

    /**
     * Adds points and hands out extra ships (at 1500, 4500, 7500, ...). If
     * you're already at MAX_LIVES the bonus just passes. Returns true if a
     * ship was awarded.
     */
    private boolean addScore(int pts) {
        score += pts;
        boolean awarded = false;
        while (score >= nextBonusShip) {
            nextBonusShip += BONUS_SHIP_EVERY;
            if (lives < MAX_LIVES) {
                lives++;
                awarded = true;
            }
        }
        if (awarded) {
            popup("EXTRA SHIP!", x, y + 40f);
            if (listener != null) listener.onExtraShip();
        }
        return awarded;
    }

    /** Which landing limits were exceeded, e.g. "17 DOWN (MAX 12)". */
    private static String overLimits(float down, float side, float tilt) {
        StringBuilder sb = new StringBuilder();
        if (down > Level.SAFE_VSPEED) sb.append(Math.round(down)).append(" DOWN (MAX ").append((int) Level.SAFE_VSPEED).append(')');
        if (side > Level.SAFE_HSPEED) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Math.round(side)).append(" SIDEWAYS (MAX ").append((int) Level.SAFE_HSPEED).append(')');
        }
        if (tilt > Level.SAFE_ANGLE) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Math.round(tilt)).append(" DEG TILT (MAX ").append((int) Level.SAFE_ANGLE).append(')');
        }
        return sb.length() == 0 ? "NONE" : sb.toString();
    }

    private void updateHighScore() {
        if (tutorial) return;
        if (score > highScore) {
            highScore = score;
            highScoreLevel = levelNumber;
            if (listener != null) listener.onHighScore(highScore, highScoreLevel);
        }
    }

    // ------------------------------------------------------------ meteors

    private void updateMeteors(float dt) {
        if (state == State.PLAYING && level.meteorRate > 0) {
            meteorClock += dt * level.meteorRate;
            while (meteorClock >= 1f) {
                meteorClock -= 1f;
                float mx = -100f + rng.nextFloat() * (worldW + 200f);
                float mvx = (worldW / 2f - mx) * 0.08f + (rng.nextFloat() * 2 - 1) * 25f;
                float mvy = -(80f + rng.nextFloat() * 70f);
                // Spawn well above the screen so the warning shows before it appears.
                meteors.add(new Meteor(rng, mx, WORLD_H + 140f, mvx, mvy));
            }
        }
        Iterator<Meteor> it = meteors.iterator();
        while (it.hasNext()) {
            Meteor m = it.next();
            m.x += m.vx * dt;
            m.y += m.vy * dt;
            m.angle += m.spin * dt;
            if (m.y - m.radius * 0.5f <= terrain.heightAt(m.x) || m.x < -150 || m.x > worldW + 150) {
                if (m.x > 0 && m.x < worldW) sparks(m.x, terrain.heightAt(m.x), 10);
                it.remove();
                continue;
            }
            // A meteor that clips a power-up has a chance to destroy it (one
            // roll per meteor per power-up); a replacement appears elsewhere.
            boolean smashed = false;
            for (int i = 0; i < pickups.size() && !smashed; i++) {
                Pickup p = pickups.get(i);
                if (p.taken || p.appear > 0 || m.grazed == p) continue;
                float pdx = m.x - p.x, pdy = m.y - p.y;
                float pr = m.radius + 5f;
                if (pdx * pdx + pdy * pdy < pr * pr) {
                    m.grazed = p;
                    if (rng.nextFloat() < METEOR_SMASH_CHANCE) {
                        destroyPickup(p);
                        smashed = true;
                    }
                }
            }
            if (smashed) {
                it.remove();
                continue;
            }
            if (state == State.PLAYING) {
                float dx = m.x - centerX(), dy = m.y - centerY();
                float rr = m.radius + 11f;
                if (dx * dx + dy * dy < rr * rr) {
                    it.remove();
                    sparks(m.x, m.y, 12);
                    float damage = bubbleShield() ? 0f : 35f;
                    if (damage > 0 && damage >= shield) {
                        crash("HIT BY A METEOR", "THE SHIELD WAS TOO WEAK",
                                "A HIT TAKES " + Math.round(damage) + " SHIELD. YOU HAD " + Math.round(shield),
                                "EDGE ARROWS WARN YOU. ANTI-GRAV BLOCKS HITS");
                        return;
                    }
                    shield -= damage;
                    shieldFlash = 0.6f;
                    vx += m.vx * 0.12f;
                    vy += m.vy * 0.12f;
                    if (listener != null) listener.onShieldHit();
                }
            }
        }
    }

    // ------------------------------------------------------------ effects

    private void explode() {
        double a = Math.toRadians(angle);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        for (int i = 0; i < SHIP_LINES.length; i += 4) {
            float mx = (SHIP_LINES[i] + SHIP_LINES[i + 2]) / 2f;
            float my = (SHIP_LINES[i + 1] + SHIP_LINES[i + 3]) / 2f;
            float dx = SHIP_LINES[i + 2] - SHIP_LINES[i];
            float dy = SHIP_LINES[i + 3] - SHIP_LINES[i + 1];
            Debris d = new Debris();
            d.x = x + mx * cos + my * sin;
            d.y = y - mx * sin + my * cos;
            d.halfLen = (float) Math.hypot(dx, dy) / 2f;
            d.angle = (float) Math.toDegrees(Math.atan2(dy, dx)) - angle;
            float out = 20f + rng.nextFloat() * 50f;
            float dir = (float) Math.atan2(d.y - y + 0.01f, d.x - x);
            d.vx = vx * 0.3f + (float) Math.cos(dir) * out;
            d.vy = Math.max(vy * 0.3f, -10f) + (float) Math.sin(dir) * out + 25f;
            d.spin = (rng.nextFloat() * 2 - 1) * 540f;
            d.maxLife = d.life = 2.2f + rng.nextFloat() * 1.2f;
            debris.add(d);
        }
        sparks(x, y, 30);
    }

    private void sparks(float sx, float sy, int n) {
        for (int i = 0; i < n; i++) {
            Debris d = new Debris();
            d.x = sx;
            d.y = sy + 1f;
            double dir = rng.nextFloat() * Math.PI;
            float sp = 15f + rng.nextFloat() * 70f;
            d.vx = (float) Math.cos(dir) * sp;
            d.vy = (float) Math.sin(dir) * sp;
            d.halfLen = 0;
            d.maxLife = d.life = 0.4f + rng.nextFloat() * 0.8f;
            debris.add(d);
        }
    }

    private void updateDebris(float dt) {
        Iterator<Debris> it = debris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            d.life -= dt;
            if (d.life <= 0) {
                it.remove();
                continue;
            }
            d.vy -= level.gravity * dt;
            d.x += d.vx * dt;
            d.y += d.vy * dt;
            d.angle += d.spin * dt;
            float g = terrain.heightAt(d.x);
            if (d.y < g) {
                d.y = g;
                d.vy = Math.abs(d.vy) * 0.3f;
                d.vx *= 0.5f;
                d.spin *= 0.5f;
            }
        }
    }

    private void popup(String text, float px, float py) {
        Popup p = new Popup();
        p.text = text;
        p.x = px;
        p.y = py;
        p.life = 1.2f;
        popups.add(p);
    }

    private void updatePopups(float dt) {
        Iterator<Popup> it = popups.iterator();
        while (it.hasNext()) {
            Popup p = it.next();
            p.life -= dt;
            p.y += 25f * dt;
            if (p.life <= 0) it.remove();
        }
    }

    // ------------------------------------------------------------ queries

    public Terrain.Pad nearestPad(float px) {
        Terrain.Pad best = null;
        for (Terrain.Pad p : terrain.pads)
            if (best == null || Math.abs(p.centerX() - px) < Math.abs(best.centerX() - px)) best = p;
        return best;
    }

    /** How far ahead (seconds) a meteor on a collision course gets flagged. */
    public static final float THREAT_HORIZON = 4f;

    /**
     * Seconds until this meteor passes close enough to hit the ship if
     * neither changes course, or -1 if it's going to miss (or is too far off).
     */
    public float meteorThreat(Meteor m) {
        float dx = m.x - centerX(), dy = m.y - centerY();
        float ux = m.vx - vx, uy = m.vy - vy;
        float uu = ux * ux + uy * uy;
        if (uu < 1e-3f) return -1;
        float t = -(dx * ux + dy * uy) / uu;      // time of closest approach
        if (t < 0 || t > THREAT_HORIZON) return -1;
        float cx = dx + ux * t, cy = dy + uy * t;
        float hit = m.radius + 26f;                 // a little margin around the hull
        return cx * cx + cy * cy < hit * hit ? t : -1;
    }

    public float centerX() {
        return x + CENTER_Y * (float) Math.sin(Math.toRadians(angle));
    }

    public float centerY() {
        return y + CENTER_Y * (float) Math.cos(Math.toRadians(angle));
    }

    /** Height of the feet above the ground directly below. */
    public float altitude() {
        return y + FOOT_Y - terrain.heightAt(x);
    }

    public boolean vSpeedUnsafe() {
        return -vy > Level.SAFE_VSPEED;
    }

    public boolean hSpeedUnsafe() {
        return Math.abs(vx) > Level.SAFE_HSPEED;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
