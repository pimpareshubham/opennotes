package org.opennotes.app.ai;

import android.graphics.PointF;
import android.graphics.RectF;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Intelligent Geometric & Morphological Shape & Arrow Detector.
 * Automatically classifies hand-drawn arrows, circles/ovals, rectangles,
 * triangles, and straight divider lines, separating them from handwriting notes
 * so diagrams and annotations are not mangled or confused with text.
 */
public class HandwrittenShapeDetector {

    public static class DetectionResult {
        public final List<ShapeItem> shapes;
        public final List<Stroke> remainingHandwritingStrokes;

        public DetectionResult(List<ShapeItem> shapes, List<Stroke> remainingHandwritingStrokes) {
            this.shapes = shapes != null ? shapes : Collections.emptyList();
            this.remainingHandwritingStrokes = remainingHandwritingStrokes != null
                    ? remainingHandwritingStrokes
                    : Collections.emptyList();
        }

        public boolean hasShapes() {
            return !shapes.isEmpty();
        }
    }

    /**
     * Inspects a collection of strokes, isolates shape & arrow candidates,
     * and separates them from genuine handwriting strokes.
     */
    public static DetectionResult detectAndSeparateShapes(List<Stroke> inputStrokes) {
        if (inputStrokes == null || inputStrokes.isEmpty()) {
            return new DetectionResult(Collections.emptyList(), inputStrokes);
        }

        List<ShapeItem> detectedShapes = new ArrayList<>();
        Set<Stroke> consumedStrokes = new HashSet<>();

        // 1. Check for 2-stroke arrows (stem stroke + V-head stroke)
        if (inputStrokes.size() >= 2) {
            detectTwoStrokeArrows(inputStrokes, detectedShapes, consumedStrokes);
        }

        // 2. Check each remaining stroke for single-stroke geometric shapes
        for (Stroke stroke : inputStrokes) {
            if (consumedStrokes.contains(stroke) || stroke == null) continue;

            List<DrawingPoint> pts = stroke.getRecognitionPoints();
            if (pts == null || pts.isEmpty()) pts = stroke.points;
            if (pts == null || pts.size() < 5) continue;

            // A: Single-stroke Arrow
            ShapeItem arrow = detectSingleStrokeArrow(stroke, pts);
            if (arrow != null) {
                detectedShapes.add(arrow);
                consumedStrokes.add(stroke);
                continue;
            }

            // B: Circle / Oval / Ellipse
            ShapeItem circle = detectCircleOrOval(stroke, pts);
            if (circle != null) {
                detectedShapes.add(circle);
                consumedStrokes.add(stroke);
                continue;
            }

            // C: Rectangle / Box
            ShapeItem rect = detectRectangle(stroke, pts);
            if (rect != null) {
                detectedShapes.add(rect);
                consumedStrokes.add(stroke);
                continue;
            }

            // D: Triangle
            ShapeItem triangle = detectTriangle(stroke, pts);
            if (triangle != null) {
                detectedShapes.add(triangle);
                consumedStrokes.add(stroke);
                continue;
            }

            // E: Isolated Straight Rule / Divider Line
            ShapeItem line = detectStraightLine(stroke, pts);
            if (line != null) {
                detectedShapes.add(line);
                consumedStrokes.add(stroke);
                continue;
            }
        }

        List<Stroke> remaining = new ArrayList<>();
        for (Stroke s : inputStrokes) {
            if (!consumedStrokes.contains(s)) {
                remaining.add(s);
            }
        }

        return new DetectionResult(detectedShapes, remaining);
    }

    // =========================================================================
    // 1. ARROW DETECTION
    // =========================================================================

