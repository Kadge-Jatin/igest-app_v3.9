package com.example.tremor;

import android.util.Log;

import org.jtransforms.fft.DoubleFFT_1D;

import java.io.*;
import java.util.*;

/**
 * TremorAnalysisUtils - safer and more robust tremor event analysis.
 *
 * - Uses complexForward FFT to avoid packed-format indexing mistakes.
 * - Guards against empty lists and malformed CSV rows.
 * - Bounds the FFT search window (3-12 Hz).
 * - Returns zero metrics and "Normal" grade for empty/invalid events rather than throwing.
 */
public class TremorAnalysisUtils {

    private static final String TAG = "TremorAnalysisUtils";

    public static class TremorEventResult {
        public String startTime;
        public String endTime;
        public double dominantFreqHz;
        public List<Double> xp2pPerWindow; // Peak-to-peak displacement per window (cm)
        public double medianXp2p;
        public double percentile90Xp2p;
        public double maxXp2p;
        public String grade; // "Mild", "Moderate", "Severe", "Normal"
        public int windowCount;
    }

    /**
     * Analyse a raw session CSV and return a list of tremor events detected within the file.
     *
     * @param rawCsvFile     File pointing to the raw CSV (app's Data/Raw/YYYY-MM-DD/...csv)
     * @param samplingRateHz Sampling rate in Hz (e.g. 100.0)
     * @return list of TremorEventResult (may be empty if no events found)
     */
    public static List<TremorEventResult> analyseSession(File rawCsvFile, double samplingRateHz) {
        List<List<Double>> eventSamples = new ArrayList<>();
        List<List<String>> eventTimes = new ArrayList<>();
        List<Double> currentSamples = new ArrayList<>();
        List<String> currentTimes = new ArrayList<>();

        if (rawCsvFile == null || !rawCsvFile.exists()) {
            Log.w(TAG, "analyseSession: rawCsvFile is null or doesn't exist");
            return Collections.emptyList();
        }

        // === Parse CSV into events ===
        // === Parse CSV into events (NEW: use status column) ===
        try (BufferedReader br = new BufferedReader(new FileReader(rawCsvFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] tokens = line.trim().split("[,\\t]+");

                // We treat rows with 4 or 5 columns as sample rows:
                // accelMag,timeDiff,time,status[,observed]
                // Marker rows or rows with other column counts are treated as event boundaries.
                if (tokens.length < 4 || tokens.length > 5) {

                    // marker rows or malformed -> treat as boundary
                    if (!currentSamples.isEmpty()) {
                        eventSamples.add(new ArrayList<>(currentSamples));
                        eventTimes.add(new ArrayList<>(currentTimes));
                        currentSamples.clear();
                        currentTimes.clear();
                    }
                    continue;
                }

                double accelMag;
                int status;
                String ts = tokens[2].trim();

                try {
                    accelMag = Double.parseDouble(tokens[0].trim());
                } catch (NumberFormatException e) {
                    // non-data row -> boundary
                    if (!currentSamples.isEmpty()) {
                        eventSamples.add(new ArrayList<>(currentSamples));
                        eventTimes.add(new ArrayList<>(currentTimes));
                        currentSamples.clear();
                        currentTimes.clear();
                    }
                    continue;
                }

                try {
                    status = Integer.parseInt(tokens[3].trim());
                } catch (NumberFormatException e) {
                    status = 0; // treat unknown as non-tremor
                }

                if (status == 1) {
                    currentSamples.add(accelMag);
                    currentTimes.add(ts);
                } else {
                    // status==0 ends a tremor run
                    if (!currentSamples.isEmpty()) {
                        eventSamples.add(new ArrayList<>(currentSamples));
                        eventTimes.add(new ArrayList<>(currentTimes));
                        currentSamples.clear();
                        currentTimes.clear();
                    }
                }
            }

            if (!currentSamples.isEmpty()) {
                eventSamples.add(new ArrayList<>(currentSamples));
                eventTimes.add(new ArrayList<>(currentTimes));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading CSV: " + e.getMessage(), e);
            return Collections.emptyList();
        }

        // === Analyse each event ===
        List<TremorEventResult> results = new ArrayList<>();
        for (int i = 0; i < eventSamples.size(); i++) {
            List<Double> samples = eventSamples.get(i);
            List<String> times = eventTimes.get(i);

            // Skip extremely short events (< 1 second)
            if (samples == null || samples.size() < Math.max(1, (int)Math.round(samplingRateHz))) {
                continue;
            }

            TremorEventResult result = new TremorEventResult();
            result.startTime = (times != null && !times.isEmpty()) ? times.get(0) : "-";
            result.endTime = (times != null && !times.isEmpty()) ? times.get(times.size() - 1) : "-";

            int n = samples.size();
            double[] realInput = new double[n];
            for (int j = 0; j < n; j++) realInput[j] = samples.get(j);

            // Build complex array (re,im,re,im,...) and run complex FFT for safety
            double[] complex = new double[2 * n];
            for (int j = 0; j < n; j++) {
                complex[2*j] = realInput[j];
                complex[2*j + 1] = 0.0;
            }

            try {
                DoubleFFT_1D fft = new DoubleFFT_1D(n);
                fft.complexForward(complex);
            } catch (Throwable t) {
                Log.w(TAG, "FFT failed for event " + i + ": " + t.getMessage() + " - skipping FFT", t);
                result.dominantFreqHz = 0.0;
                result.xp2pPerWindow = new ArrayList<>();
                result.windowCount = 0;
                result.medianXp2p = 0.0;
                result.percentile90Xp2p = 0.0;
                result.maxXp2p = 0.0;
                result.grade = "Normal";
                results.add(result);
                continue;
            }

            // Frequency resolution
            double freqResolution = samplingRateHz / n;

            // Determine search indices for 3..12 Hz, clamped to valid bins
            int startK = Math.max(1, (int)Math.ceil(3.0 / freqResolution));
            int endK = Math.min(n/2 - 1, (int)Math.floor(12.0 / freqResolution));
            if (endK < startK) {
                // Not enough resolution to search 3-12 Hz
                result.dominantFreqHz = 0.0;
            } else {
                double maxAmp = 0.0;
                double domFreq = 0.0;
                for (int k = startK; k <= endK; k++) {
                    int reIdx = 2 * k;
                    int imIdx = 2 * k + 1;
                    if (imIdx >= complex.length) break;
                    double re = complex[reIdx];
                    double im = complex[imIdx];
                    double amp = Math.hypot(re, im);
                    if (amp > maxAmp) {
                        maxAmp = amp;
                        domFreq = k * freqResolution;
                    }
                }
                result.dominantFreqHz = domFreq;
            }

            // Windowed peak-to-peak displacement calculation
            result.xp2pPerWindow = new ArrayList<>();
            int windowSize = (int)Math.round(samplingRateHz); // 1-second windows
            if (windowSize <= 0) windowSize = 1;
            for (int w = 0; w + windowSize <= samples.size(); w += windowSize) {
                List<Double> window = samples.subList(w, w + windowSize);
                double arms = computeRMS(window);
                double xp2p = computeXp2p(arms, result.dominantFreqHz);
                result.xp2pPerWindow.add(xp2p);
            }
            result.windowCount = result.xp2pPerWindow.size();

            // Summarize safely
            if (result.xp2pPerWindow.isEmpty()) {
                result.medianXp2p = 0.0;
                result.percentile90Xp2p = 0.0;
                result.maxXp2p = 0.0;
                result.grade = "Normal";
            } else {
                result.medianXp2p = median(result.xp2pPerWindow);
                result.percentile90Xp2p = percentile(result.xp2pPerWindow, 90);
                result.maxXp2p = Collections.max(result.xp2pPerWindow);
                result.grade = determineGrade(result.medianXp2p);
            }

            results.add(result);
        }

        return results;
    }

    // RMS computation with empty-list guard
    private static double computeRMS(List<Double> values) {
        if (values == null || values.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double v : values) sum += v*v;
        return Math.sqrt(sum / values.size());
    }

    // Xp2p = 2*sqrt(2)*arms / (2*pi*f_dom)^2, arms in same units as accelMag, f_dom in Hz
    // If arms==0 or freq==0 result is 0
    private static double computeXp2p(double arms, double freqHz) {
        if (arms == 0.0 || freqHz <= 0.0) return 0.0;
        double denom = Math.pow(2 * Math.PI * freqHz, 2);
        if (denom == 0.0) return 0.0;
        return 2 * Math.sqrt(2) * arms / denom;
    }

    private static double median(List<Double> vals) {
        if (vals == null || vals.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(vals);
        Collections.sort(sorted);
        int mid = sorted.size()/2;
        if (sorted.size()%2==0) return (sorted.get(mid-1) + sorted.get(mid))/2.0;
        else return sorted.get(mid);
    }

    private static double percentile(List<Double> vals, int pct) {
        if (vals == null || vals.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(vals);
        Collections.sort(sorted);
        // linear interpolation between ranks
        double rank = (pct / 100.0) * (sorted.size() - 1);
        int low = (int)Math.floor(rank);
        int high = (int)Math.ceil(rank);
        if (low == high) return sorted.get(low);
        double frac = rank - low;
        return sorted.get(low) * (1 - frac) + sorted.get(high) * frac;
    }

    private static String determineGrade(double xp2pCm) {
        if (xp2pCm >= 2.0) return "Severe";
        else if (xp2pCm >= 1.0) return "Moderate";
        else if (xp2pCm >= 0.5) return "Mild";
        else return "Normal";
    }
}