package com.example.igest_v3;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.ContextCompat;
import android.view.WindowManager;
import android.graphics.Color;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.ContextCompat;
import android.graphics.Color;
import android.util.TypedValue;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;
import androidx.core.content.ContextCompat;
import android.view.View;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // Fully-qualified Tremor activity class name (as declared in Tremor module)
    private static final String TREMOR_ACTIVITY_CLASS = "com.example.tremor.MainActivity";
    private static final String TREMOR_PACKAGE = "com.example.tremor";

    // Fully-qualified Anxiety activity class name (as declared in Anxiety module)
    private static final String ANXIETY_ACTIVITY_CLASS = "com.example.anxiety.MainActivity";
    private static final String ANXIETY_PACKAGE = "com.example.anxiety";

    private static final String PREFS_NAME = "LauncherPrefs";
    private static final String PREF_KEY_SELECTED_APP = "preferred_app";
    private static final String APP_TREMOR = "tremor";
    private static final String APP_ANXIETY = "anxiety";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If a preferred app was already chosen previously, attempt to route immediately
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String preferred = prefs.getString(PREF_KEY_SELECTED_APP, null);
        if (preferred != null) {
            Log.i(TAG, "Found preferred app: " + preferred + " — attempting automatic launch");
            boolean launched = false;
            if (APP_TREMOR.equals(preferred)) {
                launched = launchTremorAndFinish();
            } else if (APP_ANXIETY.equals(preferred)) {
                launched = launchAnxietyAndFinish();
            }
            if (launched) {
                // We started the chosen app and finished our launcher so we don't show UI
                return;
            } else {
                // Launch failed; clear stored preference and fall through to show launcher UI
                Log.w(TAG, "Automatic launch of preferred app failed — clearing stored preference");
                prefs.edit().remove(PREF_KEY_SELECTED_APP).apply();
                Toast.makeText(this, "Failed to open preferred app. Please choose again.", Toast.LENGTH_SHORT).show();
            }
        }

        // No preference set or automatic launch failed — show launcher UI
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            WindowInsetsControllerCompat ic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
            ic.show(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
            ic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

            // Temporary test color so the bar is unmistakable
            getWindow().setStatusBarColor(Color.MAGENTA);

            // Log the effective status bar color
            int sbColor = getWindow().getStatusBarColor();
            Log.i(TAG, "DIAG: window.statusBarColor = 0x" + Integer.toHexString(sbColor));
            // Log theme flag that controls icon color
            TypedValue tv = new TypedValue();
            boolean has = getTheme().resolveAttribute(android.R.attr.windowLightStatusBar, tv, true);
            Log.i(TAG, "DIAG: theme has windowLightStatusBar=" + has + " value=" + (has ? tv.data : "n/a"));
        } catch (Exception e) {
            Log.w(TAG, "DIAG statusbar fail", e);
        }

// Give root view a short post to read insets
        findViewById(R.id.main).post(() -> {
            androidx.core.view.WindowInsetsCompat rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(findViewById(R.id.main));
            int topInset = (rootInsets == null) ? -1 : rootInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            Log.i(TAG, "DIAG: statusBar top inset = " + topInset
                    + "   sysUiVis=" + getWindow().getDecorView().getSystemUiVisibility()
                    + "   windowFlags=" + getWindow().getAttributes().flags);
        });

        // --- Tremor button ---
//        Button btnTremor = findViewById(R.id.btn_tremor);
        View btnTremor = findViewById(R.id.card_tremor);
        if (btnTremor == null) {
            Log.e(TAG, "btn_tremor not found in layout");
        } else {
            btnTremor.setOnClickListener(v -> {
                // save preference
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_KEY_SELECTED_APP, APP_TREMOR).apply();
                // attempt launch (we will finish if successful)
                if (!launchTremorAndFinish()) {
                    // on failure clear pref so user can try again
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_KEY_SELECTED_APP).apply();
                }
            });
        }

        // --- Anxiety button ---
