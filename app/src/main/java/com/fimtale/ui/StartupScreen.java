package com.fimtale.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.airbnb.lottie.LottieAnimationView;
import com.fimtale.R;
import com.google.android.material.color.MaterialColors;

/** A local, bounded launch animation while the first home request runs underneath. */
public final class StartupScreen implements DefaultLifecycleObserver {
    private static final long MAX_WAIT_MS = 5500;
    private static final long TRANSITION_DURATION_MS = 320;
    private final AppCompatActivity activity;
    private final ViewGroup host;
    private final View content, overlay;
    private final LottieAnimationView animation;
    private final Runnable onFinished;
    private final int contentAccessibility, statusBarColor, navigationBarColor;
    private final float contentAlpha;
    private final boolean lightStatusBar, lightNavigationBar;
    private final Runnable timeout = () -> {
        animationFinished = true;
        contentReady = true;
        finishIfReady();
    };
    private boolean animationFinished, contentReady, closing, fadedOut, destroyed;

    public StartupScreen(AppCompatActivity activity, Runnable onFinished) {
        this.activity = activity;
        this.onFinished = onFinished;
        host = activity.findViewById(android.R.id.content);
        content = host.getChildAt(0);
        contentAccessibility = content.getImportantForAccessibility();
        contentAlpha = content.getAlpha();
        content.setAlpha(0f);
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        overlay = LayoutInflater.from(activity).inflate(R.layout.view_startup, host, false);
        animation = overlay.findViewById(R.id.startupAnimation);
        animation.setSpeed(1.5f);
        host.addView(overlay);

        Window window = activity.getWindow();
        statusBarColor = window.getStatusBarColor();
        navigationBarColor = window.getNavigationBarColor();
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        lightStatusBar = bars.isAppearanceLightStatusBars();
        lightNavigationBar = bars.isAppearanceLightNavigationBars();
        int background = MaterialColors.getColor(overlay, android.R.attr.colorBackground);
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        boolean light = activity.getResources().getBoolean(R.bool.light_navigation_bar);
        bars.setAppearanceLightStatusBars(light);
        bars.setAppearanceLightNavigationBars(light);

        animation.setFailureListener(error -> {
            if (destroyed || closing) return;
            animation.setImageResource(R.drawable.startup_logo);
            animationFinished = true;
            finishIfReady();
        });
        animation.addAnimatorListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                animationFinished = true;
                finishIfReady();
            }
        });
        activity.getLifecycle().addObserver(this);
        overlay.postDelayed(timeout, MAX_WAIT_MS);
        if (ValueAnimator.areAnimatorsEnabled()) {
            animation.setAnimation(R.raw.startup_logo);
        } else {
            animation.setImageResource(R.drawable.startup_logo);
            animationFinished = true;
        }
    }

    public void onContentReady() {
        contentReady = true;
        finishIfReady();
    }

    private void finishIfReady() {
        if (destroyed || closing || !animationFinished || !contentReady
                || !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
        closing = true;
        overlay.removeCallbacks(timeout);
        animation.removeAllAnimatorListeners();
        animation.cancelAnimation();
        long duration = ValueAnimator.areAnimatorsEnabled() ? TRANSITION_DURATION_MS : 0;
        FastOutSlowInInterpolator easing = new FastOutSlowInInterpolator();
        content.animate().alpha(contentAlpha).setDuration(duration).setInterpolator(easing).start();
        overlay.animate().alpha(0).setDuration(duration).setInterpolator(easing)
                .withEndAction(() -> {
                    fadedOut = true;
                    completeIfResumed();
                }).start();
    }

    private void completeIfResumed() {
        if (destroyed || !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
        release();
        onFinished.run();
    }

    @Override public void onResume(@NonNull LifecycleOwner owner) {
        if (fadedOut) {
            completeIfResumed();
            return;
        }
        finishIfReady();
        if (!destroyed && !closing && !animationFinished) animation.resumeAnimation();
    }

    @Override public void onPause(@NonNull LifecycleOwner owner) {
        animation.pauseAnimation();
    }

    @Override public void onDestroy(@NonNull LifecycleOwner owner) {
        destroyed = true;
        release();
    }

    private void release() {
        overlay.removeCallbacks(timeout);
        overlay.animate().cancel();
        content.animate().cancel();
        content.setAlpha(contentAlpha);
        animation.removeAllAnimatorListeners();
        animation.cancelAnimation();
        animation.setImageDrawable(null);
        host.removeView(overlay);
        content.setImportantForAccessibility(contentAccessibility);
        Window window = activity.getWindow();
        window.setStatusBarColor(statusBarColor);
        window.setNavigationBarColor(navigationBarColor);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(lightStatusBar);
        bars.setAppearanceLightNavigationBars(lightNavigationBar);
        activity.getLifecycle().removeObserver(this);
    }
}
