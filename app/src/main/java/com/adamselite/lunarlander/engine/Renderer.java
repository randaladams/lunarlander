package com.adamselite.lunarlander.engine;

import static com.adamselite.lunarlander.engine.VectorFont.ALIGN_CENTER;
import static com.adamselite.lunarlander.engine.VectorFont.ALIGN_LEFT;
import static com.adamselite.lunarlander.engine.VectorFont.ALIGN_RIGHT;

/** Draws a {@link Game} as white vector lines on black. */
public final class Renderer {

    private int w, h;
    /** Pixels per world unit with the whole field on screen. */
    private float baseScale;
    /** Current pixels per world unit (baseScale * zoom). */
    private float scale;
    private float lineW;
    private final float[] buf = new float[4096];

    // Camera: world point at the screen center, and zoom factor.
    private float camX, camY, zoom = 1f;
    private boolean zoomWanted;
    private float lastTime = -1;
    public static final float CLOSE_ZOOM = 2.1f;

    /** Remember the screen size (used for touch zones). */
    public void layout(int width, int height) {
        w = width;
        h = height;
    }

    /** Fits the playfield to the screen, zooming in near the ground like the arcade original. */
    private void updateCamera(Game g) {
        float worldW = g.terrain.xs[g.terrain.size() - 1];
        baseScale = Math.min(w / worldW, h / Game.WORLD_H);
        lineW = Math.max(1.5f, baseScale * 1.5f);

        boolean first = lastTime < 0;
        float dt = first ? 1f : Math.max(0f, Math.min(0.1f, g.time - lastTime));
        lastTime = g.time;

        boolean flying = g.state == Game.State.PLAYING || g.state == Game.State.LANDED
                || g.state == Game.State.CRASHED;
        if (!flying) {
            zoomWanted = false;
        } else if (g.state == Game.State.PLAYING) {
            float alt = g.altitude();
            if (alt < 80f) zoomWanted = true;
            else if (alt > 125f) zoomWanted = false; // hysteresis: no flicker at the boundary
        }
        float targetZoom = zoomWanted ? CLOSE_ZOOM : 1f;
        float k = Math.min(1f, dt * 3.5f);
        zoom += (targetZoom - zoom) * k;
        if (Math.abs(zoom - targetZoom) < 0.002f) zoom = targetZoom;
        scale = baseScale * zoom;

        // Follow the ship, but never show past the edges of the field.
        float halfW = w / 2f / scale, halfH = h / 2f / scale;
        // Blend from the field's center toward the ship as the zoom increases.
        float t = (zoom - 1f) / (CLOSE_ZOOM - 1f);
        float fx = worldW / 2f + (g.x - worldW / 2f) * t;
        float fy = Game.WORLD_H / 2f + (g.y - Game.WORLD_H / 2f) * t;
        float tx = clampRange(fx, halfW, worldW - halfW, worldW / 2f);
        float ty = clampRange(fy, halfH, Game.WORLD_H - halfH, Game.WORLD_H / 2f);
        camX = tx;
        camY = ty;
    }

