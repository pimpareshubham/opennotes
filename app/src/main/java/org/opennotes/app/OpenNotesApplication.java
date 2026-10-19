package org.opennotes.app;

import android.app.Application;

import org.opennotes.app.util.CrashHandler;

public class OpenNotesApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Install global crash handler to intercept unhandled exceptions and show detailed report
        CrashHandler.install(this);
    }
}
