package com.fimtale.community;

import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import com.fimtale.LoginActivity;
import com.fimtale.TopicDetailActivity;
import com.fimtale.UserDetailActivity;
import com.fimtale.R;
import com.fimtale.model.Comment;
import com.fimtale.model.CommentResponse;
import com.fimtale.model.UserAuth;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.report.ReportDialog;
import com.fimtale.report.ReportRequest;
import com.fimtale.utils.BbCodeRendering;
import com.fimtale.utils.UserPreferences;
import com.fimtale.ui.CommentReplyInput;
import com.fimtale.ui.BottomSheetMenu;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Shared actions for every native comment list; toggles always reload authoritative viewer state. */
public final class CommentActions implements DefaultLifecycleObserver {
    private final AppCompatActivity activity;
    private final Consumer<Comment> updated;
    private final Runnable stateChanged;
    private final List<Call<?>> calls = new ArrayList<>();
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private final Set<Integer> pending = new HashSet<>();
    private final Map<Integer, Integer> loadingActions = new HashMap<>();
    private final Set<Integer> hydrated = new HashSet<>();
    private int workId, workAuthorId, role;
    private String authSession = "";
    private boolean closed;
    private Runnable refresh;
    private Consumer<Comment> onReply;
    private BottomSheetMenu moreMenu;
    private long seenVersion = Community.version();
    private final Observer<Community.CommentEvent> observer;

