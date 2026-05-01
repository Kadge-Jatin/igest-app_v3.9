package com.example.tremor;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Force light mode for the entire app
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }
}