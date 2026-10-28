package org.opennotes.app.export;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opennotes.app.db.NotesDbHelper;
import org.opennotes.app.model.Notebook;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable editable OpenNotes archive containing raw ink, page objects, and their media. */
public final class NativeNoteArchive {
    public static final String MIME_TYPE = "application/zip";
    public static final String FILE_EXTENSION = ".opennote";
    private static final String FORMAT = "org.opennotes.editable-notebook";
    private static final int VERSION = 1;
    private static final String MANIFEST = "manifest.json";
    private static final String PAGE_PREFIX = "pages/page-";
    private static final String ASSET_PREFIX = "assets/asset-";
    private static final int COPY_BUFFER_BYTES = 32 * 1024;

    private NativeNoteArchive() {}

    /** Exports a notebook to a temporary .opennote archive file for cloud upload. */
    public static File exportNotebookToTempArchive(Context context, NotesDbHelper db, Notebook notebook) throws IOException, JSONException {
        if (context == null || db == null || notebook == null) {
            throw new IllegalArgumentException("Context, db, and notebook required.");
        }
        List<Page> pages = db.getPagesForNotebook(notebook.id);
        File tempFile = new File(context.getCacheDir(), "sync_" + (notebook.syncId != null ? notebook.syncId : notebook.id) + "_" + System.currentTimeMillis() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            writeNotebook(context, notebook, pages, fos);
        }
        return tempFile;
    }

