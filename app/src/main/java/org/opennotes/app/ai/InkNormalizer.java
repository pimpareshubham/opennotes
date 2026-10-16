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

        // 1. Calculate baseline 0° metrics first
        RectF b0 = getBounds(strokes);
        float w0 = Math.max(1f, b0.width());
        float h0 = Math.max(1f, b0.height());
        float ar0 = w0 / h0;
        float score0 = (w0 * w0) / (h0 * (float) Math.sqrt(w0 * h0));

        // If stroke cluster is approximately square (aspect ratio < 1.30),
        // it is a single upright character or symbol (e.g. "क", "म", "O", "A"). Do not rotate!
        if (ar0 < 1.30f) return 0f;

        // 2. Minimum Area Bounding Box / Aspect Ratio Sweep across angles [-80°, +80°]
        // Text lines have maximum width-to-height aspect ratio along the baseline!
        float bestAngle = 0f;
        float maxScore = score0;

        for (float deg = -80f; deg <= 80f; deg += 2.5f) {
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

            if (score > maxScore) {
                maxScore = score;
                bestAngle = deg;
            }
        }

        // 3. Fine-tuning pass in steps of 0.5° around bestAngle
        float refinedAngle = bestAngle;
        float fineMaxScore = maxScore;
        for (float deg = bestAngle - 2.5f; deg <= bestAngle + 2.5f; deg += 0.5f) {
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
            if (score > fineMaxScore) {
                fineMaxScore = score;
                refinedAngle = deg;
            }
        }

        // 4. Stability guards
        // Snap small angles within [-6°, +6°] to 0° to avoid jitter on normal horizontal writing
        if (Math.abs(refinedAngle) <= 6.0f) return 0f;

        // Strong horizontal anchor: If 0° is already elongated (ar0 >= 1.40),
        // rotated score must be substantially higher (> 1.35x) to justify rotation.
        if (ar0 >= 1.40f && fineMaxScore < score0 * 1.35f) return 0f;

        return refinedAngle;
    }

    /**
     * Estimates cursive slant of downstrokes (e.g. forward leaning cursive in careless/doctor handwriting).
     */
    public static float detectSlantAngleDegrees(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) return 0f;

        List<Float> downstrokeAngles = new ArrayList<>();
        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts == null || pts.size() < 4) continue;

            for (int i = 1; i < pts.size(); i++) {
                DrawingPoint p0 = pts.get(i - 1);
                DrawingPoint p1 = pts.get(i);
                float dy = p1.y - p0.y;
                float dx = p1.x - p0.x;
                // Only consider substantial downward strokes (dy > 4px)
                if (dy > 4f) {
                    double angleRad = Math.atan2(dx, dy); // 0 is straight down, positive is leaning right
                    float deg = (float) Math.toDegrees(angleRad);
                    if (deg > -45f && deg < 65f) {
                        downstrokeAngles.add(deg);
                    }
                }
            }
        }

        if (downstrokeAngles.size() < 6) return 0f;

        // Calculate median slant
        downstrokeAngles.sort(Float::compare);
        float medianSlant = downstrokeAngles.get(downstrokeAngles.size() / 2);

        // Typical upright writing is within 0 to 8 degrees; only de-slant if noticeably tilted
        if (Math.abs(medianSlant) < 10f) return 0f;

        return Math.max(-40f, Math.min(50f, medianSlant));
    }

    public static boolean isIndicLanguage(String language) {
        return "MARATHI".equalsIgnoreCase(language) || "HINDI".equalsIgnoreCase(language);
    }

    /**
     * Normalizes a collection of strokes:
     * 1. Rotates to canonical horizontal alignment if written at an angle.
     * 2. De-shears cursive slant so letters become upright (for Latin/cursive scripts only).
     */
    public static NormalizedInk normalizeInk(List<Stroke> strokes) {
        return normalizeInk(strokes, null);
    }

    public static NormalizedInk normalizeInk(List<Stroke> strokes, String language) {
        if (strokes == null || strokes.isEmpty()) {
            return new NormalizedInk(strokes, new OrientationTransform(0f, 0f, 0f, 0f));
        }

        RectF bounds = getBounds(strokes);
        float pivotX = bounds.centerX();
        float pivotY = bounds.centerY();

        // ML Kit's digital ink neural network is trained on natural human handwriting
        // (including both cursive and printed scripts). Artificial shearing deforms letter
        // shapes and harms recognition. We only apply rigid rotation to level sloped baselines.
        float writingAngle = detectWritingAngleDegrees(strokes);

        if (Math.abs(writingAngle) < 6.0f) {
            return new NormalizedInk(strokes, new OrientationTransform(0f, pivotX, pivotY, 0f));
        }

        List<Stroke> normalizedStrokes = new ArrayList<>(strokes.size());
        double rotRad = Math.toRadians(-writingAngle);
        float cos = (float) Math.cos(rotRad);
        float sin = (float) Math.sin(rotRad);

        for (Stroke s : strokes) {
            if (s == null) continue;
            Stroke clone = new Stroke(s.color, s.width, s.tool);
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts != null) {
                for (DrawingPoint p : pts) {
                    // Rotate rigidly into horizontal frame without shearing
                    float dx = p.x - pivotX;
                    float dy = p.y - pivotY;
                    float rx = dx * cos - dy * sin + pivotX;
                    float ry = dx * sin + dy * cos + pivotY;

                    clone.addPoint(rx, ry, p.pressure, p.timeMillis);
                    clone.addRecognitionPoint(rx, ry, p.pressure, p.timeMillis);
                }
            }
            normalizedStrokes.add(clone);
        }

        OrientationTransform transform = new OrientationTransform(writingAngle, pivotX, pivotY, 0f);
        return new NormalizedInk(normalizedStrokes, transform);
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
