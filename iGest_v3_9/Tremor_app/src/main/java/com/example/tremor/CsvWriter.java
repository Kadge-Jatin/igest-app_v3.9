package com.example.tremor;

import android.util.Log;
// add import below only if you still need Context elsewhere
// import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class CsvWriter {
    private static final String TAG = "CsvWriter";

    // static debug flag set by the host app
    private static boolean sIsDebug = false;

    // call from host app at startup: CsvWriter.setDebug(BuildConfig.DEBUG);
    public static void setDebug(boolean debug) {
        sIsDebug = debug;
    }

    // no-arg check used throughout this class
    private static boolean isDebug() {
        return sIsDebug;
    }

    private BufferedWriter bw;
    private boolean open = false;
    private final File file;

    public CsvWriter(File file) {
        this.file = file;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                boolean ok = parent.mkdirs();
                if (!ok) {
                    Log.w(TAG, "Could not create parent directories: " + parent.getAbsolutePath());
                }
            }
            bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
            open = true;
        } catch (IOException e) {
            open = false;
            bw = null;
            Log.e(TAG, "Failed to open CSV: " + file.getAbsolutePath(), e);
            if (isDebug()) throw new RuntimeException("Failed to open CSV: " + file.getAbsolutePath(), e);
        }
    }

    public synchronized boolean writeRow(Object[] row) {
        if (!open || bw == null) {
            Log.e(TAG, "CSV writer not open for file: " + (file != null ? file.getAbsolutePath() : "null"));
            if (isDebug()) throw new RuntimeException("CSV writer not open");
            return false;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                sb.append(escapeCsvField(row[i]));
                if (i < row.length - 1) sb.append(",");
            }
            bw.write(sb.toString());
            bw.newLine();
            bw.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write CSV row to " + file.getAbsolutePath(), e);
            if (isDebug()) throw new RuntimeException(e);
            return false;
        }
    }

    public synchronized boolean writeRow(double[] row) {
        Object[] objs = new Object[row.length];
        for (int i = 0; i < row.length; i++) objs[i] = row[i];
        return writeRow(objs);
    }

    public synchronized void close() {
        if (bw != null) {
            try {
                bw.flush();
                bw.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing CSV writer for " + file.getAbsolutePath(), e);
            } finally {
                bw = null;
                open = false;
            }
        } else {
            open = false;
        }
    }

    private static String escapeCsvField(Object obj) {
        if (obj == null) return "";
        String s = obj.toString();
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (s.contains("\"")) {
            s = s.replace("\"", "\"\"");
            needQuote = true;
        }
        if (needQuote) {
            return "\"" + s + "\"";
        } else {
            return s;
        }
    }
}