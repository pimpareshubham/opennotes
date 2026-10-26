package org.opennotes.app.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;

import org.opennotes.app.db.NotesDbHelper;
import org.opennotes.app.ai.PageWritingContext;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.ui.PaintBrushRenderer;
import org.opennotes.app.model.ImageItem;
import org.opennotes.app.model.Notebook;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.model.Stroke;
import org.opennotes.app.model.TextItem;
import org.opennotes.app.util.PdfPageRenderer;
import org.opennotes.app.util.TextSpacingCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class PdfExporter {

    public static File exportNotebookToPdf(Context context, long notebookId) throws IOException {
        NotesDbHelper db = NotesDbHelper.getInstance(context);
        Notebook nb = db.getNotebook(notebookId);
        List<Page> pages = db.getPagesForNotebook(notebookId);

        // Ultra-HD RAW 300 DPI A4 base dimensions (2480 x 3508)
        int defaultPageWidth = 2480;

        PdfDocument document = new PdfDocument();

        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            RectF bounds = calculatePageContentBounds(page);
            float contentW = bounds.width();
            float contentH = bounds.height();

            int pageWidth;
            int pageHeight;
            if (contentW > contentH) {
                // Landscape orientation
                pageWidth = 3508;
                pageHeight = Math.max(1200, (int) Math.ceil(pageWidth * (contentH / contentW)));
            } else {
                // Portrait orientation
                pageWidth = defaultPageWidth;
                pageHeight = Math.max(1200, (int) Math.ceil(pageWidth * (contentH / contentW)));
            }

            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create();
            PdfDocument.Page pdfPage = document.startPage(pageInfo);
            Canvas canvas = pdfPage.getCanvas();

            // Background color
            int bgColor = (page.backgroundColor != 0) ? page.backgroundColor : 0xFFFFFFFF;
            canvas.drawColor(bgColor);

            boolean isDark = isColorDark(bgColor);

            // Calculate uniform scaling & centering to guarantee 100% of canvas content is included
            float scaleX = (float) pageWidth / contentW;
            float scaleY = (float) pageHeight / contentH;
            float uniformScale = Math.min(scaleX, scaleY);

            float extraX = (pageWidth - contentW * uniformScale) * 0.5f;
            float extraY = (pageHeight - contentH * uniformScale) * 0.5f;

            canvas.save();
            canvas.translate(extraX, extraY);
            canvas.scale(uniformScale, uniformScale);
            canvas.translate(-bounds.left, -bounds.top);

            // Draw template background in canvas coordinates so lines register exactly with notes
            drawTemplateInCanvasSpace(canvas, page.template, bounds, isDark, extraX, extraY, uniformScale);

            // 1. Draw Images (background PDF layers first, then foreground stickers)
            if (page.images != null) {
                Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                for (ImageItem img : page.images) {
                    if (img != null && img.isBackground && img.imagePath != null) {
                        try {
                            Bitmap bmp;
                            if (PdfPageRenderer.isPdfPageReference(img.imagePath)) {
                                int targetExpW = Math.max(pageWidth, Math.round(img.width * uniformScale));
                                int targetExpH = Math.max(pageHeight, Math.round(img.height * uniformScale));
                                bmp = PdfPageRenderer.renderPageForExport(context, img.imagePath, targetExpW, targetExpH);
                            } else {
                                File imgFile = new File(img.imagePath);
                                if (!imgFile.exists()) continue;
                                bmp = BitmapFactory.decodeFile(img.imagePath);
                            }
                            if (bmp != null) {
                                canvas.drawBitmap(bmp, null, img.getBounds(), imagePaint);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                for (ImageItem img : page.images) {
                    if (img != null && !img.isBackground && img.imagePath != null) {
                        File imgFile = new File(img.imagePath);
                        if (!imgFile.exists()) continue;
                        try {
                            Bitmap bmp = BitmapFactory.decodeFile(img.imagePath);
                            if (bmp != null) {
                                canvas.drawBitmap(bmp, null, img.getBounds(), imagePaint);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            // 2. Draw Shapes
            if (page.shapes != null) {
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeCap(Paint.Cap.ROUND);
                sp.setStrokeJoin(Paint.Join.ROUND);

                for (ShapeItem s : page.shapes) {
                    if (s == null) continue;
                    sp.setColor(s.color);
                    sp.setStrokeWidth(s.strokeWidth);

                    switch (s.type) {
                        case LINE:
                            canvas.drawLine(s.startX, s.startY, s.endX, s.endY, sp);
                            break;
                        case ARROW:
                            canvas.drawLine(s.startX, s.startY, s.endX, s.endY, sp);
                            drawArrowHead(canvas, s.startX, s.startY, s.endX, s.endY, sp);
                            break;
                        case RECTANGLE:
                            canvas.drawRect(Math.min(s.startX, s.endX), Math.min(s.startY, s.endY),
                                    Math.max(s.startX, s.endX), Math.max(s.startY, s.endY), sp);
                            break;
                        case CIRCLE:
                            canvas.drawOval(new RectF(Math.min(s.startX, s.endX), Math.min(s.startY, s.endY),
                                    Math.max(s.startX, s.endX), Math.max(s.startY, s.endY)), sp);
                            break;
                        case TRIANGLE:
                            drawTriangle(canvas, s.startX, s.startY, s.endX, s.endY, sp);
                            break;
                        case STAR:
                            drawStar(canvas, s.startX, s.startY, s.endX, s.endY, sp);
                            break;
                        case COORDINATE_AXIS:
                            drawCoordinateAxis(canvas, s.startX, s.startY, s.endX, s.endY, sp);
                            break;
                        case TABLE:
                            float tLeft = Math.min(s.startX, s.endX);
                            float tTop = Math.min(s.startY, s.endY);
                            float tRight = Math.max(s.startX, s.endX);
                            float tBottom = Math.max(s.startY, s.endY);
                            canvas.drawRect(tLeft, tTop, tRight, tBottom, sp);
                            float rowHeight = (tBottom - tTop) / 3f;
                            float colWidth = (tRight - tLeft) / 3f;
                            for (int r = 1; r < 3; r++) {
                                canvas.drawLine(tLeft, tTop + r * rowHeight, tRight, tTop + r * rowHeight, sp);
                            }
                            for (int c = 1; c < 3; c++) {
                                canvas.drawLine(tLeft + c * colWidth, tTop, tLeft + c * colWidth, tBottom, sp);
                            }
                            break;
                    }
                }
            }

            // 3. Draw Texts with designated TTF / OTF font & rotation support
            if (page.texts != null) {
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (TextItem t : page.texts) {
                    if (t == null || t.text == null || t.text.isEmpty()) continue;
                    tp.setColor(t.color);
                    tp.setTextSize(t.fontSize);
                    tp.setTypeface(org.opennotes.app.util.TypefaceManager.getTypeface(context, t.fontKey));
                    TextSpacingCompat.configure(tp, t.letterSpacingEm,
                            t.wordSpacingEm * t.fontSize);
                    if (Math.abs(t.rotationAngle) > 0.5f) {
                        canvas.save();
                        canvas.rotate(t.rotationAngle, t.x, t.y);
                        TextSpacingCompat.drawTextItem(canvas, t, tp);
                        canvas.restore();
                    } else {
                        TextSpacingCompat.drawTextItem(canvas, t, tp);
                    }
                }
            }

            // 4. Draw Strokes
            if (page.strokes != null) {
                Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeCap(Paint.Cap.ROUND);
                strokePaint.setStrokeJoin(Paint.Join.ROUND);

                Paint fountainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                fountainPaint.setStyle(Paint.Style.FILL);

                Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                brushPaint.setStyle(Paint.Style.FILL);
                Paint brushGrainPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

                for (Stroke stroke : page.strokes) {
                    if (stroke.points == null || stroke.points.isEmpty()) continue;

                    if (stroke.tool == DrawingTool.FOUNTAIN) {
                        fountainPaint.setColor(stroke.color);
                        fountainPaint.setAlpha(255);
                        canvas.drawPath(stroke.getPressurePath(1.0f), fountainPaint);
                        continue;
                    } else if (stroke.tool == DrawingTool.BRUSH) {
                        PaintBrushRenderer.draw(canvas, stroke, 1.0f, brushPaint, brushGrainPaint);
                        continue;
                    }

                    strokePaint.setColor(stroke.color);

                    if (stroke.points.size() == 1) {
                        DrawingPoint dot = stroke.points.get(0);
                        strokePaint.setStyle(Paint.Style.FILL);
                        float dotRadius = Math.max(1.0f, stroke.width / 2.0f);
                        if (stroke.tool == DrawingTool.HIGHLIGHTER) {
                            strokePaint.setAlpha(100);
                            dotRadius = stroke.width * 1.5f;
                        } else if (stroke.tool == DrawingTool.PENCIL) {
                            strokePaint.setAlpha(180);
                            dotRadius = Math.max(1.0f, stroke.width * 0.35f);
                        } else {
                            strokePaint.setAlpha(255);
                        }
                        canvas.drawCircle(dot.x, dot.y, dotRadius, strokePaint);
                        strokePaint.setStyle(Paint.Style.STROKE);
                        continue;
                    }

                    if (stroke.tool == DrawingTool.HIGHLIGHTER) {
                        strokePaint.setAlpha(100);
                        strokePaint.setStrokeWidth(stroke.width * 3.5f);
                    } else if (stroke.tool == DrawingTool.PENCIL) {
                        strokePaint.setAlpha(180);
                        strokePaint.setStrokeWidth(Math.max(2.0f, stroke.width * 0.7f));
                    } else {
                        strokePaint.setAlpha(255);
                        strokePaint.setStrokeWidth(stroke.width);
                    }

                    Path path = stroke.getPath();
                    if (path != null) {
                        canvas.drawPath(path, strokePaint);
                    }
                }
            }

            canvas.restore();
            document.finishPage(pdfPage);
        }

        File exportDir = new File(context.getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();

        String safeTitle = (nb != null && nb.title != null) ? nb.title.replaceAll("[^a-zA-Z0-9.-]", "_") : "OpenNotes";
        File outFile = new File(exportDir, safeTitle + "_" + System.currentTimeMillis() + ".pdf");

        FileOutputStream fos = new FileOutputStream(outFile);
        document.writeTo(fos);
        fos.close();
        document.close();

        return outFile;
    }

    private static boolean isColorDark(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b);
        return lum < 128.0;
    }

    private static void drawTriangle(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        Path p = new Path();
        float midX = (x1 + x2) / 2.0f;
        p.moveTo(midX, Math.min(y1, y2));
        p.lineTo(Math.max(x1, x2), Math.max(y1, y2));
        p.lineTo(Math.min(x1, x2), Math.max(y1, y2));
        p.close();
        canvas.drawPath(p, paint);
    }

    private static void drawStar(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float cx = (x1 + x2) / 2.0f;
        float cy = (y1 + y2) / 2.0f;
        float r = Math.min(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 2.0f;
        if (r < 5) return;
        float innerR = r * 0.4f;

        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            double angle = i * Math.PI / 5 - Math.PI / 2;
            float radius = (i % 2 == 0) ? r : innerR;
            float x = (float) (cx + radius * Math.cos(angle));
            float y = (float) (cy + radius * Math.sin(angle));
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
        canvas.drawPath(p, paint);
    }

    private static void drawCoordinateAxis(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float minX = Math.min(x1, x2);
        float maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2);
        float maxY = Math.max(y1, y2);
        float midY = (minY + maxY) / 2.0f;
        float midX = (minX + maxX) / 2.0f;

        canvas.drawLine(minX, midY, maxX, midY, paint);
        drawArrowHead(canvas, minX, midY, maxX, midY, paint);

        canvas.drawLine(midX, maxY, midX, minY, paint);
        drawArrowHead(canvas, midX, maxY, midX, minY, paint);
    }

    private static void drawArrowHead(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float deltaX = x2 - x1;
        float deltaY = y2 - y1;
        double angle = Math.atan2(deltaY, deltaX);
        float arrowSize = 36f;

        float xA = (float) (x2 - arrowSize * Math.cos(angle - Math.PI / 6));
        float yA = (float) (y2 - arrowSize * Math.sin(angle - Math.PI / 6));
        float xB = (float) (x2 - arrowSize * Math.cos(angle + Math.PI / 6));
        float yB = (float) (y2 - arrowSize * Math.sin(angle + Math.PI / 6));

        canvas.drawLine(x2, y2, xA, yA, paint);
        canvas.drawLine(x2, y2, xB, yB, paint);
    }

    private static void drawTemplateInCanvasSpace(Canvas canvas, PaperTemplate template, RectF bounds, boolean isDark, float extraX, float extraY, float scale) {
        if (template == PaperTemplate.BLANK) return;

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(isDark ? 0x22FFFFFF : 0xFFE2E8F0);
        linePaint.setStrokeWidth(1.2f);

        Paint marginPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        marginPaint.setColor(isDark ? 0x55F87171 : 0xFFFCA5A5);
        marginPaint.setStrokeWidth(1.8f);

        float viewLeft = bounds.left - extraX / scale;
        float viewRight = bounds.right + extraX / scale;
        float viewTop = bounds.top - extraY / scale;
        float viewBottom = bounds.bottom + extraY / scale;

        if (template == PaperTemplate.RULED) {
            float lineSpacing = 60f;
            float marginX = 140f;
            if (marginX >= viewLeft && marginX <= viewRight) {
                canvas.drawLine(marginX, viewTop, marginX, viewBottom, marginPaint);
            }
            float firstLineY = ((float) Math.floor((viewTop - lineSpacing) / lineSpacing)) * lineSpacing;
            for (float y = firstLineY; y <= viewBottom + lineSpacing; y += lineSpacing) {
                canvas.drawLine(viewLeft, y, viewRight, y, linePaint);
            }
        } else if (template == PaperTemplate.GRID) {
            float gridSize = 50f;
            float firstX = ((float) Math.floor((viewLeft - gridSize) / gridSize)) * gridSize;
            float firstY = ((float) Math.floor((viewTop - gridSize) / gridSize)) * gridSize;
            for (float x = firstX; x <= viewRight + gridSize; x += gridSize) {
                canvas.drawLine(x, viewTop, x, viewBottom, linePaint);
            }
            for (float y = firstY; y <= viewBottom + gridSize; y += gridSize) {
                canvas.drawLine(viewLeft, y, viewRight, y, linePaint);
            }
        } else if (template == PaperTemplate.DOTS) {
            float dotSpacing = 50f;
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(isDark ? 0x33FFFFFF : 0xFFCBD5E1);
            dotPaint.setStyle(Paint.Style.FILL);
            float firstX = ((float) Math.floor((viewLeft - dotSpacing) / dotSpacing)) * dotSpacing;
            float firstY = ((float) Math.floor((viewTop - dotSpacing) / dotSpacing)) * dotSpacing;
            for (float x = firstX; x <= viewRight + dotSpacing; x += dotSpacing) {
                for (float y = firstY; y <= viewBottom + dotSpacing; y += dotSpacing) {
                    canvas.drawCircle(x, y, 1.8f, dotPaint);
                }
            }
        } else if (template == PaperTemplate.CORNELL) {
            float headerY = 140f;
            float marginX = 240f;
            if (headerY >= viewTop && headerY <= viewBottom) {
                canvas.drawLine(viewLeft, headerY, viewRight, headerY, linePaint);
            }
            if (marginX >= viewLeft && marginX <= viewRight) {
                canvas.drawLine(marginX, Math.max(headerY, viewTop), marginX, viewBottom, marginPaint);
            }
            float lineSpacing = 55f;
            float firstY = Math.max(headerY + lineSpacing, ((float) Math.floor((viewTop - lineSpacing) / lineSpacing)) * lineSpacing);
            float minLineX = Math.max(marginX, viewLeft);
            for (float y = firstY; y <= viewBottom + lineSpacing; y += lineSpacing) {
                canvas.drawLine(minLineX, y, viewRight, y, linePaint);
            }
        }
    }

    public static RectF calculatePageContentBounds(Page page) {
        if (page == null) return new RectF(0f, 0f, 1f, 1f);
        // If the page has a background PDF image, anchor to that exact page rectangle
        if (page.images != null) {
            for (ImageItem img : page.images) {
                if (img != null && img.isBackground) {
                    RectF bounds = new RectF(img.getBounds());
                    PageWritingContext context = PageWritingContext.from(page);
                    if (!context.contentBounds.isEmpty()) {
                        bounds.union(context.contentBounds);
                    }
                    return bounds;
                }
            }
        }
        PageWritingContext context = PageWritingContext.from(page);
        RectF bounds = new RectF(context.contentBounds);
        if (bounds.isEmpty()) {
            // Empty pages still need a printable paper aspect ratio. Once ink
            // exists, bounds below are entirely content-driven.
            float emptyHeight = page.canvasHeight > 0f ? page.canvasHeight : 1528f;
            emptyHeight = Math.max(1f, emptyHeight);
            return new RectF(0f, 0f, emptyHeight * 0.707f, emptyHeight);
        }
        float writingUnit = context.typicalStrokeHeight > 0f
                ? context.typicalStrokeHeight : Math.max(1f, Math.min(bounds.width(), bounds.height()));
        float padding = Math.max(1f, writingUnit * 0.35f);
        bounds.inset(-padding, -padding);
        return bounds;
    }

    public static float calculatePageContentBottom(Page page) {
        return calculatePageContentBounds(page).bottom;
    }
}
