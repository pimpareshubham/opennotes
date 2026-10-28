package org.opennotes.app.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opennotes.app.db.AppSettings;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Communicates directly with Google Drive REST API v3 via lightweight, reliable HTTPS connections.
 */
public final class GoogleDriveApiClient {
    private static final String TAG = "GoogleDriveApiClient";
    private static final String DRIVE_API_BASE = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD_API_BASE = "https://www.googleapis.com/upload/drive/v3";
    private static final int TIMEOUT_MS = 25000;
    private static final int BUFFER_SIZE = 32 * 1024;

    public static class DriveFile {
        public final String id;
        public final String name;
        public final String mimeType;
        public final long size;
        public final String syncId;
        public final long updatedAt;
        public final boolean isTrashed;

        public DriveFile(String id, String name, String mimeType, long size, String syncId, long updatedAt, boolean isTrashed) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
            this.syncId = syncId;
            this.updatedAt = updatedAt;
            this.isTrashed = isTrashed;
        }
    }

    private static GoogleDriveApiClient sInstance;

    private GoogleDriveApiClient() {}

    public static synchronized GoogleDriveApiClient getInstance() {
        if (sInstance == null) {
            sInstance = new GoogleDriveApiClient();
        }
        return sInstance;
    }

    /**
     * Resolves or creates the "OpenNotes" folder in the user's Google Drive root.
     */
    @NonNull
    public String getOrCreateOpenNotesFolder(@NonNull Context context) throws Exception {
        AppSettings settings = AppSettings.getInstance(context);
        String savedFolderId = settings.getGoogleDriveFolderId();
        if (savedFolderId != null && !savedFolderId.isEmpty()) {
            // Verify it still exists and is not trashed
            try {
                JSONObject folder = executeWithAuth(context, (token) -> {
                    HttpURLConnection conn = openGet(DRIVE_API_BASE + "/files/" + savedFolderId + "?fields=id,name,trashed", token);
                    if (conn.getResponseCode() == 200) {
                        return readJsonResponse(conn);
                    }
                    return null;
                });
                if (folder != null && !folder.optBoolean("trashed", false)) {
                    return savedFolderId;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Cached folder ID invalid or inaccessible, searching root: " + t.getMessage());
            }
        }

        // Query root for OpenNotes folder
        String query = "'root' in parents and name = 'OpenNotes' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        String encodedQuery = URLEncoder.encode(query, "UTF-8");
        String urlStr = DRIVE_API_BASE + "/files?q=" + encodedQuery + "&fields=files(id,name)&pageSize=1";

        JSONObject searchResult = executeWithAuth(context, (token) -> {
            HttpURLConnection conn = openGet(urlStr, token);
            return readJsonResponse(conn);
        });

        JSONArray files = searchResult.optJSONArray("files");
        if (files != null && files.length() > 0) {
            String folderId = files.getJSONObject(0).getString("id");
            settings.setGoogleDriveFolderId(folderId);
            return folderId;
        }

        // Create folder
        JSONObject createFolderBody = new JSONObject();
        createFolderBody.put("name", "OpenNotes");
        createFolderBody.put("mimeType", "application/vnd.google-apps.folder");
        JSONArray parents = new JSONArray();
        parents.put("root");
        createFolderBody.put("parents", parents);

        JSONObject created = executeWithAuth(context, (token) -> {
            HttpURLConnection conn = openPost(DRIVE_API_BASE + "/files?fields=id,name", token, "application/json; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(createFolderBody.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return readJsonResponse(conn);
        });

        String newFolderId = created.getString("id");
        settings.setGoogleDriveFolderId(newFolderId);
        Log.i(TAG, "Created OpenNotes folder in Google Drive: " + newFolderId);
        return newFolderId;
    }

    /**
     * Lists all non-trashed files located inside the specified Google Drive folder.
     */
    @NonNull
    public List<DriveFile> listFolderFiles(@NonNull Context context, @NonNull String folderId) throws Exception {
        String query = "'" + folderId + "' in parents and trashed = false";
        String encodedQuery = URLEncoder.encode(query, "UTF-8");
        String urlStr = DRIVE_API_BASE + "/files?q=" + encodedQuery + "&fields=files(id,name,mimeType,size,modifiedTime,appProperties,trashed)&pageSize=1000";

        JSONObject result = executeWithAuth(context, (token) -> {
            HttpURLConnection conn = openGet(urlStr, token);
            return readJsonResponse(conn);
        });

        List<DriveFile> list = new ArrayList<>();
        JSONArray files = result.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String id = f.getString("id");
                String name = f.getString("name");
                String mimeType = f.optString("mimeType", "");
                long size = f.optLong("size", 0L);
                boolean trashed = f.optBoolean("trashed", false);

                JSONObject appProps = f.optJSONObject("appProperties");
                String syncId = "";
                long updatedAt = 0L;
                if (appProps != null) {
                    syncId = appProps.optString("syncId", "");
                    updatedAt = appProps.optLong("updatedAt", 0L);
                }
                if (syncId.isEmpty() && name.contains("[") && name.contains("]")) {
                    try {
                        int start = name.lastIndexOf('[');
                        int end = name.lastIndexOf(']');
                        if (start >= 0 && end > start) {
                            syncId = name.substring(start + 1, end).trim();
                        }
                    } catch (Throwable ignored) {}
                }

                String modifiedTimeStr = f.optString("modifiedTime", "");
                if (updatedAt <= 0L && !modifiedTimeStr.isEmpty()) {
                    updatedAt = parseRfc3339(modifiedTimeStr);
                }

                list.add(new DriveFile(id, name, mimeType, size, syncId, updatedAt, trashed));
            }
        }
        return list;
    }

    /**
     * Uploads a local notebook .opennote archive to Google Drive inside the target folder.
     */
    @NonNull
    public DriveFile uploadNotebook(@NonNull Context context, @NonNull String folderId,
                                    @NonNull String syncId, @NonNull String title,
                                    long updatedAt, @NonNull File file) throws Exception {
        String boundary = "OpenNotes_Boundary_" + System.currentTimeMillis();
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.isEmpty()) safeTitle = "Notebook";
        String fileName = safeTitle + " [" + syncId + "].opennote";

        JSONObject metadata = new JSONObject();
        metadata.put("name", fileName);
        JSONArray parents = new JSONArray();
        parents.put(folderId);
        metadata.put("parents", parents);

        JSONObject appProps = new JSONObject();
        appProps.put("syncId", syncId);
        appProps.put("updatedAt", String.valueOf(updatedAt));
        metadata.put("appProperties", appProps);

        JSONObject result = executeWithAuth(context, (token) -> {
            HttpURLConnection conn = openPost(UPLOAD_API_BASE + "/files?uploadType=multipart&fields=id,name,mimeType,size,appProperties,trashed",
                    token, "multipart/related; boundary=" + boundary);
            writeMultipartBody(conn, boundary, metadata, file);
            return readJsonResponse(conn);
        });

        return new DriveFile(
                result.getString("id"),
                result.getString("name"),
                result.optString("mimeType", "application/zip"),
                file.length(),
                syncId,
                updatedAt,
                false
        );
    }

    /**
     * Updates an existing Google Drive notebook file with new binary data and metadata.
     */
    @NonNull
    public DriveFile updateNotebook(@NonNull Context context, @NonNull String fileId,
                                    @NonNull String syncId, @NonNull String title,
                                    long updatedAt, @NonNull File file) throws Exception {
        String boundary = "OpenNotes_Boundary_" + System.currentTimeMillis();
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.isEmpty()) safeTitle = "Notebook";
        String fileName = safeTitle + " [" + syncId + "].opennote";

        JSONObject metadata = new JSONObject();
        metadata.put("name", fileName);
        JSONObject appProps = new JSONObject();
        appProps.put("syncId", syncId);
        appProps.put("updatedAt", String.valueOf(updatedAt));
        metadata.put("appProperties", appProps);

        JSONObject result = executeWithAuth(context, (token) -> {
            HttpURLConnection conn = openConnection(UPLOAD_API_BASE + "/files/" + fileId + "?uploadType=multipart&fields=id,name,mimeType,size,appProperties,trashed",
                    "PATCH", token, "multipart/related; boundary=" + boundary);
            writeMultipartBody(conn, boundary, metadata, file);
            return readJsonResponse(conn);
        });

        return new DriveFile(
                result.getString("id"),
                result.getString("name"),
                result.optString("mimeType", "application/zip"),
                file.length(),
                syncId,
                updatedAt,
                false
        );
    }

    /**
     * Downloads a file from Google Drive directly to a local destination file atomically.
     */
    public void downloadFile(@NonNull Context context, @NonNull String fileId, @NonNull File destinationFile) throws Exception {
        File parent = destinationFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tempFile = new File(destinationFile.getAbsolutePath() + ".tmp_" + System.nanoTime());

        executeWithAuth(context, (token) -> {
            HttpURLConnection conn = openGet(DRIVE_API_BASE + "/files/" + fileId + "?alt=media", token);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Google Drive download error HTTP " + code + ": " + readError(conn));
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile), BUFFER_SIZE)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }
            return null;
        });

        if (destinationFile.exists()) {
            destinationFile.delete();
        }
        if (!tempFile.renameTo(destinationFile)) {
            // Fallback copy if renameTo fails across mounts
            try (InputStream in = new FileInputStream(tempFile);
                 OutputStream out = new FileOutputStream(destinationFile)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
            tempFile.delete();
        }
    }

    /**
     * Moves a Google Drive file to trash.
     */
    public void trashFile(@NonNull Context context, @NonNull String fileId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("trashed", true);

        executeWithAuth(context, (token) -> {
            HttpURLConnection conn = openConnection(DRIVE_API_BASE + "/files/" + fileId, "PATCH", token, "application/json; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code != 200 && code != 204) {
                Log.w(TAG, "Failed to trash Drive file " + fileId + ": HTTP " + code);
            }
            return null;
        });
    }

    // --- Helper Methods ---

    private interface AuthOperation<T> {
        T execute(String token) throws Exception;
    }

    private <T> T executeWithAuth(Context context, AuthOperation<T> op) throws Exception {
        String token = GoogleAuthManager.getInstance().getAccessToken(context);
        try {
            return op.execute(token);
        } catch (AuthExpiredException e) {
            Log.w(TAG, "OAuth access token expired, invalidating and re-trying once...");
            GoogleAuthManager.getInstance().invalidateToken(context);
            String freshToken = GoogleAuthManager.getInstance().getAccessToken(context);
            return op.execute(freshToken);
        }
    }

    private static class AuthExpiredException extends IOException {
        AuthExpiredException(String message) {
            super(message);
        }
    }

    private HttpURLConnection openGet(String urlStr, String token) throws IOException {
        return openConnection(urlStr, "GET", token, null);
    }

    private HttpURLConnection openPost(String urlStr, String token, String contentType) throws IOException {
        return openConnection(urlStr, "POST", token, contentType);
    }

    private HttpURLConnection openConnection(String urlStr, String method, String token, String contentType) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if ("PATCH".equalsIgnoreCase(method)) {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            conn.setRequestMethod(method);
        }
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
            conn.setDoOutput(true);
        }
        return conn;
    }

    private void writeMultipartBody(HttpURLConnection conn, String boundary, JSONObject metadata, File file) throws IOException {
        conn.setDoOutput(true);
        byte[] metaBytes = metadata.toString().getBytes(StandardCharsets.UTF_8);

        String part1Header = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n";
        String part2Header = "\r\n--" + boundary + "\r\n"
                + "Content-Type: application/zip\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        long totalLength = part1Header.getBytes(StandardCharsets.UTF_8).length
                + metaBytes.length
                + part2Header.getBytes(StandardCharsets.UTF_8).length
                + file.length()
                + footer.getBytes(StandardCharsets.UTF_8).length;

        conn.setFixedLengthStreamingMode(totalLength);

        try (OutputStream out = conn.getOutputStream()) {
            out.write(part1Header.getBytes(StandardCharsets.UTF_8));
            out.write(metaBytes);
            out.write(part2Header.getBytes(StandardCharsets.UTF_8));

            try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }

            out.write(footer.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private JSONObject readJsonResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        if (code == 401) {
            throw new AuthExpiredException("HTTP 401 Unauthorized from Google Drive API");
        }
        if (code < 200 || code >= 300) {
            String err = readError(conn);
            throw new IOException("Google Drive API error HTTP " + code + ": " + err);
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            String str = bos.toString(StandardCharsets.UTF_8.name());
            return str.trim().isEmpty() ? new JSONObject() : new JSONObject(str);
        }
    }

    private String readError(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream()) {
            if (in == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int len;
            while ((len = in.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable t) {
            return "Unable to read error stream: " + t.getMessage();
        }
    }

    public static long parseRfc3339(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return 0L;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                return java.time.Instant.parse(timestamp).toEpochMilli();
            }
        } catch (Throwable ignored) {}
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = sdf.parse(timestamp);
            if (d != null) return d.getTime();
        } catch (Throwable ignored) {}
        try {
            java.text.SimpleDateFormat sdf2 = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            sdf2.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d2 = sdf2.parse(timestamp);
            if (d2 != null) return d2.getTime();
        } catch (Throwable ignored) {}
        return 0L;
    }
}
