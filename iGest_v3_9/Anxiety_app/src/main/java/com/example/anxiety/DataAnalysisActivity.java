package com.example.anxiety;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.datepicker.CalendarConstraints;

import java.util.HashSet;
import java.util.Set;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DataAnalysisActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private ImageView menuIcon;
    private View bleStatusCircle;
    private BluetoothAdapter bluetoothAdapter;
    private Button selectDateButton;

    private Set<String> availableDataDates = new HashSet<>(); // Store dates as "yyyy-MM-dd" strings

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
        setContentView(R.layout.anxiety_activity_data_analysis);

        // Initialize the DrawerLayout and Menu Icon
        drawerLayout = findViewById(R.id.drawerLayout);
        menuIcon = findViewById(R.id.menuIcon);
        bleStatusCircle = findViewById(R.id.bleStatusCircle);
        selectDateButton = findViewById(R.id.selectDateButton); // You'll need to add this to your XML

        // Initialize Bluetooth Adapter
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        // Register Bluetooth state change receiver
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothReceiver, filter);

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
        navigationView.setCheckedItem(R.id.nav_data_analysis);

        headerUserRow.setOnClickListener(v -> {
            Intent intent = new Intent(DataAnalysisActivity.this, UserSettingsActivity.class);
            startActivity(intent);
        });

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_home) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Intent intent = new Intent(DataAnalysisActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_data_analysis) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(DataAnalysisActivity.this, SettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_about_us) {
                Intent intent = new Intent(DataAnalysisActivity.this, AboutUsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Load available data dates
        loadAvailableDataDates();

        // Setup date picker button
        selectDateButton.setOnClickListener(v -> showDatePicker());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister the Bluetooth state change receiver
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Update BLE status circle whenever the activity resumes
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled());
    }

    private void updateBleStatusCircle(boolean bleOn) {
        if (bleOn) {
            bleStatusCircle.setBackgroundResource(R.drawable.circle_green);
        } else {
            bleStatusCircle.setBackgroundResource(R.drawable.circle_red);
        }
    }

    private void loadAvailableDataDates() {
        availableDataDates.clear();

        // Scan folders named "YYYY-MM-DD" in Data/Raw
        File rawDir = new File(getExternalFilesDir(null), "Data/Raw");
        if (!rawDir.exists()) {
            rawDir.mkdirs();
        }

        File[] folders = rawDir.listFiles();
        if (folders != null) {
            for (File folder : folders) {
                if (folder.isDirectory()) {
                    String folderName = folder.getName();
                    if (isValidDateFormat(folderName)) {
                        availableDataDates.add(folderName);
                    }
                }
            }
        }
    }

    private void showDatePicker() {
        // Create calendar constraints
        CalendarConstraints.Builder constraintsBuilder = new CalendarConstraints.Builder();

        // Optional: Set constraints (e.g., only allow past dates)
        // constraintsBuilder.setValidator(DateValidatorPointForward.now());

        // Build the date picker
        MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select a date to view data")
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .setCalendarConstraints(constraintsBuilder.build())
                .build();

        // Handle date selection
        datePicker.addOnPositiveButtonClickListener(selection -> {
            // Convert selection to date string
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            String selectedDate = sdf.format(new Date(selection));

            handleDateSelection(selectedDate);
        });

        // Show the picker
        datePicker.show(getSupportFragmentManager(), "DATE_PICKER");
    }

    private void handleDateSelection(String dateString) {
        if (availableDataDates.contains(dateString)) {
            // Data exists for this date
            openDataForDate(dateString);
            Toast.makeText(this, "Opening data for " + dateString, Toast.LENGTH_SHORT).show();
        } else {
            // No data for this date
            showNoDataMessage(dateString);
        }
    }

    private boolean isValidDateFormat(String folderName) {
        return folderName.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private void openDataForDate(String dateString) {
        // TODO: Implement logic to open/display data for the selected date
        File dataFolder = new File(getExternalFilesDir(null), "Data/Raw/" + dateString);

        if (dataFolder.exists() && dataFolder.listFiles() != null && dataFolder.listFiles().length > 0) {
            // Open data viewer activity or show data dialog
            // Intent intent = new Intent(this, DataViewerActivity.class);
            // intent.putExtra("date", dateString);
            // startActivity(intent);

            // For now, just show a toast
            Toast.makeText(this, "Data found for " + dateString, Toast.LENGTH_LONG).show();
        }
    }

    private void showNoDataMessage(String dateString) {
        Toast.makeText(this, "No data available for " + dateString, Toast.LENGTH_SHORT).show();
    }
}