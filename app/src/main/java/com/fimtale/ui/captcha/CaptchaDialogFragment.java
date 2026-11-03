package com.fimtale.ui.captcha;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.fimtale.R;
import com.fimtale.network.SiteUrls;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** A real vendor challenge inside a native dialog; no JavaScript-to-native bridge. */
public class CaptchaDialogFragment extends DialogFragment {
    public static final String TAG = "captcha_dialog", RESULT_KEY = "captcha_result";
    public static final String TOKEN = "token", PROVIDER = "provider";
    private static final String REQUEST_KEY = "request_key";

    /** Each caller owns its result channel, which also survives fragment recreation. */
    public static CaptchaDialogFragment newInstance(String requestKey) {
        CaptchaDialogFragment dialog = new CaptchaDialogFragment();
        Bundle args = new Bundle();
        args.putString(REQUEST_KEY, requestKey);
        dialog.setArguments(args);
        return dialog;
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final Map<String, String> keys = CaptchaProviders.configured();
    private final ArrayList<String> tried = new ArrayList<>();
    private WebView web;
    private View statusPanel, progress;
    private TextView status;
    private com.fimtale.ui.PageErrorView error;
    private String provider, attempt;
    private boolean settled, polling, escapeOffered, loaded, interactive;
    private String escapeReason;
    private int availableHeight;
    private float widgetHeight = 100;

    private static class State {
        String attempt, provider, token, reason;
        boolean escape, interactive, loaded;
        float height;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!polling || web == null || settled) return;
            String expected = attempt;
            web.evaluateJavascript("JSON.stringify(window.__ftCaptchaState || null)", value -> {
                if (!polling || web == null || settled || !expected.equals(attempt)) return;
                try { receive(gson.fromJson(value, String.class)); }
                catch (RuntimeException ignored) { /* The page may still be loading. */ }
            });
            handler.postDelayed(this, 250);
        }
    };

    @NonNull @SuppressLint("SetJavaScriptEnabled")
    @Override public Dialog onCreateDialog(Bundle savedState) {
        List<String> available = new ArrayList<>(keys.keySet());
        provider = savedState == null ? null : savedState.getString(PROVIDER);
        if (!available.contains(provider)) provider = CaptchaProviders.preferred(requireContext(), available);
        if (savedState != null && savedState.getStringArrayList("tried") != null)
            tried.addAll(savedState.getStringArrayList("tried"));
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        View content = android.view.LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_captcha, null);
        statusPanel = content.findViewById(R.id.captchaStatusPanel);
        progress = content.findViewById(R.id.captchaProgress);
        status = content.findViewById(R.id.captchaStatus);
        error = content.findViewById(R.id.captchaError);
        web = content.findViewById(R.id.captchaWeb);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return request.isForMainFrame(); // The top document must remain our bundled host.
            }
        });
        AlertDialog dialog = builder.setTitle(R.string.captcha_title).setView(content)
                .setNegativeButton(R.string.captcha_close, (d, which) -> finish(null))
                .setPositiveButton(keys.size() > 1 ? R.string.captcha_switch : R.string.captcha_retry, null)
                .setBackgroundInsetStart(0).setBackgroundInsetEnd(0).create();
        dialog.setCanceledOnTouchOutside(false);
        loadWidget();
        return dialog;
    }

    @Override public void onStart() {
        super.onStart();
        float density = getResources().getDisplayMetrics().density;
        android.graphics.Rect bounds = requireActivity().getWindowManager().getCurrentWindowMetrics().getBounds();
        android.graphics.Insets bars = requireActivity().getWindowManager().getCurrentWindowMetrics().getWindowInsets()
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        int width = Math.min(bounds.width() - bars.left - bars.right - (int) (24 * density), (int) (420 * density));
        availableHeight = bounds.height() - bars.top - bars.bottom - (int) (32 * density);
        requireDialog().getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> switchProvider());
        requireDialog().getWindow().getDecorView().addOnLayoutChangeListener(
                (v, l, t, r, b, oldL, oldT, oldR, oldB) -> resizeWidget());
        renderState();
        web.onResume(); polling = true;
        handler.post(poll);
    }

    private void loadWidget() {
        attempt = UUID.randomUUID().toString();
        escapeOffered = false; escapeReason = null; loaded = false; interactive = false; widgetHeight = 100;
        renderState();
        try (InputStream in = requireContext().getAssets().open("captcha.html")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int count;
            while ((count = in.read(bytes)) != -1) buffer.write(bytes, 0, count);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("attempt", attempt); config.put("provider", provider); config.put("key", keys.get(provider));
            config.put("dark", !getResources().getBoolean(R.bool.light_navigation_bar));
            String html = new String(buffer.toByteArray(), StandardCharsets.UTF_8).replace("/*CONFIG*/", gson.toJson(config));
            web.loadDataWithBaseURL(SiteUrls.SITE + "/app-captcha/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            escapeOffered = true; escapeReason = "host_load_failed"; widgetHeight = 0;
            renderState();
        }
    }

    // Only the active document's state is accepted, including when a provider repeats next round.
    void receive(String json) {
        State state = gson.fromJson(json, State.class);
        if (state == null || settled || !attempt.equals(state.attempt) || provider == null || !provider.equals(state.provider)) return;
        if (state.token != null && !state.token.isEmpty() && state.token.length() <= 16384) {
            CaptchaProviders.rememberSuccess(requireContext(), provider);
            finish(state.token); return;
        }
        loaded = state.loaded; interactive = state.interactive;
        if (state.escape) { escapeOffered = true; escapeReason = state.reason; }
        if (Float.isFinite(state.height)) widgetHeight = Math.max(0, state.height);
        renderState();
    }

    private void switchProvider() {
        if (settled || !polling || !escapeOffered || web == null) return;
        if (provider != null && !tried.contains(provider)) tried.add(provider);
        provider = CaptchaProviders.roll(new ArrayList<>(keys.keySet()), tried, new Random());
        loadWidget();
    }

    private void renderState() {
        if (statusPanel == null) return;
        statusPanel.setVisibility(!escapeOffered && !loaded ? View.VISIBLE : View.GONE);
        progress.setVisibility(!escapeOffered && !loaded ? View.VISIBLE : View.GONE);
        int message = !escapeOffered ? R.string.captcha_loading
                : "unsupported_browser".equals(escapeReason) ? R.string.captcha_unsupported
                : "host_load_failed".equals(escapeReason) ? R.string.captcha_load_error : R.string.captcha_slow;
        if (!getText(message).equals(status.getText())) status.setText(message);
        if (escapeOffered) error.showPrompt(R.string.page_error_title, getText(message), "cloud-off-outline",
                keys.size() > 1 ? R.string.captcha_switch : R.string.captcha_retry, this::switchProvider);
        else error.hide();
        if (getDialog() instanceof AlertDialog) {
            Button switchButton = ((AlertDialog) getDialog()).getButton(AlertDialog.BUTTON_POSITIVE);
            if (switchButton != null) switchButton.setVisibility(View.GONE);
        }
        resizeWidget();
    }

    private void resizeWidget() {
        if (web == null || getDialog() == null || availableHeight <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        View decor = requireDialog().getWindow().getDecorView();
        // Reserve the measured native title, message and action row, including larger font sizes.
        int chrome = decor.getHeight() > 0 ? decor.getHeight() - web.getHeight() : (int) (160 * density);
        int maxHeight = Math.max(1, Math.min(availableHeight - chrome, (int) (540 * density)));
        // Turnstile's interactive checkbox stays inline; other providers need room for challenge panels.
        boolean expandChallenge = interactive && !"turnstile".equals(provider);
        int height = expandChallenge ? maxHeight : Math.min(maxHeight, Math.max(1, (int) Math.ceil(widgetHeight * density)));
        ViewGroup.LayoutParams params = web.getLayoutParams();
        if (height != params.height) { params.height = height; web.setLayoutParams(params); }
    }

    private void finish(String token) {
        if (settled) return;
        settled = true;
        Bundle result = new Bundle();
        result.putString(TOKEN, token); result.putString(PROVIDER, token == null ? null : provider);
        String requestKey = getArguments() == null ? RESULT_KEY : getArguments().getString(REQUEST_KEY, RESULT_KEY);
        getParentFragmentManager().setFragmentResult(requestKey, result);
        dismissAllowingStateLoss();
    }

    @Override public void onCancel(@NonNull DialogInterface dialog) { finish(null); }
    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(PROVIDER, provider); out.putStringArrayList("tried", new ArrayList<>(tried));
    }
    @Override public void onStop() {
        polling = false; handler.removeCallbacks(poll);
        if (web != null) web.onPause();
        super.onStop();
    }
    @Override public void onDestroyView() {
        polling = false; handler.removeCallbacks(poll);
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.stopLoading(); web.destroy(); web = null;
        }
        statusPanel = null; progress = null; status = null; error = null;
        super.onDestroyView();
    }
}
