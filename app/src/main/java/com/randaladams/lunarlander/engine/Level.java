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
    /** Pick-ups float between these heights above the ground. Lower = riskier. */
    public final float pickupMinClearance;
    public final float pickupMaxClearance;

    // Constants shared by all levels.
    public static final float THRUST = 24f;
    public static final float FUEL_MAX = 1000f;
    public static final float FUEL_BURN = 70f;     // per second of thrust
    public static final float FUEL_PICKUP = 280f;
    public static final float O2_MAX = 60f;        // seconds
    public static final float O2_PICKUP = 18f;
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
        gravity = Math.min(14f, 7.5f + 0.7f * k);
        startFuel = Math.max(450f, 1000f - 70f * k);
        startOxygen = Math.max(22f, 55f - 4f * k);

        padCount = Math.max(1, 4 - k / 2);
        widestPad = Math.max(48f, 120f - 10f * k);
        narrowestPad = Math.max(34f, widestPad * 0.45f);
        terrainAmplitude = Math.min(230f, 120f + 15f * k);
        terrainJag = Math.min(28f, 10f + 2f * k);

        wind = n >= 3 ? Math.min(5f, 1f + 0.5f * (n - 3)) : 0f;
        meteorRate = n >= 4 ? Math.min(1.2f, 0.25f + 0.12f * (n - 4)) : 0f;

        fuelPickups = 2 + (n % 2);
        oxygenPickups = 2;
        pickupMinClearance = 25f;
        pickupMaxClearance = Math.max(45f, 140f - 12f * k);
    }

    public static Level get(int n) {
        return new Level(Math.max(1, n));
    }

    /** Short description of what's new on this level, shown before it starts. */
    public String briefing() {
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
