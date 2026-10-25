package com.fimtale;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.auth.LoginViewModel;
import com.fimtale.auth.LoginViewModel.Mode;
import com.fimtale.ui.captcha.CaptchaDialogHost;
import com.fimtale.utils.EditorWindowStyle;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;
import java.util.function.Consumer;

/** Native account forms. Only the vendor challenge inside the shared dialog uses web content. */
public class LoginActivity extends AppCompatActivity {
    public static final String CAPTCHA_RESULT = "login_captcha_result";
    private LoginViewModel model;
    private CaptchaDialogHost captcha;
    private TabLayout tabs;
    private EditText account, email, emailConfirmation, password, passwordConfirmation, code;
    private boolean binding;
    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> resetPassword = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                if (model != null && result.getResultCode() == RESULT_OK) model.chooseMode(Mode.PASSWORD);
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_login);
        EditorWindowStyle.apply(this);
        model = new ViewModelProvider(this).get(LoginViewModel.class);
        if (!model.formRestored && state != null) {
            model.account = state.getString("account", "");
            model.email = state.getString("email", "");
            model.emailConfirmation = state.getString("email_confirmation", "");
            try { model.mode = Mode.valueOf(state.getString("mode", "PASSWORD")); }
            catch (IllegalArgumentException ignored) { model.mode = Mode.PASSWORD; }
        }
        model.formRestored = true;
        captcha = new CaptchaDialogHost(this, CAPTCHA_RESULT, () -> model.captchaRequested, model::captchaResult);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> back());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        View form = findViewById(R.id.loginForm);
        float density = getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams params = form.getLayoutParams();
        params.width = (int) (Math.min(480, getResources().getConfiguration().screenWidthDp - 48) * density);
        form.setLayoutParams(params);
        account = field(R.id.loginAccount, value -> model.account = value);
        email = field(R.id.loginEmail, value -> model.email = value);
        emailConfirmation = field(R.id.loginEmailConfirmation, value -> model.emailConfirmation = value);
        password = field(R.id.loginPassword, value -> model.password = value);
        passwordConfirmation = field(R.id.loginPasswordConfirmation, value -> model.passwordConfirmation = value);
        code = field(R.id.loginCode, value -> model.code = value);
        tabs = findViewById(R.id.loginTabs);
        for (int label : new int[]{R.string.login_password_tab, R.string.login_email_tab, R.string.login_register_tab})
            tabs.addTab(tabs.newTab().setText(label));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (!binding) model.chooseMode(Mode.values()[tab.getPosition()]);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        findViewById(R.id.loginSubmit).setOnClickListener(v -> model.submit());
        findViewById(R.id.loginSendEmail).setOnClickListener(v -> model.sendEmail());
        findViewById(R.id.loginForgot).setOnClickListener(v -> model.chooseMode(Mode.RESET));
        findViewById(R.id.loginReturn).setOnClickListener(v -> model.chooseMode(Mode.PASSWORD));
        findViewById(R.id.loginCompleteReset).setOnClickListener(v ->
                resetPassword.launch(new android.content.Intent(this, com.fimtale.account.PasswordResetActivity.class)
                        .putExtra(com.fimtale.account.PasswordResetActivity.EXTRA_RETURN_TO_LOGIN, true)));
        model.changes.observe(this, ignored -> render());
    }

    private EditText field(int id, Consumer<String> update) {
        EditText input = findViewById(id);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!binding) update.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { model.submit(); return true; }
            return false;
        });
        return input;
    }

    private void render() {
        binding = true;
        setText(account, model.account); setText(email, model.email); setText(emailConfirmation, model.emailConfirmation);
        setText(password, model.password); setText(passwordConfirmation, model.passwordConfirmation); setText(code, model.code);
        boolean registration = model.mode == Mode.REGISTER, mail = model.mode == Mode.EMAIL, reset = model.mode == Mode.RESET;
        tabs.setVisibility(reset ? View.GONE : View.VISIBLE);
        if (!reset && tabs.getSelectedTabPosition() != model.mode.ordinal()) tabs.selectTab(tabs.getTabAt(model.mode.ordinal()));
        visible(R.id.loginAccountLayout, !mail && !reset);
        ((TextInputLayout) findViewById(R.id.loginAccountLayout)).setHint(getString(registration ? R.string.login_username : R.string.login_account));
        password.setAutofillHints(registration ? "newPassword" : View.AUTOFILL_HINT_PASSWORD);
        visible(R.id.loginPasswordLayout, !mail && !reset);
        visible(R.id.loginEmailLayout, registration || mail || reset);
        visible(R.id.loginEmailConfirmationLayout, registration);
        visible(R.id.loginPasswordConfirmationLayout, registration);
        visible(R.id.loginCodeLayout, mail);
        visible(R.id.loginSendEmail, mail);
        visible(R.id.loginForgot, model.mode == Mode.PASSWORD);
        visible(R.id.loginReturn, reset);
        visible(R.id.loginCompleteReset, reset);
        visible(R.id.loginExplanation, model.mode != Mode.PASSWORD);
        ((TextView) findViewById(R.id.loginExplanation)).setText(registration ? R.string.login_register_explanation
                : reset ? R.string.login_reset_explanation : R.string.login_email_explanation);
        ((TextView) findViewById(R.id.loginSubmit)).setText(registration ? R.string.login_register_tab
                : reset ? R.string.login_send_reset : R.string.login_action);
        visible(R.id.loginMessage, !model.message.isEmpty());
        TextView message = findViewById(R.id.loginMessage);
        message.setText(model.message);
        message.setTextColor(MaterialColors.getColor(message, model.error ? com.google.android.material.R.attr.colorError
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        visible(R.id.loginProgress, model.busy);
        enabled(findViewById(R.id.loginForm), !model.busy);
        binding = false;
        if (model.busy) WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).hide(WindowInsetsCompat.Type.ime());
        captcha.sync();
        if (model.loggedIn && !isFinishing()) {
            Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK); finish();
        }
    }

    private void setText(EditText field, String text) {
        if (!text.contentEquals(field.getText())) { field.setText(text); field.setSelection(field.length()); }
    }
    private void visible(int id, boolean show) { findViewById(id).setVisibility(show ? View.VISIBLE : View.GONE); }
    private void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            enabled(((ViewGroup) view).getChildAt(i), enabled);
    }
    private void back() {
        if (model.mode == Mode.RESET && !model.busy) model.chooseMode(Mode.PASSWORD);
        else finish();
    }
    @Override protected void onPostResume() { super.onPostResume(); captcha.sync(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        // Passwords, email codes and captcha tokens never enter saved instance state.
        out.putString("account", model.account); out.putString("email", model.email);
        out.putString("email_confirmation", model.emailConfirmation); out.putString("mode", model.mode.name());
        super.onSaveInstanceState(out);
    }
}
