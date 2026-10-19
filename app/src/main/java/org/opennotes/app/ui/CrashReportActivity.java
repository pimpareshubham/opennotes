package org.opennotes.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opennotes.app.R;

public class CrashReportActivity extends AppCompatActivity {
    public static final String EXTRA_CRASH_REPORT = "extra.crash_report";
    public static final String EXTRA_ERROR_MESSAGE = "extra.error_message";
    public static final String EXTRA_ERROR_CLASS = "extra.error_class";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash_report);

        TextView textClass = findViewById(R.id.textCrashClass);
        TextView textMsg = findViewById(R.id.textCrashMessage);
        TextView textTrace = findViewById(R.id.textCrashStackTrace);
        Button btnCopy = findViewById(R.id.btnCopyCrash);
        Button btnRestart = findViewById(R.id.btnRestartApp);
        Button btnClose = findViewById(R.id.btnCloseCrash);

        String errorClass = getIntent().getStringExtra(EXTRA_ERROR_CLASS);
        String errorMsg = getIntent().getStringExtra(EXTRA_ERROR_MESSAGE);
        String report = getIntent().getStringExtra(EXTRA_CRASH_REPORT);

        if (errorClass != null) textClass.setText(errorClass);
        if (errorMsg != null) textMsg.setText(errorMsg);
        if (report != null) textTrace.setText(report);

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Crash Report", report != null ? report : errorMsg));
                Toast.makeText(this, "📋 Crash report copied to clipboard!", Toast.LENGTH_SHORT).show();
            }
        });

        btnRestart.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        btnClose.setOnClickListener(v -> finish());
    }
}
