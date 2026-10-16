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
    public static float detectWritingAngleDegrees(List<Stroke> strokes) {
        if (strokes == null || strokes.size() < 2) return 0f;

        float sumX = 0f, sumY = 0f;
        int pointCount = 0;
        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts == null) continue;
            for (DrawingPoint p : pts) {
                sumX += p.x;
                sumY += p.y;
                pointCount++;
            }
        }
        if (pointCount < 10) return 0f;

        float meanX = sumX / pointCount;
        float meanY = sumY / pointCount;

        // Compute covariance matrix elements for Principal Component Analysis (PCA)
        double covXX = 0.0, covXY = 0.0, covYY = 0.0;
        for (Stroke s : strokes) {
            if (s == null) continue;
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts == null) continue;
            for (DrawingPoint p : pts) {
                double dx = p.x - meanX;
                double dy = p.y - meanY;
                covXX += dx * dx;
                covXY += dx * dy;
                covYY += dy * dy;
            }
        }

        // Direction of maximal variance (first principal axis)
        double theta = 0.5 * Math.atan2(2.0 * covXY, covXX - covYY);
        float degrees = (float) Math.toDegrees(theta);

        // Normalize angle to [-90, +90]
        while (degrees > 90f) degrees -= 180f;
        while (degrees < -90f) degrees += 180f;

        // If handwriting is close to horizontal (within +/- 3.5 deg), keep 0 to avoid jitter
        if (Math.abs(degrees) < 3.5f) return 0f;

        return degrees;
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

        boolean isIndic = isIndicLanguage(language);
        // In Devanagari (Marathi/Hindi), characters have natural vertical stands (kana) and
        // diagonal matras. Cursive slant de-shearing MUST NOT be applied because it distorts
        // and severs matras and kana from their consonants!
        // Also, for short Indic words (<= 10 strokes or aspect ratio < 2.5), never apply angle rotation.
        float slantAngle = isIndic ? 0f : detectSlantAngleDegrees(strokes);
        float writingAngle = (isIndic && (strokes.size() <= 10 || bounds.width() < bounds.height() * 2.5f))
                ? 0f
                : detectWritingAngleDegrees(strokes);

        if (Math.abs(writingAngle) < 3.5f && Math.abs(slantAngle) < 10f) {
            return new NormalizedInk(strokes, new OrientationTransform(0f, pivotX, pivotY, 0f));
        }

        List<Stroke> normalizedStrokes = new ArrayList<>(strokes.size());
        double rotRad = Math.toRadians(-writingAngle);
        float cos = (float) Math.cos(rotRad);
        float sin = (float) Math.sin(rotRad);
        float shearFactor = (float) Math.tan(Math.toRadians(-slantAngle));

        for (Stroke s : strokes) {
            if (s == null) continue;
            Stroke clone = new Stroke(s.color, s.width, s.tool);
            List<DrawingPoint> pts = s.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = s.points;
            if (pts != null) {
                for (DrawingPoint p : pts) {
                    // Step 1: Rotate into horizontal frame
                    float dx = p.x - pivotX;
                    float dy = p.y - pivotY;
                    float rx = dx * cos - dy * sin + pivotX;
                    float ry = dx * sin + dy * cos + pivotY;

                    // Step 2: De-shear cursive slant relative to baseline
                    float sx = rx + (ry - pivotY) * shearFactor;

                    clone.addPoint(sx, ry, p.pressure, p.timeMillis);
                    clone.addRecognitionPoint(sx, ry, p.pressure, p.timeMillis);
                }
            }
            normalizedStrokes.add(clone);
        }

        OrientationTransform transform = new OrientationTransform(writingAngle, pivotX, pivotY, slantAngle);
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