    public CommentActions(AppCompatActivity activity, Consumer<Comment> updated, Runnable stateChanged) {
        this.activity = activity; this.updated = updated; this.stateChanged = stateChanged;
        observer = event -> {
            if (event == null || event.version <= seenVersion || closed) return;
            seenVersion = event.version;
            if (refresh != null && (workId == 0 || event.workId == workId)) refresh.run();
        };
        activity.getLifecycle().addObserver(this);
        Community.commentEvents.observe(activity, observer);
        loadPermissions();
    }
    public void context(int work, int author, Runnable refresh) { workId = work; workAuthorId = author; this.refresh = refresh; }
    public void author(int author) { workAuthorId = author; stateChanged.run(); }
    public void setOnReply(Consumer<Comment> reply) { onReply = reply; }
    public boolean busy(Comment comment) { return pending.contains(comment.id); }
    public int loadingAction(Comment comment) { return loadingActions.getOrDefault(comment.id, 0); }
    private void finish(Comment comment) { pending.remove(comment.id); loadingActions.remove(comment.id); }
    public void hydrate(Comment comment) {
        if (closed || comment.viewerLoaded || !UserPreferences.isLoggedIn(activity) || !hydrated.add(comment.id)) return;
        pending.add(comment.id); reload(comment, UserPreferences.getToken(activity));
    }
    public int self() {
        try { return Integer.parseInt(UserPreferences.getUserId(activity)); }
        catch (RuntimeException ignored) { return 0; }
    }
    public boolean canStar(Comment comment) { return self() > 0 && self() == workAuthorId && comment.starHonor == 0; }
    private boolean valid(String token) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return false;
        if (!token.equals(UserPreferences.getToken(activity))) { syncSession(); return false; }
        return true;
    }
    private boolean syncSession() {
        if (closed || authSession.equals(UserPreferences.getToken(activity))) return false;
        for (Call<?> call : calls) call.cancel(); calls.clear(); pending.clear(); loadingActions.clear(); hydrated.clear();
        loadPermissions();
        if (refresh != null) refresh.run();
        stateChanged.run(); return true;
    }
    @Override public void onResume(LifecycleOwner owner) {
        if (syncSession()) return;
        if (seenVersion != Community.version()) {
            seenVersion = Community.version(); if (refresh != null) refresh.run();
        }
    }
    private boolean login() {
        if (closed) return false;
        if (UserPreferences.isLoggedIn(activity)) return true;
        activity.startActivity(new Intent(activity, LoginActivity.class)); return false;
    }
    private void loadPermissions() {
        String token = UserPreferences.getToken(activity);
        if (token.equals(authSession)) return;
        authSession = token; role = 0;
        if (token.isEmpty()) return;
        Call<UserAuth> call = RetrofitClient.getInstance().getUserAuth(token); calls.add(call);
        call.enqueue(new Callback<UserAuth>() {
            @Override public void onResponse(Call<UserAuth> request, Response<UserAuth> response) {
                if (valid(token) && response.isSuccessful() && response.body() != null) role = response.body().roleId;
            }
            @Override public void onFailure(Call<UserAuth> request, Throwable failure) { }
        });
    }

    public void vote(Comment comment, int operation) {
        if (login()) mutate(comment, RetrofitClient.getInstance().voteComment(UserPreferences.getToken(activity), new Community.CommentVote(comment.id, operation)), false,
                operation == 1 ? R.id.commentLike : R.id.commentDislike);
    }
    public void favorite(Comment comment) {
        if (login()) mutate(comment, RetrofitClient.getInstance().favoriteComment(UserPreferences.getToken(activity), new Community.CommentId(comment.id)), false, R.id.commentFavorite);
    }
    public void reply(Comment comment) {
        if (!login() || busy(comment)) return;
        int work = comment.workId > 0 ? comment.workId : workId;
        if (work <= 0) return;
        if (onReply != null) onReply.accept(comment);
        else activity.startActivity(new Intent(activity, TopicDetailActivity.class)
                .putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, work)
                .putExtra(TopicDetailActivity.EXTRA_COMMENT_REPLY, CommentReplyInput.target(activity, comment, work)));
    }
    public void more(Comment comment) {
        if (closed || busy(comment) || moreMenu != null && moreMenu.isShowing()) return;
        loadPermissions();
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        add(labels, actions, R.string.community_view_original, () -> openWork(comment));
        add(labels, actions, R.string.community_comment_history, () -> history(comment));
        add(labels, actions, R.string.timeline_share, () -> {
            if (login()) mutate(comment, RetrofitClient.getInstance().highlightWorkComment(UserPreferences.getToken(activity),
                    new com.fimtale.model.TimelineItem.Highlight(comment.id)), false, R.id.commentMore);
        });
        if (canStar(comment)) add(labels, actions, R.string.community_star_honor, () -> confirm(R.string.community_star_confirm,
                () -> mutate(comment, RetrofitClient.getInstance().starComment(UserPreferences.getToken(activity), new Community.CommentId(comment.id)), false, R.id.commentStar)));
        if (self() > 0 && self() == comment.authorId()) add(labels, actions, R.string.community_edit_comment, () -> {
            if (!login()) return;
            int work = comment.workId > 0 ? comment.workId : workId;
            if (work > 0) CommentComposerDialog.show(activity, work, comment.chapterId, 0, comment.id, comment.content,
                    activity.getString(R.string.community_edit_comment));
        });
        if (self() > 0 && (self() == comment.authorId() || role >= 4)) add(labels, actions, R.string.community_delete_comment,
                () -> confirm(R.string.community_delete_comment_confirm,
                        () -> mutate(comment, RetrofitClient.getInstance().deleteComment(UserPreferences.getToken(activity), new Community.DeleteComment(comment.id)), true, R.id.commentMore)));
        add(labels, actions, R.string.community_report_comment, () -> ReportDialog.show(activity, ReportRequest.COMMENT, comment.id,
                activity.getString(R.string.community_comment_actions, comment.id)));
        String session = UserPreferences.getToken(activity);
        BottomSheetMenu sheet = new BottomSheetMenu(activity, labels.toArray(new CharSequence[0]), which -> {
                    if (valid(session)) actions.get(which).run(); else toast(activity.getString(R.string.community_account_changed));
                });
        moreMenu = sheet;
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(sheet.findViewById(R.id.bottomMenuItems),
                activity.getString(R.string.community_comment_actions, comment.id));
        sheet.setOnDismissListener(dialog -> { if (moreMenu == sheet) moreMenu = null; }); sheet.show();
    }
    private void add(List<String> labels, List<Runnable> actions, int label, Runnable action) {
        labels.add(activity.getString(label)); actions.add(action);
    }
    private void confirm(int message, Runnable action) {
        if (!login()) return;
        String session = UserPreferences.getToken(activity);
        dialogs.add(new MaterialAlertDialogBuilder(activity).setMessage(message)
                .setNegativeButton(R.string.common_cancel, null).setPositiveButton(R.string.common_confirm, (d, which) -> {
                    if (valid(session)) action.run(); else toast(activity.getString(R.string.community_account_changed));
                }).show());
    }
    public void star(Comment comment) {
        if (!canStar(comment)) return;
        confirm(R.string.community_star_confirm, () -> mutate(comment,
                RetrofitClient.getInstance().starComment(UserPreferences.getToken(activity), new Community.CommentId(comment.id)), false, R.id.commentStar));
    }
    private void mutate(Comment comment, Call<Void> call, boolean deleted, int action) {
        if (!login() || !pending.add(comment.id)) return;
        loadingActions.put(comment.id, action);
        String token = UserPreferences.getToken(activity); stateChanged.run(); calls.add(call);
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> request, Response<Void> response) {
                if (!valid(token)) { finish(comment); return; }
                if (response.isSuccessful()) {
                    Community.changed();
                    if (deleted) {
                        finish(comment); comment.statusDel = 1; updated.accept(comment);
                        Community.commentChanged(comment.workId > 0 ? comment.workId : workId);
                    } else reload(comment, token);
                } else { finish(comment); stateChanged.run(); toast(ApiErrors.message(response)); }
            }
            @Override public void onFailure(Call<Void> request, Throwable failure) {
                if (!valid(token) || request.isCanceled()) return;
                Community.changed();
                toast(activity.getString(R.string.community_operation_uncertain)); reload(comment, token);
            }
        });
    }
    private void reload(Comment comment, String token) {
        Call<CommentResponse> call = RetrofitClient.getInstance().getCommentViewer(token, comment.id); calls.add(call);
        call.enqueue(new Callback<CommentResponse>() {
            @Override public void onResponse(Call<CommentResponse> request, Response<CommentResponse> response) {
                finish(comment);
                if (!valid(token)) return;
                if (response.isSuccessful() && response.body() != null && response.body().comment != null) {
                    CommentResponse result = response.body();
                    if (result.viewer != null) result.viewer.apply(result.comment);
                    updated.accept(result.comment);
                } else { stateChanged.run(); toast(ApiErrors.message(response)); }
            }
            @Override public void onFailure(Call<CommentResponse> request, Throwable failure) {
                finish(comment);
                if (valid(token) && !request.isCanceled()) { stateChanged.run(); toast(activity.getString(R.string.community_load_failed)); }
            }
        });
    }
    public void open(int commentId) {
        activity.startActivity(CommentDetailActivity.intent(activity, commentId));
    }
    public void openContext(Comment comment) { if (comment.replyCommentId > 0) open(comment.replyCommentId); else openWork(comment); }
    public void openWork(Comment comment) {
        int work = comment.workId > 0 ? comment.workId : workId;
        if (work > 0) activity.startActivity(new Intent(activity, TopicDetailActivity.class)
                .putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, work).putExtra(TopicDetailActivity.EXTRA_FOCUS_COMMENT, comment.id));
    }
    public void openAuthor(Comment comment) {
        if (comment.authorId() <= 0) return;
        String token = UserPreferences.getToken(activity);
        Call<String> call = RetrofitClient.getInstance().getUsernameById(token, comment.authorId()); calls.add(call);
        call.enqueue(new Callback<String>() {
            @Override public void onResponse(Call<String> request, Response<String> response) {
                if (!valid(token)) return;
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty())
                    activity.startActivity(new Intent(activity, UserDetailActivity.class).putExtra(UserDetailActivity.EXTRA_USERNAME, response.body()));
                else toast(ApiErrors.message(response));
            }
            @Override public void onFailure(Call<String> request, Throwable failure) {
                if (valid(token) && !request.isCanceled()) toast(activity.getString(R.string.community_load_failed));
            }
        });
    }
    public void history(Comment comment) {
        String token = UserPreferences.getToken(activity);
        TextView loading = new TextView(activity); loading.setPadding(48, 24, 48, 24); loading.setText(R.string.common_loading_active);
        ScrollView scroll = new ScrollView(activity); scroll.addView(loading);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.community_comment_history)
                .setView(scroll).setPositiveButton(R.string.common_confirm, null).show(); dialogs.add(dialog);
        Call<List<Community.CommentHistory>> call = RetrofitClient.getInstance().getCommentHistory(token, comment.id); calls.add(call);
        dialog.setOnDismissListener(d -> call.cancel());
        call.enqueue(new Callback<List<Community.CommentHistory>>() {
            @Override public void onResponse(Call<List<Community.CommentHistory>> request, Response<List<Community.CommentHistory>> response) {
                if (!valid(token) || !dialog.isShowing()) return;
                if (!response.isSuccessful()) { loading.setText(ApiErrors.message(response)); return; }
                List<Community.CommentHistory> history = response.body();
                if (history == null || history.isEmpty()) { loading.setText(R.string.community_no_history); return; }
                LinearLayout entries = new LinearLayout(activity); entries.setOrientation(LinearLayout.VERTICAL); entries.setPadding(48, 8, 48, 24);
                for (Community.CommentHistory item : history) {
                    TextView time = new TextView(activity); time.setText(activity.getString(R.string.community_previous_content, item.created_at)); entries.addView(time);
                    TextView text = new TextView(activity); text.setTextSize(16); text.setPadding(0, 12, 0, 32);
                    entries.addView(text); BbCodeRendering.setText(BbCodeRendering.create(activity), text, item.previous_content);
                }
                scroll.removeAllViews(); scroll.addView(entries);
            }
            @Override public void onFailure(Call<List<Community.CommentHistory>> request, Throwable failure) {
                if (valid(token) && dialog.isShowing() && !request.isCanceled()) loading.setText(R.string.community_load_failed);
            }
        });
    }
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    public void close() {
        if (closed) return; closed = true;
        Community.commentEvents.removeObserver(observer); activity.getLifecycle().removeObserver(this);
        for (Call<?> call : calls) call.cancel();
        pending.clear(); loadingActions.clear();
        for (AlertDialog dialog : dialogs) dialog.dismiss();
        if (moreMenu != null) moreMenu.dismiss();
    }
    @Override public void onDestroy(LifecycleOwner owner) { close(); }
}
