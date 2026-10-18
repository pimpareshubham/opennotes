package org.opennotes.app.ai;

import android.graphics.RectF;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.ImageItem;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.model.Stroke;
import org.opennotes.app.model.TextItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Dynamic, content-sized context for all ink and objects on a notebook page. */
public final class PageWritingContext {
    public final RectF contentBounds;
    public final float typicalStrokeHeight;
    public final long strokeCount;
    public final long sampleCount;
    public final long firstSampleTime;
    public final long lastSampleTime;

    private PageWritingContext(RectF bounds, float typicalHeight, long strokes, long samples,
                               long firstTime, long lastTime) {
        contentBounds = bounds;
        typicalStrokeHeight = typicalHeight;
        strokeCount = strokes;
        sampleCount = samples;
        firstSampleTime = firstTime;
        lastSampleTime = lastTime;
    }

    public static PageWritingContext from(Page page) {
        RectF content = new RectF();
        boolean hasContent = false;
        long strokeCount = 0L;
        long sampleCount = 0L;
        long firstTime = Long.MAX_VALUE;
        long lastTime = Long.MIN_VALUE;
        List<Float> heights = new ArrayList<>();

        if (page != null && page.strokes != null) {
            List<Stroke> strokes;
            synchronized (page.strokes) { strokes = new ArrayList<>(page.strokes); }
            for (Stroke stroke : strokes) {
                if (stroke == null) continue;
                strokeCount++;
                RectF bounds = stroke.getBounds();
                if (bounds != null && !bounds.isEmpty()) {
                    content.union(bounds);
                    hasContent = true;
                    if (bounds.height() > 0f && Float.isFinite(bounds.height())) heights.add(bounds.height());
                }
                List<DrawingPoint> points = stroke.getRecognitionPoints();
                if (points == null) continue;
                sampleCount += points.size();
                for (DrawingPoint point : points) {
                    if (point == null || point.timeMillis < 0L) continue;
                    firstTime = Math.min(firstTime, point.timeMillis);
                    lastTime = Math.max(lastTime, point.timeMillis);
                }
            }
        }
        if (page != null && page.shapes != null) {
            for (ShapeItem shape : new ArrayList<>(page.shapes)) {
                if (shape == null) continue;
                RectF bounds = shape.getBounds();
                if (bounds != null && !bounds.isEmpty()) { content.union(bounds); hasContent = true; }
            }
        }
        if (page != null && page.texts != null) {
            for (TextItem text : new ArrayList<>(page.texts)) {
                if (text == null) continue;
                RectF bounds = text.getBounds();
                if (bounds != null && !bounds.isEmpty()) { content.union(bounds); hasContent = true; }
            }
        }
        if (page != null && page.images != null) {
            for (ImageItem image : new ArrayList<>(page.images)) {
                if (image == null) continue;
                RectF bounds = image.getBounds();
                if (bounds != null && !bounds.isEmpty()) { content.union(bounds); hasContent = true; }
            }
        }

        Collections.sort(heights);
        float typicalHeight = heights.isEmpty() ? 0f : heights.get(heights.size() / 2);
        return new PageWritingContext(hasContent ? content : new RectF(), typicalHeight,
                strokeCount, sampleCount,
                firstTime == Long.MAX_VALUE ? -1L : firstTime,
                lastTime == Long.MIN_VALUE ? -1L : lastTime);
    }

    /** Adds the currently active stroke without rescanning previously committed page content. */
    public PageWritingContext withActiveStroke(Stroke activeStroke) {
        if (activeStroke == null) return this;
        RectF bounds = new RectF(contentBounds);
        RectF activeBounds = activeStroke.getBounds();
        if (activeBounds != null && !activeBounds.isEmpty()) bounds.union(activeBounds);
        List<DrawingPoint> points = activeStroke.getRecognitionPoints();
        long samples = points == null ? 0L : points.size();
        long first = firstSampleTime;
        long last = lastSampleTime;
        if (points != null) {
            for (DrawingPoint point : points) {
                if (point == null || point.timeMillis < 0L) continue;
                first = first < 0L ? point.timeMillis : Math.min(first, point.timeMillis);
                last = Math.max(last, point.timeMillis);
            }
        }
        float typicalHeight = typicalStrokeHeight;
        if (!(typicalHeight > 0f) && activeBounds != null) typicalHeight = activeBounds.height();
        return new PageWritingContext(bounds, typicalHeight, strokeCount + 1L,
                sampleCount + samples, first, last);
    }
}
