package com.example.anxiety;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "AnxSettings";

    private DrawerLayout drawerLayout;
    private View menuIcon; // keep as View in case it's ImageView in layout
    private View bleStatusCircle;
    private android.bluetooth.BluetoothAdapter bluetoothAdapter;

    private boolean receiverRegistered = false;

    // UI
    private TextView currentModeValue;
    private static final String PREFS_BLE = "BlePrefs";
    private static final String PREF_CLOSE_SESSION_ON_RETURN = "CloseSessionOnReturn";


    // =========================
    // Sensitivity (NEW ADD)
    // =========================
    private static final String PREFS_SETTINGS = "AnxietySettingsPrefs";
    private static final String PREF_SENSITIVITY_LEVEL = "sensitivity_level";
    private static final int DEFAULT_SENSITIVITY_LEVEL = 4; // per requirement

    private TextView sensitivityCurrentLabel;
    private SeekBar sensitivitySeekBar;
    private Button sensitivityResetButton;
    private Button sensitivitySaveButton;
    private SensitivityTrackView sensitivityTrackView;

    // system Bluetooth receiver
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                updateBleStatusCircle(state == BluetoothAdapter.STATE_ON);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.anxiety_activity_settings);

        // UI refs
        drawerLayout = findViewById(R.id.drawerLayout);
        menuIcon = findViewById(R.id.menuIcon);
        bleStatusCircle = findViewById(R.id.bleStatusCircle);
        currentModeValue = findViewById(R.id.currentModeValue); // newly added

        // Sensitivity refs (NEW)
        sensitivityCurrentLabel = findViewById(R.id.sensitivityCurrentLabel);
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar);
        sensitivityResetButton = findViewById(R.id.sensitivityResetButton);
        sensitivitySaveButton = findViewById(R.id.sensitivitySaveButton);
        sensitivityTrackView = findViewById(R.id.sensitivityTrack);

        // Bluetooth adapter
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();

        // Register system bluetooth receiver (safe)
        try {
            registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverRegistered = true;
            Log.d(TAG, "bluetoothReceiver registered");
        } catch (Exception e) {
            receiverRegistered = false;
            Log.w(TAG, "Failed to register bluetoothReceiver", e);
        }

        // Initial BLE indicator update
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled());

        // BLE circle click (preserve behavior)
        if (bleStatusCircle != null) {
            bleStatusCircle.setOnClickListener(v -> {
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivity(enableBtIntent);
                }
            });
        }

        // Drawer/menu handling
        if (menuIcon != null && drawerLayout != null) {
            menuIcon.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        }

        NavigationView navigationView = findViewById(R.id.navigationView);
        if (navigationView != null) {
            navigationView.setCheckedItem(R.id.nav_settings);
            // header click
            try {
                View headerView = navigationView.getHeaderView(0);
                if (headerView != null) {
                    LinearLayout headerUserRow = headerView.findViewById(R.id.headerUserRow);
                    if (headerUserRow != null) {
                        headerUserRow.setOnClickListener(v -> {
                            Intent intent = new Intent(SettingsActivity.this, UserSettingsActivity.class);
                            startActivity(intent);
                        });
                    }
                }
            } catch (Exception ignored) {}
            // menu items
            navigationView.setNavigationItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_home) {
                    if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
                    Intent intent = new Intent(SettingsActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                } else if (id == R.id.nav_data_analysis) {
                    Intent intent = new Intent(SettingsActivity.this, DataAnalysisActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                } else if (id == R.id.nav_settings) {
                    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START);
                } else if (id == R.id.nav_about_us) {
                    Intent intent = new Intent(SettingsActivity.this, AboutUsActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                }
                if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            });
        }

        // wire Change Mode row (exact same logic as Tremor)
        View changeModeRow = findViewById(R.id.changeModeRow);
        if (changeModeRow != null) {
            changeModeRow.setOnClickListener(v -> {
                // Clear saved launcher preference
                getSharedPreferences("LauncherPrefs", MODE_PRIVATE).edit().remove("preferred_app").apply();
                // Feedback
                android.widget.Toast.makeText(SettingsActivity.this, "iGest mode reset — choose app on next screen", android.widget.Toast.LENGTH_SHORT).show();
                // Launch the shell launcher activity as new root
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(getPackageName(), "com.example.igest_v3.MainActivity"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                try {
                    startActivity(intent);
                    finish();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to start launcher component, falling back to package launch", e);
                    Intent pmLaunch = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (pmLaunch != null) {
                        pmLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(pmLaunch);
                        finish();
                    } else {
                        android.widget.Toast.makeText(SettingsActivity.this, "Unable to open launcher. Please reopen the app.", android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        // show current mode immediately
        refreshCurrentModeDisplay();

        // Sensitivity init (NEW)
        setupSensitivityUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        refreshCurrentModeDisplay();
        // keep label in sync if coming back
        refreshSensitivityFromPrefs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver);
                Log.d(TAG, "bluetoothReceiver unregistered");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "bluetoothReceiver was not registered or already unregistered", e);
            } finally {
                receiverRegistered = false;
            }
        }
    }

    private void refreshCurrentModeDisplay() {
        if (currentModeValue == null) return;
        SharedPreferences prefs = getSharedPreferences("LauncherPrefs", MODE_PRIVATE);
        String preferred = prefs.getString("preferred_app", null);
        if (preferred == null) {
            currentModeValue.setText("Not set");
            return;
        }
        switch (preferred) {
            case "tremor":
                currentModeValue.setText("Tremor (iGest Tremor)");
                break;
            case "anxiety":
                currentModeValue.setText("Anxiety (iGest Anxiety)");
                break;
            default:
                currentModeValue.setText(preferred);
        }
    }

    // preserve original bleStatusCircle behavior (green/red)
    private void updateBleStatusCircle(boolean bleOn) {
        if (bleStatusCircle == null) return;
        if (bleOn) {
            bleStatusCircle.setBackgroundResource(R.drawable.circle_green);
        } else {
            bleStatusCircle.setBackgroundResource(R.drawable.circle_red);
        }
    }

    // =========================
    // Sensitivity helpers (NEW)
    // =========================
    private void setupSensitivityUi() {
        if (sensitivitySeekBar == null || sensitivityCurrentLabel == null
                || sensitivityResetButton == null || sensitivitySaveButton == null) {
            Log.w(TAG, "Sensitivity UI views missing; skipping setup");
            return;
        }

        // Ensure discrete 10 levels: progress 0..9 -> level 1..10
        sensitivitySeekBar.setMax(9);

        // Load saved or default
        int level = getSavedSensitivityLevel();
        setSensitivityLevelOnUi(level);

        // Default: view mode (disabled)
        setSensitivityEditMode(false);

        sensitivitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int newLevel = progress + 1;
                setSensitivityLabel(newLevel);

                // IMPORTANT: update the custom track fill
                if (sensitivityTrackView != null) {
                    sensitivityTrackView.setProgressIndex(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        sensitivityResetButton.setOnClickListener(v -> {
            // Reset = unlock for editing
            setSensitivityEditMode(true);
        });

        sensitivitySaveButton.setOnClickListener(v -> {
            int currentLevel = (sensitivitySeekBar.getProgress() + 1);

            saveSensitivityLevel(currentLevel);

            getSharedPreferences(PREFS_BLE, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_CLOSE_SESSION_ON_RETURN, true)
                    .apply();

            // Back to view mode (disabled)
            setSensitivityEditMode(false);

            // Navigate to Anxiety main page and finish (per requirement)
            Intent intent = new Intent(SettingsActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        });
    }

    private void refreshSensitivityFromPrefs() {
        if (sensitivitySeekBar == null || sensitivityCurrentLabel == null) return;

        // If slider is disabled (view mode), keep it aligned to saved preference
        if (!sensitivitySeekBar.isEnabled()) {
            int level = getSavedSensitivityLevel();
            setSensitivityLevelOnUi(level);
        }
    }

    private void setSensitivityEditMode(boolean editable) {
        if (sensitivitySeekBar != null) sensitivitySeekBar.setEnabled(editable);
        if (sensitivitySaveButton != null) {
            sensitivitySaveButton.setVisibility(editable ? View.VISIBLE : View.GONE);
            sensitivitySaveButton.setEnabled(editable);
        }
    }

    private void setSensitivityLevelOnUi(int level) {
        int safe = clamp(level, 1, 10);
        int progress = safe - 1;

        if (sensitivitySeekBar != null) sensitivitySeekBar.setProgress(progress);

        // IMPORTANT: update the custom track fill for initial load too
        if (sensitivityTrackView != null) sensitivityTrackView.setProgressIndex(progress);

        setSensitivityLabel(safe);
    }

    private void setSensitivityLabel(int level) {
        if (sensitivityCurrentLabel == null) return;
        int safe = clamp(level, 1, 10);
        sensitivityCurrentLabel.setText("Current sensitivity level set: " + safe);
    }

    private int getSavedSensitivityLevel() {
        SharedPreferences prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        int level = prefs.getInt(PREF_SENSITIVITY_LEVEL, DEFAULT_SENSITIVITY_LEVEL);
        return clamp(level, 1, 10);
    }

    private void saveSensitivityLevel(int level) {
        int safe = clamp(level, 1, 10);
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_SENSITIVITY_LEVEL, safe)
                .apply();
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}