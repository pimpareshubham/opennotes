package org.opennotes.app.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opennotes.app.db.AppSettings;
import org.opennotes.app.db.NotesDbHelper;
import org.opennotes.app.export.NativeNoteArchive;
import org.opennotes.app.model.Notebook;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates background and manual synchronization of notebooks with Google Drive.
 */
public final class DriveSyncManager {
    private static final String TAG = "DriveSyncManager";
    private static final long DEBOUNCE_INTERVAL_MS = 10000L;
    private static final long REMOTE_POLL_INTERVAL_MS = 5 * 60 * 1000L;

    public interface SyncListener {
        void onSyncStarted();
        void onSyncProgress(String stage, int current, int total);
        void onSyncCompleted(int uploadedCount, int downloadedCount, int deletedCount);
        void onSyncError(String errorMessage);
    }

    private static DriveSyncManager sInstance;

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final List<SyncListener> listeners = new CopyOnWriteArrayList<>();
    private long lastSyncAttemptTime = 0L;
    private volatile long syncStartTime = 0L;
    private volatile String currentStage = "Synchronizing with Google Drive…";
    private volatile int currentProgress = 0;
    private volatile int currentTotal = 0;

    private DriveSyncManager() {}

    public static synchronized DriveSyncManager getInstance() {
        if (sInstance == null) {
            sInstance = new DriveSyncManager();
        }
        return sInstance;
    }

