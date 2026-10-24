package org.opennotes.app.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Stroke {
    public List<DrawingPoint> points;
    /** Original touch samples used for recognition before visual smoothing/snapping. */
    public List<DrawingPoint> recognitionPoints;
    public int color;
    public float width;
    public DrawingTool tool;
    /** Paint brush texture captured when this stroke was drawn. */
    public int brushTextureStyle = 0;
    /** Captured fiber density multiplier, from 1x through 10x. */
    public int brushFiberDensity = 10;

    public enum LineStyle {
        SOLID,
        DASH,
        DOT
    }
    public LineStyle lineStyle = LineStyle.SOLID;

    public Stroke(int color, float width, DrawingTool tool) {
        this.points = new ArrayList<>();
        this.recognitionPoints = new ArrayList<>();
        this.color = color;
        this.width = width;
        this.tool = tool;
        this.lineStyle = LineStyle.SOLID;
    }

    private transient android.graphics.Path cachedPath;
    private transient android.graphics.RectF cachedBounds;
    private transient boolean pathDirty = true;
    private transient boolean boundsDirty = true;
    private transient android.graphics.Path cachedPressurePath;
    private transient android.graphics.Path cachedBrushHighlightStrands;
    private transient android.graphics.Path cachedBrushShadeStrands;
    private transient android.graphics.Path cachedBrushEdgeFibers;
    private transient android.graphics.Path cachedBrushMainFibers;
    private transient android.graphics.Path cachedBrushTailFibers;
    private transient android.graphics.Path cachedBrushTailHighlights;
    private transient android.graphics.Path cachedBrushTailShades;
    private transient boolean pressurePathDirty = true;
    private transient float pressurePathSensitivity = Float.NaN;

    public void markDirty() {
        pathDirty = true;
        boundsDirty = true;
        cachedPath = null;
        cachedBounds = null;
        pressurePathDirty = true;
        cachedPressurePath = null;
        if (cachedBrushHighlightStrands != null) cachedBrushHighlightStrands.reset();
        if (cachedBrushShadeStrands != null) cachedBrushShadeStrands.reset();
        if (cachedBrushEdgeFibers != null) cachedBrushEdgeFibers.reset();
        if (cachedBrushMainFibers != null) cachedBrushMainFibers.reset();
        if (cachedBrushTailFibers != null) cachedBrushTailFibers.reset();
        if (cachedBrushTailHighlights != null) cachedBrushTailHighlights.reset();
        if (cachedBrushTailShades != null) cachedBrushTailShades.reset();
    }

    public android.graphics.RectF getBounds() {
        if (cachedBounds == null || boundsDirty) {
            computeBounds();
        }
        return cachedBounds;
    }

    public android.graphics.Path getPath() {
        if (cachedPath == null || pathDirty) {
            computePath();
        }
        return cachedPath;
    }

    /** Cached filled outline for pressure-sensitive fountain and brush strokes. */
    public android.graphics.Path getPressurePath(float sensitivity) {
        if (cachedPressurePath == null || pressurePathDirty
                || Float.compare(pressurePathSensitivity, sensitivity) != 0) {
            buildPressurePath(sensitivity);
        }
        return cachedPressurePath;
    }

    public android.graphics.Path getBrushHighlightStrands() { return cachedBrushHighlightStrands; }
    public android.graphics.Path getBrushShadeStrands() { return cachedBrushShadeStrands; }
    public android.graphics.Path getBrushEdgeFibers() { return cachedBrushEdgeFibers; }
    public android.graphics.Path getBrushMainFibers() { return cachedBrushMainFibers; }
    public android.graphics.Path getBrushTailFibers() { return cachedBrushTailFibers; }
    public android.graphics.Path getBrushTailHighlights() { return cachedBrushTailHighlights; }
    public android.graphics.Path getBrushTailShades() { return cachedBrushTailShades; }

    private void buildPressurePath(float sensitivity) {
        android.graphics.Path path = new android.graphics.Path();
        path.setFillType(android.graphics.Path.FillType.WINDING);
        pressurePathSensitivity = sensitivity;
        if (points == null || points.isEmpty()) {
            cachedPressurePath = path;
            pressurePathDirty = false;
            return;
        }

        // 1. Single tap (period or dot)
        if (points.size() == 1) {
            DrawingPoint p0 = points.get(0);
            float w0 = getPressureWidth(p0.pressure, sensitivity) * 0.35f;
            float singleRadius = Math.max(0.8f, w0 * 0.5f);
            path.addCircle(p0.x, p0.y, singleRadius, android.graphics.Path.Direction.CW);
            if (tool == DrawingTool.BRUSH) buildBrushDabFibers(p0, singleRadius);
            cachedPressurePath = path;
            pressurePathDirty = false;
            return;
        }

        // 2. Filter out consecutive duplicate / ultra-close samples (< 0.8px)
        // to avoid numerical instability on tangents and normal vectors.
        List<DrawingPoint> filtered = new ArrayList<>(points.size());
        filtered.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            DrawingPoint pt = points.get(i);
            DrawingPoint prev = filtered.get(filtered.size() - 1);
            float dx = pt.x - prev.x;
            float dy = pt.y - prev.y;
            if (dx * dx + dy * dy >= 0.64f || i == points.size() - 1) {
                filtered.add(pt);
            }
        }

        int count = filtered.size();
        if (count == 1) {
            DrawingPoint p0 = filtered.get(0);
            float w0 = getPressureWidth(p0.pressure, sensitivity) * 0.35f;
            float singleRadius = Math.max(0.8f, w0 * 0.5f);
            path.addCircle(p0.x, p0.y, singleRadius, android.graphics.Path.Direction.CW);
            if (tool == DrawingTool.BRUSH) buildBrushDabFibers(p0, singleRadius);
            cachedPressurePath = path;
            pressurePathDirty = false;
            return;
        }

        // Calm small sample-to-sample direction changes before building the fountain nib or paint outline.
        // Recognition keeps the original samples; this only improves how the ink is rendered.
        if ((tool == DrawingTool.FOUNTAIN || tool == DrawingTool.BRUSH) && count > 3) {
            List<DrawingPoint> smoothed = new ArrayList<>(count);
            smoothed.add(filtered.get(0));
            float neighborWeight = tool == DrawingTool.BRUSH ? 0.25f : 0.18f;
            float centerWeight = 1f - neighborWeight * 2f;
            for (int i = 1; i < count - 1; i++) {
                DrawingPoint before = filtered.get(i - 1);
                DrawingPoint point = filtered.get(i);
                DrawingPoint after = filtered.get(i + 1);
                smoothed.add(new DrawingPoint(
                        before.x * neighborWeight + point.x * centerWeight + after.x * neighborWeight,
                        before.y * neighborWeight + point.y * centerWeight + after.y * neighborWeight,
                        before.pressure * neighborWeight + point.pressure * centerWeight + after.pressure * neighborWeight,
                        point.timeMillis));
            }
            smoothed.add(filtered.get(count - 1));
            filtered = smoothed;
            count = filtered.size();
        }

        // 3. Compute continuous base widths with pressure
        float[] widths = new float[count];
        for (int i = 0; i < count; i++) {
            widths[i] = getPressureWidth(filtered.get(i).pressure, sensitivity);
        }

        // Smooth pressure noise across a short spatial window so the nib does not pulse between samples.
        if (count > 2) {
            float[] smoothedWidths = new float[count];
            final float[] widthWeights = {1f, 2f, 3f, 2f, 1f};
            for (int i = 0; i < count; i++) {
                float weightedWidth = 0f;
                float weightTotal = 0f;
                for (int offset = -2; offset <= 2; offset++) {
                    int sampleIndex = Math.max(0, Math.min(count - 1, i + offset));
                    float weight = widthWeights[offset + 2];
                    weightedWidth += widths[sampleIndex] * weight;
                    weightTotal += weight;
                }
                smoothedWidths[i] = weightedWidth / weightTotal;
            }
            System.arraycopy(smoothedWidths, 0, widths, 0, count);
        }

        // Taper by traveled distance, not sample index, so tip shape stays consistent at 60/120 Hz.
        if (count >= 3) {
            float totalLength = 0f;
            for (int i = 1; i < count; i++) {
                DrawingPoint before = filtered.get(i - 1);
                DrawingPoint after = filtered.get(i);
                totalLength += (float) Math.hypot(after.x - before.x, after.y - before.y);
            }
            float taperLength = Math.min(totalLength * 0.30f, Math.max(3f, width * 1.5f));
            if (taperLength > 0f) {
                float traveled = 0f;
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        DrawingPoint before = filtered.get(i - 1);
                        DrawingPoint point = filtered.get(i);
                        traveled += (float) Math.hypot(point.x - before.x, point.y - before.y);
                    }
                    float fromStart = smoothStep(Math.min(1f, traveled / taperLength));
                    float endTaperLength = tool == DrawingTool.BRUSH
                            ? Math.min(totalLength * 0.42f, Math.max(4f, width * 2f))
                            : taperLength;
                    float fromEnd = smoothStep(Math.min(1f, (totalLength - traveled) / endTaperLength));
                    if (tool == DrawingTool.BRUSH) {
                        // A loaded brush begins broad; lifting pressure narrows the painted body into a tail.
                        fromStart = 0.78f + fromStart * 0.22f;
                        // Preserve a broad release edge so the trailing bristles fan out gradually,
                        // rather than collapsing into a pinched knot before their tails begin.
                        fromEnd = 0.22f + fromEnd * 0.78f;
                    }
                    widths[i] *= Math.min(fromStart, fromEnd);
                }
            }
        }

        // 5. Calligraphic Nib Angle Modulation for FOUNTAIN tool
        // Fountain pen nib held at ~45°: downstrokes expand, cross-strokes are crisp & fine
        if (tool == DrawingTool.FOUNTAIN) {
            for (int i = 0; i < count; i++) {
                int tangentStart = Math.max(0, i - 2);
                int tangentEnd = Math.min(count - 1, i + 2);
                float tx = filtered.get(tangentEnd).x - filtered.get(tangentStart).x;
                float ty = filtered.get(tangentEnd).y - filtered.get(tangentStart).y;
                float len = (float) Math.hypot(tx, ty);
                if (len > 0.0001f) {
                    tx /= len;
                    ty /= len;
                    float modulation = 0.72f + 0.52f * Math.abs(0.7071f * tx - 0.7071f * ty);
                    widths[i] *= modulation;
                }
            }
        }

        // Keep the variable-width ribbon from folding back over itself at sharp reversals.
        if (tool == DrawingTool.FOUNTAIN && count > 2) {
            for (int i = 1; i < count - 1; i++) {
                DrawingPoint before = filtered.get(i - 1);
                DrawingPoint point = filtered.get(i);
                DrawingPoint after = filtered.get(i + 1);
                float inX = point.x - before.x;
                float inY = point.y - before.y;
                float outX = after.x - point.x;
                float outY = after.y - point.y;
                float inLength = (float) Math.hypot(inX, inY);
                float outLength = (float) Math.hypot(outX, outY);
                if (inLength > 0.001f && outLength > 0.001f) {
                    float directionDot = (inX * outX + inY * outY) / (inLength * outLength);
                    if (directionDot < -0.55f) {
                        float cornerWidthLimit = Math.max(1.2f, Math.min(inLength, outLength) * 0.55f);
                        widths[i] = Math.min(widths[i], cornerWidthLimit);
                    }
                }
            }
        }

        // 6. Compute Left and Right Ribbon Boundaries
        float[] leftX = new float[count];
        float[] leftY = new float[count];
        float[] rightX = new float[count];
        float[] rightY = new float[count];
        float[] tanX = new float[count];
        float[] tanY = new float[count];

        for (int i = 0; i < count; i++) {
            int tangentStart = Math.max(0, i - 2);
            int tangentEnd = Math.min(count - 1, i + 2);
            float tx = filtered.get(tangentEnd).x - filtered.get(tangentStart).x;
            float ty = filtered.get(tangentEnd).y - filtered.get(tangentStart).y;
            float dist = (float) Math.hypot(tx, ty);
            if (dist < 0.0001f) {
                tx = 1f;
                ty = 0f;
            } else {
                tx /= dist;
                ty /= dist;
            }
            tanX[i] = tx;
            tanY[i] = ty;

            float normX = -ty;
            float normY = tx;
            float halfW = Math.max(0.6f, widths[i] * 0.5f);

            DrawingPoint pt = filtered.get(i);
            leftX[i] = pt.x + normX * halfW;
            leftY[i] = pt.y + normY * halfW;
            rightX[i] = pt.x - normX * halfW;
            rightY[i] = pt.y - normY * halfW;
        }

        buildBrushTexturePaths(filtered, widths, tanX, tanY, count);

        // 7. Construct Single Continuous Smooth Bezier Envelope
        // No circles inside the stroke! No segmented joints!
        path.moveTo(leftX[0], leftY[0]);

        // Left Boundary Spine (Forward Quadratic Beziers)
        for (int i = 0; i < count - 1; i++) {
            float midX = (leftX[i] + leftX[i + 1]) * 0.5f;
            float midY = (leftY[i] + leftY[i + 1]) * 0.5f;
            path.quadTo(leftX[i], leftY[i], midX, midY);
        }
        path.lineTo(leftX[count - 1], leftY[count - 1]);

        // End Cap (Smooth Tapered Tip from Left to Right at Stroke End)
        DrawingPoint lastPt = filtered.get(count - 1);
        float endTipRadius = Math.max(0.6f, widths[count - 1] * 0.5f);
        float endTipX = lastPt.x + tanX[count - 1] * endTipRadius;
        float endTipY = lastPt.y + tanY[count - 1] * endTipRadius;
        path.quadTo(endTipX, endTipY, rightX[count - 1], rightY[count - 1]);

        // Right Boundary Spine (Backward Quadratic Beziers)
        for (int i = count - 1; i > 0; i--) {
            float midX = (rightX[i] + rightX[i - 1]) * 0.5f;
            float midY = (rightY[i] + rightY[i - 1]) * 0.5f;
            path.quadTo(rightX[i], rightY[i], midX, midY);
        }
        path.lineTo(rightX[0], rightY[0]);

        // Start Cap (Smooth Tapered Tip or Natural Flat-Bristle Landing)
        DrawingPoint firstPt = filtered.get(0);
        float startTipRadius = Math.max(0.6f, widths[0] * 0.5f);
        float startTipX = firstPt.x - tanX[0] * startTipRadius;
        float startTipY = firstPt.y - tanY[0] * startTipRadius;
        if (tool == DrawingTool.BRUSH) {
            // Natural flat-bristle touch down: staggered bristle notches across the start width
            int bristleSteps = Math.max(6, Math.min(16, (int) (widths[0] / 2.2f)));
            float prevX = rightX[0];
            float prevY = rightY[0];
            for (int s = 1; s <= bristleSteps; s++) {
                float frac = (float) s / bristleSteps;
                float targetX = rightX[0] + (leftX[0] - rightX[0]) * frac;
                float targetY = rightY[0] + (leftY[0] - rightY[0]) * frac;
                float stagger = (0.25f + 0.75f * ((edgeNoise(s, firstPt.x, firstPt.y, 443) + 1f) * 0.5f))
                        * Math.min(widths[0] * 0.22f, 3.2f);
                float notchX = (prevX + targetX) * 0.5f - tanX[0] * stagger;
                float notchY = (prevY + targetY) * 0.5f - tanY[0] * stagger;
                path.lineTo(notchX, notchY);
                path.lineTo(targetX, targetY);
                prevX = targetX;
                prevY = targetY;
            }
        } else {
            path.quadTo(startTipX, startTipY, leftX[0], leftY[0]);
        }

        path.close();

        cachedPressurePath = path;
        pressurePathDirty = false;
    }

    private static float edgeNoise(int index, float x, float y, int salt) {
        int hash = Float.floatToIntBits(x * 0.071f + y * 0.037f);
        hash ^= index * 0x45d9f3b;
        hash ^= salt * 0x27d4eb2d;
        hash = (hash ^ (hash >>> 16)) * 0x45d9f3b;
        hash = (hash ^ (hash >>> 16)) * 0x45d9f3b;
        hash ^= hash >>> 16;
        return ((hash & 0xFFFF) / 32767.5f) - 1f;
    }

    private void buildBrushTexturePaths(List<DrawingPoint> filtered, float[] widths,
                                       float[] tanX, float[] tanY, int count) {
        if (cachedBrushMainFibers == null) cachedBrushMainFibers = new android.graphics.Path();
        if (cachedBrushTailFibers == null) cachedBrushTailFibers = new android.graphics.Path();
        if (cachedBrushTailHighlights == null) cachedBrushTailHighlights = new android.graphics.Path();
        if (cachedBrushTailShades == null) cachedBrushTailShades = new android.graphics.Path();
        if (cachedBrushHighlightStrands == null) cachedBrushHighlightStrands = new android.graphics.Path();
        if (cachedBrushShadeStrands == null) cachedBrushShadeStrands = new android.graphics.Path();
        if (cachedBrushEdgeFibers == null) cachedBrushEdgeFibers = new android.graphics.Path();
        cachedBrushMainFibers.reset();
        cachedBrushTailFibers.reset();
        cachedBrushTailHighlights.reset();
        cachedBrushTailShades.reset();
        cachedBrushHighlightStrands.reset();
        cachedBrushShadeStrands.reset();
        cachedBrushEdgeFibers.reset();
        if (tool != DrawingTool.BRUSH || count < 2) return;

        float maxWidth = 0f;
        for (float value : widths) maxWidth = Math.max(maxWidth, value);
        int density = Math.max(1, Math.min(10, brushFiberDensity));

        float[] cumulativeLength = new float[count];
        for (int i = 1; i < count; i++) {
            DrawingPoint a = filtered.get(i - 1), b = filtered.get(i);
            cumulativeLength[i] = cumulativeLength[i - 1]
                    + (float) Math.hypot(b.x - a.x, b.y - a.y);
        }
        float totalLength = cumulativeLength[count - 1];
        if (totalLength < 0.001f) return;

        // 1. Upper crest 3D impasto lighting spline (tracing upper ridge of wet paint)
        int mainSamples = Math.min(count, 54);
        float lastMainX = 0f, lastMainY = 0f;
        boolean mainStarted = false;
        for (int sample = 0; sample < mainSamples; sample++) {
            int i = Math.round(sample * (count - 1f) / Math.max(1f, mainSamples - 1f));
            DrawingPoint pt = filtered.get(i);
            float halfW = widths[i] * 0.5f;
            float nx = -tanY[i], ny = tanX[i];
            float x = pt.x + nx * (halfW * 0.40f);
            float y = pt.y + ny * (halfW * 0.40f);
            if (!mainStarted) {
                cachedBrushMainFibers.moveTo(x, y);
                mainStarted = true;
            } else {
                cachedBrushMainFibers.quadTo(lastMainX, lastMainY, (lastMainX + x) * 0.5f, (lastMainY + y) * 0.5f);
            }
            lastMainX = x;
            lastMainY = y;
        }
        if (mainStarted) cachedBrushMainFibers.lineTo(lastMainX, lastMainY);

        // 2. Longitudinal bristle striations (groove shadows and highlight ridges across the body)
        int laneCount = Math.max(16, Math.min(32, 14 + density * 2));
        int strandSamples = Math.min(count, 54);
        for (int lane = 0; lane < laneCount; lane++) {
            float across = laneCount == 1 ? 0f : (lane / (laneCount - 1f)) * 1.66f - 0.83f;
            float startAt = Math.max(0f, (edgeNoise(lane, filtered.get(0).x, filtered.get(0).y, 311) + 1f) * 0.015f) * totalLength;
            float endAt = totalLength * (0.88f + 0.11f * ((edgeNoise(lane, filtered.get(count - 1).x, filtered.get(count - 1).y, 773) + 1f) * 0.5f));
            android.graphics.Path target = (lane % 2 == 0) ? cachedBrushHighlightStrands : cachedBrushShadeStrands;
            boolean started = false;
            float lastX = 0f, lastY = 0f;
            for (int sample = 0; sample < strandSamples; sample++) {
                int i = Math.round(sample * (count - 1f) / Math.max(1f, strandSamples - 1f));
                float traveled = cumulativeLength[i];
                if (traveled < startAt || traveled > endAt) continue;
                DrawingPoint pt = filtered.get(i);
                float halfW = widths[i] * 0.5f;
                float nx = -tanY[i], ny = tanX[i];
                float waviness = (float) Math.sin(i * 0.14f + lane * 1.93f) * halfW * 0.018f;
                float irreg = edgeNoise(i, pt.x, pt.y, lane * 23) * Math.min(0.40f, halfW * 0.022f);
                float offset = halfW * across + waviness + irreg;
                float x = pt.x + nx * offset;
                float y = pt.y + ny * offset;
                if (!started) {
                    target.moveTo(x, y);
                    started = true;
                } else {
                    target.quadTo(lastX, lastY, (lastX + x) * 0.5f, (lastY + y) * 0.5f);
                }
                lastX = x;
                lastY = y;
            }
            if (started) target.lineTo(lastX, lastY);
        }

        // 3. Staggered bristle contact marks at the stroke head
        int headFibers = Math.max(10, Math.min(22, (int) (maxWidth / 1.6f)));
        float startTanX = tanX[0], startTanY = tanY[0];
        float startNormalX = -startTanY, startNormalY = startTanX;
        float startHalfW = widths[0] * 0.5f;
        for (int h = 0; h < headFibers; h++) {
            float across = headFibers == 1 ? 0f : (h / (headFibers - 1f)) * 1.74f - 0.87f;
            float stagger = (0.35f + 0.65f * ((edgeNoise(h, filtered.get(0).x, filtered.get(0).y, 881) + 1f) * 0.5f))
                    * Math.min(maxWidth * 0.22f, 4.2f);
            float baseStartX = filtered.get(0).x + startNormalX * (startHalfW * across);
            float baseStartY = filtered.get(0).y + startNormalY * (startHalfW * across);
            cachedBrushEdgeFibers.moveTo(baseStartX - startTanX * stagger, baseStartY - startTanY * stagger);
            cachedBrushEdgeFibers.lineTo(baseStartX + startTanX * (stagger * 0.85f), baseStartY + startTanY * (stagger * 0.85f));
        }

        // 4. Sweeping, feathered, wispy trailing bristle swash at stroke release
        int tailCount = Math.max(20, Math.min(36, 18 + density * 2));
        int endIdx = count - 1;
        float endX = filtered.get(endIdx).x;
        float endY = filtered.get(endIdx).y;
        float endTanX = tanX[endIdx];
        float endTanY = tanY[endIdx];
        float endNormalX = -endTanY;
        float endNormalY = endTanX;

        int prevIdx = Math.max(0, count - 4);
        float curvature = (tanX[prevIdx] * tanY[endIdx] - tanY[prevIdx] * tanX[endIdx]);
        float curveScale = Math.max(-0.55f, Math.min(0.55f, curvature * 2.2f));

        for (int fiber = 0; fiber < tailCount; fiber++) {
            float noise = edgeNoise(fiber, endX, endY, 1711);
            float startFraction = 0.70f + 0.18f * ((noise + 1f) * 0.5f);
            float startDistance = totalLength * startFraction;
            int startIndex = 0;
            while (startIndex < count - 1 && cumulativeLength[startIndex] < startDistance) startIndex++;

            DrawingPoint startPoint = filtered.get(startIndex);
            float startHalfWidth = Math.max(0.15f, widths[startIndex] * 0.5f);
            float across = tailCount == 1 ? 0f : (fiber / (tailCount - 1f)) * 1.76f - 0.88f;
            float normalX = -tanY[startIndex];
            float normalY = tanX[startIndex];
            float x = startPoint.x + normalX * startHalfWidth * across;
            float y = startPoint.y + normalY * startHalfWidth * across;

            android.graphics.Path target = (fiber % 3 == 0)
                    ? cachedBrushTailHighlights
                    : ((fiber % 4 == 2) ? cachedBrushTailShades : cachedBrushTailFibers);

            target.moveTo(x, y);
            float fiberEndX = x, fiberEndY = y;
            for (int i = startIndex + 1; i < count; i++) {
                float halfW = Math.max(0.08f, widths[i] * 0.5f);
                float nx = -tanY[i], ny = tanX[i];
                float laneWobble = edgeNoise(fiber, filtered.get(i).x, filtered.get(i).y, 1801) * Math.min(0.4f, halfW * 0.02f);
                float px = filtered.get(i).x + nx * (halfW * across + laneWobble);
                float py = filtered.get(i).y + ny * (halfW * across + laneWobble);
                target.lineTo(px, py);
                fiberEndX = px;
                fiberEndY = py;
            }

            float lengthNoise = (edgeNoise(fiber, endX, endY, 2039) + 1f) * 0.5f;
            float centerBias = 1.0f - Math.abs(across) * 0.35f;
            float tailLength = Math.max(8f, maxWidth * (0.85f + centerBias * (0.8f + lengthNoise * 1.35f)));

            float fan = across * (tailLength * 0.16f) + edgeNoise(fiber, endX, endY, 2677) * (tailLength * 0.07f);
            float sweep = curveScale * tailLength * 0.60f;

            float ctrl1X = fiberEndX + endTanX * (tailLength * 0.35f) + endNormalX * (fan * 0.20f + sweep * 0.25f);
            float ctrl1Y = fiberEndY + endTanY * (tailLength * 0.35f) + endNormalY * (fan * 0.20f + sweep * 0.25f);
            float ctrl2X = fiberEndX + endTanX * (tailLength * 0.72f) + endNormalX * (fan * 0.70f + sweep * 0.70f);
            float ctrl2Y = fiberEndY + endTanY * (tailLength * 0.72f) + endNormalY * (fan * 0.70f + sweep * 0.70f);
            float tipX = fiberEndX + endTanX * tailLength + endNormalX * (fan + sweep);
            float tipY = fiberEndY + endTanY * tailLength + endNormalY * (fan + sweep);

            target.cubicTo(ctrl1X, ctrl1Y, ctrl2X, ctrl2Y, tipX, tipY);
        }
    }

    private void buildBrushDabFibers(DrawingPoint point, float radius) {
        if (cachedBrushMainFibers == null) cachedBrushMainFibers = new android.graphics.Path();
        if (cachedBrushTailFibers == null) cachedBrushTailFibers = new android.graphics.Path();
        if (cachedBrushHighlightStrands == null) cachedBrushHighlightStrands = new android.graphics.Path();
        if (cachedBrushShadeStrands == null) cachedBrushShadeStrands = new android.graphics.Path();
        cachedBrushMainFibers.reset();
        cachedBrushTailFibers.reset();
        cachedBrushHighlightStrands.reset();
        cachedBrushShadeStrands.reset();
        int fibers = Math.max(14, Math.min(48, (int) (radius * 2.2f)));
        for (int i = 0; i < fibers; i++) {
            double angle = Math.PI * 2.0 * i / fibers;
            float inner = radius * (0.10f + (edgeNoise(i, point.x, point.y, 122) + 1f) * 0.13f);
            float outer = radius * (0.64f + (edgeNoise(i, point.x, point.y, 126) + 1f) * 0.20f);
            float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
            android.graphics.Path target = i % 5 == 0 ? cachedBrushShadeStrands
                    : i % 3 == 0 ? cachedBrushHighlightStrands : cachedBrushMainFibers;
            target.moveTo(point.x + dx * inner, point.y + dy * inner);
            target.lineTo(point.x + dx * outer, point.y + dy * outer);
        }
    }

    private static float smoothStep(float value) {
        float t = Math.max(0f, Math.min(1f, value));
        return t * t * (3f - 2f * t);
    }

    private float getPressureWidth(float pressure, float sensitivity) {
        float p = (Float.isNaN(pressure) || pressure <= 0.001f)
                ? 0.45f
                : Math.max(0.06f, Math.min(1.0f, pressure));
        if (tool == DrawingTool.BRUSH) {
            return width * (0.45f + (float) Math.pow(p, 0.85)
                    * (1.1f + sensitivity * 0.7f));
        }
        return width * (0.50f + (float) Math.pow(p, 0.72)
                * (0.80f + sensitivity * 0.55f));
    }

    private synchronized void computeBounds() {
        if (points == null || points.isEmpty()) {
            cachedBounds = new android.graphics.RectF();
            boundsDirty = false;
            return;
        }

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (DrawingPoint pt : points) {
            if (pt.x < minX) minX = pt.x;
            if (pt.x > maxX) maxX = pt.x;
            if (pt.y < minY) minY = pt.y;
            if (pt.y > maxY) maxY = pt.y;
        }

        float pad = Math.max(width * 2f, 15f);
        cachedBounds = new android.graphics.RectF(minX - pad, minY - pad, maxX + pad, maxY + pad);
        boundsDirty = false;
    }

    private synchronized void computePath() {
        android.graphics.Path path = new android.graphics.Path();
        if (points == null || points.isEmpty()) {
            cachedPath = path;
            pathDirty = false;
            return;
        }
        if (points.size() == 1) {
            DrawingPoint p = points.get(0);
            path.addCircle(p.x, p.y, Math.max(1.0f, width / 2f), android.graphics.Path.Direction.CW);
        } else {
            DrawingPoint first = points.get(0);
            path.moveTo(first.x, first.y);
            for (int i = 1; i < points.size(); i++) {
                DrawingPoint p0 = points.get(i - 1);
                DrawingPoint p1 = points.get(i);
                float midX = (p0.x + p1.x) / 2.0f;
                float midY = (p0.y + p1.y) / 2.0f;
                path.quadTo(p0.x, p0.y, midX, midY);
            }
            DrawingPoint last = points.get(points.size() - 1);
            path.lineTo(last.x, last.y);
        }

        cachedPath = path;
        pathDirty = false;
    }

    public void addPoint(float x, float y, float pressure) {
        addPoint(x, y, pressure, -1L);
    }

    public void addPoint(float x, float y, float pressure, long timeMillis) {
        int previousCount = points.size();
        DrawingPoint previous = previousCount == 0 ? null : points.get(previousCount - 1);
        DrawingPoint point = new DrawingPoint(x, y, pressure, timeMillis);
        boolean canExtendPath = cachedPath != null && !pathDirty && previousCount >= 2;
        boolean canExtendBounds = cachedBounds != null && !boundsDirty;
        points.add(point);

        // The current path ends at the previous sample. Append just the new
        // quadratic segment instead of rebuilding every earlier point on each
        // display frame while a long stroke is still in progress.
        if (canExtendPath) {
            float midX = (previous.x + point.x) * 0.5f;
            float midY = (previous.y + point.y) * 0.5f;
            cachedPath.quadTo(previous.x, previous.y, midX, midY);
            cachedPath.lineTo(point.x, point.y);
        } else {
            cachedPath = null;
            pathDirty = true;
        }

        // Invalidate pressure path so smooth calligraphic envelope is updated
        cachedPressurePath = null;
        pressurePathDirty = true;

        float pad = Math.max(width * 2f, 15f);
        if (canExtendBounds) {
            cachedBounds.union(x - pad, y - pad);
            cachedBounds.union(x + pad, y + pad);
        } else {
            cachedBounds = null;
            boundsDirty = true;
        }
    }

    public void addRecognitionPoint(float x, float y, float pressure, long timeMillis) {
        if (recognitionPoints == null) recognitionPoints = new ArrayList<>();
        recognitionPoints.add(new DrawingPoint(x, y, pressure, timeMillis));
    }

    public List<DrawingPoint> getRecognitionPoints() {
        return recognitionPoints != null && !recognitionPoints.isEmpty()
                ? recognitionPoints : points;
    }

    public void copyRecognitionPointsFrom(Stroke source, float offsetX, float offsetY) {
        List<DrawingPoint> sourcePoints = source.getRecognitionPoints();
        if (sourcePoints == null || sourcePoints.isEmpty()) return;
        if (recognitionPoints == null) recognitionPoints = new ArrayList<>();
        else recognitionPoints.clear();
        for (DrawingPoint point : sourcePoints) {
            recognitionPoints.add(new DrawingPoint(point.x + offsetX, point.y + offsetY,
                    point.pressure, point.timeMillis));
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("color", color);
        obj.put("width", (double) width);
        obj.put("tool", tool.name());
        obj.put("lineStyle", lineStyle != null ? lineStyle.name() : LineStyle.SOLID.name());
        obj.put("brushTextureStyle", brushTextureStyle);
        obj.put("brushFiberDensity", Math.max(1, Math.min(10, brushFiberDensity)));

        JSONArray arr = new JSONArray();
        for (DrawingPoint pt : points) {
            arr.put(pt.toJson());
        }
        obj.put("points", arr);
        if (recognitionPoints != null && !recognitionPoints.isEmpty()) {
            JSONArray recognitionArr = new JSONArray();
            for (DrawingPoint pt : recognitionPoints) recognitionArr.put(pt.toJson());
            obj.put("recognitionPoints", recognitionArr);
        }
        return obj;
    }

    public static Stroke fromJson(JSONObject obj) {
        int color = obj.optInt("color", 0xFF000000);
        float width = (float) obj.optDouble("width", 5.0);
        String toolName = obj.optString("tool", DrawingTool.PEN.name());
        DrawingTool tool;
        try {
            tool = DrawingTool.valueOf(toolName);
        } catch (IllegalArgumentException e) {
            tool = DrawingTool.PEN;
        }

        Stroke stroke = new Stroke(color, width, tool);
        stroke.brushTextureStyle = Math.max(0, Math.min(11, obj.optInt("brushTextureStyle", 0)));
        stroke.brushFiberDensity = Math.max(1, Math.min(10, obj.optInt("brushFiberDensity", 10)));
        String lineStyleName = obj.optString("lineStyle", LineStyle.SOLID.name());
        try {
            stroke.lineStyle = LineStyle.valueOf(lineStyleName);
        } catch (Exception e) {
            stroke.lineStyle = LineStyle.SOLID;
        }
        JSONArray arr = obj.optJSONArray("points");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ptObj = arr.optJSONObject(i);
                if (ptObj != null) {
                    stroke.points.add(DrawingPoint.fromJson(ptObj));
                }
            }
        }
        JSONArray recognitionArr = obj.optJSONArray("recognitionPoints");
        if (recognitionArr != null) {
            for (int i = 0; i < recognitionArr.length(); i++) {
                JSONObject ptObj = recognitionArr.optJSONObject(i);
                if (ptObj != null) stroke.recognitionPoints.add(DrawingPoint.fromJson(ptObj));
            }
        }
        return stroke;
    }
}
