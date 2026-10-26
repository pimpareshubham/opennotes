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

    // Cache the active PdfRenderer and file descriptor so multi-page PDFs (e.g. 1000 pages)
    // are parsed once and kept open across pages, rendering each page in ~2-3ms instead of 50-100ms.
    private static String sCachedPdfPath = null;
    private static ParcelFileDescriptor sCachedPfd = null;
    private static PdfRenderer sCachedRenderer = null;

    private PdfPageRenderer() {}

    /**
     * Closes and releases any currently cached PdfRenderer and ParcelFileDescriptor.
     * Safe to call from UI or background threads.
     */
    public static synchronized void closeRenderer() {
        if (sCachedRenderer != null) {
            try { sCachedRenderer.close(); } catch (Throwable ignored) {}
            sCachedRenderer = null;
        }
        if (sCachedPfd != null) {
            try { sCachedPfd.close(); } catch (Throwable ignored) {}
            sCachedPfd = null;
        }
        sCachedPdfPath = null;
    }

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

        PdfRenderer.Page page = null;
        try {
            if (sCachedRenderer != null && !pdfPath.equals(sCachedPdfPath)) {
                closeRenderer();
            }
            if (sCachedRenderer == null) {
                sCachedPfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                sCachedRenderer = new PdfRenderer(sCachedPfd);
                sCachedPdfPath = pdfPath;
            }
            PdfRenderer renderer = sCachedRenderer;
            if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                Log.w(TAG, "Invalid page index: " + pageIndex + ", total: " + renderer.getPageCount());
                return null;
            }
            page = renderer.openPage(pageIndex);
            int origW = page.getWidth();
            int origH = page.getHeight();
            if (origW <= 0) origW = 595;
            if (origH <= 0) origH = 842;

            // Target dimensions for crisp rendering without artificial quality limits
            float scale;
            if (targetWidth > 0) {
                scale = (float) targetWidth / origW;
            } else {
                // High-resolution baseline: 300 DPI or 2880px wide
                scale = Math.max(2880f / origW, 300f / 72f);
            }
            int bmpW = Math.max(1, Math.round(origW * scale));
            int bmpH = Math.max(1, Math.round(origH * scale));

            Bitmap bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, renderMode);
            return bitmap;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to render PDF page on demand: " + reference, t);
            // On failure, clear cached renderer so subsequent attempts start freshly
            closeRenderer();
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Renders an exact sub-region of a PDF page at exact screen pixel density.
     * Used for high-zoom (e.g. 753%) vector viewport rendering with zero blurriness.
     */
    @Nullable
    public static synchronized Bitmap renderPageRegion(String reference,
                                                       int destWidth, int destHeight,
                                                       float srcLeft, float srcTop,
                                                       float srcRight, float srcBottom,
                                                       float pageWidthUnits, float pageHeightUnits,
                                                       int renderMode) {
        if (destWidth <= 0 || destHeight <= 0) return null;
        String pdfPath = extractPdfPath(reference);
        int pageIndex = extractPageIndex(reference);

        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists() || !pdfFile.canRead()) {
            Log.w(TAG, "PDF file does not exist or cannot be read: " + pdfPath);
            return null;
        }

        PdfRenderer.Page page = null;
        try {
            if (sCachedRenderer != null && !pdfPath.equals(sCachedPdfPath)) {
                closeRenderer();
            }
            if (sCachedRenderer == null) {
                sCachedPfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                sCachedRenderer = new PdfRenderer(sCachedPfd);
                sCachedPdfPath = pdfPath;
            }
            PdfRenderer renderer = sCachedRenderer;
            if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                Log.w(TAG, "Invalid page index: " + pageIndex + ", total: " + renderer.getPageCount());
                return null;
            }
            page = renderer.openPage(pageIndex);
            int origW = page.getWidth();
            int origH = page.getHeight();
            if (origW <= 0) origW = 595;
            if (origH <= 0) origH = 842;

            // Map canvas source rect to PDF point coordinates [0..origW, 0..origH]
            float unitToPdfX = (pageWidthUnits > 0) ? ((float) origW / pageWidthUnits) : 1f;
            float unitToPdfY = (pageHeightUnits > 0) ? ((float) origH / pageHeightUnits) : 1f;

            float pdfSrcL = Math.max(0f, srcLeft * unitToPdfX);
            float pdfSrcT = Math.max(0f, srcTop * unitToPdfY);
            float pdfSrcR = Math.min((float) origW, srcRight * unitToPdfX);
            float pdfSrcB = Math.min((float) origH, srcBottom * unitToPdfY);

            float pdfSrcW = pdfSrcR - pdfSrcL;
            float pdfSrcH = pdfSrcB - pdfSrcT;
            if (pdfSrcW <= 0.01f || pdfSrcH <= 0.01f) return null;

            int bmpW = Math.max(1, destWidth);
            int bmpH = Math.max(1, destHeight);

            Bitmap bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);

            // Matrix transforms PDF page space [pdfSrcL..pdfSrcR, pdfSrcT..pdfSrcB] to bitmap space [0..bmpW, 0..bmpH]
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postTranslate(-pdfSrcL, -pdfSrcT);
            matrix.postScale((float) bmpW / pdfSrcW, (float) bmpH / pdfSrcH);

            page.render(bitmap, null, matrix, renderMode);
            return bitmap;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to render PDF page region: " + reference, t);
            closeRenderer();
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
