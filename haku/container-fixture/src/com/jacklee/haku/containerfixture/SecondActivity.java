package com.jacklee.haku.containerfixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

public final class SecondActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FixtureApplication.record(this, "secondActivityOnCreate", true);
        TextView label = new TextView(this);
        label.setText("Haku navigation fixture\nReturning a real activity result…");
        label.setTextSize(20);
        setContentView(label);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            setResult(RESULT_OK, new Intent().putExtra("marker", "haku_navigation_ok"));
            FixtureApplication.record(this, "secondActivityFinished", true);
            finish();
        }, 350);
    }
    @Override public void onResume() {
        super.onResume();
        FixtureApplication.record(this, "secondActivityOnResume", true);
    }
}
