package com.example.tremor;

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
import android.widget.AdapterView;
import android.graphics.Color;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.datepicker.CalendarConstraints;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.formatter.ValueFormatter;

import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.Legend; // add at top if not already
import com.github.mikephil.charting.components.LegendEntry;
import android.content.SharedPreferences;

public class DataAnalysisActivity extends AppCompatActivity {

    // Root app folder (do not include per-user Data folder here)
    private static final String DATA_BASE_ROOT = "iGest: Tremor Detection";
    // We'll store per-user data under: <externalFiles>/<DATA_BASE_ROOT>/Data/<safe-username>/{Raw,Analysed}

    private DrawerLayout drawerLayout;
    private ImageView menuIcon;
    // CHANGED: use ImageView for status icon
    private ImageView bleStatusIcon;
    private BluetoothAdapter bluetoothAdapter;
    private Button selectDateButton;

    private TextView textViewSelectedDate, textViewDataAvailability;
    private Spinner spinnerSessionNumbers;
    private TextView textViewDataDisplay; // For displaying data
    private MaterialButtonToggleGroup toggleRawAnalysedGroup;
    private MaterialButton buttonRaw, buttonAnalysed;

    private LineChart lineChart;
    private Set<String> availableDataDates = new HashSet<>(); // "yyyy-MM-dd"
    private List<Integer> availableSessionNumbers = new ArrayList<>();
    private int selectedSession = -1; // currently selected session number
    private String selectedDate = null;
    private boolean isAnalysed = false; // Tracks selected toggle state

    // For chart
    private List<Entry> allEntries = new ArrayList<>();
    private List<String> allTimeLabels = new ArrayList<>();
    private static final int VISIBLE_POINTS = 100;
    private BarChart barChart;

    private TextView textViewSessionStart, textViewSessionEnd, textViewChartStatus;

