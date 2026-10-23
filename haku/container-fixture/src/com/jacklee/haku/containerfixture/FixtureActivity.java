package com.jacklee.haku.containerfixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class FixtureActivity extends Activity {
    private int resumes;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FixtureApplication.record(this, "activityOnCreate", true);
        FixtureApplication.record(this, "activityClass", getClass().getName());
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 48, 32, 24);
        TextView text = new TextView(this);
        text.setText("Haku Container Fixture\n\nApplication and Activity lifecycle executed.\n"
                + "Two owned runtime payloads were decoded, executed and registered.\n"
                + "Their temporary clear DEX sources have been removed.\n\n"
                + "This fixture tests runtime DEX evidence; it is not a rebuilt target app.");
        text.setTextSize(18);
        layout.addView(text);
        Button worker = new Button(this);
        worker.setText("Test worker service");
        worker.setOnClickListener(v -> startWorker());
        layout.addView(worker);
        setContentView(layout);
        new Handler(Looper.getMainLooper()).postDelayed(() ->
                FixtureApplication.record(this, "activityDelayedCallback", true), 500);
        if (getIntent().getBooleanExtra("runWorker", true)) startWorker();
        if (getIntent().getBooleanExtra("testNavigation", true))
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    FixtureApplication.record(this, "secondActivityRequested", true);
                    startActivityForResult(new Intent(this, SecondActivity.class), 71);
                } catch (Throwable failure) {
                    FixtureApplication.record(this, "secondActivityStartError", failure.toString());
                }
            }, 700);
    }

    private void startWorker() {
        try {
            android.content.ComponentName result = startService(new Intent(this, FixtureService.class));
            FixtureApplication.record(this, "workerRequestAccepted", result != null);
        } catch (Throwable failure) {
            FixtureApplication.record(this, "workerStartError", failure.toString());
        }
    }

    @Override public void onResume() {
        super.onResume();
        FixtureApplication.record(this, "activityOnResume", true);
        FixtureApplication.record(this, "mainActivityResumeCount", ++resumes);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 71)
            FixtureApplication.record(this, "secondActivityResult",
                    result == RESULT_OK && data != null
                            && "haku_navigation_ok".equals(data.getStringExtra("marker")));
    }
}
