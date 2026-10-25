package com.fimtale.ui;


import com.fimtale.utils.MdiIcons;

import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fimtale.LoginActivity;
import com.fimtale.R;
import com.fimtale.adapter.CommentAdapter;
import com.fimtale.model.WorkCommentRequest;
import com.fimtale.model.WorkCommentsResponse;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Comments and composer used by the reader's inter-chapter page and bottom sheet. */
public final class ReaderCommentsPanel {
    private static final int PER_PAGE = 16;

    private final AppCompatActivity activity;
    private final View root;
    private final int workId;
    private final RecyclerView list;
    private final CommentAdapter adapter;
    private final ShimmerSkeletonView skeleton;
    private final TextView title, status, pageLabel;
    private final PullRefreshLayout refresh;
    private final MaterialButton sort, previous, next, send, emoji;
    private final TextInputEditText input;
    private final CommentReplyInput replyInput;
    private final View pager;
    private PageErrorView pageError;
    private Call<WorkCommentsResponse> activeCall;
    private Call<Void> commentCall;
    private int chapterId = -1;
    private int page = 1;
    private int totalPages = 1;
    private boolean descending;
    private boolean loading;
    private boolean sending;
    private boolean closed;

    public ReaderCommentsPanel(AppCompatActivity activity, View root, int workId) {
        this.activity = activity;
        this.root = root;
        this.workId = workId;
        list = root.findViewById(R.id.readerCommentsList);
        pageError = PageErrorView.wrap(list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new CommentAdapter(new ArrayList<>(), activity);
        adapter.setContext(workId, 0, () -> load(page));
        list.setAdapter(adapter);
        list.setItemAnimator(null);

        skeleton = root.findViewById(R.id.readerCommentsSkeleton);
        skeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.COMMENTS);
        title = root.findViewById(R.id.readerCommentsTitle);
        status = root.findViewById(R.id.readerCommentsStatus);
        pageLabel = root.findViewById(R.id.readerCommentsPage);
        pager = root.findViewById(R.id.readerCommentsPager);
        refresh = root.findViewById(R.id.readerCommentsRefreshLayout);
        sort = root.findViewById(R.id.readerCommentsSort);
        previous = root.findViewById(R.id.readerCommentsPrevious);
        next = root.findViewById(R.id.readerCommentsNext);
        input = root.findViewById(R.id.readerCommentComposerInput);
        emoji = root.findViewById(R.id.readerCommentComposerEmoji);
        send = root.findViewById(R.id.readerCommentComposerSend);
        replyInput = new CommentReplyInput(root.findViewById(R.id.readerCommentComposerInputLayout), input,
                () -> root.setTag(R.id.readerCommentComposerInputLayout, replyState()));
        adapter.setOnReply(comment -> {
            if (closed || sending) return;
            replyInput.reply(comment, workId); replyInput.focus();
        });

        refresh.setOnChildScrollUpCallback((parent, child) -> closed || loading || list.canScrollVertically(-1));
        refresh.setOnRefreshListener(() -> load(1));
        sort.setOnClickListener(v -> {
            descending = !descending;
            sort.setText(descending ? activity.getString(R.string.comments_newest_first) : activity.getString(R.string.comments_oldest_first));
            load(1);
        });
        previous.setOnClickListener(v -> load(page - 1));
        next.setOnClickListener(v -> load(page + 1));
        send.setOnClickListener(v -> submit());
        emoji.setOnClickListener(v -> FtemojiPicker.show(activity,
                name -> insert(":ftemoji_" + name + ":")));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
    }

    public void setWorkAuthor(int authorId) { adapter.setWorkAuthor(authorId); }

    public void bind(int chapterId) {
        if (closed || chapterId < 0) return;
        if (this.chapterId != chapterId) {
            if (commentCall != null) { commentCall.cancel(); finishSubmit(); }
            adapter.updateData(new ArrayList<>());
        }
        // A recycled view may get a new panel while still showing the same chapter.
        Object previousChapter = root.getTag(R.id.readerCommentsRoot);
        if (previousChapter != null && !Integer.valueOf(chapterId).equals(previousChapter)) {
            input.setText(""); replyInput.clear();
        } else {
            Object saved = root.getTag(R.id.readerCommentComposerInputLayout);
            replyInput.restore(saved instanceof android.os.Bundle ? (android.os.Bundle) saved : null, workId);
        }
        root.setTag(R.id.readerCommentsRoot, chapterId);
        this.chapterId = chapterId;
        page = 1;
        totalPages = 1;
        refresh.setRefreshing(false);
        title.setText(activity.getString(R.string.comments_title));
        sort.setText(descending ? activity.getString(R.string.comments_newest_first) : activity.getString(R.string.comments_oldest_first));
        load(1);
    }

    private boolean valid(Call<?> call) {
        return !closed && !activity.isFinishing() && !activity.isDestroyed()
                && !call.isCanceled() && call == activeCall;
    }

