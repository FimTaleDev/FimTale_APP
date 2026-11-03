package com.fimtale.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.LoginActivity;
import com.fimtale.R;
import com.fimtale.adapter.CommentAdapter;
import com.fimtale.adapter.TimelineAdapter;
import com.fimtale.adapter.TopicAdapter;
import com.fimtale.model.*;
import com.fimtale.ui.PageErrorView;
import com.fimtale.ui.BottomSheetMenu;
import com.fimtale.ui.PullToRefresh;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import java.util.*;

/** Native profile tabs and collection management share loading, paging and session handling. */
public abstract class CommunityListActivity extends AppCompatActivity {
    protected boolean favoritesMode() { return false; }
    protected boolean followedTagFeedMode() { return false; }
    private CommunityListModel model;
    private RecyclerView list;
    private TabLayout tabs;
    private Spinner spinner;
    private PageErrorView status;
    private PageErrorView error;
    private int renderedVersion = -1, renderedFolders = -1;
    private CommentAdapter commentAdapter;
    private RecyclerView.Adapter<?> adapter;
    private final List<android.app.Dialog> dialogs = new ArrayList<>();
    private android.view.MenuItem feedMenu, scopeMenu;
    private final com.fimtale.ui.ButtonLoadingIcon foldersLoadingIcon = new com.fimtale.ui.ButtonLoadingIcon();
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(R.layout.activity_community);
        String username = getIntent().getStringExtra(UserContentActivity.EXTRA_USERNAME);
        if (!favoritesMode() && !followedTagFeedMode() && (username == null || username.isEmpty())) { finish(); return; }
        model = new ViewModelProvider(this).get(CommunityListModel.class);
        MaterialToolbar toolbar = findViewById(R.id.communityToolbar);
        toolbar.setTitle(favoritesMode() ? getString(R.string.favorites_title) : followedTagFeedMode()
                ? getString(R.string.community_followed_tag_works) : getString(R.string.community_user_content, username));
        toolbar.setNavigationOnClickListener(v -> finish());
        if (!favoritesMode()) toolbar.getMenu().add(R.string.community_refresh)
                .setOnMenuItemClickListener(item -> { model.refresh(); return true; });
        feedMenu = toolbar.getMenu().add(R.string.community_open_tags_feed).setVisible(false);
        feedMenu.setOnMenuItemClickListener(item -> { startActivity(new Intent(this, FollowedTagFeedActivity.class)); return true; });
        scopeMenu = toolbar.getMenu().add(R.string.community_choose_tags).setVisible(followedTagFeedMode());
        scopeMenu.setOnMenuItemClickListener(item -> { chooseTags(); return true; });
        list = findViewById(R.id.communityList); list.setLayoutManager(new LinearLayoutManager(this)); list.setItemAnimator(null);
        status = findViewById(R.id.communityStatus);
        View listSurface = PageErrorView.withEmptyState(list, status);
        error = PageErrorView.wrap(listSurface);
        spinner = findViewById(R.id.communityFolderSpinner); findViewById(R.id.communityFolderManage).setOnClickListener(v -> manageFolders());
        tabs = findViewById(R.id.communityTabs);
        String[] labels = getResources().getStringArray(favoritesMode() ? R.array.community_favorite_tabs
                : followedTagFeedMode() ? R.array.community_scope_tabs : R.array.community_user_tabs);
        for (String label : labels) tabs.addTab(tabs.newTab().setText(label));
        int start = 0;
        String tab = getIntent().getStringExtra(UserContentActivity.EXTRA_TAB);
        if (!favoritesMode() && !followedTagFeedMode()) for (int i = 0; i < UserContentActivity.TABS.length; i++) if (UserContentActivity.TABS[i].equals(tab)) start = i;
        if (saved != null) start = saved.getInt("tab", start);
        Integer folder = saved != null && saved.containsKey("folder") ? saved.getInt("folder") : null;
        if (followedTagFeedMode()) model.initTagFeed(start); else model.init(favoritesMode(), username, start, folder);
        model.resume();
        tabs.selectTab(tabs.getTabAt(model.tab));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { model.select(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        findViewById(R.id.communityPrevious).setOnClickListener(v -> model.load(model.page - 1));
        findViewById(R.id.communityNext).setOnClickListener(v -> model.load(model.page + 1));
        PullToRefresh.attach(listSurface, model::refresh, () -> !model.loading && !model.mutating,
                () -> list.canScrollVertically(-1));
        model.changes.observe(this, ignored -> render());
        if ((favoritesMode() || followedTagFeedMode()) && !UserPreferences.isLoggedIn(this) && saved == null) login();
    }
    @Override protected void onResume() { super.onResume(); if (model != null) model.resume(); }
    private void login() { startActivity(new Intent(this, LoginActivity.class)); }
    private boolean emptyTags() {
        return followedTagFeedMode() && model.followed.isEmpty() || favoritesMode() && model.tab == 3 && model.tags.isEmpty();
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        if (renderedVersion != model.dataVersion) {
            renderedVersion = model.dataVersion;
            if (commentAdapter != null) { commentAdapter.close(); commentAdapter = null; }
            switch (model.kind()) {
                case WORK:
                    List<TopicViewItem> topics = new ArrayList<>(); for (Topic topic : model.works) topics.add(new TopicViewItem(topic));
                    TopicAdapter works = new TopicAdapter(topics);
                    adapter = works; break;
                case COMMENT:
                    commentAdapter = new CommentAdapter(model.comments, this);
                    commentAdapter.setContext(0, 0, () -> model.load(model.page));
                    commentAdapter.setFavoritesOnly(favoritesMode()); adapter = commentAdapter; break;
                case ACTIVITY:
                    adapter = new TimelineAdapter(this, model.activity, model.reposting, item -> {
                        if (UserPreferences.isLoggedIn(this)) model.repost(item); else login();
                    }); break;
                default: adapter = new CommunityCardAdapter(model, this::removeItem); break;
            }
            list.setAdapter(adapter); list.scrollToPosition(0);
        } else if (adapter != null && !(adapter instanceof CommentAdapter)) adapter.notifyDataSetChanged();
        boolean guest = (favoritesMode() || followedTagFeedMode()) && !UserPreferences.isLoggedIn(this);
        if (guest) status.showLogin(getString(followedTagFeedMode()
                ? R.string.community_scope_login : R.string.community_login_required), this::login);
        else if (emptyTags()) status.showPrompt(R.string.page_empty_title, getString(R.string.community_empty_tags),
                "inbox-outline", R.string.tags_list_title,
                () -> startActivity(new Intent(this, com.fimtale.TagListActivity.class)));
        else status.showEmpty(getString(R.string.community_empty), model::refresh);
        status.setActionEnabled(!model.mutating);
        feedMenu.setVisible(favoritesMode() && model.tab == 3 && !guest);
        scopeMenu.setEnabled(followedTagFeedMode() && !model.followed.isEmpty() && !model.loading);
        status.setVisibility(!model.loading && model.error.isEmpty() && model.size() == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.communityProgress).setVisibility(model.loading && model.size() == 0 ? View.VISIBLE : View.GONE);
        if (!model.loading) PullToRefresh.finish(list);
        if (model.error.isEmpty()) error.hide(); else error.show(model.error, () -> model.load(model.failedPage), model.size() > 0);
        findViewById(R.id.communityPager).setVisibility(model.totalPages > 1 ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.communityPage)).setText(getString(R.string.common_pagination, model.page, model.totalPages));
        findViewById(R.id.communityPrevious).setEnabled(!model.loading && !model.mutating && model.page > 1);
        findViewById(R.id.communityNext).setEnabled(!model.loading && !model.mutating && model.page < model.totalPages);
        findViewById(R.id.communityFolders).setVisibility(favoritesMode() && model.tab == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.communityFolderManage).setEnabled(!guest && !model.mutating && !model.foldersLoading);
        foldersLoadingIcon.bind(findViewById(R.id.communityFolderManage), "folder-cog-outline", false, model.pending("folders"));
        spinner.setEnabled(!model.mutating && !model.foldersLoading);
        if (favoritesMode() && renderedFolders != model.folderVersion) {
            renderedFolders = model.folderVersion;
            List<String> names = new ArrayList<>(); names.add(getString(R.string.community_all_folders));
            for (WorkInteractions.Folder f : model.folders) names.add(getString(R.string.community_folder_count, f.name, f.count));
            spinner.setOnItemSelectedListener(null);
            ArrayAdapter<String> folders = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
            folders.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(folders);
            int selected = 0;
            for (int i = 0; i < model.folders.size(); i++) if (Objects.equals(model.folderId, model.folders.get(i).id)) selected = i + 1;
            spinner.setSelection(selected);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position <= model.folders.size()) model.folder(position == 0 ? null : model.folders.get(position - 1).id);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
        }
        if (!model.notice.isEmpty()) { String message = model.notice; model.notice = ""; Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    }
    private boolean sameSession(String token) {
        if (token.equals(UserPreferences.getToken(this))) return true;
        Toast.makeText(this, R.string.community_account_changed, Toast.LENGTH_LONG).show(); model.resume(); return false;
    }
    private void manageFolders() {
        if (model.mutating || model.foldersLoading) return;
        String token = model.token();
        List<String> labels = new ArrayList<>(); labels.add(getString(R.string.community_create_folder));
        labels.add(getString(R.string.community_refresh));
        for (WorkInteractions.Folder folder : model.folders) labels.add(getString(R.string.community_folder_count, folder.name, folder.count));
        List<WorkInteractions.Folder> snapshot = new ArrayList<>(model.folders);
        BottomSheetMenu sheet = new BottomSheetMenu(this, getString(R.string.community_manage_folders), labels.toArray(new String[0]), which -> {
            if (!sameSession(token)) return;
            if (which == 0) FolderNameDialog.show(this, null, "");
            else if (which == 1) model.loadFolders();
            else folderOptions(snapshot.get(which - 2));
        });
        dialogs.add(sheet); sheet.show();
    }
    private void chooseTags() {
        if (model.followed.isEmpty() || model.loading) return;
        String token = model.token();
        List<Community.Tag> snapshot = new ArrayList<>(model.followed);
        String[] names = new String[snapshot.size()]; boolean[] checked = new boolean[snapshot.size()];
        Set<Integer> selected = new LinkedHashSet<>(model.selectedTags);
        for (int i = 0; i < snapshot.size(); i++) {
            names[i] = snapshot.get(i).name; checked[i] = selected.contains(snapshot.get(i).id);
        }
        dialogs.add(new MaterialAlertDialogBuilder(this).setTitle(R.string.community_scope_tags_hint)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    if (isChecked) selected.add(snapshot.get(which).id); else selected.remove(snapshot.get(which).id);
                }).setNeutralButton(R.string.community_all_followed_tags, (dialog, which) -> { if (sameSession(token)) model.scope(Collections.emptySet()); })
                .setNegativeButton(R.string.common_cancel, null).setPositiveButton(R.string.common_confirm, (dialog, which) -> {
                    if (sameSession(token)) model.scope(selected);
                }).show());
    }
    private void folderOptions(WorkInteractions.Folder folder) {
        String token = model.token();
        String[] options = {getString(R.string.community_rename_folder), getString(R.string.community_delete_folder)};
        BottomSheetMenu sheet = new BottomSheetMenu(this, folder.name, options, which -> {
            if (!sameSession(token)) return;
            if (which == 0) FolderNameDialog.show(this, folder.id, folder.name);
            else confirm(getString(R.string.community_delete_folder_confirm, folder.name), () -> model.deleteFolder(folder.id));
        });
        dialogs.add(sheet); sheet.show();
    }
    private void removeItem(int position) {
        if (model.mutating) return;
        if (model.kind() == CommunityListModel.Kind.CHANNEL && position < model.channels.size()) {
            Community.Channel item = model.channels.get(position);
            confirm(getString(R.string.community_confirm_unfavorite, item.name), () -> model.removeChannel(item.id));
        } else if (model.kind() == CommunityListModel.Kind.TAG && position < model.tags.size()) {
            Community.Tag item = model.tags.get(position);
            confirm(getString(R.string.community_confirm_unfollow_tag, item.name), () -> model.removeTag(item.id));
        }
    }
    private void confirm(String message, Runnable action) {
        String token = model.token();
        dialogs.add(new MaterialAlertDialogBuilder(this).setMessage(message).setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_confirm, (d, which) -> { if (sameSession(token)) action.run(); }).show());
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out); if (model == null) return;
        out.putInt("tab", model.tab); if (model.folderId != null) out.putInt("folder", model.folderId);
    }
    @Override protected void onDestroy() {
        foldersLoadingIcon.close(); if (list != null) list.setAdapter(null);
        if (commentAdapter != null) commentAdapter.close();
        for (android.app.Dialog dialog : dialogs) dialog.dismiss(); super.onDestroy();
    }
}
