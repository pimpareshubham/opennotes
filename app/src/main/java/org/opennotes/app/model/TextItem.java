package org.opennotes.app.model;

import org.json.JSONException;
import org.json.JSONObject;

public class TextItem {
    public String text;
    public float x;
    public float y;
    public int color;
    public float fontSize;
    public String fontKey;
    public float rotationAngle;

    public TextItem(String text, float x, float y, int color, float fontSize) {
        this(text, x, y, color, fontSize, "KALAM", 0f);
    }

    public TextItem(String text, float x, float y, int color, float fontSize, String fontKey) {
        this(text, x, y, color, fontSize, fontKey, 0f);
    }

    public TextItem(String text, float x, float y, int color, float fontSize, String fontKey, float rotationAngle) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.color = color;
        this.fontSize = fontSize;
        this.fontKey = (fontKey != null && !fontKey.isEmpty()) ? fontKey : "KALAM";
        this.rotationAngle = rotationAngle;
    }

    private transient android.graphics.RectF cachedBounds;
    private static final android.graphics.Paint MEASURE_PAINT = new android.graphics.Paint();

    public void markDirty() {
        cachedBounds = null;
    }

    public android.graphics.RectF getBounds() {
        if (cachedBounds == null) {
            float width;
            synchronized (MEASURE_PAINT) {
                MEASURE_PAINT.setTextSize(fontSize);
                width = MEASURE_PAINT.measureText(text != null ? text : "");
            }
            android.graphics.RectF unrotated = new android.graphics.RectF(x - 4f, y - fontSize - 4f, x + width + 4f, y + 6f);
            if (Math.abs(rotationAngle) < 0.5f) {
                cachedBounds = unrotated;
            } else {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotationAngle, x, y);
                m.mapRect(unrotated);
                cachedBounds = unrotated;
            }
        }
        return cachedBounds;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("text", text);
        obj.put("x", (double) x);
        obj.put("y", (double) y);
        obj.put("color", color);
        obj.put("fontSize", (double) fontSize);
        obj.put("fontKey", fontKey);
        obj.put("rotationAngle", (double) rotationAngle);
        return obj;
    }

    public static TextItem fromJson(JSONObject obj) {
        String text = obj.optString("text", "");
        float x = (float) obj.optDouble("x", 50.0);
        float y = (float) obj.optDouble("y", 100.0);
        int color = obj.optInt("color", 0xFF000000);
        float size = (float) obj.optDouble("fontSize", 18.0);
        String font = obj.optString("fontKey", "KALAM");
        float rotation = (float) obj.optDouble("rotationAngle", 0.0);
        return new TextItem(text, x, y, color, size, font, rotation);
    }
}
