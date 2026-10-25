package com.fimtale;

import com.fimtale.utils.MdiIcons;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fimtale.adapter.TopicAdapter;
import com.fimtale.model.TopicListResponse;
import com.fimtale.model.TagInfo;
import com.fimtale.model.Topic;
import com.fimtale.model.TopicViewItem;
import com.fimtale.network.RetrofitClient;
import android.widget.TextView;
import androidx.recyclerview.widget.ConcatAdapter;
import com.fimtale.adapter.LoadingCardAdapter;
import com.fimtale.ui.ShimmerSkeletonView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TagArticlesActivity extends AppCompatActivity {
    private com.fimtale.ui.PageErrorView pageError;
    public static final String EXTRA_WORK_TYPE = "work_type";
    private int workType;

    public static final String EXTRA_TAG_NAME = "tag_name";

    private MaterialToolbar toolbar;
    private MaterialCardView toolbarContainer;
    private RecyclerView recyclerView;
    private ShimmerSkeletonView loadingSkeleton;
    private LoadingCardAdapter loadingFooter;
    private TextView loadingStatus;
    private Call<TopicListResponse> topicsCall;
    private TopicAdapter topicAdapter;
    private List<TopicViewItem> topicViewItemList = new ArrayList<>();
    
    private String tagName;
    private int currentPage = 1;
    private int totalPages = 1;
    private boolean isLoading = false;
    private String currentSortBy = "";
    private TagInfo tagInfo;
    private MenuItem tagInfoMenuItem;
    private MenuItem followTagMenuItem;
    private com.fimtale.community.TagFollowModel tagFollow;
    private com.fimtale.ui.MenuActionButton followAction;

    private boolean isToolbarElevated = false;
    private ObjectAnimator elevationAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tag_articles);

        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        getWindow().setStatusBarColor(typedValue.data);

        tagName = getIntent().getStringExtra(EXTRA_TAG_NAME);
        workType = getIntent().getIntExtra(EXTRA_WORK_TYPE, 0);
        if (workType != 0) tagName = workType == 2 ? getString(R.string.work_type_gallery) : getString(R.string.work_type_post);
        if (tagName == null) {
            finish();
            return;
        }

        tagFollow = new androidx.lifecycle.ViewModelProvider(this).get(com.fimtale.community.TagFollowModel.class);

        setupViews();
        tagFollow.changes.observe(this, ignored -> {
            tagInfo = tagFollow.info; updateTagInfoMenuItemVisibility();
            if (!tagFollow.error.isEmpty()) { Toast.makeText(this, tagFollow.error, Toast.LENGTH_LONG).show(); tagFollow.error = ""; }
        });
        fetchTagTopics(1);
    }

    private void setupViews() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(MdiIcons.drawable(this, "arrow-left"));
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            // Keep the ActionBar's title in sync so window updates cannot replace it.
            getSupportActionBar().setTitle(getString(R.string.tags_heading, tagName));
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        toolbarContainer = findViewById(R.id.toolbarContainer);

        recyclerView = findViewById(R.id.recyclerView);
        pageError = com.fimtale.ui.PageErrorView.wrap(recyclerView);
        loadingSkeleton = findViewById(R.id.loadingSkeleton);
        loadingSkeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.ARTICLES);
        loadingFooter = new LoadingCardAdapter();
        loadingStatus = findViewById(R.id.loadingStatus);
        loadingStatus.setOnClickListener(v -> fetchTagTopics(1));

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        topicAdapter = new TopicAdapter(topicViewItemList);
        recyclerView.setAdapter(new ConcatAdapter(topicAdapter, loadingFooter));
        recyclerView.setItemAnimator(null);
        com.fimtale.ui.PullToRefresh.attach(recyclerView, () -> {
            tagInfo = null;
            fetchTagTopics(1);
        }, () -> !isLoading);

        float targetElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                boolean shouldElevate = recyclerView.computeVerticalScrollOffset() > 0;
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
                
                if (dy > 0) {
                    LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (layoutManager != null) {
                        int visibleItemCount = layoutManager.getChildCount();
                        int totalItemCount = layoutManager.getItemCount();
                        int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();

                        if (!isLoading && (visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                                && firstVisibleItemPosition >= 0
                                && currentPage < totalPages) {
                            recyclerView.post(() -> {
                                if (!isFinishing() && !isDestroyed() && !isLoading && currentPage < totalPages) fetchTagTopics(currentPage + 1);
                            });
                        }
                    }
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MdiIcons.inflateMenu(this, getMenuInflater(), R.menu.menu_tag_articles, menu);
        tagInfoMenuItem = menu.findItem(R.id.action_tag_info);
        followTagMenuItem = menu.findItem(R.id.action_follow_tag);
        if (followAction != null) followAction.close();
        followAction = new com.fimtale.ui.MenuActionButton(this, followTagMenuItem, this::onOptionsItemSelected);
        updateTagInfoMenuItemVisibility();
        return true;
    }

    private void updateTagInfoMenuItemVisibility() {
        if (followTagMenuItem != null) {
            followTagMenuItem.setVisible(workType == 0 && tagInfo != null);
            followTagMenuItem.setEnabled(tagFollow != null && !tagFollow.busy && !tagFollow.loading);
            if (tagInfo != null) followTagMenuItem.setTitle(getString(R.string.community_tag_follow_count,
                    getString(tagInfo.faved ? R.string.community_unfollow_tag : R.string.community_follow_tag), tagInfo.count_fav));
            if (followAction != null) followAction.bind(tagInfo != null && tagInfo.faved ? "heart" : "heart-outline",
                    tagInfo != null && tagInfo.faved, tagFollow != null && (tagFollow.busy || tagFollow.loading), tagInfo != null);
            if (followAction != null && tagInfo != null) followAction.setCount(tagInfo.count_fav);
        }
        if (tagInfoMenuItem != null) {
            tagInfoMenuItem.setVisible(tagInfo != null && tagInfo.getIntro() != null && !tagInfo.getIntro().isEmpty());
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_follow_tag) {
            if (com.fimtale.utils.UserPreferences.isLoggedIn(this)) tagFollow.toggle(tagName);
            else startActivity(new android.content.Intent(this, LoginActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_tag_info) {
            showTagInfoDialog();
            return true;
        } else if (item.getItemId() == R.id.action_filter) {
            showFilterDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showFilterDialog() {
        final String[] options = getResources().getStringArray(R.array.work_sort_options);
        final String[] values = {"", "created_at", "last_chapter_at", "commented_at", "count_character", "count_comment", "count_view", "wilson_score"};

        int checkedItem = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentSortBy)) {
                checkedItem = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.work_choose_sort))
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    currentSortBy = values[which];
                    dialog.dismiss();
                    currentPage = 1;
                    totalPages = 1;
                    topicViewItemList.clear();
                    topicAdapter.notifyDataSetChanged();
                    fetchTagTopics(1);
                })
                .show();
    }

    private void showTagInfoDialog() {
        if (tagInfo == null || tagInfo.getIntro() == null || tagInfo.getIntro().isEmpty()) {
            Toast.makeText(this, getString(R.string.profile_empty_intro), Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(tagInfo.getName())
                .setMessage(android.text.Html.fromHtml(tagInfo.getIntro(), android.text.Html.FROM_HTML_MODE_COMPACT))
                .setPositiveButton(getString(R.string.common_confirm), null)
                .show();
    }

    private void fetchTagTopics(int page) {
        pageError.hide();
        if (isLoading && page != 1) return;
        if (topicsCall != null) topicsCall.cancel();
        isLoading = true;
        if (page == 1 && workType == 0 && tagInfo == null) {
            tagFollow.load(tagName);
        }
        loadingStatus.setVisibility(View.GONE);
        boolean replacing = page == 1 && !com.fimtale.ui.PullToRefresh.isRefreshing(recyclerView);
        loadingSkeleton.setVisibility(replacing ? View.VISIBLE : View.GONE);
        loadingFooter.setLoading(page > 1);
        recyclerView.setVisibility(replacing ? View.INVISIBLE : View.VISIBLE);
        topicsCall = workType == 0
                ? RetrofitClient.getInstance().getTagTopics(tagName, page, com.fimtale.network.SearchQuery.rank(currentSortBy))
                : RetrofitClient.getInstance().getTopicList(page, com.fimtale.network.SearchQuery.type(workType), com.fimtale.network.SearchQuery.rank(currentSortBy));
        topicsCall.enqueue(new Callback<TopicListResponse>() {
            @Override public void onResponse(@NonNull Call<TopicListResponse> call, @NonNull Response<TopicListResponse> response) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != topicsCall) return;
                if (response.isSuccessful() && response.body() != null) {
                    TopicListResponse data = response.body();
                    currentPage = page;
                    totalPages = data.getTotalPage();
                    if (page == 1) topicViewItemList.clear();
                    int start = topicViewItemList.size();
                    if (data.getTopicArray() != null) {
                        for (Topic topic : data.getTopicArray()) topicViewItemList.add(new TopicViewItem(topic));
                    }
                    if (page == 1) topicAdapter.notifyDataSetChanged();
                    else topicAdapter.notifyItemRangeInserted(start, topicViewItemList.size() - start);
                    finishLoading();
                    if (page == 1) recyclerView.scrollToPosition(0);
                    if (topicViewItemList.isEmpty()) {
                        loadingStatus.setText(getString(R.string.work_empty_tap));
                        loadingStatus.setVisibility(View.VISIBLE);
                    }
                } else showLoadError(page);
            }
            @Override public void onFailure(@NonNull Call<TopicListResponse> call, @NonNull Throwable t) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != topicsCall) return;
                showLoadError(page);
            }
        });
    }

    private void finishLoading() {
        com.fimtale.ui.PullToRefresh.finish(recyclerView);
        isLoading = false;
        loadingSkeleton.setVisibility(View.GONE);
        loadingFooter.setLoading(false);
        recyclerView.setVisibility(View.VISIBLE);
    }

    private void showLoadError(int page) {
        finishLoading();
        loadingStatus.setVisibility(View.GONE);
        pageError.show(null, () -> fetchTagTopics(page), topicAdapter.getItemCount() > 0);
    }

    @Override protected void onDestroy() {
        if (followAction != null) followAction.close();
        if (topicsCall != null) { topicsCall.cancel(); topicsCall = null; }
        if (elevationAnimator != null) elevationAnimator.cancel();
        super.onDestroy();
    }
    @Override protected void onResume() {
        super.onResume(); if (tagFollow != null && workType == 0) tagFollow.resume(tagName);
    }
}