    private static void detectTwoStrokeArrows(List<Stroke> strokes,
                                             List<ShapeItem> detectedShapes,
                                             Set<Stroke> consumedStrokes) {
        for (int i = 0; i < strokes.size(); i++) {
            Stroke s1 = strokes.get(i);
            if (consumedStrokes.contains(s1) || s1 == null) continue;
            List<DrawingPoint> pts1 = s1.getRecognitionPoints();
            if (pts1 == null || pts1.isEmpty()) pts1 = s1.points;
            if (pts1 == null || pts1.size() < 4) continue;

            // Check if s1 is a straight stem
            DrawingPoint start1 = pts1.get(0);
            DrawingPoint end1 = pts1.get(pts1.size() - 1);
            float stemLength = distance(start1.x, start1.y, end1.x, end1.y);
            if (stemLength < 35f) continue;

            float maxStemDev = maxDeviationFromLine(pts1, start1.x, start1.y, end1.x, end1.y);
            if (maxStemDev > stemLength * 0.16f) continue; // Not a straight stem

            // Check remaining strokes for a V-shaped arrowhead near end1 or start1
            for (int j = 0; j < strokes.size(); j++) {
                if (i == j) continue;
                Stroke s2 = strokes.get(j);
                if (consumedStrokes.contains(s2) || s2 == null) continue;
                List<DrawingPoint> pts2 = s2.getRecognitionPoints();
                if (pts2 == null || pts2.isEmpty()) pts2 = s2.points;
                if (pts2 == null || pts2.size() < 3 || pts2.size() > 25) continue;

                // Check V-head near end1 (pointing forward)
                if (isArrowHeadNear(pts2, end1.x, end1.y, start1.x, start1.y, stemLength)) {
                    detectedShapes.add(new ShapeItem(ShapeItem.Type.ARROW,
                            start1.x, start1.y, end1.x, end1.y, s1.color, s1.width));
                    consumedStrokes.add(s1);
                    consumedStrokes.add(s2);
                    break;
                }
                // Check V-head near start1 (pointing backwards)
                else if (isArrowHeadNear(pts2, start1.x, start1.y, end1.x, end1.y, stemLength)) {
                    detectedShapes.add(new ShapeItem(ShapeItem.Type.ARROW,
                            end1.x, end1.y, start1.x, start1.y, s1.color, s1.width));
                    consumedStrokes.add(s1);
                    consumedStrokes.add(s2);
                    break;
                }
            }
        }
    }

    private static boolean isArrowHeadNear(List<DrawingPoint> vPts,
                                           float tipX, float tipY,
                                           float tailX, float tailY,
                                           float stemLength) {
        float maxTolerance = Math.max(25f, stemLength * 0.25f);
        // At least one point of the V must be very close to the tip
        boolean hasNearPoint = false;
        for (DrawingPoint p : vPts) {
            if (distance(p.x, p.y, tipX, tipY) <= maxTolerance) {
                hasNearPoint = true;
                break;
            }
        }
        if (!hasNearPoint) return false;

        // An arrowhead has 2 distinct arms meeting at an acute angle
        DrawingPoint vStart = vPts.get(0);
        DrawingPoint vEnd = vPts.get(vPts.size() - 1);
        DrawingPoint vApex = findFarthestPointFromChord(vPts, vStart, vEnd);
        if (vApex == null) return false;

        float arm1 = distance(vStart.x, vStart.y, vApex.x, vApex.y);
        float arm2 = distance(vEnd.x, vEnd.y, vApex.x, vApex.y);
        if (arm1 < 6f || arm2 < 6f || arm1 > stemLength * 0.75f || arm2 > stemLength * 0.75f) {
            return false;
        }

        // Angle at apex should be between 20° and 110°
        double angleDeg = computeAngleDegrees(vStart.x, vStart.y, vApex.x, vApex.y, vEnd.x, vEnd.y);
        return angleDeg >= 18.0 && angleDeg <= 115.0;
    }

    private static ShapeItem detectSingleStrokeArrow(Stroke stroke, List<DrawingPoint> pts) {
        if (pts.size() < 10) return null;
        DrawingPoint start = pts.get(0);
        DrawingPoint end = pts.get(pts.size() - 1);
        float pathLen = pathLength(pts);
        if (pathLen < 50f) return null;

        int maxDistIdx = 0;
        float maxDist = 0f;
        for (int i = 0; i < pts.size(); i++) {
            float d = distance(start.x, start.y, pts.get(i).x, pts.get(i).y);
            if (d > maxDist) {
                maxDist = d;
                maxDistIdx = i;
            }
        }

        float ratio = (float) maxDistIdx / (float) pts.size();
        if (ratio >= 0.65f && ratio <= 0.92f && maxDist >= 40f) {
            DrawingPoint tip = pts.get(maxDistIdx);
            List<DrawingPoint> stemPts = pts.subList(0, maxDistIdx + 1);
            float stemDev = maxDeviationFromLine(stemPts, start.x, start.y, tip.x, tip.y);
            if (stemDev <= maxDist * 0.20f) {
                DrawingPoint barbEnd = end;
                double angleDeg = computeAngleDegrees(start.x, start.y, tip.x, tip.y, barbEnd.x, barbEnd.y);
                if (angleDeg <= 60.0) {
                    return new ShapeItem(ShapeItem.Type.ARROW,
                            start.x, start.y, tip.x, tip.y, stroke.color, stroke.width);
                }
            }
        }
        return null;
    }