    private static float clampRange(float v, float lo, float hi, float mid) {
        if (lo > hi) return mid;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Right half of the screen fires the engine; the left half triggers anti-gravity. */
    public boolean inThrustZone(float px, float py) {
        return px >= w / 2f;
    }

    private float sx(float wx) {
        return w / 2f + (wx - camX) * scale;
    }

    private float sy(float wy) {
        return h / 2f - (wy - camY) * scale;
    }

    private boolean blink(Game g, float hz) {
        return ((int) (g.time * hz * 2)) % 2 == 0;
    }

    // ================================================================ frame

    public void draw(Gfx gfx, Game g) {
        if (gfx.width() != w || gfx.height() != h) layout(gfx.width(), gfx.height());
        java.util.Arrays.fill(btnShown, false);
        if (g.state == Game.State.SPLASH) {
            drawSplash(gfx, g);
            return;
        }
        updateCamera(g);
        gfx.clear();
        gfx.setStroke(lineW);
        gfx.setAlpha(1f);

        drawStars(gfx, g);
        if (g.state != Game.State.SCORES) drawTerrain(gfx, g); // keep the list readable

        if (g.state != Game.State.TITLE && g.state != Game.State.SCORES) {
            drawPickups(gfx, g);
            drawMeteors(gfx, g);
            if (g.shipVisible) drawShip(gfx, g);
            drawDebris(gfx, g);
            drawPopups(gfx, g);
            drawHud(gfx, g);
            drawMeteorWarnings(gfx, g);
        } else {
            drawDemoShip(gfx, g);
        }
        drawControls(gfx, g);
        drawPauseButton(gfx, g);
        drawOverlay(gfx, g);
    }

    private void drawStars(Gfx gfx, Game g) {
        float r = Math.max(1f, baseScale * 0.9f);
        for (int i = 0; i < g.stars.length; i += 2) {
            float x = g.stars[i], y = g.stars[i + 1];
            if (y < g.terrain.heightAt(x) + 8) continue;
            float tw = 0.35f + 0.3f * (float) Math.sin(g.time * 1.3f + i);
            gfx.setAlpha(tw);
            gfx.fillRect(sx(x) - r / 2, sy(y) - r / 2, r, r);
        }
        gfx.setAlpha(1f);
    }

    private void drawTerrain(Gfx gfx, Game g) {
        Terrain t = g.terrain;
        int n = t.size();
        for (int i = 0; i < n; i++) {
            buf[i * 2] = sx(t.xs[i]);
            buf[i * 2 + 1] = sy(t.ys[i]);
        }
        gfx.polyline(buf, n, false);
        if (g.state == Game.State.TITLE) return;
        // Pads: heavier line and a payout marker underneath.
        float th = h * 0.022f;
        for (Terrain.Pad p : t.pads) {
            gfx.setStroke(lineW * 2.4f);
            gfx.line(sx(p.x1), sy(p.y), sx(p.x2), sy(p.y));
            gfx.setStroke(lineW * 0.8f);
            gfx.setAlpha(0.85f);
            String label = p.rescue ? "SOS" : "X" + p.multiplier;
            if (!p.rescue || g.astronautAboard || blink(g, 1.5f))
                VectorFont.draw(gfx, label, sx(p.centerX()), sy(p.y) + th * 0.6f, th, ALIGN_CENTER);
            gfx.setAlpha(1f);
            if (p.rescue && !g.astronautAboard) drawAstronaut(gfx, g, p);
        }
        gfx.setStroke(lineW);
    }

    /** A stick-figure astronaut waving from the SOS pad. */
    private void drawAstronaut(Gfx gfx, Game g, Terrain.Pad p) {
        float ax = p.x2 - Math.min(10f, p.width() * 0.25f), ay = p.y;
        float wave = (float) Math.sin(g.time * 6f) * 0.5f;
        gfx.setStroke(lineW * 0.9f);
        gfx.circle(sx(ax), sy(ay + 13f), 2.2f * scale);                         // helmet
        gfx.line(sx(ax), sy(ay + 10.8f), sx(ax), sy(ay + 5f));                  // body
        gfx.line(sx(ax), sy(ay + 5f), sx(ax - 2.5f), sy(ay));                   // legs
        gfx.line(sx(ax), sy(ay + 5f), sx(ax + 2.5f), sy(ay));
        gfx.line(sx(ax), sy(ay + 9f), sx(ax - 3f), sy(ay + 6.5f));              // arm
        gfx.line(sx(ax), sy(ay + 9f), sx(ax + 3f), sy(ay + 12f + wave * 2f));   // waving arm
        gfx.setStroke(lineW);
    }

    // ================================================================ start screen

    /**
     * The start screen: Earth rising over the lunar horizon while the lander
     * descends onto a pad and the title spells itself out, with a loading bar.
     * Everything is screen-space vector lines, like the game.
     */
    private void drawSplash(Gfx gfx, Game g) {
        gfx.clear();
        float t = g.stateTime;
        float T = Game.SPLASH_SECONDS;
        float base = Math.max(1.5f, h * 0.0028f);
        gfx.setStroke(base);

        // Stars (reusing the game's star positions, spread over the screen).
        float r = Math.max(1f, h * 0.0022f);
        for (int i = 0; i < g.stars.length; i += 2) {
            float x = g.stars[i] / g.worldW * w, y = g.stars[i + 1] / Game.WORLD_H * h * 0.75f;
            gfx.setAlpha(0.3f + 0.3f * (float) Math.sin(g.time * 1.3f + i));
            gfx.fillRect(x, y, r, r);
        }

        // Earth, rising slowly, lit from the right with a hatched night side.
        float er = h * 0.12f;
        float ex = w * 0.78f, ey = h * 0.30f - Math.min(1f, t / T) * h * 0.04f;
        gfx.setAlpha(1f);
        gfx.circle(ex, ey, er);
        int n = 40;
        for (int i = 0; i <= n; i++) { // terminator: half-ellipse
            double a = -Math.PI / 2 + Math.PI * i / n;
            buf[i * 2] = ex - (float) Math.cos(a) * er * 0.35f;
            buf[i * 2 + 1] = ey + (float) Math.sin(a) * er;
        }
        gfx.setAlpha(0.8f);
        gfx.polyline(buf, n + 1, false);
        gfx.setAlpha(0.22f);
        for (int k = -6; k <= 6; k++) { // night-side hatching
            float yy = ey + k * er / 7f;
            float half = (float) Math.sqrt(Math.max(0, er * er - (yy - ey) * (yy - ey)));
            float term = ex - 0.35f * half;
            gfx.line(ex - half, yy, term, yy);
        }
        // A couple of continents as squiggles on the lit side.
        gfx.setAlpha(0.6f);
        float[][] land = {{0.15f, -0.45f, 0.45f, -0.3f, 0.55f, 0.05f, 0.35f, 0.25f, 0.2f, 0.05f},
                {0.2f, 0.45f, 0.5f, 0.4f, 0.6f, 0.6f}};
        for (float[] c : land) {
            for (int i = 0; i < c.length / 2; i++) {
                buf[i * 2] = ex + c[i * 2] * er;
                buf[i * 2 + 1] = ey + c[i * 2 + 1] * er;
            }
            gfx.polyline(buf, c.length / 2, false);
        }

        // Lunar horizon: a huge curved arc with craters along it.
        float R = w * 1.4f, hcx = w / 2f, hcy = h * 0.80f + R;
        n = 60;
        for (int i = 0; i <= n; i++) {
            double a = -Math.PI / 2 + (i / (double) n - 0.5) * 1.0;
            buf[i * 2] = hcx + (float) Math.cos(a) * R;
            buf[i * 2 + 1] = hcy + (float) Math.sin(a) * R;
        }
        gfx.setAlpha(1f);
        gfx.polyline(buf, n + 1, false);
        float[][] craters = {{0.12f, 0.06f, 0.05f}, {0.30f, 0.12f, 0.035f}, {0.68f, 0.09f, 0.045f},
                {0.86f, 0.14f, 0.03f}, {0.45f, 0.17f, 0.025f}, {0.22f, 0.16f, 0.02f}};
        gfx.setAlpha(0.55f);
        for (float[] c : craters) {
            float cx = c[0] * w, cyy = h * 0.80f + c[1] * h, cr = c[2] * w;
            for (int i = 0; i <= 24; i++) {
                double a = i * 2 * Math.PI / 24;
                buf[i * 2] = cx + (float) Math.cos(a) * cr;
                buf[i * 2 + 1] = cyy + (float) Math.sin(a) * cr * 0.28f;
            }
            gfx.polyline(buf, 25, true);
        }

        // Landing pad on the horizon, and the lander coming down onto it.
        float padY = h * 0.80f - 2f, padW = w * 0.09f;
        gfx.setAlpha(1f);
        gfx.setStroke(base * 2.2f);
        gfx.line(w / 2f - padW / 2f, padY, w / 2f + padW / 2f, padY);
        gfx.setStroke(base);
        float s = h * 0.0065f;                        // ship units -> pixels
        float landAt = T * 0.8f;
        float p = Math.min(1f, t / landAt);
        float ease = 1f - (1f - p) * (1f - p) * (1f - p);
        float footY = padY;
        float shipY = h * 0.08f + (footY + Game.FOOT_Y * s - h * 0.08f) * ease; // y of ship origin, screen
        float sway = (1f - ease) * w * 0.05f * (float) Math.sin(t * 1.7f);
        float shipX = w / 2f + sway;
        float tilt = (1f - ease) * 10f * (float) Math.sin(t * 1.7f + 1f);
        double a = Math.toRadians(tilt);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        float[] Ls = Game.SHIP_LINES;
        for (int i = 0; i < Ls.length; i += 4) {
            float x1 = Ls[i], y1 = Ls[i + 1], x2 = Ls[i + 2], y2 = Ls[i + 3];
            gfx.line(shipX + (x1 * cos + y1 * sin) * s, shipY - (-x1 * sin + y1 * cos) * s,
                    shipX + (x2 * cos + y2 * sin) * s, shipY - (-x2 * sin + y2 * cos) * s);
        }
        if (p < 1f) {
            float len = (6f + 10f * (1f - ease)) * (0.7f + 0.3f * (float) Math.random());
            float[] fl = {-3.5f, -8.5f, 0f, -8.5f - len, 3.5f, -8.5f};
            for (int i = 0; i < 3; i++) {
                buf[i * 2] = shipX + (fl[i * 2] * cos + fl[i * 2 + 1] * sin) * s;
                buf[i * 2 + 1] = shipY - (-fl[i * 2] * sin + fl[i * 2 + 1] * cos) * s;
            }
            gfx.polyline(buf, 3, false);
        } else {
            // Touchdown dust, fading out.
            float d = Math.min(1f, (t - landAt) / 0.8f);
            gfx.setAlpha(1f - d);
            for (int k = 1; k <= 3; k++) {
                float spread = (20f + 25f * d) * s * k / 3f;
                gfx.line(w / 2f - padW * 0.4f, padY - 2, w / 2f - padW * 0.4f - spread, padY - 2 - spread * 0.25f);
                gfx.line(w / 2f + padW * 0.4f, padY - 2, w / 2f + padW * 0.4f + spread, padY - 2 - spread * 0.25f);
            }
            gfx.setAlpha(1f);
        }

        // Title, spelled out a letter at a time.
        String title = "LUNAR LANDER";
        int shown = Math.min(title.length(), (int) (t / 0.12f));
        float th = h * 0.09f;
        gfx.setStroke(Math.max(2.5f, th * 0.08f));
        float tw = VectorFont.measure(title, th);
        VectorFont.draw(gfx, title.substring(0, shown), w * 0.36f - tw / 2f, h * 0.20f, th, ALIGN_LEFT);
        if (shown == title.length()) {
            gfx.setStroke(Math.max(1.5f, th * 0.04f));
            gfx.setAlpha(Math.min(1f, (t - 1.5f) * 2f));
            VectorFont.draw(gfx, "A RETRO VECTOR LANDING GAME", w * 0.36f, h * 0.20f + th * 1.5f, th * 0.3f, ALIGN_CENTER);
            gfx.setAlpha(1f);
        }

        // Loading bar.
        float bw = w * 0.28f, bh = h * 0.018f, bx = w / 2f - bw / 2f, by = h * 0.92f;
        float prog = g.splashProgress();
        gfx.setStroke(base);
        gfx.setAlpha(0.8f);
        buf[0] = bx; buf[1] = by;
        buf[2] = bx + bw; buf[3] = by;
        buf[4] = bx + bw; buf[5] = by + bh;
        buf[6] = bx; buf[7] = by + bh;
        gfx.polyline(buf, 4, true);
        gfx.fillRect(bx + 2, by + 2, (bw - 4) * prog, bh - 4);
        String label = prog < 1f ? "LOADING" : "TAP TO START";
        VectorFont.draw(gfx, label, w / 2f, by - h * 0.04f, h * 0.025f, ALIGN_CENTER);
        gfx.setAlpha(1f);
        gfx.setStroke(lineW);
    }

    private void drawPickups(Gfx gfx, Game g) {
        float th = 7f * scale;
        for (Game.Pickup p : g.pickups) {
            if (p.taken) continue;
            float bob = (float) Math.sin(g.time * 2f + p.phase) * 2.5f;
            float cx = sx(p.x), cy = sy(p.y + bob);
            float pulse = 0.65f + 0.35f * (float) Math.sin(g.time * 4f + p.phase);
            if (p.appear > 0) {
                // A replacement materializing: shrinking ring, icon fading in.
                float t = p.appear / Game.Pickup.APPEAR_SECONDS;
                gfx.setAlpha(1f - t);
                gfx.setStroke(lineW * 0.8f);
                gfx.circle(cx, cy, (12f + 30f * t) * scale);
                pulse *= (1f - t) * 0.8f;
            }
            gfx.setAlpha(pulse);
            gfx.setStroke(lineW * 0.9f);
            if (p.type == Game.Pickup.FUEL) {
                float r = 11f * scale;
                buf[0] = cx; buf[1] = cy - r;
                buf[2] = cx + r; buf[3] = cy;
                buf[4] = cx; buf[5] = cy + r;
                buf[6] = cx - r; buf[7] = cy;
                gfx.polyline(buf, 4, true);
                VectorFont.draw(gfx, "F", cx, cy - th / 2, th, ALIGN_CENTER);
            } else if (p.type == Game.Pickup.OXYGEN) {
                gfx.circle(cx, cy, 10f * scale);
                VectorFont.draw(gfx, "O2", cx, cy - th / 2, th * 0.85f, ALIGN_CENTER);
            } else {
                // Shield: a hexagon with an S.
                float r = 11f * scale;
                for (int k = 0; k < 6; k++) {
                    double a = Math.PI / 6 + k * Math.PI / 3;
                    buf[k * 2] = cx + (float) Math.cos(a) * r;
                    buf[k * 2 + 1] = cy + (float) Math.sin(a) * r;
                }
                gfx.polyline(buf, 6, true);
                VectorFont.draw(gfx, "S", cx, cy - th / 2, th, ALIGN_CENTER);
            }
        }
        gfx.setAlpha(1f);
        gfx.setStroke(lineW);
    }

    /**
     * A small blinking arrow on the screen edge for each meteor that's
     * still off screen but on a collision course. It points at the meteor and
     * blinks faster as impact gets closer; it disappears once the meteor is
     * in view (or stops being a threat).
     */
    private boolean warningSound;

    /** True once each time a new meteor starts showing a warning (then resets). */
    public boolean takeWarningSound() {
        boolean w = warningSound;
        warningSound = false;
        return w;
    }

    private void drawMeteorWarnings(Gfx gfx, Game g) {
        if (g.state != Game.State.PLAYING || g.paused) return;
        float shipX = sx(g.centerX()), shipY = sy(g.centerY());
        float size = h * 0.0225f;
        for (Game.Meteor m : g.meteors) {
            float mx = sx(m.x), my = sy(m.y);
            float pad = m.radius * scale;
            boolean visible = mx > -pad && mx < w + pad && my > -pad && my < h + pad;
            if (visible) continue;
            float t = g.meteorThreat(m);
            if (t < 0) continue;
            if (!m.warned) {          // first time this meteor triggers a warning
                m.warned = true;
                warningSound = true;
            }
            // Faster blinking the closer it gets: 2 Hz far out, 7 Hz at impact.
            float hz = 2f + 5f * (1f - t / Game.THREAT_HORIZON);
            if (!blink(g, hz)) continue;

            // Where the ship→meteor line leaves the screen, inset from the edges.
            float dx = mx - shipX, dy = my - shipY;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1) continue;
            dx /= len;
            dy /= len;
            float inset = size * 2.2f;
            float left = inset, right = w - inset, top = inset, bottom = h - inset * 2f;
            float tScale = Float.MAX_VALUE;
            if (dx > 0) tScale = Math.min(tScale, (right - shipX) / dx);
            if (dx < 0) tScale = Math.min(tScale, (left - shipX) / dx);
            if (dy < 0) tScale = Math.min(tScale, (top - shipY) / dy);
            if (dy > 0) tScale = Math.min(tScale, (bottom - shipY) / dy);
            float px = Math.max(left, Math.min(right, shipX + dx * tScale));
            float py = Math.max(top, Math.min(bottom, shipY + dy * tScale));
            // Keep clear of the score (top-left) and gauges (top-right).
            float hudBottom = h * 0.27f;
            if (py < hudBottom) {
                if (px < w * 0.3f) {
                    if (py <= top + 1) px = w * 0.3f; else py = hudBottom;
                } else if (px > w * 0.7f) {
                    if (py <= top + 1) px = w * 0.7f; else py = hudBottom;
                }
            }

            // Arrowhead pointing from the marker toward the meteor.
            float ang = (float) Math.atan2(my - py, mx - px);
            float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
            float tipX = px + cos * size, tipY = py + sin * size;
            buf[0] = tipX;
            buf[1] = tipY;
            buf[2] = px + (float) Math.cos(ang + 2.5) * size * 0.85f;
            buf[3] = py + (float) Math.sin(ang + 2.5) * size * 0.85f;
            buf[4] = px + (float) Math.cos(ang - 2.5) * size * 0.85f;
            buf[5] = py + (float) Math.sin(ang - 2.5) * size * 0.85f;
            gfx.setStroke(Math.max(2f, size * 0.09f));
            gfx.setAlpha(1f);
            gfx.polyline(buf, 3, true);
        }
        gfx.setStroke(lineW);
        gfx.setAlpha(1f);
    }

