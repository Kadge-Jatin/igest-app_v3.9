package com.example.anxiety;

public class DeviceStatus {
    public String deviceKey;
    public String displayName;
    public boolean isAnxious;  // true = anxious (red), false = not anxious (green)
    public boolean isEditing = false;

    public DeviceStatus(String deviceKey, String displayName) {
        this.deviceKey = deviceKey;
        this.displayName = displayName;
        this.isAnxious = false;
    }
}