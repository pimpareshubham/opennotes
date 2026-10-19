package org.opennotes.app.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import org.opennotes.app.ui.CrashReportActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public static void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (!(current instanceof CrashHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context.getApplicationContext(), current));
        }
    }

    private CrashHandler(Context context, Thread.UncaughtExceptionHandler defaultHandler) {
        this.context = context;
        this.defaultHandler = defaultHandler;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            String report = buildCrashReport(thread, throwable);
            Log.e(TAG, "Uncaught Exception:\n" + report, throwable);

            // Save report to disk
            saveCrashReport(report);

            // Launch crash report activity
            Intent intent = new Intent(context, CrashReportActivity.class);
            intent.putExtra(CrashReportActivity.EXTRA_CRASH_REPORT, report);
            intent.putExtra(CrashReportActivity.EXTRA_ERROR_MESSAGE, throwable.getMessage() != null ? throwable.getMessage() : throwable.toString());
            intent.putExtra(CrashReportActivity.EXTRA_ERROR_CLASS, throwable.getClass().getName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);

            // Kill process cleanly
            Process.killProcess(Process.myPid());
            System.exit(10);
        } catch (Throwable t) {
            Log.e(TAG, "Failed in crash handler", t);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        }
    }

    private String buildCrashReport(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("=== OpenNotes Crash Report ===\n");
        sb.append("Time: ").append(sdf.format(new Date())).append("\n");
        sb.append("Thread: ").append(thread.getName()).append(" (ID: ").append(thread.getId()).append(")\n");
        sb.append("Android OS: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Device Hardware: ").append(Build.HARDWARE).append(" / ").append(Build.FINGERPRINT).append("\n");
        sb.append("\n--- Exception Details ---\n");
        sb.append("Exception Class: ").append(throwable.getClass().getName()).append("\n");
        sb.append("Message: ").append(throwable.getMessage()).append("\n");
        if (throwable.getCause() != null) {
            sb.append("Cause: ").append(throwable.getCause().toString()).append("\n");
        }
        sb.append("\n--- Stack Trace ---\n");
        sb.append(stackTrace);
        return sb.toString();
    }

    private void saveCrashReport(String report) {
        try {
            File file = new File(context.getFilesDir(), "crash_report.txt");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(report.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }
}