    // =========================================================================
    // 2. CIRCLE & OVAL DETECTION
    // =========================================================================

    private static ShapeItem detectCircleOrOval(Stroke stroke, List<DrawingPoint> pts) {
        if (pts.size() < 8) return null;
        DrawingPoint start = pts.get(0);
        DrawingPoint end = pts.get(pts.size() - 1);
        float perimeter = pathLength(pts);
        if (perimeter < 70f) return null;

        float gap = distance(start.x, start.y, end.x, end.y);
        if (gap > perimeter * 0.26f) return null;

        RectF bounds = stroke.getBounds();
        if (bounds == null || bounds.width() < 25f || bounds.height() < 25f) return null;

        float aspect = bounds.width() / bounds.height();
        if (aspect < 0.35f || aspect > 2.8f) return null;

        float polygonArea = Math.abs(computePolygonArea(pts));
        float isoperimetric = (float) (4.0 * Math.PI * polygonArea / (perimeter * perimeter));

        if (isoperimetric >= 0.52f) {
            return new ShapeItem(ShapeItem.Type.CIRCLE,
                    bounds.left, bounds.top, bounds.right, bounds.bottom, stroke.color, stroke.width);
        }

        return null;
    }

    // =========================================================================
    // 3. RECTANGLE DETECTION
    // =========================================================================

    private static ShapeItem detectRectangle(Stroke stroke, List<DrawingPoint> pts) {
        if (pts.size() < 10) return null;
        DrawingPoint start = pts.get(0);
        DrawingPoint end = pts.get(pts.size() - 1);
        float perimeter = pathLength(pts);
        if (perimeter < 90f) return null;

        float gap = distance(start.x, start.y, end.x, end.y);
        if (gap > perimeter * 0.28f) return null;

        RectF bounds = stroke.getBounds();
        if (bounds == null || bounds.width() < 30f || bounds.height() < 30f) return null;

        float boxArea = bounds.width() * bounds.height();
        float polygonArea = Math.abs(computePolygonArea(pts));
        float fillRatio = polygonArea / boxArea;

        if (fillRatio >= 0.72f && fillRatio <= 1.05f) {
            int corners = countCorners(pts, 60.0, 125.0);
            if (corners >= 3 && corners <= 5) {
                return new ShapeItem(ShapeItem.Type.RECTANGLE,
                        bounds.left, bounds.top, bounds.right, bounds.bottom, stroke.color, stroke.width);
            }
        }
        return null;
    }

    // =========================================================================
    // 4. TRIANGLE DETECTION
    // =========================================================================

    private static ShapeItem detectTriangle(Stroke stroke, List<DrawingPoint> pts) {
        if (pts.size() < 8) return null;
        float perimeter = pathLength(pts);
        if (perimeter < 75f) return null;

        float gap = distance(pts.get(0).x, pts.get(0).y, pts.get(pts.size() - 1).x, pts.get(pts.size() - 1).y);
        if (gap > perimeter * 0.30f) return null;

        RectF bounds = stroke.getBounds();
        if (bounds == null || bounds.width() < 25f || bounds.height() < 25f) return null;

        float boxArea = bounds.width() * bounds.height();
        float polygonArea = Math.abs(computePolygonArea(pts));
        float fillRatio = polygonArea / boxArea;

        if (fillRatio >= 0.32f && fillRatio <= 0.68f) {
            int corners = countCorners(pts, 25.0, 95.0);
            if (corners == 3) {
                return new ShapeItem(ShapeItem.Type.TRIANGLE,
                        bounds.left, bounds.top, bounds.right, bounds.bottom, stroke.color, stroke.width);
            }
        }
        return null;
    }

    // =========================================================================
    // 5. STRAIGHT LINE / UNDERLINE DETECTION
    // =========================================================================

