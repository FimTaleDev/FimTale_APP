package com.fimtale.account;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.SavedStateHandle;
import android.app.Application;
import com.fimtale.LoginActivity;
import com.fimtale.R;
import com.fimtale.modules.*;
import com.fimtale.utils.UserPreferences;
import com.google.gson.JsonObject;
import static com.fimtale.modules.ModuleApi.*;

/** Completes the existing reset-email flow without leaving the app. Secrets only live in the dialog/request. */
public class PasswordResetActivity extends ModuleActivity {
    public static final String EXTRA_RETURN_TO_LOGIN = "return_to_login";
    public static class ResetModel extends ModuleModel {
        boolean complete;
        public ResetModel(Application app, SavedStateHandle state) { super(app, state); complete = Boolean.TRUE.equals(state.get("complete")); }
        @Override protected boolean requiresLogin() { return false; }
        @Override protected void reset() { }
        void submit(JsonObject body) {
            post("reset", Route.RESET_PASSWORD, body, value -> {
                UserPreferences.clearSession(getApplication()); complete = true; saved.set("complete",true); finishAction();
            });
        }
    }
    private ResetModel state;
    @Override protected void onCreate(Bundle saved) { super.onCreate(saved); state = new ViewModelProvider(this).get(ResetModel.class); bind(state,R.string.account_reset_password); }
    @Override public void finish() {
        if (state != null && state.complete && getIntent().getBooleanExtra(EXTRA_RETURN_TO_LOGIN,false)) setResult(RESULT_OK);
        super.finish();
    }
    @Override protected void refresh() { scheduleRender(); }
    @Override protected void render() {
        errors(); text(content,state.complete ? R.string.account_reset_done : R.string.account_reset_hint);
        if (!state.complete) button(content,R.string.account_reset_password,"reset",false,false, () -> form("reset", R.string.account_reset_password,
                ModuleFormDialog.field("token",R.string.account_reset_token,""),
                ModuleFormDialog.field("password",R.string.account_new_password,"", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD),
                ModuleFormDialog.field("confirm_password",R.string.account_confirm_password,"", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)));
        button(content,R.string.login_title,"login",false,false, () -> {
            if (getIntent().getBooleanExtra(EXTRA_RETURN_TO_LOGIN,false)) setResult(RESULT_OK);
            else startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }
    @Override protected boolean onForm(String key, JsonObject values) {
        String token = AccountActivity.mailToken(str(values,"token"),"/user/reset_password",0);
        if (token.isEmpty()) { toast(R.string.account_invalid_token); return false; }
        if (!AccountActivity.passwordValid(values,false)) { toast(R.string.account_reset_password_invalid); return false; }
        state.submit(object("token",token,"password",str(values,"password"))); return true;
    }
}
