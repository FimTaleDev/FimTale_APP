package com.fimtale.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.EditorActivity;
import com.fimtale.LoginActivity;
import com.fimtale.MainActivity;
import com.fimtale.R;
import com.fimtale.adapter.TimelineAdapter;
import com.fimtale.editor.EditorChanges;
import com.fimtale.model.TimelineFeed;
import com.fimtale.model.TimelineItem;
import com.fimtale.model.UserAuth;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.button.MaterialButton;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Native equivalent of ft-front's TimelineFeed, scoped to the active login session. */
public final class TimelineFragment extends Fragment {
    private final TimelineFeed feed = new TimelineFeed();
    private final Set<Call<?>> calls = new HashSet<>();
    private final Set<String> reposting = new HashSet<>();
    private String session, error, authError;
    private int generation;
    private boolean loading, failedReset, activating, loadingAuth, needsRead, reading;
    private long editorVersion = EditorChanges.version();
    private UserAuth auth;
    private RecyclerView list;
    private PullRefreshLayout refresh;
    private ShimmerSkeletonView skeleton;
    private View composeCard;
    private PageErrorView state, footerState;
    private TextView composeHint, footerText;
    private MaterialButton composeAction, loadMore;
    private TimelineAdapter adapter;
    private PageErrorView pageError;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_timeline, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        list = view.findViewById(R.id.timelineList);
        refresh = view.findViewById(R.id.timelineRefresh);
        pageError = PageErrorView.wrap(refresh);
        skeleton = view.findViewById(R.id.timelineSkeleton);
        skeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.COMMENTS);
        state = view.findViewById(R.id.timelineState);
        View header = getLayoutInflater().inflate(R.layout.item_timeline_header, list, false);
        View footer = getLayoutInflater().inflate(R.layout.item_timeline_footer, list, false);
        composeCard = header.findViewById(R.id.timelineComposeCard);
        composeHint = header.findViewById(R.id.timelineComposeHint);
        composeAction = header.findViewById(R.id.timelineComposeAction);
        footerText = footer.findViewById(R.id.timelineFooterText);
        footerState = footer.findViewById(R.id.timelineEmptyState);
        loadMore = footer.findViewById(R.id.timelineLoadMore);
        adapter = new TimelineAdapter(requireContext(), feed.items, reposting, this::repost);
        list.setAdapter(new ConcatAdapter(new Row(header), adapter, new Row(footer)));
        list.setItemAnimator(null);
        refresh.setOnChildScrollUpCallback((parent, child) -> loading || list.canScrollVertically(-1));
        refresh.setOnRefreshListener(() -> {
            load(true);
            loadAuth();
        });
        loadMore.setOnClickListener(v -> load(error != null && failedReset));
        composeAction.setOnClickListener(v -> compose());
        composeCard.setOnClickListener(v -> compose());
        render();
    }

    @Override public void onResume() {
        super.onResume();
        if (syncSession()) return;
        if (session == null || session.isEmpty()) return;
        if (editorVersion != EditorChanges.version()) {
            editorVersion = EditorChanges.version(); load(true);
        } else if (feed.page == 0 && error == null) load(true);
        if (auth == null) loadAuth();
        markRead();
    }

    /** Drop cached private data before rendering a different account (including logout). */
    private boolean syncSession() {
        String token = UserPreferences.getToken(requireContext());
        if (token.equals(session)) return false;
        cancelCalls();
        session = token; feed.clear(); auth = null; error = null; authError = null;
        needsRead = false; reposting.clear();
        editorVersion = EditorChanges.version();
        adapter.notifyDataSetChanged(); render();
        if (!token.isEmpty()) { load(true); loadAuth(); }
        return true;
    }
    private boolean accepts(int request) {
        return isAdded() && getView() != null && generation == request
                && session != null && session.equals(UserPreferences.getToken(requireContext()));
    }
    private void cancelCalls() {
        generation++;
        for (Call<?> call : calls) call.cancel();
        calls.clear(); loading = loadingAuth = activating = reading = false;
    }

    private void load(boolean reset) {
        if (syncSession() || loading || session.isEmpty() || (!reset && feed.finished)) return;
        final int page = reset ? 1 : feed.page + 1;
        final int request = generation;
        loading = true; error = null; failedReset = reset;
        render();
        Call<List<TimelineItem>> call = RetrofitClient.getInstance().getTimeline(session, page, TimelineFeed.PAGE_SIZE);
        calls.add(call);
        call.enqueue(new Callback<List<TimelineItem>>() {
            @Override public void onResponse(Call<List<TimelineItem>> call, Response<List<TimelineItem>> response) {
                calls.remove(call);
                if (!accepts(request)) { reconcileExpiredSession(request); return; }
                loading = false;
                if (!response.isSuccessful()) { failLoad(ApiErrors.message(response)); return; }
                feed.accept(page, response.body());
                adapter.notifyDataSetChanged();
                if (reset) { list.scrollToPosition(0); needsRead = true; }
                render(); markRead();
            }
            @Override public void onFailure(Call<List<TimelineItem>> call, Throwable cause) {
                calls.remove(call);
                if (!accepts(request)) { reconcileExpiredSession(request); return; }
                loading = false; failLoad(getString(R.string.timeline_load_failed));
            }
        });
    }
    private void reconcileExpiredSession(int request) {
        if (isAdded() && getView() != null && request == generation) syncSession();
    }
    private void failLoad(String message) { error = message; render(); }

    private void loadAuth() {
        if (loadingAuth || session == null || session.isEmpty()) return;
        loadingAuth = true;
        authError = null;
        render();
        int request = generation;
        Call<UserAuth> call = RetrofitClient.getInstance().getUserAuth(session);
        calls.add(call);
        call.enqueue(new Callback<UserAuth>() {
            @Override public void onResponse(Call<UserAuth> call, Response<UserAuth> response) {
                calls.remove(call);
                if (!accepts(request)) { reconcileExpiredSession(request); return; }
                loadingAuth = false;
                if (response.isSuccessful() && response.body() != null) auth = response.body();
                else authError = ApiErrors.message(response);
                render();
            }
            @Override public void onFailure(Call<UserAuth> call, Throwable cause) {
                calls.remove(call);
                if (!accepts(request)) return;
                loadingAuth = false;
                authError = getString(R.string.timeline_permission_failed);
                render();
            }
        });
    }

    private void compose() {
        if (syncSession() || auth == null || activating) return;
        if (auth.canPost()) startActivity(EditorActivity.postIntent(requireContext()));
        else if (auth.needsSpace()) {
            activating = true; render();
            int request = generation;
            Call<Void> call = RetrofitClient.getInstance().activateUserSpace(session);
            calls.add(call);
            call.enqueue(new Callback<Void>() {
                @Override public void onResponse(Call<Void> call, Response<Void> response) {
                    calls.remove(call);
                    if (!accepts(request)) { reconcileExpiredSession(request); return; }
                    activating = false;
                    if (response.isSuccessful()) { toast(getString(R.string.timeline_space_opened)); loadAuth(); }
                    else toast(ApiErrors.message(response));
                    render();
                }
                @Override public void onFailure(Call<Void> call, Throwable cause) {
                    calls.remove(call);
                    if (!accepts(request)) return;
                    activating = false; toast(getString(R.string.timeline_open_space_uncertain)); render();
                }
            });
        }
    }

    private void markRead() {
        if (!needsRead || reading || !isResumed() || session == null || session.isEmpty()) return;
        reading = true;
        int request = generation;
        Call<Void> call = RetrofitClient.getInstance().readTimeline(session);
        calls.add(call);
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                calls.remove(call);
                if (!accepts(request)) return;
                reading = false;
                if (response.isSuccessful()) {
                    needsRead = false;
                    if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).onTimelineRead();
                }
            }
            @Override public void onFailure(Call<Void> call, Throwable cause) {
                calls.remove(call);
                if (accepts(request)) reading = false;
            }
        });
    }

    private void repost(TimelineItem item) {
        if (syncSession() || !item.canHighlight() || !reposting.add(item.key())) return;
        int request = generation;
        adapter.notifyDataSetChanged();
        TimelineItem.Highlight body = new TimelineItem.Highlight(item.entity.id);
        Call<Void> call = item.type == TimelineItem.CHANNEL_COMMENT
                ? RetrofitClient.getInstance().highlightChannelComment(session, body)
                : RetrofitClient.getInstance().highlightWorkComment(session, body);
        calls.add(call);
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                calls.remove(call);
                if (!accepts(request)) { reconcileExpiredSession(request); return; }
                reposting.remove(item.key()); adapter.notifyDataSetChanged();
                toast(response.isSuccessful() ? getString(R.string.timeline_shared) : ApiErrors.message(response));
            }
            @Override public void onFailure(Call<Void> call, Throwable cause) {
                calls.remove(call);
                if (!accepts(request)) return;
                reposting.remove(item.key()); adapter.notifyDataSetChanged();
                toast(getString(R.string.timeline_share_uncertain));
            }
        });
    }

    private void render() {
        if (list == null) return;
        boolean guest = session == null || session.isEmpty();
        if (!loading && !loadingAuth) refresh.setRefreshing(false);
        boolean replacing = !guest && loading && failedReset && !refresh.isRefreshing();
        if (!guest && error != null && !loading)
            pageError.show(error, () -> load(failedReset), !feed.items.isEmpty());
        else if (!guest && authError != null && !loading)
            pageError.show(authError, this::loadAuth, !feed.items.isEmpty());
        else pageError.hide();
        refresh.setEnabled(!guest);
        skeleton.setVisibility(replacing ? View.VISIBLE : View.GONE);
        list.setVisibility(guest || replacing ? View.INVISIBLE : View.VISIBLE);
        if (guest) state.showLogin(getString(R.string.timeline_login_message),
                () -> startActivity(new Intent(requireContext(), LoginActivity.class)));
        else state.hide();
        boolean canPost = !guest && auth != null && auth.canPost();
        boolean needsSpace = !guest && auth != null && auth.needsSpace();
        composeCard.setVisibility(canPost || needsSpace ? View.VISIBLE : View.GONE);
        composeHint.setText(canPost ? getString(R.string.timeline_post_hint) : getString(R.string.timeline_space_required));
        composeAction.setText(canPost ? getString(R.string.timeline_post) : activating ? getString(R.string.timeline_opening_space) : getString(R.string.timeline_open_space));
        composeAction.setEnabled(!activating && !loadingAuth);
        composeCard.setEnabled(!activating && !loadingAuth);
        boolean empty = !guest && !loading && error == null && feed.items.isEmpty();
        if (!guest && !loading && error != null) footerState.show(error, () -> load(failedReset));
        else if (empty) footerState.showEmpty(getString(R.string.timeline_empty), () -> { load(true); loadAuth(); });
        else footerState.hide();
        footerText.setText(loading ? getString(R.string.common_loading_active)
                : !empty && error == null && feed.finished ? getString(R.string.common_list_end) : "");
        footerText.setVisibility(footerText.length() > 0 ? View.VISIBLE : View.GONE);
        loadMore.setVisibility(!guest && !empty && error == null && !feed.finished ? View.VISIBLE : View.GONE);
        loadMore.setEnabled(!loading);
        loadMore.setText(error != null ? getString(R.string.common_retry) : loading ? getString(R.string.common_loading) : getString(R.string.common_load_more));
    }
    private void toast(String message) { Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show(); }

    @Override public void onDestroyView() {
        cancelCalls(); reposting.clear();
        if (list != null) list.setAdapter(null);
        list = null; adapter = null; refresh = null; skeleton = null; state = null; composeCard = null;
        pageError = footerState = null;
        composeHint = footerText = null; composeAction = loadMore = null;
        super.onDestroyView();
    }
    private static final class Row extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View view;
        Row(View view) { this.view = view; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RecyclerView.ViewHolder(view) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
        @Override public int getItemCount() { return 1; }
    }
}
