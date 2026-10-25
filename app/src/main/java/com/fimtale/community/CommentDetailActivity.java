package com.fimtale.community;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.R;
import com.fimtale.adapter.CommentAdapter;
import com.fimtale.model.CommentResponse;
import com.fimtale.model.TopicDetailResponse;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.ui.PageErrorView;
import com.fimtale.ui.PullToRefresh;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.Collections;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Native destination for parent-comment, timeline and notification links. */
public final class CommentDetailActivity extends AppCompatActivity {
    private static final String EXTRA_COMMENT = "comment_id";
    private CommentAdapter adapter;
    private RecyclerView list;
    private PageErrorView error;
    private Call<CommentResponse> call;
    private Call<TopicDetailResponse> workCall;
    private int id;
    private String session;
    private boolean loading;
    public static Intent intent(Context context, int id) {
        return new Intent(context, CommentDetailActivity.class).putExtra(EXTRA_COMMENT, id);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_community);
        id = getIntent().getIntExtra(EXTRA_COMMENT, 0);
        if (id <= 0) { finish(); return; }
        MaterialToolbar toolbar = findViewById(R.id.communityToolbar);
        toolbar.setTitle(getString(R.string.community_read_comment, id));
        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.communityTabs).setVisibility(View.GONE);
        list = findViewById(R.id.communityList); list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CommentAdapter(new ArrayList<>(), this);
        adapter.setContext(0, 0, this::load);
        list.setAdapter(adapter); list.setItemAnimator(null);
        error = PageErrorView.wrap(list);
        PullToRefresh.attach(list, this::load, () -> !loading);
        load();
    }
    @Override protected void onResume() {
        super.onResume();
        if (adapter != null && !UserPreferences.getToken(this).equals(session)) load();
    }
    private void load() {
        if (call != null) call.cancel();
        if (workCall != null) workCall.cancel();
        session = UserPreferences.getToken(this); final String token = session;
        loading = true; error.hide(); findViewById(R.id.communityProgress).setVisibility(View.VISIBLE);
        call = RetrofitClient.getInstance().getCommentViewer(token, id);
        call.enqueue(new Callback<CommentResponse>() {
            @Override public void onResponse(Call<CommentResponse> request, Response<CommentResponse> response) {
                if (!valid(request, token)) return;
                finishLoad();
                CommentResponse result = response.body();
                if (!response.isSuccessful() || result == null || result.comment == null) { showError(ApiErrors.message(response)); return; }
                adapter.updateData(new ArrayList<>(Collections.singletonList(result.comment)), result.viewer);
                adapter.setContext(result.comment.workId, 0, CommentDetailActivity.this::load);
                workCall = RetrofitClient.getInstance().getWorkViewer(token, result.comment.workId);
                workCall.enqueue(new Callback<TopicDetailResponse>() {
                    @Override public void onResponse(Call<TopicDetailResponse> c, Response<TopicDetailResponse> r) {
                        if (!isFinishing() && !isDestroyed() && c == workCall && !c.isCanceled() && token.equals(UserPreferences.getToken(CommentDetailActivity.this))
                                && r.isSuccessful() && r.body() != null && r.body().getAuthorInfo() != null)
                            adapter.setWorkAuthor(r.body().getAuthorInfo().getId());
                    }
                    @Override public void onFailure(Call<TopicDetailResponse> c, Throwable t) { }
                });
            }
            @Override public void onFailure(Call<CommentResponse> request, Throwable failure) {
                if (valid(request, token)) { finishLoad(); showError(getString(R.string.community_load_failed)); }
            }
        });
    }
    private boolean valid(Call<?> request, String token) {
        if (isFinishing() || isDestroyed() || request.isCanceled() || request != call) return false;
        if (!token.equals(UserPreferences.getToken(this))) { load(); return false; }
        return true;
    }
    private void finishLoad() { loading = false; PullToRefresh.finish(list); findViewById(R.id.communityProgress).setVisibility(View.GONE); }
    private void showError(String message) { error.show(message, this::load, adapter.getItemCount() > 0); }
    @Override protected void onDestroy() {
        if (call != null) call.cancel(); if (workCall != null) workCall.cancel();
        if (adapter != null) adapter.close(); super.onDestroy();
    }
}
