package com.randaladams.lunarlander;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import com.randaladams.lunarlander.engine.Gfx;

/**
 * {@link Gfx} on an Android Canvas. Every stroke is drawn twice — a wide,
 * faint pass and a thin bright pass — to fake the glow of a vector monitor.
 */
final class AndroidGfx implements Gfx {
    private static final float GLOW_WIDTH = 3.2f;
    private static final float GLOW_ALPHA = 0.16f;

    private Canvas canvas;
    private int width, height;
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float alpha = 1f;

    AndroidGfx() {
        for (Paint p : new Paint[]{core, glow}) {
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        setStroke(2f);
        setAlpha(1f);
    }

    void begin(Canvas c, int w, int h) {
        canvas = c;
        width = w;
        height = h;
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override
    public void setStroke(float px) {
        core.setStrokeWidth(px);
        glow.setStrokeWidth(px * GLOW_WIDTH);
    }

    @Override
    public void setAlpha(float a) {
        alpha = Math.max(0f, Math.min(1f, a));
        core.setAlpha(Math.round(alpha * 255));
        glow.setAlpha(Math.round(alpha * GLOW_ALPHA * 255));
        fill.setAlpha(Math.round(alpha * 255));
    }

    @Override
    public void line(float x1, float y1, float x2, float y2) {
        canvas.drawLine(x1, y1, x2, y2, glow);
        canvas.drawLine(x1, y1, x2, y2, core);
    }

    @Override
    public void polyline(float[] xy, int count, boolean closed) {
        if (count < 2) return;
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 1; i < count; i++) path.lineTo(xy[i * 2], xy[i * 2 + 1]);
        if (closed) path.close();
        canvas.drawPath(path, glow);
        canvas.drawPath(path, core);
    }

    @Override
    public void circle(float cx, float cy, float r) {
        canvas.drawCircle(cx, cy, r, glow);
        canvas.drawCircle(cx, cy, r, core);
    }

    @Override
    public void fillCircle(float cx, float cy, float r) {
        canvas.drawCircle(cx, cy, r, fill);
    }

    @Override
    public void fillRect(float x, float y, float w, float h) {
        canvas.drawRect(x, y, x + w, y + h, fill);
    }

    @Override
    public void clear() {
        canvas.drawColor(Color.BLACK);
    }
}