    private void load(int requestedPage) {
        if (closed || requestedPage < 1) return;
        pageError.hide();
        if (activeCall != null) activeCall.cancel();
        updatePager(true);
        status.setVisibility(View.GONE);
        boolean pulling = refresh.isRefreshing();
        list.setVisibility(pulling ? View.VISIBLE : View.INVISIBLE);
        skeleton.setVisibility(pulling ? View.GONE : View.VISIBLE);
        activeCall = RetrofitClient.getInstance().getWorkComments(workId, chapterId > 0 ? chapterId : null, requestedPage, PER_PAGE,
                "created_at", descending ? "desc" : "asc");
        activeCall.enqueue(new Callback<WorkCommentsResponse>() {
            @Override public void onResponse(Call<WorkCommentsResponse> call, Response<WorkCommentsResponse> response) {
                if (!valid(call)) return;
                if (response.isSuccessful() && response.body() != null) {
                    WorkCommentsResponse result = response.body();
                    totalPages = result.totalPages(PER_PAGE);
                    if (requestedPage > totalPages) { load(totalPages); return; }
                    page = requestedPage;
                    adapter.updateData(result.getItems(), result.viewer);
                    list.scrollToPosition(0);
                    title.setText(activity.getString(R.string.comments_count, result.total));
                    finishLoading();
                    if (result.getItems().isEmpty()) {
                        status.setText(activity.getString(R.string.comments_empty_pull));
                        status.setVisibility(View.VISIBLE);
                    }
                } else showError(requestedPage, ApiErrors.message(response));
            }

            @Override public void onFailure(Call<WorkCommentsResponse> call, Throwable error) {
                if (valid(call)) showError(requestedPage, activity.getString(R.string.comments_load_failed));
            }
        });
    }

    private void showError(int requestedPage, String message) {
        finishLoading();
        status.setVisibility(View.GONE);
        pageError.show(message, () -> load(requestedPage), adapter.getItemCount() > 0);
    }

    private void finishLoading() {
        refresh.setRefreshing(false);
        skeleton.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        updatePager(false);
    }

    private void updatePager(boolean loading) {
        this.loading = loading;
        pager.setVisibility(totalPages > 1 ? View.VISIBLE : View.GONE);
        previous.setEnabled(!loading && page > 1);
        next.setEnabled(!loading && page < totalPages);
        sort.setEnabled(!loading);
        pageLabel.setText(activity.getString(R.string.common_pagination, page, totalPages));
    }

    private void submit() {
        if (sending || closed) return;
        String content = input.getText() == null ? "" : input.getText().toString().trim();
        if (content.isEmpty()) {
            MdiIcons.setError(input, activity.getString(R.string.comments_content_required));
            return;
        }
        if (!UserPreferences.isLoggedIn(activity)) {
            activity.startActivity(new Intent(activity, LoginActivity.class));
            return;
        }
        if (!replyInput.canSubmit()) return;

        String token = UserPreferences.getToken(activity);
        sending = true;
        input.setEnabled(false);
        emoji.setEnabled(false);
        send.setEnabled(false);
        Integer chapter = replyInput.active() ? replyInput.chapterId() : chapterId > 0 ? chapterId : null;
        commentCall = RetrofitClient.getInstance().createUpdateComment(token,
                new WorkCommentRequest(workId, content, chapter, replyInput.commentId()));
        commentCall.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                if (!validComment(call)) return;
                finishSubmit();
                if (!token.equals(UserPreferences.getToken(activity))) {
                    Toast.makeText(activity, R.string.community_account_changed, Toast.LENGTH_LONG).show(); return;
                }
                if (response.isSuccessful()) {
                    com.fimtale.community.Community.changed();
                    input.setText("");
                    replyInput.clear();
                    input.clearFocus();
                    hideKeyboard();
                    descending = true;
                    sort.setText(activity.getString(R.string.comments_newest_first));
                    load(1);
                    Toast.makeText(activity, activity.getString(R.string.comments_sent), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, ApiErrors.message(response), Toast.LENGTH_LONG).show();
                    if (response.code() == 401) activity.startActivity(new Intent(activity, LoginActivity.class));
                }
            }

            @Override public void onFailure(Call<Void> call, Throwable error) {
                if (!validComment(call)) return;
                finishSubmit();
                Toast.makeText(activity, activity.getString(R.string.comments_send_failed), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean validComment(Call<?> call) {
        return !closed && !activity.isFinishing() && !activity.isDestroyed()
                && !call.isCanceled() && call == commentCall;
    }

    private void finishSubmit() {
        commentCall = null;
        sending = false;
        input.setEnabled(true);
        emoji.setEnabled(true);
        send.setEnabled(true);
    }

    private void insert(String value) {
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(start, input.getSelectionEnd());
        input.getText().replace(start, end, value);
        input.requestFocus();
        input.setSelection(Math.min(input.length(), start + value.length()));
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    public void close() {
        closed = true;
        replyInput.close();
        if (activeCall != null) activeCall.cancel();
        if (commentCall != null) { commentCall.cancel(); finishSubmit(); }
        refresh.setRefreshing(false);
        refresh.setOnRefreshListener(null);
        pageError.hide();
        adapter.close();
        list.setAdapter(null);
    }
    private android.os.Bundle replyState() { return replyInput == null ? null : replyInput.save(); }
}
