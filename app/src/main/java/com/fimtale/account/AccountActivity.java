package com.fimtale.account;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;
import com.bumptech.glide.Glide;
import com.fimtale.*;
import com.fimtale.modules.*;
import com.fimtale.network.SiteUrls;
import com.fimtale.ui.captcha.CaptchaDialogHost;
import com.google.gson.*;
import java.time.LocalDate;
import java.util.*;
import static com.fimtale.modules.ModuleApi.*;

public class AccountActivity extends ModuleActivity {
    public static final String EXTRA_TAB = "account_tab";
    private AccountModel state;
    private CaptchaDialogHost captcha;
    private boolean pickingAvatar;
    private String pickerOwner = "";
    private final ActivityResultLauncher<String> picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        if (uri != null && state != null && !state.busy()) {
            if (pickerOwner.equals(com.fimtale.utils.UserPreferences.getUserId(this))) state.upload(uri, pickingAvatar);
            else toast(R.string.error_account_changed);
        }
    });
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); state = new ViewModelProvider(this).get(AccountModel.class);
        if (saved == null && getIntent().hasExtra(EXTRA_TAB)) state.select(getIntent().getIntExtra(EXTRA_TAB, 0));
        pickingAvatar = saved == null || saved.getBoolean("avatar", true);
        pickerOwner = saved == null ? "" : saved.getString("picker_owner", "");
        bind(state, R.string.account_title); tabLabels(R.array.account_tabs, state.tab, state::select); centerTabs(); centerPagers();
        captcha = new CaptchaDialogHost(this, "account.captcha", () -> state.captchaRequested, state::captcha);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (state.busy()) { toast(R.string.common_loading_active); return; }
                if (state.dirty || state.badgeDraft.size() > 0) confirm(getString(R.string.modules_unsaved), () -> finish()); else finish();
            }
        });
        startPage();
    }
    @Override protected void refresh() { state.refresh(); }
    @Override protected boolean usesHeaderLoadingIndicator() { return false; }
    @Override protected boolean supportsPullToRefresh() { return true; }
    @Override protected boolean usesToolbarRefresh() { return false; }
    @Override protected void onPostResume() { super.onPostResume(); if (captcha != null) captcha.sync(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("avatar", pickingAvatar); out.putString("picker_owner", pickerOwner); }
    @Override protected void render() {
        if (state.passwordChanged) { state.passwordChanged = false; toast(R.string.account_password_changed); startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        if (loginRequired()) return; selectTab(state.tab); errors();
        if (state.loading("header") && num(state.user, "id") == 0) { loadingBlocks(3); return; }
        if (num(state.user, "id") == 0) return;
        if (state.tab == 0) profile(); else if (state.tab == 1) security(); else badges();
        if (captcha != null) captcha.sync();
    }
    private void profile() {
        image("avatar", R.string.account_avatar, true); image("photo", R.string.account_photo, false);
        field(content, R.string.account_intro, str(state.draft,"intro"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, v -> state.edit("intro",v));
        field(content, R.string.account_homepage, str(state.draft,"home_page"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, v -> state.edit("home_page",v));
        heading(content, getString(R.string.account_sex));
        select(content, Arrays.asList(getResources().getStringArray(R.array.account_sexes)), num(state.draft,"sex"), state::editSex);
        String birthday = str(state.draft,"birthday");
        if (birthday.startsWith("0001-")) birthday = ""; else if (birthday.length() > 10) birthday = birthday.substring(0,10);
        field(content, R.string.account_birthday, birthday, InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE, v -> state.edit("birthday",v));
        button(content, R.string.common_save, "profile", false, true, () -> {
            String home = str(state.draft,"home_page").trim(); if (!home.isEmpty() && !webUrl(home)) { toast(R.string.modules_invalid_url); return; }
            state.edit("home_page", home);
            String date = str(state.draft,"birthday").trim();
            if (date.length() > 10) date = date.substring(0,10);
            try {
                if (!date.isEmpty() && (date.length() != 10 || LocalDate.parse(date).isAfter(LocalDate.now()))) throw new IllegalArgumentException();
            } catch (RuntimeException failure) { toast(R.string.account_invalid_birthday); return; }
            state.edit("birthday", date.isEmpty() ? "0001-01-01T00:00:00Z" : date + "T00:00:00Z"); state.saveProfile();
        });
    }
    private void image(String key, int title, boolean avatar) {
        heading(content, getString(title)); ImageView view = new ImageView(this); view.setScaleType(ImageView.ScaleType.CENTER_CROP); view.setContentDescription(getString(title));
        content.addView(view, new LinearLayout.LayoutParams(avatar ? dp(88) : -1, dp(avatar ? 88 : 140)));
        Glide.with(this).load(SiteUrls.media(str(state.draft,key))).placeholder(com.fimtale.utils.MdiIcons.drawable(this, avatar ? "account-circle" : "image-outline")).into(view);
        LinearLayout line = row(content);
        button(line, avatar ? R.string.account_upload_avatar : R.string.account_upload_photo, key, false, true, () -> {
            pickingAvatar = avatar; pickerOwner = com.fimtale.utils.UserPreferences.getUserId(this); picker.launch("image/*");
        });
        button(line, avatar ? R.string.account_reset_avatar : R.string.account_reset_photo, "reset_" + key, false, true, () -> { state.edit(key, ""); scheduleRender(); });
    }
    private void security() {
        heading(content, str(state.user,"username"));
        button(content, R.string.account_change_username, Route.USERNAME.name(), false, true, () -> form("username", R.string.account_change_username,
                ModuleFormDialog.field("username", R.string.account_username, str(state.user,"username"))));
        text(content, getString(R.string.account_email_status, str(state.auth,"email"), getString(bool(state.auth,"email_valid") ? R.string.account_email_valid : R.string.account_email_invalid)));
        button(content, R.string.account_change_email, Route.EMAIL.name(), false, true, () -> form("email", R.string.account_change_email,
                ModuleFormDialog.field("email", R.string.account_email, str(state.auth,"email"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)));
        if (!bool(state.auth,"email_valid")) {
            button(content, R.string.account_send_verify, "verify", false, true, state::requestVerification);
            button(content, R.string.account_verify, "verify", false, true, () -> form("verify", R.string.account_verify, ModuleFormDialog.field("token", R.string.account_verify_token, "")));
        }
        text(content, R.string.account_password_hint);
        button(content, R.string.account_change_password, Route.PASSWORD.name(), false, true, () -> form("password", R.string.account_change_password,
                ModuleFormDialog.field("old_password", R.string.account_old_password, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD),
                ModuleFormDialog.field("password", R.string.account_new_password, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD),
                ModuleFormDialog.field("confirm_password", R.string.account_confirm_password, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)));
        button(content, R.string.sessions_title, "sessions", false, true, () -> startActivity(new Intent(this, AccountSessionsActivity.class)));
    }
    private void badges() {
        if (state.loading("badges")) return;
        JsonObject material = obj(state.user,"material"); JsonArray owned = array(material,"badges"), off = array(material,"off_badges");
        List<JsonObject> races = new ArrayList<>(), normal = new ArrayList<>(); List<String> names = new ArrayList<>(); names.add(getString(R.string.account_no_race));
        int selected = 0;
        for (JsonElement e : state.badges) { JsonObject badge = obj(e);
            if (str(badge,"type").equals("race")) { races.add(badge); names.add(str(badge,"name")); if (contains(owned,num(badge,"id"))) selected = races.size(); }
            else if (contains(owned,num(badge,"id"))) normal.add(badge);
        }
        if (state.badgeDraft.has("race")) {
            selected = 0;
            for (int i = 0; i < races.size(); i++) if (num(races.get(i),"id") == num(state.badgeDraft,"race")) selected = i + 1;
        }
        heading(content, getString(R.string.account_race)); final int[] race = {selected};
        select(content, names, selected, value -> { race[0] = value; state.editRace(value == 0 ? 0 : num(races.get(value-1),"id")); });
        button(content, R.string.account_save_race, Route.SET_BADGES.name(), false, true, () -> {
            JsonArray next = new JsonArray();
            for (JsonElement id : owned) { boolean raceBadge = false; for (JsonObject b : races) if (num(b,"id") == id.getAsInt()) raceBadge = true; if (!raceBadge) next.add(id); }
            if (race[0] > 0) next.add(num(races.get(race[0]-1),"id")); state.saveBadges(next, false);
        });
        heading(content, getString(R.string.account_badges)); text(content, R.string.account_badges_hint);
        Map<Integer, Boolean> visible = new LinkedHashMap<>();
        for (JsonObject badge : normal) {
            int id = num(badge,"id"); CheckBox check = new CheckBox(this); check.setText(str(badge,"name")); check.setChecked(!contains(off,id)); check.setEnabled(!state.busy());
            JsonObject pending = obj(state.badgeDraft,"visibility");
            if (pending.has(Integer.toString(id))) check.setChecked(bool(pending,Integer.toString(id)));
            visible.put(id, check.isChecked()); check.setOnCheckedChangeListener((v, checked) -> { visible.put(id,checked); state.editBadge(id,checked); }); content.addView(check);
        }
        if (normal.isEmpty()) text(content, R.string.modules_empty);
        button(content, R.string.account_save_badges, Route.BADGE_VISIBILITY.name(), false, true, () -> {
            JsonArray hidden = new JsonArray(); for (JsonElement id : off) if (!visible.containsKey(id.getAsInt())) hidden.add(id);
            visible.forEach((id, shown) -> { if (!shown) hidden.add(id); }); state.saveBadges(hidden,true);
        });
    }
    @Override protected boolean onForm(String key, JsonObject values) {
        if (key.equals("username")) {
            String username = str(values,"username").trim(); if (username.isEmpty()) { toast(R.string.modules_required); return false; }
            state.change(Route.USERNAME, object("username",username));
        } else if (key.equals("email")) {
            String email = str(values,"email").trim(); if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { toast(R.string.account_invalid_email); return false; }
            state.change(Route.EMAIL, object("email",email));
        } else if (key.equals("password")) {
            if (!passwordValid(values, true)) { toast(R.string.account_password_invalid); return false; }
            state.change(Route.PASSWORD, object("old_password",str(values,"old_password"), "password",str(values,"password")));
        } else if (key.equals("verify")) {
            String token = mailToken(str(values,"token"), "/user/verify_email", num(state.user,"id"));
            if (token.isEmpty()) { toast(R.string.account_invalid_token); return false; } state.verify(token);
        } else return false;
        return true;
    }
    public static boolean passwordValid(JsonObject values, boolean oldRequired) {
        String password = str(values,"password"); int length = password.codePointCount(0, password.length());
        return (!oldRequired || !str(values,"old_password").isEmpty())
                && length >= 6 && length <= 100 && password.equals(str(values,"confirm_password"));
    }
    public static boolean webUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException failure) { return false; }
    }
    public static String mailToken(String value, String path, int userId) {
        value = value.trim(); if (value.isEmpty()) return "";
        if (!value.contains("://")) return value.matches("[^\\s/?#]+") ? value : "";
        Uri uri = Uri.parse(value); if (!SiteUrls.isSite(value) || !path.equals(uri.getPath())) return "";
        if (userId > 0 && !Integer.toString(userId).equals(uri.getQueryParameter("user_id"))) return "";
        String token = uri.getQueryParameter("token"); return token == null ? "" : token;
    }
}
