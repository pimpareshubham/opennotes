package org.opennotes.app.util;

import android.content.Context;
import android.graphics.Typeface;

import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages loading and caching of Google Fonts for Auto-Beautification
 * in English, Marathi, and Hindi:
 * - Kalam (Indian Calligraphy / Authentic Devanagari handwriting)
 * - Caveat (English Calligraphy / Natural cursive script)
 * - Yatra One (Devanagari literary calligraphy)
 * - Poppins (Modern geometric student font)
 * - Custom External TTF / OTF Fonts imported by user from device storage
 */
public class TypefaceManager {

    public static final String FONT_KALAM = "KALAM";
    public static final String FONT_CAVEAT = "CAVEAT";
    public static final String FONT_POPPINS = "POPPINS";
    public static final String FONT_YATRA = "YATRA_ONE";
    public static final String CUSTOM_PREFIX = "CUSTOM_";

    public static class CustomFontInfo {
        public String key;
        public String name;
        public String displayName;
        public String fileName;

        public CustomFontInfo(String key, String name, String fileName) {
            this.key = key;
            this.name = name;
            this.displayName = name;
            this.fileName = fileName;
        }
    }

    private static final Map<String, Typeface> sCache = new HashMap<>();

    public static File getFontsDirectory(Context context) {
        File dir = new File(context.getFilesDir(), "fonts");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static List<CustomFontInfo> getCustomFonts(Context context) {
        List<CustomFontInfo> list = new ArrayList<>();
        File dir = getFontsDirectory(context);
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".ttf") || lower.endsWith(".otf");
        });
        if (files != null) {
            for (File f : files) {
                String fName = f.getName();
                String cleanName = fName.replaceAll("(?i)\\.(ttf|otf)$", "").replace('_', ' ');
                list.add(new CustomFontInfo(CUSTOM_PREFIX + fName, cleanName, fName));
            }
        }
        return list;
    }

    public static String importCustomFont(Context context, Uri fontUri) {
        String name = null;
        if (fontUri != null) {
            try (android.database.Cursor cursor = context.getContentResolver().query(fontUri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        name = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
            if (name == null) {
                name = fontUri.getLastPathSegment();
            }
        }
        return importCustomFont(context, fontUri, name);
    }

    public static String importCustomFont(Context context, Uri fontUri, String originalName) {
        if (fontUri == null) return null;
        try {
            File dir = getFontsDirectory(context);
            String safeName = (originalName != null && !originalName.trim().isEmpty())
                    ? originalName.replaceAll("[^a-zA-Z0-9.-]", "_")
                    : "font_" + System.currentTimeMillis() + ".ttf";
            if (!safeName.toLowerCase().endsWith(".ttf") && !safeName.toLowerCase().endsWith(".otf")) {
                safeName += ".ttf";
            }

            File destFile = new File(dir, safeName);
            InputStream is = context.getContentResolver().openInputStream(fontUri);
            if (is != null) {
                FileOutputStream fos = new FileOutputStream(destFile);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
                fos.close();
                is.close();

                Typeface tf = Typeface.createFromFile(destFile);
                String key = CUSTOM_PREFIX + destFile.getName();
                sCache.put(key, tf);
                return key;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static synchronized Typeface getTypeface(Context context, String fontKey) {
        if (fontKey == null) fontKey = FONT_KALAM;
        if (sCache.containsKey(fontKey)) {
            return sCache.get(fontKey);
        }

        Typeface tf = null;
        try {
            if (fontKey.startsWith(CUSTOM_PREFIX)) {
                String fileName = fontKey.substring(CUSTOM_PREFIX.length());
                File f = new File(getFontsDirectory(context), fileName);
                if (f.exists()) {
                    tf = Typeface.createFromFile(f);
                }
            }

            if (tf == null) {
                switch (fontKey) {
                    case FONT_CAVEAT:
                        tf = Typeface.createFromAsset(context.getAssets(), "fonts/caveat_regular.ttf");
                        break;
                    case FONT_YATRA:
                        tf = Typeface.createFromAsset(context.getAssets(), "fonts/yatra_one.ttf");
                        break;
                    case FONT_POPPINS:
                        tf = Typeface.createFromAsset(context.getAssets(), "fonts/poppins_regular.ttf");
                        break;
                    case FONT_KALAM:
                    default:
                        tf = Typeface.createFromAsset(context.getAssets(), "fonts/kalam_regular.ttf");
                        break;
                }
            }
        } catch (Exception e) {
            if (FONT_CAVEAT.equals(fontKey)) {
                tf = Typeface.create(Typeface.SERIF, Typeface.ITALIC);
            } else {
                tf = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
            }
        }

        if (tf != null) {
            sCache.put(fontKey, tf);
        }
        return tf != null ? tf : Typeface.DEFAULT;
    }

    public static Typeface getForLanguage(Context context, String language, String fontKey) {
        if (fontKey != null && !fontKey.isEmpty()) {
            return getTypeface(context, fontKey);
        }
        if ("ENGLISH".equalsIgnoreCase(language)) {
            return getTypeface(context, FONT_CAVEAT);
        } else {
            // Hindi or Marathi
            return getTypeface(context, FONT_KALAM);
        }
    }
}
