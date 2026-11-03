package com.fimtale;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ConcatAdapter;
import com.fimtale.adapter.LoadingCardAdapter;
import com.fimtale.ui.PageErrorView;
import com.fimtale.ui.PullToRefresh;
import com.fimtale.ui.ShimmerSkeletonView;
import com.fimtale.utils.MdiIcons;
import com.fimtale.adapter.HistoryAdapter;
import com.fimtale.model.DeleteReadProgressRequest;
import com.fimtale.model.HistoryResponse;
import com.fimtale.model.HistoryResponse.HistoryTopic;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HistoryActivity extends AppCompatActivity {
    private PageErrorView pageError;

    private HistoryAdapter adapter;
    private RecyclerView recyclerView;
    private ShimmerSkeletonView loadingSkeleton;
    private LoadingCardAdapter loadingFooter;
    private com.fimtale.ui.PageErrorView loadingStatus;
    private Call<HistoryResponse> activeCall;
    private Call<Void> deleteCall;
    private AlertDialog deleteDialog;
    private MaterialToolbar toolbar;
    private MaterialCardView toolbarContainer;
    private boolean isToolbarElevated = false;
    private ObjectAnimator elevationAnimator;
    private int currentPage = 1;
    private int totalPages = 1;
    private boolean isLoading = false;
    private boolean isDeleting = false;
    private boolean needsReload = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_history);
        toolbar.setNavigationOnClickListener(v -> back());
        toolbar.setOnMenuItemClickListener(item -> {
            if (!item.isEnabled()) return false;
            if (item.getItemId() == R.id.action_history_batch) {
                adapter.setBatchMode(!adapter.isBatchMode());
                return true;
            }
            if (item.getItemId() == R.id.action_history_delete) {
                confirmDeletion(adapter.getSelectedTopics());
                return true;
            }
            if (item.getItemId() == R.id.action_history_select_all) {
                if (!isBusy()) adapter.toggleSelectAll();
                return true;
            }
            return false;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });

        toolbarContainer = findViewById(R.id.toolbarContainer);
        loadingSkeleton = findViewById(R.id.loadingSkeleton);
        loadingSkeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.HISTORY);
        loadingFooter = new LoadingCardAdapter(ShimmerSkeletonView.Layout.HISTORY_ROW);
        loadingStatus = findViewById(R.id.loadingStatus);

        recyclerView = findViewById(R.id.recyclerView);
        View listSurface = PageErrorView.withEmptyState(recyclerView, loadingStatus);
        pageError = PageErrorView.wrap(listSurface);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        findViewById(R.id.historyAppBar).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            recyclerView.setPadding(recyclerView.getPaddingLeft(), bottom,
                    recyclerView.getPaddingRight(), recyclerView.getPaddingBottom());
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) loadingSkeleton.getLayoutParams();
            if (params.topMargin != bottom) {
                params.topMargin = bottom;
                loadingSkeleton.setLayoutParams(params);
            }
        });

        float targetElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                boolean shouldElevate = recyclerView.canScrollVertically(-1);

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

                        if (!isBusy() && (visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                                && firstVisibleItemPosition >= 0
                                && currentPage < totalPages) {
                            recyclerView.post(() -> {
                                if (!isFinishing() && !isDestroyed() && !isBusy() && currentPage < totalPages) loadHistory(currentPage + 1);
                            });
                        }
                    }
                }
            }
        });

        adapter = new HistoryAdapter();
        adapter.setOnItemClickListener(topic -> {
            if (topic.isWorkEntry()) {
                startActivity(new Intent(this, TopicDetailActivity.class)
                        .putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, topic.getWorkId()));
                return;
            }
            Intent intent = new Intent(HistoryActivity.this, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_CHAPTER_ID, topic.getChapterId());
            intent.putExtra(ReaderActivity.EXTRA_WORK_ID, topic.getWorkId());
            intent.putExtra(ReaderActivity.EXTRA_INITIAL_PROGRESS, topic.getProgress());
            startActivity(intent);
        });
        adapter.setOnSelectionChangedListener(this::updateSelectionUi);
        recyclerView.setAdapter(new ConcatAdapter(adapter, loadingFooter));
        recyclerView.setItemAnimator(null);
        PullToRefresh.attach(listSurface, () -> loadHistory(1), () -> !isBusy(), () -> recyclerView.canScrollVertically(-1));

        loadHistory(1);
    }

    private void loadHistory(int page) {
        if (needsReload || (page == 1 && adapter.getSelectedCount() > 0)) loadHistory(1, currentPage);
        else loadHistory(page, page);
    }

    private void loadHistory(int page, int lastPage) {
        if (isBusy()) return;
        pageError.hide();
        isLoading = true;
        loadingStatus.setVisibility(View.GONE);
        boolean replacing = page == 1 && !PullToRefresh.isRefreshing(recyclerView);
        loadingSkeleton.setVisibility(replacing ? View.VISIBLE : View.GONE);
        loadingFooter.setLoading(page > 1);
        recyclerView.setVisibility(replacing ? View.INVISIBLE : View.VISIBLE);
        updateSelectionUi();
        requestHistoryPage(page, lastPage, page == 1 ? new ArrayList<>() : null);
    }

    private void requestHistoryPage(int page, int lastPage, List<HistoryTopic> replacement) {
        activeCall = RetrofitClient.getInstance().getHistory(page);
        activeCall.enqueue(new Callback<HistoryResponse>() {
            @Override public void onResponse(Call<HistoryResponse> call, Response<HistoryResponse> response) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != activeCall) return;
                if (response.isSuccessful() && response.body() != null) {
                    int pages = response.body().getTotalPage();
                    List<HistoryTopic> history = response.body().getHistoryTopics();
                    if (replacement != null) {
                        replacement.addAll(history);
                        // Deletions shift page boundaries. Replace the loaded prefix together so
                        // remaining selections on later pages survive a partial deletion failure.
                        if (page < Math.min(lastPage, pages)) {
                            requestHistoryPage(page + 1, lastPage, replacement);
                            return;
                        }
                        adapter.setHistoryTopics(replacement);
                        needsReload = false;
                    } else adapter.addHistoryTopics(history);
                    currentPage = Math.min(page, Math.max(1, pages));
                    totalPages = pages;
                    finishLoading();
                    if (replacement != null && lastPage == 1) recyclerView.scrollToPosition(0);
                    if (adapter.getItemCount() == 0) {
                        loadingStatus.showEmpty(getString(R.string.history_empty), () -> loadHistory(1));
                        loadingStatus.setVisibility(View.VISIBLE);
                    }
                } else showLoadError(replacement != null ? 1 : page, lastPage, ApiErrors.message(response));
            }
            @Override public void onFailure(Call<HistoryResponse> call, Throwable t) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != activeCall) return;
                showLoadError(replacement != null ? 1 : page, lastPage, getString(R.string.error_network_retry));
            }
        });
    }

    private void finishLoading() {
        PullToRefresh.finish(recyclerView);
        isLoading = false;
        activeCall = null;
        loadingSkeleton.setVisibility(View.GONE);
        loadingFooter.setLoading(false);
        recyclerView.setVisibility(View.VISIBLE);
        updateSelectionUi();
    }

    private void showLoadError(int page, int lastPage, String message) {
        finishLoading();
        loadingStatus.setVisibility(View.GONE);
        pageError.show(message, () -> loadHistory(page, lastPage), adapter.getItemCount() > 0);
    }

    private boolean isBusy() { return isLoading || isDeleting || deleteDialog != null; }

    private void updateSelectionUi() {
        boolean batchMode = adapter.isBatchMode();
        int count = adapter.getSelectedCount();
        toolbar.setTitle(batchMode ? getString(R.string.history_selected_count, count) : getString(R.string.history_title));
        MenuItem deleteAction = toolbar.getMenu().findItem(R.id.action_history_delete);
        deleteAction.setVisible(batchMode);
        deleteAction.setEnabled(batchMode && !isBusy() && count > 0);
        deleteAction.setIcon(MdiIcons.drawable(this, "delete-outline"));
        deleteAction.getIcon().setAlpha(deleteAction.isEnabled() ? 255 : 97);
        MenuItem selectAllAction = toolbar.getMenu().findItem(R.id.action_history_select_all);
        selectAllAction.setVisible(batchMode);
        selectAllAction.setEnabled(batchMode && !isBusy() && adapter.getItemCount() > 0);
        selectAllAction.setTitle(adapter.areAllSelected() ? R.string.history_clear_selection : R.string.history_select_all);
        selectAllAction.setIcon(MdiIcons.drawable(this, adapter.areAllSelected() ? "select-off" : "select-all"));
        selectAllAction.getIcon().setAlpha(selectAllAction.isEnabled() ? 255 : 97);
        MenuItem batchAction = toolbar.getMenu().findItem(R.id.action_history_batch);
        batchAction.setTitle(batchMode ? R.string.history_exit_batch_select : R.string.history_batch_select);
        batchAction.setEnabled(!isDeleting && deleteDialog == null
                && (batchMode || (!isLoading && adapter.getItemCount() > 0)));
        batchAction.setIcon(MdiIcons.drawable(this, batchMode ? "close" : "checkbox-multiple-marked-outline"));
        batchAction.getIcon().setAlpha(batchAction.isEnabled() ? 255 : 97);
        adapter.setInteractionEnabled(!isBusy());
    }

    private void back() {
        if (isDeleting) return;
        if (adapter.isBatchMode()) adapter.setBatchMode(false);
        else finish();
    }

    private void confirmDeletion(List<HistoryTopic> entries) {
        if (isBusy() || entries.isEmpty()) return;
        List<HistoryTopic> pending = new ArrayList<>(entries);
        String message;
        if (pending.size() == 1) {
            HistoryTopic item = pending.get(0);
            message = getString(item.isWorkEntry() ? R.string.history_delete_work_message
                    : R.string.history_delete_chapter_message, item.getTitle());
        } else {
            message = getString(R.string.history_delete_batch_message, pending.size());
            for (HistoryTopic item : pending) {
                if (item.isWorkEntry()) {
                    message += "\n\n" + getString(R.string.history_delete_batch_work_warning);
                    break;
                }
            }
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_delete_title).setMessage(message)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_delete, null).create();
        deleteDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (deleteDialog == dialog) deleteDialog = null;
            if (!isDestroyed()) updateSelectionUi();
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (isDeleting) return;
            isDeleting = true;
            needsReload = true;
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.history_deleting);
            updateSelectionUi();
            deleteNext(pending);
        });
        updateSelectionUi();
    }

    private void deleteNext(List<HistoryTopic> pending) {
        if (pending.isEmpty()) {
            finishDeletion(null);
            return;
        }
        HistoryTopic item = pending.get(0);
        deleteCall = RetrofitClient.getInstance().deleteReadProgress(new DeleteReadProgressRequest(item));
        deleteCall.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != deleteCall) return;
                if (!response.isSuccessful()) {
                    finishDeletion(ApiErrors.message(response));
                    return;
                }
                adapter.removeHistoryTopic(item);
                // A work deletion also covers any selected chapter entries for that work.
                pending.removeIf(topic -> topic.isRemovedBy(item));
                deleteNext(pending);
            }

            @Override public void onFailure(Call<Void> call, Throwable error) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != deleteCall) return;
                finishDeletion(getString(R.string.error_network_retry));
            }
        });
    }

    private void finishDeletion(String error) {
        deleteCall = null;
        isDeleting = false;
        AlertDialog dialog = deleteDialog;
        deleteDialog = null;
        if (dialog != null) dialog.dismiss();
        if (error != null) Toast.makeText(this, getString(R.string.history_delete_failed, error), Toast.LENGTH_LONG).show();
        loadHistory(1, currentPage);
    }

    @Override protected void onDestroy() {
        if (activeCall != null) { activeCall.cancel(); activeCall = null; }
        if (deleteCall != null) { deleteCall.cancel(); deleteCall = null; }
        if (deleteDialog != null) {
            deleteDialog.setOnDismissListener(null);
            deleteDialog.dismiss();
            deleteDialog = null;
        }
        if (elevationAnimator != null) elevationAnimator.cancel();
        super.onDestroy();
    }
}
