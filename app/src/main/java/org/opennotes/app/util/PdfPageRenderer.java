package org.opennotes.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * On-demand rendering engine for native PDF documents in OpenNotes.
 * Allows pages of imported PDFs to be displayed dynamically at crisp resolution
 * without having to convert or store them as separate PNG files on disk.
 */
public final class PdfPageRenderer {
    private static final String TAG = "PdfPageRenderer";
    public static final String PAGE_SEPARATOR = "#page=";

    private PdfPageRenderer() {}

    /**
     * Checks if a given image path references an on-demand PDF page.
     */
    public static boolean isPdfPageReference(@Nullable String path) {
        if (path == null) return false;
        return path.contains(PAGE_SEPARATOR) || path.toLowerCase(java.util.Locale.US).endsWith(".pdf");
    }

    /**
     * Extracts the underlying .pdf file path from a page reference.
     */
    public static String extractPdfPath(@Nullable String reference) {
        if (reference == null) return "";
        int sepIdx = reference.indexOf(PAGE_SEPARATOR);
        if (sepIdx != -1) {
            return reference.substring(0, sepIdx);
        }
        return reference;
    }

    /**
     * Extracts the 0-based page index from a page reference.
     */
    public static int extractPageIndex(@Nullable String reference) {
        if (reference == null) return 0;
        int sepIdx = reference.indexOf(PAGE_SEPARATOR);
        if (sepIdx != -1) {
            try {
                return Integer.parseInt(reference.substring(sepIdx + PAGE_SEPARATOR.length()));
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public static String cleanPdfFilePath(@Nullable String reference) {
        return extractPdfPath(reference);
    }

    public static int parsePageNumber(@Nullable String reference) {
        return extractPageIndex(reference);
    }

    /**
     * Constructs a page reference string linking a PDF file and page index.
     */
    public static String buildReference(String pdfFilePath, int pageIndex) {
        return pdfFilePath + PAGE_SEPARATOR + pageIndex;
    }

    /**
     * Renders a single PDF page into an ARGB_8888 Bitmap for display on canvas.
     */
    @Nullable
    public static Bitmap renderPage(Context context, String reference, int targetWidth, int targetHeight) {
        return renderPageInternal(reference, targetWidth, targetHeight, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
    }

    /**
     * Renders a single PDF page into an ARGB_8888 Bitmap for print/export.
     */
    @Nullable
    public static Bitmap renderPageForExport(Context context, String reference, int targetWidth, int targetHeight) {
        return renderPageInternal(reference, targetWidth, targetHeight, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
    }

    @Nullable
    private static synchronized Bitmap renderPageInternal(String reference, int targetWidth, int targetHeight, int renderMode) {
        String pdfPath = extractPdfPath(reference);
        int pageIndex = extractPageIndex(reference);

        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists() || !pdfFile.canRead()) {
            Log.w(TAG, "PDF file does not exist or cannot be read: " + pdfPath);
            return null;
        }

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                Log.w(TAG, "Invalid page index: " + pageIndex + ", total: " + renderer.getPageCount());
                return null;
            }
            page = renderer.openPage(pageIndex);
            int origW = page.getWidth();
            int origH = page.getHeight();
            if (origW <= 0) origW = 595;
            if (origH <= 0) origH = 842;

            // Target dimensions for crisp rendering
            float scale;
            if (targetWidth > 0) {
                scale = (float) targetWidth / origW;
            } else {
                scale = 1440f / origW;
            }
            // Limit max dimension to 2560px for memory safety on mobile GPU
            float maxDim = Math.max(origW * scale, origH * scale);
            if (maxDim > 2560f) {
                scale = 2560f / Math.max(origW, origH);
            }
            int bmpW = Math.max(1, Math.round(origW * scale));
            int bmpH = Math.max(1, Math.round(origH * scale));

            Bitmap bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, renderMode);
            return bitmap;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to render PDF page on demand: " + reference, t);
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Throwable ignored) {}
            }
            if (renderer != null) {
                try { renderer.close(); } catch (Throwable ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
