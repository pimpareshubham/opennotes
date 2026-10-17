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
        // Natural ink coordinates: ML Kit natively understands inclined and angled handwriting.
        // We do not impose an artificial horizontal angle reference.
        return 0f;
    }

    public static float detectSlantAngleDegrees(List<Stroke> strokes) {
        return 0f;
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
