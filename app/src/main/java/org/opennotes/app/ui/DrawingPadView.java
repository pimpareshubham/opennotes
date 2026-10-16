package org.opennotes.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive, smooth drawing canvas pad for the "Teach Handwriting Style" flow.
 * Captures user exemplar strokes with high-fidelity trajectory timing and pressure.
 */
public class DrawingPadView extends View {
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke currentStroke;
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint guidelinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path currentPath = new Path();
    private float lastX, lastY;

    public DrawingPadView(Context context) {
        super(context);
        init();
    }

    public DrawingPadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(0xFF38BDF8); // StarNote Cyan Accent
        strokePaint.setStrokeWidth(6.0f);

        guidelinePaint.setStyle(Paint.Style.STROKE);
        guidelinePaint.setColor(0xFF334155); // Slate guide lines
        guidelinePaint.setStrokeWidth(1.5f);
        guidelinePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{12f, 8f}, 0f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // Background
        canvas.drawColor(0xFF0F172A); // Dark Slate container

        // Baseline guideline for natural cursive alignment
        float baseline = h * 0.65f;
        canvas.drawLine(16f, baseline, w - 16f, baseline, guidelinePaint);
        float midline = h * 0.38f;
        canvas.drawLine(16f, midline, w - 16f, midline, guidelinePaint);

        // Draw completed strokes
        for (Stroke s : strokes) {
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts == null || pts.size() < 2) continue;

            Path p = new Path();
            p.moveTo(pts.get(0).x, pts.get(0).y);
            for (int i = 1; i < pts.size(); i++) {
                DrawingPoint p0 = pts.get(i - 1);
                DrawingPoint p1 = pts.get(i);
                p.quadTo(p0.x, p0.y, (p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f);
            }
            DrawingPoint last = pts.get(pts.size() - 1);
            p.lineTo(last.x, last.y);
            canvas.drawPath(p, strokePaint);
        }

        // Draw live active stroke
        if (!currentPath.isEmpty()) {
            canvas.drawPath(currentPath, strokePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        long time = event.getEventTime();
        float pressure = Math.max(0.2f, event.getPressure());

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                currentStroke = new Stroke(0xFF38BDF8, 6.0f, DrawingTool.PEN);
                currentStroke.addPoint(x, y, pressure, time);
                currentStroke.addRecognitionPoint(x, y, pressure, time);
                currentPath.reset();
                currentPath.moveTo(x, y);
                lastX = x;
                lastY = y;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (currentStroke != null) {
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        float hx = event.getHistoricalX(i);
                        float hy = event.getHistoricalY(i);
                        long ht = event.getHistoricalEventTime(i);
                        float hp = Math.max(0.2f, event.getHistoricalPressure(i));
                        currentStroke.addPoint(hx, hy, hp, ht);
                        currentStroke.addRecognitionPoint(hx, hy, hp, ht);
                        currentPath.quadTo(lastX, lastY, (lastX + hx) / 2f, (lastY + hy) / 2f);
                        lastX = hx;
                        lastY = hy;
                    }
                    currentStroke.addPoint(x, y, pressure, time);
                    currentStroke.addRecognitionPoint(x, y, pressure, time);
                    currentPath.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f);
                    lastX = x;
                    lastY = y;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (currentStroke != null) {
                    currentStroke.addPoint(x, y, pressure, time);
                    currentStroke.addRecognitionPoint(x, y, pressure, time);
                    currentPath.lineTo(x, y);
                    strokes.add(currentStroke);
                    currentStroke = null;
                    currentPath.reset();
                    invalidate();
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    public List<Stroke> getStrokes() {
        return new ArrayList<>(strokes);
    }

    public boolean hasStrokes() {
        return !strokes.isEmpty();
    }

    public void undo() {
        if (!strokes.isEmpty()) {
            strokes.remove(strokes.size() - 1);
            invalidate();
        }
    }

    public void clear() {
        strokes.clear();
        currentPath.reset();
        currentStroke = null;
        invalidate();
    }
}
