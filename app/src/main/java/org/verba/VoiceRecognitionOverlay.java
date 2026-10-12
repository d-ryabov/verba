package org.verba;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class VoiceRecognitionOverlay {
    private static final int WIN_LEFT     = 696;
    private static final int WIN_CENTER_Y = 148;
    private static final int WIN_WIDTH    = 529;
    private static final int WIN_MIN_H    = 120;
    private static final int MIC_LEFT     = 48;
    private static final int MIC_SIZE     = 80;
    private static final int ICON_PAD     = 16;
    private static final int TEXT_LEFT    = 169;

    private static final int MIN_PAD_DP    = 16;
    private static final int MIN_RADIUS_DP = 36;
    private static final int TEXT_SP       = 32;
    private static final int MIN_TEXT_SP   = 28;

    private static final int REVEAL_OPEN_MS  = 180;
    private static final int REVEAL_CLOSE_MS = 135;
    private static final int PULSE_MS        = 800;
    private static final float PULSE_SCALE   = 1.1f;

    private final Context context;
    private final boolean animationsEnabled;
    private final DisplayMetrics metrics;
    private final DecelerateInterpolator revealInterpolator = new DecelerateInterpolator();
    private final Rect clipRect = new Rect();

    private final int padPx;
    private final int cornerRadiusPx;
    private final int textSizePx;
    private final int lineHeightPx;
    private final int winHeightPx;
    private final int textWidthPx;

    private int surfaceColor;
    private int textColor;
    private int idleCircleColor;
    private int idleIconColor;
    private int activeCircleColor;

    private WindowManager windowManager;
    private FrameLayout overlayView;
    private TailTextView textView;
    private ImageView micIcon;
    private GradientDrawable micBg;
    private ValueAnimator revealAnimator;
    private ObjectAnimator pulseAnimator;

    private boolean isShown = false;

    public VoiceRecognitionOverlay(Context context) {
        this.context = context.getApplicationContext();

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.animationsEnabled = pm == null || !pm.isPowerSaveMode();

        metrics = context.getResources().getDisplayMetrics();

        padPx          = dp(MIN_PAD_DP);
        cornerRadiusPx = dp(MIN_RADIUS_DP);
        textSizePx     = Math.max(sp(TEXT_SP), sp(MIN_TEXT_SP));

        TextPaint probe = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        probe.setTextSize(textSizePx);
        probe.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        Paint.FontMetricsInt fm = probe.getFontMetricsInt();
        lineHeightPx = fm.descent - fm.ascent;

        winHeightPx = Math.max(WIN_MIN_H, 2 * lineHeightPx + 2 * padPx);
        textWidthPx = WIN_WIDTH - TEXT_LEFT - padPx;

        applyTheme();
    }

    public void init() {
        if (!hasOverlayPermission()) return;

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        overlayView = createOverlayView();
        overlayView.setVisibility(View.INVISIBLE);
        try {
            windowManager.addView(overlayView, createLayoutParams());
        } catch (Exception ignored) {}
    }

    public void showOverlay(String text) {
        if (textView == null || micIcon == null) return;
        text = text.trim();
        if (text.isEmpty()) return;

        if (!isShown && !hasOverlayPermission()) return;

        textView.setTail(text);

        if (!isShown) {
            applyTheme();
            applyColors();
            setActive(true);
            cancelReveal();
            overlayView.setVisibility(View.VISIBLE);
            reveal(true);
            startMicPulse();
            isShown = true;
        }
    }

    public void hideOverlay() {
        if (!isShown || overlayView == null) return;
        isShown = false;
        stopMicPulse();
        cancelReveal();
        if (!animationsEnabled) {
            overlayView.setVisibility(View.INVISIBLE);
            return;
        }
        reveal(false);
    }

    public void stopPulse() {
        stopMicPulse();
        setActive(false);
    }

    public void release() {
        isShown = false;
        cancelReveal();
        stopMicPulse();
        if (windowManager != null && overlayView != null && overlayView.getParent() != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
        }
        overlayView   = null;
        textView      = null;
        micIcon       = null;
        micBg         = null;
        windowManager = null;
    }

    private boolean isNight() {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String theme = sp.getString("app_theme", "system");
        if ("light".equals(theme)) return false;
        if ("dark".equals(theme)) return true;
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyTheme() {
        boolean night = isNight();
        surfaceColor      = night ? 0x9917171A : 0xEAE9EFF7;
        textColor         = night ? 0xFFFFFFFF : 0xFF000000;
        idleCircleColor   = night ? 0xFFFFFFFF : 0xFF000000;
        idleIconColor     = night ? 0xFF000000 : 0xFFFFFFFF;
        activeCircleColor = night ? 0xFF0088FF : 0xFF1368FB;
    }

    private FrameLayout createOverlayView() {
        FrameLayout root = new FrameLayout(context);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(surfaceColor);
        bg.setCornerRadius(cornerRadiusPx);
        root.setBackground(bg);

        micIcon = new ImageView(context);
        micIcon.setImageResource(R.drawable.ic_mic_24);
        micIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        micIcon.setPadding(ICON_PAD, ICON_PAD, ICON_PAD, ICON_PAD);
        micBg = new GradientDrawable();
        micBg.setShape(GradientDrawable.OVAL);
        micIcon.setBackground(micBg);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(MIC_SIZE, MIC_SIZE);
        mp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        mp.leftMargin = MIC_LEFT;
        root.addView(micIcon, mp);

        textView = new TailTextView(context, textSizePx, lineHeightPx, textWidthPx);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(textWidthPx, 2 * lineHeightPx);
        tp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        tp.leftMargin = TEXT_LEFT;
        root.addView(textView, tp);

        applyColors();
        setActive(false);
        return root;
    }

    private void applyColors() {
        if (overlayView == null || textView == null) return;
        ((GradientDrawable) overlayView.getBackground()).setColor(surfaceColor);
        textView.setColor(textColor);
    }

    private void setActive(boolean active) {
        if (micBg == null || micIcon == null) return;
        micBg.setColor(active ? activeCircleColor : idleCircleColor);
        micIcon.setColorFilter(active ? Color.WHITE : idleIconColor);
    }

    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WIN_WIDTH,
                winHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = WIN_LEFT;
        lp.y = WIN_CENTER_Y - winHeightPx / 2;
        return lp;
    }

    private void reveal(boolean open) {
        if (!animationsEnabled) {
            overlayView.setClipBounds(null);
            if (!open) overlayView.setVisibility(View.INVISIBLE);
            return;
        }
        final int cx = WIN_WIDTH / 2;
        final int h = winHeightPx;

        revealAnimator = ValueAnimator.ofFloat(open ? 0f : 1f, open ? 1f : 0f);
        revealAnimator.setDuration(open ? REVEAL_OPEN_MS : REVEAL_CLOSE_MS);
        revealAnimator.setInterpolator(revealInterpolator);
        revealAnimator.addUpdateListener(a -> {
            if (overlayView == null) return;
            int half = Math.max(1, (int) (cx * (Float) a.getAnimatedValue()));
            clipRect.set(cx - half, 0, cx + half, h);
            overlayView.setClipBounds(clipRect);
        });
        revealAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled || overlayView == null) return;
                overlayView.setClipBounds(null);
                if (!open) overlayView.setVisibility(View.INVISIBLE);
                revealAnimator = null;
            }
        });
        revealAnimator.start();
    }

    private void cancelReveal() {
        if (revealAnimator != null) {
            revealAnimator.cancel();
            revealAnimator = null;
        }
        if (overlayView != null) overlayView.setClipBounds(null);
    }

    private void startMicPulse() {
        if (micIcon == null || !animationsEnabled) return;
        stopMicPulse();
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                micIcon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, PULSE_SCALE),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, PULSE_SCALE));
        pulseAnimator.setDuration(PULSE_MS);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.start();
    }

    private void stopMicPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (micIcon != null) {
            micIcon.setScaleX(1f);
            micIcon.setScaleY(1f);
        }
    }

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(context);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, metrics);
    }

    private int sp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics);
    }

    private static final class TailTextView extends View {
        private static final int MAX_LINES = 2;

        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final int width;
        private final int lineHeight;
        private final int blockHeight;
        private final int ascentOffset;
        private final int[] starts = new int[MAX_LINES + 1];
        private final int[] ends = new int[MAX_LINES + 1];

        private String fullText = "";
        private int base = 0;
        private int count = 0;

        TailTextView(Context context, int textSizePx, int lineHeight, int width) {
            super(context);
            this.width = width;
            this.lineHeight = lineHeight;
            this.blockHeight = MAX_LINES * lineHeight;
            paint.setTextSize(textSizePx);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            this.ascentOffset = -paint.getFontMetricsInt().ascent;
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setTail(String text) {
            if (text.equals(fullText)) return;

            if (base > 0 && (text.length() < base || !text.regionMatches(0, fullText, 0, base))) {
                base = 0;
            }
            fullText = text;
            wrap();
            invalidate();
        }

        private void wrap() {
            final String text = fullText;
            final int len = text.length();
            while (true) {
                count = 0;
                int pos = base;
                while (count <= MAX_LINES && pos < len) {
                    int n = Math.max(1, paint.breakText(text, pos, len, true, width, null));
                    int end = pos + n;
                    int next = end;
                    if (end < len && text.charAt(end) != ' ') {
                        int sp = text.lastIndexOf(' ', end - 1);
                        if (sp > pos) {
                            end = sp;
                            next = sp + 1;
                        }
                    }
                    starts[count] = pos;
                    ends[count] = end;
                    count++;
                    pos = next;
                    while (pos < len && text.charAt(pos) == ' ') pos++;
                }
                if (count > MAX_LINES) {
                    base = starts[count - MAX_LINES];
                    continue;
                }
                break;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (count == 0) return;
            int top = (blockHeight - count * lineHeight) / 2;
            for (int i = 0; i < count; i++) {
                float y = top + i * lineHeight + ascentOffset;
                canvas.drawText(fullText, starts[i], ends[i], 0f, y, paint);
            }
        }
    }
}
