package com.example.tremor;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Minimal LoginActivity placeholder so UserSettingsActivity can reference it.
 * Expand this into a proper login screen later when you want authentication.
 */
public class LoginActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Minimal behavior: finish and return to previous activity.
        // Replace with real layout & logic later.
        // Optionally you could setContentView(R.layout.activity_login) when ready.
        finish();
    }
}