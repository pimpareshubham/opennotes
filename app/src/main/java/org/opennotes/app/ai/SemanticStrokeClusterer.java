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
        if (strokes == null || strokes.isEmpty()) return new ArrayList<>();

        if (strokes.size() == 1) {
            RectF b = InkNormalizer.getBounds(strokes);
            List<StrokeWord> words = Collections.singletonList(new StrokeWord(new ArrayList<>(strokes), b, 0f));
            List<StrokeLine> result = new ArrayList<>();
            result.add(new StrokeLine(new ArrayList<>(strokes), words, b, 0f));
            return result;
        }

        RectF overallBounds = InkNormalizer.getBounds(strokes);

        // First attempt: horizontal line segmentation at 0° (preserves natural multi-line rows without shearing)
        List<StrokeLine> horizontalLines = segmentIntoLinesAtAngle(strokes, 0f, overallBounds);
        if (isCleanHorizontalMultiLine(horizontalLines)) {
            return horizontalLines;
        }

        // If strokes do not form multiple horizontal rows at 0° (e.g. single line at an angle,
        // or paper tilted at a slant), detect writing angle and rotate to deskew.
        float angleDegrees = InkNormalizer.detectWritingAngleDegrees(strokes);
        boolean isRotated = Math.abs(angleDegrees) >= 3.5f;
        if (!isRotated) {
            return horizontalLines;
        }

        return segmentIntoLinesAtAngle(strokes, angleDegrees, overallBounds);
    }

    private static boolean isCleanHorizontalMultiLine(List<StrokeLine> lines) {
        if (lines == null || lines.size() < 2) return false;
        int substantialLines = 0;
        float prevCy = -Float.MAX_VALUE;

        for (int i = 0; i < lines.size(); i++) {
            StrokeLine line = lines.get(i);
            if (line == null || line.bounds == null || line.strokes == null) continue;
            float w = line.bounds.width();
            float h = line.bounds.height();
            if (line.strokes.size() >= 2 && w >= 1.25f * h) {
                substantialLines++;
            }
            float cy = line.bounds.centerY();
            if (i > 0 && cy <= prevCy) {
                return false;
            }
            prevCy = cy;
        }

        return substantialLines >= 2;
    }

    public static List<StrokeLine> segmentIntoLinesAtAngle(List<Stroke> strokes, float rotAngle, RectF overallBounds) {
        List<StrokeLine> result = new ArrayList<>();
        if (strokes == null || strokes.isEmpty()) return result;

        if (strokes.size() == 1) {
            RectF b = InkNormalizer.getBounds(strokes);
            List<StrokeWord> words = Collections.singletonList(new StrokeWord(new ArrayList<>(strokes), b, rotAngle));
            result.add(new StrokeLine(new ArrayList<>(strokes), words, b, rotAngle));
            return result;
        }

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

        int n = projected.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        // 1. Group strokes that overlap or are vertically aligned (diacritics/matras/accents/stems)
        for (int i = 0; i < n; i++) {
            ProjectedStroke s1 = projected.get(i);
            for (int j = i + 1; j < n; j++) {
                ProjectedStroke s2 = projected.get(j);

                float vOverlap = Math.max(0f, Math.min(s1.maxY, s2.maxY) - Math.max(s1.minY, s2.minY));
                float hOverlap = Math.max(0f, Math.min(s1.maxX, s2.maxX) - Math.max(s1.minX, s2.minX));

                // Diacritic or modifier directly above/below character body:
                boolean isDiacriticAlignment = (hOverlap > 0f)
                        || (s1.minX >= s2.minX - 30f && s1.maxX <= s2.maxX + 30f)
                        || (s2.minX >= s1.minX - 30f && s2.maxX <= s1.maxX + 30f);

                float vGap = Math.max(0f, Math.max(s1.minY, s2.minY) - Math.min(s1.maxY, s2.maxY));
                float hGap = Math.max(0f, Math.max(s1.minX, s2.minX) - Math.min(s1.maxX, s2.maxX));

                boolean connect = false;
                if (vOverlap > 0f && hOverlap > 0f) {
                    // Directly touching or overlapping
                    connect = true;
                } else if (isDiacriticAlignment && vGap <= 12f) {
                    // Vertical accent / matra / ukar / dot directly attached to character
                    connect = true;
                } else if (vOverlap > 0f && hGap <= 50f) {
                    // Consecutive strokes in the same word
                    connect = true;
                }

                if (connect) {
                    int rootI = dsuFind(parent, i);
                    int rootJ = dsuFind(parent, j);
                    if (rootI != rootJ) {
                        parent[rootJ] = rootI;
                    }
                }
            }
        }

        // Group into initial word/token clusters
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

        // 2. Merge word clusters along the same baseline row:
        // If two clusters share vertical overlap and are positioned side-by-side horizontally along the row
        // (i.e. neither cluster is a distinct row vertically below/above the other), merge them into the same line.
        if (clusterList.size() > 1) {
            boolean merged = true;
            while (merged) {
                merged = false;
                for (int i = 0; i < clusterList.size(); i++) {
                    List<ProjectedStroke> c1 = clusterList.get(i);
                    float c1Top = Float.MAX_VALUE, c1Bottom = -Float.MAX_VALUE;
                    float c1Left = Float.MAX_VALUE, c1Right = -Float.MAX_VALUE;
                    for (ProjectedStroke s : c1) {
                        c1Top = Math.min(c1Top, s.minY);
                        c1Bottom = Math.max(c1Bottom, s.maxY);
                        c1Left = Math.min(c1Left, s.minX);
                        c1Right = Math.max(c1Right, s.maxX);
                    }

                    for (int j = i + 1; j < clusterList.size(); j++) {
                        List<ProjectedStroke> c2 = clusterList.get(j);
                        float c2Top = Float.MAX_VALUE, c2Bottom = -Float.MAX_VALUE;
                        float c2Left = Float.MAX_VALUE, c2Right = -Float.MAX_VALUE;
                        for (ProjectedStroke s : c2) {
                            c2Top = Math.min(c2Top, s.minY);
                            c2Bottom = Math.max(c2Bottom, s.maxY);
                            c2Left = Math.min(c2Left, s.minX);
                            c2Right = Math.max(c2Right, s.maxX);
                        }

                        float vOverlap = Math.max(0f, Math.min(c1Bottom, c2Bottom) - Math.max(c1Top, c2Top));
                        boolean sideBySide = (c1Right <= c2Left + 20f) || (c2Right <= c1Left + 20f);

                        // If they share vertical baseline overlap and are side-by-side along the row, merge!
                        if (vOverlap > 0f && sideBySide) {
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

        // 3. Absorb any remaining orphan marks into the nearest line that covers their horizontal span
        if (clusterList.size() > 1) {
            for (int i = clusterList.size() - 1; i >= 0; i--) {
                List<ProjectedStroke> cluster = clusterList.get(i);
                if (cluster.size() > 2) continue;

                float cLeft = Float.MAX_VALUE, cRight = -Float.MAX_VALUE, sumCy = 0f;
                for (ProjectedStroke s : cluster) {
                    cLeft = Math.min(cLeft, s.minX);
                    cRight = Math.max(cRight, s.maxX);
                    sumCy += s.cy;
                }
                float cCy = sumCy / cluster.size();

                int bestIdx = -1;
                float minCyDist = Float.MAX_VALUE;
                for (int k = 0; k < clusterList.size(); k++) {
                    if (k == i) continue;
                    List<ProjectedStroke> target = clusterList.get(k);
                    float tLeft = Float.MAX_VALUE, tRight = -Float.MAX_VALUE, tSumCy = 0f;
                    for (ProjectedStroke s : target) {
                        tLeft = Math.min(tLeft, s.minX);
                        tRight = Math.max(tRight, s.maxX);
                        tSumCy += s.cy;
                    }
                    float tCy = tSumCy / target.size();

                    // Check if target line covers this mark horizontally
                    boolean coversX = (cLeft >= tLeft - 40f && cRight <= tRight + 40f);
                    float dist = Math.abs(cCy - tCy);
                    if (coversX && dist < minCyDist) {
                        minCyDist = dist;
                        bestIdx = k;
                    }
                }

                if (bestIdx != -1) {
                    clusterList.get(bestIdx).addAll(cluster);
                    clusterList.remove(i);
                }
            }
        }

        // Sort lines top to bottom by vertical position
        Collections.sort(clusterList, (l1, l2) -> {
            float minTop1 = Float.MAX_VALUE;
            for (ProjectedStroke s : l1) minTop1 = Math.min(minTop1, s.minY);

            float minTop2 = Float.MAX_VALUE;
            for (ProjectedStroke s : l2) minTop2 = Math.min(minTop2, s.minY);

            return Float.compare(minTop1, minTop2);
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
            List<StrokeWord> words = segmentLineIntoWords(orderedLineStrokes, 0f, rotAngle);
            result.add(new StrokeLine(orderedLineStrokes, words, lineBounds, rotAngle));
        }

        return result;
    }

    /**
     * Segments strokes of a single line into coherent word / token clusters.
     */
    public static List<StrokeWord> segmentLineIntoWords(List<Stroke> orderedLineStrokes, float medianH, float rotAngle) {
        return segmentLineIntoWords(orderedLineStrokes, rotAngle);
    }

    public static List<StrokeWord> segmentLineIntoWords(List<Stroke> orderedLineStrokes, float rotAngle) {
        List<StrokeWord> words = new ArrayList<>();
        if (orderedLineStrokes == null || orderedLineStrokes.isEmpty()) return words;
        if (orderedLineStrokes.size() == 1) {
            RectF b = InkNormalizer.getBounds(orderedLineStrokes);
            words.add(new StrokeWord(new ArrayList<>(orderedLineStrokes), b, rotAngle));
            return words;
        }

        RectF overallBounds = InkNormalizer.getBounds(orderedLineStrokes);
        float pivotX = overallBounds != null ? overallBounds.centerX() : 0f;
        float pivotY = overallBounds != null ? overallBounds.centerY() : 0f;

        List<ProjectedStroke> projected = new ArrayList<>(orderedLineStrokes.size());
        for (Stroke s : orderedLineStrokes) {
            if (s != null) {
                projected.add(new ProjectedStroke(s, rotAngle, pivotX, pivotY));
            }
        }
        if (projected.isEmpty()) return words;

        int n = projected.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            ProjectedStroke s1 = projected.get(i);
            for (int j = i + 1; j < n; j++) {
                ProjectedStroke s2 = projected.get(j);

                float hOverlap = Math.max(0f, Math.min(s1.maxX, s2.maxX) - Math.max(s1.minX, s2.minX));
                float hGap = Math.max(0f, Math.max(s1.minX, s2.minX) - Math.min(s1.maxX, s2.maxX));

                boolean isShirorekha1 = (s1.width >= 2.0f * s1.height && s1.width >= 25f);
                boolean isShirorekha2 = (s2.width >= 2.0f * s2.height && s2.width >= 25f);

                boolean sameWord = false;
                if (hOverlap > 0f) {
                    sameWord = true;
                } else if (isShirorekha1 && (s2.minX >= s1.minX - 10f && s2.maxX <= s1.maxX + 10f)) {
                    sameWord = true;
                } else if (isShirorekha2 && (s1.minX >= s2.minX - 10f && s1.maxX <= s2.maxX + 10f)) {
                    sameWord = true;
                } else if (hGap <= 15f) {
                    sameWord = true;
                }

                if (sameWord) {
                    int r1 = dsuFind(parent, i);
                    int r2 = dsuFind(parent, j);
                    if (r1 != r2) parent[r1] = r2;
                }
            }
        }

        java.util.Map<Integer, List<ProjectedStroke>> wordClusters = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = dsuFind(parent, i);
            List<ProjectedStroke> list = wordClusters.get(root);
            if (list == null) {
                list = new ArrayList<>();
                wordClusters.put(root, list);
            }
            list.add(projected.get(i));
        }

        List<List<ProjectedStroke>> wordList = new ArrayList<>(wordClusters.values());
        Collections.sort(wordList, (w1, w2) -> {
            float minX1 = Float.MAX_VALUE, minX2 = Float.MAX_VALUE;
            for (ProjectedStroke s : w1) minX1 = Math.min(minX1, s.minX);
            for (ProjectedStroke s : w2) minX2 = Math.min(minX2, s.minX);
            return Float.compare(minX1, minX2);
        });

        for (List<ProjectedStroke> cluster : wordList) {
            java.util.Set<Stroke> wordStrokesSet = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (ProjectedStroke ps : cluster) {
                wordStrokesSet.add(ps.originalStroke);
            }
            List<Stroke> orderedWordStrokes = new ArrayList<>();
            for (Stroke s : orderedLineStrokes) {
                if (wordStrokesSet.contains(s)) {
                    orderedWordStrokes.add(s);
                }
            }
            if (orderedWordStrokes.isEmpty()) continue;
            RectF wb = InkNormalizer.getBounds(orderedWordStrokes);
            words.add(new StrokeWord(orderedWordStrokes, wb, rotAngle));
        }

        return words;
    }
}
