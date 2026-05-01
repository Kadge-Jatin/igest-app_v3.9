package com.example.anxiety;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class BleManager {

    private static final String TAG = "BleManager";

    private static final String IMU_CHAR_UUID = "19B10001-E8F2-537E-4F6C-D104768A1214";
    private static final String TIME_CHAR_UUID = "19B20001-E8F2-537E-4F6C-D104768A1214";
    private static final String FLASH_DATA_CHAR_UUID = "19B30001-E8F2-537E-4F6C-D104768A1214";
    private static final String CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    private Context context;
    private BleCallback callback;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic imuCharacteristic;
    private BluetoothGattCharacteristic timeCharacteristic;
    private BluetoothGattCharacteristic flashDataCharacteristic;
    private final java.util.ArrayDeque<BluetoothGattCharacteristic> notificationQueue = new java.util.ArrayDeque<>();

    private Handler timeHandler;
    private Runnable timeRunnable;
    private String lastSentMinute = "";


    public interface BleCallback {
        void onConnectionStateChanged(boolean connected);
        void onDataReceived(byte[] rawData, String debugString);
        void onFlashDataReceived(byte[] chunk);
        void onError(String error);
    }

    public BleManager(Context context, BleCallback callback) {
        this.context = context;
        this.callback = callback;

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();

        timeHandler = new Handler(Looper.getMainLooper());
    }

    public void connect(String deviceAddress) {
        if (bluetoothAdapter == null) {
            callback.onError("Bluetooth not available");
            return;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        } catch (Exception e) {
            callback.onError("Failed to connect: " + e.getMessage());
        }
    }

    public void disconnect() {
        stopTimeUpdates();

        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
            } catch (Throwable t) {
                Log.w(TAG, "Error while disconnecting", t);
            }
            try {
                bluetoothGatt.close();
            } catch (Throwable t) {
                Log.w(TAG, "Error while closing gatt", t);
            } finally {
                bluetoothGatt = null;
            }
        }
    }

    public void sendTimeToDevice(String time) {
        BluetoothGattCharacteristic target = (timeCharacteristic != null) ? timeCharacteristic : imuCharacteristic;
        if (target != null && bluetoothGatt != null) {
            // Permission guard for Android S+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    callback.onError("Missing BLUETOOTH_CONNECT permission to write characteristic");
                    return;
                }
            }
            try {
                target.setValue(time.getBytes(StandardCharsets.UTF_8));
                boolean started = bluetoothGatt.writeCharacteristic(target);
                Log.d(TAG, "Sent time to device: " + time + " writeStarted=" + started);
            } catch (Exception e) {
                callback.onError("Failed to send time: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "No writable characteristic available to send time");
        }
    }

    /**
     * Send a MODE string to the device so the peripheral knows which app mode is active.
     * Example payloads: "MODE:TREMOR", "MODE:ANXIETY"
     */
    public void sendModeToDevice(String modeString) {
        if (modeString == null || modeString.isEmpty()) return;

        if (bluetoothGatt == null) {
            Log.w(TAG, "sendModeToDevice: bluetoothGatt is null");
            return;
        }

        BluetoothGattCharacteristic target = (timeCharacteristic != null) ? timeCharacteristic : imuCharacteristic;
        if (target == null) {
            Log.w(TAG, "sendModeToDevice: no writable characteristic available");
            return;
        }

        // Permission guard for Android S+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "sendModeToDevice: missing BLUETOOTH_CONNECT permission");
                callback.onError("Missing BLUETOOTH_CONNECT permission to write characteristic");
                return;
            }
        }

        try {
            byte[] payload = modeString.getBytes(StandardCharsets.UTF_8);
            target.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            target.setValue(payload);
            boolean started = bluetoothGatt.writeCharacteristic(target);
            Log.i(TAG, "sendModeToDevice: writeStarted=" + started + " payload=" + modeString);
        } catch (Exception e) {
            Log.e(TAG, "sendModeToDevice: write failed", e);
            callback.onError("Failed to send MODE command: " + e.getMessage());
        }
    }

    private void startTimeUpdates() {
        String currentTime = new SimpleDateFormat("HH:mm:ss|ddMMyyyy", Locale.getDefault()).format(new Date());
        lastSentMinute = currentTime;
        sendTimeToDevice(currentTime);

        timeRunnable = new Runnable() {
            @Override
            public void run() {
                String currentMinute = new SimpleDateFormat("HH:mm:ss|ddMMyyyy", Locale.getDefault()).format(new Date());

                if (!currentMinute.equals(lastSentMinute)) {
                    sendTimeToDevice(currentMinute);
                    lastSentMinute = currentMinute;
                }
                timeHandler.postDelayed(this, 1000);
            }
        };
        timeHandler.postDelayed(timeRunnable, 1000);
    }

    private void stopTimeUpdates() {
        if (timeHandler != null && timeRunnable != null) {
            timeHandler.removeCallbacks(timeRunnable);
            timeRunnable = null;
        }
    }

    // GATT callback
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server");
                if (gatt != null) {
                    // Assign the received gatt to the manager field
                    bluetoothGatt = gatt;

                    // Request higher MTU if desired; onMtuChanged will proceed with discovering services
                    boolean wantHighMtu = true;
                    if (wantHighMtu) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                    Log.w(TAG, "Missing BLUETOOTH_CONNECT permission for requestMtu");
                                    gatt.discoverServices();
                                    return;
                                }
                            }
                            gatt.requestMtu(247);
                        } catch (Exception e) {
                            Log.w(TAG, "requestMtu failed, continuing to discover services", e);
                            gatt.discoverServices();
                        }
                    } else {
                        gatt.discoverServices();
                    }
                } else {
                    Log.w(TAG, "gatt is null on connect");
                    callback.onError("GATT object is null after connection");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server");
                stopTimeUpdates();
                imuCharacteristic = null;
                timeCharacteristic = null;
                flashDataCharacteristic = null;
                notificationQueue.clear();
                if (bluetoothGatt != null) {
                    try {
                        bluetoothGatt.close();
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing gatt on disconnect", e);
                    }
                    bluetoothGatt = null;
                }
                callback.onConnectionStateChanged(false);
            } else {
                Log.d(TAG, "Connection state changed to " + newState);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "onMtuChanged mtu=" + mtu + " status=" + status);
            // Continue to discover services irrespective of MTU result
            if (gatt != null) {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered");

                UUID imuUuid = UUID.fromString(IMU_CHAR_UUID);
                UUID timeUuid = UUID.fromString(TIME_CHAR_UUID);
                UUID flashUuid = UUID.fromString(FLASH_DATA_CHAR_UUID);

                imuCharacteristic = null;
                timeCharacteristic = null;
                flashDataCharacteristic = null;

                if (gatt == null) {
                    callback.onError("GATT is null during service discovery");
                    return;
                }

                for (android.bluetooth.BluetoothGattService service : gatt.getServices()) {
                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        if (characteristic.getUuid().equals(imuUuid)) {
                            imuCharacteristic = characteristic;
                            Log.d(TAG, "Found IMU characteristic");
                        }
                        if (characteristic.getUuid().equals(timeUuid)) {
                            timeCharacteristic = characteristic;
                            Log.d(TAG, "Found TIME characteristic");
                        }
                        if (characteristic.getUuid().equals(flashUuid)) {
                            flashDataCharacteristic = characteristic;
                            Log.d(TAG, "Found FLASH_DATA characteristic");
                        }
                    }
                }

                if (imuCharacteristic != null) {
                    enableNotifications();
                } else {
                    callback.onError("IMU characteristic not found");
                }
            } else {
                callback.onError("Service discovery failed: " + status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic write successful: " + (characteristic != null ? characteristic.getUuid() : "null"));
            } else {
                Log.e(TAG, "Characteristic write failed: " + status + " uuid=" + (characteristic != null ? characteristic.getUuid() : "null"));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic != null ? characteristic.getValue() : null;
            if (data == null) {
                Log.e(TAG, "Received null characteristic data");
                callback.onError("Received null characteristic data");
                return;
            }
            if (characteristic.getUuid().equals(UUID.fromString(FLASH_DATA_CHAR_UUID))) {
                callback.onFlashDataReceived(data);
            } else {
                callback.onDataReceived(data, bytesToHex(data));
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            UUID writtenUuid = (descriptor != null && descriptor.getCharacteristic() != null)
                    ? descriptor.getCharacteristic().getUuid() : null;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write OK for " + writtenUuid);
                if (notificationQueue.isEmpty()) {
                    callback.onConnectionStateChanged(true);
                    startTimeUpdates();
                } else {
                    enableNextNotification();
                }
            } else {
                Log.e(TAG, "Descriptor write failed status=" + status + " for " + writtenUuid);
                if (writtenUuid != null && writtenUuid.equals(UUID.fromString(IMU_CHAR_UUID))) {
                    callback.onError("Failed to enable notifications: " + status);
                } else {
                    // Flash notification failed — non-critical, proceed without it
                    Log.w(TAG, "Flash descriptor write failed, continuing without flash notifications");
                    notificationQueue.clear();
                    callback.onConnectionStateChanged(true);
                    startTimeUpdates();                }
            }
        }
    };

    private void enableNotifications() {
        notificationQueue.clear();
        if (imuCharacteristic != null) notificationQueue.add(imuCharacteristic);
        if (flashDataCharacteristic != null) notificationQueue.add(flashDataCharacteristic);

        if (notificationQueue.isEmpty()) {
            callback.onError("IMU characteristic not found");
            return;
        }
        enableNextNotification();
    }

    private void enableNextNotification() {
        if (bluetoothGatt == null) return;

        BluetoothGattCharacteristic ch = notificationQueue.poll();
        if (ch == null) {
            // Called with an empty queue — nothing more to enable, no action needed here;
            // finalisation is handled by onDescriptorWrite after the last successful write.
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT permission, skipping notification for " + ch.getUuid());
                proceedOrFinalize();
                return;
            }
        }

        boolean success = bluetoothGatt.setCharacteristicNotification(ch, true);
        if (!success) {
            Log.w(TAG, "setCharacteristicNotification failed for " + ch.getUuid());
            if (ch.getUuid().equals(UUID.fromString(IMU_CHAR_UUID))) {
                callback.onError("Failed to set characteristic notification");
                return;
            }
            proceedOrFinalize();
            return;
        }

        UUID configUuid = UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID);
        BluetoothGattDescriptor descriptor = ch.getDescriptor(configUuid);
        if (descriptor == null) {
            Log.w(TAG, "No CCC descriptor for " + ch.getUuid());
            if (ch.getUuid().equals(UUID.fromString(IMU_CHAR_UUID))) {
                callback.onError("Configuration descriptor not found");
                return;
            }
            proceedOrFinalize();
            return;
        }

        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        success = bluetoothGatt.writeDescriptor(descriptor);
        if (!success) {
            Log.w(TAG, "writeDescriptor failed for " + ch.getUuid());
            if (ch.getUuid().equals(UUID.fromString(IMU_CHAR_UUID))) {
                callback.onError("Failed to write descriptor");
                return;
            }
            proceedOrFinalize();
        }
        // On success, wait for onDescriptorWrite to continue the chain
    }

    /** Enables the next queued characteristic, or finalises setup if the queue is exhausted. */
    private void proceedOrFinalize() {
        if (notificationQueue.isEmpty()) {
            callback.onConnectionStateChanged(true);
            startTimeUpdates();
        } else {
            enableNextNotification();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}