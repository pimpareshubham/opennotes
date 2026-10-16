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

    public Stroke(int color, float width, DrawingTool tool) {
        this.points = new ArrayList<>();
        this.recognitionPoints = new ArrayList<>();
        this.color = color;
        this.width = width;
        this.tool = tool;
    }

    private transient android.graphics.Path cachedPath;
    private transient android.graphics.RectF cachedBounds;
    private transient boolean pathDirty = true;
    private transient boolean boundsDirty = true;
    private transient android.graphics.Path cachedPressurePath;
    private transient boolean pressurePathDirty = true;
    private transient float pressurePathSensitivity = Float.NaN;
    private transient float pressurePathCurrentWidth;

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
            pressurePathCurrentWidth = 0f;
            pressurePathDirty = false;
            return;
        }

        DrawingPoint first = points.get(0);
        pressurePathCurrentWidth = getPressureWidth(first.pressure, sensitivity);
        addPressureCircle(path, first.x, first.y, pressurePathCurrentWidth * 0.5f);
        for (int i = 1; i < points.size(); i++) {
            DrawingPoint previous = points.get(i - 1);
            DrawingPoint current = points.get(i);
            float targetWidth = getPressureWidth(current.pressure, sensitivity);
            float previousWidth = pressurePathCurrentWidth;
            pressurePathCurrentWidth = pressurePathCurrentWidth * 0.65f + targetWidth * 0.35f;
            appendPressureSegment(path, previous, current,
                    previousWidth, pressurePathCurrentWidth);
        }
        cachedPressurePath = path;
        pressurePathDirty = false;
    }

    private float getPressureWidth(float pressure, float sensitivity) {
        float safePressure = pressure <= 0.05f ? 0.5f : pressure;
        if (tool == DrawingTool.BRUSH) {
            return width * (0.5f + (float) Math.pow(safePressure, 0.8)
                    * (1.0f + sensitivity * 0.7f));
        }
        return width * (0.6f + (float) Math.pow(safePressure, 0.75)
                * (0.7f + sensitivity * 0.5f));
    }

    private static void appendPressureSegment(android.graphics.Path path,
                                              DrawingPoint from, DrawingPoint to,
                                              float fromWidth, float toWidth) {
        float dx = to.x - from.x;
        float dy = to.y - from.y;
        float distance = (float) Math.hypot(dx, dy);
        float fromRadius = Math.max(0.5f, fromWidth * 0.5f);
        float toRadius = Math.max(0.5f, toWidth * 0.5f);
        // The previous segment already contributes its endpoint disk. Adding a
        // disk at both ends of every segment doubled the number of path contours
        // on long fountain/brush strokes without changing their visible outline.
        addPressureCircle(path, to.x, to.y, toRadius);
        if (distance < 0.001f) return;

        float normalX = -dy / distance;
        float normalY = dx / distance;
        // Match the clockwise pressure disks above. Opposite winding makes
        // overlapping segments cancel under WINDING fill, leaving dotted holes.
        path.moveTo(from.x + normalX * fromRadius, from.y + normalY * fromRadius);
        path.lineTo(from.x - normalX * fromRadius, from.y - normalY * fromRadius);
        path.lineTo(to.x - normalX * toRadius, to.y - normalY * toRadius);
        path.lineTo(to.x + normalX * toRadius, to.y + normalY * toRadius);
        path.close();
    }

    private static void addPressureCircle(android.graphics.Path path, float x, float y, float radius) {
        path.addCircle(x, y, radius, android.graphics.Path.Direction.CW);
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
            path.addCircle(p.x, p.y, Math.max(2f, width / 2f), android.graphics.Path.Direction.CW);
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
        boolean canExtendPressurePath = cachedPressurePath != null && !pressurePathDirty
                && previousCount >= 1;
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

        if (canExtendPressurePath) {
            float targetWidth = getPressureWidth(point.pressure, pressurePathSensitivity);
            float previousWidth = pressurePathCurrentWidth;
            pressurePathCurrentWidth = pressurePathCurrentWidth * 0.65f + targetWidth * 0.35f;
            appendPressureSegment(cachedPressurePath, previous, point,
                    previousWidth, pressurePathCurrentWidth);
        } else {
            cachedPressurePath = null;
            pressurePathDirty = true;
        }

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
