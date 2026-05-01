package com.example.tremor;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
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
import android.widget.TextView;
import android.content.SharedPreferences;

public class AboutUsActivity extends AppCompatActivity {

    private static final String TAG = "AboutUsActivity";

    private boolean connReceiverRegistered = false;

    private DrawerLayout drawerLayout;
    private ImageView menuIcon;
//    private View bleStatusCircle;

    private ImageView bleStatusIcon;
    private BluetoothAdapter bluetoothAdapter;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "bluetoothReceiver onReceive: action=" + intent.getAction());
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
//                updateBleStatusCircle(state == BluetoothAdapter.STATE_ON);
                // When adapter state changes, keep "connected" false until broadcast says otherwise
                updateBleStatusCircle(state == BluetoothAdapter.STATE_ON, false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_us);

        drawerLayout = findViewById(R.id.drawerLayout);
        menuIcon = findViewById(R.id.menuIcon);
        // bleStatusCircle removed — using ImageView now
        bleStatusIcon = findViewById(R.id.bleStatusIcon);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        // Register receiver so we actually receive updates and can safely unregister later
        try {
            registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverRegistered = true;
            Log.d(TAG, "bluetoothReceiver registered");
        } catch (Exception e) {
            receiverRegistered = false;
            Log.w(TAG, "Failed to register bluetoothReceiver", e);
        }

        // ADDED: register connStateReceiver and record registration success
        try {
            registerReceiver(connStateReceiver, new IntentFilter("com.example.bledatalogger.ACTION_CONN_STATE"));
            connReceiverRegistered = true; // ADDED
            Log.d(TAG, "connStateReceiver registered");
        } catch (Exception e) {
            connReceiverRegistered = false; // ADDED
            Log.w(TAG, "Failed to register connStateReceiver", e);
        }

        // Initialize UI indicator: assume not connected yet (deviceConnected=false)
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled(), false);

        // Click: prompt system "Turn on Bluetooth?" dialog when adapter is OFF
        bleStatusIcon.setOnClickListener(v -> {
            if (bluetoothAdapter == null) {
                // no adapter available on device
                return;
            }
            // Ask system to enable Bluetooth with the standard prompt (not open Settings)
            if (!bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivity(enableBtIntent);
            } else {
                // Bluetooth already ON — keep minimal behavior (optional)
//                 Toast.makeText(this, "Bluetooth is ON", Toast.LENGTH_SHORT).show();
                 Toast.makeText(this, "Bluetooth is ON.", Toast.LENGTH_LONG).show();
            }
        });

        menuIcon.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        NavigationView navigationView = findViewById(R.id.navigationView);
        updateDrawerHeaderUsernameFromPrefs();
        View headerView = navigationView.getHeaderView(0);
        LinearLayout headerUserRow = headerView.findViewById(R.id.headerUserRow);
        navigationView.setCheckedItem(R.id.nav_about_us);
        headerUserRow.setOnClickListener(v -> {
            Intent intent = new Intent(AboutUsActivity.this, UserSettingsActivity.class);
            startActivity(intent);
        });

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Intent intent = new Intent(AboutUsActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_data_analysis) {
                Intent intent = new Intent(AboutUsActivity.this, DataAnalysisActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(AboutUsActivity.this, SettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_about_us) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START);
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unregister bluetoothReceiver if it was registered
        if (receiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver);
                Log.d(TAG, "bluetoothReceiver unregistered");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "bluetoothReceiver unregistration failed", e);
            } finally {
                receiverRegistered = false;
            }
        }

        // Unregister connStateReceiver if it was registered
        if (connReceiverRegistered) {
            try {
                unregisterReceiver(connStateReceiver);
                Log.d(TAG, "connStateReceiver unregistered");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "connStateReceiver unregistration failed", e);
            } finally {
                connReceiverRegistered = false;
            }
        }
    }

//    @Override
//    protected void onResume() {
//        super.onResume();
////        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled());
//        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled(), false);
//    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // When activity is brought to front, re-check adapter and persisted connection state
        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
        Log.d("About Us", "onNewIntent: adapterOn=" + adapterOn + " storedConnected=" + storedConnected);
        updateBleStatusCircle(adapterOn, storedConnected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
        Log.d("About Us", "onResume: adapterOn=" + adapterOn + " storedConnected=" + storedConnected);
        updateBleStatusCircle(adapterOn, storedConnected);
        updateDrawerHeaderUsernameFromPrefs();
    }

//    @Override
//    protected void onResume() {
//        super.onResume();
//        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
//
//        // Read last-known connection state saved by MainActivity (default false)
//        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
//
//        // Update UI using adapter state + stored connection state
//        updateBleStatusCircle(adapterOn, storedConnected);
//
//        // Keep the BroadcastReceiver registration active (already registered in onCreate)
//    }

//    private void updateBleStatusCircle(boolean bleOn) {
//        if (bleStatusCircle == null) return;
//        if (bleOn) {
//            bleStatusCircle.setBackgroundResource(R.drawable.circle_green);
//        } else {
//            bleStatusCircle.setBackgroundResource(R.drawable.circle_red);
//        }
//    }

    // New signature: accepts adapter state and whether the app is connected to the device
    private void updateBleStatusCircle(boolean bleOn, boolean deviceConnected) {
        if (bleStatusIcon == null) return;
        if (!bleOn) {
            // Adapter OFF -> show "off" image
            bleStatusIcon.setImageResource(R.drawable.bluetooth_off);
            bleStatusIcon.setContentDescription(getString(R.string.ble_status_off));
            bleStatusIcon.setImageTintList(null);
        } else {
            // Adapter ON
            if (deviceConnected) {
                // App-level connection -> show connected image
                bleStatusIcon.setImageResource(R.drawable.bluetooth_connected);
                bleStatusIcon.setContentDescription(getString(R.string.ble_status_connected));
                bleStatusIcon.setImageTintList(null);
            } else {
                // Adapter ON but not connected -> show ON image
                bleStatusIcon.setImageResource(R.drawable.bluetooth_on);
                bleStatusIcon.setContentDescription(getString(R.string.ble_status_on));
                bleStatusIcon.setImageTintList(null);
            }
        }
    }

    // New: listen for app-level connection state broadcasts (connected/disconnected)
    private final BroadcastReceiver connStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // action is expected to be "com.example.bledatalogger.ACTION_CONN_STATE"
            boolean connected = intent.getBooleanExtra("connected", false);
            // Re-evaluate adapter state + device connection
            boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
            updateBleStatusCircle(adapterOn, connected);
        }
    };

    // call inside the Activity class
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