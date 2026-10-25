package com.fimtale.adapter;


import com.fimtale.utils.MdiIcons;

import android.view.LayoutInflater;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fimtale.R;
import com.fimtale.model.Comment;
import com.fimtale.UserDetailActivity;
import com.fimtale.utils.BbCodeRendering;
import io.noties.markwon.Markwon;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import com.fimtale.ui.ButtonLoadingIcon;
import com.google.android.material.button.MaterialButton;

public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.CommentViewHolder> {

    private List<Comment> commentList;
    private final Markwon markwon;
    private final com.fimtale.community.CommentActions actions;
    private boolean favoritesOnly;
    private Runnable refresh;
    private final Set<ButtonLoadingIcon> loadingIcons = new HashSet<>();

    public CommentAdapter(List<Comment> commentList, Context context) {
        this.commentList = commentList;
        markwon = BbCodeRendering.create(context);
        actions = context instanceof androidx.appcompat.app.AppCompatActivity
                ? new com.fimtale.community.CommentActions((androidx.appcompat.app.AppCompatActivity) context, this::replace, this::notifyDataSetChanged) : null;
    }
    public void setContext(int workId, int authorId, Runnable refresh) { this.refresh = refresh; if (actions != null) actions.context(workId, authorId, refresh); }
    public void setFavoritesOnly(boolean only) { favoritesOnly = only; }
    public void setOnReply(java.util.function.Consumer<Comment> reply) { if (actions != null) actions.setOnReply(reply); }
    public void setWorkAuthor(int authorId) { if (actions != null) actions.author(authorId); }
    private void replace(Comment comment) {
        for (int i = 0; i < commentList.size(); i++) if (commentList.get(i).id == comment.id) {
            if (favoritesOnly && (!comment.favorited || comment.statusDel != 0)) {
                commentList.remove(i); notifyItemRemoved(i); if (refresh != null) refresh.run(); return;
            }
            commentList.set(i, comment); notifyItemChanged(i); return;
        }
    }
    public int positionOf(int id) { for (int i = 0; i < commentList.size(); i++) if (commentList.get(i).id == id) return i; return -1; }
    public void close() { if (actions != null) actions.close(); for (ButtonLoadingIcon icon : loadingIcons) icon.close(); }

    @NonNull
    @Override
    public CommentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_comment, parent, false);
        CommentViewHolder holder = new CommentViewHolder(view); loadingIcons.add(holder.loading);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull CommentViewHolder holder, int position) {
        Comment comment = commentList.get(position);
        if (actions != null && comment.statusDel == 0) actions.hydrate(comment);
        holder.tvUserName.setText(comment.pinned ? holder.itemView.getContext().getString(R.string.comments_pinned_author, comment.getUserName()) : comment.getUserName());
        holder.tvTime.setText(comment.getTime());
        BbCodeRendering.setText(markwon, holder.tvContent, comment.getContent());
        holder.tvChapter.setText(holder.itemView.getContext().getString(R.string.comments_context, comment.id, comment.getChapterTitle(), comment.replyCommentId > 0 ? holder.itemView.getContext().getString(R.string.comments_reply_to, comment.replyCommentId) : ""));
        View.OnClickListener openUser = v -> {
            if (comment.user == null || comment.getUserName() == null || comment.getUserName().isEmpty()) return;
            v.getContext().startActivity(new Intent(v.getContext(), UserDetailActivity.class)
                    .putExtra(UserDetailActivity.EXTRA_USERNAME, comment.getUserName()));
        };
        holder.ivAvatar.setOnClickListener(openUser); holder.tvUserName.setOnClickListener(openUser);
        if (comment.user == null && comment.authorId() > 0) {
            holder.tvUserName.setText(holder.itemView.getContext().getString(R.string.community_user_number, comment.authorId()));
            holder.ivAvatar.setOnClickListener(v -> { if (actions != null) actions.openAuthor(comment); });
            holder.tvUserName.setOnClickListener(v -> { if (actions != null) actions.openAuthor(comment); });
        }
        bindActions(holder, comment);
        holder.edited.setVisibility(comment.editedAt != null && !comment.editedAt.equals(comment.createdAt) ? View.VISIBLE : View.GONE);
        holder.edited.setOnClickListener(v -> { if (actions != null) actions.history(comment); });
        holder.tvChapter.setOnClickListener(v -> { if (actions != null) actions.openContext(comment); });

        Glide.with(holder.itemView.getContext())
                .load(comment.getAvatarUrl())
                .transform(new CircleCrop())
                .placeholder(MdiIcons.drawable(holder.itemView.getContext(), "account"))
                .error(MdiIcons.drawable(holder.itemView.getContext(), "account"))
                .into(holder.ivAvatar);
    }

    @Override
    public int getItemCount() {
        return commentList.size();
    }

    public void updateData(List<Comment> newComments) {
        this.commentList = newComments;
        notifyDataSetChanged();
    }
    public void updateData(List<Comment> comments, com.fimtale.community.Community.CommentViewer viewer) {
        if (viewer != null) for (Comment comment : comments) viewer.apply(comment);
        updateData(comments);
    }
    private void bindActions(CommentViewHolder holder, Comment comment) {
        boolean active = actions != null && comment.statusDel == 0;
        holder.actions.setVisibility(active ? View.VISIBLE : View.GONE);
        boolean busy = actions != null && actions.busy(comment);
        int loading = actions == null ? 0 : actions.loadingAction(comment);
        if (!active) { holder.loading.stop(); return; }
        bindAction(holder, holder.like, comment.likes, comment.liked, "thumb-up", !busy, loading, v -> actions.vote(comment, 1));
        bindAction(holder, holder.dislike, comment.dislikes, comment.disliked, "thumb-down", !busy, loading, v -> actions.vote(comment, 2));
        holder.favorite.setText(null); holder.favorite.setEnabled(!busy);
        holder.loading.bind(holder.favorite, comment.favorited ? "bookmark" : "bookmark-outline", comment.favorited, loading == R.id.commentFavorite);
        holder.favorite.setOnClickListener(v -> actions.favorite(comment));
        holder.favorite.setContentDescription(holder.itemView.getContext().getString(comment.favorited ? R.string.community_unfavorite : R.string.community_favorite));
        holder.reply.setEnabled(!busy); holder.reply.setOnClickListener(v -> actions.reply(comment));
        holder.more.setEnabled(!busy); holder.more.setOnClickListener(v -> actions.more(comment));
        holder.loading.bind(holder.more, "dots-horizontal", false, loading == R.id.commentMore);
        holder.star.setVisibility(comment.starHonor > 0 || actions != null && actions.canStar(comment) || loading == R.id.commentStar ? View.VISIBLE : View.GONE);
        holder.star.setText(comment.starHonor > 0 ? String.valueOf(comment.starHonor) : "");
        holder.star.setEnabled(!busy && comment.starHonor == 0);
        holder.star.setOnClickListener(v -> actions.star(comment));
        holder.loading.bind(holder.star, comment.starHonor > 0 ? "star" : "star-outline", comment.starHonor > 0, loading == R.id.commentStar);
    }
    private void bindAction(CommentViewHolder holder, MaterialButton view, int count, boolean active, String icon, boolean enabled, int loading, View.OnClickListener listener) {
        view.setText(String.valueOf(count)); view.setEnabled(enabled);
        holder.loading.bind(view, active ? icon : icon + "-outline", active, loading == view.getId());
        view.setOnClickListener(listener);
    }

    @Override public void onViewAttachedToWindow(@NonNull CommentViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        int position = holder.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION && position < commentList.size()) bindActions(holder, commentList.get(position));
    }
    @Override public void onViewDetachedFromWindow(@NonNull CommentViewHolder holder) { holder.loading.stop(); super.onViewDetachedFromWindow(holder); }

    @Override public void onViewRecycled(@NonNull CommentViewHolder holder) {
        holder.loading.stop();
        Glide.with(holder.itemView.getContext().getApplicationContext()).clear(holder.ivAvatar);
        holder.tvContent.setText(null);
        super.onViewRecycled(holder);
    }

    static class CommentViewHolder extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView tvUserName;
        TextView tvTime;
        TextView tvContent;
        TextView tvChapter;
        final TextView edited;
        final MaterialButton like, dislike, favorite, reply, star, more;
        final ButtonLoadingIcon loading = new ButtonLoadingIcon();
        final View actions;

        public CommentViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.ivAvatar);
            tvUserName = itemView.findViewById(R.id.tvUserName);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvContent = itemView.findViewById(R.id.tvContent);
            tvChapter = itemView.findViewById(R.id.tvChapter);
            like = itemView.findViewById(R.id.commentLike); dislike = itemView.findViewById(R.id.commentDislike);
            favorite = itemView.findViewById(R.id.commentFavorite); reply = itemView.findViewById(R.id.commentReply);
            star = itemView.findViewById(R.id.commentStar); more = itemView.findViewById(R.id.commentMore);
            edited = itemView.findViewById(R.id.commentEdited); actions = itemView.findViewById(R.id.commentActions);
        }
    }
}
