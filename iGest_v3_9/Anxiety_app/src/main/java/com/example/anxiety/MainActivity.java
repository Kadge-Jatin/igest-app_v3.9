package com.example.anxiety;

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
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import android.widget.ToggleButton;
import android.graphics.Color;

import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.navigation.NavigationView;
import androidx.core.view.GravityCompat;
import android.content.res.ColorStateList;

public class MainActivity extends AppCompatActivity implements BleManager.BleCallback {

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;

    private static final String PREFS_NAME = "BlePrefs";
    private static final String PREF_LAST_CODE = "LastCode";
    private static final String NOTIF_CHANNEL_ID = "ble_status_channel";
    private static final int NOTIF_ID = 12345;
    private static final String ANXIETY_NOTIF_CHANNEL_ID = "anxiety_alert_channel";
    private static final int ANXIETY_NOTIF_ID = 12346;

    // Folder root for data. Change here if you want a different folder name.
    // NOTE: Colon (:) is allowed on Android filesystems but may cause issues when accessing from Windows.
    private static final String DATA_ROOT_FOLDER = "iGest: Anxiety Detection";

    private EditText editTextCode;
    private Button buttonConnect;
    private Button buttonReset;
    private TextView textViewStatus;
    private TextView textViewDevice;
    private TextView textViewVersion;

    // Anxiety status UI
    private TextView textViewAnxietyStatus;
    private TextView textViewAnxietyData;
    private TextView textViewAnxietyRaw;
    private View bleStatusCircle;

    private BleManager bleManager;
    private CsvWriter csvWriter;
    private boolean isConnected = false;
    private String currentDeviceCode = null;

    // ensure MODE sent once per connection
    private boolean modeSentForCurrentConnection = false;

    private Handler anxietyTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable anxietyTimeoutRunnable;
    private static final int ANXIETY_STATUS_TIMEOUT_MS = 500;

    private Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;
    private static final int RECONNECT_INTERVAL_MS = 5000;

    private String currentCsvDate = null;
    private int currentSessionNumber = 0;
    private boolean pendingDateSwitch = false;
    private boolean anxietyActive = false;
    private boolean sessionActive = false;

    private final HashMap<String, DeviceInfo> bleDeviceMap = new HashMap<>();

    private ToggleButton toggleLivePlot;
    private LineChart accelChart;
    private TextView textViewNoLive;
    private List<Entry> accelEntries = new ArrayList<>();
    private int plotIndex = 0;
    private boolean showLivePlot = false;

    private DrawerLayout drawerLayout;
    private ImageView menuIcon;

    private MediaPlayer mediaPlayer;
    private boolean anxietyFeedbackGiven = false;
    private static final String PREF_CLOSE_SESSION_ON_RETURN = "CloseSessionOnReturn";
    private static final String PREFS_SETTINGS = "AnxietySettingsPrefs";
    private static final String PREF_SENSITIVITY_LEVEL = "sensitivity_level";
    private static final int DEFAULT_SENSITIVITY_LEVEL = 4;
//    private boolean loggingEnabled = false; // start logging only after first 0x1234 marker

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

    /** Sentinel indicating no 0xABCD anxiety marker was seen in flash records during the current sync. */
    private static final int FLASH_ANXIETY_COUNT_UNSET = -1;
    /** Last anxiety count received from the device via a live 0xABCD marker (before disconnection). */
    private int lastLiveAnxietyCount = 0;
    /** Last anxiety count seen in flash records during the current sync; FLASH_ANXIETY_COUNT_UNSET means no 0xABCD record found yet. */
    private int flashAnxietyCount = FLASH_ANXIETY_COUNT_UNSET;


    private int getMappedSensitivityThreshold() {
        SharedPreferences prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        int level = prefs.getInt(PREF_SENSITIVITY_LEVEL, DEFAULT_SENSITIVITY_LEVEL);
        if (level < 1) level = 1;
        if (level > 10) level = 10;
        return level * 50; // 1->50 ... 10->500
    }


    @Override
    protected void onResume() {
        super.onResume();

        DrawerLayout drawerLayout = findViewById(R.id.drawerLayout);
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }

