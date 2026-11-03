package com.fimtale;

import android.content.Intent;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.editor.EditorDraftStore;
import com.fimtale.editor.EditorDocument;
import com.fimtale.editor.OnlineDraft;
import com.fimtale.editor.DraftCodec;
import com.fimtale.editor.DraftRemote;
import com.fimtale.editor.DraftSync;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import com.fimtale.utils.UserPreferences;
import com.fimtale.ui.ShimmerSkeletonView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Website drafts, merged with pending local recovery copies. */
public class DraftsActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Row> items = new ArrayList<>();
    private static class Row {
        String key, title, preview;
        long revision, savedAt;
        boolean pending, uncertain;
        EditorDraftStore.Entry local;
    }
    private static long time(String value) {
        try { return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
        catch (RuntimeException ignored) { return 0; }
    }
    private final DraftAdapter adapter = new DraftAdapter();
    private final SummaryAdapter summaryAdapter = new SummaryAdapter();
    private String summaryText;
    private ValueAnimator headerAnimator;
    private boolean headerRaised;
    private int generation;
    private String listedUser = "";
    private ShimmerSkeletonView loadingSkeleton;
    private boolean loading;
    private com.fimtale.ui.PageErrorView pageError;
    private final ActivityResultLauncher<Intent> login = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (UserPreferences.isLoggedIn(this)) refresh(); else finish();
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_drafts);
        com.fimtale.utils.EditorWindowStyle.apply(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar); toolbar.setTitle("");
        // MaterialToolbar creates its own shape even for a null XML background.
        // Only the separate rounded surface should paint behind the title.
        toolbar.setBackground(null);
        ((TextView) findViewById(R.id.tvToolbarTitle)).setText(R.string.drafts_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        RecyclerView list = findViewById(R.id.draftsList);
        View listSurface = com.fimtale.ui.PageErrorView.withEmptyState(list, findViewById(R.id.draftsEmpty));
        pageError = com.fimtale.ui.PageErrorView.wrap(listSurface);
        loadingSkeleton = findViewById(R.id.draftsSkeleton);
        loadingSkeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.DRAFTS);
        summaryText = getString(R.string.drafts_local_hint);
        list.setLayoutManager(new LinearLayoutManager(this)); list.setAdapter(new ConcatAdapter(summaryAdapter, adapter));
        list.setItemAnimator(null);
        com.fimtale.ui.PullToRefresh.attach(listSurface, this::refresh, () -> !loading,
                () -> list.canScrollVertically(-1));
        View titleCard = findViewById(R.id.toolbarContainer);
        // The list scrolls behind the fixed card, including its summary row.
        titleCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int top = b + Math.round(16 * getResources().getDisplayMetrics().density);
            if (list.getPaddingTop() != top) list.setPadding(0, top, 0, list.getPaddingBottom());
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) loadingSkeleton.getLayoutParams();
            if (params.topMargin != top) { params.topMargin = top; loadingSkeleton.setLayoutParams(params); }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                animateHeader(view.canScrollVertically(-1));
            }
        });
        findViewById(R.id.draftsCreate).setOnClickListener(v -> {
            if (!UserPreferences.isLoggedIn(this)) { login.launch(new Intent(this, LoginActivity.class)); return; }
            startActivity(EditorActivity.workIntent(this, 0));
        });
        if (state == null && !UserPreferences.isLoggedIn(this)) login.launch(new Intent(this, LoginActivity.class));
    }
    private void setDraftsLoading(boolean loading) {
        this.loading = loading;
        View list = findViewById(R.id.draftsList);
        if (!loading) com.fimtale.ui.PullToRefresh.finish(list);
        boolean replacing = loading && !com.fimtale.ui.PullToRefresh.isRefreshing(list);
        loadingSkeleton.setVisibility(replacing ? View.VISIBLE : View.GONE);
        list.setVisibility(replacing ? View.INVISIBLE : View.VISIBLE);
        com.fimtale.ui.PageErrorView empty = findViewById(R.id.draftsEmpty);
        if (!loading && items.isEmpty()) empty.showEmpty(getString(R.string.drafts_empty), this::refresh);
        else empty.hide();
        findViewById(R.id.draftsCreate).setEnabled(!loading);
    }
    private void animateHeader(boolean raised) {
        if (headerRaised == raised) return;
        headerRaised = raised;
        if (headerAnimator != null) headerAnimator.cancel();
        View surface = findViewById(R.id.draftsHeaderSurface);
        // Fade an opaque shape and its fixed shadow together. Changing a translucent
        // MaterialCardView fill/elevation exposes shadow and elevation-overlay artifacts.
        headerAnimator = ValueAnimator.ofFloat(surface.getAlpha(), raised ? 1 : 0);
        headerAnimator.setDuration(200);
        headerAnimator.addUpdateListener(animation -> surface.setAlpha((float) animation.getAnimatedValue()));
        headerAnimator.start();
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    private void refresh() {
        pageError.hide();
        int current = ++generation;
        String userId = UserPreferences.getUserId(this);
        if (!UserPreferences.isLoggedIn(this) || !listedUser.equals(userId)) { items.clear(); adapter.notifyDataSetChanged(); }
        listedUser = userId;
        if (!UserPreferences.isLoggedIn(this) || userId.isEmpty()) { setDraftsLoading(false); return; }
        setDraftsLoading(true);
        File directory = new File(getNoBackupFilesDir(), "editor_drafts");
        String token = UserPreferences.getToken(this);
        io.execute(() -> {
            try {
                DraftRemote remote = new DraftRemote(RetrofitClient.getInstance(), token);
                java.util.LinkedHashMap<String, Row> rows = new java.util.LinkedHashMap<>();
                String warning = null;
                List<EditorDraftStore.Entry> local = EditorDraftStore.list(directory, SiteUrls.API, userId);
                // Import drafts created by earlier app builds, with revision checks protecting
                // any draft already created on the website under the same entity key.
                for (EditorDraftStore.Entry entry : local) {
                    EditorDocument document = entry.read();
                    if (document != null && document.draftKey == null && token.equals(UserPreferences.getToken(this))) {
                        try {
                            DraftSync sync = entry.synchronizer(); EditorDocument pending = sync.load(remote);
                            if (pending != null) sync.save(remote, pending);
                        } catch (Exception ignored) { warning = getString(R.string.drafts_partial_sync); }
                    }
                }
                boolean offline = false;
                try {
                    for (OnlineDraft.Summary summary : remote.list()) {
                        if (summary.key == null) continue;
                        Row row = new Row(); row.key = summary.key; row.title = summary.title; row.preview = summary.preview;
                        row.revision = summary.revision; row.savedAt = time(summary.updatedAt); rows.put(row.key, row);
                    }
                } catch (Exception e) { offline = true; warning = getString(R.string.drafts_offline_notice); }
                for (EditorDraftStore.Entry entry : EditorDraftStore.list(directory, SiteUrls.API, userId)) {
                    Row row = rows.get(entry.onlineKey);
                    if (entry.pendingSync || entry.submissionUncertain || (offline && row == null)) {
                        row = new Row(); row.key = entry.onlineKey; row.title = entry.title; row.preview = entry.preview;
                        row.revision = entry.revision; row.savedAt = entry.savedAt;
                        row.pending = entry.pendingSync; row.uncertain = entry.submissionUncertain; rows.put(row.key, row);
                    }
                    if (row != null) row.local = entry;
                }
                List<Row> drafts = new ArrayList<>(rows.values()); drafts.sort(java.util.Comparator.comparingLong((Row row) -> row.savedAt).reversed());
                String notice = warning;
                boolean onlineFailed = offline;
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || current != generation || !token.equals(UserPreferences.getToken(this))) return;
                    items.clear(); items.addAll(drafts); adapter.notifyDataSetChanged();
                    setDraftsLoading(false);
                    com.fimtale.ui.PageErrorView empty = findViewById(R.id.draftsEmpty);
                    summaryText = notice == null ? getString(R.string.drafts_count, items.size()) : notice;
                    summaryAdapter.notifyItemChanged(0);
                    if (onlineFailed) {
                        empty.setVisibility(View.GONE);
                        pageError.show(getString(R.string.drafts_online_load_failed), DraftsActivity.this::refresh, !items.isEmpty());
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || current != generation || !token.equals(UserPreferences.getToken(this))) return;
                    setDraftsLoading(false);
                    com.fimtale.ui.PageErrorView empty = findViewById(R.id.draftsEmpty);
                    summaryText = getString(R.string.drafts_load_failed_tap);
                    summaryAdapter.notifyItemChanged(0);
                    empty.setVisibility(View.GONE);
                    pageError.show(null, DraftsActivity.this::refresh, !items.isEmpty());
                });
            }
        });
    }
    private boolean accountMatches() {
        if (UserPreferences.isLoggedIn(this) && listedUser.equals(UserPreferences.getUserId(this))) return true;
        refresh(); return false;
    }
    private void launch(int workId, int chapterId, String slot) {
        startActivity(new Intent(this, EditorActivity.class).putExtra(EditorActivity.EXTRA_WORK_ID, workId)
                .putExtra(EditorActivity.EXTRA_CHAPTER_ID, chapterId)
                .putExtra(EditorActivity.EXTRA_DRAFT_ID, slot == null ? "" : slot));
    }
    private void open(Row row) {
        if (loading) return;
        if (!accountMatches()) return;
        if (row.local != null) { launch(row.local.workId, row.local.chapterId, row.local.draftId); return; }
        int current = ++generation;
        setDraftsLoading(true);
        String token = UserPreferences.getToken(this);
        io.execute(() -> {
            try {
                OnlineDraft draft = new DraftRemote(RetrofitClient.getInstance(), token).get(row.key);
                if (draft == null) throw new java.io.IOException(getString(R.string.drafts_deleted_online));
                DraftCodec.Target target = DraftCodec.target(draft.key, draft.payload);
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || current != generation || !token.equals(UserPreferences.getToken(this))) return;
                    setDraftsLoading(false); launch(target.workId, target.chapterId, target.slot);
                });
            } catch (Exception e) { main.post(() -> {
                if (isFinishing() || isDestroyed() || current != generation || !token.equals(UserPreferences.getToken(this))) return;
                setDraftsLoading(false); Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }); }
        });
    }
    private void remove(Row row) {
        if (!accountMatches()) return;
        new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.drafts_delete_title))
                .setMessage(getString(R.string.drafts_delete_message, row.title))
                .setNegativeButton(getString(R.string.common_cancel), null).setPositiveButton(getString(R.string.common_delete), (d, w) -> {
                    String token = UserPreferences.getToken(this);
                    io.execute(() -> {
                        try {
                            new DraftRemote(RetrofitClient.getInstance(), token).delete(row.key, row.revision);
                            if (row.local != null) row.local.delete();
                            main.post(() -> { if (!isDestroyed()) refresh(); });
                        } catch (Exception e) { main.post(() -> {
                            if (isDestroyed()) return;
                            Toast.makeText(this, getString(R.string.drafts_delete_failed, e.getMessage()), Toast.LENGTH_LONG).show(); refresh();
                        }); }
                    });
                }).show();
    }
    private static class SummaryHolder extends RecyclerView.ViewHolder {
        SummaryHolder(View view) { super(view); }
    }
    private class SummaryAdapter extends RecyclerView.Adapter<SummaryHolder> {
        @NonNull @Override public SummaryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new SummaryHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_drafts_summary, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull SummaryHolder holder, int position) {
            ((TextView) holder.itemView).setText(summaryText);
            holder.itemView.setOnClickListener(v -> refresh());
        }
        @Override public int getItemCount() { return 1; }
    }
    private class DraftAdapter extends RecyclerView.Adapter<DraftHolder> {
        @NonNull @Override public DraftHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new DraftHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_editor_draft, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull DraftHolder holder, int position) {
            Row entry = items.get(position);
            holder.title.setText(com.fimtale.editor.WorkInput.blank(entry.title) ? getString(R.string.drafts_unnamed) : entry.title);
            holder.preview.setText(com.fimtale.editor.WorkInput.blank(entry.preview) ? getString(R.string.drafts_empty_body) : entry.preview);
            String kind = entry.key.startsWith("chapter:") ? getString(R.string.drafts_chapter_kind) : getString(R.string.drafts_work_kind);
            String time = entry.savedAt > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.savedAt)) : getString(R.string.drafts_not_saved);
            holder.time.setText(getString(R.string.drafts_item_summary, kind, time, entry.uncertain ? getString(R.string.drafts_uncertain_suffix) : entry.pending ? getString(R.string.drafts_pending_suffix) : ""));
            holder.itemView.setOnClickListener(v -> open(entry));
            holder.more.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(DraftsActivity.this, v); menu.getMenu().add(getString(R.string.drafts_delete));
                menu.setOnMenuItemClickListener(item -> { remove(entry); return true; }); menu.show();
            });
        }
        @Override public int getItemCount() { return items.size(); }
    }
    private static class DraftHolder extends RecyclerView.ViewHolder {
        final TextView title, preview, time; final View more;
        DraftHolder(View view) {
            super(view); title = view.findViewById(R.id.draftTitle); preview = view.findViewById(R.id.draftPreview);
            time = view.findViewById(R.id.draftTime); more = view.findViewById(R.id.draftMore);
        }
    }
    @Override protected void onDestroy() {
        generation++; if (headerAnimator != null) headerAnimator.cancel(); io.shutdown(); super.onDestroy();
    }
}