    // Track registration for conn-state receiver
    private boolean connReceiverRegistered = false;
    private boolean bluetoothReceiverRegistered = false;

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("DataAnalysis", "bluetoothReceiver onReceive: action=" + intent.getAction());
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                // When adapter state changes, assume not connected until app-level broadcast arrives
                updateBleStatusCircle(state == BluetoothAdapter.STATE_ON, false);
            }
        }
    };

    // New: listen for app-level connection state broadcasts (connected/disconnected)
    private final BroadcastReceiver connStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra("connected", false);
            Log.d("DataAnalysis", "connStateReceiver onReceive connected=" + connected);
            boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
            updateBleStatusCircle(adapterOn, connected);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_analysis);

        // Initialize views
        drawerLayout = findViewById(R.id.drawerLayout);
        menuIcon = findViewById(R.id.menuIcon);
        // CHANGED: find ImageView instead of View
        bleStatusIcon = findViewById(R.id.bleStatusIcon);
        selectDateButton = findViewById(R.id.selectDateButton);

        textViewSelectedDate = findViewById(R.id.textViewSelectedDate);
        textViewDataAvailability = findViewById(R.id.textViewDataAvailability);
        spinnerSessionNumbers = findViewById(R.id.spinnerSessionNumbers);
        textViewDataDisplay = findViewById(R.id.textViewDataDisplay);
        toggleRawAnalysedGroup = findViewById(R.id.toggleRawAnalysedGroup);
        buttonRaw = findViewById(R.id.buttonRaw);
        buttonAnalysed = findViewById(R.id.buttonAnalysed);
        lineChart = findViewById(R.id.lineChart);
        lineChart.setVisibility(View.GONE);
        textViewSessionStart = findViewById(R.id.textViewSessionStart);
        textViewSessionEnd = findViewById(R.id.textViewSessionEnd);
        textViewChartStatus = findViewById(R.id.textViewChartStatus);
        barChart = findViewById(R.id.barChart);
        barChart.setVisibility(View.GONE);
        lineChart.setVisibility(View.GONE); // (already present)

        // Export PDF FAB
        FloatingActionButton exportFab = findViewById(R.id.fabExportPdf);
        exportFab.setOnClickListener(v -> showExportDialog());

        // Set Raw selected by default
        toggleRawAnalysedGroup.check(R.id.buttonRaw);
        isAnalysed = false;
        updateToggleColors();

        toggleRawAnalysedGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) { // Only respond to button being checked
                isAnalysed = (checkedId == R.id.buttonAnalysed);
                displaySelectedSessionData();
                updateToggleColors();
            }
        });

        spinnerSessionNumbers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (availableSessionNumbers.size() > position) {
                    selectedSession = availableSessionNumbers.get(position); // Use number from list
                    displaySelectedSessionData();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Bluetooth
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            registerReceiver(bluetoothReceiver, filter);
            bluetoothReceiverRegistered = true;
        } catch (Exception e) {
            bluetoothReceiverRegistered = false;
            Log.w("DataAnalysis", "Failed to register bluetoothReceiver", e);
        }

        // Register connStateReceiver
        try {
            registerReceiver(connStateReceiver, new IntentFilter("com.example.bledatalogger.ACTION_CONN_STATE"));
            connReceiverRegistered = true;
        } catch (Exception e) {
            connReceiverRegistered = false;
            Log.w("DataAnalysis", "Failed to register connStateReceiver", e);
        }

        // Initialize UI from stored state (in case we missed broadcasts)
        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
        updateBleStatusCircle(adapterOn, storedConnected);

        bleStatusIcon.setOnClickListener(v -> {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivity(enableBtIntent);
            }
        });

        menuIcon.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        NavigationView navigationView = findViewById(R.id.navigationView);
        updateDrawerHeaderUsernameFromPrefs();
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
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START);
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

        // Load dates for current user
        loadAvailableDataDates();
        selectDateButton.setOnClickListener(v -> showDatePicker());
    }

    // Color the toggle buttons based on selection
    private void updateToggleColors() {
        if (isAnalysed) {
            buttonAnalysed.setBackgroundColor(Color.parseColor("#43A047")); // green
            buttonAnalysed.setTextColor(Color.WHITE);
            buttonRaw.setBackgroundColor(Color.parseColor("#E0E0E0")); // light gray
            buttonRaw.setTextColor(Color.BLACK);
        } else {
            buttonRaw.setBackgroundColor(Color.parseColor("#D32F2F")); // red
            buttonRaw.setTextColor(Color.WHITE);
            buttonAnalysed.setBackgroundColor(Color.parseColor("#E0E0E0")); // light gray
            buttonAnalysed.setTextColor(Color.BLACK);
        }
    }

    private void handleDateSelection(String dateString) {
        selectedDate = dateString;
        textViewSelectedDate.setText(dateString);

        boolean available = checkDataAvailability(dateString);
        updateDataAvailabilityLabel(available);

        availableSessionNumbers = getSessionNumbersForDate(dateString, true); // exclude ongoing
        if (availableSessionNumbers.isEmpty()) {
            spinnerSessionNumbers.setEnabled(false);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"No sessions"});
            spinnerSessionNumbers.setAdapter(adapter);
            selectedSession = -1;
            textViewDataDisplay.setText("No session data available for this date.");
        } else {
            spinnerSessionNumbers.setEnabled(true);
            List<String> sessionLabels = new ArrayList<>();
            for (int sessionNum : availableSessionNumbers) {
                sessionLabels.add("Session " + sessionNum);
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sessionLabels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerSessionNumbers.setAdapter(adapter);
            spinnerSessionNumbers.setSelection(0);
            selectedSession = availableSessionNumbers.get(0);
            displaySelectedSessionData();
        }
    }

    // --- NEW: Helpers to resolve per-user Data folder ---

    // returns safe username used for folder names (replace unsafe chars)
    private String getSafeCurrentUser() {
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String user = prefs.getString("current_user", "User");
        if (user == null || user.isEmpty()) user = "User";
        String safe = user.replaceAll("[/,\\\\:;\\*\\?\"<>\\|]", "_").trim().replaceAll("\\s+", "_");
        if (safe.isEmpty()) safe = "User";
        return safe;
    }

    // returns the per-user root: <externalFiles>/<DATA_BASE_ROOT>/Data/<safe-username>
    private File getPerUserDataRoot() {
        File base = new File(getExternalFilesDir(null), DATA_BASE_ROOT);
        File dataRoot = new File(base, "Data");
        File userDir = new File(dataRoot, getSafeCurrentUser());
        if (!userDir.exists()) {
            File analysed = new File(userDir, "Analysed");
            File raw = new File(userDir, "Raw");
            analysed.mkdirs();
            raw.mkdirs();
        } else {
            File analysed = new File(userDir, "Analysed");
            File raw = new File(userDir, "Raw");
            if (!analysed.exists()) analysed.mkdirs();
            if (!raw.exists()) raw.mkdirs();
        }
        return userDir;
    }

    // CHANGED: check analysed availability for current user/date
    private boolean checkDataAvailability(String dateString) {
        File analysedDir = new File(getPerUserDataRoot(), "Analysed/" + dateString);
        File[] files = analysedDir.listFiles((dir, name) -> name.endsWith(".csv"));
        return files != null && files.length > 0;
    }

    private void updateDataAvailabilityLabel(boolean available) {
        if (available) {
            textViewDataAvailability.setText("Available");
            textViewDataAvailability.setTextColor(Color.parseColor("#43A047")); // green
        } else {
            textViewDataAvailability.setText("NA");
            textViewDataAvailability.setTextColor(Color.parseColor("#D32F2F")); // red
        }
    }

    // CHANGED: list session numbers using per-user Raw folder
    private List<Integer> getSessionNumbersForDate(String dateString, boolean excludeOngoing) {
        List<Integer> sessionNumbers = new ArrayList<>();
        File rawDir = new File(getPerUserDataRoot(), "Raw/" + dateString);
        File[] files = rawDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(".csv"));
        if (files == null) return sessionNumbers;
        int latestSession = -1;
        String latestTime = "";
        for (File file : files) {
            String name = file.getName();
            try {
                int idx1 = name.indexOf("_") + 1;
                int idx2 = name.indexOf("_raw_");
                if (idx1 <= 0 || idx2 <= 0) continue;
                int sessionNum = Integer.parseInt(name.substring(idx1, idx2));
                sessionNumbers.add(sessionNum);
                String timeStr = name.substring(name.indexOf("_raw_") + 5, name.length() - 4); // get {timestamp}
                if (latestTime.compareTo(timeStr) < 0) {
                    latestSession = sessionNum;
                    latestTime = timeStr;
                }
            } catch (Exception e) {}
        }
        List<Integer> result;
        if (excludeOngoing) {
            result = new ArrayList<>();
            for (Integer sessionNum : sessionNumbers) {
                File analysedFile = new File(getPerUserDataRoot(),
                        "Analysed/" + dateString + "/session_" + sessionNum + "_analysed.csv");
                if (analysedFile.exists()) {
                    result.add(sessionNum);
                }
            }
        } else {
            result = new ArrayList<>(sessionNumbers);
        }
        Collections.sort(result);
        return result;
    }

    private void displaySelectedSessionData() {
        Log.d("DataAnalysis", "displaySelectedSessionData called");
        if (selectedDate == null || selectedSession == -1) {
            textViewDataDisplay.setText("No session selected.");
            lineChart.setVisibility(View.GONE);
            barChart.setVisibility(View.GONE);
            return;
        }
        String targetSub = isAnalysed ? "Analysed" : "Raw";
        String filePattern = isAnalysed ?
                "session_" + selectedSession + "_analysed.csv" :
                "session_" + selectedSession + "_raw_";
        File dir = new File(getPerUserDataRoot(), targetSub + "/" + selectedDate);

        Log.d("DataAnalysis", "Looking for files in: " + dir.getAbsolutePath());
        File[] files;
        if (isAnalysed) {
            files = dir.listFiles((d, name) -> name.equals(filePattern));
        } else {
            files = dir.listFiles((d, name) -> name.startsWith(filePattern) && name.endsWith(".csv"));
        }
        Log.d("DataAnalysis", "files=" + (files == null ? "null" : files.length));

        if (files == null || files.length == 0) {
            textViewDataDisplay.setText("No data found for selected session.");
            lineChart.setVisibility(View.GONE);
            barChart.setVisibility(View.GONE);
            return;
        }

        if (!isAnalysed) {
            // RAW (LineChart)
            barChart.setVisibility(View.GONE);
            lineChart.setVisibility(View.VISIBLE);

            allEntries.clear();
            allTimeLabels.clear();

            List<LineDataSet> segmentSets = new ArrayList<>();

            List<Entry> currentSegment = new ArrayList<>();
            Integer currentStatus = null;

            int index = 0;
            String startTime = null;
            String endTime = null;

            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(files[0]))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("[,\\t]+");

                    // samples only (markers are 6 columns)
                    if (parts.length != 5) continue;

                    final float accel;
                    try {
                        accel = Float.parseFloat(parts[0].trim());
                    } catch (Exception e) {
                        continue;
                    }

                    int timeDiff;
                    try {
                        timeDiff = Integer.parseInt(parts[1].trim());
                    } catch (Exception e) {
                        continue;
                    }

                    // skip 0,0 points
                    if (accel == 0f && timeDiff == 0) continue;

                    int status;
                    try {
                        status = Integer.parseInt(parts[3].trim()); // 4th column (0/1)
                    } catch (Exception e) {
                        status = 0;
                    }
                    if (status != 0 && status != 1) status = 0;

                    String timestamp = parts[2].trim();

                    // status change -> flush current segment into a dataset
                    // status change -> flush current segment into a dataset
                    if (currentStatus == null) currentStatus = status;

                    if (status != currentStatus) {
                        // Flush old segment if it has points
                        if (!currentSegment.isEmpty()) {
                            LineDataSet seg = new LineDataSet(new ArrayList<>(currentSegment), "");
                            styleSegment(seg, currentStatus);
                            segmentSets.add(seg);
                        }

                        // IMPORTANT: carry last point into the next segment so the line doesn't "break"
                        Entry last = (!currentSegment.isEmpty()) ? currentSegment.get(currentSegment.size() - 1) : null;

                        currentSegment.clear();
                        currentStatus = status;

                        if (last != null) {
                            currentSegment.add(last);
                        }
                    }

                    // add point
                    currentSegment.add(new Entry(index, accel));
                    allTimeLabels.add(timestamp);
                    if (startTime == null) startTime = timestamp;
                    endTime = timestamp;

                    index++;
                }
            } catch (Exception e) {
                textViewDataDisplay.setText("Error reading file: " + e.getMessage());
                lineChart.setVisibility(View.GONE);
                return;
            }