        // If Settings Save requested a clean close, do it NOW
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean shouldClose = prefs.getBoolean(PREF_CLOSE_SESSION_ON_RETURN, false);
        if (shouldClose) {
            prefs.edit().putBoolean(PREF_CLOSE_SESSION_ON_RETURN, false).apply();

            // 1) Close file explicitly
            closeSessionCsv();

            // 2) Disconnect explicitly
            if (bleManager != null) {
                stopReconnection();
                bleManager.disconnect();
                bleManager = null;
            }

            // 3) Reset state/UI (so it’s obvious it refreshed)
            isConnected = false;
            modeSentForCurrentConnection = false;
            setStatusDisconnected();
            setAnxietyStatusDisconnected();

            TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
            if (textViewFileLocation != null) textViewFileLocation.setText("");

            // 4) Start reconnection again (your existing 5-sec behavior)
            startReconnection();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.anxiety_activity_main);

        drawerLayout = findViewById(R.id.drawerLayout);
        menuIcon = findViewById(R.id.menuIcon);

        NavigationView navigationView = findViewById(R.id.navigationView);
        View headerView = navigationView.getHeaderView(0);
        LinearLayout headerUserRow = headerView.findViewById(R.id.headerUserRow);
        navigationView.setCheckedItem(R.id.nav_home);

        headerUserRow.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, UserSettingsActivity.class);
            startActivity(intent);
        });

        menuIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
            } else if (id == R.id.nav_data_analysis) {
                Intent intent = new Intent(MainActivity.this, DataAnalysisActivity.class);
                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            } else if (id == R.id.nav_about_us) {
                Intent intent = new Intent(MainActivity.this, AboutUsActivity.class);
                startActivity(intent);
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        initializeViews();
        initializeBleDeviceMap();
        checkPermissions();
        initializeBluetooth();

        Switch toggleLivePlot = findViewById(R.id.toggleLivePlot);
        accelChart = findViewById(R.id.accelChart);
        textViewNoLive = findViewById(R.id.textViewNoLive);

        accelChart.setNoDataText("Turn ON the toggle to see active anxiety events");
        setupChart(accelChart, Color.CYAN);

        accelChart.getAxisLeft().setAxisMinimum(0f);
        accelChart.getAxisLeft().setAxisMaximum(3000f);

        toggleLivePlot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showLivePlot = isChecked;
            if (!showLivePlot) {
                clearPlots();
                accelChart.setNoDataText("Turn ON the toggle to see active anxiety events");
                textViewNoLive.setVisibility(View.GONE);
            } else {
                accelChart.setNoDataText("No active Anxiety event");
                textViewNoLive.setVisibility(View.VISIBLE);
                accelChart.invalidate();
            }
        });

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled());

        createNotificationChannel();

        registerReceiver(bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
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
            setAnxietyStatusDisconnected();
//            buttonConnect.setEnabled(false);
            setConnectButtonEnabledStyled(false);
        }
    }

    private void applyObservedButtonStyle() {
        if (buttonObservedRecord == null) return;

        int blue = ContextCompat.getColor(this, R.color.primary); // or your brand blue
        int red = ContextCompat.getColor(this, android.R.color.holo_red_dark);
        int disabledGrey = Color.parseColor("#BDBDBD");

        boolean enabled = buttonObservedRecord.isEnabled();
        int bg = !enabled ? disabledGrey : (observedRecording ? red : blue);

        buttonObservedRecord.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        buttonObservedRecord.setAlpha(enabled ? 1.0f : 0.60f);
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

    private void setConnectButtonEnabledStyled(boolean enabled) {
        if (buttonConnect == null) return;

        buttonConnect.setEnabled(enabled);

        int color = enabled
                ? ContextCompat.getColor(this, R.color.primary)   // blue
                : 0xFFB0B0B0;                                     // grey

        buttonConnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        buttonConnect.setAlpha(enabled ? 1.0f : 0.60f);
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
        accelSet.setColor(observedRecording ? Color.RED : Color.CYAN);
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
        textViewAnxietyStatus = findViewById(R.id.textViewAnxietyStatus);
        textViewAnxietyData = findViewById(R.id.textViewAnxietyData);
        textViewAnxietyRaw = findViewById(R.id.textViewAnxietyRaw);
        bleStatusCircle = findViewById(R.id.bleStatusCircle);

        buttonObservedRecord = findViewById(R.id.buttonObservedRecord);
        textViewObservedTimer = findViewById(R.id.textViewObservedTimer);

        if (textViewObservedTimer != null) textViewObservedTimer.setText("00:00");

        // initial state
        setObservedUiState(false);
        updateObservedButtonEnabled();

        if (buttonObservedRecord != null) {
            buttonObservedRecord.setOnClickListener(v -> {
                setObservedUiState(!observedRecording);
                if (showLivePlot) updateLivePlots(); // refresh color immediately
            });
        }

        bleStatusCircle.setOnClickListener(v -> {
            BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                Intent intent = getBaseContext().getPackageManager()
                        .getLaunchIntentForPackage(getBaseContext().getPackageName());
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                    Runtime.getRuntime().exit(0);
                }
            }
        });

        editTextCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
