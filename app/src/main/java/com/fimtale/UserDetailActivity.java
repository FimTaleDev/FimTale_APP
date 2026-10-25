package com.fimtale;

import com.fimtale.utils.MdiIcons;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.Menu;
import android.view.View;
import android.widget.ImageView;
import com.fimtale.ui.ShimmerSkeletonView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.util.TypedValue;
import android.content.res.ColorStateList;

import com.fimtale.adapter.TopicAdapter;
import com.fimtale.model.Topic;
import com.fimtale.model.TopicListResponse;
import com.fimtale.model.TopicViewItem;
import com.fimtale.model.UserDetailResponse;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class UserDetailActivity extends AppCompatActivity {

    public static final String EXTRA_USERNAME = "extra_username";

    private MaterialCardView imageContainer;
    private ImageView ivBackground;
    private ImageView ivAvatar;
    private TextView tvUsername;
    private TextView tvUserRole;
    private TextView tvLastSeen;
    private TextView tvIntro;
    private TextView tvFollowing;
    private TextView tvFollowers;
    private TextView tvTopics;
    private ChipGroup chipGroupBadges;
    private TextView tvMedalsTitle;
    private ChipGroup chipGroupMedals;
    private AlertDialog medalPreviewDialog;
    private ShimmerSkeletonView loadingSkeleton, topicsSkeleton;
    private boolean profileLoading;
    private TextView loadError, topicsStatus;
    private com.fimtale.ui.PageErrorView pageError;
    private Call<UserDetailResponse> profileCall;
    private Call<com.fimtale.model.UserWorksResponse> topicsCall;
    private CollapsingToolbarLayout collapsingToolbar;
    private MaterialCardView toolbarContainer;
    private Toolbar toolbar;
    private com.fimtale.ui.BottomSheetMenu moreMenu;
    private TextView tvToolbarTitle;
    private NestedScrollView scrollView;
    private boolean isToolbarElevated = false;
    private boolean isTitleVisible = false;
    private ObjectAnimator elevationAnimator;

    private RecyclerView rvUserTopics;
    private TopicAdapter topicAdapter;
    private java.util.List<TopicViewItem> topicList = new java.util.ArrayList<>();
    private TextView tvUserTopicsTitle;
    private int currentPage = 1;
    private int totalPages = 1;
    private boolean isLoading = false;
    private String currentUsername;
    private int currentUserId;
    private String reportUsername;
    private long editorVersion = com.fimtale.editor.EditorChanges.version();
    private com.fimtale.community.ProfileSocialModel social;
    private com.google.android.material.button.MaterialButton followButton, messageButton;
    private UserDetailResponse profileData;
    private AlertDialog blockDialog;
    private final com.fimtale.ui.ButtonLoadingIcon followLoading = new com.fimtale.ui.ButtonLoadingIcon();
    private final com.fimtale.ui.ButtonLoadingIcon messageLoading = new com.fimtale.ui.ButtonLoadingIcon();
    private com.fimtale.ui.MenuActionButton moreAction;
    private String profileSession;
    private long communityVersion = com.fimtale.community.Community.version();

    @Override protected void onResume() {
        super.onResume();
        if (social != null) social.syncSession();
        if (currentUsername != null && (!UserPreferences.getToken(this).equals(profileSession)
                || communityVersion != com.fimtale.community.Community.version())) {
            communityVersion = com.fimtale.community.Community.version();
            loadData(currentUsername);
        }
        if (editorVersion != com.fimtale.editor.EditorChanges.version()) {
            editorVersion = com.fimtale.editor.EditorChanges.version();
            loadUserTopics(currentUsername, 1);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_detail);

        String username = getIntent().getStringExtra(EXTRA_USERNAME);
        if (TextUtils.isEmpty(username)) {
            finish();
            return;
        }
        this.currentUsername = username;
        social = new androidx.lifecycle.ViewModelProvider(this).get(com.fimtale.community.ProfileSocialModel.class);
        social.syncSession();
        moreMenu = new com.fimtale.ui.BottomSheetMenu(this, R.menu.user_more_menu, this::onOptionsItemSelected);

        initView();
        social.header.observe(this, data -> { if (data != null && data.getId() > 0) bindData(data); });
        social.changes.observe(this, ignored -> {
            updateSocialActions();
            if (!social.error.isEmpty()) { Toast.makeText(this, social.error, Toast.LENGTH_LONG).show(); social.error = ""; }
            com.fimtale.notifications.Inbox.Conversation conversation = social.takeConversation();
            if (conversation != null) startActivity(ConversationActivity.intent(this, conversation));
        });
        com.fimtale.ui.PullToRefresh.attach(scrollView, () -> {
            loadData(currentUsername);
            loadUserTopics(currentUsername, 1);
        }, () -> !isLoading && !profileLoading);
        loadData(username);
        loadUserTopics(username, 1);
    }

    private void initView() {
        toolbar = findViewById(R.id.toolbar);
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(MdiIcons.drawable(this, "arrow-left"));
            getSupportActionBar().setTitle("");
        }

        collapsingToolbar = findViewById(R.id.collapsingToolbar);
        toolbarContainer = findViewById(R.id.toolbarContainer);
        scrollView = findViewById(R.id.scrollView);
        imageContainer = findViewById(R.id.imageContainer);
        ivBackground = findViewById(R.id.ivBackground);
        ivAvatar = findViewById(R.id.ivAvatar);
        tvUsername = findViewById(R.id.tvUsername);
        tvUserRole = findViewById(R.id.tvUserRole);
        tvLastSeen = findViewById(R.id.tvLastSeen);
        tvIntro = findViewById(R.id.tvIntro);
        tvFollowing = findViewById(R.id.tvFollowing);
        tvFollowers = findViewById(R.id.tvFollowers);
        tvTopics = findViewById(R.id.tvTopics);
        chipGroupBadges = findViewById(R.id.chipGroupBadges);
        tvMedalsTitle = findViewById(R.id.tvMedalsTitle);
        chipGroupMedals = findViewById(R.id.chipGroupMedals);
        loadingSkeleton = findViewById(R.id.profileLoadingSkeleton);
        loadingSkeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.USER_DETAIL);
        topicsSkeleton = findViewById(R.id.userTopicsSkeleton);
        topicsSkeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.CARD);
        loadError = findViewById(R.id.profileLoadError);
        pageError = com.fimtale.ui.PageErrorView.wrap(findViewById(R.id.scrollView));
        loadError.setOnClickListener(v -> loadData(currentUsername));
        topicsStatus = findViewById(R.id.userTopicsStatus);
        rvUserTopics = findViewById(R.id.rvUserTopics);
        tvUserTopicsTitle = findViewById(R.id.tvUserTopicsTitle);
        followButton = findViewById(R.id.profileFollow); messageButton = findViewById(R.id.profileMessage);
        followButton.setOnClickListener(v -> { if (socialLogin()) social.follow(currentUserId, currentUsername); });
        messageButton.setOnClickListener(v -> { if (socialLogin()) social.message(currentUserId); });
        ((View) tvFollowing.getParent()).setOnClickListener(v -> openUserTab("following"));
        ((View) tvFollowers.getParent()).setOnClickListener(v -> openUserTab("follower"));
        ((View) tvTopics.getParent()).setOnClickListener(v -> openUserTab("work"));
        ChipGroup contentLinks = findViewById(R.id.profileContentLinks);
        String[] labels = getResources().getStringArray(R.array.community_user_tabs);
        for (int i = 0; i < labels.length; i++) {
            Chip link = new Chip(this); link.setText(labels[i]); link.setCheckable(false); link.setClickable(true);
            String tab = com.fimtale.community.UserContentActivity.TABS[i];
            link.setOnClickListener(v -> openUserTab(tab)); contentLinks.addView(link);
        }

        rvUserTopics.setLayoutManager(new LinearLayoutManager(this));
        topicAdapter = new TopicAdapter(topicList);
        rvUserTopics.setAdapter(topicAdapter);
        rvUserTopics.setItemAnimator(null);

        float targetElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        float titleThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 240, getResources().getDisplayMetrics());
        
        scrollView.setOnScrollChangeListener(new NestedScrollView.OnScrollChangeListener() {
            @Override
            public void onScrollChange(NestedScrollView v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                boolean shouldElevate = v.canScrollVertically(-1);

                if (shouldElevate != isToolbarElevated) {
                    isToolbarElevated = shouldElevate;

                    if (elevationAnimator != null && elevationAnimator.isRunning()) {
                        elevationAnimator.cancel();
                    }

                    float start = toolbarContainer.getCardElevation();
                    float end = shouldElevate ? targetElevation : 0;

                    elevationAnimator = ObjectAnimator.ofFloat(toolbarContainer, "cardElevation", start, end);
                    elevationAnimator.setDuration(200);
                    elevationAnimator.start();
                }

                if (scrollY > titleThreshold) {
                    if (!isTitleVisible) {
                        isTitleVisible = true;
                        tvToolbarTitle.setText(currentUsername);
                        tvToolbarTitle.animate().alpha(1.0f).setDuration(200).start();
                    }
                } else {
                    if (isTitleVisible) {
                        isTitleVisible = false;
                        tvToolbarTitle.animate().alpha(0.0f).setDuration(200).start();
                    }
                }

                if (scrollY > oldScrollY && !v.canScrollVertically(1)) {
                    if (!isLoading && currentPage < totalPages) {
                        loadUserTopics(currentUsername, currentPage + 1);
                    }
                }
            }
        });
    }

    private void loadData(String username) {
        pageError.hide();
        if (profileCall != null) profileCall.cancel();
        profileLoading = true;
        boolean pulling = com.fimtale.ui.PullToRefresh.isRefreshing(scrollView);
        loadingSkeleton.setVisibility(pulling ? View.GONE : View.VISIBLE);
        loadError.setVisibility(View.GONE);
        scrollView.setVisibility(pulling ? View.VISIBLE : View.INVISIBLE);
        profileSession = UserPreferences.getToken(this);
        final String token = profileSession;
        profileCall = RetrofitClient.getInstance().getUserHeader(token, username);
        profileCall.enqueue(new Callback<UserDetailResponse>() {
            @Override
            public void onResponse(Call<UserDetailResponse> call, Response<UserDetailResponse> response) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != profileCall) return;
                if (!token.equals(UserPreferences.getToken(UserDetailActivity.this))) { loadData(currentUsername); return; }
                if (response.isSuccessful() && response.body() != null) {
                    UserDetailResponse data = response.body();
                    if (data.getId() > 0) {
                        bindData(data);
                        finishProfileLoading();
                        loadingSkeleton.setVisibility(View.GONE);
                        scrollView.setVisibility(View.VISIBLE);
                    } else {
                        showProfileLoadError();
                    }
                } else {
                    showProfileLoadError();
                }
            }

            @Override
            public void onFailure(Call<UserDetailResponse> call, Throwable t) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != profileCall) return;
                if (!token.equals(UserPreferences.getToken(UserDetailActivity.this))) { loadData(currentUsername); return; }
                showProfileLoadError();
            }
        });
    }

    private void finishProfileLoading() {
        profileLoading = false;
        updateSocialActions();
        if (!isLoading) com.fimtale.ui.PullToRefresh.finish(scrollView);
    }

    private void showProfileLoadError() {
        finishProfileLoading();
        loadingSkeleton.setVisibility(View.GONE);
        loadError.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);
        pageError.show(null, () -> { loadData(currentUsername); loadUserTopics(currentUsername, 1); });
    }

    private void bindData(UserDetailResponse data) {
        profileData = data;
        UserDetailResponse info = data;
        currentUserId = info.getId();
        social.target(currentUserId);
        updateSocialActions();
        reportUsername = TextUtils.isEmpty(info.getUserName()) ? currentUsername : info.getUserName();
        updateMoreMenu();
        invalidateOptionsMenu();

        tvUsername.setText(info.getUserName());
        tvUserRole.setText(getString(R.string.profile_level, info.getLevel()));

        if (!TextUtils.isEmpty(info.getLastSeen())) {
            try {
                long timestamp = java.time.OffsetDateTime.parse(info.getLastSeen()).toInstant().toEpochMilli();
                SimpleDateFormat sdf = new SimpleDateFormat(getString(R.string.common_date_time), Locale.getDefault());
                tvLastSeen.setText(getString(R.string.profile_last_seen, sdf.format(new Date(timestamp))));
            } catch (Exception e) {
                tvLastSeen.setVisibility(View.GONE);
            }
        } else {
             tvLastSeen.setVisibility(View.GONE);
        }

        tvIntro.setText(info.getUserIntro());
        tvFollowing.setText(String.valueOf(info.getFollowing()));
        tvFollowers.setText(String.valueOf(info.getFollowers()));
        tvTopics.setText(String.valueOf(info.getTopics()));

        if (TextUtils.isEmpty(info.getBackground())) {
            imageContainer.setVisibility(View.GONE);
        } else {
            imageContainer.setVisibility(View.VISIBLE);
            Glide.with(this)
                 .load(info.getBackground())
                 .placeholder(MdiIcons.coverPlaceholder(this))
                 .error(MdiIcons.coverPlaceholder(this))
                 .into(ivBackground);
        }

        com.fimtale.ui.ImagePreview.bind(ivBackground, info.getBackground());
        String avatarUrl = info.getAvatar();
        com.fimtale.ui.ImagePreview.bind(ivAvatar, avatarUrl);
        Glide.with(this)
             .load(avatarUrl)
             .placeholder(MdiIcons.drawable(this, "account"))
             .into(ivAvatar);

        chipGroupBadges.removeAllViews();
        if (info.badges != null) {
            int bgColor = resolveThemeColor(com.google.android.material.R.attr.colorPrimaryContainer);
            int textColor = resolveThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer);
            for (UserDetailResponse.Badge badge : info.badges) {
                addChip(chipGroupBadges, badge.name, bgColor, textColor);
            }
        }

        for (int i = 0; i < chipGroupMedals.getChildCount(); i++) {
            Glide.with(this).clear((ImageView) chipGroupMedals.getChildAt(i));
        }
        chipGroupMedals.removeAllViews();
        if (info.medals != null && !info.medals.isEmpty()) {
            tvMedalsTitle.setVisibility(View.VISIBLE);
            for (UserDetailResponse.Medal medal : info.medals) {
                addMedalImage(chipGroupMedals, medal);
            }
        } else {
            tvMedalsTitle.setVisibility(View.GONE);
        }
    }

    private void addMedalImage(ChipGroup group, UserDetailResponse.Medal medal) {
        String medalName = TextUtils.isEmpty(medal.name) ? getString(R.string.medal_title) : medal.name;
        String medalUrl = com.fimtale.network.SiteUrls.media(medal.image);
        ImageView image = (ImageView) getLayoutInflater().inflate(R.layout.item_user_medal, group, false);
        image.setContentDescription(medalName);
        image.setOnClickListener(v -> showMedalPreview(medalName, medalUrl));
        group.addView(image);

        Glide.with(this)
                .load(medalUrl)
                .fitCenter()
                .placeholder(MdiIcons.drawable(this, "medal-outline"))
                .error(MdiIcons.drawable(this, "medal-outline"))
                .into(image);
    }

    private void showMedalPreview(String name, String imageUrl) {
        if (isFinishing() || isDestroyed()) return;
        if (medalPreviewDialog != null) medalPreviewDialog.dismiss();
        ImageView image = (ImageView) getLayoutInflater().inflate(R.layout.dialog_medal_preview, null);
        image.setContentDescription(name);
        RequestManager images = Glide.with(this);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setView(image)
                .create();
        dialog.setOnDismissListener(ignored -> {
            images.clear(image);
            if (medalPreviewDialog == dialog) medalPreviewDialog = null;
        });
        medalPreviewDialog = dialog;
        dialog.show();
        images.load(imageUrl)
                .fitCenter()
                .placeholder(MdiIcons.drawable(this, "medal-outline"))
                .error(MdiIcons.drawable(this, "medal-outline"))
                .into(image);
    }

    private void addChip(ChipGroup group, String text, int bgColor, int textColor) {
        Chip chip = new Chip(this);
        chip.setText(text);
        chip.setChipBackgroundColor(ColorStateList.valueOf(bgColor));
        chip.setTextColor(textColor);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setElevation(0);
        chip.setChipMinHeight(0);
        float padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        chip.setChipStartPadding(padding);
        chip.setChipEndPadding(padding);
        chip.setTextStartPadding(padding);
        chip.setTextEndPadding(padding);
        chip.setCloseIconVisible(false);
        chip.setChipStrokeWidth(0);
        group.addView(chip);
    }

    private void loadUserTopics(String username, int page) {
        pageError.hide();
        if (username == null) return;
        if (isLoading) {
            if (page != 1) return;
            if (topicsCall != null) topicsCall.cancel();
        }
        isLoading = true;
        topicsStatus.setVisibility(View.GONE);
        tvUserTopicsTitle.setVisibility(View.VISIBLE);
        topicsSkeleton.setVisibility(com.fimtale.ui.PullToRefresh.isRefreshing(scrollView) ? View.GONE : View.VISIBLE);
        topicsCall = RetrofitClient.getInstance().getUserTopics(username, "work", page);
        topicsCall.enqueue(new Callback<com.fimtale.model.UserWorksResponse>() {
            @Override public void onResponse(Call<com.fimtale.model.UserWorksResponse> call, Response<com.fimtale.model.UserWorksResponse> response) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != topicsCall) return;
                if (response.isSuccessful() && response.body() != null && response.body().content != null) {
                    TopicListResponse data = response.body().content;
                    if (page == 1) topicList.clear();
                    currentPage = page; totalPages = data.getTotalPage();
                    int start = topicList.size();
                    if (data.getTopicArray() != null) for (Topic topic : data.getTopicArray()) topicList.add(new TopicViewItem(topic));
                    if (page == 1) topicAdapter.notifyDataSetChanged();
                    else topicAdapter.notifyItemRangeInserted(start, topicList.size() - start);
                    finishTopicsLoading();
                    rvUserTopics.setVisibility(topicList.isEmpty() ? View.GONE : View.VISIBLE);
                    if (topicList.isEmpty()) {
                        topicsStatus.setText(getString(R.string.work_empty)); topicsStatus.setVisibility(View.VISIBLE);
                        topicsStatus.setOnClickListener(null);
                    }
                } else showTopicsLoadError(page);
            }
            @Override public void onFailure(Call<com.fimtale.model.UserWorksResponse> call, Throwable t) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != topicsCall) return;
                showTopicsLoadError(page);
            }
        });
    }

    private void finishTopicsLoading() {
        isLoading = false;
        if (!profileLoading) com.fimtale.ui.PullToRefresh.finish(scrollView);
        topicsSkeleton.setVisibility(View.GONE);
    }

    private void showTopicsLoadError(int page) {
        finishTopicsLoading();
        topicsStatus.setVisibility(View.GONE);
        pageError.show(getString(R.string.profile_works_failed), () -> { loadData(currentUsername); loadUserTopics(currentUsername, page); }, currentUserId > 0);
    }

    @Override protected void onDestroy() {
        followLoading.close(); messageLoading.close(); if (moreAction != null) moreAction.close();
        if (blockDialog != null) blockDialog.dismiss();
        if (medalPreviewDialog != null) medalPreviewDialog.dismiss();
        if (moreMenu != null) moreMenu.dismiss();
        if (profileCall != null) { profileCall.cancel(); profileCall = null; }
        if (topicsCall != null) { topicsCall.cancel(); topicsCall = null; }
        if (elevationAnimator != null) elevationAnimator.cancel();
        super.onDestroy();
    }

    private int resolveThemeColor(int attrRes) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(attrRes, typedValue, true);
        return typedValue.data;
    }

    private void openUserTab(String tab) {
        if (currentUsername != null) startActivity(com.fimtale.community.UserContentActivity.intent(this, currentUsername, tab));
    }
    private boolean socialLogin() {
        if (currentUserId <= 0) return false;
        if (UserPreferences.isLoggedIn(this)) return true;
        startActivity(new android.content.Intent(this, LoginActivity.class)); return false;
    }
    private void updateSocialActions() {
        if (social == null || followButton == null) return;
        int self = 0;
        try { self = Integer.parseInt(UserPreferences.getUserId(this)); } catch (RuntimeException ignored) { }
        findViewById(R.id.profileSocialActions).setVisibility(currentUserId > 0 && self != currentUserId ? View.VISIBLE : View.GONE);
        boolean followed = profileData != null && profileData.followedBy(self);
        followButton.setText(followed ? R.string.community_unfollow : R.string.community_follow);
        followButton.setEnabled(!social.following && !profileLoading);
        messageButton.setEnabled(!social.messaging);
        followLoading.bind(followButton, followed ? "account-check" : "account-plus-outline", followed, social.following);
        messageLoading.bind(messageButton, "message-text-outline", false, social.messaging);
        updateMoreMenu();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        MdiIcons.inflateMenu(this, getMenuInflater(), R.menu.menu_user_detail, menu);
        if (moreAction != null) moreAction.close();
        moreAction = new com.fimtale.ui.MenuActionButton(this, menu.findItem(R.id.action_more), this::onOptionsItemSelected);
        updateMoreMenu();
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        updateMoreMenu();
        return super.onPrepareOptionsMenu(menu);
    }

    private void updateMoreMenu() {
        if (moreMenu == null) return;
        moreMenu.getMenu().findItem(R.id.action_report_user).setEnabled(currentUserId > 0);
        MenuItem blockItem = moreMenu.getMenu().findItem(R.id.action_block_user);
        boolean self = String.valueOf(currentUserId).equals(UserPreferences.getUserId(this));
        blockItem.setVisible(currentUserId > 0 && !self);
        blockItem.setEnabled(social != null && !social.blocking);
        blockItem.setTitle(social != null && social.blocked ? R.string.filters_unblock : R.string.community_block_user);
        blockItem.setIcon(MdiIcons.drawable(this, social != null && social.blocked ? "account-cancel" : "account-cancel-outline"));
        if (moreAction != null) moreAction.bind("dots-vertical", false, social != null && social.blockChanging, true);
        if (moreMenu.isShowing()) moreMenu.refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_more) { moreMenu.show(); return true; }
        if (item.getItemId() == R.id.action_block_user) {
            if (!socialLogin()) return true;
            if (!social.blockKnown) { social.loadBlocked(); return true; }
            String token = UserPreferences.getToken(this);
            String action = getString(social.blocked ? R.string.filters_unblock : R.string.community_block_user);
            blockDialog = new MaterialAlertDialogBuilder(this).setMessage(getString(R.string.community_block_confirm, action, currentUsername))
                    .setNegativeButton(R.string.common_cancel, null).setPositiveButton(R.string.common_confirm, (dialog, which) -> {
                        if (token.equals(UserPreferences.getToken(this))) social.toggleBlock();
                    }).show();
            return true;
        }
        if (item.getItemId() == R.id.action_report_user) {
            com.fimtale.report.ReportDialog.show(this, com.fimtale.report.ReportRequest.USER, currentUserId,
                    getString(R.string.report_user_target, reportUsername));
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
