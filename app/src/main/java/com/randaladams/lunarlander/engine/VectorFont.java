package com.randaladams.lunarlander.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * A stroke font in the spirit of 1979-era vector arcade games.
 * Each glyph lives on a 4 x 6 grid (y down). A glyph is a set of polylines
 * separated by '|', each polyline a run of digit pairs "xy".
 */
public final class VectorFont {
    private static final Map<Character, float[][]> GLYPHS = new HashMap<>();

    static {
        def('0', "0040460600|0640");
        def('1', "2026|1020|0646");
        def('2', "004043030646");
        def('3', "00404606|1343");
        def('4', "000343|4046");
        def('5', "400003434606");
        def('6', "400006464303");
        def('7', "004046");
        def('8', "0040460600|0343");
        def('9', "4640000343");
        def('A', "0602204246|0444");
        def('B', "00063645443303|0030414233");
        def('C', "40000646");
        def('D', "00062644422000");
        def('E', "40000646|0333");
        def('F', "400006|0333");
        def('G', "4140000006464323");
        def('H', "0006|4046|0343");
        def('I', "0040|2026|0646");
        def('J', "04064640");
        def('K', "0006|4003|0346");
        def('L', "000646");
        def('M', "0600224046");
        def('N', "06004640");
        def('O', "0040460600");
        def('P', "0600404303");
        def('Q', "004044260600|2446");
        def('R', "0600404303|0346");
        def('S', "400003434606");
        def('T', "0040|2026");
        def('U', "00064640");
        def('V', "002640");
        def('W', "0006244640");
        def('X', "0046|4006");
        def('Y', "0023|4023|2326");
        def('Z', "00400646");
        def('-', "0343");
        def('+', "0343|2125");
        def('.', "2526");
        def(',', "2516");
        def(':', "2122|2425");
        def('/', "0640");
        def('!', "2024|2526");
        def('?', "0040422324|2526");
        def('>', "004306");
        def('<', "400346");
        def('%', "0640|0001|4546");
        def('=', "0242|0444");
        def('(', "30212536");
        def(')', "10212516");
        def('\'', "2021");
    }

    private VectorFont() {
    }

    private static void def(char c, String spec) {
        GLYPHS.put(c, parse(spec));
    }

    private static float[][] parse(String spec) {
        String[] parts = spec.split("\\|");
        float[][] out = new float[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            float[] pts = new float[p.length()];
            for (int j = 0; j < p.length(); j++) {
                pts[j] = p.charAt(j) - '0';
            }
            out[i] = pts;
        }
        return out;
    }

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    /** Width in pixels of text drawn at the given cap height. */
    public static float measure(String text, float height) {
        float u = height / 6f;
        int n = text.length();
        return n == 0 ? 0 : (n * 6 - 2) * u;
    }

    private static final float[] TMP = new float[64];

    /** Draws text with its top edge at y. */
    public static void draw(Gfx g, String text, float x, float y, float height, int align) {
        float u = height / 6f;
        float w = measure(text, height);
        if (align == ALIGN_CENTER) x -= w / 2f;
        else if (align == ALIGN_RIGHT) x -= w;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            float[][] glyph = GLYPHS.get(Character.toUpperCase(c));
            if (glyph != null) {
                for (float[] poly : glyph) {
                    int n = poly.length / 2;
                    for (int k = 0; k < n; k++) {
                        TMP[k * 2] = x + poly[k * 2] * u;
                        TMP[k * 2 + 1] = y + poly[k * 2 + 1] * u;
                    }
                    g.polyline(TMP, n, false);
                }
            }
            x += 6 * u;
        }
    }
}