// flush last segment
            if (!currentSegment.isEmpty() && currentStatus != null) {
                LineDataSet seg = new LineDataSet(new ArrayList<>(currentSegment), "");
                styleSegment(seg, currentStatus);
                segmentSets.add(seg);
            }

// render chart
            showChartScrollableAndZoomableSegments(segmentSets, allTimeLabels, startTime, endTime);
            textViewDataDisplay.setText("");
        } else {
            // ANALYSED (BarChart)
            lineChart.setVisibility(View.GONE);
            barChart.setVisibility(View.VISIBLE);
            showAnalysedBarChart(files[0]);
        }
    }

    private void styleSegment(LineDataSet seg, int status) {
        seg.setDrawCircles(false);
        seg.setLineWidth(2f);

        // OPTION B: show values
        seg.setDrawValues(true);
        seg.setValueTextColor(Color.BLACK);
        seg.setValueTextSize(8f);

        seg.setColor(status == 1 ? Color.RED : Color.BLUE);
    }

    private void showChartScrollableAndZoomableSegments(
            List<LineDataSet> sets,
            List<String> allTimeLabels,
            String startTime,
            String endTime
    ) {
        if (startTime != null) textViewSessionStart.setText("Start: " + startTime);
        else textViewSessionStart.setText("Start: -");

        if (endTime != null) textViewSessionEnd.setText("End: " + endTime);
        else textViewSessionEnd.setText("End: -");

        textViewChartStatus.setText("Chart updated for Session " + selectedSession);

        if (lineChart == null) return;

        lineChart.clear();
        lineChart.fitScreen();

        LineData lineData = new LineData();
        for (LineDataSet s : sets) lineData.addDataSet(s);

        lineChart.setData(lineData);

        lineChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int i = (int) value;
                if (i % 3 == 0 && i >= 0 && i < allTimeLabels.size()) return allTimeLabels.get(i);
                return "";
            }
        });
        lineChart.getXAxis().setLabelRotationAngle(0f);
        lineChart.getXAxis().setPosition(com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);

        lineChart.getLegend().setEnabled(false); // looks like one graph
        lineChart.setExtraBottomOffset(20f);

        lineChart.setVisibleXRangeMaximum(VISIBLE_POINTS);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);

        lineChart.moveViewToX(0);
        lineChart.getAxisRight().setEnabled(false);
        lineChart.getDescription().setEnabled(false);

        lineChart.invalidate();
        lineChart.setVisibility(View.VISIBLE);
    }

    private void showAnalysedBarChart(File analysedFile) {

        // Indicate chart update
        textViewChartStatus.setText("Chart updated for Session " + selectedSession);

        List<com.github.mikephil.charting.data.BarEntry> barEntries = new ArrayList<>();
        List<String> startTimes = new ArrayList<>();
        java.util.Map<String, Integer> gradeToY = new java.util.HashMap<>();
        java.util.Map<String, Integer> gradeColorMap = new java.util.HashMap<>();
        // Mapping as per your requirement
        gradeToY.put("normal", 1);
        gradeToY.put("mild", 2);
        gradeToY.put("moderate", 3);
        gradeToY.put("severe", 4);
        gradeColorMap.put("normal", Color.parseColor("#43a047"));
        gradeColorMap.put("mild", Color.parseColor("#ffa726"));
        gradeColorMap.put("moderate", Color.parseColor("#f4511e"));
        gradeColorMap.put("severe", Color.parseColor("#8e24aa"));

        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(analysedFile))) {
            String line;
            boolean isHeader = true;
            int idx = 0;
            while ((line = br.readLine()) != null) {
                if (isHeader) { isHeader = false; continue; }
                String[] parts = line.split("[,\\t]+");
                if (parts.length < 9) continue;
                String startTime = parts[1].trim();
                String grade = parts[8].trim().toLowerCase();
                Integer yValue = gradeToY.get(grade);
                if (yValue == null) continue;
                barEntries.add(new com.github.mikephil.charting.data.BarEntry(idx, yValue, grade));
                startTimes.add(startTime);
                idx++;
            }
        } catch (Exception e) {
            textViewDataDisplay.setText("Error reading analysed file: " + e.getMessage());
            barChart.setVisibility(View.GONE);
            return;
        }

        if (barEntries.isEmpty()) {
            textViewDataDisplay.setText("No events found in analysed data.");
            barChart.setVisibility(View.GONE);
            return;
        }

        barChart.clear();
        barChart.fitScreen();

        com.github.mikephil.charting.data.BarDataSet dataSet = new com.github.mikephil.charting.data.BarDataSet(barEntries, "Tremor Events");
        dataSet.setDrawValues(false);
        List<Integer> barColors = new ArrayList<>();
        for (com.github.mikephil.charting.data.BarEntry entry : barEntries) {
            String grade = (String) entry.getData();
            Integer color = gradeColorMap.get(grade);
            if (color == null) color = Color.GRAY;
            barColors.add(color);
        }
        dataSet.setColors(barColors);

        com.github.mikephil.charting.data.BarData barData = new com.github.mikephil.charting.data.BarData(dataSet);
        barData.setBarWidth(0.35f); // Thinner bars!

        barChart.setData(barData);

        // X axis: show each start_time as label
        barChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int i = (int) value;
                if (i >= 0 && i < startTimes.size()) return startTimes.get(i);
                return "";
            }
        });
        barChart.getXAxis().setPosition(com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);
        barChart.getXAxis().setGranularity(1f);
        barChart.getXAxis().setGranularityEnabled(true);
        barChart.getXAxis().setLabelRotationAngle(0f);
        barChart.getXAxis().setAxisMinimum(-0.5f);
        barChart.getXAxis().setAxisMaximum(barEntries.size() - 0.5f);
        barChart.getXAxis().setDrawGridLines(false);

        // Y axis: categorical with extra 0 and 5
        barChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                switch ((int) value) {
                    case 1: return "Normal";
                    case 2: return "Mild";
                    case 3: return "Moderate";
                    case 4: return "Severe";
                    default: return ""; // 0 and 5 are blank
                }
            }
        });
        barChart.getAxisLeft().setAxisMinimum(0f);
        barChart.getAxisLeft().setAxisMaximum(5f);
        barChart.getAxisLeft().setGranularity(1f);
        barChart.getAxisLeft().setGranularityEnabled(true);
        barChart.getAxisLeft().setDrawGridLines(true);
        barChart.getAxisLeft().setLabelCount(6, true); // for 0,1,2,3,4,5
        barChart.getAxisRight().setEnabled(false);

        // Make scrollable (show 5 events at a time)
        barChart.setVisibleXRangeMaximum(5f);
        barChart.setDragEnabled(true);
        barChart.setScaleEnabled(false);
        barChart.setPinchZoom(false);
        barChart.moveViewToX(0);

        barChart.getLegend().setEnabled(false);

        barChart.getDescription().setEnabled(false);
        barChart.invalidate();
        barChart.setVisibility(View.VISIBLE);
        textViewDataDisplay.setText("");

        Legend legend = barChart.getLegend();
        legend.setEnabled(true); // make sure it's visible

        // Build custom legend entries
        LegendEntry entryNormal = new LegendEntry();
        entryNormal.label = "Normal";
        entryNormal.formColor = Color.parseColor("#43a047"); // replace with your actual color

        LegendEntry entryMild = new LegendEntry();
        entryMild.label = "Mild";
        entryMild.formColor = Color.parseColor("#ffa726");

        LegendEntry entryModerate = new LegendEntry();
        entryModerate.label = "Moderate";
        entryModerate.formColor = Color.parseColor("#f4511e");

        LegendEntry entrySevere = new LegendEntry();
        entrySevere.label = "Severe";
        entrySevere.formColor = Color.parseColor("#8e24aa");

        // Assign the custom legend
        legend.setCustom(new LegendEntry[]{entryNormal, entryMild, entryModerate, entrySevere});
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        barChart.setExtraBottomOffset(20f); // optional, adds space below x-axis
    }

    // Show chart with scroll and zoom features, showing only VISIBLE_POINTS at a time
    private void showChartScrollableAndZoomable(List<Entry> allEntries, List<String> allTimeLabels, String startTime, String endTime) {
        if (startTime != null) {
            textViewSessionStart.setText("Start: " + startTime);
        } else {
            textViewSessionStart.setText("Start: -");
        }
        if (endTime != null) {
            textViewSessionEnd.setText("End: " + endTime);
        } else {
            textViewSessionEnd.setText("End: -");
        }

        // Indicate chart update
        textViewChartStatus.setText("Chart updated for Session " + selectedSession);

        if (lineChart == null) return;

        lineChart.clear();         // Remove previous data and state
        lineChart.fitScreen();     // Reset zoom/pan

        LineDataSet dataSet = new LineDataSet(allEntries, "Accel Magnitude");
        dataSet.setColor(Color.BLUE);
        dataSet.setDrawCircles(false);
        dataSet.setLineWidth(2f);

        LineData lineData = new LineData(dataSet);
        lineChart.setData(lineData);

        // Custom X axis labels: every 3rd, horizontally, at the bottom
        lineChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int i = (int) value;
                if (i % 3 == 0 && i >= 0 && i < allTimeLabels.size()) {
                    return allTimeLabels.get(i);
                } else {
                    return "";
                }
            }
        });
        lineChart.getXAxis().setLabelRotationAngle(0f);
        lineChart.getXAxis().setPosition(com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);

        lineChart.getLegend().setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        lineChart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
