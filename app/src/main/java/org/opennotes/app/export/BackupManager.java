package org.opennotes.app.export;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;
import org.opennotes.app.db.NotesDbHelper;
import org.opennotes.app.model.Notebook;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Handles full local backup and restore for OpenNotes:
 * - SQLite database (opennotes.db)
 * - Stroke & shape page data (page_data/)
 * - Attached photos and images (note_images/)
 * - PDF documents and imported assets (notebook_assets/)
 * - Voice notes and audio memos (voice_notes/, voice_memos/)
 * - Custom typography and fonts (fonts/)
 */
public final class BackupManager {
    private static final String TAG = "BackupManager";
    public static final String MIME_TYPE = "application/zip";
    public static final String BACKUP_FORMAT = "org.opennotes.local-backup";
    public static final int BACKUP_VERSION = 1;
    public static final String MANIFEST_ENTRY = "backup_manifest.json";
    public static final String DB_ENTRY = "database/opennotes.db";
    public static final String FILES_PREFIX = "files/";
    private static final int BUFFER_SIZE = 32 * 1024;

    public interface BackupCallback {
        void onSuccess(int notebookCount, long totalBytes);
        void onError(Throwable error);
    }

    public interface RestoreCallback {
        void onSuccess(int notebookCount);
        void onError(Throwable error);
    }

    private BackupManager() {}

    /**
     * Creates a compressed ZIP backup archive of all notebooks, pages, drawings, and media.
     */
    public static void createBackup(Context context, Uri destinationUri, BackupCallback callback) {
        new Thread(() -> {
            File tempZipFile = null;
            try {
                Context appContext = context.getApplicationContext();
                NotesDbHelper dbHelper = NotesDbHelper.getInstance(appContext);

                // 1. Force SQLite WAL checkpoint so opennotes.db contains all committed records
                try {
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    Cursor c = db.rawQuery("PRAGMA wal_checkpoint(FULL);", null);
                    if (c != null) c.close();
                } catch (Throwable t) {
                    Log.w(TAG, "WAL checkpoint warning: " + t.getMessage());
                }

                List<Notebook> allNotebooks = dbHelper.getAllNotebooks("All", false, null);
                int notebookCount = allNotebooks.size();

                tempZipFile = new File(appContext.getCacheDir(), "backup_staging_" + System.currentTimeMillis() + ".zip");
                try (FileOutputStream fos = new FileOutputStream(tempZipFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE);
                     ZipOutputStream zos = new ZipOutputStream(bos)) {

                    // 2. Write backup manifest
                    JSONObject manifest = new JSONObject();
                    manifest.put("format", BACKUP_FORMAT);
                    manifest.put("version", BACKUP_VERSION);
                    manifest.put("timestamp", System.currentTimeMillis());
                    manifest.put("notebookCount", notebookCount);
                    manifest.put("appName", "OpenNotes");

                    ZipEntry manifestEntry = new ZipEntry(MANIFEST_ENTRY);
                    zos.putNextEntry(manifestEntry);
                    zos.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();

                    // 3. Write database file
                    File dbFile = appContext.getDatabasePath("opennotes.db");
                    if (dbFile.exists() && dbFile.length() > 0) {
                        writeFileToZip(zos, dbFile, DB_ENTRY);
                    }

                    // 4. Write data directories from getFilesDir()
                    File filesDir = appContext.getFilesDir();
                    if (filesDir.exists() && filesDir.isDirectory()) {
                        writeDirectoryToZip(zos, filesDir, filesDir, FILES_PREFIX);
                    }

                    zos.finish();
                }

                long finalSize = tempZipFile.length();

                // 5. Copy completed backup archive to user's selected destination URI
                try (InputStream in = new FileInputStream(tempZipFile);
                     OutputStream out = appContext.getContentResolver().openOutputStream(destinationUri, "w")) {
                    if (out == null) {
                        throw new IOException("Unable to open output stream for destination URI.");
                    }
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }

                callback.onSuccess(notebookCount, finalSize);
            } catch (Throwable e) {
                Log.e(TAG, "Backup creation failed", e);
                callback.onError(e);
            } finally {
                if (tempZipFile != null && tempZipFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tempZipFile.delete();
                }
            }
        }).start();
    }

