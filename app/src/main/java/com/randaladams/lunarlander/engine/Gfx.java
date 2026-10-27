package com.randaladams.lunarlander.engine;

/**
 * Minimal vector drawing surface in screen pixels (y grows downward).
 * The Android app implements this on top of Canvas; desktop tools can
 * implement it with java.awt to render previews and tests.
 */
public interface Gfx {
    int width();

    int height();

    /** Line width in pixels for subsequent strokes. */
    void setStroke(float px);

    /** Opacity 0..1 for subsequent drawing. */
    void setAlpha(float a);

    void line(float x1, float y1, float x2, float y2);

    /** Draws count points from xy (x0,y0,x1,y1,...) as a connected line. */
    void polyline(float[] xy, int count, boolean closed);

    void circle(float cx, float cy, float r);

    void fillCircle(float cx, float cy, float r);

    void fillRect(float x, float y, float w, float h);

    void clear();
}
