package com.randaladams.lunarlander;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.randaladams.lunarlander.engine.Game;
import com.randaladams.lunarlander.engine.Renderer;

/**
 * Hosts the game: runs a fixed-step simulation and draws on a background
 * thread, and turns touches into thrust and taps.
 */
@SuppressLint("ViewConstructor")
final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable, Game.Listener {

    private static final float STEP = 1f / 120f;   // physics tick
    private static final String PREFS = "lunarlander";
    private static final String KEY_HIGH = "highScore";

    private final Game game;
    private final Renderer renderer = new Renderer();
    private final AndroidGfx gfx = new AndroidGfx();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final Sounds sounds;

    private Thread thread;
    private volatile boolean running;
    private int surfaceW, surfaceH;

    GameView(Context context, Game game) {
        super(context);
        this.game = game;
        game.setListener(this);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        game.highScore = prefs.getInt(KEY_HIGH, 0);
        vibrator = findVibrator(context);
        sounds = new Sounds(context);
        getHolder().addCallback(this);
        setFocusable(true);
        setKeepScreenOn(true);
    }

    @SuppressWarnings("deprecation")
    private static Vibrator findVibrator(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm == null ? null : vm.getDefaultVibrator();
        }
        return (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
    }

    // ------------------------------------------------------------ surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this, "game-loop");
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (game) {
            surfaceW = width;
            surfaceH = height;
            renderer.layout(width, height);
            game.setViewAspect(width / (float) height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        sounds.setThrust(false);
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    // ------------------------------------------------------------ loop

    @Override
    public void run() {
        long last = System.nanoTime();
        float acc = 0;
        SurfaceHolder holder = getHolder();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - last) / 1e9f); // don't spiral after a hiccup
            last = now;
            acc += dt;
            boolean engineOn;
            synchronized (game) {
                while (acc >= STEP) {
                    game.update(STEP);
                    acc -= STEP;
                }
                engineOn = game.thrusting && game.state == Game.State.PLAYING && !game.paused;
            }
            sounds.setThrust(engineOn);

            Canvas c = null;
            try {
                c = holder.lockHardwareCanvas();
            } catch (Exception e) {
                // Some devices refuse a hardware canvas; fall back to software.
                c = holder.lockCanvas();
            }
            if (c == null) {
                sleep(8);
                continue;
            }
            boolean warn;
            try {
                synchronized (game) {
                    gfx.begin(c, surfaceW > 0 ? surfaceW : c.getWidth(), surfaceH > 0 ? surfaceH : c.getHeight());
                    renderer.draw(gfx, game);
                    warn = renderer.takeWarningSound();
                }
            } finally {
                holder.unlockCanvasAndPost(c); // waits for the display's next frame
            }
            if (warn) sounds.warning();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        synchronized (game) {
            boolean flying = game.state == Game.State.PLAYING && !game.paused;
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                int i = e.getActionIndex();
                boolean thrustSide = renderer.inThrustZone(e.getX(i), e.getY(i));
                if (!flying) game.tap();                     // menus: tap anywhere
                else if (!thrustSide) game.antiGravity();    // left side: anti-gravity
            }
            // Thrust is on while any finger (not the one lifting) is on the right half.
            boolean thrust = false;
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
                int lifting = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (i != lifting && renderer.inThrustZone(e.getX(i), e.getY(i))) thrust = true;
                }
            }
            game.setThrust(thrust && game.state == Game.State.PLAYING);
        }
        return true;
    }

    void pause() {
        synchronized (game) {
            game.pause();
        }
        sounds.setThrust(false);
    }

    void release() {
        sounds.release();
    }

    // ------------------------------------------------------------ game events

    private void buzz(long ms, int amplitude) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude));
    }

    @Override
    public void onLanded(boolean perfect) {
        buzz(perfect ? 40 : 90, perfect ? 80 : 160);
    }

    @Override
    public void onCrashed() {
        sounds.setThrust(false);
        sounds.crash();
        buzz(450, 255);
    }

    @Override
    public void onPickup() {
        buzz(25, 120);
    }

    @Override
    public void onShieldHit() {
        sounds.shield();
        buzz(70, 200);
    }

    @Override
    public void onAntiGravity() {
        sounds.shield(); // the anti-gravity shield switching on
        buzz(60, 140);
    }

    @Override
    public void onHighScore(int score) {
        prefs.edit().putInt(KEY_HIGH, score).apply();
    }
}