    /**
     * Restores library from a ZIP backup archive.
     */
    public static void restoreBackup(Context context, Uri sourceUri, RestoreCallback callback) {
        new Thread(() -> {
            File stagingDir = null;
            try {
                Context appContext = context.getApplicationContext();
                InputStream inStream = appContext.getContentResolver().openInputStream(sourceUri);
                if (inStream == null) {
                    throw new IOException("Unable to open input stream for backup URI.");
                }

                stagingDir = new File(appContext.getCacheDir(), "restore_staging_" + System.currentTimeMillis());
                if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                    throw new IOException("Failed to create temporary staging directory for restore.");
                }

                // 1. Unzip everything to stagingDir
                boolean foundManifest = false;
                boolean foundDb = false;

                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(inStream, BUFFER_SIZE))) {
                    ZipEntry entry;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        // Prevent zip slip vulnerability
                        File targetFile = new File(stagingDir, name);
                        String canonicalPath = targetFile.getCanonicalPath();
                        if (!canonicalPath.startsWith(stagingDir.getCanonicalPath() + File.separator)) {
                            throw new SecurityException("Zip entry attempted directory traversal: " + name);
                        }

                        if (entry.isDirectory()) {
                            targetFile.mkdirs();
                        } else {
                            File parent = targetFile.getParentFile();
                            if (parent != null && !parent.exists()) {
                                parent.mkdirs();
                            }
                            try (FileOutputStream fos = new FileOutputStream(targetFile);
                                 BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE)) {
                                int len;
                                while ((len = zis.read(buffer)) != -1) {
                                    bos.write(buffer, 0, len);
                                }
                            }
                            if (MANIFEST_ENTRY.equalsIgnoreCase(name)) {
                                foundManifest = true;
                            }
                            if (DB_ENTRY.equalsIgnoreCase(name) || "opennotes.db".equalsIgnoreCase(name)) {
                                foundDb = true;
                            }
                        }
                        zis.closeEntry();
                    }
                }

                if (!foundManifest && !foundDb) {
                    throw new IllegalArgumentException("The selected file is not a valid OpenNotes backup archive.");
                }

                // 2. Safely close database connection
                NotesDbHelper.getInstance(appContext).closeDatabase();

                // 3. Restore database file
                File stagingDb = new File(stagingDir, DB_ENTRY);
                if (!stagingDb.exists()) {
                    stagingDb = new File(stagingDir, "opennotes.db");
                }
                if (!stagingDb.exists()) {
                    stagingDb = new File(stagingDir, "database/notes.db");
                }
                if (!stagingDb.exists()) {
                    stagingDb = new File(stagingDir, "notes.db");
                }

                if (stagingDb.exists()) {
                    File targetDb = appContext.getDatabasePath("opennotes.db");
                    File targetDir = targetDb.getParentFile();
                    if (targetDir != null && !targetDir.exists()) {
                        targetDir.mkdirs();
                    }
                    if (targetDb.exists()) {
                        targetDb.delete();
                    }
                    File wal = new File(targetDb.getPath() + "-wal");
                    if (wal.exists()) wal.delete();
                    File shm = new File(targetDb.getPath() + "-shm");
                    if (shm.exists()) shm.delete();

                    copyFile(stagingDb, targetDb);
                }

                // 4. Restore files (strokes, images, audio, assets)
                File stagingFiles = new File(stagingDir, "files");
                if (stagingFiles.exists() && stagingFiles.isDirectory()) {
                    copyDirectory(stagingFiles, appContext.getFilesDir());
                }

                // 5. Reinitialize SQLite database instance & warm up caches
                NotesDbHelper.resetInstance(appContext);
                NotesDbHelper refreshedDb = NotesDbHelper.getInstance(appContext);
                List<Notebook> restoredNotebooks = refreshedDb.getAllNotebooks("All", false, null);
                int count = restoredNotebooks.size();

                callback.onSuccess(count);
            } catch (Throwable e) {
                Log.e(TAG, "Restore failed", e);
                // Attempt to reopen database if closed
                try {
                    NotesDbHelper.resetInstance(context.getApplicationContext());
                } catch (Throwable ignored) {}
                callback.onError(e);
            } finally {
                if (stagingDir != null && stagingDir.exists()) {
                    deleteRecursive(stagingDir);
                }
            }
        }).start();
    }

    private static void writeDirectoryToZip(ZipOutputStream zos, File rootDir, File currentDir, String zipPrefix) throws IOException {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            // Skip transient / cache files
            if ("crash_report.txt".equals(name) || "clipboard_selection.json".equals(name)
                    || name.startsWith(".temp") || name.startsWith("cache")) {
                continue;
            }
            if (file.isDirectory()) {
                writeDirectoryToZip(zos, rootDir, file, zipPrefix);
            } else if (file.isFile()) {
                String relativePath = rootDir.toURI().relativize(file.toURI()).getPath();
                writeFileToZip(zos, file, zipPrefix + relativePath);
            }
        }
    }

    private static void writeFileToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis, BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                zos.write(buffer, 0, len);
            }
        }
        zos.closeEntry();
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private static void copyDirectory(File sourceDir, File targetDir) throws IOException {
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File[] files = sourceDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            File targetFile = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, targetFile);
            } else {
                copyFile(file, targetFile);
            }
        }
    }

    private static void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }
}
