package com.fimtale;

import com.fimtale.utils.MdiIcons;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fimtale.adapter.LoadingCardAdapter;
import com.fimtale.adapter.SearchHistoryAdapter;
import com.fimtale.adapter.TopicAdapter;
import com.fimtale.model.Topic;
import com.fimtale.model.TopicListResponse;
import com.fimtale.model.TopicViewItem;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SearchQuery;
import com.fimtale.ui.ShimmerSkeletonView;
import com.fimtale.ui.PullRefreshLayout;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Full-page work search with its own query, history and result state. */
public class SearchActivity extends AppCompatActivity {
    private com.fimtale.ui.PageErrorView pageError;
    public static final String EXTRA_QUERY = "query";

    private MaterialToolbar toolbar;
    private TextInputEditText searchInput;
    private MaterialButton searchButton;
    private PullRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private RecyclerView historyRecyclerView;
    private View historyPanel;
    private MaterialButton clearHistoryButton;
    private ShimmerSkeletonView loadingSkeleton;
    private com.fimtale.ui.PageErrorView loadingStatus;
    private LoadingCardAdapter loadingFooter;
    private TopicAdapter topicAdapter;
    private SearchHistoryAdapter historyAdapter;
    private final List<TopicViewItem> topicItems = new ArrayList<>();

    private Call<TopicListResponse> topicsCall;
    private int currentPage = 1;
    private int totalPages = 1;
    private boolean isLoading;
    private String currentSortBy = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        getWindow().setStatusBarColor(typedValue.data);

