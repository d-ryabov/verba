package org.verba;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class VoiceRecognitionOverlay {
    private final Context context;
    private WindowManager windowManager;
    private View overlayView;
    private TextView textView;
    private WindowManager.LayoutParams params;
    private boolean isShown = false;
    private final boolean animationsEnabled;

    private final int overlayWidth;
    private final int bottomMarginPx;
    private final int maxHeightPx;
    private final int cornerRadiusPx;
    private final int textSizePx;
    private int surfaceColor;
    private int textPrimaryColor;

    public VoiceRecognitionOverlay(Context context) {
        this.context = context.getApplicationContext();

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.animationsEnabled = pm == null || !pm.isPowerSaveMode();

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        overlayWidth = (int) (screenWidth * 0.9f);
        bottomMarginPx = dpToPx(24, metrics);
        maxHeightPx = (int) (screenHeight * 0.2f);
        cornerRadiusPx = dpToPx(12, metrics);
        textSizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 36, metrics);

        updateColors();
    }

    public void init() {
        if (!hasOverlayPermission()) return;

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        overlayView = createOverlayView();
        params = createLayoutParams();
        if (overlayView != null && params != null) {
            try {
                windowManager.addView(overlayView, params);
            } catch (Exception ignored) {}
        }
    }

    public void showOverlay(String text) {
        if (textView == null || !hasOverlayPermission()) return;
        text = text.trim();
        if (text.isEmpty()) return;

        textView.setText(text);
        textView.setTextColor(textPrimaryColor);

        if (!isShown) {
            textView.setVisibility(View.VISIBLE);
            fadeIn();
            isShown = true;
        }
    }

    public void hideOverlay() {
        hideWithAnimation();
    }

    public void release() {
        isShown = false;
        if (windowManager != null && overlayView != null) {
            if (overlayView.getParent() != null) {
                try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            }
        }
        overlayView = null;
        textView = null;
        windowManager = null;
    }

    private void updateColors() {
        Resources res = context.getResources();
        boolean isNight = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (isNight) {
            surfaceColor = 0xFF0C0F14;
            textPrimaryColor = 0xFFEAF1F6;
        } else {
            surfaceColor = 0xFFEAF1F6;
            textPrimaryColor = 0xFF0C0F14;
        }
    }

    private View createOverlayView() {
        FrameLayout container = new FrameLayout(context);
        container.setBackground(createRoundedRectDrawable());
        container.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));

        textView = new TextView(context);
        textView.setMaxHeight(maxHeightPx);
        textView.setTextColor(textPrimaryColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        //textView.setTypeface(Typeface.MONOSPACE);
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setMinHeight(dpToPx(48));
        textView.setSingleLine();
        textView.setAlpha(0f);
        textView.setVisibility(View.GONE);

        container.addView(textView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        return container;
    }

    private GradientDrawable createRoundedRectDrawable() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(surfaceColor);
        d.setCornerRadius(cornerRadiusPx);
        return d;
    }

    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = bottomMarginPx;
        return lp;
    }

    private void fadeIn() {
        if (textView == null) return;
        if (!animationsEnabled) {
            textView.setAlpha(1f);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(150);
        animator.addUpdateListener(a -> {
            if (textView != null) textView.setAlpha((Float) a.getAnimatedValue());
        });
        animator.start();
    }

    private void hideWithAnimation() {
        if (!isShown || overlayView == null) return;
        if (!animationsEnabled) {
            overlayView.setVisibility(View.GONE);
            isShown = false;
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(200);
        animator.addUpdateListener(a -> {
            if (overlayView != null) overlayView.setAlpha((Float) a.getAnimatedValue());
        });
        animator.addListener(new Animator.AnimatorListener() {
            @Override public void onAnimationStart(Animator a) {}
            @Override public void onAnimationEnd(Animator a) {
                if (overlayView != null) {
                    overlayView.setVisibility(View.GONE);
                    overlayView.setAlpha(1f);
                    isShown = false;
                }
            }
            @Override public void onAnimationCancel(Animator a) {}
            @Override public void onAnimationRepeat(Animator a) {}
        });
        animator.start();
    }

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(context);
    }

    private int dpToPx(int dp) {
        return dpToPx(dp, context.getResources().getDisplayMetrics());
    }

    private static int dpToPx(int dp, DisplayMetrics metrics) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics);
    }
}
