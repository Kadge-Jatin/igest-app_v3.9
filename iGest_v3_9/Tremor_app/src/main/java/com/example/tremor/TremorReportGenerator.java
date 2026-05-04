package com.example.tremor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.ScatterChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.ScatterData;
import com.github.mikephil.charting.data.ScatterDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.utils.ViewPortHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TremorReportGenerator – generates a multi-page A4 PDF report for a date range.
 * Call {@link #generate} from the UI thread; all work runs on a background thread
 * and the callback is delivered back on the main thread.
 */
public class TremorReportGenerator {

    private static final String TAG = "TremorReportGenerator";

    // ── Public callback interface ──────────────────────────────────────────────

    public interface ReportCallback {
        void onSuccess(File pdfFile);
        void onError(String errorMessage);
    }

    // ── Inner data classes ─────────────────────────────────────────────────────

    public static class TremorEvent {
        public int eventNum;
        public String startTime;   // HH:mm:ss
        public String endTime;
        public double dominantFreqHz;
        public int windowCount;
        public double medianXp2pCm;
        public double percentile90Xp2pCm;
        public double maxXp2pCm;
        public String grade;       // Normal / Mild / Moderate / Severe
    }

    public static class DayStats {
        public String date;
        public int totalEvents;
        public double meanDominantFreqHz;
        public double avgMedianDisplacementCm;
        public int countParkinsonian;    // 3–5 Hz inclusive
        public double percentParkinsonian;
        public int countEssential;       // 6–12 Hz inclusive
        public double percentEssential;
        public int countNormal, countMild, countModerate, countSevere;
    }

    // ── PDF layout constants ───────────────────────────────────────────────────

    private static final int PAGE_W  = 794;
    private static final int PAGE_H  = 1123;
    private static final int ML      = 40;   // left margin
    private static final int MR      = 40;   // right margin
    private static final int MT      = 40;   // top margin
    private static final int MB      = 60;   // bottom margin
    private static final int CONTENT_W = PAGE_W - ML - MR;

    // Off-screen chart canvas size
    private static final int CHART_W = 900;
    private static final int CHART_H = 450;

    // Histogram bin edges: centers at 3, 4, … 12 Hz; edges at 2.5, 3.5, … 12.5
    private static final float HIST_BIN_OFFSET = 2.5f;  // first edge (= center 3 – 0.5)
    private static final float HIST_X_MIN = 2.3f;       // chart x-axis minimum
    private static final float HIST_X_MAX = 12.7f;      // chart x-axis maximum
    private static final float HIST_DIVIDER_HZ = 5.5f;  // Parkinsonian / Essential divider

    // Display version embedded in the PDF cover page
    private static final String REPORT_APP_VERSION = "iGest v3.9";

    // ── Entry point ────────────────────────────────────────────────────────────

    /**
     * Generate a PDF report asynchronously.
     *
     * @param context        Any context (application context used internally).
     * @param username       Display name of the current user.
     * @param fromDate       Start date "yyyy-MM-dd".
     * @param toDate         End date "yyyy-MM-dd" (may equal fromDate).
     * @param perUserDir     Per-user data root, contains "Analysed/" sub-folder.
     * @param callback       Delivered on the main thread.
     */
    public static void generate(Context context, String username,
                                String fromDate, String toDate,
                                File perUserDir, ReportCallback callback) {
        Context appCtx = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File result = doGenerate(appCtx, username, fromDate, toDate, perUserDir);
                main.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "PDF generation failed";
                main.post(() -> callback.onError(msg));
            }
        }).start();
    }

    // ── Core generation ────────────────────────────────────────────────────────

    private static File doGenerate(Context ctx, String username,
                                   String fromDate, String toDate,
                                   File perUserDir) throws Exception {
        // 1. Load data
        Map<String, List<TremorEvent>> dataByDate =
                loadDataForDateRange(fromDate, toDate, perUserDir);

        // 2. Prepare output directory and filename
        // Expected layout: <externalFilesDir>/iGest: Tremor Detection/Data/<username>/
        //   perUserDir.getParentFile()           → .../Data/
        //   perUserDir.getParentFile().getParentFile() → .../iGest: Tremor Detection/
        File dataDir   = perUserDir.getParentFile();                        // .../Data/
        File igestRoot = dataDir != null ? dataDir.getParentFile() : null; // .../iGest: Tremor Detection/
        if (igestRoot == null) igestRoot = perUserDir; // fallback (should not happen)
        File reportsDir = new File(igestRoot, "Reports");
        reportsDir.mkdirs();

        String period = fromDate.equals(toDate)
                ? fromDate
                : fromDate + "_to_" + toDate;
        String filename = "iGest_Report_" + safeFilename(username) + "_" + period + ".pdf";
        File outputFile = new File(reportsDir, filename);

        // 3. Build PDF
        PdfDocument doc = new PdfDocument();
        PdfWriter writer = new PdfWriter(doc);

        // Cover page
        writer.startNewPage();
        drawCoverPage(writer, dataByDate, username, fromDate, toDate);

        // Per-date sections
        List<String> sortedDates = new ArrayList<>(dataByDate.keySet());
        Collections.sort(sortedDates);
        for (String date : sortedDates) {
            List<TremorEvent> events = dataByDate.get(date);
            if (events == null || events.isEmpty()) continue;
            DayStats stats = calculateStats(date, events);
            writer.startNewPage();
            drawDateSection(ctx, writer, date, events, stats, username);
        }

        // Finish and write
        writer.finishLastPage();
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            doc.writeTo(fos);
        }
        doc.close();
        return outputFile;
    }

    // ── Data loading ───────────────────────────────────────────────────────────

    private static Map<String, List<TremorEvent>> loadDataForDateRange(
            String fromDate, String toDate, File perUserDir) throws Exception {

        Map<String, List<TremorEvent>> result = new LinkedHashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Date from = sdf.parse(fromDate);
        Date to   = sdf.parse(toDate);
        if (from == null || to == null)
            throw new Exception("Invalid date format: from='" + fromDate + "', to='" + toDate + "'");

        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        while (!cal.getTime().after(to)) {
            String dateStr = sdf.format(cal.getTime());
            File dateDir = new File(perUserDir, "Analysed/" + dateStr);
            if (dateDir.isDirectory()) {
                List<TremorEvent> events = loadDayData(dateDir);
                if (!events.isEmpty()) {
                    result.put(dateStr, events);
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return result;
    }

    private static List<TremorEvent> loadDayData(File dateDir) {
        List<TremorEvent> events = new ArrayList<>();
        File[] files = dateDir.listFiles((d, n) -> n.endsWith("_analysed.csv"));
        if (files == null || files.length == 0) return events;

        // Sort by session number (session_<N>_analysed.csv)
        Arrays.sort(files, (a, b) ->
                Integer.compare(parseSessionNum(a.getName()), parseSessionNum(b.getName())));

        for (File f : files) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    if (header) { header = false; continue; }
                    String[] p = line.split(",");
                    if (p.length < 9) continue;
                    try {
                        TremorEvent e = new TremorEvent();
                        e.startTime        = p[1].trim();
                        e.endTime          = p[2].trim();
                        e.dominantFreqHz   = Double.parseDouble(p[3].trim());
                        e.windowCount      = Integer.parseInt(p[4].trim());
                        e.medianXp2pCm     = Double.parseDouble(p[5].trim());
                        e.percentile90Xp2pCm = Double.parseDouble(p[6].trim());
                        e.maxXp2pCm        = Double.parseDouble(p[7].trim());
                        e.grade            = p[8].trim();
                        events.add(e);
                    } catch (Exception ex) {
                        Log.w(TAG, "Skipping malformed CSV row in " + f.getName() + ": " + line, ex);
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG, "Could not read CSV file: " + f.getAbsolutePath(), ex);
            }
        }
        // Re-number events globally across all sessions for this date
        for (int i = 0; i < events.size(); i++) events.get(i).eventNum = i + 1;
        return events;
    }

    private static int parseSessionNum(String filename) {
        // session_<N>_analysed.csv
        try {
            String[] parts = filename.split("_");
            return Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    // ── Stats calculation ─────────────────────────────────────────────────────

    static DayStats calculateStats(String date, List<TremorEvent> events) {
        DayStats s = new DayStats();
        s.date = date;
        s.totalEvents = events.size();
        if (s.totalEvents == 0) return s;

        double sumFreq = 0, sumDisp = 0;
        for (TremorEvent e : events) {
            sumFreq += e.dominantFreqHz;
            sumDisp += e.medianXp2pCm;
            String g = e.grade.trim().toLowerCase();
            switch (g) {
                case "normal":   s.countNormal++;   break;
                case "mild":     s.countMild++;     break;
                case "moderate": s.countModerate++; break;
                case "severe":   s.countSevere++;   break;
            }
            if (e.dominantFreqHz >= 3.0 && e.dominantFreqHz <= 5.0) s.countParkinsonian++;
            if (e.dominantFreqHz >= 6.0 && e.dominantFreqHz <= 12.0) s.countEssential++;
        }
        // Round to spec: mean freq → 2dp, percentages → 1dp
        s.meanDominantFreqHz      = Math.round(sumFreq / s.totalEvents * 100.0) / 100.0;
        s.avgMedianDisplacementCm = Math.round(sumDisp / s.totalEvents * 100.0) / 100.0;
        s.percentParkinsonian     = Math.round((double) s.countParkinsonian / s.totalEvents * 1000.0) / 10.0;
        s.percentEssential        = Math.round((double) s.countEssential    / s.totalEvents * 1000.0) / 10.0;
        return s;
    }

    // ── Chart rendering (off-screen) ──────────────────────────────────────────

    /** Grade Distribution BarChart (replicates Python Script 3). */
    private static Bitmap renderGradeDistributionChart(Context ctx, List<TremorEvent> events) {
        int[] counts = new int[4]; // 0=Normal,1=Mild,2=Moderate,3=Severe
        for (TremorEvent e : events) {
            int g = gradeIndex(e.grade);
            if (g >= 0) counts[g]++;
        }

        List<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < 4; i++) entries.add(new BarEntry(i, counts[i]));

        BarDataSet ds = new BarDataSet(entries, "");
        ds.setColors(
                Color.parseColor("#4CAF50"),
                Color.parseColor("#FFC107"),
                Color.parseColor("#FF7043"),
                Color.parseColor("#B71C1C"));
        ds.setValueTextSize(11f);
        ds.setValueTypeface(Typeface.DEFAULT_BOLD);
        ds.setDrawValues(true);
        ds.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        });

        BarData barData = new BarData(ds);
        barData.setBarWidth(0.5f);

        BarChart chart = new BarChart(ctx);
        chart.setData(barData);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setExtraBottomOffset(20f);

        String[] gradeNames = {"Normal", "Mild", "Moderate", "Severe"};
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(gradeNames));
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setDrawGridLines(false);
        chart.getXAxis().setGranularity(1f);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(3.5f);

        int maxC = 0;
        for (int c : counts) maxC = Math.max(maxC, c);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setAxisMaximum(maxC + 2);
        chart.getAxisLeft().setGranularity(1f);
        chart.getAxisLeft().setGranularityEnabled(true);
        chart.getAxisRight().setEnabled(false);

        return chartToBitmap(chart, Color.WHITE);
    }

    /** Dominant Frequency Histogram (replicates Python Script 2). */
    private static Bitmap renderFrequencyHistogramChart(Context ctx, List<TremorEvent> events) {
        // Bin centers 3..12; bin edges 2.5..12.5 in steps of 1.0
        // Bin index = floor(freq - HIST_BIN_OFFSET) where HIST_BIN_OFFSET = 2.5
        int[] counts = new int[10];
        for (TremorEvent e : events) {
            int bin = (int) Math.floor(e.dominantFreqHz - HIST_BIN_OFFSET);
            if (bin >= 0 && bin < 10) counts[bin]++;
        }

        List<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) entries.add(new BarEntry(i + 3, counts[i]));

        BarDataSet ds = new BarDataSet(entries, "");
        ds.setColor(Color.parseColor("#1565C0"));
        ds.setBarBorderColor(Color.BLACK);
        ds.setBarBorderWidth(0.5f);
        ds.setDrawValues(false);

        BarData barData = new BarData(ds);
        barData.setBarWidth(0.8f);

        BarChart chart = new BarChart(ctx);
        chart.setData(barData);
        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        // Do NOT set a white background – we draw zones behind it

        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setAxisMinimum(HIST_X_MIN);
        chart.getXAxis().setAxisMaximum(HIST_X_MAX);
        chart.getXAxis().setGranularity(1f);
        chart.getXAxis().setGranularityEnabled(true);
        chart.getXAxis().setLabelCount(10, true);
        // Vertical dotted grid lines, alpha ≈ 0.2
        chart.getXAxis().enableGridDashedLine(5f, 5f, 0f);
        chart.getXAxis().setGridColor(Color.argb(51, 0, 0, 0));

        // Dashed vertical divider at HIST_DIVIDER_HZ (5.5 Hz)
        LimitLine divider = new LimitLine(HIST_DIVIDER_HZ, "");
        divider.setLineColor(Color.GRAY);
        divider.setLineWidth(1.0f);
        divider.enableDashedLine(10f, 5f, 0f);
        chart.getXAxis().addLimitLine(divider);

        int maxC = 0;
        for (int c : counts) maxC = Math.max(maxC, c);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setAxisMaximum(maxC + 2);
        chart.getAxisLeft().setGranularity(1f);
        chart.getAxisLeft().setGranularityEnabled(true);
        chart.getAxisRight().setEnabled(false);

        // Custom legend: Parkinsonian (orange) + Essential (green)
        LegendEntry parkEntry = new LegendEntry();
        parkEntry.label = "Parkinsonian (3\u20135 Hz)";
        parkEntry.formColor = Color.parseColor("#FF6600");

        LegendEntry essEntry = new LegendEntry();
        essEntry.label = "Essential Tremor (6\u201312 Hz)";
        essEntry.formColor = Color.parseColor("#388E3C");

        chart.getLegend().setCustom(new LegendEntry[]{parkEntry, essEntry});
        chart.getLegend().setEnabled(true);

        // Measure and layout the chart
        chart.measure(
                View.MeasureSpec.makeMeasureSpec(CHART_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(CHART_H, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, CHART_W, CHART_H);

        // Pass 1: draw chart to a transparent bitmap so onDraw() populates the viewport.
        Bitmap chartBmp = Bitmap.createBitmap(CHART_W, CHART_H, Bitmap.Config.ARGB_8888);
        chart.draw(new Canvas(chartBmp));

        // Now the ViewPortHandler's content rect is populated.
        ViewPortHandler vph = chart.getViewPortHandler();
        float cLeft   = vph.contentLeft();
        float cRight  = vph.contentRight();
        float cTop    = vph.contentTop();
        float cBottom = vph.contentBottom();
        float xRange  = HIST_X_MAX - HIST_X_MIN;
        float ppu     = (cRight > cLeft) ? (cRight - cLeft) / xRange : 1f;

        float x3   = cLeft + (3.0f         - HIST_X_MIN) * ppu;
        float x5_5 = cLeft + (HIST_DIVIDER_HZ - HIST_X_MIN) * ppu;
        float x12  = cLeft + (12.0f        - HIST_X_MIN) * ppu;

        // Pass 2: compose final bitmap → white → zones → chart (chart has transparent bg)
        Bitmap bmp = Bitmap.createBitmap(CHART_W, CHART_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        // Parkinsonian zone: orange, alpha ≈ 10 % (26/255)
        Paint parkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        parkPaint.setStyle(Paint.Style.FILL);
        parkPaint.setColor(Color.argb(26, 255, 152, 0));
        canvas.drawRect(x3, cTop, x5_5, cBottom, parkPaint);

        // Essential zone: green, alpha ≈ 10 %
        Paint essPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        essPaint.setStyle(Paint.Style.FILL);
        essPaint.setColor(Color.argb(26, 56, 142, 60));
        canvas.drawRect(x5_5, cTop, x12, cBottom, essPaint);

        // Composite the chart (transparent background) on top; zones show through
        canvas.drawBitmap(chartBmp, 0, 0, null);
        chartBmp.recycle();

        return bmp;
    }

    /** Event Timeline ScatterChart (replicates Python Script 4). */
    private static Bitmap renderEventTimelineChart(Context ctx, List<TremorEvent> events) {
        final String[] gradeNames  = {"Normal", "Mild", "Moderate", "Severe"};
        final int[]    gradeColors = {
                Color.parseColor("#4CAF50"),
                Color.parseColor("#FFC107"),
                Color.parseColor("#FF7043"),
                Color.parseColor("#B71C1C")
        };

        ScatterData scatterData = new ScatterData();
        for (int g = 0; g < 4; g++) {
            List<Entry> pts = new ArrayList<>();
            for (TremorEvent e : events) {
                if (gradeIndex(e.grade) == g) {
                    pts.add(new Entry(timeToMinutes(e.startTime), g));
                }
            }
            ScatterDataSet ds = new ScatterDataSet(pts, gradeNames[g]);
            ds.setColor(gradeColors[g]);
            ds.setScatterShape(ScatterChart.ScatterShape.CIRCLE);
            ds.setScatterShapeSize(10f);
            ds.setDrawValues(false);
            scatterData.addDataSet(ds);
        }

        ScatterChart chart = new ScatterChart(ctx);
        chart.setData(scatterData);
        chart.getDescription().setEnabled(false);

        // X-axis: time in minutes, formatted as HH:MM
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setGranularity(10f);
        chart.getXAxis().setGranularityEnabled(true);
        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return minutesToTime(value);
            }
        });
        chart.getXAxis().setLabelRotationAngle(90f);
        chart.getXAxis().enableGridDashedLine(10f, 5f, 0f);
        chart.getXAxis().setGridColor(Color.argb(77, 0, 0, 0)); // alpha ≈ 0.3

        // Y-axis: grade names
        chart.getAxisLeft().setAxisMinimum(-0.6f);
        chart.getAxisLeft().setAxisMaximum(3.6f);
        chart.getAxisLeft().setGranularity(1f);
        chart.getAxisLeft().setGranularityEnabled(true);
        chart.getAxisLeft().setLabelCount(4, true);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int v = Math.round(value);
                return (v >= 0 && v < 4) ? gradeNames[v] : "";
            }
        });
        chart.getAxisLeft().enableGridDashedLine(5f, 5f, 0f);
        chart.getAxisLeft().setGridColor(Color.argb(51, 0, 0, 0)); // alpha ≈ 0.2
        chart.getAxisRight().setEnabled(false);

        // Always show all 4 grade legend entries
        LegendEntry[] legendEntries = new LegendEntry[4];
        for (int g = 0; g < 4; g++) {
            LegendEntry le = new LegendEntry();
            le.label     = gradeNames[g];
            le.formColor = gradeColors[g];
            legendEntries[g] = le;
        }
        chart.getLegend().setCustom(legendEntries);
        chart.getLegend().setEnabled(true);

        return chartToBitmap(chart, Color.WHITE);
    }

    /** Measure, layout, and draw a chart to a Bitmap of size CHART_W × CHART_H. */
    private static Bitmap chartToBitmap(View chart, int bgColor) {
        chart.measure(
                View.MeasureSpec.makeMeasureSpec(CHART_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(CHART_H, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, CHART_W, CHART_H);
        Bitmap bmp = Bitmap.createBitmap(CHART_W, CHART_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(bgColor);
        chart.draw(canvas);
        return bmp;
    }

    // ── PDF writing helpers ───────────────────────────────────────────────────

    /** Tracks the PDF document, current page/canvas, and the Y cursor. */
    private static class PdfWriter {
        final PdfDocument doc;
        PdfDocument.Page currentPage;
        Canvas canvas;
        float y;
        int pageNum;

        PdfWriter(PdfDocument doc) {
            this.doc = doc;
        }

        void startNewPage() {
            if (currentPage != null) doc.finishPage(currentPage);
            pageNum++;
            PdfDocument.PageInfo info =
                    new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create();
            currentPage = doc.startPage(info);
            canvas = currentPage.getCanvas();
            y = MT;
        }

        /** Ensure at least {@code neededPx} pixels remain on this page. */
        void ensureSpace(float neededPx) {
            if (y + neededPx > PAGE_H - MB) startNewPage();
        }

        void finishLastPage() {
            if (currentPage != null) {
                doc.finishPage(currentPage);
                currentPage = null;
            }
        }
    }

    // ── Cover page ─────────────────────────────────────────────────────────────

    private static void drawCoverPage(PdfWriter w,
                                      Map<String, List<TremorEvent>> dataByDate,
                                      String username,
                                      String fromDate, String toDate) {
        Canvas cv = w.canvas;

        // 1. "iGest" – size 28, bold, #1565C0, centered
        Paint pAppTitle = makePaint(true, 28f, Color.parseColor("#1565C0"), Paint.Align.CENTER);
        w.y += 20;
        w.y = drawLine(cv, "iGest", PAGE_W / 2f, w.y, pAppTitle);
        w.y += 6;

        // 2. "Tremor Analysis Report" – size 18, bold, black, centered
        Paint pSubTitle = makePaint(true, 18f, Color.BLACK, Paint.Align.CENTER);
        w.y = drawLine(cv, "Tremor Analysis Report", PAGE_W / 2f, w.y, pSubTitle);
        w.y += 14;

        // 3. Horizontal rule
        drawHRule(cv, w);
        w.y += 10;

        // 4. Info lines
        Paint pBody = makePaint(false, 12f, Color.BLACK, Paint.Align.LEFT);
        String genTime = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(new Date());
        String periodStr = fromDate.equals(toDate)
                ? formatDate(fromDate)
                : formatDate(fromDate) + " \u2013 " + formatDate(toDate);
        String[] infoLines = {
                "Patient / User   :  " + username,
                "Report Generated :  " + genTime,
                "Report Period    :  " + periodStr,
                "App Version      :  " + REPORT_APP_VERSION
        };
        for (String line : infoLines) {
            w.y = drawLine(cv, line, ML, w.y, pBody);
            w.y += 3;
        }
        w.y += 8;

        // 5. Horizontal rule
        drawHRule(cv, w);
        w.y += 12;

        // 6. "Report Contents:"
        Paint pBold = makePaint(true, 12f, Color.BLACK, Paint.Align.LEFT);
        w.y = drawLine(cv, "Report Contents:", ML, w.y, pBold);
        w.y += 6;

        // 7. Bulleted list of dates with data
        List<String> sortedDates = new ArrayList<>(dataByDate.keySet());
        Collections.sort(sortedDates);
        if (sortedDates.isEmpty()) {
            w.y = drawLine(cv, "  \u2022  No data found for the selected period.", ML + 8, w.y, pBody);
        } else {
            for (String d : sortedDates) {
                w.y = drawLine(cv, "  \u2022  " + formatDate(d), ML + 8, w.y, pBody);
                w.y += 2;
            }
        }
    }

    // ── Main-thread chart rendering helper ────────────────────────────────────

    /**
     * Runs {@code task} on the main (UI) thread and returns the resulting {@link Bitmap}.
     * MPAndroidChart views create a {@code Handler} internally, so they must be
     * instantiated and drawn on a thread that has a {@code Looper}.
     */
    private static Bitmap renderOnMainThread(Callable<Bitmap> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.call();
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new Exception("Chart rendering timed out on main thread");
        }
        if (error.get() != null) throw error.get();
        return result.get();
    }

    // ── Per-date section ───────────────────────────────────────────────────────

    private static void drawDateSection(Context ctx, PdfWriter w,
                                        String date, List<TremorEvent> events,
                                        DayStats stats, String username) throws Exception {
        Canvas cv = w.canvas;

        // ① Header strip (page is fresh, cv is valid)
        drawDateHeader(cv, w, "Date : " + formatDate(date));

        // ② Summary statistics
        drawSectionLabel(w, "\u2460 Summary Statistics");
        drawSummaryStats(w, stats);

        // ③ Grade Distribution chart
        drawSectionLabel(w, "\u2461 Tremor Grade Distribution");
        Bitmap gradeBmp = renderOnMainThread(() -> renderGradeDistributionChart(ctx, events));
        drawChartBitmap(w, gradeBmp);

        // ④ Frequency Histogram
        drawSectionLabel(w, "\u2462 Dominant Frequency Distribution");
        Bitmap freqBmp = renderOnMainThread(() -> renderFrequencyHistogramChart(ctx, events));
        drawChartBitmap(w, freqBmp);

        // ⑤ Event Timeline
        drawSectionLabel(w, "\u2463 Event Timeline");
        Bitmap timelineBmp = renderOnMainThread(() -> renderEventTimelineChart(ctx, events));
        drawChartBitmap(w, timelineBmp);

        // ⑥ Event Details Table
        drawSectionLabel(w, "\u2464 Event Details");
        drawEventDetailsTable(w, events);

        // ⑦ Observations
        drawSectionLabel(w, "\u2465 Observations");
        drawObservations(w, stats, date, username);
    }

    // ── Section drawing helpers ────────────────────────────────────────────────

    private static void drawDateHeader(Canvas cv, PdfWriter w, String title) {
        Paint fill = makeFill(Color.parseColor("#1565C0"));
        cv.drawRect(ML, w.y, PAGE_W - MR, w.y + 36, fill);

        Paint tp = makePaint(true, 16f, Color.WHITE, Paint.Align.CENTER);
        float baseline = w.y + 18 - (tp.ascent() + tp.descent()) / 2f;
        cv.drawText(title, PAGE_W / 2f, baseline, tp);
        w.y += 36 + 16;
    }

    private static void drawSectionLabel(PdfWriter w, String label) {
        w.ensureSpace(30);
        Paint p = makePaint(true, 12f, Color.BLACK, Paint.Align.LEFT);
        p.setUnderlineText(true);
        w.y = drawLine(w.canvas, label, ML, w.y, p);
        w.y += 6;
    }

    private static void drawSummaryStats(PdfWriter w, DayStats stats) {
        Paint body = makePaint(false, 12f, Color.BLACK, Paint.Align.LEFT);
        float lh = lineH(body) + 4;
        float pad = 10f;
        float boxH = pad * 2 + lh * 5 + 4;

        w.ensureSpace(boxH + 16);
        Canvas cv = w.canvas;

        float top = w.y;
        cv.drawRect(ML, top, PAGE_W - MR, top + boxH, makeFill(Color.parseColor("#FAFAFA")));
        cv.drawRect(ML, top, PAGE_W - MR, top + boxH, makeStroke(1f, Color.parseColor("#BDBDBD")));

        float ty = top + pad;
        String[] rows = {
                String.format("Total Tremor Events              :  %d", stats.totalEvents),
                String.format("Mean Dominant Frequency          :  %.2f Hz", stats.meanDominantFreqHz),
                String.format("Avg. Median Displacement         :  %.2f cm", stats.avgMedianDisplacementCm),
                String.format("Events in 3\u20135 Hz (Parkinsonian)  :  %d  (%.1f%%)",
                        stats.countParkinsonian, stats.percentParkinsonian),
                String.format("Events in 6\u201312 Hz (Essential)    :  %d  (%.1f%%)",
                        stats.countEssential, stats.percentEssential)
        };
        for (String row : rows) {
            ty = drawLine(cv, row, ML + pad, ty, body);
            ty += 4;
        }
        w.y = top + boxH + 16;
    }

    private static void drawChartBitmap(PdfWriter w, Bitmap bmp) {
        float scale  = (float) CONTENT_W / bmp.getWidth();
        float scaledH = bmp.getHeight() * scale;

        w.ensureSpace(scaledH + 16);

        RectF dest = new RectF(ML, w.y, ML + CONTENT_W, w.y + scaledH);
        w.canvas.drawBitmap(bmp, null, dest, null);
        bmp.recycle();
        w.y += scaledH + 16;
    }

    private static void drawEventDetailsTable(PdfWriter w, List<TremorEvent> events) {
        // Column widths (spec: 30,80,80,70,110,70 = 440), scaled to CONTENT_W
        float scale = (float) CONTENT_W / 440f;
        final float[] cw = {30*scale, 80*scale, 80*scale, 70*scale, 110*scale, 70*scale};
        final String[] headers = {"#", "Start Time", "End Time", "Freq (Hz)", "Median Disp (cm)", "Grade"};
        final float rowH = 18f;
        final float startX = ML;

        Paint headerBg  = makeFill(Color.parseColor("#37474F"));
        Paint headerTxt = makePaint(true, 11f, Color.WHITE, Paint.Align.LEFT);
        Paint rowTxt    = makePaint(false, 10f, Color.BLACK, Paint.Align.LEFT);
        Paint rowAltBg  = makeFill(Color.parseColor("#F5F5F5"));

        final int[] gradeColors = {
                Color.parseColor("#4CAF50"),
                Color.parseColor("#FFC107"),
                Color.parseColor("#FF7043"),
                Color.parseColor("#B71C1C")
        };

        // Helper: draw header row (inlined to avoid java.util.function.Consumer API-24 requirement)
        w.ensureSpace(rowH * 2 + 16);
        {
            Canvas hcv = w.canvas;
            hcv.drawRect(startX, w.y, startX + CONTENT_W, w.y + rowH, headerBg);
            float tx = startX + 4;
            for (int c = 0; c < headers.length; c++) {
                float by = w.y + rowH / 2f - (headerTxt.ascent() + headerTxt.descent()) / 2f;
                hcv.drawText(headers[c], tx, by, headerTxt);
                tx += cw[c];
            }
            w.y += rowH;
        }

        for (int r = 0; r < events.size(); r++) {
            if (w.y + rowH > PAGE_H - MB) {
                w.startNewPage();
                // Re-draw header on new page
                Canvas hcv = w.canvas;
                hcv.drawRect(startX, w.y, startX + CONTENT_W, w.y + rowH, headerBg);
                float tx = startX + 4;
                for (int c = 0; c < headers.length; c++) {
                    float by = w.y + rowH / 2f - (headerTxt.ascent() + headerTxt.descent()) / 2f;
                    hcv.drawText(headers[c], tx, by, headerTxt);
                    tx += cw[c];
                }
                w.y += rowH;
            }
            Canvas cv = w.canvas;

            if (r % 2 == 1) {
                cv.drawRect(startX, w.y, startX + CONTENT_W, w.y + rowH, rowAltBg);
            }

            TremorEvent e = events.get(r);
            String[] cells = {
                    String.valueOf(e.eventNum),
                    e.startTime,
                    e.endTime,
                    String.format("%.2f", e.dominantFreqHz),
                    String.format("%.3f", e.medianXp2pCm),
                    e.grade
            };
            float tx = startX + 4;
            for (int c = 0; c < cells.length; c++) {
                float by = w.y + rowH / 2f - (rowTxt.ascent() + rowTxt.descent()) / 2f;
                if (c == 5) {
                    Paint gp = makePaint(false, 10f, gradeColors[Math.max(0, gradeIndex(e.grade))],
                            Paint.Align.LEFT);
                    cv.drawText(cells[c], tx, by, gp);
                } else {
                    cv.drawText(cells[c], tx, by, rowTxt);
                }
                tx += cw[c];
            }
            w.y += rowH;
        }
        w.y += 16;
    }

    private static void drawObservations(PdfWriter w, DayStats stats, String date, String username) {
        String obs = String.format(Locale.US,
                "A total of %d tremor events were recorded on %s for %s. " +
                "The mean dominant tremor frequency was %.2f Hz. " +
                "%d event%s (%.1f%%) fell in the Parkinsonian frequency range (3\u20135 Hz), " +
                "and %d event%s (%.1f%%) fell in the Essential Tremor range (6\u201312 Hz). " +
                "Grade breakdown \u2013 Normal: %d, Mild: %d, Moderate: %d, Severe: %d.",
                stats.totalEvents, formatDate(date), username,
                stats.meanDominantFreqHz,
                stats.countParkinsonian, stats.countParkinsonian == 1 ? "" : "s", stats.percentParkinsonian,
                stats.countEssential,    stats.countEssential    == 1 ? "" : "s", stats.percentEssential,
                stats.countNormal, stats.countMild, stats.countModerate, stats.countSevere);

        Paint body = makePaint(false, 12f, Color.BLACK, Paint.Align.LEFT);
        List<String> lines = wrapText(obs, body, CONTENT_W - 10);
        for (String line : lines) {
            w.ensureSpace(lineH(body) + 4);
            w.y = drawLine(w.canvas, line, ML, w.y, body);
            w.y += 3;
        }
        w.y += 8;
    }

    // ── PDF primitive helpers ──────────────────────────────────────────────────

    /** Draw a horizontal rule and advance w.y. */
    private static void drawHRule(Canvas cv, PdfWriter w) {
        cv.drawLine(ML, w.y, PAGE_W - MR, w.y, makeStroke(1f, Color.parseColor("#BDBDBD")));
        w.y += 10;
    }

    /**
     * Draw text with {@code y} as the top of the character cell.
     * Returns the new top (= y + line height).
     */
    private static float drawLine(Canvas cv, String text, float x, float y, Paint p) {
        cv.drawText(text, x, y - p.ascent(), p);
        return y + lineH(p);
    }

    private static float lineH(Paint p) {
        return p.descent() - p.ascent();
    }

    // ── Paint factories ────────────────────────────────────────────────────────

    private static Paint makePaint(boolean bold, float size, int color, Paint.Align align) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(align);
        return p;
    }

    private static Paint makeFill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    private static Paint makeStroke(float width, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(color);
        return p;
    }

    // ── Utility helpers ────────────────────────────────────────────────────────

    /** Returns 0=Normal, 1=Mild, 2=Moderate, 3=Severe; -1 if unknown. */
    private static int gradeIndex(String grade) {
        if (grade == null) return -1;
        switch (grade.trim().toLowerCase()) {
            case "normal":   return 0;
            case "mild":     return 1;
            case "moderate": return 2;
            case "severe":   return 3;
            default:         return -1;
        }
    }

    /** "HH:mm:ss" → minutes since midnight (float). */
    private static float timeToMinutes(String time) {
        if (time == null || time.isEmpty()) return 0f;
        String[] p = time.split(":");
        try {
            float h = Float.parseFloat(p[0]);
            float m = p.length > 1 ? Float.parseFloat(p[1]) : 0f;
            float s = p.length > 2 ? Float.parseFloat(p[2]) : 0f;
            return h * 60f + m + s / 60f;
        } catch (Exception e) {
            return 0f;
        }
    }

    /** Minutes since midnight → "HH:MM". */
    private static String minutesToTime(float minutes) {
        int h = (int) (minutes / 60);
        int m = (int) (minutes % 60);
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    /** "yyyy-MM-dd" → "dd MMM yyyy". */
    private static String formatDate(String ymd) {
        try {
            SimpleDateFormat in  = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("dd MMM yyyy", Locale.US);
            Date d = in.parse(ymd);
            return d != null ? out.format(d) : ymd;
        } catch (Exception e) {
            return ymd;
        }
    }

    /** Strip characters that are unsafe in file names. */
    private static String safeFilename(String name) {
        if (name == null || name.isEmpty()) return "User";
        return name.replaceAll("[/\\\\:;*?\"<>|\\s]+", "_").trim();
    }

    /** Word-wrap {@code text} to fit within {@code maxWidth} canvas units. */
    private static List<String> wrapText(String text, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (paint.measureText(test) > maxWidth && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(test);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }
}
