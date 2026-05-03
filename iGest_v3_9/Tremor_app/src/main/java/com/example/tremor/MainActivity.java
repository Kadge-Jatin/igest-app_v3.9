package com.example.tremor;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch; // use correct Switch import

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.io.BufferedWriter;
import java.io.FileWriter;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import android.graphics.Color;

import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.navigation.NavigationView;
import androidx.core.view.GravityCompat;
import android.content.res.ColorStateList;

public class MainActivity extends AppCompatActivity implements BleManager.BleCallback {

    // base folder (app root). We will place per-user data under: <base>/Data/<safe-username>/{Raw,Analysed,Data}
    private static final String DATA_BASE_ROOT = "iGest: Tremor Detection";
    private static final String ACTION_PERFORM_RESET = "com.example.bledatalogger.ACTION_PERFORM_RESET";
    // add near other fields in MainActivity
    private boolean modeSentForCurrentConnection = false;

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;

    private static final String PREFS_NAME = "BlePrefs";
    private static final String PREF_LAST_CODE = "LastCode";
    private static final String NOTIF_CHANNEL_ID = "ble_status_channel";
    private static final int NOTIF_ID = 12345;

    private EditText editTextCode;
    private Button buttonConnect;
    private Button buttonReset;
    private TextView textViewStatus;
    private TextView textViewDevice;
    private TextView textViewVersion;
    private TextView textViewTremorStatus;
    private TextView textViewTremorData;
    private TextView textViewTremorRaw;

    private ImageView bleStatusIcon;

    private BleManager bleManager;
    private CsvWriter csvWriter;
    private boolean isConnected = false;
    private String currentDeviceCode = null;

    private Handler tremorTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable tremorTimeoutRunnable;
    private static final int TREMOR_STATUS_TIMEOUT_MS = 500;

    private Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;
    private static final int RECONNECT_INTERVAL_MS = 5000;

    private String currentCsvDate = null;
    private int currentSessionNumber = 0;
    private boolean pendingDateSwitch = false;
    private boolean tremorActive = false;
    private boolean sessionActive = false;

    private final HashMap<String, DeviceInfo> bleDeviceMap = new HashMap<>();

    private Switch toggleLivePlot;
    private LineChart accelChart;
    private TextView textViewNoLive;
    private List<Entry> accelEntries = new ArrayList<>();
    private int plotIndex = 0;
    private boolean showLivePlot = false;

    private DrawerLayout drawerLayout;
    private ImageView menuIcon;

    // Signals used between activities
    private static final String ACTION_CLOSE_SESSION = "com.example.bledatalogger.ACTION_CLOSE_SESSION";
    private static final String ACTION_START_NEW_SESSION = "com.example.bledatalogger.ACTION_START_NEW_SESSION";

    // Caretaker observational recording (adds 5th column in sample rows)
    private Button buttonObservedRecord;
    private TextView textViewObservedTimer;

    private boolean observedRecording = false;
    private long observedStartMs = 0L;

    private final Handler observedTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable observedTimerRunnable;

    // --- Flash sync state machine ---
    // Marker bytes must match the sender's #define values exactly
    private static final byte[] SYNC_BLE_START = {(byte)0xAA, (byte)0xBB, (byte)0xCC, (byte)0xDD};
    private static final byte[] SYNC_BLE_END   = {(byte)0xFF, (byte)0xEE, (byte)0xDD, (byte)0xCC};
    /** Each flash record is 9 bytes: 4 accelMag + 2 timeDiff + 2 rtcTime + 1 status */
    private static final int FLASH_RECORD_SIZE = 9;
    /** Max live rows buffered during a sync (15 min × 60 s × 100 Hz = 90 000) */
    private static final int LIVE_BUFFER_MAX   = 90_000;

    private static final int FLASH_SYNC_IDLE       = 0;
    private static final int FLASH_SYNC_AWAIT_SIZE = 1;
    private static final int FLASH_SYNC_ACTIVE     = 2;

    private int    flashSyncState    = FLASH_SYNC_IDLE;
    private int    syncExpectedBytes = 0;
    private int    syncReceivedBytes = 0;
    private boolean liveBufferDropped = false;
    /** Accumulates partial flash records across BLE chunks */
    private final  ByteArrayOutputStream syncReassemblyBuffer = new ByteArrayOutputStream(256);
    /** Buffers live-data rows that arrive while a flash sync is in progress */
    private final  ArrayDeque<Object[]>   liveDataBuffer       = new ArrayDeque<>();

    @Override
    protected void onResume() {
        super.onResume();
        // close drawer if open and update header
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            updateDrawerHeaderUsernameFromPrefs();
        }
        // update header username
        try {
            NavigationView navigationView = findViewById(R.id.navigationView);
            updateDrawerHeaderUsernameFromPrefs();
            if (navigationView != null) {
                View headerView = navigationView.getHeaderView(0);
                TextView drawerUsername = headerView.findViewById(R.id.drawerUsername);
                String currentUser = getSharedPreferences("UserPrefs", MODE_PRIVATE).getString("current_user", "User");
                drawerUsername.setText(currentUser);
            }
        } catch (Exception ignored) {}

