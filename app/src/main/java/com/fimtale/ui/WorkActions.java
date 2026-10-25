package com.fimtale.ui;


import com.fimtale.utils.MdiIcons;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import com.fimtale.R;
import com.fimtale.model.TopicDetailResponse;
import com.fimtale.model.WorkInteractions;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Owns authenticated actions; always re-reads server state after a mutation. */
public final class WorkActions {
    private final AppCompatActivity activity;
    private final int workId;
    private final Runnable login;
    private final Consumer<TopicDetailResponse> updated;
    private final MaterialButton like, favorite, highPraise;
    private final TextView status;
    private TopicDetailResponse data;
    private String stateToken;
    private boolean busy, known, closed;
    private final ButtonLoadingIcon loadingIcon = new ButtonLoadingIcon();
    private Call<?> activeCall;
    private AlertDialog foldersDialog;

    public WorkActions(AppCompatActivity activity, int workId, Runnable login, Consumer<TopicDetailResponse> updated) {
        this.activity = activity; this.workId = workId; this.login = login; this.updated = updated;
        stateToken = UserPreferences.getToken(activity);
        like = activity.findViewById(R.id.likeWorkButton);
        favorite = activity.findViewById(R.id.favoriteWorkButton);
        highPraise = activity.findViewById(R.id.highPraiseWorkButton);
        ColorStateList transparent = ColorStateList.valueOf(Color.TRANSPARENT);
        like.setBackgroundTintList(transparent);
        favorite.setBackgroundTintList(transparent);
        highPraise.setBackgroundTintList(transparent);
        status = activity.findViewById(R.id.workActionStatus);
        like.setOnClickListener(v -> vote());
        favorite.setOnClickListener(v -> chooseFavorites());
        highPraise.setOnClickListener(v -> chooseHighPraise());
        status.setOnClickListener(v -> { if (!busy) refresh(); });
    }

    public void bind(TopicDetailResponse data, String token) {
        if (closed || busy || !token.equals(UserPreferences.getToken(activity))) return;
        this.data = data; stateToken = token;
        known = data.viewer != null || token.isEmpty();
        render();
    }

    public void onResume() {
        String token = UserPreferences.getToken(activity);
        if (token.equals(stateToken)) return;
        if (activeCall != null) activeCall.cancel();
        activeCall = null;
        if (foldersDialog != null) foldersDialog.dismiss();
        stateToken = token; known = false; finishAction();
        refresh();
    }

