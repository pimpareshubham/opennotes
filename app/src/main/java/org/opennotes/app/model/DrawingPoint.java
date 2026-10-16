package org.opennotes.app.model;

import org.json.JSONException;
import org.json.JSONObject;

public class DrawingPoint {
    public float x;
    public float y;
    public float pressure;

    public DrawingPoint(float x, float y, float pressure) {
        this.x = x;
        this.y = y;
        this.pressure = pressure;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("x", (double) x);
        obj.put("y", (double) y);
        obj.put("p", (double) pressure);
        return obj;
    }

    public static DrawingPoint fromJson(JSONObject obj) {
        float x = (float) obj.optDouble("x", 0.0);
        float y = (float) obj.optDouble("y", 0.0);
        float p = (float) obj.optDouble("p", 1.0);
        return new DrawingPoint(x, y, p);
    }
}
