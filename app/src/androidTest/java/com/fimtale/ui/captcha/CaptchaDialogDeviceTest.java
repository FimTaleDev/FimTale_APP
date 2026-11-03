package com.fimtale.ui.captcha;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fimtale.AboutActivity;
import com.fimtale.R;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real WebView + native dialog integration; vendor requests terminate in local fixtures. */
@RunWith(AndroidJUnit4.class)
public class CaptchaDialogDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<WebView> web = new AtomicReference<>();
    private final AtomicReference<Bundle> result = new AtomicReference<>();
    private final AtomicBoolean pageReady = new AtomicBoolean();
    private SharedPreferences preferences;
    private Map<String, ?> originalPreferences;
    private int originalNightMode;

    @Before public void setup() {
        preferences = instrumentation.getTargetContext().getSharedPreferences("fimtale_captcha", 0);
        originalPreferences = preferences.getAll();
        preferences.edit().clear().commit();
        originalNightMode = AppCompatDelegate.getDefaultNightMode();
        instrumentation.runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO));
    }

    @After public void cleanup() {
        SharedPreferences.Editor editor = preferences.edit().clear();
        originalPreferences.forEach((key, value) -> { if (value instanceof String) editor.putString(key, (String) value); });
        editor.commit();
        instrumentation.runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(originalNightMode));
    }

    @Test public void fallbackCyclesAndSuccessfulProviderIsRememberedInARealWebView() throws Exception {
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            scenario.onActivity(activity -> activity.getSupportFragmentManager().setFragmentResultListener(
                    CaptchaDialogFragment.RESULT_KEY, activity, (key, value) -> result.set(value)));
            open(scenario);
            JsonObject initial = awaitEscape(null);
            assertEquals("turnstile", initial.get("provider").getAsString());
            Set<String> tried = new HashSet<>(); tried.add("turnstile");
            capture("captcha-dialog");
            for (int i = 0; i < 2; i++) {
                String previousAttempt = state().get("attempt").getAsString();
                switchProvider(scenario);
                JsonObject next = awaitEscape(previousAttempt);
                assertTrue("No repeated provider before all three are tried", tried.add(next.get("provider").getAsString()));
            }
            assertEquals(3, tried.size());
            String previousAttempt = state().get("attempt").getAsString();
            switchProvider(scenario);
            JsonObject repeated = awaitEscape(previousAttempt);
            String successful = repeated.get("provider").getAsString();
            assertTrue(tried.contains(successful));
            evaluate("window.fixtureSuccess()");
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (result.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertNotNull(result.get());
            assertNotNull(result.get().getString(CaptchaDialogFragment.TOKEN));
            assertEquals(successful, result.get().getString(CaptchaDialogFragment.PROVIDER));
            instrumentation.waitForIdleSync();
            open(scenario);
            assertEquals(successful, awaitEscape(null).get("provider").getAsString());
            scenario.onActivity(activity -> ((AlertDialog) ((CaptchaDialogFragment) activity.getSupportFragmentManager()
                    .findFragmentByTag(CaptchaDialogFragment.TAG)).requireDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            instrumentation.waitForIdleSync();
            assertNull(result.get().getString(CaptchaDialogFragment.TOKEN));
        }
    }

    @Test public void nightThemeUsesNativeMaterialErrorAndActions() throws Exception {
        instrumentation.runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        try (ActivityScenario<AboutActivity> scenario = ActivityScenario.launch(AboutActivity.class)) {
            open(scenario); awaitEscape(null);
            awaitNativeSwitch();
            scenario.onActivity(activity -> {
                AlertDialog dialog = (AlertDialog) ((CaptchaDialogFragment) activity.getSupportFragmentManager()
                        .findFragmentByTag(CaptchaDialogFragment.TAG)).requireDialog();
                assertTrue(dialog.findViewById(R.id.pageErrorRetry) instanceof com.google.android.material.button.MaterialButton);
                assertEquals(activity.getString(R.string.captcha_unsupported), ((TextView) dialog.findViewById(R.id.captchaError).findViewById(R.id.pageErrorMessage)).getText().toString());
                assertEquals(View.GONE, dialog.findViewById(R.id.captchaProgress).getVisibility());
            });
            capture("captcha-dialog-night");
        }
    }

    private void switchProvider(ActivityScenario<AboutActivity> scenario) throws Exception {
        awaitNativeSwitch();
        scenario.onActivity(activity -> {
            AlertDialog dialog = (AlertDialog) ((CaptchaDialogFragment) activity.getSupportFragmentManager()
                    .findFragmentByTag(CaptchaDialogFragment.TAG)).requireDialog();
            Button button = dialog.findViewById(R.id.pageErrorRetry);
            assertTrue(button instanceof com.google.android.material.button.MaterialButton);
            assertTrue(button.isShown());
            pageReady.set(false);
            button.performClick();
            assertTrue(dialog.isShowing());
        });
    }

    private void awaitNativeSwitch() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> visible = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> {
                View button = web.get().getRootView().findViewById(R.id.pageErrorRetry);
                visible.set(button != null && button.isShown());
            });
            if (visible.get()) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Native switch action did not appear");
    }

    private void open(ActivityScenario<AboutActivity> scenario) {
        pageReady.set(false);
        scenario.onActivity(activity -> {
            CaptchaDialogFragment dialog = new CaptchaDialogFragment();
            dialog.showNow(activity.getSupportFragmentManager(), CaptchaDialogFragment.TAG);
            WebView view = dialog.requireDialog().findViewById(R.id.captchaWeb); web.set(view);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { pageReady.set(false); }
                @Override public void onPageFinished(WebView view, String url) { pageReady.set(true); }
                @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
                    String host = request.getUrl().getHost();
                    if (host == null) return null; // The bundled data document has no host.
                    String source = "";
                    if (host.equals("challenges.cloudflare.com")) {
                        source = "window.turnstile={render:function(el,o){document.querySelector(el).style.height='65px';"
                                + "window.fixtureSuccess=()=>o.callback('fixture-token');setTimeout(()=>o['unsupported-callback'](),50);return 'id'},reset:function(){}};";
                    } else if (host.equals("js.hcaptcha.com")) {
                        source = "window.hcaptcha={render:function(el,o){window.fixtureSuccess=()=>o.callback('fixture-token');"
                                + "setTimeout(()=>o['chalexpired-callback'](),50);return 'id'},reset:function(){}};setTimeout(()=>ftHCaptchaReady(),0);";
                    } else if (host.equals("ca.turing.captcha.qcloud.com")) {
                        source = "window.TencentCaptcha=function(el,key,cb,o){window.fixtureSuccess=()=>cb({ret:0,ticket:'fixture-ticket',randstr:'fixture-random'});"
                                + "this.show=()=>setTimeout(()=>cb({ret:1}),50);this.destroy=function(){}};";
                    }
                    return new WebResourceResponse("application/javascript", "UTF-8", new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
                }
            });
        });
    }

    private String evaluate(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> web.get().evaluateJavascript(script, text -> { value.set(text); done.countDown(); }));
        assertTrue(done.await(5, TimeUnit.SECONDS)); return value.get();
    }
    private JsonObject state() throws Exception {
        String json = new Gson().fromJson(evaluate("JSON.stringify(window.__ftCaptchaState || null)"), String.class);
        if (json == null || "null".equals(json)) return null;
        return new Gson().fromJson(json, JsonObject.class);
    }
    private JsonObject awaitEscape(String previousAttempt) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!pageReady.get()) { SystemClock.sleep(50); continue; }
            JsonObject current = state();
            if (current != null && current.get("escape").getAsBoolean()
                    && !current.get("attempt").getAsString().equals(previousAttempt)) return current;
            SystemClock.sleep(50);
        }
        throw new AssertionError("The fallback button did not appear");
    }
    private void capture(String name) throws Exception {
        SystemClock.sleep(500); instrumentation.waitForIdleSync();
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        File output = new File(instrumentation.getTargetContext().getFilesDir(), name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
    }
}