    private boolean valid(Call<?> call, String token) {
        if (closed || activity.isFinishing() || activity.isDestroyed() || call.isCanceled() || call != activeCall) return false;
        if (!token.equals(UserPreferences.getToken(activity))) {
            onResume();
            Toast.makeText(activity, activity.getString(R.string.error_session_changed), Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean authenticated() {
        if (!UserPreferences.isLoggedIn(activity)) { login.run(); return false; }
        if (!stateToken.equals(UserPreferences.getToken(activity))) { onResume(); return false; }
        return !busy && known && data != null;
    }

    private void render() {
        boolean liked = known && data != null && data.viewer != null && data.viewer.isLiked();
        boolean faved = known && data != null && data.viewer != null && !data.viewer.folderIds().isEmpty();
        int hpGiven = known && data != null && data.viewer != null ? data.viewer.highPraiseCount() : 0;
        int likes = data == null ? 0 : data.getTopicInfo().getLikeCount();
        int favorites = data == null ? 0 : data.getTopicInfo().getFavoriteCount();
        like.setText(String.valueOf(likes));
        favorite.setText(String.valueOf(favorites));
        highPraise.setText(activity.getString(R.string.work_hp_action));
        boolean enabled = !closed && !busy && data != null && (known || !UserPreferences.isLoggedIn(activity));
        like.setEnabled(enabled); favorite.setEnabled(enabled); highPraise.setEnabled(enabled);
        like.setChecked(liked); favorite.setChecked(faved);
        highPraise.setChecked(hpGiven > 0);
        loadingIcon.setIcon(like, MdiIcons.drawable(activity, liked ? "thumb-up" : "thumb-up-outline"));
        loadingIcon.setIcon(favorite, MdiIcons.drawable(activity, faved ? "bookmark" : "bookmark-outline"));
        loadingIcon.setIcon(highPraise, MdiIcons.drawable(activity, hpGiven > 0 ? "star" : "star-outline"));
        like.setContentDescription(activity.getString(liked ? R.string.work_unlike_description : R.string.work_like_description, likes));
        favorite.setContentDescription(activity.getString(faved ? R.string.work_favorited_description : R.string.work_favorite_description, favorites));
        int hpTotal = data == null ? 0 : data.getTopicInfo().getHighPraise();
        highPraise.setContentDescription(activity.getString(hpGiven > 0 ? R.string.work_hp_sent_description : R.string.work_hp_description, hpTotal));
        // The active icon shows progress through both the mutation and reconciliation.
        // Expose a retry affordance only when the interaction state could not be loaded.
        status.setVisibility(!busy && !known ? View.VISIBLE : View.GONE);
        status.setText(!busy && !known ? activity.getString(R.string.work_interactions_load_failed) : "");
    }

    private void beginAction(MaterialButton button) {
        busy = true;
        loadingIcon.start(button);
        render();
    }

    private void finishAction() {
        loadingIcon.stop();
        busy = false;
    }

    private void vote() {
        if (!authenticated()) return;
        beginAction(like);
        String token = stateToken;
        Call<Void> call = RetrofitClient.getInstance().voteWork(token, new WorkInteractions.Vote(workId));
        activeCall = call;
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                if (!valid(call, token)) return;
                if (!response.isSuccessful()) Toast.makeText(activity, ApiErrors.message(response), Toast.LENGTH_LONG).show();
                refresh();
            }
            @Override public void onFailure(Call<Void> call, Throwable error) {
                if (!valid(call, token)) return;
                // Vote toggles. Never repeat a POST whose response might have been lost.
                Toast.makeText(activity, activity.getString(R.string.work_like_uncertain), Toast.LENGTH_LONG).show();
                refresh();
            }
        });
    }

    private void refresh() {
        if (closed) return;
        busy = true; known = false; render();
        String token = UserPreferences.getToken(activity);
        stateToken = token;
        Call<TopicDetailResponse> call = RetrofitClient.getInstance().getWorkViewer(token, workId);
        activeCall = call;
        call.enqueue(new Callback<TopicDetailResponse>() {
            @Override public void onResponse(Call<TopicDetailResponse> call, Response<TopicDetailResponse> response) {
                if (!valid(call, token)) return;
                finishAction();
                if (response.isSuccessful() && response.body() != null && response.body().getTopicInfo() != null) {
                    data = response.body(); known = data.viewer != null || token.isEmpty();
                    updated.accept(data);
                }
                render();
            }
            @Override public void onFailure(Call<TopicDetailResponse> call, Throwable error) {
                if (!valid(call, token)) return;
                finishAction(); render();
            }
        });
    }

    private void chooseFavorites() {
        if (!authenticated()) return;
        beginAction(favorite);
        String token = stateToken;
        Call<List<WorkInteractions.Folder>> call = RetrofitClient.getInstance().getFavoriteFolders(token);
        activeCall = call;
        call.enqueue(new Callback<List<WorkInteractions.Folder>>() {
            @Override public void onResponse(Call<List<WorkInteractions.Folder>> call, Response<List<WorkInteractions.Folder>> response) {
                if (!valid(call, token)) return;
                finishAction(); render();
                if (!response.isSuccessful()) {
                    Toast.makeText(activity, ApiErrors.message(response), Toast.LENGTH_LONG).show(); return;
                }
                showFolders(response.body(), token);
            }
            @Override public void onFailure(Call<List<WorkInteractions.Folder>> call, Throwable error) {
                if (!valid(call, token)) return;
                finishAction(); render();
                Toast.makeText(activity, activity.getString(R.string.work_favorites_failed), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void chooseHighPraise() {
        if (!authenticated()) return;
        String token = stateToken;
        boolean reprint = data != null && data.getTopicInfo() != null && data.getTopicInfo().getOrigin() == 3;
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.work_hp_title))
                .setMessage(activity.getString(R.string.work_hp_message))
                .setNegativeButton(activity.getString(R.string.common_cancel), null)
                .setPositiveButton(activity.getString(R.string.work_hp_one), (d, which) -> sendHighPraise(1, token));
        if (!reprint) dialog.setNeutralButton(activity.getString(R.string.work_hp_two), (d, which) -> sendHighPraise(2, token));
        dialog.show();
    }

    private void sendHighPraise(int count, String token) {
        if (!token.equals(UserPreferences.getToken(activity))) { onResume(); return; }
        beginAction(highPraise);
        Call<Void> call = RetrofitClient.getInstance().highPraiseWork(token,
                new WorkInteractions.HighPraise(workId, count));
        activeCall = call;
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                if (!valid(call, token)) return;
                if (!response.isSuccessful()) Toast.makeText(activity, ApiErrors.message(response), Toast.LENGTH_LONG).show();
                refresh();
            }
            @Override public void onFailure(Call<Void> call, Throwable error) {
                if (!valid(call, token)) return;
                Toast.makeText(activity, activity.getString(R.string.work_hp_uncertain), Toast.LENGTH_LONG).show();
                refresh();
            }
        });
    }

