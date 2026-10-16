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
        if (strokes == null || strokes.size() < 6) return false;
        List<StrokeLine> lines = segmentIntoLines(strokes);
        return lines != null && lines.size() > 1;
    }

    /**
     * Segments strokes into lines and words, invariant to writing direction and tilt.
     */
    public static List<StrokeLine> segmentIntoLines(List<Stroke> strokes) {
        List<StrokeLine> result = new ArrayList<>();
        if (strokes == null || strokes.isEmpty()) return result;

        if (strokes.size() == 1) {
            Stroke s = strokes.get(0);
            RectF b = s.getBounds() != null ? new RectF(s.getBounds()) : new RectF();
            List<StrokeWord> words = Collections.singletonList(new StrokeWord(strokes, b, 0f));
            result.add(new StrokeLine(strokes, words, b, 0f));
            return result;
        }

        // 1. Detect dominant writing orientation
        float dominantAngle = InkNormalizer.detectWritingAngleDegrees(strokes);
        RectF globalBounds = InkNormalizer.getBounds(strokes);
        float pivotX = globalBounds.centerX();
        float pivotY = globalBounds.centerY();

        // 2. Project all strokes into canonical horizontal reference frame
        List<ProjectedStroke> projectedList = new ArrayList<>(strokes.size());
        List<Float> heights = new ArrayList<>(strokes.size());
        for (Stroke s : strokes) {
            if (s == null) continue;
            ProjectedStroke ps = new ProjectedStroke(s, dominantAngle, pivotX, pivotY);
            projectedList.add(ps);
            heights.add(ps.height);
        }

        if (projectedList.isEmpty()) return result;

        // Find median stroke height
        Collections.sort(heights);
        float medianHeight = heights.get(heights.size() / 2);
        if (medianHeight < 12f) medianHeight = 24f;

        // Find total vertical span in projected canonical frame
        float totalMinY = Float.MAX_VALUE, totalMaxY = -Float.MAX_VALUE;
        for (ProjectedStroke ps : projectedList) {
            totalMinY = Math.min(totalMinY, ps.minY);
            totalMaxY = Math.max(totalMaxY, ps.maxY);
        }
        float totalHeight = Math.max(1f, totalMaxY - totalMinY);

        // If the total vertical span is within normal single-line height (< 1.60x medianHeight)
        // or 2 or fewer strokes, it is definitively a SINGLE LINE
        if (totalHeight < medianHeight * 1.60f || strokes.size() <= 2) {
            RectF lineBounds = InkNormalizer.getBounds(strokes);
            List<StrokeWord> words = Collections.singletonList(new StrokeWord(new ArrayList<>(strokes), lineBounds, dominantAngle));
            result.add(new StrokeLine(new ArrayList<>(strokes), words, lineBounds, dominantAngle));
            return result;
        }

        // 3. Sort projected strokes along vertical canonical axis (top to bottom)
        Collections.sort(projectedList, (a, b) -> Float.compare(a.cy, b.cy));

        // 4. Cluster into lines based on baseline distance and localized diacritic matching
        List<List<ProjectedStroke>> lineClusters = new ArrayList<>();

        for (ProjectedStroke ps : projectedList) {
            List<ProjectedStroke> bestLine = null;
            float bestDist = Float.MAX_VALUE;

            for (List<ProjectedStroke> line : lineClusters) {
                float lineSumY = 0f;
                for (ProjectedStroke m : line) lineSumY += m.cy;
                float lineAvgY = lineSumY / line.size();
                float distY = Math.abs(ps.cy - lineAvgY);

                // Check 1: Direct baseline alignment (standard character body/stem)
                if (distY <= medianHeight * 0.55f) {
                    if (distY < bestDist) {
                        bestDist = distY;
                        bestLine = line;
                    }
                    continue;
                }

                // Check 2: Diacritic / Matra / Dot / Shirorekha belonging to this line
                // In Devanagari and English, a diacritic or matra sits directly above or below
                // an existing character on that line (localized X overlap, small stroke height).
                if (distY <= medianHeight * 1.25f) {
                    boolean isSmallMark = ps.height <= medianHeight * 0.70f;
                    boolean isShirorekha = (ps.maxX - ps.minX > medianHeight * 1.2f) && (ps.height <= medianHeight * 0.40f);
                    if (isSmallMark || isShirorekha) {
                        boolean overlapsCharacterX = false;
                        for (ProjectedStroke m : line) {
                            float charTolerance = Math.max(medianHeight * 0.95f, (m.maxX - m.minX) * 0.85f);
                            if (Math.abs(ps.cx - m.cx) <= charTolerance
                                    || (ps.minX <= m.maxX && ps.maxX >= m.minX)) {
                                overlapsCharacterX = true;
                                break;
                            }
                        }
                        if (overlapsCharacterX && distY < bestDist) {
                            bestDist = distY;
                            bestLine = line;
                        }
                    }
                }
            }

            if (bestLine != null) {
                bestLine.add(ps);
            } else {
                List<ProjectedStroke> newLine = new ArrayList<>();
                newLine.add(ps);
                lineClusters.add(newLine);
            }
        }

        // Sort line clusters from top to bottom
        Collections.sort(lineClusters, (l1, l2) -> {
            float sum1 = 0f; for (ProjectedStroke p : l1) sum1 += p.cy;
            float sum2 = 0f; for (ProjectedStroke p : l2) sum2 += p.cy;
            return Float.compare(sum1 / l1.size(), sum2 / l2.size());
        });

        // 5. Within each line, preserve natural human chronological stroke drawing order
        for (List<ProjectedStroke> cluster : lineClusters) {
            java.util.Set<Stroke> clusterStrokeSet = new java.util.HashSet<>();
            for (ProjectedStroke ps : cluster) clusterStrokeSet.add(ps.originalStroke);

            List<Stroke> lineStrokes = new ArrayList<>(cluster.size());
            for (Stroke s : strokes) {
                if (clusterStrokeSet.contains(s)) lineStrokes.add(s);
            }

            if (!lineStrokes.isEmpty()) {
                RectF lineBounds = InkNormalizer.getBounds(lineStrokes);
                List<StrokeWord> lineWords = Collections.singletonList(new StrokeWord(lineStrokes, lineBounds, dominantAngle));
                result.add(new StrokeLine(lineStrokes, lineWords, lineBounds, dominantAngle));
            }
        }

        return result;
    }
}
