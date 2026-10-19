package com.fanniuzhan.winddown;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
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
    static final int INK = 0xFF303D33;
    static final int MUTED = 0xFF74766A;
    static final int ACCENT = 0xFF50664E;
    static final int DARK = 0xFF293F35;
    static final int CANVAS = 0xFFF3F0E7;
    static final int PAPER = 0xFFFFFDF7;
    static final int SOFT = 0xFFECEEE3;
    static final int PALE = 0xFFD8DDC9;
    static final int GOLD = 0xFFDCC69C;
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
        GradientDrawable surface = background(color, context, 20);
        if (color != DARK) surface.setStroke(dp(context, 1), 0xFFE4E2D5);
        view.setBackground(surface);
        int padding = dp(context, 20); view.setPadding(padding, padding, padding, padding);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 16); view.setLayoutParams(params); return view;
    }
    static Button button(Context context, String text, boolean primary) {
        Button view = new Button(context);
        view.setText(text); view.setTextSize(15); view.setAllCaps(false);
        view.setTextColor(new ColorStateList(new int[][] { new int[] { -android.R.attr.state_enabled }, new int[0] },
                new int[] { primary ? 0xFFDAE4DC : 0xFF8B9790, primary ? Color.WHITE : ACCENT }));
        StateListDrawable surface = new StateListDrawable();
        surface.addState(new int[] { -android.R.attr.state_enabled }, background(0xFFE3E6DA, context, 12));
        surface.addState(new int[0], background(primary ? ACCENT : SOFT, context, 12));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2350664E),
                surface, background(Color.WHITE, context, 12)));
        view.setMinHeight(dp(context, 52)); view.setGravity(Gravity.CENTER);
        view.setStateListAnimator(null);
        view.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, 8); view.setLayoutParams(params); return view;
    }

    static LinearLayout step(Context context, String number, String title, String description) {
        LinearLayout box = card(context, CANVAS);
        int inset = dp(context, 16); box.setPadding(inset, inset, inset, inset);
        LinearLayout header = new LinearLayout(context); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(context, number, 25, ACCENT);
        badge.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        header.addView(badge, new LinearLayout.LayoutParams(dp(context, 44), -2));
        TextView caption = title(context, title); caption.setTextSize(16);
        header.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(header); box.addView(text(context, description, 12, MUTED));
        return box;
    }

    static Drawable nightScene(Context context) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private int opacity = 255;
            @Override public void draw(Canvas canvas) {
                float w = getBounds().width(), h = getBounds().height();
                int layer = canvas.saveLayerAlpha(new RectF(getBounds()), opacity);
                canvas.translate(getBounds().left, getBounds().top);
                Path clip = new Path();
                clip.addRoundRect(new RectF(0, 0, w, h), dp(context, 20), dp(context, 20), Path.Direction.CW);
                canvas.clipPath(clip); canvas.drawColor(DARK);
                float x = w - dp(context, 52), y = dp(context, 48), r = dp(context, 22);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(context, 1));
                paint.setColor(0x355E7962); canvas.drawCircle(x, y, r + dp(context, 11), paint);
                paint.setStyle(Paint.Style.FILL); paint.setColor(GOLD); canvas.drawCircle(x, y, r, paint);
                paint.setColor(DARK); canvas.drawCircle(x + r * .48f, y - r * .25f, r * .9f, paint);
                paint.setColor(PALE); canvas.drawCircle(x - r * 1.8f, y - r * .8f, dp(context, 1), paint);
                canvas.drawCircle(x + r * 1.4f, y + r, dp(context, 1), paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(context.getResources().getDisplayMetrics().density * .7f);
                paint.setColor(0x285F7C68);
                for (int i = 0; i < 4; i++) {
                    float base = h - dp(context, 10 + i * 12);
                    Path hill = new Path(); hill.moveTo(-w * .1f, base);
                    hill.cubicTo(w * .25f, base - dp(context, 48), w * .65f, base + dp(context, 42), w * 1.1f, base - dp(context, 28));
                    canvas.drawPath(hill, paint);
                }
                paint.setStyle(Paint.Style.FILL); canvas.restoreToCount(layer);
            }
            @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
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
                paint.setColor(GOLD); canvas.drawCircle(31, 34, 19, paint);
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