        // Check if UserSettings requested a forced reset (robust & reliable)
        try { performResetIfRequested(); } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.tremor_activity_main);

        drawerLayout = findViewById(R.id.drawerLayout);
        drawerLayout.setStatusBarBackgroundColor(ContextCompat.getColor(this, R.color.brand_dark_blue));
        final int originalStatusBarColor = getWindow().getStatusBarColor();
        final int originalUiFlags = getWindow().getDecorView().getSystemUiVisibility();
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    getWindow().setStatusBarColor(ContextCompat.getColor(MainActivity.this, R.color.brand_dark_blue));
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    getWindow().getDecorView().setSystemUiVisibility(0);
                }
            }
            @Override
            public void onDrawerClosed(View drawerView) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    getWindow().setStatusBarColor(originalStatusBarColor);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    getWindow().getDecorView().setSystemUiVisibility(originalUiFlags);
                }
            }
        });

        menuIcon = findViewById(R.id.menuIcon);

        NavigationView navigationView = findViewById(R.id.navigationView);
        updateDrawerHeaderUsernameFromPrefs();
        View headerView = navigationView.getHeaderView(0);
        LinearLayout headerUserRow = headerView.findViewById(R.id.headerUserRow);
        navigationView.setCheckedItem(R.id.nav_home);

        TextView drawerUsername = headerView.findViewById(R.id.drawerUsername);
        String currentUser = getSharedPreferences("UserPrefs", MODE_PRIVATE).getString("current_user", "User");
        drawerUsername.setText(currentUser);

        // make header open UserSettings (you may prefer reorder-to-front but keeping as existing)
        headerUserRow.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, UserSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        menuIcon.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        // ensure navigation uses reorder-to-front to avoid duplicate instances (consistent behavior)
        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_home) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
            } else if (id == R.id.nav_data_analysis) {
                Intent intent = new Intent(MainActivity.this, DataAnalysisActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } else if (id == R.id.nav_about_us) {
                Intent intent = new Intent(MainActivity.this, AboutUsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        initializeViews();
        initializeBleDeviceMap();
        checkPermissions();
        initializeBluetooth();

        toggleLivePlot = findViewById(R.id.toggleLivePlot);
        accelChart = findViewById(R.id.accelChart);
        textViewNoLive = findViewById(R.id.textViewNoLive);

        // TREMOR mode: data comes via Flash UUID — hide live streaming UI
        View livePlotsHeader = findViewById(R.id.livePlotsHeader);
        if (livePlotsHeader != null) livePlotsHeader.setVisibility(View.GONE);
        accelChart.setVisibility(View.GONE);
        textViewNoLive.setText("TREMOR Mode: Data recorded to flash, synced periodically");
        textViewNoLive.setVisibility(View.VISIBLE);

        // Hide Caretaker Observation panel — not used in TREMOR mode
        View observationSection = findViewById(R.id.observationSection);
        if (observationSection != null) observationSection.setVisibility(View.GONE);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        updateBleStatusIcon(bluetoothAdapter != null && bluetoothAdapter.isEnabled(), isConnected);

        createNotificationChannel();
        // register bluetooth state receiver (handles adapter on/off)
        registerReceiver(bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        // register closeSessionReceiver for actions from UserSettingsActivity
        // RECEIVER_NOT_EXPORTED: these actions are only ever sent from within this app
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_CLOSE_SESSION);
            f.addAction(ACTION_START_NEW_SESSION);
            f.addAction(ACTION_PERFORM_RESET);
            androidx.core.content.ContextCompat.registerReceiver(
                    this, closeSessionReceiver, f,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception ignored) {}

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean wasSessionActive = prefs.getBoolean("sessionActive", false);
        if (wasSessionActive) {
            String savedDate = prefs.getString("currentCsvDate", null);
            int savedSession = prefs.getInt("currentSessionNumber", -1);
            if (savedDate != null && savedSession > 0) {
                currentCsvDate = savedDate;
                currentSessionNumber = savedSession;
                new Thread(() -> {
                    try {
                        recoverAndFinalisePreviousSession();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }).start();
            }
        }
        String lastCode = prefs.getString(PREF_LAST_CODE, null);
        if (lastCode != null) {
            currentDeviceCode = lastCode;
            editTextCode.setText(lastCode);
            editTextCode.setEnabled(false);
            currentCsvDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            currentSessionNumber = getLastSessionNumberForDate(currentCsvDate);
            startReconnection();
        } else {
            setStatusDisconnected();
            setTremorStatusDisconnected();
            buttonConnect.setEnabled(false);
        }
    }

    private void recoverAndFinalisePreviousSession() {
        try {
            if (currentCsvDate == null || currentSessionNumber <= 0) return;

            // Use per-user Raw folder
            File userDir = getCurrentUserDataDir();
            File rawDir = new File(userDir, "Raw/" + currentCsvDate);
            if (!rawDir.exists()) return;
            File[] files = rawDir.listFiles();
            if (files == null || files.length == 0) return;

            File rawFile = null;
            for (File f : files) {
                if (f.getName().startsWith("session_" + currentSessionNumber + "_raw_")) {
                    rawFile = f;
                    break;
                }
            }
            if (rawFile == null) {
                int lastSessionNum = getLastSessionNumberForDate(currentCsvDate);
                for (File f : files) {
                    if (f.getName().startsWith("session_" + lastSessionNum + "_raw_")) {
                        rawFile = f;
                        currentSessionNumber = lastSessionNum;
                        break;
                    }
                }
            }
            if (rawFile == null) return;

            List<TremorAnalysisUtils.TremorEventResult> results = TremorAnalysisUtils.analyseSession(rawFile, 100.0);

            File analysedDir = new File(getCurrentUserDataDir(), "Analysed/" + currentCsvDate);
            if (!analysedDir.exists()) analysedDir.mkdirs();
            File temp = new File(analysedDir, "session_" + currentSessionNumber + "_analysed.csv.tmp");
            File finalFile = new File(analysedDir, "session_" + currentSessionNumber + "_analysed.csv");

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(temp))) {
                bw.write("event_num,start_time,end_time,dominant_freq_Hz,window_count,median_xp2p_cm,percentile90_xp2p_cm,max_xp2p_cm,grade\n");
                for (int i = 0; i < results.size(); i++) {
                    TremorAnalysisUtils.TremorEventResult r = results.get(i);
                    bw.write(String.format(Locale.US,
                            "%d,%s,%s,%.2f,%d,%.2f,%.2f,%.2f,%s\n",
                            i+1, r.startTime, r.endTime, r.dominantFreqHz, r.windowCount,
                            r.medianXp2p, r.percentile90Xp2p, r.maxXp2p, r.grade));
                }
                bw.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (temp.exists()) {
                try { temp.renameTo(finalFile); } catch (Exception ignore) {}
            }

            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.remove("currentCsvDate");
            editor.remove("currentSessionNumber");
            editor.putBoolean("sessionActive", false);
            editor.apply();

            try {
                sendBroadcast(new Intent("com.example.bledatalogger.ACTION_DATA_UPDATED"));
            } catch (Exception ignored) {}

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void setupChart(LineChart chart, int color) {
        chart.setBackgroundColor(Color.BLACK);
        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        chart.setTouchEnabled(false);
        chart.setScaleEnabled(false);
        chart.getLegend().setEnabled(false);
        XAxis xAxis = chart.getXAxis();
        xAxis.setEnabled(false);
        YAxis rightAxis = chart.getAxisRight();
        rightAxis.setEnabled(false);
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setTextColor(Color.WHITE);
    }

    private void updateLivePlots() {
        if (!showLivePlot) return;
        int maxPoints = 200;
        while (accelEntries.size() > maxPoints) accelEntries.remove(0);

        LineDataSet accelSet = new LineDataSet(accelEntries, "Accel");
        accelSet.setColor(observedRecording ? Color.RED : Color.BLUE);
        accelSet.setDrawCircles(false);
        accelSet.setDrawValues(false);
        accelSet.setLineWidth(2f);
        accelSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        LineData accelData = new LineData(accelSet);
        accelChart.setData(accelData);
        accelChart.setVisibleXRangeMaximum(50);
        accelChart.moveViewToX(Math.max(0, plotIndex - 1));
        accelChart.notifyDataSetChanged();
        accelChart.invalidate();
    }

    private void clearPlots() {
        accelEntries.clear();
        plotIndex = 0;
        if (accelChart != null) accelChart.clear();
    }

    private void initializeViews() {
        editTextCode = findViewById(R.id.editTextCode);
        buttonConnect = findViewById(R.id.buttonConnect);
        buttonReset = findViewById(R.id.buttonReset);
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewDevice = findViewById(R.id.textViewDevice);
        textViewVersion = findViewById(R.id.textViewVersion);
        textViewTremorStatus = findViewById(R.id.textViewTremorStatus);
        textViewTremorData = findViewById(R.id.textViewTremorData);
        textViewTremorRaw = findViewById(R.id.textViewTremorRaw);
        bleStatusIcon = findViewById(R.id.bleStatusIcon);

        // TREMOR mode initial status hint
        if (textViewTremorStatus != null)
            textViewTremorStatus.setText("Status: Flash Recording");
        if (textViewTremorData != null)
            textViewTremorData.setText("Data: Sync on reconnection");

        // Observation controls
        buttonObservedRecord = findViewById(R.id.buttonObservedRecord);
        textViewObservedTimer = findViewById(R.id.textViewObservedTimer);

        if (textViewObservedTimer != null) {
            textViewObservedTimer.setText("00:00");
        }

        // Start in STOPPED state
        setObservedUiState(false);
        updateObservedButtonEnabled();

        if (buttonObservedRecord != null) {
            buttonObservedRecord.setOnClickListener(v -> {
                // Toggle press-to-start / press-to-stop
                setObservedUiState(!observedRecording);

                // Force live plot to refresh color immediately (if enabled)
                if (showLivePlot) updateLivePlots();
            });
        }

        bleStatusIcon.setOnClickListener(v -> {
            BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter == null) {
                Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
                        return;
                    }
                }
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                if (!isConnected) {
                    Toast.makeText(this, "Bluetooth is ON", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Connected to device", Toast.LENGTH_SHORT).show();
                }
            }
        });

        editTextCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                buttonConnect.setEnabled(!s.toString().trim().isEmpty());
            }
        });

        buttonConnect.setEnabled(!editTextCode.getText().toString().trim().isEmpty());

        buttonConnect.setOnClickListener(v -> {
            String code = editTextCode.getText().toString().trim();
            tryConnectToDevice(code);
        });

        buttonReset.setOnClickListener(v -> {
            // (optional) log for debugging
            Log.d("MainActivity","buttonReset clicked by user/automation");
            if (bleManager != null) {
                stopReconnection();
                isConnected = false;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("ble_connected", false).apply();
                Intent connIntent = new Intent("com.example.bledatalogger.ACTION_CONN_STATE");
                connIntent.putExtra("connected", false);
                sendBroadcast(connIntent);

                bleManager.disconnect();
                bleManager = null;

                BluetoothAdapter btAdapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
                updateBleStatusIcon(btAdapter != null && btAdapter.isEnabled(), false);
            }

            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.remove(PREF_LAST_CODE);
            editor.apply();

            editTextCode.setText("");
            editTextCode.setEnabled(true);

            setStatusDisconnected();
            setTremorStatusDisconnected();
            buttonConnect.setEnabled(false);
            currentDeviceCode = null;
            closeSessionCsv();

            currentCsvDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            currentSessionNumber = getLastSessionNumberForDate(currentCsvDate);
            pendingDateSwitch = false;

            TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
            textViewFileLocation.setText("");
            stopReconnection();
        });
    }

    private static class DeviceInfo {
        String address;
        String name;
        String version;
        DeviceInfo(String address, String name, String version) {
            this.address = address;
            this.name = name;
            this.version = version;
        }
    }

    private void initializeBleDeviceMap() {
        bleDeviceMap.put("1803", new DeviceInfo("8B:67:BC:18:6F:03", "iGest _01", "TD_v1"));
//        bleDeviceMap.put("0672", new DeviceInfo("24:BA-84-06-B2-72".replace("-", ":"), "iGest _02", "TD_v1"));
        bleDeviceMap.put("7776", new DeviceInfo( "A0:DD:6C:73:77:76","iGest _02", "TD_v2"));
        bleDeviceMap.put("6373", new DeviceInfo("A0:DD:6C:B3:7E:3E", "iGest _03", "TD_v2"));
        bleDeviceMap.put("2892", new DeviceInfo("2C:BC:BB:A8:9B:B2", "iGest _04", "TD_v2"));
    }

    private void applyObservedButtonStyle() {
        if (buttonObservedRecord == null) return;

        int blue = ContextCompat.getColor(this, R.color.brand_dark_blue);
        int red = ContextCompat.getColor(this, android.R.color.holo_red_dark);
        int disabledGrey = Color.parseColor("#BDBDBD");

        boolean enabled = buttonObservedRecord.isEnabled();
        int bg = !enabled ? disabledGrey : (observedRecording ? red : blue);

        buttonObservedRecord.setBackgroundTintList(ColorStateList.valueOf(bg));
    }

    private void updateObservedButtonEnabled() {
        if (buttonObservedRecord == null) return;
        buttonObservedRecord.setEnabled(isConnected && sessionActive);
        applyObservedButtonStyle();
    }

    private void setObservedUiState(boolean recording) {
        observedRecording = recording;

        if (buttonObservedRecord != null) {
            buttonObservedRecord.setText(recording ? "STOP" : "RECORD");
        }

        applyObservedButtonStyle();

        if (recording) {
            observedStartMs = System.currentTimeMillis();
            startObservedTimer();
        } else {
            observedStartMs = 0L;
            stopObservedTimer();
            if (textViewObservedTimer != null) textViewObservedTimer.setText("00:00");
        }
    }

    private void startObservedTimer() {
        stopObservedTimer();

        observedTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!observedRecording || observedStartMs == 0L) return;

                long elapsedMs = System.currentTimeMillis() - observedStartMs;
                long totalSec = elapsedMs / 1000;
                long mm = totalSec / 60;
                long ss = totalSec % 60;

                if (textViewObservedTimer != null) {
                    textViewObservedTimer.setText(String.format(Locale.US, "%02d:%02d", mm, ss));
                }
                observedTimerHandler.postDelayed(this, 1000);
            }
        };
        observedTimerHandler.post(observedTimerRunnable);
    }

    private void stopObservedTimer() {
        if (observedTimerRunnable != null) {
            observedTimerHandler.removeCallbacks(observedTimerRunnable);
            observedTimerRunnable = null;
        }
    }

    private void updateBleStatusIcon(boolean bleOn, boolean deviceConnected) {
        if (bleStatusIcon == null) return;
        if (!bleOn) {
            bleStatusIcon.setImageResource(R.drawable.bluetooth_off);
            bleStatusIcon.setContentDescription(getString(R.string.ble_status_off));
            bleStatusIcon.setImageTintList(null);
        } else {
            if (deviceConnected) {
                bleStatusIcon.setImageResource(R.drawable.bluetooth_connected);
                bleStatusIcon.setContentDescription(getString(R.string.ble_status_connected));
            } else {
                bleStatusIcon.setImageResource(R.drawable.bluetooth_on);
                bleStatusIcon.setContentDescription(getString(R.string.ble_status_on));
            }
            bleStatusIcon.setImageTintList(null);
        }
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void initializeBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
                    return;
                }
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        bleManager = null;
    }

    private int getLastSessionNumberForDate(String date) {
        File userDir = getCurrentUserDataDir();
        File rootDir = new File(userDir, "Raw/" + date);
        int maxSession = 0;
        if (!rootDir.exists()) return 0;
        File[] files = rootDir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("session_") && name.contains("_raw_")) {
                try {
                    String withoutPrefix = name.substring("session_".length());
                    int sep = withoutPrefix.indexOf("_");
                    if (sep > 0) {
                        int number = Integer.parseInt(withoutPrefix.substring(0, sep));
                        if (number > maxSession) maxSession = number;
                    }
                } catch (Exception e) { }
            }
        }
        return maxSession;
    }

    private void startNewSessionCsv() {
        if (sessionActive) return;
        sessionActive = true;

        currentSessionNumber = getLastSessionNumberForDate(currentCsvDate) + 1;

        File rootDir = new File(getCurrentUserDataDir(), "Raw/" + currentCsvDate);
        if (!rootDir.exists()) rootDir.mkdirs();

        String timestamp = new SimpleDateFormat("HHmmss", Locale.getDefault()).format(new Date());
        String filename = "session_" + currentSessionNumber + "_raw_" + timestamp + ".csv";
        File csvFile = new File(rootDir, filename);

        if (csvWriter != null) csvWriter.close();
        csvWriter = new CsvWriter(csvFile);

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString("currentCsvDate", currentCsvDate);
        editor.putInt("currentSessionNumber", currentSessionNumber);
        editor.putBoolean("sessionActive", true);
        editor.apply();

        TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
        textViewFileLocation.setText(
                "Current file: " + currentCsvDate + "/session_" + currentSessionNumber + "_raw_" + timestamp + ".csv"
        );

        updateObservedButtonEnabled();
    }

    private void closeSessionCsv() {
        setObservedUiState(false);
        updateObservedButtonEnabled();

        // If a flash sync was in progress, safely terminate it before closing the session file
        if (flashSyncState != FLASH_SYNC_IDLE) {
            for (Object[] row : liveDataBuffer) {
                if (csvWriter != null) csvWriter.writeRow(row);
            }
            liveDataBuffer.clear();
            syncReassemblyBuffer.reset();
            flashSyncState = FLASH_SYNC_IDLE;
        }

        if (csvWriter != null) {
            try {
                csvWriter.close();
            } catch (Exception ignored) {}
            csvWriter = null;
        }
        sessionActive = false;

        if (currentCsvDate == null) {
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.remove("currentCsvDate");
            editor.remove("currentSessionNumber");
            editor.putBoolean("sessionActive", false);
            editor.apply();
            return;
        }

        try {
            File rawDir = new File(getCurrentUserDataDir(), "Raw/" + currentCsvDate);
            File analysedDir = new File(getCurrentUserDataDir(), "Analysed/" + currentCsvDate);
            if (!analysedDir.exists()) analysedDir.mkdirs();

            if (rawDir == null || !rawDir.exists()) {
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.remove("currentCsvDate");
                editor.remove("currentSessionNumber");
                editor.putBoolean("sessionActive", false);
                editor.apply();
                return;
            }

            File[] rawFiles = rawDir.listFiles();
            if (rawFiles == null || rawFiles.length == 0) {
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.remove("currentCsvDate");
                editor.remove("currentSessionNumber");
                editor.putBoolean("sessionActive", false);
                editor.apply();
                return;
            }

            int targetSession = (currentSessionNumber > 0) ? currentSessionNumber : getLastSessionNumberForDate(currentCsvDate);

            File rawFile = null;
            for (File f : rawFiles) {
                if (f.getName().startsWith("session_" + targetSession + "_raw_")) {
                    rawFile = f;
                    break;
                }
            }
            if (rawFile == null) {
                int lastSessionNum = getLastSessionNumberForDate(currentCsvDate);
                for (File f : rawFiles) {
                    if (f.getName().startsWith("session_" + lastSessionNum + "_raw_")) {
                        rawFile = f;
                        targetSession = lastSessionNum;
                        break;
                    }
                }
            }

            if (rawFile == null) {
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.remove("currentCsvDate");
                editor.remove("currentSessionNumber");
                editor.putBoolean("sessionActive", false);
                editor.apply();
                return;
            }

            List<TremorAnalysisUtils.TremorEventResult> results = TremorAnalysisUtils.analyseSession(rawFile, 100.0);

            String analysedFilename = "session_" + targetSession + "_analysed.csv";
            File temp = new File(analysedDir, analysedFilename + ".tmp");
            File analysedCsv = new File(analysedDir, analysedFilename);

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(temp))) {
                bw.write("event_num,start_time,end_time,dominant_freq_Hz,window_count,median_xp2p_cm,percentile90_xp2p_cm,max_xp2p_cm,grade\n");
                for (int i = 0; i < results.size(); i++) {
                    TremorAnalysisUtils.TremorEventResult r = results.get(i);
                    bw.write(String.format(Locale.US,
                            "%d,%s,%s,%.2f,%d,%.2f,%.2f,%.2f,%s\n",
                            i+1, r.startTime, r.endTime, r.dominantFreqHz, r.windowCount,
                            r.medianXp2p, r.percentile90Xp2p, r.maxXp2p, r.grade));
                }
                bw.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (temp.exists()) {
                try { temp.renameTo(analysedCsv); } catch (Exception ignored) {}
            }

        } catch(Exception e) {
            e.printStackTrace();
        } finally {
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.remove("currentCsvDate");
            editor.remove("currentSessionNumber");
            editor.putBoolean("sessionActive", false);
            editor.apply();

            try { sendBroadcast(new Intent("com.example.bledatalogger.ACTION_DATA_UPDATED")); } catch (Exception ignored){}
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "BLE Status";
            String description = "Bluetooth status notifications";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(NOTIF_CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    private void showNotification(String message) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("BLE Data Logger")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIF_ID, builder.build());
    }
    private void clearNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIF_ID);
    }

    // Correct bluetoothStateReceiver: handles BluetoothAdapter.ACTION_STATE_CHANGED only
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    updateBleStatusIcon(false, false);
                    isConnected = false;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean("ble_connected", false)
                            .apply();

                    Intent connIntent = new Intent("com.example.bledatalogger.ACTION_CONN_STATE");
                    connIntent.putExtra("connected", false);
                    sendBroadcast(connIntent);

                    new Thread(() -> {
                        try {
                            closeSessionCsv();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }).start();

                    if (bleManager != null) {
                        bleManager.disconnect();
                        bleManager = null;
                    }

                    setStatusDisconnected();
                    setTremorStatusDisconnected();
                    stopReconnection();
                    Toast.makeText(MainActivity.this, "Bluetooth is OFF. Please switch ON Bluetooth!", Toast.LENGTH_LONG).show();
                    showNotification("Bluetooth is OFF. Please switch ON Bluetooth!");
                    if (buttonConnect != null) buttonConnect.setEnabled(true);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    updateBleStatusIcon(true, false);
                    clearNotification();
                    setStatusDisconnected();
                    setTremorStatusDisconnected();
                    if (buttonConnect != null) buttonConnect.setEnabled(!editTextCode.getText().toString().trim().isEmpty());
                    if (currentDeviceCode != null) {
                        // Code is already saved — kick off the reconnect loop immediately
                        startReconnection();
                    } else {
                        Toast.makeText(MainActivity.this, "Bluetooth is ON. Please connect again.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
    };

    // performResetIfRequested: small helper to force MainActivity reset when UserSettings requests it
    private void performResetIfRequested() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean doReset = prefs.getBoolean("force_reset", false);
            if (!doReset) return;
            prefs.edit().remove("force_reset").apply();

            // Prefer reusing the Reset button action
            if (buttonReset != null) {
                buttonReset.performClick();
                return;
            }

            // Fallback: replicate Reset button behavior if buttonReset is null
            if (bleManager != null) {
                stopReconnection();
                isConnected = false;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("ble_connected", false).apply();
                Intent connIntent = new Intent("com.example.bledatalogger.ACTION_CONN_STATE");
                connIntent.putExtra("connected", false);
                sendBroadcast(connIntent);

                bleManager.disconnect();
                bleManager = null;

                BluetoothAdapter btAdapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
                updateBleStatusIcon(btAdapter != null && btAdapter.isEnabled(), false);
            }

            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.remove(PREF_LAST_CODE);
            editor.apply();

            if (editTextCode != null) {
                editTextCode.setText("");
                editTextCode.setEnabled(true);
            }

            setStatusDisconnected();
            setTremorStatusDisconnected();
            if (buttonConnect != null) buttonConnect.setEnabled(false);
            currentDeviceCode = null;
            closeSessionCsv();

            if (currentCsvDate == null) {
                currentCsvDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            }
            currentSessionNumber = getLastSessionNumberForDate(currentCsvDate);
            pendingDateSwitch = false;

            TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
            if (textViewFileLocation != null) textViewFileLocation.setText("");
            stopReconnection();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startReconnection() {
        // Cancel any pending callbacks first to avoid double-scheduling
        stopReconnection();
        if (reconnectRunnable == null) {
            reconnectRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isConnected && currentDeviceCode != null) {
                        tryConnectToDevice(currentDeviceCode);
                        reconnectHandler.postDelayed(this, RECONNECT_INTERVAL_MS);
                    }
                }
            };
        }
        // Fire the first attempt immediately, then retry every RECONNECT_INTERVAL_MS
        reconnectHandler.post(reconnectRunnable);
    }

    private void stopReconnection() {
        if (reconnectRunnable != null) reconnectHandler.removeCallbacks(reconnectRunnable);
    }

    private void tryConnectToDevice(String code) {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
            return;
        }
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth is OFF. Please switch ON Bluetooth!", Toast.LENGTH_LONG).show();
            showNotification("Bluetooth is OFF. Please switch ON Bluetooth!");
            buttonConnect.setEnabled(true);
            return;
        }

        String input = code.trim();
        if (!input.matches("\\d{4}")) {
            Toast.makeText(this, "Please enter a valid 4-digit code", Toast.LENGTH_SHORT).show();
            return;
        }
        currentDeviceCode = input;
        if (bleDeviceMap.containsKey(input)) {
            DeviceInfo info = bleDeviceMap.get(input);
            input = info.address;
        }
        textViewStatus.setText("Status: Connecting...");
        textViewDevice.setText("Device: -");
        textViewVersion.setText("Version: -");
        buttonConnect.setEnabled(false);

        // Ensure we send MODE only once for this connection
        modeSentForCurrentConnection = false;

        if (bleManager != null) {
            bleManager.disconnect();
            bleManager = null;
        }
        bleManager = new BleManager(this, this);
        bleManager.connect(input);

        editTextCode.setEnabled(false);

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(PREF_LAST_CODE, code);
        editor.apply();
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        runOnUiThread(() -> {
            isConnected = connected;
            BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
            boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
            updateBleStatusIcon(adapterOn, isConnected);

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean("ble_connected", connected)
                    .apply();

            Intent connIntent = new Intent("com.example.bledatalogger.ACTION_CONN_STATE");
            connIntent.putExtra("connected", connected);
            sendBroadcast(connIntent);

            if (connected) {
                textViewStatus.setText("Status: Connected");
                buttonConnect.setEnabled(false);

                if (currentDeviceCode != null && bleDeviceMap.containsKey(currentDeviceCode)) {
                    DeviceInfo info = bleDeviceMap.get(currentDeviceCode);
                    textViewDevice.setText("Device: " + info.name);
                    textViewVersion.setText("Version: " + info.version);
                } else {
                    textViewDevice.setText("Device: -");
                    textViewVersion.setText("Version: -");
                }

                String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());

                if (bleManager != null) {
                    // send time (existing)
                    bleManager.sendTimeToDevice(currentTime);

                    // Send MODE:TREMOR once per connection (delayed slightly to avoid racing the time write)
                    if (!modeSentForCurrentConnection) {
                        modeSentForCurrentConnection = true;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                // Ensure permission on Android S+
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                        && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
                                    return;
                                }
                                bleManager.sendModeToDevice("MODE:TREMOR");
                            } catch (Throwable t) {
                                Log.e("MainActivity", "Failed to send MODE command", t);
                            }
                        }, 200); // 150-300 ms is usually sufficient
                    }
                }

                if (currentCsvDate == null) {
                    currentCsvDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                    currentSessionNumber = 0;
                }
                startNewSessionCsv();
                updateObservedButtonEnabled();
                stopReconnection();
            } else {
                setObservedUiState(false);
                updateObservedButtonEnabled();
                textViewStatus.setText("Status: Disconnected");
                textViewDevice.setText("Device: -");
                textViewVersion.setText("Version: -");
                buttonConnect.setEnabled(!editTextCode.getText().toString().trim().isEmpty());
                setTremorStatusDisconnected();

                closeSessionCsv();

                TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
                textViewFileLocation.setText("");
                startReconnection();

                if (showLivePlot) {
                    clearPlots();
                    textViewNoLive.setVisibility(View.VISIBLE);
                }
                // textViewNoLive stays visible — it shows the TREMOR mode message
            }
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            textViewStatus.setText("Status: Error");
            textViewDevice.setText("Device: -");
            textViewVersion.setText("Version: -");
            setTremorStatusDisconnected();
            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            buttonConnect.setEnabled(!editTextCode.getText().toString().trim().isEmpty());

            closeSessionCsv();

            TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
            textViewFileLocation.setText("");
        });
    }

    @Override
    protected void onDestroy() {
        // in onDestroy()
        try { unregisterReceiver(closeSessionReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(bluetoothStateReceiver); } catch (Exception ignored){}
        tremorTimeoutHandler.removeCallbacksAndMessages(null);
        stopReconnection();

        if (bleManager != null) {
            bleManager.disconnect();
            bleManager = null;
        }
        closeSessionCsv();
        super.onDestroy();
    }

    @Override
    public void onDataReceived(byte[] rawData, String debugString) {
        runOnUiThread(() -> {
            if (rawData == null) return;

            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            if (currentCsvDate == null) {
                currentCsvDate = today;
                currentSessionNumber = 0;
                startNewSessionCsv();
            }
            if (!today.equals(currentCsvDate)) {
                if (tremorActive) pendingDateSwitch = true;
                else switchToNewDate(today);
            }

            if (rawData.length == 210) {
                StringBuilder parsedData = new StringBuilder();
                boolean anyPlotted = false;
                boolean detectedInPacket = false;

                for (int i = 0; i < 30; i++) {
                    int base = i * 7;

                    float accelMag = leBytesToFloat(rawData, base);
                    int timeDiff = leBytesToUint16(rawData, base + 4);
                    int status = rawData[base + 6] & 0xFF; // 0 or 1

                    if (status == 1) detectedInPacket = true;

                    double accelMagRounded = Math.round(accelMag * 100.0) / 100.0;
                    String timeString = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());

                    if (csvWriter != null || flashSyncState != FLASH_SYNC_IDLE) {
                        // 5 columns: accel, timeDiff, realTime, status, observed status
                        int observed = observedRecording ? 1 : 0;
                        writeOrBufferRow(new Object[]{accelMagRounded, timeDiff, timeString, status, observed});
                    }

                    if (i == 29) {
                        parsedData.append(String.format(Locale.US, "%.2f, %d, %d", accelMag, timeDiff, status));
                    }

                    if (showLivePlot) {
                        accelEntries.add(new Entry(plotIndex, (float) accelMagRounded));
                        plotIndex++;
                        anyPlotted = true;
                    }
                }

                if (showLivePlot && anyPlotted) {
                    updateLivePlots();
                    textViewNoLive.setVisibility(View.GONE);
                } else if (showLivePlot) {
                    textViewNoLive.setVisibility(View.VISIBLE);
                }

                // UI status: only "Detected" if any status==1 in this packet
                if (detectedInPacket) {
                    setLabelValueColor(
                            textViewTremorStatus,
                            "Status",
                            "Detected",
                            getResources().getColor(android.R.color.holo_red_dark)
                    );
                    setLabelValueColor(
                            textViewTremorData,
                            "Data",
                            "Receiving ...",
                            getResources().getColor(android.R.color.holo_red_dark)
                    );
                    tremorActive = true;
                    resetTremorTimeout();
                } else {
                    // keep "Receiving ..." but not detected
                    setLabelValueColor(
                            textViewTremorData,
                            "Data",
                            "Receiving ...",
                            getResources().getColor(android.R.color.darker_gray)
                    );
                    // do NOT set tremorActive=true here
                }

                textViewTremorRaw.setText(parsedData.toString());
            } else if (rawData.length == 6) {
                int header = leBytesToUint16(rawData, 0);
                int tremorCount = leBytesToUint16(rawData, 2);
                int tremorDataCount = leBytesToUint16(rawData, 4);

                if (header == 0x6789) {
                    // START marker
                    writeOrBufferRow(new Object[]{6789.0, 0.0, 0, 0, 0, 0});
                    textViewTremorRaw.setText("Start Marker");

                } else if (header == 0xABCD) {
                    // END marker with counts
                    writeOrBufferRow(new Object[]{9999.0, 0.0, 0, 0, (double)tremorCount, (double)tremorDataCount});
                    textViewTremorRaw.setText("Tremor Marker: tremorCount=" + tremorCount + ", tremorDataCount=" + tremorDataCount);

                } else if (header == 0x1234) {
                    // Zero marker / separator
                    writeOrBufferRow(new Object[]{1234.0, 0.0, 0, 0, 0, 0});
                    textViewTremorRaw.setText("Zero Marker");

                } else {
                    textViewTremorRaw.setText("Unknown marker: header=0x" + Integer.toHexString(header));
                }

                if (showLivePlot) {
                    clearPlots();
                    textViewNoLive.setVisibility(View.VISIBLE);
                }
            } else {
                Toast.makeText(this, "Invalid BLE packet length: " + rawData.length, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void switchToNewDate(String newDate) {
        closeSessionCsv();
        currentCsvDate = newDate;
        currentSessionNumber = getLastSessionNumberForDate(currentCsvDate);
        TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
        textViewFileLocation.setText("");
        startNewSessionCsv();
        pendingDateSwitch = false;
    }

    // -------------------------------------------------------------------------
    // Flash sync helpers
    // -------------------------------------------------------------------------

    @Override
    public void onFlashDataReceived(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        Log.d("FlashUUID", "RX " + chunk.length + "B");
        runOnUiThread(() -> handleFlashChunk(chunk));
    }

    private void handleFlashChunk(byte[] chunk) {
        switch (flashSyncState) {

            case FLASH_SYNC_IDLE:
                if (isMarker(chunk, SYNC_BLE_START)) {
                    // Explicit START marker packet received; wait for optional size packet next
                    beginFlashSync(false);
                } else if (!isMarker(chunk, SYNC_BLE_END)) {
                    // Firmware sent data directly without a standalone START marker — auto-start the sync
                    Log.i("FlashUUID", "IDLE: no START marker seen; auto-starting sync on first data chunk len=" + chunk.length);
                    beginFlashSync(true);
                    appendAndParseFlashChunk(chunk);
                } else {
                    Log.d("FlashUUID", "IDLE: ignoring spurious END marker");
                }
                break;

            case FLASH_SYNC_AWAIT_SIZE:
                if (isMarker(chunk, SYNC_BLE_END)) {
                    Log.i("FlashUUID", "AWAIT_SIZE: END marker received (empty sync)");
                    finishFlashSync();
                } else if (chunk.length == 4) {
                    // 4-byte little-endian uint32 = total bytes the sender will stream
                    syncExpectedBytes = (chunk[0] & 0xFF)
                            | ((chunk[1] & 0xFF) << 8)
                            | ((chunk[2] & 0xFF) << 16)
                            | ((chunk[3] & 0xFF) << 24);
                    flashSyncState = FLASH_SYNC_ACTIVE;
                    Log.i("FlashUUID", "AWAIT_SIZE: expecting " + syncExpectedBytes + " bytes");
                } else {
                    // Size packet not received; treat this chunk as data directly
                    Log.d("FlashUUID", "AWAIT_SIZE: no size packet, treating chunk as data len=" + chunk.length);
                    flashSyncState = FLASH_SYNC_ACTIVE;
                    appendAndParseFlashChunk(chunk);
                }
                break;

            case FLASH_SYNC_ACTIVE:
                if (isMarker(chunk, SYNC_BLE_END)) {
                    Log.i("FlashUUID", "ACTIVE: END marker received, receivedBytes=" + syncReceivedBytes);
                    finishFlashSync();
                } else {
                    appendAndParseFlashChunk(chunk);
                }
                break;
        }
    }

    /**
     * Initialises sync state and writes the 1111 sentinel row to the session CSV.
     * @param skipSizePacket when true, the firmware sends no size packet so go straight to ACTIVE.
     */
    private void beginFlashSync(boolean skipSizePacket) {
        flashSyncState = skipSizePacket ? FLASH_SYNC_ACTIVE : FLASH_SYNC_AWAIT_SIZE;
        syncExpectedBytes = 0;
        syncReceivedBytes = 0;
        liveBufferDropped = false;
        syncReassemblyBuffer.reset();
        liveDataBuffer.clear();
        if (csvWriter != null) {
            csvWriter.writeRow(new Object[]{1111.0, 0.0, "0:00", 0.0, 0.0, 0});
            Log.i("FlashUUID", "Flash sync started (skipSizePacket=" + skipSizePacket + ") — sentinel 1111 written to CSV");
        } else {
            Log.e("FlashUUID", "Flash sync started but csvWriter is NULL — no CSV open, data will be lost!");
        }
    }

    /** Appends {@code chunk} to the reassembly buffer and drains complete 9-byte records. */
    private void appendAndParseFlashChunk(byte[] chunk) {
        syncReassemblyBuffer.write(chunk, 0, chunk.length);
        byte[] buf = syncReassemblyBuffer.toByteArray();
        int pos = 0;
        while (pos + FLASH_RECORD_SIZE <= buf.length) {
            parseAndWriteFlashRecord(buf, pos);
            pos += FLASH_RECORD_SIZE;
            syncReceivedBytes += FLASH_RECORD_SIZE;
        }
        syncReassemblyBuffer.reset();
        if (pos < buf.length) {
            syncReassemblyBuffer.write(buf, pos, buf.length - pos);
        }
    }

    /**
     * Parses one 9-byte flash record starting at {@code offset} in {@code buf} and writes it
     * directly to the session CSV so it appears alongside live data:
     * bytes 0-3  float  accelMag  (LE)
     * bytes 4-5  uint16 timeDiff  (LE)
     * bytes 6-7  uint16 rtcTime   (LE) — stored as compact MMSS, e.g. 523 → "5:23"
     * byte  8    uint8  status
     */
    private void parseAndWriteFlashRecord(byte[] buf, int offset) {
        if (csvWriter == null) {
            Log.e("FlashUUID", "parseAndWriteFlashRecord: csvWriter is NULL — record dropped!");
            return;
        }
        // Check if this record is a special marker.
        // Guard: real marker records have zero padding in bytes 6-8 (rtcTime=0, status=0).
        int header = leBytesToUint16(buf, offset);
        boolean trailingZeros = (buf[offset + 6] & 0xFF) == 0
                && (buf[offset + 7] & 0xFF) == 0
                && (buf[offset + 8] & 0xFF) == 0;
        if (header == 0x6789 && trailingZeros
                && (buf[offset + 2] & 0xFF) == 0 && (buf[offset + 3] & 0xFF) == 0
                && (buf[offset + 4] & 0xFF) == 0 && (buf[offset + 5] & 0xFF) == 0) {
            csvWriter.writeRow(new Object[]{6789.0, 0.0, "0:00", 0.0, 0.0, 0.0});
            return;
        } else if (header == 0xABCD && trailingZeros) {
            int tremorCount = leBytesToUint16(buf, offset + 2);
            int tremorDataCount = leBytesToUint16(buf, offset + 4);
            csvWriter.writeRow(new Object[]{9999.0, 0.0, "0:00", 0.0, (double)tremorCount, (double)tremorDataCount});
            return;
        } else if (header == 0x1234 && trailingZeros
                && (buf[offset + 2] & 0xFF) == 0 && (buf[offset + 3] & 0xFF) == 0
                && (buf[offset + 4] & 0xFF) == 0 && (buf[offset + 5] & 0xFF) == 0) {
            csvWriter.writeRow(new Object[]{1234.0, 0.0, "0:00", 0.0, 0.0, 0.0});
            return;
        }
        // Not a marker — parse as normal data record
        float  accelMag = leBytesToFloat(buf, offset);
        int    timeDiff = leBytesToUint16(buf, offset + 4);
        int    rtcTime  = leBytesToUint16(buf, offset + 6);
        int    status   = buf[offset + 8] & 0xFF;
        double accelMagRounded = Math.round(accelMag * 100.0) / 100.0;
        String timeStr  = String.format(Locale.US, "%d:%02d", rtcTime / 100, rtcTime % 100);
        Log.d("FlashUUID", "Record #" + (syncReceivedBytes / FLASH_RECORD_SIZE + 1)
                + " accel=" + accelMagRounded + " timeDiff=" + timeDiff
                + " rtcTime=" + timeStr + " status=" + status);
        csvWriter.writeRow(new Object[]{accelMagRounded, timeDiff, timeStr, status, ""});
    }

    /** Called when the END marker arrives; writes the 2222 sentinel and dumps the live buffer to the session CSV. */
    private void finishFlashSync() {
        // Validate transfer completeness when the sender declared a byte count
        if (syncExpectedBytes > 0 && syncReceivedBytes < syncExpectedBytes) {
            Log.w("FlashUUID", "Sync incomplete: expected " + syncExpectedBytes
                    + " bytes, received " + syncReceivedBytes + " bytes");
            Toast.makeText(this,
                    "Flash sync incomplete: received " + syncReceivedBytes + "/" + syncExpectedBytes + " B",
                    Toast.LENGTH_LONG).show();
        }

        // Write SYNC_END sentinel to session CSV
        if (csvWriter != null) {
            csvWriter.writeRow(new Object[]{2222.0, 0.0, "0:00", 0.0, 0.0, 0});
        }

        // Dump buffered live rows in arrival order
        for (Object[] row : liveDataBuffer) {
            if (csvWriter != null) csvWriter.writeRow(row);
        }
        liveDataBuffer.clear();
        syncReassemblyBuffer.reset();
        flashSyncState = FLASH_SYNC_IDLE;
        Log.i("FlashUUID", "Sync complete — received " + syncReceivedBytes + " bytes");

        if (liveBufferDropped) {
            Toast.makeText(this,
                    "Flash sync complete (" + syncReceivedBytes + " B) — some live rows were dropped (buffer overflow)",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Flash sync complete (" + syncReceivedBytes + " B)", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Writes {@code row} directly to the session CSV when not syncing, or queues it in the live
     * buffer when a flash sync is in progress (so live data is preserved in time order).
     */
    private void writeOrBufferRow(Object[] row) {
        if (flashSyncState != FLASH_SYNC_IDLE) {
            if (liveDataBuffer.size() >= LIVE_BUFFER_MAX) {
                liveDataBuffer.pollFirst(); // drop oldest row to cap memory usage
                if (!liveBufferDropped) {
                    liveBufferDropped = true;
                    Log.w("FlashUUID", "liveDataBuffer full, dropping oldest rows");
                    Toast.makeText(this,
                            "Warning: live data buffer full — oldest samples being dropped during flash sync",
                            Toast.LENGTH_LONG).show();
                }
            }
            liveDataBuffer.addLast(row);
        } else if (csvWriter != null) {
            csvWriter.writeRow(row);
        }
    }

    /** Returns {@code true} when {@code data} starts with the given marker bytes. */
    private static boolean isMarker(byte[] data, byte[] marker) {
        if (data.length < marker.length) return false;
        for (int i = 0; i < marker.length; i++) {
            if (data[i] != marker[i]) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------

    private void resetTremorTimeout() {
        tremorTimeoutHandler.removeCallbacksAndMessages(null);
        if (tremorTimeoutRunnable == null) {
            tremorTimeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    tremorActive = false;
                    setLabelValueColor(
                            textViewTremorStatus,
                            "Status",
                            "Not Detected",
                            getResources().getColor(android.R.color.holo_green_dark)
                    );
                    setLabelValueColor(
                            textViewTremorData,
                            "Data",
                            "-",
                            getResources().getColor(android.R.color.darker_gray)
                    );
                    if (pendingDateSwitch) {
                        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                        switchToNewDate(today);
                    }
                }
            };
        }
        tremorTimeoutHandler.postDelayed(tremorTimeoutRunnable, TREMOR_STATUS_TIMEOUT_MS);
    }

    private static float leBytesToFloat(byte[] arr, int offset) {
        ByteBuffer bb = ByteBuffer.wrap(arr, offset, 4);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        return bb.getFloat();
    }
    private static int leBytesToUint16(byte[] arr, int offset) {
        return (arr[offset] & 0xFF) | ((arr[offset+1] & 0xFF) << 8);
    }

    private void setLabelValueColor(TextView textView, String label, String value, int color) {
        String fullText = label + ": " + value;
        int start = label.length() + 2;
        int end = fullText.length();
        SpannableString spannable = new SpannableString(fullText);
        spannable.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        textView.setText(spannable);
    }

    private void setStatusDisconnected() {
        textViewStatus.setText("Status: -");
        textViewDevice.setText("Device: -");
        textViewVersion.setText("Version: -");
    }

    private void setTremorStatusDisconnected() {
        setLabelValueColor(textViewTremorStatus, "Status", "-", getResources().getColor(android.R.color.darker_gray));
        setLabelValueColor(textViewTremorData, "Data", "-", getResources().getColor(android.R.color.darker_gray));
        textViewTremorRaw.setText("-");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            initializeBluetooth();
        }
    }

    // update drawer header from shared prefs
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
        } catch (Exception ignored) {}
    }

    // returns per-user root directory: <externalFiles>/iGest: Tremor Detection/Data/<safe-username>
    private File getCurrentUserDataDir() {
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String user = prefs.getString("current_user", "User");
        if (user == null || user.isEmpty()) user = "User";
        String safe = user.replaceAll("[/,\\\\:;\\*\\?\"<>\\|]", "_").trim().replaceAll("\\s+", "_");
        if (safe.isEmpty()) safe = "User";

        File base = new File(getExternalFilesDir(null), DATA_BASE_ROOT);
        File dataRoot = new File(base, "Data");
        File userDir = new File(dataRoot, safe);

        if (!userDir.exists()) {
            File analysed = new File(userDir, "Analysed");
            File raw = new File(userDir, "Raw");
            try {
                analysed.mkdirs();
                raw.mkdirs();
            } catch (Exception ignored) {}
        } else {
            // ensure expected subfolders exist
            File analysed = new File(userDir, "Analysed");
            File raw = new File(userDir, "Raw");
            if (!analysed.exists()) analysed.mkdirs();
            if (!raw.exists()) raw.mkdirs();
        }
        return userDir;
    }

    // closeSessionReceiver handles user-triggered broadcasts: close, start new, or perform reset
    private final BroadcastReceiver closeSessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (ACTION_CLOSE_SESSION.equals(action)) {
                new Thread(() -> {
                    try {
                        closeSessionCsv();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }).start();
            } else if (ACTION_START_NEW_SESSION.equals(action)) {
                // Start a new session for the current user on UI thread
                runOnUiThread(() -> {
                    try {
                        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                        currentCsvDate = today;
                        currentSessionNumber = 0;
                        startNewSessionCsv();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });
            } else if (ACTION_PERFORM_RESET.equals(action)) {
                // Perform same logic as Reset button; run on UI thread
                runOnUiThread(() -> {
                    try {
                        if (buttonReset != null) {
                            buttonReset.performClick();
                            return;
                        }
                        // fallback: replicate Reset behavior if buttonReset is not available
                        if (bleManager != null) {
                            stopReconnection();
                            isConnected = false;
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("ble_connected", false).apply();
                            Intent connIntent = new Intent("com.example.bledatalogger.ACTION_CONN_STATE");
                            connIntent.putExtra("connected", false);
                            sendBroadcast(connIntent);

                            bleManager.disconnect();
                            bleManager = null;

                            BluetoothAdapter btAdapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
                            updateBleStatusIcon(btAdapter != null && btAdapter.isEnabled(), false);
                        }

                        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                        editor.remove(PREF_LAST_CODE);
                        editor.apply();

                        if (editTextCode != null) {
                            editTextCode.setText("");
                            editTextCode.setEnabled(true);
                        }

                        setStatusDisconnected();
                        setTremorStatusDisconnected();
                        if (buttonConnect != null) buttonConnect.setEnabled(false);
                        currentDeviceCode = null;
                        closeSessionCsv();

                        if (currentCsvDate == null) {
                            currentCsvDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                        }
                        currentSessionNumber = getLastSessionNumberForDate(currentCsvDate);
                        pendingDateSwitch = false;

                        TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
                        if (textViewFileLocation != null) textViewFileLocation.setText("");
                        stopReconnection();
                    } catch (Exception ignored) {}
                });
            }
        }
    };
}