    private void showFolders(List<WorkInteractions.Folder> remote, String token) {
        List<WorkInteractions.Folder> folders = new ArrayList<>();
        folders.add(new WorkInteractions.Folder(0, activity.getString(R.string.work_default_favorites)));
        Set<Integer> ids = new LinkedHashSet<>(); ids.add(0);
        if (remote != null) for (WorkInteractions.Folder folder : remote) {
            if (folder != null && folder.id > 0 && ids.add(folder.id)) folders.add(folder);
        }
        Set<Integer> initial = data.viewer == null ? new LinkedHashSet<>() : data.viewer.folderIds();
        // Keep existing memberships even if a folder is absent from the returned list.
        for (int id : initial) if (ids.add(id)) folders.add(new WorkInteractions.Folder(id, activity.getString(R.string.work_favorite_folder, id)));
        Set<Integer> selected = new LinkedHashSet<>(initial);
        if (selected.isEmpty()) selected.add(0);
        String[] labels = new String[folders.size()]; boolean[] checked = new boolean[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = folders.get(i).name; checked[i] = selected.contains(folders.get(i).id);
        }
        foldersDialog = new MaterialAlertDialogBuilder(activity).setTitle(activity.getString(R.string.work_favorite_choose))
                .setMultiChoiceItems(labels, checked, (dialog, index, value) -> {
                    int id = folders.get(index).id;
                    if (value) selected.add(id); else selected.remove(id);
                })
                .setNegativeButton(activity.getString(R.string.common_cancel), null)
                .setPositiveButton(activity.getString(R.string.common_save), (dialog, which) -> {
                    if (!token.equals(UserPreferences.getToken(activity))) { onResume(); return; }
                    List<WorkInteractions.Change> changes = WorkInteractions.changes(initial, selected);
                    if (changes.isEmpty()) return;
                    beginAction(favorite);
                    saveFavorites(changes, 0, token);
                }).create();
        foldersDialog.show();
    }

    private void saveFavorites(List<WorkInteractions.Change> changes, int index, String token) {
        if (index >= changes.size()) { com.fimtale.community.Community.changed(); refresh(); return; }
        if (!token.equals(UserPreferences.getToken(activity))) { onResume(); return; }
        WorkInteractions.Change change = changes.get(index);
        WorkInteractions.FavoriteRequest request = new WorkInteractions.FavoriteRequest(workId, change.folderId);
        Call<Void> call = change.add ? RetrofitClient.getInstance().addFavoriteWork(token, request)
                : RetrofitClient.getInstance().removeFavoriteWork(token, request);
        activeCall = call;
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                if (!valid(call, token)) return;
                if (response.isSuccessful()) { com.fimtale.community.Community.changed(); saveFavorites(changes, index + 1, token); }
                else {
                    Toast.makeText(activity, ApiErrors.message(response), Toast.LENGTH_LONG).show();
                    refresh();
                }
            }
            @Override public void onFailure(Call<Void> call, Throwable error) {
                if (!valid(call, token)) return;
                Toast.makeText(activity, activity.getString(R.string.work_favorites_uncertain), Toast.LENGTH_LONG).show();
                com.fimtale.community.Community.changed();
                refresh();
            }
        });
    }

    public void close() {
        closed = true;
        if (activeCall != null) activeCall.cancel();
        if (foldersDialog != null) foldersDialog.dismiss();
        finishAction();
        render();
    }
}
