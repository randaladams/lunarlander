package com.randaladams.lunarlander.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Jagged lunar surface: a height field polyline with flat landing pads. */
public final class Terrain {

    public static final class Pad {
        public final float x1, x2, y;
        public int multiplier;
        /** Rescue missions: the SOS pad where the astronaut waits (pays nothing itself). */
        public boolean rescue;

        Pad(float x1, float x2, float y) {
            this.x1 = x1;
            this.x2 = x2;
            this.y = y;
        }

        public float width() {
            return x2 - x1;
        }

        public float centerX() {
            return (x1 + x2) / 2f;
        }
    }

    public final float[] xs;
    public final float[] ys;
    public final List<Pad> pads;
    /** The widest, lowest-paying pad: the ship starts near it. */
    public Pad homePad;

    private Terrain(float[] xs, float[] ys, List<Pad> pads) {
        this.xs = xs;
        this.ys = ys;
        this.pads = pads;
    }

    public int size() {
        return xs.length;
    }

    /** Ground height at x (linear interpolation). */
    public float heightAt(float x) {
        if (x <= xs[0]) return ys[0];
        int n = xs.length;
        if (x >= xs[n - 1]) return ys[n - 1];
        int i = Arrays.binarySearch(xs, x);
        if (i >= 0) return ys[i];
        int hi = -i - 1;
        int lo = hi - 1;
        float t = (x - xs[lo]) / (xs[hi] - xs[lo]);
        return ys[lo] + (ys[hi] - ys[lo]) * t;
    }

    /** Highest ground between x0 and x1. */
    public float maxHeight(float x0, float x1) {
        float m = Math.max(heightAt(x0), heightAt(x1));
        for (int i = 0; i < xs.length; i++) {
            if (xs[i] > x0 && xs[i] < x1) m = Math.max(m, ys[i]);
        }
        return m;
    }

    /** The pad that spans the whole range [x0, x1], or null. */
    public Pad padSpanning(float x0, float x1) {
        for (Pad p : pads) {
            if (x0 >= p.x1 - 0.5f && x1 <= p.x2 + 0.5f) return p;
        }
        return null;
    }

