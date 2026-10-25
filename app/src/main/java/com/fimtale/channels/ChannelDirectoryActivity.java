package com.fimtale.channels;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
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
        button(content, R.string.channel_create, "create", false, false, this::create);
        if (state.signedIn()) {
            button(content, R.string.channel_my_channels, "my_channels", false, false,
                    () -> startActivity(UserContentActivity.intent(this, UserPreferences.getUserName(this), "channel")));
            if (state.auth.size() > 0 && !state.canCreate()) {
                text(content, R.string.channel_create_exam);
                button(content, R.string.exam_title, "exam", false, false, () -> startActivity(new Intent(this, ExamActivity.class)));
            }
        }
        if (state.tab == 1 && loginRequired()) return;
        if (state.channels.size() == 0 && state.loading("page") && !state.errors.containsKey("page")) { loadingBlocks(3); return; }
        if (state.tab == 0) {
            field(content, R.string.channel_search_hint, state.input, InputType.TYPE_CLASS_TEXT, state::input);
            button(content, R.string.channel_search, "search", false, false, state::search);
        }
        if (!state.loading("page") && state.channels.size() == 0 && !state.errors.containsKey("page")) text(content, R.string.modules_empty);
        for (JsonElement value : state.channels) {
            JsonObject channel = obj(value); int id = num(channel, "id"); if (id <= 0) continue;
            LinearLayout card = column(content);
            String cover = str(channel, "cover_image");
            if (!cover.isEmpty()) {
                ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setContentDescription(getString(R.string.channel_cover_image, str(channel, "name")));
                card.addView(image, new LinearLayout.LayoutParams(-1, dp(140)));
                Glide.with(this).load(SiteUrls.media(cover)).placeholder(MdiIcons.drawable(this, "image-outline")).into(image);
            }
            heading(card, str(channel, "name")); text(card, str(channel, "description"));
            text(card, getString(R.string.channel_stats, num(channel, "count_works"), num(channel, "count_user_fav"), num(channel, "count_comments")));
            button(card, R.string.channel_open, "open:" + id, false, false, () -> startActivity(ChannelActivity.intent(this, id)));
        }
        pager(state.page, state.pages(), state::load);
        if (wantCreate && state.signedIn() && !state.loading("header") && state.auth.size() > 0) create();
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
