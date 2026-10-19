package com.fanniuzhan.winddown;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

final class Ui {
    static final int INK = 0xFF263C36;
    static final int MUTED = 0xFF65756E;
    static final int ACCENT = 0xFF276555;
    static final int DARK = 0xFF193F37;
    static final int CANVAS = 0xFFF5F5EF;
    static final int SOFT = 0xFFEEF2EC;
    static final int PALE = 0xFFCADBD0;
    private Ui() { }
    static int dp(Context context, float size) { return (int) (size * context.getResources().getDisplayMetrics().density + .5f); }
    static TextView text(Context context, String text, int size, int color) {
        TextView view = new TextView(context);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(context, 3), 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 8); view.setLayoutParams(params);
        return view;
    }
    static TextView title(Context context, String text) {
        TextView view = text(context, text, 18, INK); view.setTypeface(null, Typeface.BOLD); return view;
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
        int padding = dp(context, 20); view.setPadding(padding, padding, padding, padding);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 16); view.setLayoutParams(params); return view;
    }
    static Button button(Context context, String text, boolean primary) {
        Button view = new Button(context);
        view.setText(text); view.setTextSize(15); view.setAllCaps(false);
        view.setTextColor(new ColorStateList(new int[][] { new int[] { -android.R.attr.state_enabled }, new int[0] },
                new int[] { primary ? 0xFFDAE4DC : 0xFF8B9790, primary ? Color.WHITE : ACCENT }));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x23276555),
                background(primary ? ACCENT : SOFT, context, 14), background(Color.WHITE, context, 14)));
        view.setMinHeight(dp(context, 52)); view.setGravity(Gravity.CENTER);
        view.setStateListAnimator(null);
        view.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, 8); view.setLayoutParams(params); return view;
    }

    static ToggleButton weekday(Context context, String label) {
        ToggleButton view = new ToggleButton(context);
        view.setTextOn(label); view.setTextOff(label); view.setTextSize(14);
        view.setMinWidth(0); view.setMinimumWidth(0); view.setPadding(0, 0, 0, 0);
        view.setStateListAnimator(null);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_checked }, background(ACCENT, context, 24));
        states.addState(new int[0], background(SOFT, context, 24));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33276555),
                new InsetDrawable(states, dp(context, 2)), background(Color.WHITE, context, 24)));
        view.setTextColor(new ColorStateList(new int[][] { new int[] { android.R.attr.state_checked }, new int[0] },
                new int[] { Color.WHITE, MUTED }));
        return view;
    }

    static View moon(Context context) {
        View view = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float scale = Math.min(getWidth(), getHeight()) / 64f;
                canvas.save(); canvas.scale(scale, scale);
                paint.setColor(0xFFE4D8AF); canvas.drawCircle(31, 34, 19, paint);
                paint.setColor(DARK); canvas.drawCircle(39, 26, 18, paint);
                paint.setColor(PALE); canvas.drawCircle(49, 45, 1.5f, paint);
                canvas.drawCircle(14, 14, 1.5f, paint); canvas.drawCircle(53, 13, 1, paint);
                canvas.restore();
            }
        };
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }
}
