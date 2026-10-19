package com.fanniuzhan.winddown;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int INK = Color.rgb(24, 47, 56);
    static final int MUTED = Color.rgb(100, 119, 125);
    static final int ACCENT = Color.rgb(34, 108, 96);
    static final int DARK = Color.rgb(25, 63, 59);
    static final int CANVAS = Color.rgb(244, 245, 240);
    private Ui() { }
    static int dp(Context context, float size) { return (int) (size * context.getResources().getDisplayMetrics().density + .5f); }
    static TextView text(Context context, String text, int size, int color) {
        TextView view = new TextView(context);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(context, 3), 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 10); view.setLayoutParams(params);
        return view;
    }
    static TextView title(Context context, String text) {
        TextView view = text(context, text, 21, INK); view.setTypeface(null, Typeface.BOLD); return view;
    }
    static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    static GradientDrawable background(int color, Context context, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius)); return drawable;
    }
    static LinearLayout card(Context context, int color) {
        LinearLayout view = column(context);
        view.setBackground(background(color, context, 22));
        int padding = dp(context, 22); view.setPadding(padding, padding, padding, padding);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 16); view.setLayoutParams(params); return view;
    }
    static Button button(Context context, String text, boolean primary) {
        Button view = new Button(context);
        view.setText(text); view.setTextSize(15); view.setAllCaps(false);
        view.setTextColor(primary ? Color.WHITE : ACCENT);
        view.setBackground(background(primary ? ACCENT : Color.rgb(233, 239, 232), context, 13));
        view.setMinHeight(dp(context, 50)); view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, 8); view.setLayoutParams(params); return view;
    }
}
