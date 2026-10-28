package com.srmsync.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Today's classes drawn as one strip, like a bell schedule:
 * red blocks are classes, the white line is "now".
 */
final class DayStripView extends View {

    private final Ui ui;
    private final List<int[]> blocks = new ArrayList<>();   // {start, end} in minutes
    private int nowMin = -1;
    private int fromMin = 8 * 60;
    private int toMin = 17 * 60;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    DayStripView(Context c, Ui ui) {
        super(c);
        this.ui = ui;
        label.setTypeface(ui.boardSemi);
        label.setTextSize(sp(12));
        label.setColor(ui.heroMuted);
        setContentDescription("Today's classes on a timeline");
    }

    void setDay(List<Timetable.Block> today, int nowMinutes) {
        blocks.clear();
        int first = 24 * 60;
        int last = 0;
        for (Timetable.Block b : today) {
            blocks.add(new int[] { b.start, b.end });
            first = Math.min(first, b.start);
            last = Math.max(last, b.end);
        }
        // Whole hours around the classes, at least 8:00-17:00.
        fromMin = Math.min(8 * 60, (first / 60) * 60);
        toMin = Math.max(17 * 60, ((last + 59) / 60) * 60);
        nowMin = nowMinutes;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(52));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float top = dp(6);
        float h = dp(22);
        float radius = dp(6);

        fill.setColor(ui.heroTrack);
        r.set(0, top, w, top + h);
        canvas.drawRoundRect(r, radius, radius, fill);

        for (int[] b : blocks) {
            boolean past = nowMin >= b[1];
            fill.setColor(past ? Color.argb(110, 227, 38, 43) : Ui.RED);
            r.set(x(b[0], w) + dp(1), top, x(b[1], w) - dp(1), top + h);
            canvas.drawRoundRect(r, radius, radius, fill);
        }

        if (nowMin >= fromMin && nowMin <= toMin) {
            float nx = x(nowMin, w);
            fill.setColor(ui.heroText);
            r.set(nx - dp(1.5f), top - dp(5), nx + dp(1.5f), top + h + dp(5));
            canvas.drawRoundRect(r, dp(1.5f), dp(1.5f), fill);
        }

        // Hour labels: start, middle, end.
        float baseline = top + h + dp(20);
        label.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(hour(fromMin), 0, baseline, label);
        label.setTextAlign(Paint.Align.CENTER);
        int mid = ((fromMin + toMin) / 2 / 60) * 60;
        canvas.drawText(hour(mid), x(mid, w), baseline, label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(hour(toMin), w, baseline, label);
    }

    private float x(int minutes, float width) {
        return (minutes - fromMin) / (float) (toMin - fromMin) * width;
    }

    private static String hour(int minutes) {
        int h = minutes / 60;
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d %s", h12, h < 12 ? "am" : "pm");
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
