package org.opennotes.app.ai;

import android.graphics.PointF;
import android.graphics.RectF;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced Geometric & Morphological Ink Normalizer.
 * Provides:
 * 1. Slant detection & de-shearing (uprights cursive & doctor's handwriting).
 * 2. Arbitrary-angle baseline orientation detection & rotation (handles writing in any direction).
 * 3. Inverse transformation back to natural page coordinates.
 */
public class InkNormalizer {

    public static class OrientationTransform {
        public final float angleDegrees;
        public final float pivotX;
        public final float pivotY;
        public final float slantAngleDegrees;

        public OrientationTransform(float angleDegrees, float pivotX, float pivotY, float slantAngleDegrees) {
            this.angleDegrees = angleDegrees;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.slantAngleDegrees = slantAngleDegrees;
        }

        public PointF toOriginal(float x, float y) {
            if (Math.abs(angleDegrees) < 2.0f) {
                return new PointF(x, y);
            }
            double rad = Math.toRadians(angleDegrees);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float dx = x - pivotX;
            float dy = y - pivotY;
            float rx = dx * cos - dy * sin + pivotX;
            float ry = dx * sin + dy * cos + pivotY;
            return new PointF(rx, ry);
        }
    }

    /**
     * Detects principal writing direction/angle across a collection of strokes.
     * Evaluates stroke centroid distribution and inter-point vectors.
     */
    /**
     * Detects principal writing direction/angle across a collection of strokes.
     * Uses Minimum Area Bounding Box (Oriented Bounding Box) aspect ratio maximization
     * coupled with Temporal Kinematic Progression to determine baseline slope across all directions.
     */
    public static float detectWritingAngleDegrees(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return 0f;

        List<DrawingPoint> allPoints = new ArrayList<>();
        float sumX = 0f, sumY = 0f;
        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts == null) continue;
            for (DrawingPoint p : pts) {
                if (p != null && Float.isFinite(p.x) && Float.isFinite(p.y)) {
                    allPoints.add(p);
                    sumX += p.x;
                    sumY += p.y;
                }
            }
        }
        if (allPoints.size() < 4) return 0f;

        final int pointCount = allPoints.size();
        final float cx = sumX / pointCount;
        final float cy = sumY / pointCount;

        // 1. Calculate temporal progression vector from early strokes/points to late strokes/points
        float progDx = 0f, progDy = 0f;
        boolean hasProgression = false;
        if (strokes.size() >= 2) {
            float earlyX = 0f, earlyY = 0f; int earlyCount = 0;
            float lateX = 0f, lateY = 0f; int lateCount = 0;
            int nStrokes = strokes.size();
            int split = Math.max(1, nStrokes / 3);
            for (int i = 0; i < split; i++) {
                RectF b = strokes.get(i).getBounds();
                if (b != null && !b.isEmpty()) {
                    earlyX += b.centerX(); earlyY += b.centerY(); earlyCount++;
                }
            }
            for (int i = nStrokes - split; i < nStrokes; i++) {
                RectF b = strokes.get(i).getBounds();
                if (b != null && !b.isEmpty()) {
                    lateX += b.centerX(); lateY += b.centerY(); lateCount++;
                }
            }
            if (earlyCount > 0 && lateCount > 0) {
                progDx = (lateX / lateCount) - (earlyX / earlyCount);
                progDy = (lateY / lateCount) - (earlyY / earlyCount);
                hasProgression = true;
            }
        } else if (allPoints.size() >= 8) {
            int k = Math.max(2, allPoints.size() / 5);
            float earlyX = 0f, earlyY = 0f;
            float lateX = 0f, lateY = 0f;
            for (int i = 0; i < k; i++) {
                earlyX += allPoints.get(i).x; earlyY += allPoints.get(i).y;
            }
            for (int i = allPoints.size() - k; i < allPoints.size(); i++) {
                lateX += allPoints.get(i).x; lateY += allPoints.get(i).y;
            }
            progDx = (lateX / k) - (earlyX / k);
            progDy = (lateY / k) - (earlyY / k);
            hasProgression = true;
        }

        float progDist = (float) Math.hypot(progDx, progDy);
        float progAngle = hasProgression && progDist > 5f
                ? (float) Math.toDegrees(Math.atan2(progDy, progDx))
                : 0f;

        RectF bounds = getBounds(strokes);
        if (bounds != null && bounds.height() >= bounds.width() * 1.05f) {
            // Check whether strokes exhibit horizontal line structure (multiple horizontal pairs of strokes)
            int horizontalPairs = countHorizontalStrokePairs(strokes);
            if (horizontalPairs >= 2) {
                // Multi-line paragraphs or vertical columns of numbers/words are stacked vertically.
                // Individual lines are written horizontally (0°). Never rotate by 90°!
                return 0f;
            }
        }

        // 2. Minimum Area Bounding Box / Aspect Ratio Sweep across angles [-115°, +115°]
        // Covers natural horizontal slope ([-45°, +45°]), steep angles, and vertical handwriting (+/-90°).
        float bestAngle = 0f;
        float maxScore = -1f;
        float bestAspectRatio = 1f;

        for (float deg = -115f; deg <= 115f; deg += 2.0f) {
            double rad = Math.toRadians(-deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

            for (DrawingPoint p : allPoints) {
                float dx = p.x - cx;
                float dy = p.y - cy;
                float rx = dx * cos - dy * sin;
                float ry = dx * sin + dy * cos;
                if (rx < minX) minX = rx;
                if (rx > maxX) maxX = rx;
                if (ry < minY) minY = ry;
                if (ry > maxY) maxY = ry;
            }

            float w = Math.max(1f, maxX - minX);
            float h = Math.max(1f, maxY - minY);
            float ar = w / h;
            float area = w * h;

            // Score favors high aspect ratio (elongated baseline) and compact bounding area
            float score = (w * w) / (h * (float) Math.sqrt(area));

            // Temporal progression alignment bonus if the user wrote multiple strokes/points along a direction
            if (hasProgression && progDist >= 18f) {
                float diff = Math.abs(normalizeAngleDiff(deg, progAngle));
                if (diff < 45f) {
                    score *= (1.0f + (1.0f - diff / 45f) * 0.4f);
                } else if (diff > 135f) {
                    // Suppress 180-degree inverted direction
                    score *= 0.5f;
                }
            }

            if (score > maxScore) {
                maxScore = score;
                bestAngle = deg;
                bestAspectRatio = ar;
            }
        }

        // 3. Fine-tuning pass in steps of 0.5° around bestAngle
        float refinedAngle = bestAngle;
        float fineMaxScore = maxScore;
        for (float deg = bestAngle - 2.0f; deg <= bestAngle + 2.0f; deg += 0.5f) {
            double rad = Math.toRadians(-deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

            for (DrawingPoint p : allPoints) {
                float dx = p.x - cx;
                float dy = p.y - cy;
                float rx = dx * cos - dy * sin;
                float ry = dx * sin + dy * cos;
                if (rx < minX) minX = rx;
                if (rx > maxX) maxX = rx;
                if (ry < minY) minY = ry;
                if (ry > maxY) maxY = ry;
            }

            float w = Math.max(1f, maxX - minX);
            float h = Math.max(1f, maxY - minY);
            float score = (w * w) / (h * (float) Math.sqrt(w * h));
            if (hasProgression && progDist >= 18f) {
                float diff = Math.abs(normalizeAngleDiff(deg, progAngle));
                if (diff < 45f) score *= (1.0f + (1.0f - diff / 45f) * 0.4f);
            }
            if (score > fineMaxScore) {
                fineMaxScore = score;
                refinedAngle = deg;
            }
        }

        // 4. Stability guards
        // If angle is close to horizontal (+/- 3.5°), snap to 0° to avoid jitter on normal horizontal writing.
        if (Math.abs(refinedAngle) < 3.5f) return 0f;

        // If angle is close to vertical (+/- 3.5° of +90° or -90°), snap to +/-90°
        if (Math.abs(refinedAngle - 90f) < 3.5f) return 90f;
        if (Math.abs(refinedAngle - (-90f)) < 3.5f) return -90f;

        // If stroke is approximately square (aspect ratio < 1.30) and has low progression distance,
        // it is a single upright character or symbol (e.g. "क", "म", "O", "A"). Do not rotate!
        if (bestAspectRatio < 1.30f && progDist < 25f) return 0f;

        return refinedAngle;
    }

    private static int countHorizontalStrokePairs(List<Stroke> strokes) {
        if (strokes == null || strokes.size() < 2) return 0;
        int hPairs = 0;
        int n = strokes.size();
        for (int i = 0; i < n; i++) {
            Stroke s1 = strokes.get(i);
            if (s1 == null) continue;
            RectF b1 = s1.getBounds();
            if (b1 == null || b1.isEmpty()) continue;
            float h1 = Math.max(1f, b1.height());
            for (int j = i + 1; j < n; j++) {
                Stroke s2 = strokes.get(j);
                if (s2 == null) continue;
                RectF b2 = s2.getBounds();
                if (b2 == null || b2.isEmpty()) continue;
                float h2 = Math.max(1f, b2.height());

                float vOverlap = Math.max(0f, Math.min(b1.bottom, b2.bottom) - Math.max(b1.top, b2.top));
                float minH = Math.min(h1, h2);
                if (vOverlap >= 0.35f * minH) {
                    float hDist = Math.abs(b1.centerX() - b2.centerX());
                    if (hDist >= 0.50f * minH) {
                        hPairs++;
                        if (hPairs >= 4) return hPairs;
                    }
                }
            }
        }
        return hPairs;
    }

    private static float normalizeAngleDiff(float a, float b) {
        float diff = a - b;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return diff;
    }

    public static boolean isIndicLanguage(String language) {
        return "MARATHI".equalsIgnoreCase(language) || "HINDI".equalsIgnoreCase(language);
    }

    public static NormalizedInk normalizeInk(List<Stroke> strokes) {
        return normalizeInk(strokes, null);
    }

    public static NormalizedInk normalizeInk(List<Stroke> strokes, String language) {
        if (strokes == null || strokes.isEmpty()) {
            return new NormalizedInk(strokes, new OrientationTransform(0f, 0f, 0f, 0f));
        }
        RectF bounds = getBounds(strokes);
        return new NormalizedInk(strokes, new OrientationTransform(0f, bounds.centerX(), bounds.centerY(), 0f));
    }

    public static class NormalizedInk {
        public final List<Stroke> strokes;
        public final OrientationTransform transform;

        public NormalizedInk(List<Stroke> strokes, OrientationTransform transform) {
            this.strokes = strokes;
            this.transform = transform;
        }
    }

    public static RectF getBounds(List<Stroke> strokes) {
        RectF r = new RectF();
        boolean found = false;
        for (Stroke s : strokes) {
            if (s != null && s.getBounds() != null && !s.getBounds().isEmpty()) {
                if (!found) {
                    r.set(s.getBounds());
                    found = true;
                } else {
                    r.union(s.getBounds());
                }
            }
        }
        return r;
    }
}
