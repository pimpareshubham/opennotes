package org.opennotes.app.importer;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opennotes.app.db.NotesDbHelper;
import org.opennotes.app.model.ImageItem;
import org.opennotes.app.model.Notebook;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles seamless import of PDF documents into OpenNotes.
 * Renders each PDF page at high DPI as a background layer, preserving the original
 * aspect ratio and page geometry, and saves it as a full-featured, persistent Notebook.
 */
public final class PdfImporter {

    public interface ProgressListener {
        void onProgress(int current, int total);
    }

    private PdfImporter() {}

    /**
     * Imports a PDF from a given URI into a new Notebook.
     *
     * @param context Application context
     * @param pdfUri URI of the source PDF (from file picker or external app intent)
     * @param customTitle Optional title override (if null, extracted from file name)
     * @param listener Progress update listener
     * @return ID of the newly created Notebook
     * @throws Exception If file reading, rendering, or database storage fails
     */
    public static long importPdf(@NonNull Context context,
                                 @NonNull Uri pdfUri,
                                 @Nullable String customTitle,
                                 @Nullable ProgressListener listener) throws Exception {

        Context appContext = context.getApplicationContext();
        File tempFile = new File(appContext.getCacheDir(), "import_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".pdf");

        // 1. Copy the PDF content to a local temporary cache file
        try (InputStream is = appContext.getContentResolver().openInputStream(pdfUri);
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            if (is == null) {
                throw new IOException("Cannot open input stream for PDF: " + pdfUri);
            }
            byte[] buf = new byte[16384];
            int read;
            while ((read = is.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
            fos.flush();
        }

        // 2. Resolve human-readable title
        String resolvedTitle = customTitle;
        if (resolvedTitle == null || resolvedTitle.trim().isEmpty()) {
            resolvedTitle = extractPdfDisplayName(appContext, pdfUri);
        }
        if (resolvedTitle == null || resolvedTitle.trim().isEmpty()) {
            resolvedTitle = "Imported PDF";
        }

        // 3. Render pages with Android's native PdfRenderer
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageCount <= 0) {
                throw new IOException("The selected PDF document contains no pages.");
            }

            // Create target directory for rendered page bitmaps in app internal storage
            String assetFolder = "pdf_doc_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
            File docDir = new File(appContext.getFilesDir(), "notebook_assets/" + assetFolder);
            if (!docDir.exists() && !docDir.mkdirs()) {
                throw new IOException("Failed to create storage directory for PDF pages: " + docDir.getAbsolutePath());
            }

            List<Page> notebookPages = new ArrayList<>(pageCount);
            float standardCanvasWidth = 1440f;

            for (int i = 0; i < pageCount; i++) {
                if (listener != null) {
                    listener.onProgress(i + 1, pageCount);
                }

                PdfRenderer.Page pdfPage = renderer.openPage(i);
                int origW = pdfPage.getWidth();
                int origH = pdfPage.getHeight();

                if (origW <= 0) origW = 595;
                if (origH <= 0) origH = 842;

                // Scale canvas height proportionally to original page aspect ratio
                float canvasHeight = standardCanvasWidth * ((float) origH / (float) origW);

                // Ultra-HD Print-Grade Rendering: true 300 DPI (300 / 72 = 4.1667x) up to 3840px
                float dpiScale = 300f / 72f;
                float renderScale = Math.min(dpiScale, 3840f / Math.max(origW, origH));
                int bmpW = Math.max(1, Math.round(origW * renderScale));
                int bmpH = Math.max(1, Math.round(origH * renderScale));

                Bitmap bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE); // Base white page background

                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                pdfPage.close();

                // Compress rendered page to lossless PNG for original maximum possible quality
                File pageImgFile = new File(docDir, "page_" + i + ".png");
                try (FileOutputStream imgOut = new FileOutputStream(pageImgFile)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, imgOut);
                    imgOut.flush();
                }
                bitmap.recycle();

                // Build OpenNotes Page object
                Page page = new Page(0, i, PaperTemplate.BLANK, 0xFFFFFFFF, false);
                page.canvasHeight = canvasHeight;

                // Add background image item spanning the full page canvas
                ImageItem bgImage = new ImageItem(pageImgFile.getAbsolutePath(), 0f, 0f, standardCanvasWidth, canvasHeight, null, true);
                page.images.add(bgImage);
                notebookPages.add(page);
            }

            // 4. Save into SQLite database as a new notebook
            NotesDbHelper dbHelper = NotesDbHelper.getInstance(appContext);
            Notebook notebook = new Notebook(resolvedTitle, 0xFF0284C7, PaperTemplate.BLANK);
            notebook.defaultIsInfinite = false;
            notebook.defaultBgColor = 0xFFFFFFFF;
            notebook.pageCount = pageCount;

            long notebookId = dbHelper.importNotebookWithPages(notebook, notebookPages);
            return notebookId;

        } finally {
            if (renderer != null) {
                try { renderer.close(); } catch (Throwable ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Throwable ignored) {}
            }
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Appends pages of a PDF document to an existing Notebook.
     */
    public static int appendPdfToNotebook(@NonNull Context context,
                                          long notebookId,
                                          @NonNull Uri pdfUri,
                                          @Nullable ProgressListener listener) throws Exception {
        Context appContext = context.getApplicationContext();
        NotesDbHelper dbHelper = NotesDbHelper.getInstance(appContext);
        Notebook notebook = dbHelper.getNotebook(notebookId);
        if (notebook == null) {
            throw new IllegalArgumentException("Notebook with ID " + notebookId + " not found.");
        }

        List<Page> existingPages = dbHelper.getPagesForNotebook(notebookId);
        int startIndex = (existingPages != null) ? existingPages.size() : 0;

        File tempFile = new File(appContext.getCacheDir(), "append_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".pdf");
        try (InputStream is = appContext.getContentResolver().openInputStream(pdfUri);
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            if (is == null) throw new IOException("Cannot open input stream for PDF: " + pdfUri);
            byte[] buf = new byte[16384];
            int read;
            while ((read = is.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
            fos.flush();
        }

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageCount <= 0) return 0;

            String assetFolder = "pdf_doc_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
            File docDir = new File(appContext.getFilesDir(), "notebook_assets/" + assetFolder);
            if (!docDir.exists() && !docDir.mkdirs()) {
                throw new IOException("Failed to create storage directory: " + docDir.getAbsolutePath());
            }

            float standardCanvasWidth = 1440f;

            for (int i = 0; i < pageCount; i++) {
                if (listener != null) {
                    listener.onProgress(i + 1, pageCount);
                }

                PdfRenderer.Page pdfPage = renderer.openPage(i);
                int origW = pdfPage.getWidth();
                int origH = pdfPage.getHeight();
                if (origW <= 0) origW = 595;
                if (origH <= 0) origH = 842;

                float canvasHeight = standardCanvasWidth * ((float) origH / (float) origW);
                float dpiScale = 300f / 72f;
                float renderScale = Math.min(dpiScale, 3840f / Math.max(origW, origH));
                int bmpW = Math.max(1, Math.round(origW * renderScale));
                int bmpH = Math.max(1, Math.round(origH * renderScale));

                Bitmap bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                pdfPage.close();

                File pageImgFile = new File(docDir, "page_append_" + (startIndex + i) + ".png");
                try (FileOutputStream imgOut = new FileOutputStream(pageImgFile)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, imgOut);
                    imgOut.flush();
                }
                bitmap.recycle();

                Page page = new Page(notebookId, startIndex + i, PaperTemplate.BLANK, 0xFFFFFFFF, false);
                page.canvasHeight = canvasHeight;

                ImageItem bgImage = new ImageItem(pageImgFile.getAbsolutePath(), 0f, 0f, standardCanvasWidth, canvasHeight, null, true);
                page.images.add(bgImage);

                dbHelper.insertPage(page);
            }

            notebook.pageCount = startIndex + pageCount;
            notebook.updatedAt = System.currentTimeMillis();
            dbHelper.updateNotebook(notebook);
            return pageCount;

        } finally {
            if (renderer != null) {
                try { renderer.close(); } catch (Throwable ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Throwable ignored) {}
            }
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private static String extractPdfDisplayName(Context context, Uri uri) {
        String displayName = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex);
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = uri.getLastPathSegment();
        }

        if (displayName == null || displayName.trim().isEmpty()) {
            return "Imported PDF";
        }

        if (displayName.toLowerCase().endsWith(".pdf")) {
            displayName = displayName.substring(0, displayName.length() - 4);
        }

        displayName = displayName.replace('_', ' ').replace('-', ' ').trim();
        return displayName.isEmpty() ? "Imported PDF" : displayName;
    }
}
