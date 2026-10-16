package org.opennotes.app.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Stroke {
    public List<DrawingPoint> points;
    public int color;
    public float width;
    public DrawingTool tool;

    public Stroke(int color, float width, DrawingTool tool) {
        this.points = new ArrayList<>();
        this.color = color;
        this.width = width;
        this.tool = tool;
    }

    private transient android.graphics.Path cachedPath;
    private transient android.graphics.RectF cachedBounds;
    private transient boolean pathDirty = true;
    private transient boolean boundsDirty = true;

    public void markDirty() {
        pathDirty = true;
        boundsDirty = true;
        cachedPath = null;
        cachedBounds = null;
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
        points.add(new DrawingPoint(x, y, pressure));
        markDirty();
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
        return stroke;
    }
}
