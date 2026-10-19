package org.opennotes.app;

import android.app.Application;
import android.database.CursorWindow;
import android.util.Log;

import org.opennotes.app.util.CrashHandler;

import java.lang.reflect.Field;

public class OpenNotesApplication extends Application {
    private static final String TAG = "OpenNotesApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Install global crash handler to intercept unhandled exceptions and show detailed report
        CrashHandler.install(this);

        // 2. Increase SQLite CursorWindow size to 100MB so large notes with thousands of strokes never fail
        increaseCursorWindowSize();
    }

    private void increaseCursorWindowSize() {
        try {
            Field field = CursorWindow.class.getDeclaredField("sCursorWindowSize");
            field.setAccessible(true);
            field.set(null, 100 * 1024 * 1024); // 100 MB window
            Log.i(TAG, "Successfully increased CursorWindow.sCursorWindowSize to 100MB");
        } catch (Throwable t) {
            Log.w(TAG, "Reflection on CursorWindow.sCursorWindowSize not permitted: " + t.getMessage());
        }
    }
}
