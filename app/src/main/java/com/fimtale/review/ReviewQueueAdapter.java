package com.fimtale.review;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.LoginActivity;
import com.fimtale.R;
import com.fimtale.ReviewQueueActivity;
import com.fimtale.TopicDetailActivity;
import com.fimtale.utils.MdiIcons;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import java.util.ArrayList;
import java.util.List;

/** One independently scrollable author review page. */
public final class ReviewQueueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final ReviewQueueActivity activity;
    private final ReviewQueueViewModel model;
    private final List<ReviewEntry> rows = new ArrayList<>();
    private final ReviewQueueViewModel.Section section;
    private final int[] empty = {R.string.review_empty_ready, R.string.review_empty_pending, R.string.review_empty_completed};
    public ReviewQueueAdapter(ReviewQueueActivity activity, ReviewQueueViewModel model, ReviewQueueViewModel.Section section) {
        this.activity = activity; this.model = model; this.section = section;
    }
    public void rebuild() {
        rows.clear();
        long now = System.currentTimeMillis();
        for (ReviewEntry entry : model.reviews) if (ReviewQueueViewModel.section(entry, now) == section) rows.add(entry);
        notifyDataSetChanged();
    }
    public int highlightedPosition() {
        for (int i = 0; i < rows.size(); i++) if (activity.isHighlighted(rows.get(i))) return i + 1;
        return -1;
    }
    @Override public int getItemCount() { return rows.size() + 2; }
    @Override public int getItemViewType(int position) {
        return position == 0 ? 0 : position == getItemCount() - 1 ? 2 : 1;
    }
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        int layout = type == 0 ? R.layout.item_review_queue_header : type == 2 ? R.layout.item_review_queue_footer
                : R.layout.item_review;
        return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false)) {};
    }
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        View view = holder.itemView;
        switch (getItemViewType(position)) {
            case 0:
                text(view, R.id.reviewSummary, model.loaded || !model.reviews.isEmpty()
                        ? activity.getString(R.string.review_summary, rows.size()) : "");
                break;
            case 1: row(view, rows.get(position - 1)); break;
            case 2: footer(view); break;
        }
    }
    private void footer(View view) {
        com.fimtale.ui.PageErrorView state = (com.fimtale.ui.PageErrorView) view;
        String message = model.error;
        if (message.isEmpty() && model.uncertain) message = activity.getString(R.string.review_action_uncertain);
        if (model.loading) state.hide();
        else if (model.needsLogin) state.showLogin(message,
                () -> activity.startActivity(new Intent(activity, LoginActivity.class)));
        else if (!message.isEmpty()) state.show(message, model::refresh);
        else if (rows.isEmpty()) state.showEmpty(activity.getString(empty[section.ordinal()]), model::refresh);
        else state.hide();
        state.setActionEnabled(!model.mutating);
    }
    private void row(View view, ReviewEntry entry) {
        view.setBackgroundColor(activity.isHighlighted(entry)
                ? MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimaryContainer) : Color.TRANSPARENT);
        text(view, R.id.reviewTitle, entry.title());
        View.OnClickListener openWork = v -> activity.startActivity(new Intent(activity, TopicDetailActivity.class)
                .putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, entry.workId));
        view.setOnClickListener(openWork);
        view.findViewById(R.id.reviewOpenWork).setOnClickListener(openWork);
        text(view, R.id.reviewNumber, activity.getString(R.string.review_work_number, entry.workId));
        Chip status = view.findViewById(R.id.reviewStatus);
        int label = entry.status == ReviewEntry.UNSUBMITTED ? R.string.review_unsubmitted
                : entry.status == ReviewEntry.PENDING ? R.string.review_pending : entry.status == ReviewEntry.PASSED ? R.string.review_passed
                : entry.status == ReviewEntry.REJECTED ? R.string.review_rejected : R.string.review_unknown;
        status.setText(label);
        status.setChipIcon(MdiIcons.drawable(activity, entry.status == ReviewEntry.UNSUBMITTED ? "file-document-outline"
                : entry.status == ReviewEntry.PENDING ? "clock-outline" : entry.status == ReviewEntry.PASSED ? "check-circle-outline" : "close-circle-outline"));
        status.setChipIconVisible(true); status.setChipIconSize(activity.getResources().getDimension(R.dimen.mdi_icon_size));
        int tone = entry.status == ReviewEntry.REJECTED ? com.google.android.material.R.attr.colorError
                : entry.status == ReviewEntry.PASSED ? com.google.android.material.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant;
        status.setChipIconTint(ColorStateList.valueOf(MaterialColors.getColor(status, tone)));
        String description = entry.status == ReviewEntry.REJECTED ? entry.reason()
                : activity.getString(entry.status == ReviewEntry.UNSUBMITTED ? R.string.review_not_queued
                : entry.status == ReviewEntry.PENDING ? R.string.review_received
                : entry.status == ReviewEntry.PASSED ? R.string.review_finished : R.string.review_unknown);
        text(view, R.id.reviewReason, description); visible(view, R.id.reviewReason, !description.isEmpty());
        String time = entry.status == ReviewEntry.UNSUBMITTED ? "" : ReviewEntry.date(entry.updatedAt);
        if (time.isEmpty() && entry.status != ReviewEntry.UNSUBMITTED) time = ReviewEntry.date(entry.createdAt);
        text(view, R.id.reviewUpdated, activity.getString(R.string.review_updated, time.isEmpty() ? activity.getString(R.string.common_missing_value) : time));
        String after = entry.status == ReviewEntry.REJECTED && entry.payload != null ? ReviewEntry.date(entry.payload.resubmitAfter) : "";
        text(view, R.id.reviewResubmit, activity.getString(R.string.review_resubmit_selected, after));
        visible(view, R.id.reviewResubmit, !after.isEmpty());
        visible(view, R.id.reviewSubmit, entry.canSubmit(System.currentTimeMillis()));
        text(view, R.id.reviewSubmit, activity.getString(entry.status == ReviewEntry.UNSUBMITTED ? R.string.review_submit : R.string.review_resubmit));
        view.findViewById(R.id.reviewSubmit).setEnabled(model.canSubmit(entry));
        view.findViewById(R.id.reviewSubmit).setOnClickListener(v -> model.open(entry));
    }
    private static void text(View view, int id, String text) { ((TextView) view.findViewById(id)).setText(text); }
    private static void visible(View view, int id, boolean visible) { view.findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE); }
}