//                buttonConnect.setEnabled(!s.toString().trim().isEmpty());
                setConnectButtonEnabledStyled(!s.toString().trim().isEmpty() && !isConnected);
            }
        });

//        buttonConnect.setEnabled(!editTextCode.getText().toString().trim().isEmpty());

        setConnectButtonEnabledStyled(!editTextCode.getText().toString().trim().isEmpty() && !isConnected);

        buttonConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String code = editTextCode.getText().toString().trim();
                tryConnectToDevice(code);
            }
        });

        buttonReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isConnected = false;
                if (bleManager != null) {
                    stopReconnection();
                    bleManager.disconnect();
                    bleManager = null;
                }
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.remove(PREF_LAST_CODE);
                editor.apply();
                editTextCode.setText("");
                editTextCode.setEnabled(true);

                setStatusDisconnected();
                setAnxietyStatusDisconnected();
//                buttonConnect.setEnabled(false);
                setConnectButtonEnabledStyled(false);
                currentDeviceCode = null;
                closeSessionCsv();

                currentCsvDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                currentSessionNumber = getLastSessionNumberForDate(currentCsvDate);
                pendingDateSwitch = false;

                TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
                textViewFileLocation.setText("");
                stopReconnection();
            }
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
        bleDeviceMap.put("7776", new DeviceInfo( "A0:DD:6C:73:77:76","iGest _02", "TD_v2"));
        bleDeviceMap.put("6373", new DeviceInfo("A0:DD:6C:B3:7E:3E", "iGest _03", "TD_v2"));
        bleDeviceMap.put("2892", new DeviceInfo("2C:BC:BB:A8:9B:B2", "iGest _04", "TD_v2"));
    }

    private void updateBleStatusCircle(boolean bleOn) {
        if (bleOn) {
            bleStatusCircle.setBackgroundResource(R.drawable.circle_green);
        } else {
            bleStatusCircle.setBackgroundResource(R.drawable.circle_red);
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

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
        File rootDir = new File(getExternalFilesDir(null), DATA_ROOT_FOLDER + "/Raw/" + date);
        int maxSession = 0;
        if (rootDir.exists()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
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
            }
        }
        return maxSession;
    }

    private void startNewSessionCsv() {
        if (sessionActive) return;
        sessionActive = true;
        currentSessionNumber = getLastSessionNumberForDate(currentCsvDate) + 1;
        File rootDir = new File(getExternalFilesDir(null), DATA_ROOT_FOLDER + "/Raw/" + currentCsvDate);
        if (!rootDir.exists()) rootDir.mkdirs();

        String timestamp = new SimpleDateFormat("HHmmss", Locale.getDefault()).format(new Date());
        String filename = "session_" + currentSessionNumber + "_raw_" + timestamp + ".csv";
        File csvFile = new File(rootDir, filename);

        if (csvWriter != null) csvWriter.close();
        csvWriter = new CsvWriter(csvFile);

        TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
        textViewFileLocation.setText(
                "Current file: " + DATA_ROOT_FOLDER + "/Raw/" + currentCsvDate + "/session_" + currentSessionNumber + "_raw_" + timestamp + ".csv"
        );

        updateObservedButtonEnabled();
    }

    private void closeSessionCsv() {
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
            csvWriter.close();
            csvWriter = null;
        }
        sessionActive = false;

        setObservedUiState(false);
        updateObservedButtonEnabled();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);

            // BLE status channel (default priority)
            NotificationChannel bleChannel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "BLE Status", NotificationManager.IMPORTANCE_DEFAULT);
            bleChannel.setDescription("Bluetooth status notifications");
            notificationManager.createNotificationChannel(bleChannel);

            // Anxiety alert channel (high priority — sound + vibration)
            NotificationChannel anxietyChannel = new NotificationChannel(
                    ANXIETY_NOTIF_CHANNEL_ID, "Missed Anxiety Alerts", NotificationManager.IMPORTANCE_HIGH);
            anxietyChannel.setDescription("Alerts for anxiety episodes missed during BLE disconnection");
            anxietyChannel.enableVibration(true);
            notificationManager.createNotificationChannel(anxietyChannel);
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

    private void showMissedAnxietyNotification(int missedCount) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String message = missedCount + " anxiety episode" + (missedCount == 1 ? "" : "s")
                + " occurred during disconnection";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ANXIETY_NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("⚠️ Missed Anxiety Events")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 300, 200, 300})
                .setContentIntent(pendingIntent);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(ANXIETY_NOTIF_ID, builder.build());
        Log.i("AnxietyAlert", "Missed anxiety notification fired: missedCount=" + missedCount);
    }

    private void clearNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIF_ID);
    }

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    updateBleStatusCircle(false);
                    if (bleManager != null) {
                        bleManager.disconnect();
                        bleManager = null;
                    }
                    setStatusDisconnected();
                    setAnxietyStatusDisconnected();
                    stopReconnection();
                    Toast.makeText(MainActivity.this, "Bluetooth is OFF. Please switch ON Bluetooth!", Toast.LENGTH_LONG).show();
                    showNotification("Bluetooth is OFF. Please switch ON Bluetooth!");