    public static void writeNotebook(Context context, Notebook notebook, List<Page> pages,
                                     OutputStream output) throws IOException, JSONException {
        if (context == null || notebook == null || pages == null || output == null) {
            throw new IllegalArgumentException("Notebook, pages, and destination are required.");
        }
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output));
        Map<String, String> archivedAssets = new HashMap<>();
        JSONArray pageEntries = new JSONArray();
        int assetIndex = 0;
        try {
            for (int i = 0; i < pages.size(); i++) {
                Page page = pages.get(i);
                if (page == null) continue;
                String entryName = String.format(java.util.Locale.US, "pages/page-%08d.json", i);
                JSONObject pageJson = pageToJson(page);
                JSONArray images = pageJson.getJSONArray("images");
                for (int imageIndex = 0; imageIndex < images.length(); imageIndex++) {
                    JSONObject image = images.getJSONObject(imageIndex);
                    String imagePath = image.optString("imagePath", "");
                    if (!imagePath.isEmpty()) {
                        AssetResult result = writeAssetIfNeeded(context, zip, archivedAssets, imagePath, assetIndex);
                        assetIndex = result.nextIndex;
                        image.put("imagePath", result.reference);
                    }
                    String audioPath = image.optString("audioPath", "");
                    if (!audioPath.isEmpty()) {
                        AssetResult result = writeAssetIfNeeded(context, zip, archivedAssets, audioPath, assetIndex);
                        assetIndex = result.nextIndex;
                        image.put("audioPath", result.reference);
                    }
                }
                putJsonEntry(zip, entryName, pageJson);
                pageEntries.put(entryName);
            }

            String archivedCoverImage = "";
            if (notebook.coverImagePath != null && !notebook.coverImagePath.trim().isEmpty()) {
                AssetResult result = writeAssetIfNeeded(context, zip, archivedAssets,
                        notebook.coverImagePath, assetIndex);
                assetIndex = result.nextIndex;
                archivedCoverImage = result.reference;
            }

            JSONObject manifest = new JSONObject();
            manifest.put("format", FORMAT);
            manifest.put("version", VERSION);
            JSONObject notebookJson = new JSONObject();
            notebookJson.put("title", notebook.title != null ? notebook.title : "Untitled Notebook");
            notebookJson.put("folder", notebook.folder != null ? notebook.folder : "Default");
            notebookJson.put("coverColor", notebook.coverColor);
            notebookJson.put("coverStyle", notebook.coverStyle);
            notebookJson.put("coverText", notebook.coverText != null ? notebook.coverText : "");
            notebookJson.put("coverImage", archivedCoverImage);
            notebookJson.put("defaultTemplate", (notebook.defaultTemplate != null
                    ? notebook.defaultTemplate : PaperTemplate.BLANK).name());
            notebookJson.put("defaultBgColor", notebook.defaultBgColor);
            notebookJson.put("defaultIsInfinite", notebook.defaultIsInfinite);
            notebookJson.put("favorite", notebook.isFavorite);
            notebookJson.put("pinned", notebook.isPinned);
            notebookJson.put("createdAt", notebook.createdAt);
            notebookJson.put("updatedAt", notebook.updatedAt);
            notebookJson.put("syncId", notebook.syncId != null ? notebook.syncId : "");
            manifest.put("notebook", notebookJson);
            manifest.put("pages", pageEntries);
            putJsonEntry(zip, MANIFEST, manifest);
            zip.finish();
            zip.flush();
        } finally {
            zip.close();
        }
    }

    private static JSONObject pageToJson(Page page) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("pageIndex", page.pageIndex);
        json.put("template", (page.template != null ? page.template : PaperTemplate.BLANK).name());
        json.put("backgroundColor", page.backgroundColor);
        json.put("isInfinite", page.isInfinite);
        json.put("canvasHeight", (double) page.canvasHeight);
        json.put("createdAt", page.createdAt);
        json.put("updatedAt", page.updatedAt);
        json.put("strokes", new JSONArray(page.strokesToJson()));
        json.put("shapes", new JSONArray(page.shapesToJson()));
        json.put("texts", new JSONArray(page.textsToJson()));
        json.put("images", new JSONArray(page.imagesToJson()));
        return json;
    }

    private static final class AssetResult {
        final String reference;
        final int nextIndex;
        AssetResult(String reference, int nextIndex) {
            this.reference = reference;
            this.nextIndex = nextIndex;
        }
    }

    private static AssetResult writeAssetIfNeeded(Context context, ZipOutputStream zip,
                                                  Map<String, String> assets, String sourcePath,
                                                  int nextIndex) throws IOException {
        String baseSource = sourcePath.contains("#page=") ? sourcePath.substring(0, sourcePath.indexOf("#page=")) : sourcePath;
        String pageSuffix = sourcePath.contains("#page=") ? sourcePath.substring(sourcePath.indexOf("#page=")) : "";

        String existing = assets.get(baseSource);
        if (existing != null) return new AssetResult(existing + pageSuffix, nextIndex);
        String entryName = String.format(java.util.Locale.US, "assets/asset-%08d.bin", nextIndex++);
        try (InputStream input = openSource(context, baseSource)) {
            if (input == null) throw new IOException("A notebook image or audio file could not be opened: " + baseSource);
            zip.putNextEntry(new ZipEntry(entryName));
            copy(input, zip);
            zip.closeEntry();
        }
        String reference = "asset:" + entryName;
        assets.put(baseSource, reference);
        return new AssetResult(reference + pageSuffix, nextIndex);
    }

    private static InputStream openSource(Context context, String sourcePath) throws IOException {
        Uri uri = Uri.parse(sourcePath);
        if ("content".equalsIgnoreCase(uri.getScheme()) || "file".equalsIgnoreCase(uri.getScheme())) {
            return context.getContentResolver().openInputStream(uri);
        }
        return new FileInputStream(new File(sourcePath));
    }

    private static void putJsonEntry(ZipOutputStream zip, String name, JSONObject value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    public static long importNotebook(Context context, NotesDbHelper db, InputStream input)
            throws IOException, JSONException {
        if (context == null || db == null || input == null) {
            throw new IllegalArgumentException("Notebook source is unavailable.");
        }
        File staging = new File(context.getCacheDir(), "notebook-import-" + UUID.randomUUID());
        File installedAssets = null;
        boolean committed = false;
        try {
            if (!staging.mkdirs()) throw new IOException("Could not prepare a temporary notebook import folder.");
            Set<String> seenEntries = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
                ZipEntry entry;
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !isAllowedEntry(name)) {
                        throw new IOException("The file contains an unsupported archive entry: " + name);
                    }
                    if (!seenEntries.add(name)) throw new IOException("The notebook archive contains a duplicate entry: " + name);
                    File destination = new File(staging, name);
                    File parent = destination.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Could not prepare the notebook archive contents.");
                    }
                    try (OutputStream output = new FileOutputStream(destination)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                    zip.closeEntry();
                }
            }

            File manifestFile = new File(staging, MANIFEST);
            if (!manifestFile.isFile()) throw new IOException("This file is not an OpenNotes notebook archive.");
            JSONObject manifest = new JSONObject(readUtf8(manifestFile));
            if (!FORMAT.equals(manifest.optString("format")) || manifest.optInt("version", -1) != VERSION) {
                throw new IOException("This OpenNotes notebook archive version is not supported.");
            }
            JSONObject notebookJson = manifest.getJSONObject("notebook");
            Notebook notebook = notebookFromJson(notebookJson);
            JSONArray pageList = manifest.getJSONArray("pages");
            installedAssets = new File(new File(context.getFilesDir(), "notebook_assets"), UUID.randomUUID().toString());
            if (!installedAssets.mkdirs()) throw new IOException("Could not create storage for the notebook's media.");

            Map<String, String> resolvedAssets = new HashMap<>();
            String coverImageRef = notebookJson.optString("coverImage", "");
            if (!coverImageRef.isEmpty()) {
                notebook.coverImagePath = resolveAsset(coverImageRef, staging, installedAssets, resolvedAssets);
            }
            List<Page> pages = new ArrayList<>(pageList.length());
            for (int index = 0; index < pageList.length(); index++) {
                String name = pageList.getString(index);
                if (!isPageEntry(name)) throw new IOException("The notebook manifest contains an invalid page reference.");
                File pageFile = new File(staging, name);
                if (!pageFile.isFile()) throw new IOException("A notebook page is missing from the archive: " + name);
                JSONObject pageJson = new JSONObject(readUtf8(pageFile));
                Page page = pageFromJson(notebook, pageJson, index);
                JSONArray images = pageJson.getJSONArray("images");
                for (int imageIndex = 0; imageIndex < images.length(); imageIndex++) {
                    JSONObject image = images.getJSONObject(imageIndex);
                    image.put("imagePath", resolveAsset(image.optString("imagePath", ""),
                            staging, installedAssets, resolvedAssets));
                    String audio = image.optString("audioPath", "");
                    if (!audio.isEmpty()) image.put("audioPath", resolveAsset(audio,
                            staging, installedAssets, resolvedAssets));
                }
                page.imagesFromJson(images.toString());
                pages.add(page);
            }
            if (pages.isEmpty()) throw new IOException("The notebook archive contains no pages.");
            Notebook existing = (notebook.syncId != null && !notebook.syncId.isEmpty())
                    ? db.getNotebookBySyncId(notebook.syncId)
                    : null;
            long id;
            if (existing != null) {
                db.updateNotebookWithPages(existing.id, notebook, pages);
                id = existing.id;
            } else {
                id = db.importNotebookWithPages(notebook, pages);
            }
            committed = true;
            return id;
        } catch (JSONException e) {
            throw new IOException("The notebook archive contains invalid or incomplete page data.", e);
        } finally {
            deleteRecursively(staging);
            if (!committed && installedAssets != null) deleteRecursively(installedAssets);
        }
    }

    private static boolean isAllowedEntry(String name) {
        return MANIFEST.equals(name) || isPageEntry(name) || isAssetEntry(name);
    }

    private static boolean isPageEntry(String name) {
        return name != null && name.matches("pages/page-[0-9]{8}\\.json");
    }

    private static boolean isAssetEntry(String name) {
        return name != null && name.matches("assets/asset-[0-9]{8}\\.bin");
    }

    private static Notebook notebookFromJson(JSONObject json) {
        Notebook notebook = new Notebook(json.optString("title", "Imported Notebook"),
                json.optInt("coverColor", 0xFF1E3A8A),
                PaperTemplate.fromString(json.optString("defaultTemplate", "BLANK")));
        notebook.folder = json.optString("folder", "Default");
        notebook.coverStyle = json.optInt("coverStyle", -1);
        notebook.coverText = json.optString("coverText", "");
        notebook.defaultBgColor = json.optInt("defaultBgColor", 0xFF121214);
        notebook.defaultIsInfinite = json.optBoolean("defaultIsInfinite", false);
        notebook.isFavorite = json.optBoolean("favorite", false);
        notebook.isPinned = json.optBoolean("pinned", false);
        notebook.createdAt = json.optLong("createdAt", notebook.createdAt);
        notebook.updatedAt = json.optLong("updatedAt", notebook.updatedAt);
        String syncId = json.optString("syncId", "");
        if (!syncId.isEmpty()) {
            notebook.syncId = syncId;
        }
        return notebook;
    }

    private static Page pageFromJson(Notebook notebook, JSONObject json, int index) throws JSONException {
        PaperTemplate template = PaperTemplate.fromString(json.optString("template",
                notebook.defaultTemplate != null ? notebook.defaultTemplate.name() : "BLANK"));
        Page page = new Page(0L, index, template,
                json.optInt("backgroundColor", notebook.defaultBgColor),
                json.optBoolean("isInfinite", notebook.defaultIsInfinite));
        page.canvasHeight = (float) json.optDouble("canvasHeight", 0d);
        page.createdAt = json.optLong("createdAt", page.createdAt);
        page.updatedAt = json.optLong("updatedAt", page.updatedAt);
        page.strokesFromJson(json.getJSONArray("strokes").toString());
        page.shapesFromJson(json.getJSONArray("shapes").toString());
        page.textsFromJson(json.getJSONArray("texts").toString());
        page.imagesFromJson(json.getJSONArray("images").toString());
        return page;
    }

    private static String resolveAsset(String reference, File staging, File installDirectory,
                                       Map<String, String> resolved) throws IOException {
        if (reference == null || reference.isEmpty()) return "";
        String pageSuffix = "";
        String baseRef = reference;
        if (reference.contains("#page=")) {
            pageSuffix = reference.substring(reference.indexOf("#page="));
            baseRef = reference.substring(0, reference.indexOf("#page="));
        }
        if (!baseRef.startsWith("asset:")) {
            throw new IOException("An image, PDF or audio reference is not portable: " + reference);
        }
        String entryName = baseRef.substring("asset:".length());
        if (!isAssetEntry(entryName)) throw new IOException("An image, PDF or audio reference is invalid.");
        String path = resolved.get(entryName);
        if (path != null) return path + pageSuffix;
        File source = new File(staging, entryName);
        if (!source.isFile()) throw new IOException("A media file is missing from the notebook archive: " + entryName);
        File destination = new File(installDirectory, entryName.substring(ASSET_PREFIX.length()));
        copy(source, destination);
        path = destination.getAbsolutePath();
        resolved.put(entryName, path);
        return path + pageSuffix;
    }

    private static void copy(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static String readUtf8(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            copy(input, bytes);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        // Temporary staging and failed-import media are best-effort cleanup.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
