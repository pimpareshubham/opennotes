package org.opennotes.app.model;

import org.json.JSONException;
import org.json.JSONObject;

public class ShapeItem {
    public enum Type {
        LINE,
        ARROW,
        RECTANGLE,
        CIRCLE,
        TRIANGLE,
        STAR,
        COORDINATE_AXIS,
        TABLE
    }

    public Type type;
    public float startX;
    public float startY;
    public float endX;
    public float endY;
    public int color;
    public float strokeWidth;
    public Stroke.LineStyle lineStyle = Stroke.LineStyle.SOLID;
    public Integer fillColor = null;

    public ShapeItem(Type type, float startX, float startY, float endX, float endY, int color, float strokeWidth) {
        this.type = type;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.color = color;
        this.strokeWidth = strokeWidth;
        this.lineStyle = Stroke.LineStyle.SOLID;
        this.fillColor = null;
    }

    private transient android.graphics.RectF cachedBounds;
    public void markDirty() {
        cachedBounds = null;
    }
    public android.graphics.RectF getBounds() {
        if (cachedBounds == null) {
            float minX = Math.min(startX, endX) - strokeWidth;
            float minY = Math.min(startY, endY) - strokeWidth;
            float maxX = Math.max(startX, endX) + strokeWidth;
            float maxY = Math.max(startY, endY) + strokeWidth;
            cachedBounds = new android.graphics.RectF(minX, minY, maxX, maxY);
        }
        return cachedBounds;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("type", type.name());
        obj.put("startX", (double) startX);
        obj.put("startY", (double) startY);
        obj.put("endX", (double) endX);
        obj.put("endY", (double) endY);
        obj.put("color", color);
        obj.put("strokeWidth", (double) strokeWidth);
        obj.put("lineStyle", lineStyle != null ? lineStyle.name() : Stroke.LineStyle.SOLID.name());
        if (fillColor != null) {
            obj.put("fillColor", fillColor.intValue());
        }
        return obj;
    }

    public static ShapeItem fromJson(JSONObject obj) {
        String typeStr = obj.optString("type", Type.RECTANGLE.name());
        Type type;
        try {
            type = Type.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            type = Type.RECTANGLE;
        }

        float sx = (float) obj.optDouble("startX", 0.0);
        float sy = (float) obj.optDouble("startY", 0.0);
        float ex = (float) obj.optDouble("endX", 0.0);
        float ey = (float) obj.optDouble("endY", 0.0);
        int color = obj.optInt("color", 0xFF000000);
        float sw = (float) obj.optDouble("strokeWidth", 4.0);

        ShapeItem shape = new ShapeItem(type, sx, sy, ex, ey, color, sw);
        String lineStyleName = obj.optString("lineStyle", Stroke.LineStyle.SOLID.name());
        try {
            shape.lineStyle = Stroke.LineStyle.valueOf(lineStyleName);
        } catch (Exception e) {
            shape.lineStyle = Stroke.LineStyle.SOLID;
        }
        if (obj.has("fillColor")) {
            shape.fillColor = obj.optInt("fillColor");
        }
        return shape;
    }
}