        setupViews();
        String initialQuery = getIntent().getStringExtra(EXTRA_QUERY);
        if (initialQuery != null && !initialQuery.trim().isEmpty()) {
            searchInput.setText(initialQuery.trim());
            searchInput.setSelection(searchInput.length());
            submitSearch();
        } else {
            showHistory();
        }
    }

    private void setupViews() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(MdiIcons.drawable(this, "arrow-left"));
            getSupportActionBar().setTitle(getString(R.string.search_title));
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        searchInput = findViewById(R.id.searchInput);
        searchButton = findViewById(R.id.searchButton);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        recyclerView = findViewById(R.id.recyclerView);
        pageError = com.fimtale.ui.PageErrorView.wrap(recyclerView);
        historyRecyclerView = findViewById(R.id.historyRecyclerView);
        historyPanel = findViewById(R.id.historyPanel);
        clearHistoryButton = findViewById(R.id.clearHistoryButton);
        loadingSkeleton = findViewById(R.id.loadingSkeleton);
        loadingStatus = findViewById(R.id.loadingStatus);
        loadingSkeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.ARTICLES);

        topicAdapter = new TopicAdapter(topicItems);
        loadingFooter = new LoadingCardAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new ConcatAdapter(topicAdapter, loadingFooter));
        recyclerView.setItemAnimator(null);

        historyAdapter = new SearchHistoryAdapter(new ArrayList<>(), new SearchHistoryAdapter.OnHistoryClickListener() {
            @Override public void onHistoryClick(String query) {
                searchInput.setText(query);
                searchInput.setSelection(searchInput.length());
                submitSearch();
            }

            @Override public void onDeleteClick(String query) {
                UserPreferences.removeSearchHistoryItem(SearchActivity.this, query);
                showHistory();
            }
        });
        historyRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        historyRecyclerView.setAdapter(historyAdapter);

        searchButton.setOnClickListener(v -> submitSearch());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitSearch();
                return true;
            }
            return false;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.toString().trim().isEmpty()) showHistory();
                else {
                    historyPanel.setVisibility(View.GONE);
                    loadingStatus.setVisibility(View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        clearHistoryButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.common_confirm_clear))
                .setMessage(getString(R.string.search_clear_history_message))
                .setPositiveButton(getString(R.string.common_clear), (dialog, which) -> {
                    UserPreferences.clearSearchHistory(this);
                    showHistory();
                })
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show());
        swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> isLoading
                || (recyclerView.getVisibility() == View.VISIBLE && recyclerView.canScrollVertically(-1)));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (!searchInput.getText().toString().trim().isEmpty()) loadTopics(1);
            else showHistory();
        });

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy <= 0 || isLoading || currentPage >= totalPages) return;
                LinearLayoutManager manager = (LinearLayoutManager) view.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= manager.getItemCount() - 3) {
                    view.post(() -> {
                        if (!isFinishing() && !isDestroyed() && !isLoading && currentPage < totalPages) {
                            loadTopics(currentPage + 1);
                        }
                    });
                }
            }
        });
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        MdiIcons.inflateMenu(this, getMenuInflater(), R.menu.menu_search, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_filter) {
            showFilterDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void submitSearch() {
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            MdiIcons.setError(searchInput, getString(R.string.search_query_required));
            showHistory();
            return;
        }
        searchInput.setError(null);
        UserPreferences.saveSearchHistory(this, query);
        currentPage = 1;
        loadTopics(1);
        searchInput.clearFocus();
    }

    private void showHistory() {
        swipeRefreshLayout.setRefreshing(false);
        pageError.hide();
        if (topicsCall != null) { topicsCall.cancel(); topicsCall = null; }
        isLoading = false;
        loadingSkeleton.setVisibility(View.GONE);
        loadingFooter.setLoading(false);
        recyclerView.setVisibility(View.GONE);
        List<String> history = UserPreferences.getSearchHistory(this);
        historyAdapter.updateData(history);
        historyPanel.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        loadingStatus.showPrompt(R.string.search_title, getString(R.string.search_empty_hint),
                "magnify", R.string.search_title, null);
        loadingStatus.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadTopics(int page) {
        pageError.hide();
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
        if (query.isEmpty()) { showHistory(); return; }
        if (isLoading && page != 1) return;
        if (topicsCall != null) topicsCall.cancel();
        isLoading = true;
        final int requestedPage = page;
        historyPanel.setVisibility(View.GONE);
        loadingStatus.setVisibility(View.GONE);
        boolean replacing = page == 1 && !swipeRefreshLayout.isRefreshing();
        loadingSkeleton.setVisibility(replacing ? View.VISIBLE : View.GONE);
        loadingFooter.setLoading(page > 1);
        recyclerView.setVisibility(replacing ? View.INVISIBLE : View.VISIBLE);

        topicsCall = RetrofitClient.getInstance().getTopicList(page,
                SearchQuery.keywords(query), SearchQuery.rank(currentSortBy));
        topicsCall.enqueue(new Callback<TopicListResponse>() {
            @Override public void onResponse(Call<TopicListResponse> call, Response<TopicListResponse> response) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != topicsCall) return;
                if (response.isSuccessful() && response.body() != null) {
                    TopicListResponse data = response.body();
                    currentPage = requestedPage;
                    totalPages = Math.max(1, data.getTotalPage());
                    if (requestedPage == 1) topicItems.clear();
                    int start = topicItems.size();
                    List<Topic> topics = data.getTopicArray();
                    if (topics != null) for (Topic topic : topics) topicItems.add(new TopicViewItem(topic));
                    if (requestedPage == 1) topicAdapter.notifyDataSetChanged();
                    else topicAdapter.notifyItemRangeInserted(start, topicItems.size() - start);
                    finishLoading();
                    recyclerView.scrollToPosition(requestedPage == 1 ? 0 : start);
                    if (topicItems.isEmpty()) {
                        loadingStatus.showEmpty(getString(R.string.search_no_results), () -> loadTopics(1));
                        loadingStatus.setVisibility(View.VISIBLE);
                    }
                } else showLoadError(page, ApiErrors.message(response));
            }

            @Override public void onFailure(Call<TopicListResponse> call, Throwable t) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != topicsCall) return;
                showLoadError(page, getString(R.string.error_network_retry));
            }
        });
    }

    private void finishLoading() {
        isLoading = false;
        loadingSkeleton.setVisibility(View.GONE);
        loadingFooter.setLoading(false);
        recyclerView.setVisibility(View.VISIBLE);
        swipeRefreshLayout.setRefreshing(false);
    }

    private void showLoadError(int page, String message) {
        finishLoading();
        loadingStatus.setVisibility(View.GONE);
        pageError.show(message, () -> loadTopics(page), !topicItems.isEmpty());
    }

    private void showFilterDialog() {
        final String[] options = getResources().getStringArray(R.array.work_sort_options);
        final String[] values = {"", "created_at", "last_chapter_at", "commented_at", "count_character", "count_comment", "count_view", "wilson_score"};
        int checkedItem = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentSortBy)) { checkedItem = i; break; }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.work_choose_sort))
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    currentSortBy = values[which];
                    dialog.dismiss();
                    if (!searchInput.getText().toString().trim().isEmpty()) loadTopics(1);
                }).show();
    }

    @Override protected void onDestroy() {
        if (topicsCall != null) { topicsCall.cancel(); topicsCall = null; }
        super.onDestroy();
    }
}
