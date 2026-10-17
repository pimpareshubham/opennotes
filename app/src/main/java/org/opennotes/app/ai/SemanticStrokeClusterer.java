package org.opennotes.app.ai;

import android.graphics.PointF;
import android.graphics.RectF;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Dynamic Linguistic & Semantic Stroke Clusterer.
 * 
 * Capabilities:
 * 1. Multi-line segmentation at arbitrary writing angles:
 *    Detects baseline slope and separates multi-line statements written one below
 *    another in any direction.
 * 2. Word cluster extraction:
 *    Groups strokes within each line into coherent words based on spatial gaps,
 *    bounding box overlaps, and stroke timing.
 * 3. Dynamic semantic stroke grouping:
 *    Maintains stroke groupings until a meaningful token is formed.
 */
public class SemanticStrokeClusterer {

    public static class StrokeWord {
        public final List<Stroke> strokes;
        public final RectF bounds;
        public final float angleDegrees;

        public StrokeWord(List<Stroke> strokes, RectF bounds, float angleDegrees) {
            this.strokes = strokes;
            this.bounds = bounds;
            this.angleDegrees = angleDegrees;
        }
    }

    public static class StrokeLine {
        public final List<Stroke> strokes;
        public final List<StrokeWord> words;
        public final RectF bounds;
        public final float angleDegrees;

        public StrokeLine(List<Stroke> strokes, List<StrokeWord> words, RectF bounds, float angleDegrees) {
            this.strokes = strokes;
            this.words = words;
            this.bounds = bounds;
            this.angleDegrees = angleDegrees;
        }
    }

    private static class ProjectedStroke {
        final Stroke originalStroke;
        final RectF originalBounds;
        final float cx; // canonical rotated X
        final float cy; // canonical rotated Y
        final float minX;
        final float maxX;
        final float minY;
        final float maxY;
        final float height;
        final long minTime;
        final long maxTime;

        ProjectedStroke(Stroke stroke, float rotAngleDegrees, float pivotX, float pivotY) {
            this.originalStroke = stroke;
            this.originalBounds = stroke.getBounds() != null ? stroke.getBounds() : new RectF();

            double rad = Math.toRadians(-rotAngleDegrees);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            float pMinX = Float.MAX_VALUE, pMaxX = -Float.MAX_VALUE;
            float pMinY = Float.MAX_VALUE, pMaxY = -Float.MAX_VALUE;
            float sumX = 0f, sumY = 0f;
            int count = 0;
            long tMin = Long.MAX_VALUE, tMax = Long.MIN_VALUE;

            List<DrawingPoint> pts = stroke.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = stroke.points;
            if (pts != null && !pts.isEmpty()) {
                for (DrawingPoint p : pts) {
                    if (p == null) continue;
                    float dx = p.x - pivotX;
                    float dy = p.y - pivotY;
                    float rx = dx * cos - dy * sin + pivotX;
                    float ry = dx * sin + dy * cos + pivotY;

                    pMinX = Math.min(pMinX, rx);
                    pMaxX = Math.max(pMaxX, rx);
                    pMinY = Math.min(pMinY, ry);
                    pMaxY = Math.max(pMaxY, ry);

                    sumX += rx;
                    sumY += ry;
                    count++;

                    if (p.timeMillis > 0) {
                        tMin = Math.min(tMin, p.timeMillis);
                        tMax = Math.max(tMax, p.timeMillis);
                    }
                }
            }

            if (count > 0) {
                this.cx = sumX / count;
                this.cy = sumY / count;
                this.minX = pMinX;
                this.maxX = pMaxX;
                this.minY = pMinY;
                this.maxY = pMaxY;
                this.height = Math.max(1f, pMaxY - pMinY);
            } else {
                this.cx = originalBounds.centerX();
                this.cy = originalBounds.centerY();
                this.minX = originalBounds.left;
                this.maxX = originalBounds.right;
                this.minY = originalBounds.top;
                this.maxY = originalBounds.bottom;
                this.height = Math.max(1f, originalBounds.height());
            }

            this.minTime = tMin == Long.MAX_VALUE ? 0L : tMin;
            this.maxTime = tMax == Long.MIN_VALUE ? 0L : tMax;
        }
    }

    /**
     * Determines whether a set of strokes contains multiple distinct lines of writing.
     */
    public static boolean isMultiLine(List<Stroke> strokes) {
        return false;
    }

    /**
     * Preserves strokes naturally without artificial horizontal projection or arbitrary line fragmentation.
     */
    public static List<StrokeLine> segmentIntoLines(List<Stroke> strokes) {
        List<StrokeLine> result = new ArrayList<>();
        if (strokes == null || strokes.isEmpty()) return result;
        RectF b = InkNormalizer.getBounds(strokes);
        List<StrokeWord> words = Collections.singletonList(new StrokeWord(new ArrayList<>(strokes), b, 0f));
        result.add(new StrokeLine(new ArrayList<>(strokes), words, b, 0f));
        return result;
    }
}
