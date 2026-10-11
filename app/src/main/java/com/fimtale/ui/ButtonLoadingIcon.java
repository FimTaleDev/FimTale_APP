package com.fimtale.ui;

import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import androidx.annotation.MainThread;
import com.fimtale.R;
import com.google.android.material.button.MaterialButton;

/**
 * Temporarily replaces one MaterialButton's icon with a loading animation.
 * Each instance manages one active button. Call close() when its owning view is destroyed.
 * Button enabled state and request handling remain the caller's responsibility.
 */
@MainThread
public final class ButtonLoadingIcon implements AutoCloseable {
    private MaterialButton button;
    private Drawable originalIcon, loadingIcon;
    private CharSequence originalStateDescription;

    /** Starts loading, restoring any previously active button first. */
    public void start(MaterialButton target) {
        if (button == target) return;
        stop();
        button = target;
        originalIcon = target.getIcon();
        originalStateDescription = target.getStateDescription();
        loadingIcon = target.getContext().getDrawable(R.drawable.pull_refresh_loading);
        target.setIcon(loadingIcon);
        target.setStateDescription(target.getContext().getString(R.string.common_loading_active));
        ((Animatable) loadingIcon).start();
    }

    /** Updates the normal icon without interrupting an active loading animation. */
    public void setIcon(MaterialButton target, Drawable icon) {
        if (target == button) originalIcon = icon;
        else target.setIcon(icon);
    }

    /** Stops loading and restores the latest normal icon and accessibility state. */
    public void stop() {
        if (button == null) return;
        ((Animatable) loadingIcon).stop();
        button.setIcon(originalIcon);
        button.setStateDescription(originalStateDescription);
        button = null;
        originalIcon = loadingIcon = null;
        originalStateDescription = null;
    }

    @Override public void close() { stop(); }
}
