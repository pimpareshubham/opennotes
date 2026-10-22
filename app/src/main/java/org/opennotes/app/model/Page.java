package org.opennotes.app.model;

import org.json.JSONArray;
import org.json.JSONException;
import android.graphics.RectF;
import org.opennotes.app.ai.PageWritingContext;

import java.util.ArrayList;
import java.util.List;

public class Page {
    public long id;
    public long notebookId;
    public int pageIndex;
    public PaperTemplate template;
    public int backgroundColor;
    public boolean isInfinite;
    public float canvasHeight;
    public List<Stroke> strokes;
    public List<ShapeItem> shapes;
    public List<TextItem> texts;
    public List<ImageItem> images;
    public long createdAt;
    public long updatedAt;
    /** Revision for derived full-page geometry and writing context. */
    public long contentRevision;
    /** Fast loading flag: true if strokes, shapes, texts, and images are loaded from disk/db. */
    public volatile boolean isContentLoaded = true;
    /** Dirty tracking flag: true if page content, height, or properties were modified since load/save. */
    public volatile boolean isModified = false;
    private transient PageWritingContext cachedWritingContext;
    private transient long cachedWritingContextRevision = -1L;

    public Page(long notebookId, int pageIndex, PaperTemplate template) {
        this(notebookId, pageIndex, template, 0xFFFFFFFF, false);
    }

    public Page(long notebookId, int pageIndex, PaperTemplate template, int backgroundColor, boolean isInfinite) {
        this.id = 0;
        this.notebookId = notebookId;
        this.pageIndex = pageIndex;
        this.template = template;
        this.backgroundColor = backgroundColor;
        this.isInfinite = isInfinite;
        this.canvasHeight = 0f;
        this.strokes = new ArrayList<>();
        this.shapes = new ArrayList<>();
        this.texts = new ArrayList<>();
        this.images = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.isContentLoaded = true;
        this.isModified = false;
    }

    public synchronized Page createSnapshot() {
        Page snapshot = new Page(this.notebookId, this.pageIndex, this.template, this.backgroundColor, this.isInfinite);
        snapshot.id = this.id;
        snapshot.canvasHeight = this.canvasHeight;
        snapshot.createdAt = this.createdAt;
        snapshot.updatedAt = this.updatedAt;
        snapshot.contentRevision = this.contentRevision;
        snapshot.isContentLoaded = this.isContentLoaded;
        snapshot.isModified = false;
        synchronized (this.strokes) {
            snapshot.strokes = new ArrayList<>(this.strokes);
        }
        synchronized (this.shapes) {
            snapshot.shapes = new ArrayList<>(this.shapes);
        }
        synchronized (this.texts) {
            snapshot.texts = new ArrayList<>(this.texts);
        }
        synchronized (this.images) {
            snapshot.images = new ArrayList<>(this.images);
        }
        return snapshot;
    }

    public synchronized void markContentChanged() {
        isModified = true;
        contentRevision++;
        updatedAt = System.currentTimeMillis();
        cachedWritingContext = null;
        cachedWritingContextRevision = -1L;
    }

    public synchronized void setCanvasHeight(float height) {
        if (Math.abs(this.canvasHeight - height) > 1f) {
            this.canvasHeight = height;
            if (this.isContentLoaded) {
                this.isModified = true;
            }
        }
    }

    /** All content bounds are derived from current page items, never a fixed paper size. */
    public synchronized RectF getContentBounds() {
        return new RectF(getWritingContext().contentBounds);
    }

    /** Full-page writing profile, recalculated only after a recorded page mutation. */
    public synchronized PageWritingContext getWritingContext() {
        if (cachedWritingContext == null || cachedWritingContextRevision != contentRevision) {
            cachedWritingContext = PageWritingContext.from(this);
            cachedWritingContextRevision = contentRevision;
        }
        return cachedWritingContext;
    }

    public String strokesToJson() {
        JSONArray arr = new JSONArray();
        List<Stroke> copy;
        synchronized (strokes) {
            copy = new ArrayList<>(strokes);
        }
        for (Stroke s : copy) {
            try {
                arr.put(s.toJson());
            } catch (JSONException ignored) {}
        }
        return arr.toString();
    }

    public void strokesFromJson(String jsonStr) {
        synchronized (strokes) {
            strokes.clear();
            if (jsonStr == null || jsonStr.trim().isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(jsonStr);
                for (int i = 0; i < arr.length(); i++) {
                    strokes.add(Stroke.fromJson(arr.getJSONObject(i)));
                }
            } catch (JSONException ignored) {}
        }
    }

    public String shapesToJson() {
        JSONArray arr = new JSONArray();
        List<ShapeItem> copy;
        synchronized (shapes) {
            copy = new ArrayList<>(shapes);
        }
        for (ShapeItem s : copy) {
            try {
                arr.put(s.toJson());
            } catch (JSONException ignored) {}
        }
        return arr.toString();
    }

    public void shapesFromJson(String jsonStr) {
        synchronized (shapes) {
            shapes.clear();
            if (jsonStr == null || jsonStr.trim().isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(jsonStr);
                for (int i = 0; i < arr.length(); i++) {
                    shapes.add(ShapeItem.fromJson(arr.getJSONObject(i)));
                }
            } catch (JSONException ignored) {}
        }
    }

    public String textsToJson() {
        JSONArray arr = new JSONArray();
        List<TextItem> copy;
        synchronized (texts) {
            copy = new ArrayList<>(texts);
        }
        for (TextItem t : copy) {
            try {
                arr.put(t.toJson());
            } catch (JSONException ignored) {}
        }
        return arr.toString();
    }

    public void textsFromJson(String jsonStr) {
        synchronized (texts) {
            texts.clear();
            if (jsonStr == null || jsonStr.trim().isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(jsonStr);
                for (int i = 0; i < arr.length(); i++) {
                    texts.add(TextItem.fromJson(arr.getJSONObject(i)));
                }
            } catch (JSONException ignored) {}
        }
    }

    public String imagesToJson() {
        JSONArray arr = new JSONArray();
        List<ImageItem> copy;
        synchronized (images) {
            copy = new ArrayList<>(images);
        }
        for (ImageItem img : copy) {
            try {
                arr.put(img.toJson());
            } catch (JSONException ignored) {}
        }
        return arr.toString();
    }

    public void imagesFromJson(String jsonStr) {
        synchronized (images) {
            images.clear();
            if (jsonStr == null || jsonStr.trim().isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(jsonStr);
                for (int i = 0; i < arr.length(); i++) {
                    images.add(ImageItem.fromJson(arr.getJSONObject(i)));
                }
            } catch (JSONException ignored) {}
        }
    }
}
