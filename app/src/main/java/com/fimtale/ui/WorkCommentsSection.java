package com.fimtale.ui;


import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.R;
import com.fimtale.adapter.CommentAdapter;
import com.fimtale.model.WorkCommentsResponse;
import com.fimtale.model.CommentResponse;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import java.util.ArrayList;
import java.util.function.IntConsumer;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Paged comments in the detail page's scroll content, loaded when they come into view. */
public final class WorkCommentsSection {
    private static final int PER_PAGE = 16;
    private final AppCompatActivity activity;
    private final int workId;
    private final IntConsumer countUpdated;
    private final View root, pager;
    private final NestedScrollView scroll;
    private final RecyclerView list;
    private final CommentAdapter adapter;
    private final ShimmerSkeletonView skeleton;
    private final TextView status, pageLabel, title, sort;
    private final View previous, next;
    private final Rect visibleBounds = new Rect();
    private PageErrorView pageError;
    private Call<WorkCommentsResponse> activeCall;
    private Call<CommentResponse> locationCall;
    private int focusComment;
    private int page = 1, totalPages = 1;
    private boolean started, loading, descending, closed;

    public WorkCommentsSection(AppCompatActivity activity, int workId, IntConsumer countUpdated) {
        this.activity = activity; this.workId = workId; this.countUpdated = countUpdated;
        root = activity.findViewById(R.id.workCommentsSection);
        scroll = activity.findViewById(R.id.scrollView);
        list = root.findViewById(R.id.commentsList);
        pageError = PageErrorView.wrap(list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new CommentAdapter(new ArrayList<>(), activity);
        adapter.setContext(workId, 0, () -> load(page));
        list.setAdapter(adapter); list.setItemAnimator(null);
        skeleton = root.findViewById(R.id.commentsSkeleton);
        skeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.COMMENTS);
        skeleton.setVisibility(View.INVISIBLE);
        status = root.findViewById(R.id.commentsStatus);
        title = root.findViewById(R.id.commentsTitle);
        pageLabel = root.findViewById(R.id.commentsPage);
        pager = root.findViewById(R.id.commentsPager);
        previous = root.findViewById(R.id.commentsPrevious);
        next = root.findViewById(R.id.commentsNext);
        sort = root.findViewById(R.id.commentsSort);
        previous.setOnClickListener(v -> changePage(page - 1));
        next.setOnClickListener(v -> changePage(page + 1));
        sort.setOnClickListener(v -> {
            descending = !descending;
            sort.setText(descending ? activity.getString(R.string.comments_newest_first) : activity.getString(R.string.comments_oldest_first));
            changePage(1);
        });
    }

    public void setCount(int count) { title.setText(activity.getString(R.string.comments_count, count)); }
    public void setWorkAuthor(int authorId) { adapter.setWorkAuthor(authorId); }
    public void setOnReply(java.util.function.Consumer<com.fimtale.model.Comment> reply) { adapter.setOnReply(reply); }

    public void focus(int commentId) {
        if (closed || commentId <= 0) return;
        if (locationCall != null) locationCall.cancel();
        focusComment = commentId;
        locationCall = RetrofitClient.getInstance().getCommentLocation(commentId, PER_PAGE, "created_at", descending ? "desc" : "asc");
        locationCall.enqueue(new Callback<CommentResponse>() {
            @Override public void onResponse(Call<CommentResponse> call, Response<CommentResponse> response) {
                if (closed || call.isCanceled() || call != locationCall) return;
                CommentResponse result = response.body();
                if (response.isSuccessful() && result != null && result.comment != null && result.comment.workId == workId) {
                    load(result.pageNumber == null ? 1 : Math.max(1, result.pageNumber));
                } else { focusComment = 0; open(); }
            }
            @Override public void onFailure(Call<CommentResponse> call, Throwable failure) {
                if (!closed && !call.isCanceled() && call == locationCall) { focusComment = 0; open(); }
            }
        });
    }

    public void loadIfVisible() {
        if (!started && !closed && root.isShown() && root.getGlobalVisibleRect(visibleBounds)) load(1);
    }

