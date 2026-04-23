package org.verba;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class VoiceRecognitionOverlay {
    private final Context context;
    private final boolean animationsEnabled;

    private final int overlayWidth;
    private final int bottomMarginPx;
    private final int maxHeightPx;
    private final int cornerRadiusPx;
    private final int textSizePx;

    private final int surfaceColor;
    private final int textPrimaryColor;

    private WindowManager windowManager;
    private View overlayView;
    private TextView textView;
    private ImageView micIcon;
    private ValueAnimator hideAnimator;

    private boolean isShown = false;

    public VoiceRecognitionOverlay(Context context) {
        this.context = context.getApplicationContext();

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.animationsEnabled = pm == null || !pm.isPowerSaveMode();

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth  = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        overlayWidth    = (int) (screenWidth * 0.9f);
        bottomMarginPx  = dpToPx(24, metrics);
        maxHeightPx     = (int) (screenHeight * 0.2f);
        cornerRadiusPx  = dpToPx(12, metrics);
        textSizePx      = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 36, metrics);

        boolean isNight = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        surfaceColor     = isNight ? 0xFF0C0F14 : 0xFFEAF1F6;
        textPrimaryColor = isNight ? 0xFFEAF1F6 : 0xFF0C0F14;
    }

    public void init() {
        if (!hasOverlayPermission()) return;

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        overlayView = createOverlayView();
        WindowManager.LayoutParams lp = createLayoutParams();
        try {
            windowManager.addView(overlayView, lp);
        } catch (Exception ignored) {}
    }

    public void showOverlay(String text) {
        if (textView == null || micIcon == null || !hasOverlayPermission()) return;
        text = text.trim();
        if (text.isEmpty()) return;

        textView.setText(text);
        textView.setTextColor(textPrimaryColor);
        micIcon.setColorFilter(textPrimaryColor);

        if (!isShown) {
            cancelHideAnimator();
            overlayView.setAlpha(1f);
            textView.setVisibility(View.VISIBLE);
            micIcon.setVisibility(View.VISIBLE);
            fadeIn();
            startMicPulse();
            isShown = true;
        }
    }

    public void hideOverlay() {
        hideWithAnimation();
    }

    public void stopPulse() {
        stopMicPulse();
    }

    public void release() {
        isShown = false;
        cancelHideAnimator();
        stopMicPulse();
        if (windowManager != null && overlayView != null) {
            if (overlayView.getParent() != null) {
                try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            }
        }
        overlayView   = null;
        textView      = null;
        micIcon       = null;
        windowManager = null;
    }

    private View createOverlayView() {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setBaselineAligned(false);
        container.setBackground(createRoundedRectDrawable());
        container.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));

        int iconSize = textSizePx;

        micIcon = new ImageView(context);
        micIcon.setImageResource(R.drawable.ic_mic_24);
        micIcon.setColorFilter(textPrimaryColor);
        micIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        micIcon.setAlpha(0f);
        micIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.rightMargin = dpToPx(12);
        iconParams.gravity = Gravity.CENTER_VERTICAL;
        micIcon.setLayoutParams(iconParams);

        textView = new TextView(context);
        textView.setMaxHeight(maxHeightPx);
        textView.setTextColor(textPrimaryColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setSingleLine();
        textView.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            textView.setFirstBaselineToTopHeight(0);
            textView.setLastBaselineToBottomHeight(0);
        }
        textView.setAlpha(0f);
        textView.setVisibility(View.GONE);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.gravity = Gravity.CENTER_VERTICAL;
        textView.setLayoutParams(textParams);

        container.addView(micIcon);
        container.addView(textView);
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
        if (textView == null || micIcon == null) return;
        if (!animationsEnabled) {
            textView.setAlpha(1f);
            micIcon.setAlpha(1f);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(150);
        animator.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            if (textView != null) textView.setAlpha(v);
            if (micIcon != null)  micIcon.setAlpha(v);
        });
        animator.start();
    }

    private void startMicPulse() {
        if (micIcon == null) return;
        stopMicPulse();
        if (!animationsEnabled) return;

        ValueAnimator pulseAnim = ValueAnimator.ofFloat(1.0f, 1.1f);
        pulseAnim.setDuration(800);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnim.addUpdateListener(a -> {
            if (micIcon == null) return;
            float scale = (Float) a.getAnimatedValue();
            micIcon.setScaleX(scale);
            micIcon.setScaleY(scale);
        });
        pulseAnim.start();
        micIcon.setTag(pulseAnim);
    }

    private void stopMicPulse() {
        if (micIcon == null) return;
        Object tag = micIcon.getTag();
        if (tag instanceof ValueAnimator) {
            ((ValueAnimator) tag).cancel();
            micIcon.setTag(null);
        }
        micIcon.setScaleX(1f);
        micIcon.setScaleY(1f);
        micIcon.setAlpha(1f);
    }

    private void cancelHideAnimator() {
        if (hideAnimator != null) {
            hideAnimator.cancel();
            hideAnimator = null;
        }
    }

    private void hideWithAnimation() {
        if (!isShown || overlayView == null) return;
        stopMicPulse();
        cancelHideAnimator();

        if (!animationsEnabled) {
            overlayView.setVisibility(View.GONE);
            isShown = false;
            return;
        }

        hideAnimator = ValueAnimator.ofFloat(1f, 0f);
        hideAnimator.setDuration(200);
        hideAnimator.addUpdateListener(a -> {
            if (overlayView != null) overlayView.setAlpha((Float) a.getAnimatedValue());
        });
        hideAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (overlayView != null) {
                    overlayView.setVisibility(View.GONE);
                    overlayView.setAlpha(1f);
                }
                isShown = false;
                hideAnimator = null;
            }
        });
        hideAnimator.start();
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
