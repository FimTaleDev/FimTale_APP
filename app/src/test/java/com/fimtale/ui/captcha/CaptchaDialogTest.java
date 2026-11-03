package com.fimtale.ui.captcha;

import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import android.view.View;
import android.widget.TextView;
import com.fimtale.R;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = com.fimtale.ResourceApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptchaDialogTest {
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private final List<String> available = Arrays.asList("turnstile", "hcaptcha", "tencent");

    @Before public void setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("fimtale_captcha", 0).edit().clear().commit();
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_Fimtale);
        activity = controller.setup().get();
    }
    @After public void cleanup() { controller.pause().stop().destroy(); }
    private CaptchaDialogFragment open() {
        CaptchaDialogFragment dialog = new CaptchaDialogFragment();
        dialog.showNow(activity.getSupportFragmentManager(), CaptchaDialogFragment.TAG);
        return dialog;
    }
    private Map<String, Object> state(CaptchaDialogFragment dialog) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("attempt", ReflectionHelpers.getField(dialog, "attempt"));
        state.put("provider", ReflectionHelpers.getField(dialog, "provider"));
        return state;
    }
    private void send(CaptchaDialogFragment dialog, Map<String, Object> state) { dialog.receive(new Gson().toJson(state)); }

    @Test public void rollsAllUntriedProvidersBeforeStartingAnotherRound() {
        List<String> tried = new ArrayList<>(); tried.add("turnstile");
        Random random = new Random(9);
        String next = CaptchaProviders.roll(available, tried, random);
        assertNotEquals("turnstile", next);
        String third = CaptchaProviders.roll(available, tried, random);
        assertNotEquals("turnstile", third); assertNotEquals(next, third);
        for (int round = 0; round < 20; round++) {
            CaptchaProviders.roll(available, tried, random);
            assertEquals(1, tried.size());
            CaptchaProviders.roll(available, tried, random);
            CaptchaProviders.roll(available, tried, random);
            assertEquals(3, new java.util.HashSet<>(tried).size());
        }
        tried.clear(); tried.add("tencent");
        assertEquals("tencent", CaptchaProviders.roll(Arrays.asList("tencent"), tried, random));
    }

    @Test public void failureSwitchesWithoutRememberingButSuccessIsUsedNextTime() {
        CaptchaDialogFragment dialog = open();
        assertEquals("turnstile", state(dialog).get("provider"));
        Map<String, Object> failure = state(dialog);
        failure.put("escape", true); send(dialog, failure);
        ((AlertDialog) dialog.requireDialog()).findViewById(R.id.pageErrorRetry).performClick();
        String switched = (String) state(dialog).get("provider");
        assertNotEquals("turnstile", switched);
        assertEquals("turnstile", CaptchaProviders.preferred(activity, available));
        AtomicInteger completed = new AtomicInteger();
        activity.getSupportFragmentManager().setFragmentResultListener(CaptchaDialogFragment.RESULT_KEY, activity, (key, result) -> {
            assertEquals("fixture-token", result.getString(CaptchaDialogFragment.TOKEN));
            assertEquals(switched, result.getString(CaptchaDialogFragment.PROVIDER));
            completed.incrementAndGet();
        });
        Map<String, Object> success = state(dialog); success.put("token", "fixture-token");
        send(dialog, success); send(dialog, success);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, completed.get());
        assertEquals(switched, CaptchaProviders.preferred(activity, available));
        assertEquals(switched, state(open()).get("provider"));
        assertEquals("turnstile", CaptchaProviders.preferred(activity, Arrays.asList("turnstile")));
    }

    @Test public void oldDocumentAndWrongProviderCannotCompleteTheCurrentChallenge() {
        CaptchaDialogFragment dialog = open();
        AtomicInteger completed = new AtomicInteger();
        activity.getSupportFragmentManager().setFragmentResultListener(CaptchaDialogFragment.RESULT_KEY, activity,
                (key, result) -> completed.incrementAndGet());
        Map<String, Object> stale = state(dialog);
        Map<String, Object> failure = state(dialog); failure.put("escape", true);
        send(dialog, failure);
        ((AlertDialog) dialog.requireDialog()).findViewById(R.id.pageErrorRetry).performClick();
        stale.put("token", "old-token"); send(dialog, stale);
        Map<String, Object> wrong = state(dialog); wrong.put("provider", "unknown"); wrong.put("token", "wrong-token");
        send(dialog, wrong);
        assertEquals(0, completed.get());
        assertTrue(dialog.requireDialog().isShowing());
        ((AlertDialog) dialog.requireDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, completed.get());
        assertEquals("turnstile", CaptchaProviders.preferred(activity, available));
    }

    @Test public void callerResultChannelSurvivesRecreationAndDoesNotNotifyAnotherCaller() {
        AtomicInteger login = new AtomicInteger(), editor = new AtomicInteger();
        activity.getSupportFragmentManager().setFragmentResultListener("login", activity, (key, result) -> login.incrementAndGet());
        activity.getSupportFragmentManager().setFragmentResultListener(CaptchaDialogFragment.RESULT_KEY, activity,
                (key, result) -> editor.incrementAndGet());
        CaptchaDialogFragment original = CaptchaDialogFragment.newInstance("login");
        original.showNow(activity.getSupportFragmentManager(), "login_dialog");
        android.content.res.Configuration rotated = new android.content.res.Configuration(activity.getResources().getConfiguration());
        rotated.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(rotated); activity = controller.get();
        activity.getSupportFragmentManager().setFragmentResultListener("login", activity, (key, result) -> login.incrementAndGet());
        activity.getSupportFragmentManager().setFragmentResultListener(CaptchaDialogFragment.RESULT_KEY, activity,
                (key, result) -> editor.incrementAndGet());
        CaptchaDialogFragment restored = (CaptchaDialogFragment) activity.getSupportFragmentManager().findFragmentByTag("login_dialog");
        Map<String, Object> success = state(restored); success.put("token", "fixture-token");
        send(restored, success); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, login.get()); assertEquals(0, editor.get());
    }

    @Test public void nativeMaterialControlsShowProgressAndErrorsAndSwitchWithoutDismissing() {
        CaptchaDialogFragment fragment = open();
        AlertDialog dialog = (AlertDialog) fragment.requireDialog();
        assertTrue(dialog.findViewById(R.id.pageErrorRetry) instanceof com.google.android.material.button.MaterialButton);
        assertEquals(View.GONE, dialog.findViewById(R.id.captchaError).getVisibility());
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.captchaProgress).getVisibility());
        Map<String, Object> loaded = state(fragment); loaded.put("loaded", true); send(fragment, loaded);
        assertEquals(View.GONE, dialog.findViewById(R.id.captchaStatusPanel).getVisibility());
        Map<String, Object> failure = state(fragment); failure.put("escape", true); failure.put("reason", "unsupported_browser");
        send(fragment, failure);
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.captchaError).getVisibility());
        assertEquals(View.GONE, dialog.findViewById(R.id.captchaProgress).getVisibility());
        assertEquals(activity.getString(R.string.captcha_unsupported), ((TextView) dialog.findViewById(R.id.captchaError).findViewById(R.id.pageErrorMessage)).getText().toString());
        dialog.findViewById(R.id.pageErrorRetry).performClick();
        assertTrue(dialog.isShowing());
        assertNotEquals("turnstile", state(fragment).get("provider"));
        assertEquals(View.GONE, dialog.findViewById(R.id.captchaError).getVisibility());
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.captchaProgress).getVisibility());
        send(fragment, failure);
        assertEquals(View.GONE, dialog.findViewById(R.id.captchaError).getVisibility());
    }
}