    public boolean isSyncing() {
        return isSyncing.get();
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public int getCurrentProgress() {
        return currentProgress;
    }

    public int getCurrentTotal() {
        return currentTotal;
    }

    public void addListener(@NonNull SyncListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(@NonNull SyncListener listener) {
        listeners.remove(listener);
    }

    /**
     * Checks if there are pending local changes (new, edited, or deleted notebooks)
     * that require uploading or syncing to Google Drive.
     */
    public boolean hasPendingLocalChanges(@NonNull Context context) {
        AppSettings settings = AppSettings.getInstance(context);
        Set<String> deleted = settings.getDeletedSyncIds();
        if (deleted != null && !deleted.isEmpty()) {
            return true;
        }
        NotesDbHelper db = NotesDbHelper.getInstance(context);
        List<Notebook> list = db.getAllNotebooks("All", false, null);
        for (Notebook nb : list) {
            if (nb == null) continue;
            if (nb.syncId == null || nb.syncId.trim().isEmpty()) {
                return true;
            }
            long lastSynced = settings.getNotebookLastSyncedTime(nb.syncId);
            if (nb.updatedAt > lastSynced) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schedules an automatic background sync (e.g., triggered on note save, note exit, or app resume).
     * Avoids redundant syncs: runs only if there are pending local changes or if the periodic
     * remote polling interval (5 minutes) has elapsed.
     */
    public void scheduleAutoSync(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        AppSettings settings = AppSettings.getInstance(appContext);
        if (!settings.isGoogleSignedIn() || !settings.isGoogleAutoSyncEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSyncAttemptTime < DEBOUNCE_INTERVAL_MS) {
            return;
        }

        boolean hasLocalChanges = hasPendingLocalChanges(appContext);
        long lastSyncTime = settings.getGoogleLastSyncTime();
        boolean remotePollDue = (now - lastSyncTime) > REMOTE_POLL_INTERVAL_MS;

        if (!hasLocalChanges && !remotePollDue) {
            // Nothing changed locally and remote was checked recently. No redundant sync!
            return;
        }

        syncNow(appContext, false, null);
    }

    /**
     * Executes synchronization immediately on a background thread.
     */
    public void syncNow(@NonNull Context context, boolean isUserInitiated, @Nullable SyncListener singleListener) {
        Context appContext = context.getApplicationContext();
        if (!GoogleAuthManager.getInstance().isSignedIn(appContext)) {
            if (isUserInitiated) {
                notifyError("Please sign in with Google to synchronize.", singleListener);
            }
            return;
        }

        long now = System.currentTimeMillis();
        // Safety watchdog: reset if previous sync has been running for > 90 seconds
        if (isSyncing.get() && (now - syncStartTime > 90000L)) {
            Log.w(TAG, "Sync operation exceeded 90s safety timeout, resetting lock.");
            isSyncing.set(false);
        }

        if (!isSyncing.compareAndSet(false, true)) {
            Log.i(TAG, "Sync already in progress, attaching listener to active session.");
            if (singleListener != null) {
                addListener(singleListener);
                final String stg = currentStage;
                final int cur = currentProgress;
                final int tot = currentTotal;
                mainHandler.post(() -> singleListener.onSyncProgress(stg, cur, tot));
            }
            return;
        }

        syncStartTime = now;
        lastSyncAttemptTime = now;
        currentStage = "Starting synchronization…";
        currentProgress = 0;
        currentTotal = 0;

        if (singleListener != null) {
            addListener(singleListener);
        }
        notifyStarted(singleListener);

        try {
            syncExecutor.execute(() -> {
                int uploadedCount = 0;
                int downloadedCount = 0;
                int deletedCount = 0;
                String syncError = null;

                try {
                notifyProgress("Connecting to Google Drive…", 0, 0, singleListener);

                // 1. Locate or create OpenNotes folder in Google Drive root
                String folderId = GoogleDriveApiClient.getInstance().getOrCreateOpenNotesFolder(appContext);

                notifyProgress("Scanning Google Drive notes…", 0, 0, singleListener);

                // 2. Fetch remote files in the OpenNotes folder
                List<GoogleDriveApiClient.DriveFile> remoteFiles =
                        GoogleDriveApiClient.getInstance().listFolderFiles(appContext, folderId);

                // Index remote files strictly by immutable syncId.
                // Distinct notebooks can share the same display title, but each has a unique syncId.
                // If the exact same syncId exists multiple times on Drive (e.g. from an accidental double upload),
                // keep the newest version and trash the obsolete duplicate with that same syncId.
                Map<String, GoogleDriveApiClient.DriveFile> remoteBySyncId = new HashMap<>();

                for (GoogleDriveApiClient.DriveFile rf : remoteFiles) {
                    if (rf == null || rf.name == null || !rf.name.endsWith(".opennote")) continue;
                    if (rf.syncId == null || rf.syncId.trim().isEmpty()) continue;

                    GoogleDriveApiClient.DriveFile existing = remoteBySyncId.get(rf.syncId);
                    if (existing != null) {
                        if (rf.updatedAt > existing.updatedAt) {
                            try { GoogleDriveApiClient.getInstance().trashFile(appContext, existing.id); } catch (Exception ignored) {}
                            remoteBySyncId.put(rf.syncId, rf);
                        } else {
                            try { GoogleDriveApiClient.getInstance().trashFile(appContext, rf.id); } catch (Exception ignored) {}
                        }
                    } else {
                        remoteBySyncId.put(rf.syncId, rf);
                    }
                }

                // 3. Process locally deleted notebooks
                AppSettings settings = AppSettings.getInstance(appContext);
                Set<String> deletedSyncIds = settings.getDeletedSyncIds();
                for (String deletedSyncId : deletedSyncIds) {
                    GoogleDriveApiClient.DriveFile remote = remoteBySyncId.remove(deletedSyncId);
                    if (remote != null) {
                        try {
                            GoogleDriveApiClient.getInstance().trashFile(appContext, remote.id);
                            deletedCount++;
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to trash remote file: " + e.getMessage());
                        }
                    }
                    settings.clearDeletedSyncId(deletedSyncId);
                    settings.clearNotebookLastSyncedTime(deletedSyncId);
                }

                // 4. Compare and sync local notebooks to Google Drive
                NotesDbHelper db = NotesDbHelper.getInstance(appContext);
                db.deduplicateNotebooks();
                List<Notebook> localNotebooks = db.getAllNotebooks("All", false, null);
                int totalLocal = localNotebooks.size();

                // Resolve the same title-based legacy matches used below before calculating
                // progress. Remote files that belong to local notebooks are already covered
                // by the local pass and must not be counted a second time.
                Set<String> matchedRemoteSyncIds = new HashSet<>();
                for (Notebook localNb : localNotebooks) {
                    if (localNb == null) continue;
                    if (localNb.syncId == null || localNb.syncId.trim().isEmpty()) {
                        localNb.syncId = UUID.randomUUID().toString();
                        db.updateNotebookSyncId(localNb.id, localNb.syncId);
                    }

                    GoogleDriveApiClient.DriveFile matchedRemote = remoteBySyncId.get(localNb.syncId);
                    if (matchedRemote == null && localNb.title != null && !localNb.title.trim().isEmpty()) {
                        for (GoogleDriveApiClient.DriveFile rf : remoteBySyncId.values()) {
                            if (matchedRemoteSyncIds.contains(rf.syncId)) continue;
                            String rfTitle = extractTitleFromFileName(rf.name, rf.syncId);
                            if (localNb.title.trim().equalsIgnoreCase(rfTitle.trim())) {
                                matchedRemote = rf;
                                localNb.syncId = rf.syncId;
                                db.updateNotebookSyncId(localNb.id, rf.syncId);
                                break;
                            }
                        }
                    }
                    if (matchedRemote != null) matchedRemoteSyncIds.add(matchedRemote.syncId);
                }

                int totalSteps = Math.max(1, totalLocal + remoteBySyncId.size() - matchedRemoteSyncIds.size());
                Set<String> processedSyncIds = new HashSet<>();

                for (int i = 0; i < totalLocal; i++) {
                    Notebook localNb = localNotebooks.get(i);
                    if (localNb == null) continue;

                    int stepNum = i + 1;
                    GoogleDriveApiClient.DriveFile remoteFile = remoteBySyncId.get(localNb.syncId);
                    // If remote file not found by syncId, check if a remote file matches by title so we link them instead of creating a duplicate
                    if (remoteFile == null && localNb.title != null && !localNb.title.trim().isEmpty()) {
                        for (GoogleDriveApiClient.DriveFile rf : remoteBySyncId.values()) {
                            if (processedSyncIds.contains(rf.syncId)) continue;
                            String rfTitle = extractTitleFromFileName(rf.name, rf.syncId);
                            if (localNb.title.trim().equalsIgnoreCase(rfTitle.trim())) {
                                remoteFile = rf;
                                localNb.syncId = rf.syncId;
                                db.updateNotebookSyncId(localNb.id, rf.syncId);
                                break;
                            }
                        }
                    }

                    processedSyncIds.add(localNb.syncId);
                    long lastSynced = settings.getNotebookLastSyncedTime(localNb.syncId);

                    if (remoteFile == null) {
                        // Notebook does not exist in Drive -> upload
                        notifyProgress("Uploading \"" + localNb.title + "\" (" + stepNum + "/" + totalSteps + ")…",
                                stepNum, totalSteps, singleListener);
                        File tempArchive = null;
                        try {
                            tempArchive = NativeNoteArchive.exportNotebookToTempArchive(appContext, db, localNb);
                            GoogleDriveApiClient.DriveFile uploaded = GoogleDriveApiClient.getInstance().uploadNotebook(appContext, folderId,
                                    localNb.syncId, localNb.title, localNb.updatedAt, tempArchive);
                            uploadedCount++;
                            long syncTimestamp = Math.max(localNb.updatedAt, uploaded.updatedAt);
                            settings.setNotebookLastSyncedTime(localNb.syncId, syncTimestamp);
                            db.touchNotebookUpdatedAt(localNb.id, syncTimestamp);
                            localNb.updatedAt = syncTimestamp;
                            remoteBySyncId.put(localNb.syncId, uploaded);
                        } finally {
                            if (tempArchive != null && tempArchive.exists()) {
                                tempArchive.delete();
                            }
                        }
                    } else {
                        // Notebook exists in both -> compare timestamps and dirty state
                        long localUpdated = localNb.updatedAt;
                        long remoteUpdated = remoteFile.updatedAt;

                        boolean remoteIsNewer = remoteUpdated > localUpdated + 2000L && remoteUpdated > lastSynced + 2000L;
                        boolean localIsNewer = localUpdated > remoteUpdated + 2000L;
                        boolean localDirty = localUpdated > lastSynced + 1000L;

                        if (remoteIsNewer) {
                            // Remote is newer -> download and update local notebook
                            notifyProgress("Downloading updated \"" + localNb.title + "\" (" + stepNum + "/" + totalSteps + ")…",
                                    stepNum, totalSteps, singleListener);
                            File tempDown = new File(appContext.getCacheDir(), "down_" + localNb.syncId + "_" + System.nanoTime() + ".tmp");
                            try {
                                GoogleDriveApiClient.getInstance().downloadFile(appContext, remoteFile.id, tempDown);
                                try (FileInputStream fis = new FileInputStream(tempDown)) {
                                    NativeNoteArchive.importNotebook(appContext, db, fis, localNb.syncId, remoteUpdated);
                                    downloadedCount++;
                                    long syncTimestamp = Math.max(localUpdated, remoteUpdated);
                                    settings.setNotebookLastSyncedTime(localNb.syncId, syncTimestamp);
                                    db.touchNotebookUpdatedAt(localNb.id, syncTimestamp);
                                    localNb.updatedAt = syncTimestamp;
                                }
                            } finally {
                                if (tempDown.exists()) tempDown.delete();
                            }
                        } else if (localDirty && localIsNewer) {
                            // Local was modified since last sync and is newer than remote -> update remote file
                            notifyProgress("Updating \"" + localNb.title + "\" in Drive (" + stepNum + "/" + totalSteps + ")…",
                                    stepNum, totalSteps, singleListener);
                            File tempArchive = null;
                            try {
                                tempArchive = NativeNoteArchive.exportNotebookToTempArchive(appContext, db, localNb);
                                GoogleDriveApiClient.DriveFile updatedFile = GoogleDriveApiClient.getInstance().updateNotebook(appContext, remoteFile.id,
                                        localNb.syncId, localNb.title, localNb.updatedAt, tempArchive);
                                uploadedCount++;
                                long syncTimestamp = Math.max(localNb.updatedAt, updatedFile.updatedAt);
                                settings.setNotebookLastSyncedTime(localNb.syncId, syncTimestamp);
                                db.touchNotebookUpdatedAt(localNb.id, syncTimestamp);
                                localNb.updatedAt = syncTimestamp;
                                remoteBySyncId.put(localNb.syncId, updatedFile);
                            } finally {
                                if (tempArchive != null && tempArchive.exists()) {
                                    tempArchive.delete();
                                }
                            }
                        } else {
                            // Both are already synchronized; report progress so progress bar advances
                            notifyProgress("Checking \"" + localNb.title + "\" (" + stepNum + "/" + totalSteps + ")…",
                                    stepNum, totalSteps, singleListener);
                            long syncTimestamp = Math.max(localUpdated, remoteUpdated);
                            if (lastSynced < syncTimestamp) {
                                settings.setNotebookLastSyncedTime(localNb.syncId, syncTimestamp);
                            }
                            Log.d(TAG, "Notebook \"" + localNb.title + "\" is in sync, skipping redundant transfer.");
                        }
                    }
                }

                // 5. Download remote notebooks created on other devices
                int remoteIndex = 0;
                for (GoogleDriveApiClient.DriveFile rf : remoteBySyncId.values()) {
                    if (rf == null || rf.syncId == null || rf.syncId.trim().isEmpty()) continue;
                    if (processedSyncIds.contains(rf.syncId)) continue;

                    if (deletedSyncIds.contains(rf.syncId)) {
                        // Was deleted locally, trash remote
                        try {
                            GoogleDriveApiClient.getInstance().trashFile(appContext, rf.id);
                            deletedCount++;
                        } catch (Exception ignored) {}
                    } else {
                        String rfTitle = extractTitleFromFileName(rf.name, rf.syncId);

                        // Download note
                        remoteIndex++;
                        int currentStep = totalLocal + remoteIndex;
                        notifyProgress("Downloading note: \"" + (rfTitle.isEmpty() ? rf.name : rfTitle) + "\" (" + currentStep + "/" + totalSteps + ")…",
                                currentStep, totalSteps, singleListener);
                        File tempDown = new File(appContext.getCacheDir(), "import_" + System.nanoTime() + ".tmp");
                        try {
                            GoogleDriveApiClient.getInstance().downloadFile(appContext, rf.id, tempDown);
                            try (FileInputStream fis = new FileInputStream(tempDown)) {
                                NativeNoteArchive.importNotebook(appContext, db, fis, rf.syncId, rf.updatedAt);
                                downloadedCount++;
                                long syncTimestamp = rf.updatedAt > 0L ? rf.updatedAt : System.currentTimeMillis();
                                settings.setNotebookLastSyncedTime(rf.syncId, syncTimestamp);
                                processedSyncIds.add(rf.syncId);
                            }
                        } finally {
                            if (tempDown.exists()) tempDown.delete();
                        }
                    }
                }

                notifyProgress("Finalizing synchronization…", totalSteps, totalSteps, singleListener);

                // 6. Record success
                long nowSuccess = System.currentTimeMillis();
                settings.setGoogleLastSyncTime(nowSuccess);
                String statusMsg;
                if (uploadedCount > 0 || downloadedCount > 0 || deletedCount > 0) {
                    statusMsg = "Synced (" + uploadedCount + " uploaded, " + downloadedCount + " downloaded"
                            + (deletedCount > 0 ? ", " + deletedCount + " deleted" : "") + ")";
                } else {
                    statusMsg = "All notes up to date";
                }
                settings.setGoogleLastSyncStatus(statusMsg);

                Log.i(TAG, "Google Drive sync completed: " + statusMsg);

            } catch (Throwable t) {
                Log.e(TAG, "Google Drive synchronization failed", t);
                syncError = t.getMessage() != null ? t.getMessage() : "Sync failed";
                AppSettings.getInstance(appContext).setGoogleLastSyncStatus("Failed: " + syncError);
            } finally {
                isSyncing.set(false);
            }

            if (syncError == null) {
                notifyCompleted(uploadedCount, downloadedCount, deletedCount, singleListener);
            } else {
                notifyError(syncError, singleListener);
            }
        });
        } catch (Throwable t) {
            isSyncing.set(false);
            notifyError("Could not start background sync: " + t.getMessage(), singleListener);
        }
    }

    private void notifyStarted(@Nullable SyncListener singleListener) {
        currentStage = "Starting synchronization…";
        currentProgress = 0;
        currentTotal = 0;
        mainHandler.post(() -> {
            if (singleListener != null) {
                try {
                    singleListener.onSyncStarted();
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking singleListener.onSyncStarted", t);
                }
            }
            for (SyncListener l : listeners) {
                try {
                    l.onSyncStarted();
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking listener.onSyncStarted", t);
                }
            }
        });
    }

    private void notifyProgress(String stage, int current, int total, @Nullable SyncListener singleListener) {
        currentStage = stage;
        currentProgress = current;
        currentTotal = total;
        mainHandler.post(() -> {
            if (singleListener != null) {
                try {
                    singleListener.onSyncProgress(stage, current, total);
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking singleListener.onSyncProgress", t);
                }
            }
            for (SyncListener l : listeners) {
                try {
                    l.onSyncProgress(stage, current, total);
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking listener.onSyncProgress", t);
                }
            }
        });
    }

    private void notifyCompleted(int uploaded, int downloaded, int deleted, @Nullable SyncListener singleListener) {
        currentStage = "All notes up to date";
        currentProgress = Math.max(1, currentTotal);
        mainHandler.post(() -> {
            if (singleListener != null) {
                try {
                    singleListener.onSyncCompleted(uploaded, downloaded, deleted);
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking singleListener.onSyncCompleted", t);
                }
            }
            for (SyncListener l : listeners) {
                try {
                    l.onSyncCompleted(uploaded, downloaded, deleted);
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking listener.onSyncCompleted", t);
                }
            }
        });
    }

    private void notifyError(String error, @Nullable SyncListener singleListener) {
        currentStage = "Failed: " + error;
        mainHandler.post(() -> {
            if (singleListener != null) {
                try {
                    singleListener.onSyncError(error);
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking singleListener.onSyncError", t);
                }
            }
            for (SyncListener l : listeners) {
                try {
                    l.onSyncError(error);
                } catch (Throwable t) {
                    Log.w(TAG, "Error invoking listener.onSyncError", t);
                }
            }
        });
    }

    public static String extractTitleFromFileName(String fileName, String syncId) {
        if (fileName == null) return "";
        String name = fileName;
        if (name.endsWith(".opennote")) {
            name = name.substring(0, name.length() - ".opennote".length());
        }
        if (syncId != null && !syncId.isEmpty() && name.endsWith("[" + syncId + "]")) {
            name = name.substring(0, name.length() - ("[" + syncId + "]").length());
        } else {
            int lastBracket = name.lastIndexOf('[');
            if (lastBracket > 0) {
                name = name.substring(0, lastBracket);
            }
        }
        return name.trim();
    }
}
