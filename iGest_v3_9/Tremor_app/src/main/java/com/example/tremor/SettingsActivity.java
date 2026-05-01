package com.example.tremor;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import android.content.SharedPreferences;
import android.widget.TextView;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    // ADDED: track connStateReceiver registration
    private boolean connReceiverRegistered = false;

    private DrawerLayout drawerLayout;
    private ImageView menuIcon;
    private ImageView bleStatusIcon;
    private BluetoothAdapter bluetoothAdapter;
    private boolean receiverRegistered = false;

    // UI
    private TextView currentModeValue;

    // listens for system Bluetooth adapter state changes
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "bluetoothReceiver onReceive: action=" + intent.getAction());
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                updateBleStatusCircle(state == BluetoothAdapter.STATE_ON, false);
            }
        }
    };

    // ADDED: listen for app-level connection state broadcasts (connected/disconnected)
    private final BroadcastReceiver connStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra("connected", false);
            Log.d(TAG, "connStateReceiver onReceive connected=" + connected);
            boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
            updateBleStatusCircle(adapterOn, connected);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // UI refs
        drawerLayout = findViewById(R.id.drawerLayout);
        menuIcon = findViewById(R.id.menuIcon);
        bleStatusIcon = findViewById(R.id.bleStatusIcon);
        currentModeValue = findViewById(R.id.currentModeValue);

        // Bluetooth adapter
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        // Register receivers
        try {
            registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverRegistered = true;
            Log.d(TAG, "bluetoothReceiver registered");
        } catch (Exception e) {
            receiverRegistered = false;
            Log.w(TAG, "Failed to register bluetoothReceiver", e);
        }

        try {
            registerReceiver(connStateReceiver, new IntentFilter("com.example.bledatalogger.ACTION_CONN_STATE"));
            connReceiverRegistered = true;
            Log.d(TAG, "connStateReceiver registered");
        } catch (Exception e) {
            connReceiverRegistered = false;
            Log.w(TAG, "Failed to register connStateReceiver", e);
        }

        // Initial UI update (assume not connected yet)
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled(), false);

        // BLE icon click: prompt to enable Bluetooth using system dialog
        bleStatusIcon.setOnClickListener(v -> {
            if (bluetoothAdapter == null) return;
            if (!bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivity(enableBtIntent);
            } else {
                Toast.makeText(this, "Bluetooth is ON", Toast.LENGTH_SHORT).show();
            }
        });

        // Drawer/menu handling
        menuIcon.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        NavigationView navigationView = findViewById(R.id.navigationView);
        updateDrawerHeaderUsernameFromPrefs();
        View headerView = navigationView.getHeaderView(0);
        LinearLayout headerUserRow = headerView.findViewById(R.id.headerUserRow);
        navigationView.setCheckedItem(R.id.nav_settings);
        headerUserRow.setOnClickListener(v -> {
            Intent intent = new Intent(SettingsActivity.this, UserSettingsActivity.class);
            startActivity(intent);
        });
        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Intent intent = new Intent(SettingsActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_data_analysis) {
                Intent intent = new Intent(SettingsActivity.this, DataAnalysisActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START))
                    drawerLayout.closeDrawer(GravityCompat.START);
            } else if (id == R.id.nav_about_us) {
                Intent intent = new Intent(SettingsActivity.this, AboutUsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // wire Change Mode row (existing id from previous edits)
        View changeModeRow = findViewById(R.id.changeModeRow);
        if (changeModeRow != null) {
            changeModeRow.setOnClickListener(v -> {
                // Clear the saved launcher preference so the launcher shows options again
                getSharedPreferences("LauncherPrefs", MODE_PRIVATE).edit().remove("preferred_app").apply();

                Toast.makeText(SettingsActivity.this, "iGest mode reset — choose app on next screen", Toast.LENGTH_SHORT).show();

                // Try to start the launcher activity (shell MainActivity). Use app package + class name.
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(getPackageName(), "com.example.igest_v3.MainActivity"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                try {
                    startActivity(intent);
                    finish();
                } catch (Exception e) {
                    Log.w(TAG, "Explicit component start of launcher failed, falling back to package launch", e);
                    Intent pmLaunch = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (pmLaunch != null) {
                        pmLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(pmLaunch);
                        finish();
                    } else {
                        Toast.makeText(SettingsActivity.this, "Unable to open launcher. Please reopen the app.", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        // show current mode immediately
        refreshCurrentModeDisplay();
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

        if (connReceiverRegistered) {
            try {
                unregisterReceiver(connStateReceiver);
                Log.d(TAG, "connStateReceiver unregistered");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "connStateReceiver was not registered or already unregistered", e);
            } finally {
                connReceiverRegistered = false;
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
        Log.d("Settings", "onNewIntent: adapterOn=" + adapterOn + " storedConnected=" + storedConnected);
        updateBleStatusCircle(adapterOn, storedConnected);
        refreshCurrentModeDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
        Log.d("Settings", "onResume: adapterOn=" + adapterOn + " storedConnected=" + storedConnected);
        updateBleStatusCircle(adapterOn, storedConnected);
        updateDrawerHeaderUsernameFromPrefs();
        refreshCurrentModeDisplay();
    }

    private void refreshCurrentModeDisplay() {
        String display = getPreferredModeDisplay();
        currentModeValue.setText(display);
    }

    private String getPreferredModeDisplay() {
        SharedPreferences prefs = getSharedPreferences("LauncherPrefs", MODE_PRIVATE);
        String preferred = prefs.getString("preferred_app", null);
        if (preferred == null) return "Not set";
        if ("tremor".equals(preferred)) return "Tremor (iGest Tremor)";
        if ("anxiety".equals(preferred)) return "Anxiety (iGest Anxiety)";
        // fallback to raw string
        return preferred;
    }

    // CHANGED: accept deviceConnected flag and show connected drawable if true
    private void updateBleStatusCircle(boolean bleOn, boolean deviceConnected) {
        if (bleStatusIcon == null) return;
        if (!bleOn) {
            bleStatusIcon.setImageResource(R.drawable.bluetooth_off);
            bleStatusIcon.setContentDescription(getString(R.string.ble_status_off));
            bleStatusIcon.setImageTintList(null);
        } else {
            if (deviceConnected) {
                bleStatusIcon.setImageResource(R.drawable.bluetooth_connected);
                bleStatusIcon.setContentDescription(getString(R.string.ble_status_connected));
                bleStatusIcon.setImageTintList(null);
            } else {
                bleStatusIcon.setImageResource(R.drawable.bluetooth_on);
                bleStatusIcon.setContentDescription(getString(R.string.ble_status_on));
                bleStatusIcon.setImageTintList(null);
            }
        }
    }

    private void updateDrawerHeaderUsernameFromPrefs() {
        try {
            SharedPreferences prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);
            String current = prefs.getString("current_user", "User");
            NavigationView nv = findViewById(R.id.navigationView);
            if (nv == null) return;
            View header = nv.getHeaderView(0);
            if (header == null) return;
            TextView drawerUsername = header.findViewById(R.id.drawerUsername);
            if (drawerUsername == null) return;
            drawerUsername.setText((current == null || current.isEmpty()) ? "User" : current);
        } catch (Exception e) {
            Log.w("NavHeaderUpdate", "updateDrawerHeaderUsernameFromPrefs failed", e);
        }
    }
}