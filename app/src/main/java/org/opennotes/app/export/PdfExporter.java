package org.opennotes.app.export;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;

import org.opennotes.app.db.NotesDbHelper;
import org.opennotes.app.model.DrawingPoint;
import org.opennotes.app.model.DrawingTool;
import org.opennotes.app.model.Notebook;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;
import org.opennotes.app.model.ShapeItem;
import org.opennotes.app.model.Stroke;
import org.opennotes.app.model.TextItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class PdfExporter {

    public static File exportNotebookToPdf(Context context, long notebookId) throws IOException {
        NotesDbHelper db = NotesDbHelper.getInstance(context);
        Notebook nb = db.getNotebook(notebookId);
        List<Page> pages = db.getPagesForNotebook(notebookId);

        // Ultra-HD RAW 300 DPI A4 dimensions (2480 x 3508)
        int defaultPageWidth = 2480;
        int defaultPageHeight = 3508;

        PdfDocument document = new PdfDocument();

        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            int pageWidth = defaultPageWidth;
            float contentH = calculatePageContentBottom(page);
            int pageHeight = Math.max(1200, (int) (defaultPageWidth * (contentH / 1080f)));

            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create();
            PdfDocument.Page pdfPage = document.startPage(pageInfo);
            Canvas canvas = pdfPage.getCanvas();

            // Background color
            int bgColor = (page.backgroundColor != 0) ? page.backgroundColor : 0xFFFFFFFF;
            canvas.drawColor(bgColor);

            boolean isDark = isColorDark(bgColor);
            drawTemplate(canvas, page.template, pageWidth, pageHeight, isDark);

            // Scaling from screen canvas coordinates (1080 x contentH) to 300 DPI RAW print resolution
            float scaleX = (float) pageWidth / 1080f;
            float scaleY = (float) pageHeight / contentH;
            canvas.save();
            canvas.scale(scaleX, scaleY);

            // Draw Shapes
            for (ShapeItem s : page.shapes) {
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setColor(s.color);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeWidth(s.strokeWidth);
                sp.setStrokeCap(Paint.Cap.ROUND);
                sp.setStrokeJoin(Paint.Join.ROUND);

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
                }
            }

            // Draw Texts with designated TTF / OTF font
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (TextItem t : page.texts) {
                tp.setColor(t.color);
                tp.setTextSize(t.fontSize);
                tp.setTypeface(org.opennotes.app.util.TypefaceManager.getTypeface(context, t.fontKey));
                canvas.drawText(t.text, t.x, t.y, tp);
            }

            // Draw Strokes
            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            for (Stroke stroke : page.strokes) {
                if (stroke.points == null || stroke.points.isEmpty()) continue;
                strokePaint.setColor(stroke.color);

                if (stroke.points.size() == 1) {
                    DrawingPoint dot = stroke.points.get(0);
                    strokePaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(dot.x, dot.y, Math.max(2.5f, stroke.width / 2.0f), strokePaint);
                    strokePaint.setStyle(Paint.Style.STROKE);
                    continue;
                }

                if (stroke.tool == DrawingTool.HIGHLIGHTER) {
                    strokePaint.setAlpha(90);
                    strokePaint.setStrokeWidth(stroke.width * 3.5f);
                } else if (stroke.tool == DrawingTool.BRUSH) {
                    strokePaint.setAlpha(200);
                    strokePaint.setStrokeWidth(stroke.width * 1.5f);
                } else if (stroke.tool == DrawingTool.PENCIL) {
                    strokePaint.setAlpha(190);
                    strokePaint.setStrokeWidth(Math.max(2.0f, stroke.width * 0.75f));
                } else if (stroke.tool == DrawingTool.FOUNTAIN) {
                    strokePaint.setAlpha(255);
                    strokePaint.setStrokeWidth(stroke.width * 1.2f);
                } else {
                    strokePaint.setAlpha(255);
                    strokePaint.setStrokeWidth(stroke.width);
                }

                Path path = new Path();
                DrawingPoint first = stroke.points.get(0);
                path.moveTo(first.x, first.y);
                for (int pIdx = 1; pIdx < stroke.points.size(); pIdx++) {
                    DrawingPoint p0 = stroke.points.get(pIdx - 1);
                    DrawingPoint p1 = stroke.points.get(pIdx);
                    path.quadTo(p0.x, p0.y, (p0.x + p1.x) / 2.0f, (p0.y + p1.y) / 2.0f);
                }
                DrawingPoint last = stroke.points.get(stroke.points.size() - 1);
                path.lineTo(last.x, last.y);
                canvas.drawPath(path, strokePaint);
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

    private static void drawTemplate(Canvas canvas, PaperTemplate template, int width, int height, boolean isDark) {
        if (template == PaperTemplate.BLANK) return;

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(isDark ? 0x2EFFFFFF : 0xFFE2E8F0);
        linePaint.setStrokeWidth(1.0f);

        if (template == PaperTemplate.RULED) {
            float lineSpacing = 30f;
            float startY = 60f;
            for (float y = startY; y < height; y += lineSpacing) {
                canvas.drawLine(0, y, width, y, linePaint);
            }
        } else if (template == PaperTemplate.GRID) {
            float gridSize = 25f;
            for (float x = 0; x < width; x += gridSize) {
                canvas.drawLine(x, 0, x, height, linePaint);
            }
            for (float y = 0; y < height; y += gridSize) {
                canvas.drawLine(0, y, width, y, linePaint);
            }
        } else if (template == PaperTemplate.DOTS) {
            float dotSpacing = 25f;
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(isDark ? 0x33FFFFFF : 0xFFCBD5E1);
            dotPaint.setStyle(Paint.Style.FILL);
            for (float x = dotSpacing; x < width; x += dotSpacing) {
                for (float y = dotSpacing; y < height; y += dotSpacing) {
                    canvas.drawCircle(x, y, 1.5f, dotPaint);
                }
            }
        }
    }

    public static float calculatePageContentBottom(Page page) {
        if (page == null) return 1528f;
        float maxBottom = 0f;
        boolean hasContent = false;

        if (page.strokes != null) {
            for (Stroke s : page.strokes) {
                if (s == null) continue;
                RectF b = s.getBounds();
                if (b != null && !b.isEmpty()) {
                    maxBottom = Math.max(maxBottom, b.bottom + s.width * 0.5f);
                    hasContent = true;
                }
            }
        }

        if (page.shapes != null) {
            for (ShapeItem sh : page.shapes) {
                if (sh == null) continue;
                maxBottom = Math.max(maxBottom, Math.max(sh.startY, sh.endY) + sh.strokeWidth);
                hasContent = true;
            }
        }

        if (page.texts != null) {
            for (TextItem t : page.texts) {
                if (t == null) continue;
                maxBottom = Math.max(maxBottom, t.y + t.fontSize + 10f);
                hasContent = true;
            }
        }

        if (page.images != null) {
            for (org.opennotes.app.model.ImageItem img : page.images) {
                if (img == null) continue;
                maxBottom = Math.max(maxBottom, img.y + img.height);
                hasContent = true;
            }
        }

        if (!hasContent) {
            return page.canvasHeight > 0 ? page.canvasHeight : 1528f;
        }

        // Add 80px bottom breathing room
        return Math.max(600f, maxBottom + 80f);
    }
}
