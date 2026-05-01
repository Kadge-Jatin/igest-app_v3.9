package com.example.igest_v3;

import android.app.Application;
import android.util.Log;

// import com.example.tremor.TremorInitializer; // enable if TremorInitializer is safe

public class ShellApplication extends Application {
    private static final String TAG = "ShellApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        // If you want the Tremor library initialized at app start, uncomment and guard it:
        /*
        try {
            TremorInitializer.init(this);
        } catch (Throwable t) {
            Log.e(TAG, "TremorInitializer.init failed", t);
            // keep app alive — inspect the log to fix the initializer
        }
        */
    }
}