    private void drawMeteors(Gfx gfx, Game g) {
        for (Game.Meteor m : g.meteors) {
            int n = m.shape.length;
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(m.angle + i * 360.0 / n);
                buf[i * 2] = sx(m.x + (float) Math.cos(a) * m.shape[i]);
                buf[i * 2 + 1] = sy(m.y + (float) Math.sin(a) * m.shape[i]);
            }
            gfx.polyline(buf, n, true);
            // Short streak behind it.
            gfx.setAlpha(0.35f);
            gfx.line(sx(m.x), sy(m.y), sx(m.x - m.vx * 0.12f), sy(m.y - m.vy * 0.12f));
            gfx.setAlpha(1f);
        }
    }

    private void shipPoint(Game g, float lx, float ly, float cos, float sin, float[] out, int idx) {
        out[idx] = sx(g.x + lx * cos + ly * sin);
        out[idx + 1] = sy(g.y - lx * sin + ly * cos);
    }

    private void drawShip(Gfx gfx, Game g) {
        double a = Math.toRadians(g.angle);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        float[] L = Game.SHIP_LINES;
        for (int i = 0; i < L.length; i += 4) {
            shipPoint(g, L[i], L[i + 1], cos, sin, buf, 0);
            shipPoint(g, L[i + 2], L[i + 3], cos, sin, buf, 2);
            gfx.line(buf[0], buf[1], buf[2], buf[3]);
        }
        if (g.thrusting) {
            float len = 9f + (float) Math.random() * 12f;
            shipPoint(g, -3.5f, -8.5f, cos, sin, buf, 0);
            shipPoint(g, 0, -8.5f - len, cos, sin, buf, 2);
            shipPoint(g, 3.5f, -8.5f, cos, sin, buf, 4);
            gfx.polyline(buf, 3, false);
            shipPoint(g, -1.5f, -8.5f, cos, sin, buf, 0);
            shipPoint(g, 0, -8.5f - len * 0.55f, cos, sin, buf, 2);
            shipPoint(g, 1.5f, -8.5f, cos, sin, buf, 4);
            gfx.setAlpha(0.6f);
            gfx.polyline(buf, 3, false);
            gfx.setAlpha(1f);
        }
        // Anti-gravity field: rings pulsing outward, fading as it runs out.
        if (g.antiGravTime > 0) {
            float cxs = sx(g.centerX()), cys = sy(g.centerY());
            float fade = Math.min(1f, g.antiGravTime / Game.ANTI_GRAV_SECONDS + 0.2f);
            for (int i = 0; i < 3; i++) {
                float ph = (g.time * 2.5f + i / 3f) % 1f;
                gfx.setAlpha(fade * (1f - ph));
                gfx.circle(cxs, cys, (16f + ph * 16f) * scale);
            }
            // The protective bubble itself.
            gfx.setAlpha(0.9f * fade);
            gfx.circle(cxs, cys, 21f * scale);
            gfx.setAlpha(1f);
        }
        // Shield bubble flashes when it absorbs something.
        if (g.shieldFlash > 0) {
            gfx.setAlpha(Math.min(1f, g.shieldFlash * 2f));
            gfx.circle(sx(g.centerX()), sy(g.centerY()), 20f * scale);
            gfx.setAlpha(1f);
        }
    }

    private void drawDemoShip(Gfx gfx, Game g) {
        // A lander drifting across the title screen.
        float savedX = g.x, savedY = g.y, savedA = g.angle;
        boolean savedT = g.thrusting;
        g.x = (g.time * 40f) % (g.worldW + 100f) - 50f;
        g.y = 420f + (float) Math.sin(g.time * 0.8f) * 25f;
        g.angle = (float) Math.sin(g.time * 0.6f) * 20f;
        g.thrusting = Math.sin(g.time * 0.8f) < 0;
        drawShip(gfx, g);
        g.x = savedX;
        g.y = savedY;
        g.angle = savedA;
        g.thrusting = savedT;
    }

    private void drawDebris(Gfx gfx, Game g) {
        float r = Math.max(1.5f, scale * 1.2f);
        for (Game.Debris d : g.debris) {
            gfx.setAlpha(Math.min(1f, d.life / d.maxLife * 1.6f));
            if (d.halfLen > 0) {
                double a = Math.toRadians(d.angle);
                float dx = (float) Math.cos(a) * d.halfLen, dy = (float) Math.sin(a) * d.halfLen;
                gfx.line(sx(d.x - dx), sy(d.y - dy), sx(d.x + dx), sy(d.y + dy));
            } else {
                gfx.fillRect(sx(d.x) - r / 2, sy(d.y) - r / 2, r, r);
            }
        }
        gfx.setAlpha(1f);
    }

    private void drawPopups(Gfx gfx, Game g) {
        float th = h * 0.03f;
        gfx.setStroke(lineW * 0.9f);
        for (Game.Popup p : g.popups) {
            gfx.setAlpha(Math.min(1f, p.life * 1.5f));
            VectorFont.draw(gfx, p.text, sx(p.x), sy(p.y) - th * 2, th, ALIGN_CENTER);
        }
        gfx.setAlpha(1f);
        gfx.setStroke(lineW);
    }

    // ================================================================ HUD

    private void drawHud(Gfx gfx, Game g) {
        float th = h * 0.032f;
        float m = th;
        gfx.setStroke(Math.max(1.5f, th * 0.11f));

        // Left: score, level, ships.
        VectorFont.draw(gfx, String.format("SCORE %05d", g.score), m, m, th, ALIGN_LEFT);
        String hi = String.format("HI %05d", Math.max(g.highScore, g.score));
        VectorFont.draw(gfx, hi, m, m + th * 1.7f, th * 0.75f, ALIGN_LEFT);
        if (!g.tutorial) {
            gfx.setAlpha(0.65f);
            VectorFont.draw(gfx, "NEXT SHIP " + g.nextBonusShip, m + VectorFont.measure(hi, th * 0.75f) + th,
                    m + th * 1.85f, th * 0.55f, ALIGN_LEFT);
            gfx.setAlpha(1f);
        }
        String lvText = g.tutorial ? "TUTORIAL" : "LEVEL " + g.levelNumber;
        VectorFont.draw(gfx, lvText, m, m + th * 3.1f, th * 0.75f, ALIGN_LEFT);
        float shipsX = m + VectorFont.measure("LEVEL " + g.levelNumber, th * 0.75f) + th * 1.6f;
        // Spare ships: the one you are flying doesn't count (after a crash it's already gone).
        boolean flyingOne = g.state != Game.State.CRASHED && g.state != Game.State.GAME_OVER;
        int spares = Math.max(0, g.lives - (flyingOne ? 1 : 0));
        if (g.tutorial) spares = 0;
        for (int i = 0; i < spares; i++) {
            drawMiniShip(gfx, shipsX + i * th * 1.4f, m + th * 3.45f, th * 0.06f);
        }

        // Rescue mission status and strikes, under the level.
        float ry = m + th * 4.6f;
        if (g.level.rescue && !g.tutorial) {
            String r = g.astronautAboard ? "ASTRONAUT ABOARD: LAND ON A PAD" : "RESCUE: LAND AT THE SOS PAD";
            if (g.astronautAboard || blink(g, 1)) VectorFont.draw(gfx, r, m, ry, th * 0.6f, ALIGN_LEFT);
            ry += th * 1.1f;
        }
        if (g.strikes > 0 && !g.tutorial) {
            StringBuilder st = new StringBuilder("STRIKES ");
            for (int i = 0; i < Game.STRIKES_TO_LOSE_SHIP; i++) st.append(i < g.strikes ? "X" : "-");
            VectorFont.draw(gfx, st.toString(), m, ry, th * 0.6f, ALIGN_LEFT);
        }

        // Right: gauges.
        float barW = w * 0.16f, barH = th * 0.75f;
        float bx = w - m - barW;
        boolean lowFuel = g.fuel < Level.FUEL_MAX * 0.15f;
        boolean lowO2 = g.oxygen < 10f;
        boolean lowShield = g.shield < 25f;
        gauge(gfx, g, "FUEL", g.fuel / Level.FUEL_MAX, bx, m, barW, barH, lowFuel);
        gauge(gfx, g, "O2", g.oxygen / Level.O2_MAX, bx, m + th * 1.3f, barW, barH, lowO2);
        gauge(gfx, g, "SHLD", g.shield / Level.SHIELD_MAX, bx, m + th * 2.6f, barW, barH, lowShield);

        // Flight data under the gauges.
        float ty = m + th * 4.1f;
        float small = th * 0.75f;
        int alt = Math.max(0, Math.round(g.altitude()));
        VectorFont.draw(gfx, "ALT " + alt, w - m, ty, small, ALIGN_RIGHT);
        int hs = Math.round(g.vx);
        int vs = Math.round(-g.vy);
        String hsText = "H " + Math.abs(hs) + (hs > 0 ? " >" : hs < 0 ? " <" : "  ");
        String vsText = "V " + Math.abs(vs) + (vs > 0 ? " DN" : vs < 0 ? " UP" : "   ");
        if (!g.hSpeedUnsafe() || blink(g, 3) || g.state != Game.State.PLAYING)
            VectorFont.draw(gfx, hsText, w - m, ty + small * 1.6f, small, ALIGN_RIGHT);
        if (!g.vSpeedUnsafe() || blink(g, 3) || g.state != Game.State.PLAYING)
            VectorFont.draw(gfx, vsText, w - m, ty + small * 3.2f, small, ALIGN_RIGHT);

        // Wind indicator, top center.
        if (g.level.wind > 0) {
            float cx = w / 2f, cy = h * 0.075f + th * 0.9f; // below the pause button
            VectorFont.draw(gfx, "WIND", cx, cy - th * 0.5f, small, ALIGN_CENTER);
            float len = g.windNow / 5f * w * 0.08f;
            float ay = cy + th * 1.3f;
            gfx.line(cx, ay, cx + len, ay);
            if (Math.abs(len) > 3) {
                float d = Math.signum(len) * th * 0.35f;
                gfx.line(cx + len, ay, cx + len - d, ay - th * 0.3f);
                gfx.line(cx + len, ay, cx + len - d, ay + th * 0.3f);
            }
        }

        // Oxygen emergency.
        if (g.state == Game.State.PLAYING && g.oxygen <= 0 && blink(g, 2)) {
            float left = Math.max(0, Level.O2_GRACE - g.noOxygenTime);
            gfx.setStroke(Math.max(2f, th * 0.14f));
            VectorFont.draw(gfx, "OXYGEN DEPLETED", w / 2f, h * 0.30f, th * 1.3f, ALIGN_CENTER);
            VectorFont.draw(gfx, String.format("%.1f", left), w / 2f, h * 0.30f + th * 2.2f, th * 1.3f, ALIGN_CENTER);
        } else if (g.state == Game.State.PLAYING && g.fuel <= 0 && blink(g, 2)) {
            VectorFont.draw(gfx, "OUT OF FUEL", w / 2f, h * 0.30f, th * 1.1f, ALIGN_CENTER);
        }
        gfx.setStroke(lineW);
    }

    private void gauge(Gfx gfx, Game g, String label, float frac, float x, float y,
                       float bw, float bh, boolean low) {
        frac = Math.max(0, Math.min(1, frac));
        boolean show = !low || blink(g, 2.5f);
        if (show) VectorFont.draw(gfx, label, x - bh * 0.8f, y, bh, ALIGN_RIGHT);
        float stroke = Math.max(1.5f, bh * 0.12f);
        gfx.setStroke(stroke);
        buf[0] = x; buf[1] = y;
        buf[2] = x + bw; buf[3] = y;
        buf[4] = x + bw; buf[5] = y + bh;
        buf[6] = x; buf[7] = y + bh;
        gfx.polyline(buf, 4, true);
        float pad = stroke * 1.8f;
        if (show && frac > 0) gfx.fillRect(x + pad, y + pad, (bw - pad * 2) * frac, bh - pad * 2);
    }

    private void drawMiniShip(Gfx gfx, float cx, float cy, float s) {
        float[] L = Game.SHIP_LINES;
        for (int i = 0; i < L.length; i += 4) {
            gfx.line(cx + L[i] * s, cy - L[i + 1] * s, cx + L[i + 2] * s, cy - L[i + 3] * s);
        }
    }

    // ================================================================ touch controls

    /**
     * Left half: tap for anti-gravity. Right half: hold to thrust.
     * Shown as faint labels along the bottom edge so they don't hide the ground.
     */
    private void drawControls(Gfx gfx, Game g) {
        if (g.state != Game.State.PLAYING && g.state != Game.State.BRIEFING) return;
        boolean active = g.state == Game.State.PLAYING && !g.paused;
        float th = h * 0.028f;
        float y = h - th * 1.9f;
        float m = th * 1.2f;
        gfx.setStroke(Math.max(1.5f, th * 0.11f));

        // Divider down the lower part of the middle.
        gfx.setAlpha(active ? 0.18f : 0.1f);
        gfx.line(w / 2f, h * 0.78f, w / 2f, h - th * 0.6f);

        // Left: anti-gravity, with one pip per remaining charge.
        boolean on = g.antiGravTime > 0;
        String label = "TAP = ANTI-GRAV";
        gfx.setAlpha(!active ? 0.25f : on ? 1f : g.antiGravLeft > 0 ? 0.55f : 0.2f);
        float pip = th * 0.4f, gap = th * 0.55f;
        for (int i = 0; i < Game.ANTI_GRAV_CHARGES; i++) {
            float pcx = m + pip + i * (pip * 2 + gap);
            float pcy = y + th / 2f;
            if (i < g.antiGravLeft) gfx.fillCircle(pcx, pcy, pip);
            else gfx.circle(pcx, pcy, pip);
        }
        float labelLeft = m + Game.ANTI_GRAV_CHARGES * (pip * 2 + gap) + th * 0.3f;
        VectorFont.draw(gfx, label, labelLeft, y, th, ALIGN_LEFT);
        if (on) {
            float uw = VectorFont.measure(label, th);
            float frac = g.antiGravTime / Game.ANTI_GRAV_SECONDS;
            gfx.line(labelLeft, y + th * 1.5f, labelLeft + uw * frac, y + th * 1.5f);
        }

        // Right: thrust.
        boolean burning = active && g.thrustInput;
        String t = "HOLD = THRUST";
        gfx.setAlpha(!active ? 0.25f : burning ? 1f : 0.55f);
        VectorFont.draw(gfx, t, w - m, y, th, ALIGN_RIGHT);
        if (burning) {
            float uw = VectorFont.measure(t, th);
            gfx.line(w - m - uw, y + th * 1.5f, w - m, y + th * 1.5f);
        }
        gfx.setAlpha(1f);
        gfx.setStroke(lineW);
    }

    // ================================================================ messages

    // ================================================================ buttons

    public static final int BTN_NONE = -1, BTN_PLAY = 0, BTN_TUTORIAL = 1, BTN_SCORES = 2,
            BTN_RESUME = 3, BTN_QUIT = 4, BTN_PAUSE = 5, BTN_EXIT = 6;
    private static final int BTN_COUNT = 7;
    private final float[][] btnRect = new float[BTN_COUNT][4];
    private final boolean[] btnShown = new boolean[BTN_COUNT];

    /** Which on-screen button (as last drawn) is at this point, or BTN_NONE. */
    public int hit(float px, float py) {
        for (int i = 0; i < BTN_COUNT; i++) {
            if (!btnShown[i]) continue;
            float[] r = btnRect[i];
            float slop = h * 0.02f; // fingers are bigger than vector lines
            if (px >= r[0] - slop && px <= r[2] + slop && py >= r[1] - slop && py <= r[3] + slop) return i;
        }
        return BTN_NONE;
    }

    /** A boxed text button centered at (cx, cy). */
    private void button(Gfx gfx, int id, String label, float cx, float cy, float th, boolean bright) {
        float tw = VectorFont.measure(label, th);
        float padX = th * 1.1f, padY = th * 0.8f;
        float x0 = cx - tw / 2f - padX, x1 = cx + tw / 2f + padX;
        float y0 = cy - th / 2f - padY, y1 = cy + th / 2f + padY;
        gfx.setAlpha(bright ? 1f : 0.8f);
        buf[0] = x0; buf[1] = y0;
        buf[2] = x1; buf[3] = y0;
        buf[4] = x1; buf[5] = y1;
        buf[6] = x0; buf[7] = y1;
        gfx.polyline(buf, 4, true);
        VectorFont.draw(gfx, label, cx, cy - th / 2f, th, ALIGN_CENTER);
        gfx.setAlpha(1f);
        btnRect[id][0] = x0;
        btnRect[id][1] = y0;
        btnRect[id][2] = x1;
        btnRect[id][3] = y1;
        btnShown[id] = true;
    }

    /** The pause button: two bars in a small box, top center. */
    private void drawPauseButton(Gfx gfx, Game g) {
        if (g.state != Game.State.PLAYING || g.paused) return;
        float sz = h * 0.06f;
        float cx = w / 2f, cy = h * 0.012f + sz / 2f;
        float x0 = cx - sz / 2f, y0 = cy - sz / 2f;
        gfx.setStroke(Math.max(1.5f, sz * 0.05f));
        gfx.setAlpha(0.7f);
        buf[0] = x0; buf[1] = y0;
        buf[2] = x0 + sz; buf[3] = y0;
        buf[4] = x0 + sz; buf[5] = y0 + sz;
        buf[6] = x0; buf[7] = y0 + sz;
        gfx.polyline(buf, 4, true);
        float bw = sz * 0.16f, bh = sz * 0.5f;
        gfx.fillRect(cx - sz * 0.2f - bw / 2f, cy - bh / 2f, bw, bh);
        gfx.fillRect(cx + sz * 0.2f - bw / 2f, cy - bh / 2f, bw, bh);
        gfx.setAlpha(1f);
        // Generous touch target around the small icon.
        btnRect[BTN_PAUSE][0] = x0 - sz * 0.4f;
        btnRect[BTN_PAUSE][1] = 0;
        btnRect[BTN_PAUSE][2] = x0 + sz * 1.4f;
        btnRect[BTN_PAUSE][3] = y0 + sz * 1.3f;
        btnShown[BTN_PAUSE] = true;
        gfx.setStroke(lineW);
    }

    // ================================================================ messages

    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    private static String date(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format("%2d %s", c.get(java.util.Calendar.DAY_OF_MONTH), MONTHS[c.get(java.util.Calendar.MONTH)]);
    }

    private void drawScores(Gfx gfx, Game g, float th) {
        float cx = w / 2f;
        gfx.setStroke(Math.max(2.5f, th * 0.14f));
        VectorFont.draw(gfx, "SCOREBOARD", cx, h * 0.06f, th * 1.3f, ALIGN_CENTER);
        gfx.setStroke(Math.max(2f, th * 0.1f));
        String best = String.format("HIGH SCORE %05d", g.highScore)
                + (g.highScoreLevel > 0 ? "   LEVEL " + g.highScoreLevel : "");
        VectorFont.draw(gfx, best, cx, h * 0.06f + th * 2.0f, th * 0.75f, ALIGN_CENTER);

        float s = th * 0.5f;
        float top = h * 0.06f + th * 3.9f;
        gfx.setStroke(Math.max(1.5f, s * 0.12f));
        gfx.setAlpha(0.7f);
        VectorFont.draw(gfx, "LAST " + Game.HISTORY_SIZE + " GAMES", cx, top, s, ALIGN_CENTER);
        gfx.setAlpha(1f);
        if (g.history.isEmpty()) {
            VectorFont.draw(gfx, "NO GAMES YET", cx, top + s * 3f, s, ALIGN_CENTER);
        }
        // Two columns of ten.
        float rowH = s * 1.75f;
        float y0 = top + s * 2.2f;
        float colW = w * 0.40f;
        for (int i = 0; i < g.history.size(); i++) {
            Game.Record r = g.history.get(i);
            int col = i / 10, row = i % 10;
            float x = cx - colW + col * colW + colW * 0.06f;
            String line = String.format("%2d. %05d  LV %-2d  %s", i + 1, r.score, r.level, date(r.time));
            boolean isBest = r.score == g.highScore && r.score > 0;
            gfx.setAlpha(isBest ? 1f : 0.8f);
            VectorFont.draw(gfx, line, x, y0 + row * rowH, s, ALIGN_LEFT);
        }
        gfx.setAlpha(1f);
        if (g.stateTime > 0.3f && blink(g, 1))
            VectorFont.draw(gfx, "TAP TO RETURN", cx, h * 0.92f, s, ALIGN_CENTER);
    }

    private static final String[][] TUTORIAL_TEXT = {
            {"TILT THE DEVICE LEFT AND RIGHT", "THE SHIP TURNS THE WAY YOU TILT"},
            {"HOLD THE RIGHT SIDE OF THE SCREEN", "TO FIRE THE ENGINE. IT PUSHES THE WAY THE SHIP POINTS"},
            {"TAP THE LEFT SIDE: ANTI-GRAVITY", "STOPS SIDEWAYS DRIFT AND SHIELDS YOU FOR 1 SECOND. 3 PER GAME"},
            {"FLY THROUGH THE FUEL TANK", "F = FUEL   O2 = OXYGEN   S = SHIELD"},
            {"GRAVITY IS ON. LAND ON A PAD", "SLOWLY (V UNDER 12), LITTLE SIDEWAYS SPEED, UPRIGHT"},
    };

    private void drawTutorial(Gfx gfx, Game g, float th) {
        if (!g.tutorial || g.state != Game.State.PLAYING || g.paused) return;
        int step = Math.min(g.tutorialStep, Game.TUTORIAL_STEPS - 1);
        float cx = w / 2f, y = h * 0.11f; // top center, between the score and the gauges
        gfx.setAlpha(0.7f);
        VectorFont.draw(gfx, "TUTORIAL " + (step + 1) + "/" + Game.TUTORIAL_STEPS, cx, y, th * 0.5f, ALIGN_CENTER);
        gfx.setAlpha(1f);
        VectorFont.draw(gfx, TUTORIAL_TEXT[step][0], cx, y + th * 1.1f, th * 0.75f, ALIGN_CENTER);
        gfx.setAlpha(0.85f);
        VectorFont.draw(gfx, TUTORIAL_TEXT[step][1], cx, y + th * 2.3f, th * 0.45f, ALIGN_CENTER);
        if (step == 0) {
            String l = (g.tutTiltLeft ? "[X]" : "[ ]") + " LEFT     " + (g.tutTiltRight ? "[X]" : "[ ]") + " RIGHT";
            VectorFont.draw(gfx, l.replace("[", "(").replace("]", ")"), cx, y + th * 3.3f, th * 0.45f, ALIGN_CENTER);
        } else if (step == 1) {
            float frac = Math.min(1f, g.tutThrustTime / 1.5f);
            float bw = w * 0.2f, bh = th * 0.35f, bx = cx - bw / 2f, by = y + th * 3.4f;
            buf[0] = bx; buf[1] = by;
            buf[2] = bx + bw; buf[3] = by;
            buf[4] = bx + bw; buf[5] = by + bh;
            buf[6] = bx; buf[7] = by + bh;
            gfx.polyline(buf, 4, true);
            if (frac > 0) gfx.fillRect(bx, by, bw * frac, bh);
        }
        gfx.setAlpha(1f);
    }

    /** The "why" lines under a landing or crash message. Returns the next free y. */
    private float drawExplain(Gfx gfx, Game g, float cx, float y, float s) {
        if (g.explain.isEmpty()) return y;
        float es = s * 0.85f;
        gfx.setAlpha(0.8f);
        for (String line : g.explain) {
            VectorFont.draw(gfx, line, cx, y, es, ALIGN_CENTER);
            y += es * 1.7f;
        }
        gfx.setAlpha(1f);
        return y + es * 0.8f;
    }

    /** Darkens a band behind message text so terrain and power-ups don't show through. */
    private void panel(Gfx gfx, float y0, float y1) {
        gfx.fillDark(w * 0.1f, y0, w * 0.8f, y1 - y0, 0.82f);
    }

    /** Height that the explain + breakdown block of a landing will take. */
    private float landedBlock(Game g, float th) {
        float s = th * 0.6f;
        return g.explain.size() * s * 0.85f * 1.7f + s * 0.7f + g.breakdown.size() * s * 1.7f + th * 3.2f;
    }

    private void drawOverlay(Gfx gfx, Game g) {
        float th = h * 0.045f;
        float cx = w / 2f;
        switch (g.state) {
            case BRIEFING:
                panel(gfx, h * 0.31f, h * 0.33f + th * 5.4f);
                break;
            case LANDED:
                panel(gfx, h * 0.22f, h * 0.24f + th * 2.2f + (g.tutorial ? th * 3f : landedBlock(g, th)));
                break;
            case CRASHED:
                panel(gfx, h * 0.22f, h * 0.24f + th * 3.3f + g.explain.size() * th * 0.5f * 0.85f * 1.7f + th * 2.2f);
                break;
            case GAME_OVER:
                panel(gfx, h * 0.26f, h * 0.28f + th * 5.8f);
                break;
            default:
                break;
        }
        if (g.paused) panel(gfx, h * 0.26f, h * 0.28f + th * 6.4f);
        gfx.setStroke(Math.max(2f, th * 0.1f));
        switch (g.state) {
            case TITLE: {
                gfx.setStroke(Math.max(2.5f, th * 0.16f));
                VectorFont.draw(gfx, "LUNAR LANDER", cx, h * 0.15f, th * 2.0f, ALIGN_CENTER);
                gfx.setStroke(Math.max(1.5f, th * 0.09f));
                float s = th * 0.5f;
                VectorFont.draw(gfx, "TILT TO STEER   RIGHT SIDE: THRUST   LEFT SIDE: ANTI-GRAVITY", cx, h * 0.34f, s, ALIGN_CENTER);
                float by = h * 0.50f, bt = th * 0.7f;
                gfx.setStroke(Math.max(2f, bt * 0.12f));
                button(gfx, BTN_PLAY, "PLAY", cx - w * 0.3f, by, bt, true);
                button(gfx, BTN_TUTORIAL, "TUTORIAL", cx - w * 0.1f, by, bt, !g.tutorialDone);
                button(gfx, BTN_SCORES, "SCORES", cx + w * 0.1f, by, bt, false);
                button(gfx, BTN_EXIT, "EXIT", cx + w * 0.3f, by, bt, false);
                gfx.setStroke(Math.max(1.5f, th * 0.09f));
                if (!g.tutorialDone && blink(g, 1))
                    VectorFont.draw(gfx, "FIRST TIME? TRY THE TUTORIAL", cx, by + bt * 2.4f, s, ALIGN_CENTER);
                if (g.highScore > 0)
                    VectorFont.draw(gfx, String.format("HIGH SCORE %05d", g.highScore)
                            + (g.highScoreLevel > 0 ? "   LEVEL " + g.highScoreLevel : ""), cx, h * 0.68f, s, ALIGN_CENTER);
                break;
            }
            case SCORES:
                drawScores(gfx, g, th);
                break;
            case BRIEFING: {
                if (g.tutorial) {
                    VectorFont.draw(gfx, "TUTORIAL", cx, h * 0.33f, th * 1.4f, ALIGN_CENTER);
                    VectorFont.draw(gfx, "5 SHORT STEPS. NO GRAVITY UNTIL THE LAST ONE", cx, h * 0.33f + th * 2.4f, th * 0.55f, ALIGN_CENTER);
                    if (blink(g, 1)) VectorFont.draw(gfx, "TAP TO BEGIN", cx, h * 0.33f + th * 4.2f, th * 0.6f, ALIGN_CENTER);
                    break;
                }
                VectorFont.draw(gfx, "LEVEL " + g.levelNumber, cx, h * 0.33f, th * 1.4f, ALIGN_CENTER);
                VectorFont.draw(gfx, g.level.briefing(), cx, h * 0.33f + th * 2.4f, th * 0.7f, ALIGN_CENTER);
                if (blink(g, 1)) VectorFont.draw(gfx, "TAP TO LAUNCH", cx, h * 0.33f + th * 4.4f, th * 0.6f, ALIGN_CENTER);
                break;
            }
            case PLAYING:
                drawTutorial(gfx, g, th);
                break;
            case LANDED: {
                VectorFont.draw(gfx, g.message, cx, h * 0.24f, th * 1.2f, ALIGN_CENTER);
                float s = th * 0.6f;
                float y = h * 0.24f + th * 2.2f;
                if (g.tutorial) {
                    VectorFont.draw(gfx, g.detail, cx, y, s, ALIGN_CENTER);
                    if (g.stateTime > 1f && blink(g, 1))
                        VectorFont.draw(gfx, "TAP TO RETURN", cx, y + th * 2f, s, ALIGN_CENTER);
                    break;
                }
                y = drawExplain(gfx, g, cx, y, s);
                for (String line : g.breakdown) {
                    VectorFont.draw(gfx, line, cx, y, s, ALIGN_CENTER);
                    y += s * 1.7f;
                }
                VectorFont.draw(gfx, "+" + g.lastLandingPoints, cx, y + s * 0.4f, th * 0.9f, ALIGN_CENTER);
                if (g.stateTime > 1f && blink(g, 1))
                    VectorFont.draw(gfx, "TAP FOR NEXT LEVEL", cx, y + th * 2.2f, s, ALIGN_CENTER);
                break;
            }
            case CRASHED: {
                VectorFont.draw(gfx, g.message, cx, h * 0.24f, th * 1.2f, ALIGN_CENTER);
                VectorFont.draw(gfx, g.detail, cx, h * 0.24f + th * 2.0f, th * 0.6f, ALIGN_CENTER);
                float ey = drawExplain(gfx, g, cx, h * 0.24f + th * 3.3f, th * 0.5f);
                if (g.stateTime > (g.tutorial ? 1f : 1.5f)) {
                    String t = g.tutorial ? "TAP TO TRY AGAIN"
                            : g.lives > 0 ? (g.lives + (g.lives == 1 ? " SHIP LEFT. TAP TO RETRY" : " SHIPS LEFT. TAP TO RETRY"))
                            : "TAP TO CONTINUE";
                    if (blink(g, 1)) VectorFont.draw(gfx, t, cx, ey + th * 0.8f, th * 0.6f, ALIGN_CENTER);
                }
                break;
            }
            case GAME_OVER: {
                gfx.setStroke(Math.max(2.5f, th * 0.15f));
                VectorFont.draw(gfx, "GAME OVER", cx, h * 0.28f, th * 1.8f, ALIGN_CENTER);
                gfx.setStroke(Math.max(2f, th * 0.1f));
                VectorFont.draw(gfx, String.format("SCORE %05d   LEVEL %d", g.score, g.levelNumber), cx, h * 0.28f + th * 3f, th * 0.8f, ALIGN_CENTER);
                if (g.score > 0 && g.score >= g.highScore)
                    VectorFont.draw(gfx, "NEW HIGH SCORE", cx, h * 0.28f + th * 4.6f, th * 0.6f, ALIGN_CENTER);
                break;
            }
            default:
                break;
        }
        if (g.paused) {
            VectorFont.draw(gfx, "PAUSED", cx, h * 0.28f, th * 1.4f, ALIGN_CENTER);
            float bt = th * 0.7f, by = h * 0.28f + th * 3.6f;
            gfx.setStroke(Math.max(2f, bt * 0.12f));
            button(gfx, BTN_RESUME, "RESUME", cx - w * 0.14f, by, bt, true);
            button(gfx, BTN_QUIT, g.tutorial ? "EXIT" : "QUIT", cx + w * 0.14f, by, bt, false);
            if (!g.tutorial) {
                gfx.setAlpha(0.6f);
                VectorFont.draw(gfx, "QUIT ENDS THE GAME AND SAVES YOUR SCORE", cx, by + bt * 2.5f, th * 0.4f, ALIGN_CENTER);
                gfx.setAlpha(1f);
            }
        }
        gfx.setStroke(lineW);
    }
}