//        lineChart.getLegend().setYOffset(40f); // Increase to your liking (pixels)
        lineChart.setExtraBottomOffset(20f); // Increase this value to add more space below the X-axis

        lineChart.setVisibleXRangeMaximum(VISIBLE_POINTS);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);

        lineChart.moveViewToX(0); // Always start from the beginning
        lineChart.getAxisRight().setEnabled(false); // <-- Add this line

        lineChart.getDescription().setEnabled(false);
        lineChart.invalidate();
        lineChart.setVisibility(View.VISIBLE);
    }

    private void loadAvailableDataDates() {
        availableDataDates.clear();
        File analysedRoot = new File(getPerUserDataRoot(), "Analysed");
        if (!analysedRoot.exists()) analysedRoot.mkdirs();
        File[] folders = analysedRoot.listFiles();
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
        CalendarConstraints.Builder constraintsBuilder = new CalendarConstraints.Builder();
        MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select a date to view data")
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .setCalendarConstraints(constraintsBuilder.build())
                .build();
        datePicker.addOnPositiveButtonClickListener(selection -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            String pickedDate = sdf.format(new Date(selection));
            handleDateSelection(pickedDate);
        });
        datePicker.show(getSupportFragmentManager(), "DATE_PICKER");
    }

    private boolean isValidDateFormat(String folderName) {
        return folderName.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver);
                Log.d("DataAnalysis", "bluetoothReceiver unregistered");
            } catch (IllegalArgumentException ignored) {}
            bluetoothReceiverRegistered = false;
        }
        if (connReceiverRegistered) {
            try {
                unregisterReceiver(connStateReceiver);
                Log.d("DataAnalysis", "connStateReceiver unregistered");
            } catch (IllegalArgumentException ignored) {}
            connReceiverRegistered = false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
        Log.d("DataAnalysis", "onNewIntent: adapterOn=" + adapterOn + " storedConnected=" + storedConnected);
        updateBleStatusCircle(adapterOn, storedConnected);

        // If forced reset was requested earlier and Main activity performed it, the current_user might have changed.
        // Refresh available dates for the (possibly) new current user.
        loadAvailableDataDates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
        Log.d("DataAnalysis", "onResume: adapterOn=" + adapterOn + " storedConnected=" + storedConnected);
        updateBleStatusCircle(adapterOn, storedConnected);
        updateDrawerHeaderUsernameFromPrefs();

        // reload available dates in case user switched while we were paused
        loadAvailableDataDates();
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

    // paste inside the Activity class (MainActivity, DataAnalysisActivity, SettingsActivity, UserSettingsActivity)
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
            drawerUsername.setText(current == null || current.isEmpty() ? "User" : current);
        } catch (Exception e) {
//            Log.w("ActivityName", "updateDrawerHeaderUsernameFromPrefs failed", e);
        }
    }

    // ── Export PDF ──────────────────────────────────────────────────────────────

    /** Show the date-range selection dialog for PDF export. */
    private void showExportDialog() {
        int dp8 = Math.round(8 * getResources().getDisplayMetrics().density);
        int dp16 = dp8 * 2;
        int dp24 = dp8 * 3;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp24, dp16, dp24, dp8);

        // "From" label
        TextView tvFromLabel = new TextView(this);
        tvFromLabel.setText("From Date (required)");
        tvFromLabel.setTextSize(13f);
        tvFromLabel.setTextColor(Color.parseColor("#757575"));
        layout.addView(tvFromLabel);

        Button btnFrom = new Button(this);
        btnFrom.setText("Select From Date");
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpBtn.setMargins(0, dp8, 0, dp16);
        btnFrom.setLayoutParams(lpBtn);
        layout.addView(btnFrom);

        // "To" label
        TextView tvToLabel = new TextView(this);
        tvToLabel.setText("To Date (optional – leave blank for single date)");
        tvToLabel.setTextSize(13f);
        tvToLabel.setTextColor(Color.parseColor("#757575"));
        layout.addView(tvToLabel);

        Button btnTo = new Button(this);
        btnTo.setText("Not selected (single date)");
        LinearLayout.LayoutParams lpBtn2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpBtn2.setMargins(0, dp8, 0, dp16);
        btnTo.setLayoutParams(lpBtn2);
        layout.addView(btnTo);

        // Generate button (disabled until From chosen)
        Button btnGenerate = new Button(this);
        btnGenerate.setText("Generate Report");
        btnGenerate.setEnabled(false);
        btnGenerate.setBackgroundColor(Color.parseColor("#E0E0E0"));
        btnGenerate.setTextColor(Color.parseColor("#9E9E9E"));
        LinearLayout.LayoutParams lpGen = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpGen.setMargins(0, dp8, 0, 0);
        btnGenerate.setLayoutParams(lpGen);
        layout.addView(btnGenerate);

        SimpleDateFormat sdfDialog = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        final String[] fromDate = {null};
        final String[] toDate = {null};

        AlertDialog exportDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Export PDF Report")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .create();

        btnFrom.setOnClickListener(v -> {
            MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText("Select From Date")
                    .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                    .build();
            picker.addOnPositiveButtonClickListener(sel -> {
                fromDate[0] = sdfDialog.format(new Date(sel));
                btnFrom.setText(fromDate[0]);
                btnGenerate.setEnabled(true);
                btnGenerate.setBackgroundColor(Color.parseColor("#1565C0"));
                btnGenerate.setTextColor(Color.WHITE);
            });
            picker.show(getSupportFragmentManager(), "EXPORT_FROM_DATE");
        });

        btnTo.setOnClickListener(v -> {
            MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText("Select To Date (optional)")
                    .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                    .build();
            picker.addOnPositiveButtonClickListener(sel -> {
                toDate[0] = sdfDialog.format(new Date(sel));
                btnTo.setText(toDate[0]);
            });
            picker.show(getSupportFragmentManager(), "EXPORT_TO_DATE");
        });

        btnGenerate.setOnClickListener(v -> {
            if (fromDate[0] == null) return;
            String from = fromDate[0];
            String to = toDate[0] != null ? toDate[0] : from;
            if (to.compareTo(from) < 0) {
                Toast.makeText(this, "'To' date cannot be before 'From' date", Toast.LENGTH_SHORT).show();
                return;
            }
            exportDialog.dismiss();
            startPdfGeneration(from, to);
        });

        exportDialog.show();
    }

    /** Show loading dialog, call TremorReportGenerator on a background thread. */
    private void startPdfGeneration(String fromDate, String toDate) {
        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Generating Report")
                .setMessage("Please wait, building PDF…")
                .setCancelable(false)
                .create();
        progressDialog.show();

        String username = getSharedPreferences("UserPrefs", MODE_PRIVATE)
                .getString("current_user", "User");

        TremorReportGenerator.generate(
                this,
                username,
                fromDate,
                toDate,
                getPerUserDataRoot(),
                new TremorReportGenerator.ReportCallback() {
                    @Override
                    public void onSuccess(File pdfFile) {
                        progressDialog.dismiss();
                        showPdfResultDialog(pdfFile);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        progressDialog.dismiss();
                        Toast.makeText(DataAnalysisActivity.this,
                                "Report error: " + errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** Show Open / Share / OK dialog after PDF is ready. */
    private void showPdfResultDialog(File pdfFile) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Report Ready")
                .setMessage(pdfFile.getName())
                .setPositiveButton("Open", (d, w) -> openPdf(pdfFile))
                .setNeutralButton("Share", (d, w) -> sharePdf(pdfFile))
                .setNegativeButton("OK", null)
                .show();
    }

    private void openPdf(File pdfFile) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, "com.example.igest_v3.fileprovider", pdfFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open PDF"));
        } catch (Exception e) {
            Toast.makeText(this, "No PDF viewer found", Toast.LENGTH_SHORT).show();
        }
    }

    private void sharePdf(File pdfFile) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, "com.example.igest_v3.fileprovider", pdfFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share Report"));
        } catch (Exception e) {
            Toast.makeText(this, "Unable to share file", Toast.LENGTH_SHORT).show();
        }
    }
}