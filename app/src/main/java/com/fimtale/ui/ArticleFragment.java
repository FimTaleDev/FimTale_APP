package com.fimtale.ui;

import com.fimtale.utils.MdiIcons;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ConcatAdapter;
import com.fimtale.adapter.LoadingCardAdapter;

import com.fimtale.R;
import com.fimtale.adapter.TopicAdapter;
import com.fimtale.model.Topic;
import com.fimtale.model.TopicListResponse;
import com.fimtale.model.TopicViewItem;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.DialogHelper;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ArticleFragment extends Fragment {
    private com.fimtale.ui.PageErrorView pageError;

    private TabLayout tabLayout;
    private PullRefreshLayout swipeRefreshLayout;
    private FrameLayout contentContainer;
    private ShimmerSkeletonView loadingSkeleton;
    private LoadingCardAdapter loadingFooter;
    private PageErrorView emptyStateLayout;
    private PageErrorView tvNoResults;
    private RecyclerView recyclerView;
    private TopicAdapter adapter;
    private List<TopicViewItem> dataList = new ArrayList<>();
    private int currentPage = 1;
    private int totalPages = 1;
    private boolean isLoading = false;
    private Call<TopicListResponse> topicsCall;
    private String currentSortBy = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_article, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tabLayout = view.findViewById(R.id.tabs);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        contentContainer = view.findViewById(R.id.content_container);
        loadingSkeleton = view.findViewById(R.id.loadingSkeleton);
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout);
        tvNoResults = view.findViewById(R.id.tvNoResults);

        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.common_all)));
        tabLayout.setVisibility(View.GONE);

        setupRecyclerView();
        setupSwipeRefresh();
        setupEmptyState();

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                MdiIcons.inflateMenu(requireContext(), menuInflater, R.menu.article_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_filter) {
                    showFilterDialog();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        loadContent();
    }

    private void setupEmptyState() {
        emptyStateLayout.showLogin(getString(R.string.profile_login_message),
                () -> DialogHelper.openLogin(requireContext()));
        emptyStateLayout.setVisibility(View.GONE);
    }

    private void loadContent() {
        emptyStateLayout.setVisibility(View.GONE);
        swipeRefreshLayout.setVisibility(View.VISIBLE);
        swipeRefreshLayout.setEnabled(true);
        currentPage = 1;
        loadTopics();
    }

    private void setupRecyclerView() {
        recyclerView = new RecyclerView(getContext());
        recyclerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT));
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        int padding = (int)(8 * getResources().getDisplayMetrics().density);
        int topPadding = getResources().getDimensionPixelSize(R.dimen.page_margin);
        int bottomPadding = getResources().getDimensionPixelSize(R.dimen.bottom_navigation_corner_radius);
        recyclerView.setPadding(padding, topPadding, padding, bottomPadding);
        recyclerView.setClipToPadding(false);
        recyclerView.setVisibility(View.GONE);
        
        adapter = new TopicAdapter(dataList);
        loadingFooter = new LoadingCardAdapter();
        recyclerView.setAdapter(new ConcatAdapter(adapter, loadingFooter));
        recyclerView.setItemAnimator(null);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
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
                                if (getView() != null && !isLoading && currentPage < totalPages) {
                                    currentPage++; loadTopics();
                                }
                            });
                        }
                    }
                }
            }
        });
        contentContainer.addView(recyclerView, 0);
        pageError = com.fimtale.ui.PageErrorView.wrap(recyclerView);
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> isLoading || recyclerView.canScrollVertically(-1));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            currentPage = 1;
            loadTopics();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (emptyStateLayout != null && emptyStateLayout.getVisibility() == View.VISIBLE ) {
            loadContent();
        }
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

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.work_choose_sort))
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    currentSortBy = values[which];
                    dialog.dismiss();
                    currentPage = 1;
                    loadTopics();
                })
                .show();
    }

    private void loadTopics() {
        pageError.hide();
        if (isLoading) {
            if (currentPage != 1) return;
            if (topicsCall != null) topicsCall.cancel();
        }
        isLoading = true;
        final int requestedPage = currentPage;
        tvNoResults.setVisibility(View.GONE);
        boolean replacing = requestedPage == 1 && !swipeRefreshLayout.isRefreshing();
        loadingSkeleton.setVisibility(replacing ? View.VISIBLE : View.GONE);
        loadingFooter.setLoading(requestedPage > 1);
        recyclerView.setVisibility(replacing ? View.INVISIBLE : View.VISIBLE);

        topicsCall = RetrofitClient.getInstance().getTopicList(requestedPage,
                null, com.fimtale.network.SearchQuery.rank(currentSortBy));
        topicsCall.enqueue(new Callback<TopicListResponse>() {
            @Override public void onResponse(Call<TopicListResponse> call, Response<TopicListResponse> response) {
                if (!isAdded() || getView() == null || call.isCanceled() || call != topicsCall) return;
                if (response.isSuccessful() && response.body() != null) {
                    TopicListResponse data = response.body();
                    totalPages = data.getTotalPage();
                    if (requestedPage == 1) dataList.clear();
                    int start = dataList.size();
                    List<Topic> topics = data.getTopicArray();
                    if (topics != null) for (Topic topic : topics) dataList.add(new TopicViewItem(topic));
                    if (requestedPage == 1) {
                        adapter.notifyDataSetChanged(); recyclerView.scrollToPosition(0);
                    } else adapter.notifyItemRangeInserted(start, dataList.size() - start);
                    finishLoading();
                    tvNoResults.showEmpty(getString(R.string.search_no_results), () -> { currentPage = 1; loadTopics(); });
                    tvNoResults.setVisibility(dataList.isEmpty() ? View.VISIBLE : View.GONE);
                } else loadFailed(requestedPage, com.fimtale.network.ApiErrors.message(response));
            }
            @Override public void onFailure(Call<TopicListResponse> call, Throwable t) {
                if (!isAdded() || getView() == null || call.isCanceled() || call != topicsCall) return;
                loadFailed(requestedPage, getString(R.string.common_load_failed_retry));
            }
        });
    }
    private void finishLoading() {
        isLoading = false;
        loadingSkeleton.setVisibility(View.GONE); loadingFooter.setLoading(false);
        recyclerView.setVisibility(View.VISIBLE); swipeRefreshLayout.setRefreshing(false);
    }
    private void loadFailed(int requestedPage, String message) {
        currentPage = Math.max(1, requestedPage - 1);
        finishLoading();
        pageError.show(message, () -> { currentPage = requestedPage; loadTopics(); }, !dataList.isEmpty());
    }
    @Override public void onDestroyView() {
        pageError = null;
        if (topicsCall != null) { topicsCall.cancel(); topicsCall = null; }
        isLoading = false;
        super.onDestroyView();
    }
}
