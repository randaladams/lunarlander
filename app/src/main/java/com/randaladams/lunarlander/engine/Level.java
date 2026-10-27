package com.randaladams.lunarlander.engine;

/**
 * Difficulty settings for a level. Everything that gets harder as levels
 * progress is computed here so the curve is easy to tune in one place.
 *
 * World units: the playfield is {@link Game#worldW} x {@link Game#WORLD_H};
 * the lander is about 28 units wide. Speeds are units/second.
 */
public final class Level {
    public final int number;

    /** Downward acceleration. */
    public final float gravity;
    /** Fuel in the tank at the start of the level (tank holds FUEL_MAX). */
    public final float startFuel;
    /** Seconds of oxygen at the start of the level (tank holds O2_MAX). */
    public final float startOxygen;

    public final int padCount;
    public final float widestPad;
    public final float narrowestPad;
    /** Height variation of the terrain. */
    public final float terrainAmplitude;
    /** Small-scale jaggedness of the terrain. */
    public final float terrainJag;

    /** Peak horizontal wind acceleration (0 = no wind). */
    public final float wind;
    /** Meteors per second (0 = none). */
    public final float meteorRate;

    public final int fuelPickups;
    public final int oxygenPickups;
    public final int shieldPickups;
    /**
     * Rescue mission: land on the SOS pad to pick up an astronaut (refuel and
     * shield repair), then land again on a scoring pad.
     */
    public final boolean rescue;
    /** Pick-ups float between these heights above the ground. */
    public final float pickupMinClearance;
    public final float pickupMaxClearance;

    // Constants shared by all levels.
    /**
     * Engine push. Originally 24; raised 10%, then another 5% (26.4 -> 27.72).
     * Fuel burn per second is unchanged, so each unit of fuel buys more push.
     */
    public static final float THRUST = 27.72f;
    /** Rescue missions start here, then every second level. */
    public static final int RESCUE_FROM = 10;
    public static final int RESCUE_BONUS = 250;
    /** An extra anti-gravity charge after every this-many levels (never above the max). */
    public static final int ANTI_GRAV_BONUS_EVERY = 5;
    public static final float FUEL_MAX = 1000f;
    public static final float FUEL_BURN = 70f;     // per second of thrust
    public static final float FUEL_PICKUP = 350f;
    /** Multiplier on the number of fuel/O2 power-ups per level (1.05 = 5% more). */
    public static final float POWERUP_BONUS = 1.05f;
    public static final float O2_MAX = 60f;        // seconds
    public static final float O2_PICKUP = 22f;
    public static final float SHIELD_PICKUP = 35f;
    public static final float O2_GRACE = 6f;       // seconds you can fly with an empty tank
    public static final float SHIELD_MAX = 100f;
    public static final float SHIELD_LEVEL_BONUS = 20f;

    // Landing limits. Anything over these must be absorbed by the shield.
    public static final float SAFE_VSPEED = 12f;
    public static final float SAFE_HSPEED = 7f;
    public static final float SAFE_ANGLE = 8f;     // degrees
    public static final float TIP_OVER_ANGLE = 30f;

    private Level(int n) {
        number = n;
        int k = n - 1;
        // Capped so the tank always holds enough fuel to brake from the spawn
        // height (earlier, levels 7+ could not be landed without power-ups).
        gravity = Math.min(10.5f, 7.5f + 0.4f * k);
        // Floor checked by the desktop autopilot: enough to brake from spawn,
        // cross to the nearest pad and fight the wind (700 left levels 11+
        // mostly unwinnable).
        startFuel = Math.max(850f, 1000f - 25f * k);
        startOxygen = Math.max(30f, 55f - 3f * k);

        // Never fewer than 2 pads: they're spread across the whole surface so
        // one is always within reach of the spawn point (see Terrain/Game).
        padCount = n <= 3 ? 4 : (n <= 8 ? 3 : 2);
        widestPad = Math.max(60f, 120f - 8f * k);
        narrowestPad = Math.max(38f, widestPad * 0.5f);
        terrainAmplitude = Math.min(230f, 120f + 15f * k);
        terrainJag = Math.min(28f, 10f + 2f * k);

        // Capped: stronger wind mostly just drained fuel holding position.
        wind = n >= 3 ? Math.min(3f, 1f + 0.3f * (n - 3)) : 0f;
        meteorRate = n >= 4 ? Math.min(1.2f, 0.25f + 0.12f * (n - 4)) : 0f;

        fuelPickups = 2 + (n % 2);
        oxygenPickups = 2;
        shieldPickups = 1;
        rescue = n >= RESCUE_FROM && (n - RESCUE_FROM) % 2 == 0;
        // High enough to grab on the way down without climbing back up.
        pickupMinClearance = 70f;
        pickupMaxClearance = Math.max(140f, 200f - 6f * k);
    }

    public static Level get(int n) {
        return new Level(Math.max(1, n));
    }

    /** Short description of what's new on this level, shown before it starts. */
    public String briefing() {
        if (rescue) return "RESCUE: LAND AT THE SOS PAD, THEN ON ANY PAD";
        switch (number) {
            case 1: return "LAND ON A FLAT PAD";
            case 2: return "GRAVITY IS STRONGER";
            case 3: return "WARNING: SOLAR WIND";
            case 4: return "WARNING: METEOR SHOWER";
            case 5: return "FEWER PADS. LESS FUEL";
            default: return "HAZARDS INCREASING";
        }
    }
}
