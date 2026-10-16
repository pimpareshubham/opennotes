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

    public TextItem(String text, float x, float y, int color, float fontSize) {
        this(text, x, y, color, fontSize, "KALAM");
    }

    public TextItem(String text, float x, float y, int color, float fontSize, String fontKey) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.color = color;
        this.fontSize = fontSize;
        this.fontKey = (fontKey != null && !fontKey.isEmpty()) ? fontKey : "KALAM";
    }

    private transient android.graphics.RectF cachedBounds;
    public void markDirty() {
        cachedBounds = null;
    }
    public android.graphics.RectF getBounds() {
        if (cachedBounds == null) {
            float estimatedWidth = (text != null ? text.length() : 0) * (fontSize * 0.7f);
            cachedBounds = new android.graphics.RectF(x - 10f, y - fontSize - 10f, x + estimatedWidth + 20f, y + 20f);
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
        return obj;
    }

    public static TextItem fromJson(JSONObject obj) {
        String text = obj.optString("text", "");
        float x = (float) obj.optDouble("x", 50.0);
        float y = (float) obj.optDouble("y", 100.0);
        int color = obj.optInt("color", 0xFF000000);
        float size = (float) obj.optDouble("fontSize", 18.0);
        String font = obj.optString("fontKey", "KALAM");
        return new TextItem(text, x, y, color, size, font);
    }
}
