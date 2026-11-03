package com.fimtale;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.github.chrisbanes.photoview.PhotoView;
import com.fimtale.network.SiteUrls;

/** PhotoView owns pinch, double-tap and pan; Glide owns image decoding and lifecycle. */
public class ImagePreviewActivity extends AppCompatActivity {
    private PhotoView photo;
    private ProgressBar loading;
    private com.fimtale.ui.PageErrorView error;
    private String url;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        url = SiteUrls.media(getIntent().getStringExtra("image_url"));
        if (url == null) { finish(); return; }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightNavigationBars(false);
        bars.setAppearanceLightStatusBars(false);
        bars.hide(WindowInsetsCompat.Type.systemBars());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        photo = new PhotoView(this);
        photo.setContentDescription(getString(R.string.image_preview_description));
        root.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        photo.setOnSingleFlingListener((first, last, vx, vy) -> {
            if (photo.getScale() <= photo.getMinimumScale() + .01f
                    && Math.abs(vy) > Math.abs(vx) * 1.5f
                    && Math.abs(last.getY() - first.getY()) > dp(80)) {
                finish(); return true;
            }
            return false;
        });
        loading = new ProgressBar(this);
        root.addView(loading, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        android.content.res.Configuration palette = new android.content.res.Configuration(getResources().getConfiguration());
        palette.uiMode = (palette.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        android.content.Context errorContext = new android.view.ContextThemeWrapper(
                createConfigurationContext(palette), R.style.Theme_Fimtale);
        error = new com.fimtale.ui.PageErrorView(errorContext);
        root.addView(error, new FrameLayout.LayoutParams(-1, -1));
        TextView close = label(getString(R.string.common_close));
        close.setContentDescription(getString(R.string.image_preview_close_description));
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.END);
        root.addView(close, closeParams);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            closeParams.topMargin = safe.top;
            closeParams.rightMargin = safe.right + dp(8);
            close.setLayoutParams(closeParams);
            error.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(root);
        load();
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(16); view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER); view.setPadding(dp(20), dp(12), dp(20), dp(12));
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void load() {
        loading.setVisibility(View.VISIBLE); error.hide();
        Glide.with(this).load(url).listener(new RequestListener<Drawable>() {
            @Override public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean first) {
                loading.setVisibility(View.GONE); error.show(getString(R.string.image_preview_load_failed), ImagePreviewActivity.this::load); return false;
            }
            @Override public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource source, boolean first) {
                loading.setVisibility(View.GONE); error.hide(); return false;
            }
        }).into(photo);
    }
}
