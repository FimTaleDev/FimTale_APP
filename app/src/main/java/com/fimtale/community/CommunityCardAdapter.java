package com.fimtale.community;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.fimtale.R;
import com.fimtale.SiteActivity;
import com.fimtale.TagArticlesActivity;
import com.fimtale.UserDetailActivity;
import com.fimtale.model.AuthorInfo;
import com.fimtale.utils.MdiIcons;
import com.google.android.material.button.MaterialButton;
import java.util.function.IntConsumer;
import java.util.Set;
import java.util.HashSet;
import com.fimtale.ui.ButtonLoadingIcon;

/** Compact cards for people, channels and tags. */
final class CommunityCardAdapter extends RecyclerView.Adapter<CommunityCardAdapter.Holder> {
    private final CommunityListModel model;
    private final IntConsumer remove;
    private final Set<ButtonLoadingIcon> loadingIcons = new HashSet<>();
    CommunityCardAdapter(CommunityListModel model, IntConsumer remove) { this.model = model; this.remove = remove; }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Holder holder = new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_community_card, parent, false));
        loadingIcons.add(holder.loading); return holder;
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        android.content.Context context = holder.itemView.getContext();
        String image = null, title, subtitle = "", body = "";
        Intent intent;
        boolean user = model.kind() == CommunityListModel.Kind.USER;
        if (user) {
            AuthorInfo item = model.users.get(position); image = item.getAvatar(); title = item.getUserName();
            subtitle = context.getString(R.string.community_user_number, item.getId());
            intent = new Intent(context, UserDetailActivity.class).putExtra(UserDetailActivity.EXTRA_USERNAME, item.getUserName());
        } else if (model.kind() == CommunityListModel.Kind.CHANNEL) {
            Community.Channel item = model.channels.get(position); image = item.cover(); title = item.name; body = item.description;
            subtitle = context.getString(R.string.community_channel_stats, item.count_works, item.count_user_fav);
            intent = com.fimtale.channels.ChannelActivity.intent(context, item.id);
        } else {
            Community.Tag item = model.tags.get(position); title = item.name;
            intent = new Intent(context, TagArticlesActivity.class).putExtra(TagArticlesActivity.EXTRA_TAG_NAME, item.name);
        }
        holder.title.setText(title); holder.subtitle.setText(subtitle); holder.subtitle.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        holder.body.setText(body); holder.body.setVisibility(body == null || body.isEmpty() ? View.GONE : View.VISIBLE);
        holder.image.setVisibility(model.kind() == CommunityListModel.Kind.TAG ? View.GONE : View.VISIBLE);
        Glide.with(context).load(image).placeholder(MdiIcons.drawable(context, user ? "account" : "wifi"))
                .error(MdiIcons.drawable(context, user ? "account" : "wifi")).into(holder.image);
        holder.itemView.setOnClickListener(v -> context.startActivity(intent));
        holder.action.setVisibility(model.favorites ? View.VISIBLE : View.GONE);
        holder.action.setText(model.kind() == CommunityListModel.Kind.TAG ? R.string.community_unfollow_tag : R.string.community_unfavorite);
        holder.action.setEnabled(!model.mutating);
        bindAction(holder, position);
        holder.action.setOnClickListener(v -> { int index = holder.getBindingAdapterPosition(); if (index != RecyclerView.NO_POSITION) remove.accept(index); });
    }
    private void bindAction(Holder holder, int position) {
        if (!model.favorites) { holder.loading.stop(); return; }
        boolean tag = model.kind() == CommunityListModel.Kind.TAG;
        String key = model.kind() == CommunityListModel.Kind.CHANNEL ? "channel:" + model.channels.get(position).id
                : tag ? "tag:" + model.tags.get(position).id : "";
        holder.action.setEnabled(!model.mutating);
        holder.loading.bind(holder.action, tag ? "heart" : "bookmark", model.favorites, model.favorites && model.pending(key));
    }
    @Override public int getItemCount() { return model.size(); }
    @Override public void onViewAttachedToWindow(@NonNull Holder holder) {
        super.onViewAttachedToWindow(holder); int position = holder.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION && position < model.size()) bindAction(holder, position);
    }
    @Override public void onViewDetachedFromWindow(@NonNull Holder holder) { holder.loading.stop(); super.onViewDetachedFromWindow(holder); }
    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView recycler) {
        for (ButtonLoadingIcon icon : loadingIcons) icon.close(); super.onDetachedFromRecyclerView(recycler);
    }
    @Override public void onViewRecycled(@NonNull Holder holder) { holder.loading.stop(); Glide.with(holder.itemView.getContext().getApplicationContext()).clear(holder.image); super.onViewRecycled(holder); }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image; final TextView title, subtitle, body; final MaterialButton action;
        final ButtonLoadingIcon loading = new ButtonLoadingIcon();
        Holder(View view) { super(view); image = view.findViewById(R.id.communityCardImage); title = view.findViewById(R.id.communityCardTitle);
            subtitle = view.findViewById(R.id.communityCardSubtitle); body = view.findViewById(R.id.communityCardBody); action = view.findViewById(R.id.communityCardAction); }
    }
}