    public boolean overPad(float x, float margin) {
        for (Pad p : pads) {
            if (x > p.x1 - margin && x < p.x2 + margin) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- generation

    private static float[] noiseTable(Random r, int n) {
        float[] t = new float[n + 2];
        for (int i = 0; i < t.length; i++) t[i] = r.nextFloat() * 2f - 1f;
        return t;
    }

    private static float sample(float[] table, float u) {
        // u in [0,1] across the world; cosine interpolation between knots.
        float p = u * (table.length - 2);
        int i = (int) p;
        float f = p - i;
        float s = (1f - (float) Math.cos(f * Math.PI)) * 0.5f;
        return table[i] * (1 - s) + table[i + 1] * s;
    }

    public static Terrain generate(Random r, Level lv, float worldW) {
        final float base = 150f;
        final float minY = 40f, maxY = 380f; // minY keeps ground above the control labels
        float[] o1 = noiseTable(r, 3);
        float[] o2 = noiseTable(r, 7);
        float[] o3 = noiseTable(r, 17);
        float amp = lv.terrainAmplitude;

        // Smooth large-scale shape.
        int coarseN = 400;
        float[] smooth = new float[coarseN + 1];
        for (int i = 0; i <= coarseN; i++) {
            float u = i / (float) coarseN;
            float h = base + amp * (sample(o1, u) + 0.5f * sample(o2, u) + 0.25f * sample(o3, u));
            smooth[i] = clamp(h, minY + 10, maxY - 20);
        }

        // Place pads: one per equal slice of the surface, so there's never a
        // huge empty stretch with no pad. The ship starts over the "home"
        // slice at one end; pads get narrower (and pay more) the further they
        // are from home, so the big multipliers are the long trip.
        // On rescue missions an extra SOS pad takes a slice in the middle.
        List<Pad> pads = new ArrayList<>();
        int n = lv.padCount;
        int slots = n + (lv.rescue ? 1 : 0);
        int home = r.nextBoolean() ? 0 : slots - 1;
        int sos = lv.rescue ? slots / 2 : -1;
        int[] rank = new int[slots]; // scoring pads: 0 = home, n-1 = farthest
        for (int i = 0, k = 0; i < slots; i++) {
            int idx = home == 0 ? i : slots - 1 - i;
            if (idx == sos) continue;
            rank[idx] = k++;
        }
        float slice = (worldW - 100f) / slots;
        for (int i = 0; i < slots; i++) {
            float t = n == 1 ? 0f : rank[i] / (float) (n - 1);
            float w = i == sos ? (lv.widestPad + lv.narrowestPad) / 2f
                    : lv.widestPad + (lv.narrowestPad - lv.widestPad) * t;
            float lo = 50f + i * slice + w / 2f + 35f;
            float hi = 50f + (i + 1) * slice - w / 2f - 35f;
            float cx = hi > lo ? lo + r.nextFloat() * (hi - lo) : (lo + hi) / 2f;
            float y = smooth[Math.round(cx / worldW * coarseN)];
            Pad pad = new Pad(cx - w / 2f, cx + w / 2f, clamp(y, 60f, 320f));
            pad.rescue = i == sos;
            pads.add(pad);
        }

        // Jagged surface points, skipping pad interiors.
        List<float[]> pts = new ArrayList<>();
        float x = 0f;
        while (x <= worldW) {
            Pad in = null;
            for (Pad p : pads) {
                if (x >= p.x1 && x <= p.x2) {
                    in = p;
                    break;
                }
            }
            if (in == null) {
                float h = smooth[Math.round(clamp(x / worldW, 0, 1) * coarseN)];
                h += (r.nextFloat() * 2f - 1f) * lv.terrainJag;
                // Occasional sharp spire or crevice for that jagged look.
                if (r.nextFloat() < 0.08f) h += (r.nextFloat() < 0.5f ? 1 : -1) * lv.terrainJag * 2.2f;
                pts.add(new float[]{x, clamp(h, minY, maxY)});
                x += 7f + r.nextFloat() * 13f;
            } else {
                x = in.x2 + 4f + r.nextFloat() * 8f;
            }
        }
        // Make sure the last point sits on the right edge.
        float[] last = pts.get(pts.size() - 1);
        if (last[0] < worldW) {
            pts.add(new float[]{worldW, last[1]});
        }
        for (Pad p : pads) {
            pts.add(new float[]{p.x1, p.y});
            pts.add(new float[]{p.x2, p.y});
        }
        Collections.sort(pts, (a, b) -> Float.compare(a[0], b[0]));

        // Remove points that are too close to a pad edge (keeps pads exactly flat).
        List<float[]> clean = new ArrayList<>();
        for (float[] p : pts) {
            boolean edge = false, inside = false;
            for (Pad pad : pads) {
                if (p[0] == pad.x1 || p[0] == pad.x2) edge = true;
                else if (p[0] > pad.x1 - 2f && p[0] < pad.x2 + 2f) inside = true;
            }
            if (edge || !inside) clean.add(p);
        }

        float[] xs = new float[clean.size()];
        float[] ys = new float[clean.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = clean.get(i)[0];
            ys[i] = clean.get(i)[1];
        }

        // Payout by distance from home: the pad you start next to is the cheap
        // one, the far end is X5. (Pads are in left-to-right slice order.)
        int[][] table = {{5}, {2, 5}, {1, 3, 5}, {1, 2, 3, 5}};
        int[] mult = table[Math.min(n, 4) - 1];
        for (int i = 0; i < slots; i++)
            pads.get(i).multiplier = i == sos ? 0 : mult[Math.min(rank[i], mult.length - 1)];
        Pad homePad = pads.get(home);
        Terrain terrain = new Terrain(xs, ys, pads);
        terrain.homePad = homePad;
        return terrain;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
