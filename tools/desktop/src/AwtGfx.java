import com.adamselite.lunarlander.engine.Gfx;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** Gfx on a BufferedImage, with the same two-pass glow as the Android version. */
final class AwtGfx implements Gfx {
    final BufferedImage img;
    private final Graphics2D g;
    private float stroke = 2f, alpha = 1f;

    AwtGfx(int w, int h) {
        img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public int width() { return img.getWidth(); }
    public int height() { return img.getHeight(); }
    public void setStroke(float px) { stroke = px; }
    public void setAlpha(float a) { alpha = Math.max(0, Math.min(1, a)); }

    private void stroke(java.awt.Shape s) {
        g.setColor(new Color(1f, 1f, 1f, alpha * 0.16f));
        g.setStroke(new BasicStroke(stroke * 3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.setColor(new Color(1f, 1f, 1f, alpha));
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
    }

    public void line(float x1, float y1, float x2, float y2) {
        stroke(new Line2D.Float(x1, y1, x2, y2));
    }

    public void polyline(float[] xy, int count, boolean closed) {
        if (count < 2) return;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 1; i < count; i++) p.lineTo(xy[i * 2], xy[i * 2 + 1]);
        if (closed) p.closePath();
        stroke(p);
    }

    public void circle(float cx, float cy, float r) {
        stroke(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    public void fillCircle(float cx, float cy, float r) {
        g.setColor(new Color(1f, 1f, 1f, alpha));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    public void fillRect(float x, float y, float w, float h) {
        g.setColor(new Color(1f, 1f, 1f, alpha));
        g.fill(new java.awt.geom.Rectangle2D.Float(x, y, w, h));
    }

    public void fillDark(float x, float y, float w, float h, float a) {
        g.setColor(new Color(0f, 0f, 0f, Math.max(0f, Math.min(1f, a))));
        g.fill(new java.awt.geom.Rectangle2D.Float(x, y, w, h));
    }

    public void clear() {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
    }
}
