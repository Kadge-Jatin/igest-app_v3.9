package com.example.anxiety;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class SensitivityTrackView extends View {

    private static final int DOT_COUNT = 10;

    private final Paint ringBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint dotOddPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotEvenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF ringRect = new RectF();

    // sizes in px
    private float ringHeightPx;
    private float ringRadiusPx;
    private float dotOddRadiusPx;
    private float dotEvenRadiusPx;

    // progress: 0..9 (matches SeekBar progress)
    private int progress = 0;

    public SensitivityTrackView(Context context) {
        super(context);
        init();
    }

    public SensitivityTrackView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SensitivityTrackView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        ringBgPaint.setStyle(Paint.Style.FILL);
        ringBgPaint.setColor(0xFFE6E6E6); // light gray

        ringFillPaint.setStyle(Paint.Style.FILL);
        ringFillPaint.setColor(0xFF2196F3); // blue fill (same as your theme)

        dotOddPaint.setStyle(Paint.Style.FILL);
        dotOddPaint.setColor(0xFFB0B0B0); // light gray

        dotEvenPaint.setStyle(Paint.Style.FILL);
        dotEvenPaint.setColor(0xFF444444); // dark

        ringHeightPx = dp(14);
        ringRadiusPx = ringHeightPx / 2f;

        dotOddRadiusPx = dp(3);  // 6dp diameter
        dotEvenRadiusPx = dp(4); // 8dp diameter
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Call this from SettingsActivity when SeekBar progress changes. */
    public void setProgressIndex(int progress0to9) {
        int safe = progress0to9;
        if (safe < 0) safe = 0;
        if (safe > 9) safe = 9;
        if (this.progress != safe) {
            this.progress = safe;
            invalidate(); // redraw
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) Math.ceil(dp(40));
        int width = MeasureSpec.getSize(widthMeasureSpec);

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int height = (heightMode == MeasureSpec.EXACTLY)
                ? MeasureSpec.getSize(heightMeasureSpec)
                : desiredHeight;

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();

        float sidePadding = dp(16);

        float centerY = h / 2f;
        float top = centerY - ringHeightPx / 2f;
        float bottom = centerY + ringHeightPx / 2f;

        ringRect.set(sidePadding, top, w - sidePadding, bottom);
        float dotEdgePadding = dp(10); // move dot 1 and 10 inside the ring

        // 1) Draw background ring
        canvas.drawRoundRect(ringRect, ringRadiusPx, ringRadiusPx, ringBgPaint);

        // 2) Draw blue fill up to current progress
        // progress 0..9 corresponds to dot 1..10
        // Fill should reach the current dot center.
        float left = ringRect.left + dotEdgePadding;
        float right = ringRect.right - dotEdgePadding;
        float available = right - left;
        float step = available / (DOT_COUNT - 1);

        float fillRight = left + (progress * step);

        RectF fillRect = new RectF(ringRect.left, ringRect.top, fillRight, ringRect.bottom);
        canvas.drawRoundRect(fillRect, ringRadiusPx, ringRadiusPx, ringFillPaint);

        // 3) Draw dots on top
        float dotY = centerY;
        for (int i = 0; i < DOT_COUNT; i++) {
            int level = i + 1; // 1..10
            boolean even = (level % 2 == 0);

            float x = left + (i * step);
            float r = even ? dotEvenRadiusPx : dotOddRadiusPx;
            Paint p = even ? dotEvenPaint : dotOddPaint;

            canvas.drawCircle(x, dotY, r, p);
        }
    }
}