//        Button btnAnxiety = findViewById(R.id.btn_anxiety);
        View btnAnxiety = findViewById(R.id.card_anxiety);
        if (btnAnxiety == null) {
            Log.e(TAG, "btn_anxiety not found in layout");
        } else {
            btnAnxiety.setOnClickListener(v -> {
                // save preference
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_KEY_SELECTED_APP, APP_ANXIETY).apply();
                // attempt launch (we will finish if successful)
                if (!launchAnxietyAndFinish()) {
                    // on failure clear pref so user can try again
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_KEY_SELECTED_APP).apply();
                }
            });
        }
    }

    // Helper: attempt to launch Tremor and finish launcher. Returns true on success.
    private boolean launchTremorAndFinish() {
        // 1) Ensure library init first (safe to call multiple times)
        try {
            com.example.tremor.TremorInitializer.init(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "TremorInitializer.init() raised", t);
            // continue trying to launch; initialization is best-effort
        }

        // 2) Try explicit start via class reference (works when Tremor is a module dependency)
        try {
            Intent intent = new Intent(this, com.example.tremor.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return true;
        } catch (Throwable e1) {
            Log.w(TAG, "Direct startActivity(com.example.tremor.MainActivity) failed", e1);
        }

        // 3) Try component using app package + Tremor activity class (handles merged-manifest package)
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(getPackageName(), TREMOR_ACTIVITY_CLASS));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return true;
        } catch (Throwable e2) {
            Log.w(TAG, "startActivity via ComponentName(getPackageName(), className) failed", e2);
        }

        // 4) If Tremor is a separate installed app, try PackageManager launch intent
        try {
            PackageManager pm = getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(TREMOR_PACKAGE);
            if (launch != null) {
                // ensure it becomes the root task
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(launch);
                finish();
                return true;
            } else {
                Log.w(TAG, "No launch intent for package: " + TREMOR_PACKAGE);
            }
        } catch (Throwable e3) {
            Log.w(TAG, "PackageManager launchIntent failed", e3);
        }

        // 5) All strategies failed — show user and log instructive message
        Toast.makeText(this, "Unable to open Tremor. See logcat for details.", Toast.LENGTH_LONG).show();
        return false;
    }

    // Helper: attempt to launch Anxiety and finish launcher. Returns true on success.
    private boolean launchAnxietyAndFinish() {
        // Initialize Anxiety library (if module exposes initializer)
        try {
            Class.forName("com.example.anxiety.AnxietyInitializer")
                    .getMethod("init", android.content.Context.class)
                    .invoke(null, getApplicationContext());
        } catch (ClassNotFoundException cnf) {
            // initializer not present — that's fine for a library without explicit init
            Log.i(TAG, "AnxietyInitializer not found - skipping init");
        } catch (Throwable t) {
            Log.w(TAG, "AnxietyInitializer.init() raised", t);
            // continue to attempt launch
        }

        // 1) Try explicit start via class reference (works when Anxiety is a module dependency)
        try {
            Class<?> clazz = Class.forName("com.example.anxiety.MainActivity");
            Intent intent = new Intent(this, clazz.asSubclass(android.app.Activity.class));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return true;
        } catch (Throwable e1) {
            Log.w(TAG, "Direct startActivity(com.example.anxiety.MainActivity) failed", e1);
        }

        // 2) Try component using app package + Anxiety activity class (handles merged-manifest package)
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(getPackageName(), ANXIETY_ACTIVITY_CLASS));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return true;
        } catch (Throwable e2) {
            Log.w(TAG, "startActivity via ComponentName(getPackageName(), className) failed", e2);
        }

        // 3) If Anxiety is a separate installed app, try PackageManager launch intent
        try {
            PackageManager pm = getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(ANXIETY_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(launch);
                finish();
                return true;
            } else {
                Log.w(TAG, "No launch intent for package: " + ANXIETY_PACKAGE);
            }
        } catch (Throwable e3) {
            Log.w(TAG, "PackageManager launchIntent failed", e3);
        }

        // 4) All strategies failed — show user and log instructive message
        Toast.makeText(this, "Unable to open Anxiety module. See logcat for details.", Toast.LENGTH_LONG).show();
        return false;
    }
}