package com.example.anxiety;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

public class AboutUsActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private ImageView menuIcon;
    private View bleStatusCircle;
    private BluetoothAdapter bluetoothAdapter;

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
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
        setContentView(R.layout.anxiety_activity_about_us);

        // Initialize the DrawerLayout and Menu Icon
        drawerLayout = findViewById(R.id.drawerLayout);
        menuIcon = findViewById(R.id.menuIcon);
        bleStatusCircle = findViewById(R.id.bleStatusCircle);

        // Initialize Bluetooth Adapter
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        // Set BLE status circle initial state
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled());

        // Handle BLE status circle click
        bleStatusCircle.setOnClickListener(v -> {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                // Prompt user to enable Bluetooth
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivity(enableBtIntent);
            }
        });

        // Setup Hamburger menu click to open the drawer
        menuIcon.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        // Handle navigation view item clicks
        NavigationView navigationView = findViewById(R.id.navigationView);

        // Handle drawer header user icon click
        View headerView = navigationView.getHeaderView(0);
        LinearLayout headerUserRow = headerView.findViewById(R.id.headerUserRow);
        navigationView.setCheckedItem(R.id.nav_about_us);

        headerUserRow.setOnClickListener(v -> {
            // Handle click here: open user profile, settings, dialog, etc.
            // Example: open a new activity
            Intent intent = new Intent(AboutUsActivity.this, UserSettingsActivity.class);
            startActivity(intent);
        });

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_home) {
                // Close the current drawer
                drawerLayout.closeDrawer(GravityCompat.START);

                // Navigate to MainActivity without recreating it
                Intent intent = new Intent(AboutUsActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_data_analysis) {
                // Navigate to Data Analysis Activity
                Intent intent = new Intent(AboutUsActivity.this, DataAnalysisActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                // Navigate to Settings Activity
                Intent intent = new Intent(AboutUsActivity.this, SettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_about_us) {
                // Navigate to About Us Activity
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
            }
            // Close the drawer after selecting an item
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });
    }

    @Override
    protected void onDestroy () {
        super.onDestroy();
        // Unregister the Bluetooth state change receiver
        unregisterReceiver(bluetoothReceiver);
    }

    @Override
    protected void onResume () {
        super.onResume();

        // Update BLE status circle whenever the activity resumes
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled());
    }

    private void updateBleStatusCircle ( boolean bleOn){
        if (bleOn) {
            bleStatusCircle.setBackgroundResource(R.drawable.circle_green);
        } else {
            bleStatusCircle.setBackgroundResource(R.drawable.circle_red);
        }
    }
}

