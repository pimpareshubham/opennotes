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
    private transient boolean pressurePathDirty = true;
    private transient float pressurePathSensitivity = Float.NaN;

    public void markDirty() {
        pathDirty = true;
        boundsDirty = true;
        cachedPath = null;
        cachedBounds = null;
        pressurePathDirty = true;
        cachedPressurePath = null;
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
            cachedPressurePath = path;
            pressurePathDirty = false;
            return;
        }

        // Calm small sample-to-sample direction changes before building the fountain nib outline.
        // Recognition keeps the original samples; this only improves how the ink is rendered.
        if (tool == DrawingTool.FOUNTAIN && count > 3) {
            List<DrawingPoint> smoothed = new ArrayList<>(count);
            smoothed.add(filtered.get(0));
            for (int i = 1; i < count - 1; i++) {
                DrawingPoint before = filtered.get(i - 1);
                DrawingPoint point = filtered.get(i);
                DrawingPoint after = filtered.get(i + 1);
                smoothed.add(new DrawingPoint(
                        before.x * 0.18f + point.x * 0.64f + after.x * 0.18f,
                        before.y * 0.18f + point.y * 0.64f + after.y * 0.18f,
                        before.pressure * 0.18f + point.pressure * 0.64f + after.pressure * 0.18f,
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
                    float fromEnd = smoothStep(Math.min(1f, (totalLength - traveled) / taperLength));
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

        // Start Cap (Smooth Tapered Tip from Right to Left at Stroke Start)
        DrawingPoint firstPt = filtered.get(0);
        float startTipRadius = Math.max(0.6f, widths[0] * 0.5f);
        float startTipX = firstPt.x - tanX[0] * startTipRadius;
        float startTipY = firstPt.y - tanY[0] * startTipRadius;
        path.quadTo(startTipX, startTipY, leftX[0], leftY[0]);

        path.close();

        cachedPressurePath = path;
        pressurePathDirty = false;
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
