package com.fimtale.crash;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import androidx.fragment.app.FragmentActivity;
import com.fimtale.FimTaleApplication;
import com.fimtale.LoginActivity;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Captures crashes early and offers feedback on the first usable foreground activity after restart. */
public final class CrashFeedback implements Application.ActivityLifecycleCallbacks {
    private final Application app;
    private final CrashStore store;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CrashReport.Environment environment;
    private volatile String screen = "";
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private final List<Runnable> afterCheck = new ArrayList<>();
    private CrashReport pending;
    private boolean ready, presented;

    CrashFeedback(Application app, CrashStore store, Executor worker) {
        this.app = app; this.store = store; this.worker = worker; environment = CrashReport.Environment.current(app);
    }
    public static CrashFeedback install(Application app) {
        CrashFeedback feedback = new CrashFeedback(app, new CrashStore(app), Executors.newSingleThreadExecutor());
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(feedback.store, feedback.environment, () -> feedback.screen,
                Thread.getDefaultUncaughtExceptionHandler(), () -> { Process.killProcess(Process.myPid()); System.exit(10); }));
        feedback.start(); return feedback;
    }
    void start() {
        app.registerActivityLifecycleCallbacks(this);
        worker.execute(() -> {
            List<CrashStore.Exit> exits = new ArrayList<>();
            try {
                ActivityManager manager = app.getSystemService(ActivityManager.class);
                if (manager != null) for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(app.getPackageName(), 0, 16))
                    exits.add(new CrashStore.Exit(exit.getTimestamp(), exit.getPid(), exit.getReason(), exit.getProcessName()));
            } catch (RuntimeException ignored) {}
            CrashReport report;
            try { report = store.beginLaunch(environment, exits); }
            catch (IOException | RuntimeException ignored) { report = store.pending(); }
            CrashReport result = report;
            main.post(() -> {
                pending = result; ready = true; showIfReady();
                for (Runnable action : afterCheck) action.run();
                afterCheck.clear();
            });
        });
    }
    /** Give crash recovery priority over the automatic update prompt for this launch. */
    public static void whenNoPendingReport(Activity activity, Runnable action) {
        Application app = activity.getApplication();
        CrashFeedback feedback = app instanceof FimTaleApplication ? ((FimTaleApplication) app).getCrashFeedback() : null;
        if (feedback == null) { action.run(); return; }
        Runnable guarded = () -> {
            if (!activity.isFinishing() && !activity.isDestroyed() && feedback.pending == null) action.run();
        };
        if (feedback.ready) {
            feedback.showIfReady();
            guarded.run();
        } else feedback.afterCheck.add(guarded);
    }
    private void showIfReady() {
        Activity activity = resumed.get();
        if (!ready || presented || pending == null || !(activity instanceof FragmentActivity)
                || activity instanceof LoginActivity || activity.isFinishing() || activity.isDestroyed()) return;
        // Present recovery after the launch screen hands control to the page.
        if (activity.findViewById(com.fimtale.R.id.startupScreen) != null) return;
        FragmentActivity host = (FragmentActivity) activity;
        if (host.getSupportFragmentManager().isStateSaved()) return;
        presented = true;
        CrashFeedbackDialog.show(host, pending);
    }
    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        screen = activity.getClass().getSimpleName();
        String currentScreen = screen;
        worker.execute(() -> { try { store.rememberScreen(environment, currentScreen); } catch (IOException | RuntimeException ignored) {} });
        main.post(this::showIfReady);
    }
    @Override public void onActivityPaused(Activity activity) { if (resumed.get() == activity) resumed.clear(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) { if (resumed.get() == activity) resumed.clear(); }
}
