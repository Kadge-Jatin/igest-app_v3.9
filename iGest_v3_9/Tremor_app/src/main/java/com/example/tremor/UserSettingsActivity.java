package com.example.tremor;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UserSettingsActivity - updated to reliably request MainActivity to reset when changing profiles.
 */
public class UserSettingsActivity extends AppCompatActivity {

    private static final String TAG = "UserSettingsActivity";
    private static final String ACTION_PERFORM_RESET = "com.example.bledatalogger.ACTION_PERFORM_RESET";
    // Use the original base folder name (keeps exactly as in your repo)
    private static final String DATA_BASE_FOLDER = "iGest: Tremor Detection";
    private static final String USERS_CSV_NAME = "users.csv";

    // Action names used to request MainActivity to finalize/close current session and to start a new session
    private static final String ACTION_CLOSE_SESSION = "com.example.bledatalogger.ACTION_CLOSE_SESSION";
    private static final String ACTION_START_NEW_SESSION = "com.example.bledatalogger.ACTION_START_NEW_SESSION";

    private DrawerLayout drawerLayout;
    private ImageView menuIcon;
    private ImageView bleStatusIcon;

    private boolean connReceiverRegistered = false;
    private boolean receiverRegistered = false;
    private BluetoothAdapter bluetoothAdapter;

    private static final String PREFS_USER = "UserPrefs";
    private static final String PREF_CURRENT_USER = "current_user";

    // UI: bindings for user card
    private ImageView imgUserAvatar;
    private TextView tvLoggedInLabelSmall;
    private TextView tvLoggedInUser;

    private Button btnAddUser;
    private Button btnSelectMultiple;
    private Button btnDelete;
    private Button btnLogout;
    private ListView listViewRecentLogins;
    private ArrayAdapter<String> recentAdapter; // used for multiple-select built-in adapter
    private UserAdapter userAdapter;            // custom adapter for single-choice mode
    private final List<String> recentLogins = new ArrayList<>();
    private SharedPreferences userPrefs;

    // selection mode flag
    private boolean multiSelectMode = false;

    // Broadcast to monitor Bluetooth adapter state
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

