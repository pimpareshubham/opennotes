package org.opennotes.app.export;

import android.content.Context;
import android.graphics.Bitmap;

import org.opennotes.app.ui.CanvasView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ImageExporter {

    public static File exportPageToImage(Context context, CanvasView canvasView, String notebookTitle, int pageIndex) throws IOException {
        int width = Math.max(1080, canvasView.getWidth());
        int height = Math.max(1528, canvasView.getHeight());

        Bitmap bitmap = canvasView.renderToBitmap(width, height);

        File exportDir = new File(context.getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();

        String safeTitle = (notebookTitle != null) ? notebookTitle.replaceAll("[^a-zA-Z0-9.-]", "_") : "OpenNotes";
        File outFile = new File(exportDir, safeTitle + "_page_" + (pageIndex + 1) + "_" + System.currentTimeMillis() + ".png");

        FileOutputStream fos = new FileOutputStream(outFile);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        fos.flush();
        fos.close();
        bitmap.recycle();

        return outFile;
    }
}