    public void open() {
        if (closed) return;
        if (!started) load(1);
        scrollToComments();
    }

    public void refresh() {
        if (!closed) load(1);
    }

    /** After posting, match the website's newest-first view so the new comment is visible immediately. */
    public void refreshLatest() {
        if (closed) return;
        descending = true;
        sort.setText(activity.getString(R.string.comments_newest_first));
        load(1);
    }

    public int top() { return root.getTop(); }

    private void scrollToComments() {
        scroll.post(() -> { if (!closed) scroll.smoothScrollTo(0, root.getTop()); });
    }

    private void changePage(int requestedPage) {
        if (loading) return;
        load(requestedPage); scrollToComments();
    }

    private boolean valid(Call<?> call) {
        return !closed && !activity.isFinishing() && !activity.isDestroyed() && !call.isCanceled() && call == activeCall;
    }

    private void load(int requestedPage) {
        pageError.hide();
        if (closed || requestedPage < 1) return;
        if (activeCall != null) activeCall.cancel();
        started = true; loading = true; updatePager();
        status.setVisibility(View.GONE);
        list.setVisibility(View.GONE); skeleton.setVisibility(View.VISIBLE);
        activeCall = RetrofitClient.getInstance().getWorkComments(workId, requestedPage, PER_PAGE, "created_at", descending ? "desc" : "asc");
        activeCall.enqueue(new Callback<WorkCommentsResponse>() {
            @Override public void onResponse(Call<WorkCommentsResponse> call, Response<WorkCommentsResponse> response) {
                if (!valid(call)) return;
                if (response.isSuccessful() && response.body() != null) {
                    WorkCommentsResponse result = response.body();
                    totalPages = result.totalPages(PER_PAGE);
                    if (requestedPage > totalPages) { load(totalPages); return; }
                    page = requestedPage;
                    adapter.updateData(result.getItems(), result.viewer);
                    loading = false; updatePager();
                    skeleton.setVisibility(View.GONE); list.setVisibility(View.VISIBLE);
                    countUpdated.accept(result.total);
                    if (focusComment > 0) {
                        int position = adapter.positionOf(focusComment);
                        focusComment = 0;
                        scrollToComments();
                        if (position >= 0) list.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override public void onGlobalLayout() {
                                list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                if (closed) return;
                                View row = list.getLayoutManager().findViewByPosition(position);
                                if (row != null) {
                                    Rect bounds = new Rect(); row.getDrawingRect(bounds);
                                    scroll.offsetDescendantRectToMyCoords(row, bounds);
                                    scroll.smoothScrollTo(0, Math.max(0, bounds.top - 16));
                                    row.setPressed(true); row.postDelayed(() -> row.setPressed(false), 600);
                                }
                            }
                        });
                    }
                    if (result.getItems().isEmpty()) {
                        status.setText(activity.getString(R.string.comments_empty_tap)); status.setVisibility(View.VISIBLE);
                        status.setOnClickListener(v -> load(1));
                    }
                } else showError(requestedPage, ApiErrors.message(response));
            }
            @Override public void onFailure(Call<WorkCommentsResponse> call, Throwable error) {
                if (valid(call)) showError(requestedPage, activity.getString(R.string.comments_load_failed));
            }
        });
    }

    private void showError(int requestedPage, String message) {
        loading = false; updatePager();
        skeleton.setVisibility(View.GONE);
        status.setVisibility(View.GONE); list.setVisibility(View.VISIBLE);
        pageError.show(message, () -> load(requestedPage), adapter.getItemCount() > 0);
    }

    private void updatePager() {
        pager.setVisibility(totalPages > 1 ? View.VISIBLE : View.GONE);
        previous.setEnabled(!loading && page > 1);
        next.setEnabled(!loading && page < totalPages);
        sort.setEnabled(!loading);
        pageLabel.setText(activity.getString(R.string.common_pagination, page, totalPages));
    }

    public void close() {
        closed = true;
        if (activeCall != null) activeCall.cancel();
        if (locationCall != null) locationCall.cancel();
        adapter.close();
        list.setAdapter(null);
    }
}
