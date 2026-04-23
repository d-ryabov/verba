package org.verba;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
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
import android.widget.Toast;

public class VoiceRecognitionOverlay {
    private final Context context;
    private final boolean isDebug;
    private WindowManager windowManager;
    private View overlayView;
    private TextView textView;
    private WindowManager.LayoutParams params;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideWithAnimation;
    private boolean isShown = false;
    private String lastText = "";
    // Cached values
    private final int screenWidth;
    private final int screenHeight;
    private final int overlayWidth;
    private final int bottomMarginPx;
    private final int maxHeightPx;
    private final int cornerRadiusPx;
    private final int textSizePx;
    private int surfaceColor;
    private int textPrimaryColor;
    private final boolean animationsEnabled;

    public VoiceRecognitionOverlay(Context context, boolean isVoiceDebug) {
        this.context = context.getApplicationContext();
        this.isDebug = isVoiceDebug;
        this.animationsEnabled = !isPowerSaveMode() && isVoiceDebug;

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        overlayWidth = (int) (screenWidth * 0.9f);
        bottomMarginPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, metrics);
        maxHeightPx = (int) (screenHeight * 0.2f);
        cornerRadiusPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, metrics);
        textSizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18, metrics);

        updateColors();
    }

    public void init() {
        if (!isDebug || !hasOverlayPermission()) {
            return;
        }
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        overlayView = createOverlayView();
        params = createLayoutParams();
        if (overlayView != null && params != null) {
            try {
                windowManager.addView(overlayView, params);
            } catch (Exception e) {
                Toast.makeText(context, "Overlay permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void showOverlay(String text) {
        text = text.trim();
        if (!isDebug || textView == null || !hasOverlayPermission()) {
            return;
        }
        text = text.trim();
        if (text.equals(lastText)) return;
        lastText = text;

        if (text.isEmpty()) {
            hideWithAnimation();
            return;
        }

        textView.setText(text.isEmpty() ? "Команда не распознана" : text);
        textView.setTextColor(textPrimaryColor);

        if (!isShown) {
            textView.setVisibility(View.VISIBLE);
            fadeIn();
            isShown = true;
        }
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.postDelayed(hideRunnable, 5000);
    }

    public void release() {
        isShown = false;
        hideHandler.removeCallbacks(hideRunnable);
        if (windowManager != null && overlayView != null) {
            if (overlayView.getParent() != null) {
                windowManager.removeView(overlayView);
            }
            overlayView.setVisibility(View.GONE);
        }
        overlayView = null;
        textView = null;
        windowManager = null;
    }

    private boolean isPowerSaveMode() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isPowerSaveMode();
    }

    private void updateColors() {
        Resources res = context.getResources();
        Configuration config = res.getConfiguration();
        boolean isNight = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

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

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );

        textView = new TextView(context);
        textView.setMaxHeight(maxHeightPx);
        textView.setTextColor(textPrimaryColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setMinHeight(dpToPx(48));
        textView.setSingleLine();
        textView.setAlpha(0f);
        textView.setVisibility(View.GONE);

        container.addView(textView, lp);

        return container;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        );
    }

    private GradientDrawable createRoundedRectDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(surfaceColor);
        drawable.setCornerRadius(cornerRadiusPx);
        return drawable;
    }

    private WindowManager.LayoutParams createLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = bottomMarginPx;
        return lp;
    }

    private void fadeIn() {
        if (!animationsEnabled || textView == null) {
            textView.setAlpha(1f);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(150);
        animator.addUpdateListener(animation -> {
            if (textView != null) textView.setAlpha((Float) animation.getAnimatedValue());
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
        animator.addUpdateListener(animation -> {
            if (overlayView != null) overlayView.setAlpha((Float) animation.getAnimatedValue());
        });
        animator.addListener(new Animator.AnimatorListener() {
            @Override public void onAnimationStart(Animator animation) {}
            @Override public void onAnimationEnd(Animator animation) {
                if (overlayView != null) {
                    overlayView.setVisibility(View.GONE);
                    overlayView.setAlpha(1f);  // ← Reset
                    isShown = false;
                }
            }
            @Override public void onAnimationCancel(Animator animation) {}
            @Override public void onAnimationRepeat(Animator animation) {}
        });
        animator.start();
    }


    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(context);
    }
}