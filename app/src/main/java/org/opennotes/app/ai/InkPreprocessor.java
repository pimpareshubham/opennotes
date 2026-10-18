package org.opennotes.app.ai;

import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.Stroke;

import java.util.ArrayList;
import java.util.List;

/** Cleans noisy touch samples for recognition without mutating the note's saved ink. */
public final class InkPreprocessor {
    private static final float DUPLICATE_DISTANCE = 0.45f;
    private static final float RESAMPLE_STEP = 3f;
    private static final int MAX_POINTS_PER_STROKE = 512;

    private InkPreprocessor() { }

    public static List<Stroke> prepare(List<Stroke> source) {
        List<Stroke> output = new ArrayList<>();
        if (source == null) return output;
        for (Stroke stroke : source) {
            if (stroke == null) continue;
            List<DrawingPoint> raw = stroke.getRecognitionPoints();
            if (raw == null || raw.isEmpty()) raw = stroke.points;
            if (raw == null || raw.isEmpty()) continue;
            List<DrawingPoint> clean = cleanPoints(raw);
            if (clean.isEmpty()) continue;
            if (clean.size() >= 4) smoothGentleTurns(clean);
            List<DrawingPoint> sampled = resample(clean);
            Stroke copy = new Stroke(stroke.color, stroke.width, stroke.tool);
            copy.lineStyle = stroke.lineStyle;
            for (DrawingPoint point : sampled) {
                copy.addPoint(point.x, point.y, point.pressure, point.timeMillis);
                copy.addRecognitionPoint(point.x, point.y, point.pressure, point.timeMillis);
            }
            output.add(copy);
        }
        return output;
    }

    private static List<DrawingPoint> cleanPoints(List<DrawingPoint> raw) {
        List<DrawingPoint> valid = new ArrayList<>(raw.size());
        for (DrawingPoint point : raw) {
            if (point == null || !Float.isFinite(point.x) || !Float.isFinite(point.y)) continue;
            if (!valid.isEmpty()) {
                DrawingPoint previous = valid.get(valid.size() - 1);
                float dx = point.x - previous.x;
                float dy = point.y - previous.y;
                if (dx * dx + dy * dy < DUPLICATE_DISTANCE * DUPLICATE_DISTANCE) continue;
            }
            valid.add(new DrawingPoint(point.x, point.y,
                    Float.isFinite(point.pressure) ? Math.max(0f, Math.min(1f, point.pressure)) : 1f,
                    point.timeMillis));
        }
        if (valid.size() < 3) {
            repairTimes(valid);
            return valid;
        }

        // Remove isolated spikes only when the path immediately returns near its
        // previous position. Long legitimate pen movements remain untouched.
        List<DrawingPoint> filtered = new ArrayList<>(valid.size());
        filtered.add(valid.get(0));
        for (int i = 1; i < valid.size() - 1; i++) {
            DrawingPoint before = valid.get(i - 1);
            DrawingPoint point = valid.get(i);
            DrawingPoint after = valid.get(i + 1);
            float incoming = distance(before, point);
            float outgoing = distance(point, after);
            float returnDistance = distance(before, after);
            float spikeLimit = Math.max(18f, DUPLICATE_DISTANCE * 24f);
            if (Math.min(incoming, outgoing) > spikeLimit && returnDistance < spikeLimit * 0.35f) continue;
            filtered.add(point);
        }
        filtered.add(valid.get(valid.size() - 1));
        repairTimes(filtered);
        return filtered;
    }

    private static void repairTimes(List<DrawingPoint> points) {
        if (points.isEmpty()) return;
        boolean hasClock = false;
        for (DrawingPoint point : points) if (point.timeMillis >= 0L) { hasClock = true; break; }
        if (!hasClock) return;
        long time = Math.max(0L, points.get(0).timeMillis);
        points.get(0).timeMillis = time;
        for (int i = 1; i < points.size(); i++) {
            DrawingPoint point = points.get(i);
            long proposed = point.timeMillis;
            time = proposed > time ? proposed : time + 8L;
            point.timeMillis = time;
        }
    }

    private static void smoothGentleTurns(List<DrawingPoint> points) {
        List<DrawingPoint> smoothed = new ArrayList<>(points);
        for (int i = 1; i < points.size() - 1; i++) {
            DrawingPoint before = points.get(i - 1);
            DrawingPoint point = points.get(i);
            DrawingPoint after = points.get(i + 1);
            float ax = point.x - before.x;
            float ay = point.y - before.y;
            float bx = after.x - point.x;
            float by = after.y - point.y;
            float denom = (float) Math.hypot(ax, ay) * (float) Math.hypot(bx, by);
            if (denom < 0.01f || (ax * bx + ay * by) / denom < 0.72f) continue;
            smoothed.set(i, new DrawingPoint(
                    point.x * 0.8f + (before.x + after.x) * 0.1f,
                    point.y * 0.8f + (before.y + after.y) * 0.1f,
                    point.pressure * 0.8f + (before.pressure + after.pressure) * 0.1f,
                    point.timeMillis));
        }
        for (int i = 0; i < points.size(); i++) points.set(i, smoothed.get(i));
    }

    private static List<DrawingPoint> resample(List<DrawingPoint> points) {
        if (points.size() < 3) return points;
        float total = 0f;
        for (int i = 1; i < points.size(); i++) total += distance(points.get(i - 1), points.get(i));
        if (total <= RESAMPLE_STEP || total / RESAMPLE_STEP >= MAX_POINTS_PER_STROKE) return points;

        int count = Math.max(2, Math.min(MAX_POINTS_PER_STROKE, (int) Math.ceil(total / RESAMPLE_STEP) + 1));
        List<DrawingPoint> sampled = new ArrayList<>(count);
        int segment = 1;
        float segmentStart = 0f;
        float segmentLength = distance(points.get(0), points.get(1));
        for (int i = 0; i < count; i++) {
            float target = total * i / (count - 1f);
            while (segment < points.size() - 1 && segmentStart + segmentLength < target) {
                segmentStart += segmentLength;
                segment++;
                segmentLength = distance(points.get(segment - 1), points.get(segment));
            }
            DrawingPoint first = points.get(segment - 1);
            DrawingPoint second = points.get(segment);
            float t = segmentLength <= 0.001f ? 0f : Math.max(0f, Math.min(1f, (target - segmentStart) / segmentLength));
            long time = first.timeMillis >= 0L && second.timeMillis >= first.timeMillis
                    ? first.timeMillis + (long) ((second.timeMillis - first.timeMillis) * t) : first.timeMillis;
            sampled.add(new DrawingPoint(first.x + (second.x - first.x) * t,
                    first.y + (second.y - first.y) * t,
                    first.pressure + (second.pressure - first.pressure) * t, time));
        }
        return sampled;
    }

    private static float distance(DrawingPoint first, DrawingPoint second) {
        return (float) Math.hypot(second.x - first.x, second.y - first.y);
    }
}
