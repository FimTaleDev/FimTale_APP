package com.fimtale.channels;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.lifecycle.ViewModelProvider;
import com.bumptech.glide.Glide;
import com.fimtale.LoginActivity;
import com.fimtale.R;
import com.fimtale.account.ExamActivity;
import com.fimtale.community.UserContentActivity;
import com.fimtale.modules.ModuleActivity;
import com.fimtale.network.SiteUrls;
import com.fimtale.utils.MdiIcons;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.*;
import static com.fimtale.modules.ModuleApi.*;

/** Native discovery, subscriptions and creation entry for channels. */
public class ChannelDirectoryActivity extends ModuleActivity {
    public static final String EXTRA_CREATE = "create_channel";
    private ChannelDirectoryModel state;
    private boolean wantCreate;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = new ViewModelProvider(this).get(ChannelDirectoryModel.class);
        wantCreate = saved == null ? getIntent().getBooleanExtra(EXTRA_CREATE, false) : saved.getBoolean("want_create");
        bind(state, R.string.channels_title);
        MenuItem actionsMenu = toolbar.getMenu().add(R.string.common_more);
        actionsMenu.setIcon(MdiIcons.drawable(this, "dots-vertical"));
        actionsMenu.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        actionsMenu.setOnMenuItemClickListener(item -> {
            boolean signedIn = state.signedIn();
            String[] actions = signedIn
                    ? new String[]{getString(R.string.channel_create), getString(R.string.channel_my_channels)}
                    : new String[]{getString(R.string.channel_create)};
            menu(actions, selected -> {
                if (selected == 0) create();
                else startActivity(UserContentActivity.intent(this, UserPreferences.getUserName(this), "channel"));
            });
            return true;
        });
        tabLabels(R.array.channel_directory_tabs, state.tab, state::select);
        centerTabs(); centerPagers();
        startPage();
    }
    @Override protected void refresh() { state.refresh(); }
    @Override protected boolean usesHeaderLoadingIndicator() { return false; }
    @Override protected boolean supportsPullToRefresh() { return true; }
    @Override protected boolean usesToolbarRefresh() { return false; }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("want_create", wantCreate); }
    @Override protected void render() {
        selectTab(state.tab); errors();
        if (state.createdId > 0 && !state.busy()) {
            int id = state.createdId; state.createdId = 0; wantCreate = false;
            startActivity(ChannelActivity.intent(this, id)); return;
        }
        if (state.signedIn()) {
            if (state.auth.size() > 0 && !state.canCreate()) {
                text(content, R.string.channel_create_exam);
                button(content, R.string.exam_title, "exam", false, false, () -> startActivity(new Intent(this, ExamActivity.class)));
            }
        }
        if (state.tab == 1 && loginRequired()) return;
        if (state.tab == 0) renderSearchBar();
        if (state.channels.size() == 0 && state.loading("page") && !state.errors.containsKey("page")) { loadingBlocks(3); return; }
        if (!state.loading("page") && state.channels.size() == 0 && !state.errors.containsKey("page")) text(content, R.string.modules_empty);
        for (JsonElement value : state.channels) {
            JsonObject channel = obj(value); int id = num(channel, "id"); if (id <= 0) continue;
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(16)); card.setCardElevation(0); card.setStrokeWidth(0);
            card.setCardBackgroundColor(getColor(R.color.card_background));
            card.setFocusable(true); card.setEnabled(!state.busy());
            card.setOnClickListener(v -> {
                if (!state.busy()) startActivity(ChannelActivity.intent(this, id));
            });
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.setMargins(dp(8), dp(4), dp(8), dp(12)); content.addView(card, cardParams);
            LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); card.addView(layout);
            String cover = str(channel, "cover_image");
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(image, com.google.android.material.R.attr.colorSurfaceVariant));
            image.setContentDescription(getString(R.string.channel_cover_image, str(channel, "name")));
            layout.addView(image, new LinearLayout.LayoutParams(-1, dp(200)));
            Glide.with(this).load(SiteUrls.media(cover)).placeholder(MdiIcons.drawable(this, "image-outline"))
                    .error(MdiIcons.drawable(this, "image-outline")).into(image);
            LinearLayout details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(16), dp(12), dp(16), dp(12)); layout.addView(details);
            TextView title = heading(details, str(channel, "name"));
            title.setTextIsSelectable(false);
            title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String summary = str(channel, "description");
            if (!summary.isEmpty()) {
                TextView description = text(details, summary);
                description.setTextIsSelectable(false);
                description.setMaxLines(3); description.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            TextView stats = text(details, getString(R.string.channel_stats, num(channel, "count_works"), num(channel, "count_user_fav"), num(channel, "count_comments")));
            stats.setTextIsSelectable(false);
            stats.setTextColor(com.google.android.material.color.MaterialColors.getColor(stats, com.google.android.material.R.attr.colorOnSurfaceVariant));
        }
        pager(state.page, state.pages(), state::load);
        if (wantCreate && state.signedIn() && !state.loading("header") && state.auth.size() > 0) create();
    }
    private void renderSearchBar() {
        View bar = getLayoutInflater().inflate(R.layout.view_channel_search, content, false);
        TextInputEditText input = bar.findViewById(R.id.channelSearchInput);
        MaterialButton submit = bar.findViewById(R.id.channelSearchButton);
        input.setTag("module_field:" + R.string.channel_search_hint);
        input.setSaveEnabled(false);
        input.setText(state.input);
        input.setEnabled(!state.busy()); submit.setEnabled(!state.busy());
        watch(input, state::input);
        submit.setOnClickListener(view -> submitSearch(input));
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                submitSearch(input); return true;
            }
            return false;
        });
        content.addView(bar);
    }
    private void submitSearch(TextInputEditText input) {
        if (state.busy()) return;
        input.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        state.search();
    }
    private void create() {
        if (state.busy()) return;
        if (!state.signedIn()) { wantCreate = true; startActivity(new Intent(this, LoginActivity.class)); return; }
        if (state.auth.size() == 0) { wantCreate = true; state.refresh(); return; }
        wantCreate = false;
        if (!state.canCreate()) { toast(R.string.channel_create_exam); return; }
        form("create", R.string.channel_create, ChannelActivity.channelFields(new JsonObject()));
    }
    @Override protected boolean onForm(String key, JsonObject values) {
        if (!"create".equals(key) || !ChannelActivity.validateChannel(this, values) || !state.canCreate()) return false;
        state.create(values); return true;
    }
}
