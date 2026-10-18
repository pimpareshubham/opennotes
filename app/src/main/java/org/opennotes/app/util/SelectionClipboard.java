package org.opennotes.app.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.model.Stroke;
import org.opennotes.app.model.TextItem;
import org.opennotes.app.model.ImageItem;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-wide clipboard for preserving editable note objects across pages and notebooks. */
public final class SelectionClipboard {
    private static final String TAG = "SelectionClipboard";
    private static final String FILE_NAME = "selection_clipboard.json";
    private static final int VERSION = 2;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object CACHE_LOCK = new Object();
    private static volatile Payload cached;
    private static long cacheGeneration;

    public interface LoadCallback {
        void onLoaded(Payload payload);
    }

    public static final class Payload {
        public final List<Stroke> strokes;
        public final List<ShapeItem> shapes;
        public final List<TextItem> texts;
        public final List<ImageItem> images;
        public final float centerX;
        public final float centerY;

        public Payload(List<Stroke> strokes, List<ShapeItem> shapes, List<TextItem> texts,
                       List<ImageItem> images, float centerX, float centerY) {
            this.strokes = Collections.unmodifiableList(new ArrayList<>(strokes));
            this.shapes = Collections.unmodifiableList(new ArrayList<>(shapes));
            this.texts = Collections.unmodifiableList(new ArrayList<>(texts));
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.centerX = centerX;
            this.centerY = centerY;
        }

        public Payload(List<Stroke> strokes, List<ShapeItem> shapes, List<TextItem> texts,
                       float centerX, float centerY) {
            this(strokes, shapes, texts, Collections.<ImageItem>emptyList(), centerX, centerY);
        }

        public boolean hasContents() {
            return !strokes.isEmpty() || !shapes.isEmpty() || !texts.isEmpty() || !images.isEmpty();
        }
    }

    private SelectionClipboard() { }

    /** Makes the new clipboard available immediately and persists it off the UI thread. */
    public static void set(Context context, Payload payload) {
        if (context == null || payload == null || !payload.hasContents()) return;
        Context appContext = context.getApplicationContext();
        synchronized (CACHE_LOCK) {
            cached = payload;
            cacheGeneration++;
        }
        IO.execute(() -> write(appContext, payload));
    }

    /** Checks memory first; the file check also enables paste after process recreation. */
    public static boolean hasContents(Context context) {
        Payload value = cached;
        if (value != null) return value.hasContents();
        return context != null && new File(context.getApplicationContext().getFilesDir(), FILE_NAME).isFile();
    }

    /** Loads the persisted clipboard away from the UI thread if this process has no cached copy. */
    public static void load(Context context, LoadCallback callback) {
        if (context == null || callback == null) return;
        Payload value;
        long requestedGeneration;
        synchronized (CACHE_LOCK) {
            value = cached;
            requestedGeneration = cacheGeneration;
        }
        if (value != null) {
            MAIN.post(() -> callback.onLoaded(value));
            return;
        }
        Context appContext = context.getApplicationContext();
        IO.execute(() -> {
            Payload loaded = read(appContext);
            Payload delivery;
            synchronized (CACHE_LOCK) {
                if (cacheGeneration == requestedGeneration && cached == null && loaded != null) {
                    cached = loaded;
                    cacheGeneration++;
                }
                delivery = cached != null ? cached : loaded;
            }
            MAIN.post(() -> {
                Payload latest = cached;
                callback.onLoaded(latest != null ? latest : delivery);
            });
        });
    }

    private static void write(Context context, Payload payload) {
        AtomicFile atomicFile = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
        FileOutputStream stream = null;
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("centerX", (double) payload.centerX);
            root.put("centerY", (double) payload.centerY);
            JSONArray strokes = new JSONArray();
            for (Stroke stroke : payload.strokes) {
                if (stroke != null) strokes.put(stroke.toJson());
            }
            root.put("strokes", strokes);
            JSONArray shapes = new JSONArray();
            for (ShapeItem shape : payload.shapes) {
                if (shape != null) shapes.put(shape.toJson());
            }
            root.put("shapes", shapes);
            JSONArray texts = new JSONArray();
            for (TextItem text : payload.texts) {
                if (text != null) texts.put(text.toJson());
            }
            root.put("texts", texts);
            JSONArray images = new JSONArray();
            for (ImageItem image : payload.images) {
                if (image != null) images.put(image.toJson());
            }
            root.put("images", images);

            stream = atomicFile.startWrite();
            stream.write(root.toString().getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) atomicFile.failWrite(stream);
            Log.e(TAG, "Could not persist copied note content", error);
        }
    }

    private static Payload read(Context context) {
        AtomicFile atomicFile = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
        try (FileInputStream input = atomicFile.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject root = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            int version = root.optInt("version", -1);
            if (version != 1 && version != VERSION) {
                atomicFile.delete();
                return null;
            }

            List<Stroke> strokes = new ArrayList<>();
            JSONArray strokeArray = root.optJSONArray("strokes");
            if (strokeArray != null) {
                for (int i = 0; i < strokeArray.length(); i++) {
                    JSONObject item = strokeArray.optJSONObject(i);
                    if (item != null) strokes.add(Stroke.fromJson(item));
                }
            }
            List<ShapeItem> shapes = new ArrayList<>();
            JSONArray shapeArray = root.optJSONArray("shapes");
            if (shapeArray != null) {
                for (int i = 0; i < shapeArray.length(); i++) {
                    JSONObject item = shapeArray.optJSONObject(i);
                    if (item != null) shapes.add(ShapeItem.fromJson(item));
                }
            }
            List<TextItem> texts = new ArrayList<>();
            JSONArray textArray = root.optJSONArray("texts");
            if (textArray != null) {
                for (int i = 0; i < textArray.length(); i++) {
                    JSONObject item = textArray.optJSONObject(i);
                    if (item != null) texts.add(TextItem.fromJson(item));
                }
            }
            List<ImageItem> images = new ArrayList<>();
            JSONArray imageArray = root.optJSONArray("images");
            if (imageArray != null) {
                for (int i = 0; i < imageArray.length(); i++) {
                    JSONObject item = imageArray.optJSONObject(i);
                    if (item != null) images.add(ImageItem.fromJson(item));
                }
            }
            Payload payload = new Payload(strokes, shapes, texts, images,
                    (float) root.optDouble("centerX", 0d),
                    (float) root.optDouble("centerY", 0d));
            if (!payload.hasContents()) atomicFile.delete();
            return payload.hasContents() ? payload : null;
        } catch (java.io.FileNotFoundException ignored) {
            return null;
        } catch (Exception error) {
            atomicFile.delete();
            Log.e(TAG, "Could not read copied note content", error);
            return null;
        }
    }
}
