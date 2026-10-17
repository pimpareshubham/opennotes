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
        final float width;
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
                this.width = Math.max(1f, pMaxX - pMinX);
                this.height = Math.max(1f, pMaxY - pMinY);
            } else {
                this.cx = originalBounds.centerX();
                this.cy = originalBounds.centerY();
                this.minX = originalBounds.left;
                this.maxX = originalBounds.right;
                this.minY = originalBounds.top;
                this.maxY = originalBounds.bottom;
                this.width = Math.max(1f, originalBounds.width());
                this.height = Math.max(1f, originalBounds.height());
            }

            this.minTime = tMin == Long.MAX_VALUE ? 0L : tMin;
            this.maxTime = tMax == Long.MIN_VALUE ? 0L : tMax;
        }
    }

    private static int dsuFind(int[] parent, int i) {
        int root = i;
        while (root != parent[root]) {
            root = parent[root];
        }
        int curr = i;
        while (curr != root) {
            int nxt = parent[curr];
            parent[curr] = root;
            curr = nxt;
        }
        return root;
    }

    /**
     * Determines whether a set of strokes contains multiple distinct lines or rows of writing.
     */
    public static boolean isMultiLine(List<Stroke> strokes) {
        if (strokes == null || strokes.size() < 2) return false;
        List<StrokeLine> lines = segmentIntoLines(strokes);
        return lines != null && lines.size() > 1;
    }

    /**
     * Segments strokes into horizontal lines of writing.
     * Uses connected component analysis with Devanagari diacritic attachment
     * and horizontal baseline cluster merging.
     */
    public static List<StrokeLine> segmentIntoLines(List<Stroke> strokes) {
        List<StrokeLine> result = new ArrayList<>();
        if (strokes == null || strokes.isEmpty()) return result;

        if (strokes.size() == 1) {
            RectF b = InkNormalizer.getBounds(strokes);
            List<StrokeWord> words = Collections.singletonList(new StrokeWord(new ArrayList<>(strokes), b, 0f));
            result.add(new StrokeLine(new ArrayList<>(strokes), words, b, 0f));
            return result;
        }

        RectF overallBounds = InkNormalizer.getBounds(strokes);
        float angleDegrees = InkNormalizer.detectWritingAngleDegrees(strokes);
        boolean isRotated = Math.abs(angleDegrees) >= 3.5f;
        float rotAngle = isRotated ? angleDegrees : 0f;
        float pivotX = overallBounds != null ? overallBounds.centerX() : 0f;
        float pivotY = overallBounds != null ? overallBounds.centerY() : 0f;

        List<ProjectedStroke> projected = new ArrayList<>(strokes.size());
        for (Stroke s : strokes) {
            if (s != null) {
                projected.add(new ProjectedStroke(s, rotAngle, pivotX, pivotY));
            }
        }
        if (projected.isEmpty()) return result;
        if (projected.size() == 1) {
            RectF b = InkNormalizer.getBounds(strokes);
            List<StrokeWord> words = Collections.singletonList(new StrokeWord(new ArrayList<>(strokes), b, rotAngle));
            result.add(new StrokeLine(new ArrayList<>(strokes), words, b, rotAngle));
            return result;
        }

        // Estimate median stroke height excluding tiny flecks/noise
        List<Float> heights = new ArrayList<>();
        for (ProjectedStroke ps : projected) {
            if (ps.height > 6f) {
                heights.add(ps.height);
            }
        }
        if (heights.isEmpty()) {
            for (ProjectedStroke ps : projected) {
                heights.add(ps.height);
            }
        }
        Collections.sort(heights);
        float medianH = !heights.isEmpty() ? heights.get(heights.size() / 2) : 30f;
        medianH = Math.max(15f, medianH);

        int n = projected.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        // Form connected components of strokes on the same line
        for (int i = 0; i < n; i++) {
            ProjectedStroke s1 = projected.get(i);
            for (int j = i + 1; j < n; j++) {
                ProjectedStroke s2 = projected.get(j);

                float vOverlap = Math.max(0f, Math.min(s1.maxY, s2.maxY) - Math.max(s1.minY, s2.minY));
                float minH = Math.min(s1.height, s2.height);

                float hOverlap = Math.max(0f, Math.min(s1.maxX, s2.maxX) - Math.max(s1.minX, s2.minX));
                float minW = Math.min(s1.width, s2.width);

                float vGap = Math.max(0f, Math.max(s1.minY, s2.minY) - Math.min(s1.maxY, s2.maxY));
                float hGap = Math.max(0f, Math.max(s1.minX, s2.minX) - Math.min(s1.maxX, s2.maxX));
                float cyDist = Math.abs(s1.cy - s2.cy);

                boolean isDiacritic = (minH < 0.45f * medianH)
                        || (minH < 0.35f * Math.max(s1.height, s2.height));

                boolean isSameLine = false;

                if (isDiacritic) {
                    // Vertical diacritic / accent / matra / shirorekha attached to character body
                    if (hOverlap >= 0.20f * minW
                            || (s1.minX >= s2.minX - 15f && s1.maxX <= s2.maxX + 15f)
                            || (s2.minX >= s1.minX - 15f && s2.maxX <= s1.maxX + 15f)) {
                        if (vGap <= 0.55f * medianH || cyDist <= 0.85f * medianH) {
                            isSameLine = true;
                        }
                    }
                } else {
                    // Both are main character strokes: they must be on the same horizontal baseline!
                    if (cyDist <= 0.42f * medianH) {
                        if (vOverlap >= 0.20f * minH || vGap <= 0.25f * medianH) {
                            isSameLine = true;
                        }
                    }
                }

                if (isSameLine) {
                    int rootI = dsuFind(parent, i);
                    int rootJ = dsuFind(parent, j);
                    if (rootI != rootJ) {
                        parent[rootI] = rootJ;
                    }
                }
            }
        }

        // Group into initial clusters
        java.util.Map<Integer, List<ProjectedStroke>> clustersMap = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = dsuFind(parent, i);
            List<ProjectedStroke> list = clustersMap.get(root);
            if (list == null) {
                list = new ArrayList<>();
                clustersMap.put(root, list);
            }
            list.add(projected.get(i));
        }
        List<List<ProjectedStroke>> clusterList = new ArrayList<>(clustersMap.values());

        // Merge horizontally separated word clusters that lie along the same baseline
        if (clusterList.size() > 1) {
            boolean merged = true;
            while (merged) {
                merged = false;
                for (int i = 0; i < clusterList.size(); i++) {
                    List<ProjectedStroke> c1 = clusterList.get(i);
                    float c1Top = Float.MAX_VALUE, c1Bottom = -Float.MAX_VALUE, sumCy1 = 0f;
                    for (ProjectedStroke s : c1) {
                        c1Top = Math.min(c1Top, s.minY);
                        c1Bottom = Math.max(c1Bottom, s.maxY);
                        sumCy1 += s.cy;
                    }
                    float c1Cy = sumCy1 / c1.size();
                    float c1H = Math.max(1f, c1Bottom - c1Top);

                    for (int j = i + 1; j < clusterList.size(); j++) {
                        List<ProjectedStroke> c2 = clusterList.get(j);
                        float c2Top = Float.MAX_VALUE, c2Bottom = -Float.MAX_VALUE, sumCy2 = 0f;
                        for (ProjectedStroke s : c2) {
                            c2Top = Math.min(c2Top, s.minY);
                            c2Bottom = Math.max(c2Bottom, s.maxY);
                            sumCy2 += s.cy;
                        }
                        float c2Cy = sumCy2 / c2.size();
                        float c2H = Math.max(1f, c2Bottom - c2Top);

                        float cyDiff = Math.abs(c1Cy - c2Cy);
                        float vOverlap = Math.max(0f, Math.min(c1Bottom, c2Bottom) - Math.max(c1Top, c2Top));
                        float minClusterH = Math.min(c1H, c2H);

                        if (cyDiff <= 0.40f * medianH && (vOverlap >= 0.25f * minClusterH || cyDiff <= 0.30f * medianH)) {
                            c1.addAll(c2);
                            clusterList.remove(j);
                            merged = true;
                            break;
                        }
                    }
                    if (merged) break;
                }
            }
        }

        // Absorb orphan marks (dots, accents, short diacritics) into the nearest line cluster
        if (clusterList.size() > 1) {
            for (int i = clusterList.size() - 1; i >= 0; i--) {
                List<ProjectedStroke> cluster = clusterList.get(i);
                float cTop = Float.MAX_VALUE, cBottom = -Float.MAX_VALUE, cCy = 0f;
                for (ProjectedStroke s : cluster) {
                    cTop = Math.min(cTop, s.minY);
                    cBottom = Math.max(cBottom, s.maxY);
                    cCy += s.cy;
                }
                cCy /= cluster.size();
                float cH = cBottom - cTop;
                if (cluster.size() <= 2 && cH < 0.45f * medianH && clusterList.size() > 1) {
                    int bestIdx = -1;
                    float minDistance = Float.MAX_VALUE;
                    for (int k = 0; k < clusterList.size(); k++) {
                        if (k == i) continue;
                        List<ProjectedStroke> target = clusterList.get(k);
                        float targetCy = 0f;
                        for (ProjectedStroke s : target) targetCy += s.cy;
                        targetCy /= target.size();
                        float dist = Math.abs(cCy - targetCy);
                        if (dist < minDistance) {
                            minDistance = dist;
                            bestIdx = k;
                        }
                    }
                    if (bestIdx != -1 && minDistance <= 1.0f * medianH) {
                        clusterList.get(bestIdx).addAll(cluster);
                        clusterList.remove(i);
                    }
                }
            }
        }

        // Sort lines top to bottom by vertical center
        Collections.sort(clusterList, (l1, l2) -> {
            float sum1 = 0f;
            for (ProjectedStroke s : l1) sum1 += s.cy;
            float avg1 = sum1 / l1.size();

            float sum2 = 0f;
            for (ProjectedStroke s : l2) sum2 += s.cy;
            float avg2 = sum2 / l2.size();

            return Float.compare(avg1, avg2);
        });

        // Reconstruct StrokeLines preserving each stroke's natural temporal drawing order
        for (List<ProjectedStroke> cluster : clusterList) {
            java.util.Set<Stroke> lineStrokesSet = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (ProjectedStroke ps : cluster) {
                lineStrokesSet.add(ps.originalStroke);
            }
            List<Stroke> orderedLineStrokes = new ArrayList<>();
            for (Stroke s : strokes) {
                if (lineStrokesSet.contains(s)) {
                    orderedLineStrokes.add(s);
                }
            }
            if (orderedLineStrokes.isEmpty()) continue;

            RectF lineBounds = InkNormalizer.getBounds(orderedLineStrokes);
            List<StrokeWord> words = Collections.singletonList(
                    new StrokeWord(new ArrayList<>(orderedLineStrokes), lineBounds, rotAngle));
            result.add(new StrokeLine(orderedLineStrokes, words, lineBounds, rotAngle));
        }

        return result;
    }
}