//                    buttonConnect.setEnabled(true);
                    setConnectButtonEnabledStyled(true);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    updateBleStatusCircle(true);
                    clearNotification();
                    Toast.makeText(MainActivity.this, "Bluetooth is ON. Please connect again.", Toast.LENGTH_LONG).show();
                    setStatusDisconnected();
                    setAnxietyStatusDisconnected();
//                    buttonConnect.setEnabled(!editTextCode.getText().toString().trim().isEmpty());
                    setConnectButtonEnabledStyled(!editTextCode.getText().toString().trim().isEmpty() && !isConnected);
                }
            }
        }
    };

    private void startReconnection() {
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
        reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_INTERVAL_MS);
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
//            buttonConnect.setEnabled(true);
            setConnectButtonEnabledStyled(true);
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
//        buttonConnect.setEnabled(false);
        setConnectButtonEnabledStyled(false);
        // ensure we send MODE only once for this connection
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
            if (connected) {
                textViewStatus.setText("Status: Connected");
//                buttonConnect.setEnabled(false);
                setConnectButtonEnabledStyled(false);


                if (currentDeviceCode != null && bleDeviceMap.containsKey(currentDeviceCode)) {
                    DeviceInfo info = bleDeviceMap.get(currentDeviceCode);
                    textViewDevice.setText("Device: " + info.name);
                    textViewVersion.setText("Version: " + info.version);
                } else {
                    textViewDevice.setText("Device: -");
                    textViewVersion.setText("Version: -");
                }
                String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
//                if (bleManager != null) bleManager.sendTimeToDevice(currentTime);
                if (bleManager != null) {
                    // existing time write
//                    bleManager.sendTimeToDevice(currentTime);

                    // Send MODE:ANXIETY once per connection (delayed slightly to avoid racing writes)
                    if (!modeSentForCurrentConnection) {
                        modeSentForCurrentConnection = true;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                // On Android S+, ensure BLUETOOTH_CONNECT permission before write
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                        && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
                                    return;
                                }
                                bleManager.sendModeToDevice("MODE:ANXIETY");
                                int threshold = getMappedSensitivityThreshold();
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                                && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
                                            return;
                                        }
                                        if (bleManager != null) {
                                            bleManager.sendModeToDevice("SENS:" + threshold);
                                        }
                                    } catch (Throwable t) {
                                        // ignore/log if you want
                                    }
                                }, 200);
                            } catch (Throwable t) {
//                                Log.e("MainActivity", "Failed to send MODE command", t);
                            }
                        }, 200); // 150–300 ms is sufficient
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
//                loggingEnabled = false;
                textViewStatus.setText("Status: Disconnected");
                textViewDevice.setText("Device: -");
                textViewVersion.setText("Version: -");