    private static ShapeItem detectStraightLine(Stroke stroke, List<DrawingPoint> pts) {
        if (pts.size() < 5) return null;
        DrawingPoint start = pts.get(0);
        DrawingPoint end = pts.get(pts.size() - 1);
        float directDist = distance(start.x, start.y, end.x, end.y);
        if (directDist < 60f) return null;

        float pathLen = pathLength(pts);
        if (pathLen > directDist * 1.10f) return null;

        float maxDev = maxDeviationFromLine(pts, start.x, start.y, end.x, end.y);
        if (maxDev <= Math.max(8f, directDist * 0.05f)) {
            return new ShapeItem(ShapeItem.Type.LINE,
                    start.x, start.y, end.x, end.y, stroke.color, stroke.width);
        }
        return null;
    }

    // =========================================================================
    // GEOMETRIC UTILITIES
    // =========================================================================

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.hypot(dx, dy);
    }

    private static float pathLength(List<DrawingPoint> pts) {
        float len = 0f;
        for (int i = 1; i < pts.size(); i++) {
            len += distance(pts.get(i - 1).x, pts.get(i - 1).y, pts.get(i).x, pts.get(i).y);
        }
        return len;
    }

    private static float maxDeviationFromLine(List<DrawingPoint> pts,
                                              float x1, float y1, float x2, float y2) {
        float dist = distance(x1, y1, x2, y2);
        if (dist < 0.001f) return 0f;
        float maxDev = 0f;
        for (DrawingPoint p : pts) {
            float dev = Math.abs((y2 - y1) * p.x - (x2 - x1) * p.y + x2 * y1 - y2 * x1) / dist;
            maxDev = Math.max(maxDev, dev);
        }
        return maxDev;
    }

    private static DrawingPoint findFarthestPointFromChord(List<DrawingPoint> pts,
                                                           DrawingPoint chordStart,
                                                           DrawingPoint chordEnd) {
        float maxDist = -1f;
        DrawingPoint farthest = null;
        float chordLen = distance(chordStart.x, chordStart.y, chordEnd.x, chordEnd.y);
        if (chordLen < 0.001f) return null;

        for (DrawingPoint p : pts) {
            float d = Math.abs((chordEnd.y - chordStart.y) * p.x
                    - (chordEnd.x - chordStart.x) * p.y
                    + chordEnd.x * chordStart.y - chordEnd.y * chordStart.x) / chordLen;
            if (d > maxDist) {
                maxDist = d;
                farthest = p;
            }
        }
        return farthest;
    }

    private static double computeAngleDegrees(float ax, float ay, float bx, float by, float cx, float cy) {
        double v1x = ax - bx;
        double v1y = ay - by;
        double v2x = cx - bx;
        double v2y = cy - by;
        double dot = v1x * v2x + v1y * v2y;
        double mag1 = Math.hypot(v1x, v1y);
        double mag2 = Math.hypot(v2x, v2y);
        if (mag1 < 0.001 || mag2 < 0.001) return 0.0;
        double cosVal = Math.max(-1.0, Math.min(1.0, dot / (mag1 * mag2)));
        return Math.toDegrees(Math.acos(cosVal));
    }

    private static float computePolygonArea(List<DrawingPoint> pts) {
        float area = 0f;
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            DrawingPoint p1 = pts.get(i);
            DrawingPoint p2 = pts.get((i + 1) % n);
            area += (p1.x * p2.y - p2.x * p1.y);
        }
        return area / 2f;
    }

    private static int countCorners(List<DrawingPoint> pts, double minDeg, double maxDeg) {
        if (pts.size() < 7) return 0;
        int step = Math.max(2, pts.size() / 12);
        int corners = 0;
        int lastCornerIdx = -999;
        for (int i = step; i < pts.size() - step; i += step) {
            DrawingPoint prev = pts.get(i - step);
            DrawingPoint curr = pts.get(i);
            DrawingPoint next = pts.get(i + step);
            double angle = computeAngleDegrees(prev.x, prev.y, curr.x, curr.y, next.x, next.y);
            if (angle >= minDeg && angle <= maxDeg && (i - lastCornerIdx) > step * 2) {
                corners++;
                lastCornerIdx = i;
            }
        }
        return corners;
    }
}