    // App-level connection state receiver (keeps BLE icon in sync)
    private final BroadcastReceiver connStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra("connected", false);
            Log.d(TAG, "connStateReceiver onReceive connected=" + connected);
            boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
            updateBleStatusCircle(adapterOn, connected);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_settings);

        userPrefs = getSharedPreferences(PREFS_USER, Context.MODE_PRIVATE);

        // bind user card views
        imgUserAvatar = findViewById(R.id.imgUserAvatar);
        tvLoggedInLabelSmall = findViewById(R.id.tvLoggedInLabelSmall);
        tvLoggedInUser = findViewById(R.id.tvLoggedInUser);

        btnAddUser = findViewById(R.id.btnAddUser);
        btnSelectMultiple = findViewById(R.id.btnSelectMultiple);
        btnDelete = findViewById(R.id.btnDelete);
        btnLogout = findViewById(R.id.btnLogout);
        listViewRecentLogins = findViewById(R.id.listViewRecentLogins);

        // Drawer/menu/ble initialization (from old implementation)
        drawerLayout = findViewById(R.id.drawerLayout);
        menuIcon = findViewById(R.id.menuIcon);
        bleStatusIcon = findViewById(R.id.bleStatusIcon);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        // initial BLE icon state
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled(), false);

        // ble icon click: request enable if not enabled (keeps old behavior)
        bleStatusIcon.setOnClickListener(v -> {
            if (bluetoothAdapter == null) return;
            if (!bluetoothAdapter.isEnabled()) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            else Toast.makeText(this, "Bluetooth is ON", Toast.LENGTH_SHORT).show();
        });

        // hamburger opens drawer
        menuIcon.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        // restore NavigationView behavior from old code so selecting a menu item navigates
        NavigationView navigationView = findViewById(R.id.navigationView);
        // ensure drawer header shows current user at startup
        updateDrawerHeaderUsername();
        if (navigationView != null) {
            // header click behavior (close drawer)
            try {
                View headerView = navigationView.getHeaderView(0);
                if (headerView != null) {
                    LinearLayout headerUserRow = headerView.findViewById(R.id.headerUserRow);
                    if (headerUserRow != null) {
                        headerUserRow.setSelected(true);
                        headerUserRow.setOnClickListener(v -> {
                            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                                drawerLayout.closeDrawer(GravityCompat.START);
                            }
                        });
                    }
                }
            } catch (Exception ignored) {}

            // navigation item selection: use FLAG_ACTIVITY_REORDER_TO_FRONT to reuse activities
            navigationView.setNavigationItemSelectedListener(item -> {
                int id = item.getItemId();

                if (id == R.id.nav_home) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    Intent intent = new Intent(UserSettingsActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                } else if (id == R.id.nav_data_analysis) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    Intent intent = new Intent(UserSettingsActivity.this, DataAnalysisActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                } else if (id == R.id.nav_settings) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    Intent intent = new Intent(UserSettingsActivity.this, SettingsActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                } else if (id == R.id.nav_about_us) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    Intent intent = new Intent(UserSettingsActivity.this, AboutUsActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                } else {
                    drawerLayout.closeDrawer(GravityCompat.START);
                }

                // mark checked visually
                item.setChecked(true);
                return true;
            });
        }

        // set avatar drawable (best-effort)
        try {
            Drawable avatar = ContextCompat.getDrawable(this, R.drawable.ic_person_avatar_from_orig_filled);
            if (avatar != null) {
                try { avatar.setTintList(null); } catch (Exception ignored) {}
                imgUserAvatar.setImageDrawable(avatar);
            }
        } catch (Exception ignored) {}

        // set up adapters: custom adapter for single-choice by default
        userAdapter = new UserAdapter(this, R.layout.list_item_user, recentLogins);
        listViewRecentLogins.setAdapter(userAdapter);
        listViewRecentLogins.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // initial load
        refreshRecentLoginsFromCsv();
        updateLoggedInLabelAndSelection();

        // clicking a row prompts for password then selects
        listViewRecentLogins.setOnItemClickListener((parent, view, position, id) -> {
            if (multiSelectMode) {
                updateDeleteButtonState();
                return;
            }
            String username = recentLogins.get(position);
            showPasswordPromptForUser(username, position);
        });

        btnAddUser.setOnClickListener(v -> showAddUserDialog());
        btnSelectMultiple.setOnClickListener(v -> toggleMultiSelectMode(!multiSelectMode));

        btnDelete.setOnClickListener(v -> {
            // collect selected items
            Set<String> toDelete = new HashSet<>();
            if (multiSelectMode) {
                for (int i = 0; i < recentLogins.size(); i++) {
                    if (listViewRecentLogins.isItemChecked(i)) {
                        toDelete.add(recentLogins.get(i));
                    }
                }
            } else {
                Toast.makeText(this, "Enable Select multiple first", Toast.LENGTH_SHORT).show();
                return;
            }

            if (toDelete.isEmpty()) {
                Toast.makeText(this, "No users selected to delete", Toast.LENGTH_SHORT).show();
                return;
            }

            new AlertDialog.Builder(this)
                    .setTitle("Delete selected")
                    .setMessage("Delete " + toDelete.size() + " account(s)? This cannot be undone.")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        boolean ok = deleteUsersFromCsv(toDelete);
                        if (ok) {
                            String curr = userPrefs.getString(PREF_CURRENT_USER, "User");
                            if (curr != null && toDelete.contains(curr)) {
                                // finalize any session for the user we are removing
                                sendBroadcast(new Intent(ACTION_CLOSE_SESSION));
                                // request MainActivity reset forcibly (robust)
                                try {
                                    getSharedPreferences("BlePrefs", MODE_PRIVATE).edit().putBoolean("force_reset", true).apply();
                                } catch (Exception ignored) {}
                                try {
                                    Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                                    toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                    startActivity(toMain);
                                } catch (Exception ignored) {}
                                userPrefs.edit().putString(PREF_CURRENT_USER, "User").apply();
                                updateDrawerHeaderUsername();
                            }
                            refreshRecentLoginsFromCsv();
                            toggleMultiSelectMode(false);
                            updateLoggedInLabelAndSelection();
                            Toast.makeText(this, "Deleted selected users", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Failed to delete users", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnLogout.setOnClickListener(v -> {
            String curr = userPrefs.getString(PREF_CURRENT_USER, "User");
            if ("User".equals(curr)) {
                new AlertDialog.Builder(this)
                        .setTitle("Login required")
                        .setMessage("You are using the default user. Please login or create a user.")
                        .setPositiveButton("Open Login", (dialog, which) -> startActivity(new Intent(UserSettingsActivity.this, LoginActivity.class)))
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                // finalize session for current user before logging out
                sendBroadcast(new Intent(ACTION_CLOSE_SESSION));
                // request MainActivity reset forcibly (robust)
                try {
                    getSharedPreferences("BlePrefs", MODE_PRIVATE).edit().putBoolean("force_reset", true).apply();
                } catch (Exception ignored) {}
                try {
                    Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                    toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(toMain);
                } catch (Exception ignored) {}

                userPrefs.edit().putString(PREF_CURRENT_USER, "User").apply();
                updateLoggedInLabelAndSelection();
                updateDrawerHeaderUsername();
                Toast.makeText(UserSettingsActivity.this, "Logged out", Toast.LENGTH_SHORT).show();
            }
        });

        // Register broadcast receivers (safe register pattern)
        try {
            registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverRegistered = true;
        } catch (Exception e) {
            receiverRegistered = false;
        }
        try {
            registerReceiver(connStateReceiver, new IntentFilter("com.example.bledatalogger.ACTION_CONN_STATE"));
            connReceiverRegistered = true;
        } catch (Exception e) {
            connReceiverRegistered = false;
        }

        // initial state
        toggleMultiSelectMode(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBleStatusCircle(bluetoothAdapter != null && bluetoothAdapter.isEnabled(), userPrefs.getBoolean("ble_connected", false));
        refreshRecentLoginsFromCsv();
        updateLoggedInLabelAndSelection();
        updateDrawerHeaderUsername();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean adapterOn = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        boolean storedConnected = getSharedPreferences("BlePrefs", MODE_PRIVATE).getBoolean("ble_connected", false);
        Log.d(TAG, "onNewIntent: adapterOn=" + adapterOn + " storedConnected=" + storedConnected);
        updateBleStatusCircle(adapterOn, storedConnected);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        if (connReceiverRegistered) {
            try { unregisterReceiver(connStateReceiver); } catch (Exception ignored) {}
            connReceiverRegistered = false;
        }
    }

    // ---------------- BLE helpers ----------------

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
            } else {
                bleStatusIcon.setImageResource(R.drawable.bluetooth_on);
                bleStatusIcon.setContentDescription(getString(R.string.ble_status_on));
            }
            bleStatusIcon.setImageTintList(null);
        }
    }

    // ---------------- Adapter & UI helpers ----------------

    /**
     * Custom adapter to show: [radio][username] ... [eye]
     * Eye opens details dialog; radio visuals follow ListView selection state.
     */
    private class UserAdapter extends ArrayAdapter<String> {
        private final int resource;

        UserAdapter(Context ctx, int resource, List<String> items) {
            super(ctx, resource, items);
            this.resource = resource;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(resource, parent, false);
            }

            ImageView ivEye = v.findViewById(R.id.ivEye);
            TextView tvName = v.findViewById(R.id.tvUserNameRow);
            RadioButton rb = v.findViewById(R.id.rbSelect);

            String name = getItem(position);
            tvName.setText(name != null ? name : "");

            // Eye click opens details dialog (preview)
            ivEye.setOnClickListener(view -> showUserDetailsDialog(name));

            // Keep radio button in sync with ListView selection state
            boolean checked = false;
            if (listViewRecentLogins != null) {
                try {
                    checked = listViewRecentLogins.isItemChecked(position);
                } catch (Exception ignored) { checked = false; }
            }
            rb.setChecked(checked);

            // Optional: tint the eye if selected
            if (checked) {
                try { ivEye.setColorFilter(ContextCompat.getColor(getContext(), R.color.brand_dark_blue)); } catch (Exception ignored) {}
            } else {
                try { ivEye.clearColorFilter(); } catch (Exception ignored) {}
            }

            // ensure row container doesn't swallow ListView clicks
            v.setClickable(false);
            return v;
        }
    }

    // Toggle multi-select mode on/off (single <-> multiple choice adapters)
    private void toggleMultiSelectMode(boolean enable) {
        multiSelectMode = enable;
        if (enable) {
            recentAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, recentLogins);
            listViewRecentLogins.setAdapter(recentAdapter);
            listViewRecentLogins.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            btnSelectMultiple.setText("Cancel select");
            btnDelete.setEnabled(false);
        } else {
            // back to custom adapter (eye + radio)
            userAdapter = new UserAdapter(this, R.layout.list_item_user, recentLogins);
            listViewRecentLogins.setAdapter(userAdapter);
            listViewRecentLogins.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            btnSelectMultiple.setText("Select multiple");
            btnDelete.setEnabled(false);
            listViewRecentLogins.clearChoices();
            userAdapter.notifyDataSetChanged();
        }
    }

    private void updateDeleteButtonState() {
        if (!multiSelectMode) {
            btnDelete.setEnabled(false);
            return;
        }
        boolean any = false;
        for (int i = 0; i < recentLogins.size(); i++) {
            if (listViewRecentLogins.isItemChecked(i)) { any = true; break; }
        }
        btnDelete.setEnabled(any);
    }

    private void updateLoggedInLabelAndSelection() {
        String current = userPrefs.getString(PREF_CURRENT_USER, "User");
        if (current == null || current.isEmpty() || "User".equals(current)) {
            tvLoggedInLabelSmall.setText("Please login with your name");
            tvLoggedInUser.setText("");
            try { imgUserAvatar.setImageResource(R.drawable.ic_person_avatar_from_orig_filled); } catch (Exception ignored) {}
            if (!multiSelectMode) {
                listViewRecentLogins.clearChoices();
                if (userAdapter != null) userAdapter.notifyDataSetChanged();
                if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
            }
            return;
        } else {
            tvLoggedInLabelSmall.setText("Logged in to");
            tvLoggedInUser.setText(current);
            try { imgUserAvatar.setImageResource(R.drawable.ic_person_avatar_from_orig_filled); } catch (Exception ignored) {}
        }

        if (!multiSelectMode) {
            int index = -1;
            for (int i = 0; i < recentLogins.size(); i++) {
                if (recentLogins.get(i).equals(current)) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                listViewRecentLogins.setItemChecked(index, true);
                if (userAdapter != null) userAdapter.notifyDataSetChanged();
            } else {
                listViewRecentLogins.clearChoices();
            }
        } else {
            listViewRecentLogins.clearChoices();
        }
    }

    // ---------------- Dialogs & CSV helpers ----------------

    private void showPasswordPromptForUser(String username, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter password for " + username);

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        input.setHint("4-digit password (optional)");

        builder.setView(input);
        builder.setPositiveButton("Confirm", (dialog, which) -> {
            String entered = input.getText().toString().trim();
            String storedPass = getPasswordForUserFromCsv(username);
            if (storedPass == null || storedPass.isEmpty() || entered.equals(storedPass)) {
                // finalize previous user's session before switching
                sendBroadcast(new Intent(ACTION_CLOSE_SESSION));
                // request MainActivity reset forcibly (robust)
                try {
                    getSharedPreferences("BlePrefs", MODE_PRIVATE).edit().putBoolean("force_reset", true).apply();
                } catch (Exception ignored) {}
                try {
                    Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                    toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(toMain);
                } catch (Exception ignored) {}

                // successful authentication -> set current user
                userPrefs.edit().putString(PREF_CURRENT_USER, username).apply();
                updateDrawerHeaderUsername();

                // ensure per-user dirs exist
                createUserDataDirs(username);

                // touch timestamp so selected user becomes newest (top of list)
                boolean ok = touchUserTimestamp(username);
                if (!ok) {
                    Log.w(TAG, "touchUserTimestamp failed for " + username);
                }

                // refresh list from CSV and update UI
                refreshRecentLoginsFromCsv();

                // find new index and select it (should be 0 if timestamp updated)
                int newIndex = -1;
                for (int i = 0; i < recentLogins.size(); i++) {
                    if (recentLogins.get(i).equals(username)) {
                        newIndex = i;
                        break;
                    }
                }
                if (newIndex >= 0) {
                    listViewRecentLogins.setItemChecked(newIndex, true);
                } else {
                    listViewRecentLogins.clearChoices();
                }

                updateLoggedInLabelAndSelection();
                Toast.makeText(this, "Switched to " + username, Toast.LENGTH_SHORT).show();

                // start new session for the newly-selected user
                try {
                    sendBroadcast(new Intent(ACTION_START_NEW_SESSION));
                    Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                    toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(toMain);
                } catch (Exception ignored) {}
            } else {
                Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show();
                updateLoggedInLabelAndSelection();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.dismiss();
            updateLoggedInLabelAndSelection();
        });
        builder.show();
    }

    private File getUsersCsvFile() {
        File baseDir = new File(getExternalFilesDir(null), DATA_BASE_FOLDER);
        if (!baseDir.exists()) baseDir.mkdirs();
        return new File(baseDir, USERS_CSV_NAME);
    }

    private String getPasswordForUserFromCsv(String username) {
        File csv = getUsersCsvFile();
        if (!csv.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (line.toLowerCase().startsWith("name,password")) continue;
                String[] parts = line.split(",", -1);
                if (parts.length >= 2) {
                    String name = parts[0].trim();
                    String pass = parts[1].trim();
                    if (name.equals(username)) return pass;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "read users.csv failed", e);
        }
        return null;
    }

    private boolean appendUserToCsv(String name, String passwordNumeric, String email) {
        File csv = getUsersCsvFile();
        boolean newFile = false;
        try {
            if (!csv.exists()) {
                if (!csv.createNewFile()) {
                    Log.e(TAG, "Failed to create users.csv");
                    return false;
                }
                newFile = true;
            }
        } catch (IOException e) {
            Log.e(TAG, "create users.csv failed", e);
            return false;
        }

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(csv, true))) {
            if (newFile) bw.write("name,password,email,created_at\n");
            String ts = String.valueOf(System.currentTimeMillis());
            String safeName = name.replace(",", ";");
            String safeEmail = (email == null) ? "" : email.replace(",", ";");
            String safePass = (passwordNumeric == null) ? "" : passwordNumeric.replace(",", ";");
            String line = safeName + "," + safePass + "," + safeEmail + "," + ts + "\n";
            bw.write(line);
            bw.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "write users.csv failed", e);
            return false;
        }
    }

    private boolean deleteUsersFromCsv(Set<String> toDelete) {
        File csv = getUsersCsvFile();
        if (!csv.exists()) return true; // nothing to do
        File tmp = new File(csv.getAbsolutePath() + ".tmp");
        boolean wrote = false;
        try (BufferedReader br = new BufferedReader(new FileReader(csv));
             BufferedWriter bw = new BufferedWriter(new FileWriter(tmp))) {
            String line;
            String headerLine = null;
            // preserve header
            if ((headerLine = br.readLine()) != null) {
                // write header back
                if (!headerLine.trim().isEmpty()) {
                    bw.write(headerLine);
                    bw.newLine();
                    wrote = true;
                }
            }
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", -1);
                String name = parts.length > 0 ? parts[0].trim() : "";
                if (name.isEmpty()) continue;
                if (toDelete.contains(name)) continue;
                bw.write(line);
                bw.newLine();
                wrote = true;
            }
        } catch (IOException e) {
            Log.e(TAG, "deleteUsersFromCsv read/write failed", e);
            return false;
        }
        try {
            if (!csv.delete()) Log.w(TAG, "Unable to delete original CSV");
            if (!tmp.renameTo(csv)) {
                Log.e(TAG, "Failed to rename temp csv");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "replace csv failed", e);
            return false;
        }
        return wrote;
    }

    private void refreshRecentLoginsFromCsv() {
        recentLogins.clear();
        File csv = getUsersCsvFile();
        if (!csv.exists()) {
            if (userAdapter != null) userAdapter.notifyDataSetChanged();
            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
            return;
        }

        class Entry { String name; long ts; }
        List<Entry> entries = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String line;
            // read header first (skip it)
            if ((line = br.readLine()) == null) {
                // empty file
            }
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", -1);
                String name = parts.length > 0 ? parts[0].trim() : "";
                String tsStr = parts.length > 3 ? parts[3].trim() : "";
                long ts = 0L;
                try { if (!tsStr.isEmpty()) ts = Long.parseLong(tsStr); } catch (NumberFormatException ignored) {}
                if (!name.isEmpty()) {
                    Entry e = new Entry();
                    e.name = name;
                    e.ts = ts;
                    entries.add(e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read users.csv", e);
        }

        // newest-first
        entries.sort((a, b) -> Long.compare(b.ts, a.ts));

        recentLogins.clear();
        for (Entry e : entries) {
            if (!recentLogins.contains(e.name)) recentLogins.add(e.name);
        }

        if (userAdapter != null) userAdapter.notifyDataSetChanged();
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
    }

    // ---------------- Header-aware CSV read & details dialog ----------------

    private Map<String, Object> getUserRecordFromCsvMap(String username) {
        File csv = getUsersCsvFile();
        if (!csv.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String headerLine = null;
            // read header (first non-empty)
            while ((headerLine = br.readLine()) != null) {
                if (headerLine.trim().isEmpty()) continue;
                break;
            }
            if (headerLine == null) return null;
            String[] headers = headerLine.split(",", -1);
            for (int i = 0; i < headers.length; i++) headers[i] = headers[i].trim();

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", -1);
                String name = parts.length > 0 ? parts[0].trim() : "";
                if (name.equals(username)) {
                    Map<String, String> record = new HashMap<>();
                    for (int i = 0; i < headers.length; i++) {
                        String key = headers[i].isEmpty() ? ("col" + i) : headers[i];
                        String val = (i < parts.length) ? parts[i].trim() : "";
                        record.put(key, val);
                    }
                    Map<String, Object> out = new HashMap<>();
                    out.put("headers", headers);
                    out.put("record", record);
                    return out;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "getUserRecordFromCsvMap failed", e);
        }
        return null;
    }

    private void showUserDetailsDialog(String username) {
        Map<String, Object> result = getUserRecordFromCsvMap(username);
        if (result == null) {
            new AlertDialog.Builder(this)
                    .setTitle("User details")
                    .setMessage("No details found for " + username)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String[] headers = (String[]) result.get("headers");
        @SuppressWarnings("unchecked")
        Map<String, String> record = (Map<String, String>) result.get("record");

        StringBuilder msg = new StringBuilder();
        for (String h : headers) {
            String label = (h == null || h.trim().isEmpty()) ? "Unknown" : h.trim();
            String value = record.getOrDefault(h, "").trim();

            // If empty show "No"
            if (value.isEmpty()) {
                value = "No";
            } else {
                // Detect likely timestamp fields by header name or numeric epoch value
                String keyLower = label.toLowerCase();
                boolean headerLooksLikeTime = keyLower.contains("created") || keyLower.contains("created_at")
                        || keyLower.contains("date") || keyLower.contains("time")
                        || keyLower.contains("ts") || keyLower.contains("timestamp") || keyLower.contains("at");
                boolean looksLikeEpochDigits = value.matches("^\\d{10,}$"); // 10+ digits

                if (headerLooksLikeTime || looksLikeEpochDigits) {
                    try {
                        long epoch = Long.parseLong(value);
                        // convert seconds to millis if necessary (10-digit = seconds)
                        if (String.valueOf(epoch).length() == 10) epoch = epoch * 1000L;
                        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance();
                        value = df.format(new java.util.Date(epoch));
                    } catch (Exception ignored) {
                        // if parsing fails, leave raw value
                    }
                }
            }
            msg.append(label).append(": ").append(value).append("\n\n");
        }

        AlertDialog.Builder db = new AlertDialog.Builder(this)
                .setTitle("User details")
                .setMessage(msg.toString())
                .setPositiveButton("OK", null)
                .setNegativeButton("Delete", (d, which) -> {
                    // use 'name' header if present; otherwise try the first header's value
                    String targetName = record.containsKey("name") ? record.get("name")
                            : (record.values().iterator().hasNext() ? record.values().iterator().next() : username);
                    new AlertDialog.Builder(UserSettingsActivity.this)
                            .setTitle("Delete user")
                            .setMessage("Delete user " + targetName + "? This cannot be undone.")
                            .setPositiveButton("Delete", (dd, ww) -> {
                                Set<String> del = new HashSet<>();
                                del.add(targetName);
                                if (deleteUsersFromCsv(del)) {
                                    if (targetName.equals(userPrefs.getString(PREF_CURRENT_USER, "User"))) {
                                        // finalize session for deleted user
                                        sendBroadcast(new Intent(ACTION_CLOSE_SESSION));
                                        // request MainActivity reset forcibly (robust)
                                        try {
                                            getSharedPreferences("BlePrefs", MODE_PRIVATE).edit().putBoolean("force_reset", true).apply();
                                        } catch (Exception ignored) {}
                                        try {
                                            Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                                            toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                            startActivity(toMain);
                                        } catch (Exception ignored) {}

                                        userPrefs.edit().putString(PREF_CURRENT_USER, "User").apply();
                                        updateDrawerHeaderUsername();
                                    }
                                    refreshRecentLoginsFromCsv();
                                    updateLoggedInLabelAndSelection();
                                    Toast.makeText(UserSettingsActivity.this, "Deleted " + targetName, Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(UserSettingsActivity.this, "Failed to delete " + targetName, Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });

        db.setNeutralButton("Edit", (d, which) -> {
            d.dismiss();
            // open edit dialog using the 'name' key if present
            String targetName = record.containsKey("name") ? record.get("name")
                    : (record.values().iterator().hasNext() ? record.values().iterator().next() : username);
            showEditUserDialog(targetName);
        });

        db.show();
    }

    // ---------------- Update (edit) helpers ----------------

    private boolean updateUserInCsv(String oldName, String newName, String passwordNumeric, String email) {
        File csv = getUsersCsvFile();
        if (!csv.exists()) return false;
        File tmp = new File(csv.getAbsolutePath() + ".tmp");
        boolean wrote = false;
        try (BufferedReader br = new BufferedReader(new FileReader(csv));
             BufferedWriter bw = new BufferedWriter(new FileWriter(tmp))) {
            String headerLine = null;
            if ((headerLine = br.readLine()) != null) {
                // write header back
                bw.write(headerLine);
                bw.newLine();
                wrote = true;
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", -1);
                String name = parts.length > 0 ? parts[0].trim() : "";
                String createdAt = parts.length > 3 ? parts[3].trim() : "";
                if (name.equals(oldName)) {
                    String safeName = newName.replace(",", ";");
                    String safePass = (passwordNumeric == null) ? "" : passwordNumeric.replace(",", ";");
                    String safeEmail = (email == null) ? "" : email.replace(",", ";");
                    String ts = (!createdAt.isEmpty()) ? createdAt : String.valueOf(System.currentTimeMillis());
                    String newline = safeName + "," + safePass + "," + safeEmail + "," + ts;
                    bw.write(newline);
                    bw.newLine();
                    wrote = true;
                } else {
                    bw.write(line);
                    bw.newLine();
                    wrote = true;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "updateUserInCsv read/write failed", e);
            return false;
        }
        try {
            if (!csv.delete()) Log.w(TAG, "Unable to delete original CSV");
            if (!tmp.renameTo(csv)) {
                Log.e(TAG, "Failed to rename temp csv");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "replace csv failed", e);
            return false;
        }
        return wrote;
    }

    private void showEditUserDialog(String originalName) {
        Map<String, Object> result = getUserRecordFromCsvMap(originalName);
        if (result == null) {
            Toast.makeText(this, "User not found", Toast.LENGTH_SHORT).show();
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> record = (Map<String, String>) result.get("record");

        String curName = record.getOrDefault("name", originalName);
        String curPass = record.getOrDefault("password", "");
        String curEmail = record.getOrDefault("email", record.getOrDefault("Email", record.getOrDefault("e-mail", "")));

        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_add_user, null); // reuse same dialog layout (etName, etPasswordNumeric, etEmail)

        final EditText etName = dialogView.findViewById(R.id.etName);
        final EditText etPassword = dialogView.findViewById(R.id.etPasswordNumeric);
        final EditText etEmail = dialogView.findViewById(R.id.etEmail);

        etName.setText(curName);
        etPassword.setText(curPass == null ? "" : curPass);
        etEmail.setText(curEmail == null ? "" : curEmail);

        InputFilter numericFilter = (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                if (!Character.isDigit(source.charAt(i))) return "";
            }
            return null;
        };
        etPassword.setFilters(new InputFilter[]{numericFilter, new InputFilter.LengthFilter(4)});

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit User");
        builder.setView(dialogView);
        builder.setCancelable(true);
        builder.setPositiveButton("Save", null);
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dlg -> {
            Button b = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            b.setOnClickListener(v -> {
                String newName = etName.getText().toString().trim();
                String newPass = etPassword.getText().toString().trim();
                String newEmail = etEmail.getText().toString().trim();

                if (newName.isEmpty()) { etName.setError("Name required"); return; }
                if (!newPass.isEmpty() && !newPass.matches("\\d{4}")) { etPassword.setError("Password must be exactly 4 digits or empty"); return; }
                if (!newEmail.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) { etEmail.setError("Invalid email"); return; }

                boolean ok = updateUserInCsv(originalName, newName, newPass, newEmail);
                if (ok) {
                    String curr = userPrefs.getString(PREF_CURRENT_USER, "User");
                    if (curr != null && curr.equals(originalName)) {
                        // finalize previous session before renaming current user
                        sendBroadcast(new Intent(ACTION_CLOSE_SESSION));
                        // request MainActivity reset forcibly (robust)
                        try {
                            getSharedPreferences("BlePrefs", MODE_PRIVATE).edit().putBoolean("force_reset", true).apply();
                        } catch (Exception ignored) {}
                        try {
                            Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                            toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(toMain);
                        } catch (Exception ignored) {}

                        userPrefs.edit().putString(PREF_CURRENT_USER, newName).apply();
                        updateDrawerHeaderUsername();
                        createUserDataDirs(newName);

                        // after renaming to a new current user, request MainActivity to start a fresh session
                        try {
                            sendBroadcast(new Intent(ACTION_START_NEW_SESSION));
                            Intent toMain2 = new Intent(UserSettingsActivity.this, MainActivity.class);
                            toMain2.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(toMain2);
                        } catch (Exception ignored) {}
                    }
                    refreshRecentLoginsFromCsv();
                    updateLoggedInLabelAndSelection();
                    Toast.makeText(UserSettingsActivity.this, "Updated user " + newName, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    Toast.makeText(UserSettingsActivity.this, "Failed to update user", Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    // Update only the created_at timestamp for the given username to "now".
    // Returns true if update succeeded.
    private boolean touchUserTimestamp(String username) {
        File csv = getUsersCsvFile();
        if (!csv.exists()) return false;
        File tmp = new File(csv.getAbsolutePath() + ".tmp");
        boolean wrote = false;
        try (BufferedReader br = new BufferedReader(new FileReader(csv));
             BufferedWriter bw = new BufferedWriter(new FileWriter(tmp))) {
            String headerLine = null;
            // write header if present (first non-empty line)
            while ((headerLine = br.readLine()) != null) {
                if (headerLine.trim().isEmpty()) continue;
                bw.write(headerLine);
                bw.newLine();
                wrote = true;
                break;
            }
            if (headerLine == null) {
                // empty file
                return false;
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", -1);
                String name = parts.length > 0 ? parts[0].trim() : "";
                // preserve other fields; set created_at to now for matching user
                if (name.equals(username)) {
                    String safeName = name.replace(",", ";");
                    String safePass = (parts.length > 1) ? parts[1].replace(",", ";") : "";
                    String safeEmail = (parts.length > 2) ? parts[2].replace(",", ";") : "";
                    String ts = String.valueOf(System.currentTimeMillis());
                    String newline = safeName + "," + safePass + "," + safeEmail + "," + ts;
                    bw.write(newline);
                    bw.newLine();
                    wrote = true;
                } else {
                    bw.write(line);
                    bw.newLine();
                    wrote = true;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "touchUserTimestamp read/write failed", e);
            return false;
        }
        // replace original
        try {
            if (!csv.delete()) Log.w(TAG, "Unable to delete original CSV");
            if (!tmp.renameTo(csv)) {
                Log.e(TAG, "Failed to rename temp csv in touchUserTimestamp");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "replace csv failed in touchUserTimestamp", e);
            return false;
        }
        return wrote;
    }

    // Update the navigation drawer header username text from SharedPreferences
    private void updateDrawerHeaderUsername() {
        try {
            NavigationView nv = findViewById(R.id.navigationView);
            if (nv == null) return;
            View header = nv.getHeaderView(0);
            if (header == null) return;
            TextView drawerUsername = header.findViewById(R.id.drawerUsername);
            if (drawerUsername == null) return;
            String current = userPrefs.getString(PREF_CURRENT_USER, "User");
            if (current == null || current.isEmpty()) current = "User";
            drawerUsername.setText(current);
        } catch (Exception e) {
            Log.w(TAG, "updateDrawerHeaderUsername failed", e);
        }
    }

    // Create per-user data folders under the app external files dir: Analysed and Raw
    private File createUserDataDirs(String username) {
        if (username == null) return null;
        String safe = safeFileName(username);
        File base = new File(getExternalFilesDir(null), DATA_BASE_FOLDER);
        File dataRoot = new File(base, "Data");
        File userDir = new File(dataRoot, safe);

        File analysed = new File(userDir, "Analysed");
        File raw = new File(userDir, "Raw");

        try {
            if (!analysed.exists() && !analysed.mkdirs()) {
                Log.w(TAG, "Failed to create analysed dir: " + analysed.getAbsolutePath());
            }
            if (!raw.exists() && !raw.mkdirs()) {
                Log.w(TAG, "Failed to create raw dir: " + raw.getAbsolutePath());
            }
            return userDir;
        } catch (Exception e) {
            Log.e(TAG, "createUserDataDirs error", e);
            return null;
        }
    }

    // Safe filename generator: remove or replace characters that are not safe in file/directory names.
    private String safeFileName(String raw) {
        if (raw == null) return "unknown";
        // replace commas, slashes, colons, control chars with underscore
        String s = raw.trim().replaceAll("[/,\\\\:;\\*\\?\"<>\\|]", "_");
        // also replace sequences of whitespace with a single underscore
        s = s.replaceAll("\\s+", "_");
        if (s.isEmpty()) s = "user";
        // optional: limit length
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }

    // Add / Edit / Add-user dialog (placed after safeFileName to match your original structure)
    private void showAddUserDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_add_user, null);

        final EditText etName = dialogView.findViewById(R.id.etName);
        final EditText etPassword = dialogView.findViewById(R.id.etPasswordNumeric);
        final EditText etEmail = dialogView.findViewById(R.id.etEmail);

        InputFilter numericFilter = (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                if (!Character.isDigit(source.charAt(i))) return "";
            }
            return null;
        };
        etPassword.setFilters(new InputFilter[]{numericFilter, new InputFilter.LengthFilter(4)});

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add User");
        builder.setView(dialogView);
        builder.setCancelable(true);
        builder.setPositiveButton("Save", null);
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button b = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            b.setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                String pass = etPassword.getText().toString().trim();
                String email = etEmail.getText().toString().trim();

                if (name.isEmpty()) { etName.setError("Name required"); return; }
                if (!pass.isEmpty() && !pass.matches("\\d{4}")) { etPassword.setError("Password must be exactly 4 digits or empty"); return; }
                if (!email.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { etEmail.setError("Invalid email"); return; }

                // Check for duplicate username
                boolean exists = false;
                for (String existing : recentLogins) {
                    if (existing.equals(name)) { exists = true; break; }
                }

                if (exists) {
                    new AlertDialog.Builder(UserSettingsActivity.this)
                            .setTitle("Username exists")
                            .setMessage("A user named \"" + name + "\" already exists.\n\nDo you want to login to the previous account instead?")
                            .setPositiveButton("Yes, login", (dd, ww) -> {
                                // finalize previous user's session before switching
                                try { sendBroadcast(new Intent(ACTION_CLOSE_SESSION)); } catch (Exception ignored) {}
                                // request MainActivity reset forcibly (robust)
                                try {
                                    getSharedPreferences("BlePrefs", MODE_PRIVATE).edit().putBoolean("force_reset", true).apply();
                                } catch (Exception ignored) {}
                                try {
                                    Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                                    toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                    startActivity(toMain);
                                } catch (Exception ignored) {}

                                userPrefs.edit().putString(PREF_CURRENT_USER, name).apply();
                                createUserDataDirs(name);
                                boolean ok = touchUserTimestamp(name);
                                if (!ok) Log.w(TAG, "touchUserTimestamp failed for existing user " + name);
                                refreshRecentLoginsFromCsv();
                                int newIndex = -1;
                                for (int i = 0; i < recentLogins.size(); i++) {
                                    if (recentLogins.get(i).equals(name)) { newIndex = i; break; }
                                }
                                if (newIndex >= 0) listViewRecentLogins.setItemChecked(newIndex, true);
                                else listViewRecentLogins.clearChoices();
                                updateLoggedInLabelAndSelection();
                                updateDrawerHeaderUsername();

                                // start new session for the user
                                try {
                                    sendBroadcast(new Intent(ACTION_START_NEW_SESSION));
                                    Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                                    toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                                    startActivity(toMain);
                                } catch (Exception ignored) {}

                                Toast.makeText(UserSettingsActivity.this, "Logged in as " + name, Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                            })
                            .setNegativeButton("No, choose different", (dd, ww) -> {})
                            .show();
                    return;
                }

                // Not a duplicate — append as before
                boolean ok = appendUserToCsv(name, pass, email);
                if (ok) {
                    // finalize previous session before switching to new user
                    try { sendBroadcast(new Intent(ACTION_CLOSE_SESSION)); } catch (Exception ignored) {}
                    // request MainActivity reset forcibly (robust)
                    try {
                        getSharedPreferences("BlePrefs", MODE_PRIVATE).edit().putBoolean("force_reset", true).apply();
                    } catch (Exception ignored) {}
                    try {
                        Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                        toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(toMain);
                    } catch (Exception ignored) {}

                    userPrefs.edit().putString(PREF_CURRENT_USER, name).apply();
                    createUserDataDirs(name);
                    updateDrawerHeaderUsername();
                    refreshRecentLoginsFromCsv();
                    toggleMultiSelectMode(false);
                    updateLoggedInLabelAndSelection();
                    Toast.makeText(UserSettingsActivity.this, "Saved user " + name, Toast.LENGTH_SHORT).show();

                    // start new session for the newly created user
                    try {
                        sendBroadcast(new Intent(ACTION_START_NEW_SESSION));
                        Intent toMain = new Intent(UserSettingsActivity.this, MainActivity.class);
                        toMain.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(toMain);
                    } catch (Exception ignored) {}

                    dialog.dismiss();
                } else {
                    Toast.makeText(UserSettingsActivity.this, "Failed to save user", Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

}