//                buttonConnect.setEnabled(!editTextCode.getText().toString().trim().isEmpty());
                setConnectButtonEnabledStyled(!editTextCode.getText().toString().trim().isEmpty());
                setAnxietyStatusDisconnected();

                closeSessionCsv();

                TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
                textViewFileLocation.setText("");
                startReconnection();

                if (showLivePlot) {
                    clearPlots();
                    textViewNoLive.setVisibility(View.VISIBLE);
                } else {
                    textViewNoLive.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            textViewStatus.setText("Status: Error");
            textViewDevice.setText("Device: -");
            textViewVersion.setText("Version: -");
            setAnxietyStatusDisconnected();
            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
//            buttonConnect.setEnabled(!editTextCode.getText().toString().trim().isEmpty());
            setConnectButtonEnabledStyled(!editTextCode.getText().toString().trim().isEmpty());

            closeSessionCsv();

            TextView textViewFileLocation = findViewById(R.id.textViewFileLocation);
            textViewFileLocation.setText("");
        });
    }

    @Override
    protected void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        unregisterReceiver(bluetoothStateReceiver);
        anxietyTimeoutHandler.removeCallbacksAndMessages(null);
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
                if (anxietyActive) pendingDateSwitch = true;
                else switchToNewDate(today);
            }

            if (rawData.length == 238) {
                StringBuilder parsedData = new StringBuilder();
                boolean anyPlotted = false;
                boolean detected = false;

                for (int i = 0; i < 30; i++) {
                    int base = i * 7;

                    float accelMag = leBytesToFloat(rawData, base);
                    int timeDiff = leBytesToUint16(rawData, base + 4);
                    int status = rawData[base + 6] & 0xFF; // 0 or 1

                    if (status == 1) detected = true;

                    double accelMagRounded = Math.round(accelMag * 100.0) / 100.0;
                    String timeString = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());

                    if (csvWriter != null || flashSyncState != FLASH_SYNC_IDLE) {
                        // Column 3 stays real-world time, status becomes column 4
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

                if (detected) {
                    setLabelValueColor(
                            textViewAnxietyStatus,
                            "Status",
                            "Anxious Detected",
                            getResources().getColor(android.R.color.holo_red_dark)
                    );
                    setLabelValueColor(
                            textViewAnxietyData,
                            "Data",
                            "Receiving ...",
                            getResources().getColor(android.R.color.holo_red_dark)
                    );

                    // Feedback only once per continuous detected period
                    if (!anxietyFeedbackGiven) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ((android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE))
                                    .vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            ((android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(500);
                        }
                        try {
                            if (mediaPlayer != null) {
                                mediaPlayer.release();
                                mediaPlayer = null;
                            }
                            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound);
                            if (mediaPlayer != null) {
                                mediaPlayer.setOnCompletionListener(mp -> {
                                    mp.release();
                                    mediaPlayer = null;
                                });
                                mediaPlayer.start();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        anxietyFeedbackGiven = true;
                    }

                    anxietyActive = true;
                    resetAnxietyTimeout();
                } else {
                    // Not detected in this packet: do NOT force "Detected"
                    // (timeout runnable will eventually flip state; or you can set it immediately here if you want)
                    setLabelValueColor(
                            textViewAnxietyData,
                            "Data",
                            "Receiving ...",
                            getResources().getColor(android.R.color.darker_gray)
                    );
                }

                textViewAnxietyRaw.setText(parsedData.toString());
            } else if (rawData.length == 9) {
                int header = leBytesToUint16(rawData, 0);

                if (header == 0x6789) {
                    // START marker - no payload
                    writeOrBufferRow(new Object[]{6789.0, 0.0, "0:00", 0.0, 0.0, 0.0});
                    textViewAnxietyRaw.setText("Start Marker");
                } else if (header == 0xABCD) {
                    // END marker - with anxiety counts
                    int anxietyCount = leBytesToUint16(rawData, 2);
                    int anxietyDataCount = leBytesToUint16(rawData, 4);
                    lastLiveAnxietyCount = anxietyCount;
                    writeOrBufferRow(new Object[]{9999.0, 0.0, "0:00", 0.0, (double)anxietyCount, (double)anxietyDataCount});
                    textViewAnxietyRaw.setText("Anxiety Marker: anxietyCount=" + anxietyCount + ", anxietyDataCount=" + anxietyDataCount);
                } else if (header == 0x1234) {
                    // ZERO marker - no payload
                    writeOrBufferRow(new Object[]{1234.0, 0.0, "0:00", 0.0, 0.0, 0.0});
                    textViewAnxietyRaw.setText("Zero Marker");
                } else {
                    textViewAnxietyRaw.setText("Unknown marker: header=0x" + Integer.toHexString(header));
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

    private void resetAnxietyTimeout() {
        anxietyTimeoutHandler.removeCallbacksAndMessages(null);
        if (anxietyTimeoutRunnable == null) {
            anxietyTimeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    anxietyActive = false;
                    anxietyFeedbackGiven = false;
                    setLabelValueColor(
                            textViewAnxietyStatus,
                            "Status",
                            "Not Anxious",
                            getResources().getColor(android.R.color.holo_green_dark)
                    );
                    setLabelValueColor(
                            textViewAnxietyData,
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
        anxietyTimeoutHandler.postDelayed(anxietyTimeoutRunnable, ANXIETY_STATUS_TIMEOUT_MS);
    }

    // -------------------------------------------------------------------------
    // Flash sync helpers
    // -------------------------------------------------------------------------

    @Override
    public void onFlashDataReceived(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        Log.d("FlashUUID", "RX " + chunk.length + "B hex=" + bytesToHex(chunk));
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
                    // Empty sync — nothing to transfer
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
        flashAnxietyCount = FLASH_ANXIETY_COUNT_UNSET;
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
     * directly to the session CSV (csvWriter) so it appears alongside live data:
     * bytes 0-3  float  accelMag  (LE)
     * bytes 4-5  uint16 timeDiff  (LE)
     * bytes 6-7  uint16 rtcTime   (LE) — stored as compact MMSS, e.g. 523 → "5:23"
     * byte  8    uint8  status
     */
    private void parseAndWriteFlashRecord(byte[] buf, int offset) {
        if (csvWriter == null) {
            Log.e("FlashUUID", "parseAndWriteFlashRecord: csvWriter is NULL — record dropped! Flash data cannot be saved.");
            return;
        }
        // Check if this record is a marker
        int header = leBytesToUint16(buf, offset);
        if (header == 0x6789) {
            csvWriter.writeRow(new Object[]{6789.0, 0.0, "0:00", 0.0, 0.0, 0.0});
            return;
        } else if (header == 0xABCD) {
            int anxietyCount = leBytesToUint16(buf, offset + 2);
            int anxietyDataCount = leBytesToUint16(buf, offset + 4);
            flashAnxietyCount = anxietyCount;
            csvWriter.writeRow(new Object[]{9999.0, 0.0, "0:00", 0.0, (double)anxietyCount, (double)anxietyDataCount});
            return;
        } else if (header == 0x1234) {
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

    /** Called when the END marker arrives; writes the 8002 sentinel and dumps the live buffer to the session CSV. */
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

        // Notify teacher if anxiety episodes were missed during disconnection
        if (flashAnxietyCount != FLASH_ANXIETY_COUNT_UNSET) {
            int missedCount = flashAnxietyCount - lastLiveAnxietyCount;
            if (missedCount > 0) {
                Log.i("AnxietyAlert", "Missed anxiety notification: flashAnxietyCount=" + flashAnxietyCount
                        + " lastLiveAnxietyCount=" + lastLiveAnxietyCount + " missed=" + missedCount);
                showMissedAnxietyNotification(missedCount);
            } else {
                Log.i("AnxietyAlert", "No missed anxiety events (flashAnxietyCount=" + flashAnxietyCount
                        + " lastLiveAnxietyCount=" + lastLiveAnxietyCount + ")");
            }
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

    private static float leBytesToFloat(byte[] arr, int offset) {
        ByteBuffer bb = ByteBuffer.wrap(arr, offset, 4);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        return bb.getFloat();
    }
    private static int leBytesToUint16(byte[] arr, int offset) {
        return (arr[offset] & 0xFF) | ((arr[offset+1] & 0xFF) << 8);
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
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

    private void setAnxietyStatusDisconnected() {
        setLabelValueColor(textViewAnxietyStatus, "Status", "-", getResources().getColor(android.R.color.darker_gray));
        setLabelValueColor(textViewAnxietyData, "Data", "-", getResources().getColor(android.R.color.darker_gray));
        textViewAnxietyRaw.setText("-");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            initializeBluetooth();
        }
    }
}