package com.randaladams.lunarlander;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** Engine rumble (looping while thrusting), crash, shield and meteor-warning sounds. */
final class Sounds {
    private static final float THRUST_VOLUME = 0.55f;

    private final SoundPool pool;
    private static final long WARNING_GAP_MS = 1500; // several meteors at once → one alert

    private final int thrust, crash, shield, warning;
    private long lastWarning;
    private volatile int loadedCount;
    private int thrustStream;   // 0 = not playing

    Sounds(Context context) {
        pool = new SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((p, id, status) -> {
            if (status == 0) loadedCount++;
        });
        thrust = pool.load(context, R.raw.thrust, 1);
        crash = pool.load(context, R.raw.crash, 1);
        shield = pool.load(context, R.raw.shield, 1);
        warning = pool.load(context, R.raw.warning, 1);
    }

    private boolean ready() {
        return loadedCount >= 4;
    }

    /** Start or stop the engine loop. Cheap to call every frame. */
    synchronized void setThrust(boolean on) {
        if (on && thrustStream == 0 && ready()) {
            thrustStream = pool.play(thrust, THRUST_VOLUME, THRUST_VOLUME, 2, -1, 1f);
        } else if (!on && thrustStream != 0) {
            pool.stop(thrustStream);
            thrustStream = 0;
        }
    }

    void crash() {
        if (ready()) pool.play(crash, 1f, 1f, 3, 0, 1f);
    }

    void shield() {
        if (ready()) pool.play(shield, 0.8f, 0.8f, 2, 0, 1f);
    }

    void warning() {
        long now = System.currentTimeMillis();
        if (!ready() || now - lastWarning < WARNING_GAP_MS) return;
        lastWarning = now;
        pool.play(warning, 0.7f, 0.7f, 2, 0, 1f);
    }

    void release() {
        setThrust(false);
        pool.release();
    }
}
