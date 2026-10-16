package org.opennotes.app.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.opennotes.app.model.Notebook;
import org.opennotes.app.model.Page;
import org.opennotes.app.model.PaperTemplate;

import java.util.ArrayList;
import java.util.List;

public class NotesDbHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "opennotes.db";
    private static final int DATABASE_VERSION = 3;

    // Notebooks table
    public static final String TABLE_NOTEBOOKS = "notebooks";
    public static final String COL_NB_ID = "id";
    public static final String COL_NB_TITLE = "title";
    public static final String COL_NB_FOLDER = "folder";
    public static final String COL_NB_COVER_COLOR = "cover_color";
    public static final String COL_NB_TEMPLATE = "template";
    public static final String COL_NB_DEFAULT_BG_COLOR = "default_bg_color";
    public static final String COL_NB_DEFAULT_IS_INFINITE = "default_is_infinite";
    public static final String COL_NB_PAGE_COUNT = "page_count";
    public static final String COL_NB_FAVORITE = "is_favorite";
    public static final String COL_NB_CREATED_AT = "created_at";
    public static final String COL_NB_UPDATED_AT = "updated_at";

    // Pages table
    public static final String TABLE_PAGES = "pages";
    public static final String COL_PAGE_ID = "id";
    public static final String COL_PAGE_NB_ID = "notebook_id";
    public static final String COL_PAGE_INDEX = "page_index";
    public static final String COL_PAGE_TEMPLATE = "template";
    public static final String COL_PAGE_BG_COLOR = "background_color";
    public static final String COL_PAGE_IS_INFINITE = "is_infinite";
    public static final String COL_PAGE_CANVAS_HEIGHT = "canvas_height";
    public static final String COL_PAGE_STROKES = "strokes_json";
    public static final String COL_PAGE_SHAPES = "shapes_json";
    public static final String COL_PAGE_TEXTS = "texts_json";
    public static final String COL_PAGE_CREATED_AT = "created_at";
    public static final String COL_PAGE_UPDATED_AT = "updated_at";

    private static NotesDbHelper instance;

    public static synchronized NotesDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new NotesDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private NotesDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_NOTEBOOKS + " ("
                + COL_NB_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_NB_TITLE + " TEXT NOT NULL, "
                + COL_NB_FOLDER + " TEXT DEFAULT 'Default', "
                + COL_NB_COVER_COLOR + " INTEGER, "
                + COL_NB_TEMPLATE + " TEXT, "
                + COL_NB_DEFAULT_BG_COLOR + " INTEGER DEFAULT -1, "
                + COL_NB_DEFAULT_IS_INFINITE + " INTEGER DEFAULT 0, "
                + COL_NB_PAGE_COUNT + " INTEGER DEFAULT 1, "
                + COL_NB_FAVORITE + " INTEGER DEFAULT 0, "
                + COL_NB_CREATED_AT + " INTEGER, "
                + COL_NB_UPDATED_AT + " INTEGER"
                + ");");

        db.execSQL("CREATE TABLE " + TABLE_PAGES + " ("
                + COL_PAGE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_PAGE_NB_ID + " INTEGER NOT NULL, "
                + COL_PAGE_INDEX + " INTEGER NOT NULL, "
                + COL_PAGE_TEMPLATE + " TEXT, "
                + COL_PAGE_BG_COLOR + " INTEGER DEFAULT -1, "
                + COL_PAGE_IS_INFINITE + " INTEGER DEFAULT 0, "
                + COL_PAGE_CANVAS_HEIGHT + " REAL DEFAULT 0, "
                + COL_PAGE_STROKES + " TEXT, "
                + COL_PAGE_SHAPES + " TEXT, "
                + COL_PAGE_TEXTS + " TEXT, "
                + COL_PAGE_CREATED_AT + " INTEGER, "
                + COL_PAGE_UPDATED_AT + " INTEGER, "
                + "FOREIGN KEY(" + COL_PAGE_NB_ID + ") REFERENCES " + TABLE_NOTEBOOKS + "(" + COL_NB_ID + ") ON DELETE CASCADE"
                + ");");

        db.execSQL("CREATE INDEX idx_pages_nb ON " + TABLE_PAGES + "(" + COL_PAGE_NB_ID + ", " + COL_PAGE_INDEX + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_NOTEBOOKS + " ADD COLUMN " + COL_NB_DEFAULT_BG_COLOR + " INTEGER DEFAULT -1;");
                db.execSQL("ALTER TABLE " + TABLE_NOTEBOOKS + " ADD COLUMN " + COL_NB_DEFAULT_IS_INFINITE + " INTEGER DEFAULT 0;");
                db.execSQL("ALTER TABLE " + TABLE_PAGES + " ADD COLUMN " + COL_PAGE_BG_COLOR + " INTEGER DEFAULT -1;");
                db.execSQL("ALTER TABLE " + TABLE_PAGES + " ADD COLUMN " + COL_PAGE_IS_INFINITE + " INTEGER DEFAULT 0;");
                db.execSQL("ALTER TABLE " + TABLE_PAGES + " ADD COLUMN " + COL_PAGE_CANVAS_HEIGHT + " REAL DEFAULT 0;");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 3) {
            removeUntouchedSeedNotebook(db);
        }
    }

    /** Remove the old auto-created sample only when it still has its untouched default page. */
    private void removeUntouchedSeedNotebook(SQLiteDatabase db) {
        List<Long> candidateIds = new ArrayList<>();
        Cursor notebooks = db.query(TABLE_NOTEBOOKS, new String[]{COL_NB_ID},
                COL_NB_TITLE + "=? AND " + COL_NB_FOLDER + "=? AND "
                        + COL_NB_COVER_COLOR + "=? AND " + COL_NB_TEMPLATE + "=? AND "
                        + COL_NB_DEFAULT_BG_COLOR + "=? AND " + COL_NB_DEFAULT_IS_INFINITE + "=0 AND "
                        + COL_NB_PAGE_COUNT + "=1 AND " + COL_NB_FAVORITE + "=0",
                new String[]{"11 PI", "Work", String.valueOf(0xFF1E1E24), "RULED",
                        String.valueOf(0xFF121214)},
                null, null, null);
        if (notebooks == null) return;
        try {
            while (notebooks.moveToNext()) candidateIds.add(notebooks.getLong(0));
        } finally {
            notebooks.close();
        }

        for (long notebookId : candidateIds) {
            Cursor pages = db.query(TABLE_PAGES,
                    new String[]{COL_PAGE_INDEX, COL_PAGE_TEMPLATE, COL_PAGE_BG_COLOR,
                            COL_PAGE_IS_INFINITE, COL_PAGE_CANVAS_HEIGHT, COL_PAGE_STROKES,
                            COL_PAGE_SHAPES, COL_PAGE_TEXTS},
                    COL_PAGE_NB_ID + "=?", new String[]{String.valueOf(notebookId)},
                    null, null, null);
            if (pages == null) continue;

            boolean untouched;
            try {
                untouched = pages.moveToFirst()
                        && pages.getInt(0) == 0
                        && "RULED".equals(pages.getString(1))
                        && pages.getInt(2) == 0xFF121214
                        && pages.getInt(3) == 0
                        && pages.getFloat(4) <= 0f
                        && isEmptyJsonArray(pages.getString(5))
                        && isEmptyJsonArray(pages.getString(6))
                        && isEmptyJsonArray(pages.getString(7))
                        && !pages.moveToNext();
            } finally {
                pages.close();
            }

            if (untouched) {
                db.delete(TABLE_PAGES, COL_PAGE_NB_ID + "=?",
                        new String[]{String.valueOf(notebookId)});
                db.delete(TABLE_NOTEBOOKS, COL_NB_ID + "=?",
                        new String[]{String.valueOf(notebookId)});
            }
        }
    }

    private static boolean isEmptyJsonArray(String value) {
        return value == null || value.trim().isEmpty() || "[]".equals(value.trim());
    }

    // --- Notebook Operations ---

    public synchronized long insertNotebook(Notebook nb) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NB_TITLE, nb.title);
        cv.put(COL_NB_FOLDER, nb.folder);
        cv.put(COL_NB_COVER_COLOR, nb.coverColor);
        cv.put(COL_NB_TEMPLATE, nb.defaultTemplate.name());
        cv.put(COL_NB_DEFAULT_BG_COLOR, nb.defaultBgColor);
        cv.put(COL_NB_DEFAULT_IS_INFINITE, nb.defaultIsInfinite ? 1 : 0);
        cv.put(COL_NB_PAGE_COUNT, 1);
        cv.put(COL_NB_FAVORITE, nb.isFavorite ? 1 : 0);
        long now = System.currentTimeMillis();
        cv.put(COL_NB_CREATED_AT, now);
        cv.put(COL_NB_UPDATED_AT, now);

        long id = db.insert(TABLE_NOTEBOOKS, null, cv);
        nb.id = id;

        // Create the initial first page with matching background and infinite mode
        Page firstPage = new Page(id, 0, nb.defaultTemplate, nb.defaultBgColor, nb.defaultIsInfinite);
        insertPage(firstPage);

        return id;
    }

    public synchronized void updateNotebook(Notebook nb) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NB_TITLE, nb.title);
        cv.put(COL_NB_FOLDER, nb.folder);
        cv.put(COL_NB_COVER_COLOR, nb.coverColor);
        cv.put(COL_NB_TEMPLATE, nb.defaultTemplate.name());
        cv.put(COL_NB_DEFAULT_BG_COLOR, nb.defaultBgColor);
        cv.put(COL_NB_DEFAULT_IS_INFINITE, nb.defaultIsInfinite ? 1 : 0);
        cv.put(COL_NB_PAGE_COUNT, nb.pageCount);
        cv.put(COL_NB_FAVORITE, nb.isFavorite ? 1 : 0);
        cv.put(COL_NB_UPDATED_AT, System.currentTimeMillis());

        db.update(TABLE_NOTEBOOKS, cv, COL_NB_ID + "=?", new String[]{String.valueOf(nb.id)});
    }

    public synchronized void updateNotebookDefaultBgColor(long notebookId, int bgColor, boolean applyToAllExistingPages) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NB_DEFAULT_BG_COLOR, bgColor);
        cv.put(COL_NB_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_NOTEBOOKS, cv, COL_NB_ID + "=?", new String[]{String.valueOf(notebookId)});

        if (applyToAllExistingPages) {
            ContentValues pcv = new ContentValues();
            pcv.put(COL_PAGE_BG_COLOR, bgColor);
            pcv.put(COL_PAGE_UPDATED_AT, System.currentTimeMillis());
            db.update(TABLE_PAGES, pcv, COL_PAGE_NB_ID + "=?", new String[]{String.valueOf(notebookId)});
        }
    }

    public synchronized void deleteNotebook(long notebookId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_PAGES, COL_PAGE_NB_ID + "=?", new String[]{String.valueOf(notebookId)});
        db.delete(TABLE_NOTEBOOKS, COL_NB_ID + "=?", new String[]{String.valueOf(notebookId)});
    }

    public synchronized void setFavorite(long notebookId, boolean fav) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NB_FAVORITE, fav ? 1 : 0);
        cv.put(COL_NB_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_NOTEBOOKS, cv, COL_NB_ID + "=?", new String[]{String.valueOf(notebookId)});
    }

    public synchronized Notebook getNotebook(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_NOTEBOOKS, null, COL_NB_ID + "=?", new String[]{String.valueOf(id)}, null, null, null);
        Notebook nb = null;
        if (c != null && c.moveToFirst()) {
            nb = cursorToNotebook(c);
            c.close();
        }
        return nb;
    }

    public synchronized List<Notebook> getAllNotebooks(String filterFolder, boolean favoritesOnly, String searchQuery) {
        List<Notebook> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        StringBuilder query = new StringBuilder("SELECT * FROM " + TABLE_NOTEBOOKS + " WHERE 1=1");
        List<String> args = new ArrayList<>();

        if (favoritesOnly) {
            query.append(" AND " + COL_NB_FAVORITE + " = 1");
        }
        if (filterFolder != null && !filterFolder.isEmpty() && !filterFolder.equalsIgnoreCase("All")) {
            query.append(" AND " + COL_NB_FOLDER + " = ?");
            args.add(filterFolder);
        }
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            query.append(" AND " + COL_NB_TITLE + " LIKE ?");
            args.add("%" + searchQuery.trim() + "%");
        }

        query.append(" ORDER BY " + COL_NB_UPDATED_AT + " DESC");

        Cursor c = db.rawQuery(query.toString(), args.toArray(new String[0]));
        if (c != null) {
            while (c.moveToNext()) {
                list.add(cursorToNotebook(c));
            }
            c.close();
        }
        return list;
    }

    public synchronized long duplicateNotebook(long notebookId) {
        Notebook original = getNotebook(notebookId);
        if (original == null) return -1;

        Notebook dup = new Notebook(original.title + " (Copy)", original.coverColor, original.defaultTemplate);
        dup.folder = original.folder;
        dup.defaultBgColor = original.defaultBgColor;
        dup.defaultIsInfinite = original.defaultIsInfinite;
        long newId = insertNotebook(dup);

        List<Page> originalPages = getPagesForNotebook(notebookId);
        // Replace auto-created first page with copies of original pages
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_PAGES, COL_PAGE_NB_ID + "=?", new String[]{String.valueOf(newId)});

        for (Page p : originalPages) {
            Page newPage = new Page(newId, p.pageIndex, p.template, p.backgroundColor, p.isInfinite);
            newPage.canvasHeight = p.canvasHeight;
            newPage.strokesFromJson(p.strokesToJson());
            newPage.shapesFromJson(p.shapesToJson());
            newPage.textsFromJson(p.textsToJson());
            insertPage(newPage);
        }

        dup.id = newId;
        dup.pageCount = originalPages.size();
        updateNotebook(dup);

        return newId;
    }

    private Notebook cursorToNotebook(Cursor c) {
        Notebook nb = new Notebook();
        nb.id = c.getLong(c.getColumnIndexOrThrow(COL_NB_ID));
        nb.title = c.getString(c.getColumnIndexOrThrow(COL_NB_TITLE));
        nb.folder = c.getString(c.getColumnIndexOrThrow(COL_NB_FOLDER));
        nb.coverColor = c.getInt(c.getColumnIndexOrThrow(COL_NB_COVER_COLOR));
        String tpl = c.getString(c.getColumnIndexOrThrow(COL_NB_TEMPLATE));
        nb.defaultTemplate = PaperTemplate.fromString(tpl);

        int bgColIdx = c.getColumnIndex(COL_NB_DEFAULT_BG_COLOR);
        nb.defaultBgColor = (bgColIdx != -1 && !c.isNull(bgColIdx)) ? c.getInt(bgColIdx) : 0xFFFFFFFF;

        int infIdx = c.getColumnIndex(COL_NB_DEFAULT_IS_INFINITE);
        nb.defaultIsInfinite = (infIdx != -1 && !c.isNull(infIdx)) && c.getInt(infIdx) == 1;

        nb.pageCount = c.getInt(c.getColumnIndexOrThrow(COL_NB_PAGE_COUNT));
        nb.isFavorite = c.getInt(c.getColumnIndexOrThrow(COL_NB_FAVORITE)) == 1;
        nb.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_NB_CREATED_AT));
        nb.updatedAt = c.getLong(c.getColumnIndexOrThrow(COL_NB_UPDATED_AT));
        return nb;
    }

    // --- Page Operations ---

    public synchronized long insertPage(Page page) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_PAGE_NB_ID, page.notebookId);
        cv.put(COL_PAGE_INDEX, page.pageIndex);
        cv.put(COL_PAGE_TEMPLATE, page.template.name());
        cv.put(COL_PAGE_BG_COLOR, page.backgroundColor);
        cv.put(COL_PAGE_IS_INFINITE, page.isInfinite ? 1 : 0);
        cv.put(COL_PAGE_CANVAS_HEIGHT, page.canvasHeight);
        cv.put(COL_PAGE_STROKES, page.strokesToJson());
        cv.put(COL_PAGE_SHAPES, page.shapesToJson());
        cv.put(COL_PAGE_TEXTS, page.textsToJson());
        long now = System.currentTimeMillis();
        cv.put(COL_PAGE_CREATED_AT, now);
        cv.put(COL_PAGE_UPDATED_AT, now);
        long id = db.insert(TABLE_PAGES, null, cv);
        page.id = id;
        return id;
    }

    public synchronized void updatePage(Page page) {
        savePage(page);
    }

    public synchronized void savePage(Page page) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_PAGE_TEMPLATE, page.template.name());
        cv.put(COL_PAGE_BG_COLOR, page.backgroundColor);
        cv.put(COL_PAGE_IS_INFINITE, page.isInfinite ? 1 : 0);
        cv.put(COL_PAGE_CANVAS_HEIGHT, page.canvasHeight);
        cv.put(COL_PAGE_STROKES, page.strokesToJson());
        cv.put(COL_PAGE_SHAPES, page.shapesToJson());
        cv.put(COL_PAGE_TEXTS, page.textsToJson());
        cv.put(COL_PAGE_UPDATED_AT, System.currentTimeMillis());

        int rows = db.update(TABLE_PAGES, cv,
                COL_PAGE_NB_ID + "=? AND " + COL_PAGE_INDEX + "=?",
                new String[]{String.valueOf(page.notebookId), String.valueOf(page.pageIndex)});

        if (rows == 0) {
            insertPage(page);
        }

        // Also touch notebook updatedAt
        ContentValues nbCv = new ContentValues();
        nbCv.put(COL_NB_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_NOTEBOOKS, nbCv, COL_NB_ID + "=?", new String[]{String.valueOf(page.notebookId)});
    }

    public synchronized Page getPage(long notebookId, int pageIndex) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_PAGES, null,
                COL_PAGE_NB_ID + "=? AND " + COL_PAGE_INDEX + "=?",
                new String[]{String.valueOf(notebookId), String.valueOf(pageIndex)},
                null, null, null);

        Page page = null;
        if (c != null && c.moveToFirst()) {
            page = cursorToPage(c);
            c.close();
        }
        return page;
    }

    public synchronized List<Page> getPagesForNotebook(long notebookId) {
        List<Page> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_PAGES, null,
                COL_PAGE_NB_ID + "=?",
                new String[]{String.valueOf(notebookId)},
                null, null, COL_PAGE_INDEX + " ASC");

        if (c != null) {
            while (c.moveToNext()) {
                list.add(cursorToPage(c));
            }
            c.close();
        }
        return list;
    }

    public synchronized void deletePage(long notebookId, int pageIndex) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_PAGES,
                COL_PAGE_NB_ID + "=? AND " + COL_PAGE_INDEX + "=?",
                new String[]{String.valueOf(notebookId), String.valueOf(pageIndex)});

        // Re-index remaining pages
        Cursor c = db.query(TABLE_PAGES, new String[]{COL_PAGE_ID, COL_PAGE_INDEX},
                COL_PAGE_NB_ID + "=? AND " + COL_PAGE_INDEX + " > ?",
                new String[]{String.valueOf(notebookId), String.valueOf(pageIndex)},
                null, null, COL_PAGE_INDEX + " ASC");

        if (c != null) {
            while (c.moveToNext()) {
                long pageId = c.getLong(0);
                int oldIdx = c.getInt(1);
                ContentValues cv = new ContentValues();
                cv.put(COL_PAGE_INDEX, oldIdx - 1);
                db.update(TABLE_PAGES, cv, COL_PAGE_ID + "=?", new String[]{String.valueOf(pageId)});
            }
            c.close();
        }

        // Update notebook page count
        Cursor countCursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_PAGES + " WHERE " + COL_PAGE_NB_ID + "=?",
                new String[]{String.valueOf(notebookId)});
        int count = 1;
        if (countCursor != null && countCursor.moveToFirst()) {
            count = countCursor.getInt(0);
            countCursor.close();
        }
        ContentValues nbCv = new ContentValues();
        nbCv.put(COL_NB_PAGE_COUNT, count);
        nbCv.put(COL_NB_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_NOTEBOOKS, nbCv, COL_NB_ID + "=?", new String[]{String.valueOf(notebookId)});
    }

    private Page cursorToPage(Cursor c) {
        long nbId = c.getLong(c.getColumnIndexOrThrow(COL_PAGE_NB_ID));
        int idx = c.getInt(c.getColumnIndexOrThrow(COL_PAGE_INDEX));
        String tpl = c.getString(c.getColumnIndexOrThrow(COL_PAGE_TEMPLATE));

        int bgColIdx = c.getColumnIndex(COL_PAGE_BG_COLOR);
        int bgColor = (bgColIdx != -1 && !c.isNull(bgColIdx)) ? c.getInt(bgColIdx) : 0xFFFFFFFF;

        int infIdx = c.getColumnIndex(COL_PAGE_IS_INFINITE);
        boolean isInf = (infIdx != -1 && !c.isNull(infIdx)) && c.getInt(infIdx) == 1;

        Page page = new Page(nbId, idx, PaperTemplate.fromString(tpl), bgColor, isInf);
        page.id = c.getLong(c.getColumnIndexOrThrow(COL_PAGE_ID));

        int hIdx = c.getColumnIndex(COL_PAGE_CANVAS_HEIGHT);
        if (hIdx != -1 && !c.isNull(hIdx)) {
            page.canvasHeight = c.getFloat(hIdx);
        }

        page.strokesFromJson(c.getString(c.getColumnIndexOrThrow(COL_PAGE_STROKES)));
        page.shapesFromJson(c.getString(c.getColumnIndexOrThrow(COL_PAGE_SHAPES)));
        page.textsFromJson(c.getString(c.getColumnIndexOrThrow(COL_PAGE_TEXTS)));
        page.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_PAGE_CREATED_AT));
        page.updatedAt = c.getLong(c.getColumnIndexOrThrow(COL_PAGE_UPDATED_AT));
        return page;
    }
}
