package com.fimtale;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.fimtale.model.LogoutRequest;
import com.fimtale.model.UserSession;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Native account/session management backed by the current user API. */
public class AccountSessionsActivity extends AppCompatActivity {
    private TextView summary;
    private com.fimtale.ui.PageErrorView empty;
    private LinearLayout sessions;
    private CircularProgressIndicator progress;
    private TextInputState tokenView;
    private Call<List<UserSession>> sessionsCall;
    private Call<String> createTokenCall;
    private Call<Void> logoutCall;
    private ValueAnimator headerAnimator;
    private boolean headerRaised;
    private boolean closed;
    private com.fimtale.ui.PageErrorView pageError;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_account_sessions);
        com.fimtale.utils.EditorWindowStyle.apply(this);
        MaterialToolbar toolbar = findViewById(R.id.accountToolbar);
        toolbar.setTitle("");
        toolbar.setBackground(null);
        ((TextView) findViewById(R.id.accountToolbarTitle)).setText(getString(R.string.sessions_title));
        toolbar.setNavigationOnClickListener(v -> finish());
        ScrollView scroll = findViewById(R.id.accountScroll);
        pageError = com.fimtale.ui.PageErrorView.wrap(scroll);
        View titleCard = findViewById(R.id.accountToolbarContainer);
        titleCard.addOnLayoutChangeListener((v, l, t, r, b, oldLeft, oldTop, oldRight, oldBottom) -> {
            int top = b + dp(16);
            if (scroll.getPaddingTop() != top) {
                scroll.setPadding(scroll.getPaddingLeft(), top, scroll.getPaddingRight(), scroll.getPaddingBottom());
            }
        });
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                animateHeader(scroll.canScrollVertically(-1)));
        summary = findViewById(R.id.accountSummary);
        sessions = findViewById(R.id.accountSessions);
        empty = findViewById(R.id.accountEmpty);
        progress = findViewById(R.id.accountProgress);
        tokenView = new TextInputState(findViewById(R.id.accountTokenLayout), findViewById(R.id.accountToken),
                findViewById(R.id.accountCopyToken));
        findViewById(R.id.accountRefresh).setOnClickListener(v -> loadSessions());
        com.fimtale.ui.PullToRefresh.attach(scroll, this::loadSessions,
                () -> !closed && sessionsCall == null && logoutCall == null && createTokenCall == null);
        findViewById(R.id.accountCreateToken).setOnClickListener(v -> createApiToken());
        tokenView.copy.setOnClickListener(v -> copyToken());
        if (!UserPreferences.isLoggedIn(this)) {
            startActivity(new android.content.Intent(this, LoginActivity.class));
            finish();
            return;
        }
        String username = UserPreferences.getUserName(this);
        summary.setText(TextUtils.isEmpty(username) ? getString(R.string.profile_logged_in) : getString(R.string.sessions_current_account, username));
        loadSessions();
    }

    private void loadSessions() {
        if (closed) return;
        pageError.hide();
        if (sessionsCall != null) sessionsCall.cancel();
        setLoading(true);
        String token = UserPreferences.getToken(this);
        sessionsCall = RetrofitClient.getInstance().getActiveSessions(token);
        sessionsCall.enqueue(new Callback<List<UserSession>>() {
            @Override public void onResponse(@NonNull Call<List<UserSession>> call, @NonNull Response<List<UserSession>> response) {
                if (!valid(call, sessionsCall)) return;
                setLoading(false); sessionsCall = null;
                if (response.isSuccessful()) renderSessions(response.body());
                else pageError.show(ApiErrors.message(response), AccountSessionsActivity.this::loadSessions, sessions.getChildCount() > 0);
            }
            @Override public void onFailure(@NonNull Call<List<UserSession>> call, @NonNull Throwable error) {
                if (!valid(call, sessionsCall)) return;
                setLoading(false); sessionsCall = null;
                pageError.show(null, AccountSessionsActivity.this::loadSessions, sessions.getChildCount() > 0);
            }
        });
    }

    private boolean valid(Call<?> call, Call<?> current) {
        return !closed && !isFinishing() && !isDestroyed() && !call.isCanceled() && call == current;
    }

    private void renderSessions(List<UserSession> values) {
        sessions.removeAllViews();
        List<UserSession> list = values == null ? Collections.emptyList() : values;
        if (list.isEmpty()) empty.showEmpty(getString(R.string.sessions_empty), this::loadSessions);
        else empty.hide();
        String currentToken = UserPreferences.getToken(this);
        for (UserSession session : list) {
            if (session == null || TextUtils.isEmpty(session.tokenPrefix)) continue;
            boolean current = currentToken.startsWith(session.tokenPrefix);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(12); row.setPadding(pad, pad, 0, pad);
            LinearLayout details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL);
            TextView label = new TextView(this); label.setText(current ? getString(R.string.sessions_current_token, session.tokenPrefix) : session.tokenPrefix);
            label.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            TextView activity = new TextView(this); activity.setText(getString(R.string.sessions_last_active, formatDate(session.lastActivity)));
            activity.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            details.addView(label); details.addView(activity);
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton logout = new MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
            logout.setText(getString(R.string.sessions_logout)); logout.setContentDescription(getString(R.string.sessions_logout_description, session.tokenPrefix));
            logout.setOnClickListener(v -> logout(session));
            row.addView(logout, new LinearLayout.LayoutParams(-2, -2));
            sessions.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View divider = new View(this); divider.setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant));
            sessions.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void logout(UserSession session) {
        if (logoutCall != null || session == null) return;
        String token = UserPreferences.getToken(this);
        boolean current = !TextUtils.isEmpty(session.tokenPrefix) && token.startsWith(session.tokenPrefix);
        logoutCall = RetrofitClient.getInstance().logoutSession(token, new LogoutRequest(session.tokenPrefix));
        logoutCall.enqueue(new Callback<Void>() {
            @Override public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                if (!valid(call, logoutCall)) return;
                logoutCall = null;
                if (response.isSuccessful()) {
                    if (current) { UserPreferences.clearSession(AccountSessionsActivity.this); finish(); }
                    else loadSessions();
                } else showError(ApiErrors.message(response));
            }
            @Override public void onFailure(@NonNull Call<Void> call, @NonNull Throwable error) {
                if (!valid(call, logoutCall)) return;
                logoutCall = null; showError(getString(R.string.sessions_logout_failed));
            }
        });
    }

    private void createApiToken() {
        if (createTokenCall != null) return;
        String token = UserPreferences.getToken(this);
        findViewById(R.id.accountCreateToken).setEnabled(false);
        createTokenCall = RetrofitClient.getInstance().createApiKey(token);
        createTokenCall.enqueue(new Callback<String>() {
            @Override public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                if (!valid(call, createTokenCall)) return;
                createTokenCall = null; findViewById(R.id.accountCreateToken).setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    tokenView.set(response.body()); loadSessions();
                } else showError(ApiErrors.message(response));
            }
            @Override public void onFailure(@NonNull Call<String> call, @NonNull Throwable error) {
                if (!valid(call, createTokenCall)) return;
                createTokenCall = null; findViewById(R.id.accountCreateToken).setEnabled(true);
                showError(getString(R.string.sessions_token_failed));
            }
        });
    }

    private void copyToken() {
        String value = tokenView.input.getText() == null ? "" : tokenView.input.getText().toString();
        if (value.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.sessions_token_clipboard_label), value));
            android.widget.Toast.makeText(this, getString(R.string.sessions_token_copied), android.widget.Toast.LENGTH_SHORT).show(); }
    }

    private void setLoading(boolean loading) {
        View scroll = findViewById(R.id.accountScroll);
        if (!loading) com.fimtale.ui.PullToRefresh.finish(scroll);
        progress.setVisibility(loading && !com.fimtale.ui.PullToRefresh.isRefreshing(scroll) ? View.VISIBLE : View.GONE);
    }
    private void animateHeader(boolean raised) {
        if (headerRaised == raised) return;
        headerRaised = raised;
        if (headerAnimator != null) headerAnimator.cancel();
        View surface = findViewById(R.id.accountHeaderSurface);
        headerAnimator = ValueAnimator.ofFloat(surface.getAlpha(), raised ? 1f : 0f);
        headerAnimator.setDuration(200);
        headerAnimator.addUpdateListener(animation -> surface.setAlpha((float) animation.getAnimatedValue()));
        headerAnimator.start();
    }
    private void showError(String message) { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int resolveColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true); return value.data;
    }
    private String formatDate(String value) {
        if (TextUtils.isEmpty(value)) return getString(R.string.common_unknown);
        try { return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(getString(R.string.common_date_time))); }
        catch (RuntimeException ignored) { return value; }
    }

    @Override protected void onDestroy() {
        closed = true;
        if (sessionsCall != null) sessionsCall.cancel();
        if (createTokenCall != null) createTokenCall.cancel();
        if (logoutCall != null) logoutCall.cancel();
        if (headerAnimator != null) headerAnimator.cancel();
        super.onDestroy();
    }

    private static final class TextInputState {
        final View layout; final com.google.android.material.textfield.TextInputEditText input; final View copy;
        TextInputState(View layout, com.google.android.material.textfield.TextInputEditText input, View copy) {
            this.layout = layout; this.input = input; this.copy = copy;
        }
        void set(String value) { input.setText(value); layout.setVisibility(View.VISIBLE); copy.setVisibility(View.VISIBLE); }
    }
}
