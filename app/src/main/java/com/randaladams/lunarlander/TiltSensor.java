package com.randaladams.lunarlander;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.Display;
import android.view.Surface;

/**
 * Reads the accelerometer and reports how far the device is rolled left or
 * right, measured relative to the screen as it is currently displayed (so it
 * works in either landscape direction, on phones and tablets alike).
 */
final class TiltSensor implements SensorEventListener {

    interface Listener {
        /** Degrees; positive when the right edge of the screen is tilted down. */
        void onTilt(float rollDegrees);
    }

    private static final float SMOOTHING = 0.25f; // low-pass filter, 0..1

    private final Activity activity;
    private final SensorManager sensors;
    private final Sensor accel;
    private final Listener listener;
    private final float[] g = new float[3];
    private boolean primed;

    TiltSensor(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        sensors = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        accel = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    boolean available() {
        return accel != null;
    }

    void start() {
        if (accel != null) {
            primed = false;
            sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    void stop() {
        if (sensors != null) sensors.unregisterListener(this);
    }

    @SuppressWarnings("deprecation")
    private int displayRotation() {
        Display d = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? activity.getDisplay()
                : activity.getWindowManager().getDefaultDisplay();
        return d == null ? Surface.ROTATION_90 : d.getRotation();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!primed) {
            System.arraycopy(e.values, 0, g, 0, 3);
            primed = true;
        } else {
            for (int i = 0; i < 3; i++) g[i] += (e.values[i] - g[i]) * SMOOTHING;
        }
        // Project "up" (what the accelerometer reports at rest) onto the
        // screen's right and up axes for the current display rotation.
        float right, up;
        switch (displayRotation()) {
            case Surface.ROTATION_90:
                right = -g[1];
                up = g[0];
                break;
            case Surface.ROTATION_180:
                right = -g[0];
                up = -g[1];
                break;
            case Surface.ROTATION_270:
                right = g[1];
                up = -g[0];
                break;
            default:
                right = g[0];
                up = g[1];
                break;
        }
        // Rolling the right edge down tips "up" toward the screen's left.
        // Using the full up/z magnitude keeps the reading the same however
        // far the device is leaned back toward the player.
        float roll = (float) Math.toDegrees(Math.atan2(-right, Math.hypot(up, g[2])));
        listener.onTilt(roll);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
