package com.fimtale.adapter;

import com.fimtale.utils.MdiIcons;
import com.fimtale.utils.TagChipLayout;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.R;
import com.fimtale.TagArticlesActivity;
import com.fimtale.TopicDetailActivity;
import com.fimtale.model.Tags;
import com.fimtale.model.TopicViewItem;
import com.fimtale.ui.ParallaxImageView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import java.util.List;

public class TopicAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<TopicViewItem> topics;
    public TopicAdapter(List<TopicViewItem> topics) {
        this.topics = topics;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.grid_item_topic, parent, false);
        return new TopicViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof TopicViewHolder) {
            TopicViewHolder topicHolder = (TopicViewHolder) holder;
            TopicViewItem topic = topics.get(position);
            bindCard(topicHolder, topic);
        }
    }

    /** Uses the same work card in native pages without a RecyclerView. */
    public static void bindCard(@NonNull View card, @NonNull TopicViewItem topic) {
        bindCard(new TopicViewHolder(card), topic);
    }

    private static void bindCard(TopicViewHolder topicHolder, TopicViewItem topic) {
        Tags tags = topic.getTags();
        topicHolder.titleTextView.setText(topic.getTitle());
        topicHolder.authorTextView.setText(topic.getAuthorName());

        // 绑定分类标签
        if (topicHolder.tagChipGroup != null) {
            topicHolder.tagChipGroup.removeAllViews();
            if (tags != null) {
                topicHolder.tagChipGroup.setVisibility(View.VISIBLE);
                if (!TextUtils.isEmpty(tags.getStatus())) {
                    addTagChip(topicHolder.tagChipGroup, tags.getStatus(), true);
                }
                if (tags.getOtherTags() != null) {
                    for (String tagName : tags.getOtherTags()) {
                        addTagChip(topicHolder.tagChipGroup, tagName, false);
                    }
                }
                TagChipLayout.alignHeights(topicHolder.tagChipGroup);
            } else {
                topicHolder.tagChipGroup.setVisibility(View.GONE);
            }
        }

        // 绑定图片层标签
        bindCoverTag(topicHolder.tagType, tags == null ? null : tags.getType());
        bindCoverTag(topicHolder.tagSource, tags == null ? null : tags.getSource());
        bindCoverTag(topicHolder.tagLength, tags == null ? null : tags.getLength());
        String rating = tags == null ? null : tags.getRating();
        bindCoverTag(topicHolder.tagRate, rating);
        if (topicHolder.tagRate != null) {
            int backgroundColor = 0x80000000;
            if (rating != null) {
                if (rating.equalsIgnoreCase("Everyone") || rating.equalsIgnoreCase("E")) {
                    backgroundColor = 0xFF4CAF50;
                } else if (rating.equalsIgnoreCase("Teen") || rating.equalsIgnoreCase("T")) {
                    backgroundColor = 0xFFFFC107;
                } else if (rating.equalsIgnoreCase("Restricted") || rating.equalsIgnoreCase("Mature") || rating.equalsIgnoreCase("M")) {
                    backgroundColor = 0xFFF44336;
                }
            }

            Drawable background = topicHolder.tagRate.getBackground();
            if (background instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) background.mutate()).setColor(backgroundColor);
            }
        }

        // 绑定统计数据
        if (topicHolder.wordCountTextView != null) {
            topicHolder.wordCountTextView.setText(topic.getWordCount());
        }
        if (topicHolder.viewCountTextView != null) {
            topicHolder.viewCountTextView.setText(topic.getViewCount());
        }
        if (topicHolder.commentCountTextView != null) {
            topicHolder.commentCountTextView.setText(topic.getCommentCount());
        }
        if (topicHolder.favoriteCountTextView != null) {
            topicHolder.favoriteCountTextView.setText(topic.getFavoriteCount());
        }

        ParallaxImageView parallaxView = null;
        if (topicHolder.coverImageView instanceof ParallaxImageView) {
            parallaxView = (ParallaxImageView) topicHolder.coverImageView;
            parallaxView.setParallaxEnabled(false);
        }

        final ParallaxImageView finalParallaxView = parallaxView;

        Glide.with(topicHolder.itemView.getContext())
                .load(topic.getBackground())
                .placeholder(MdiIcons.coverPlaceholder(topicHolder.itemView.getContext()))
                .error(MdiIcons.coverPlaceholder(topicHolder.itemView.getContext()))
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        if (finalParallaxView != null) {
                            finalParallaxView.setParallaxEnabled(false);
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        if (finalParallaxView != null) {
                            finalParallaxView.setParallaxEnabled(true);
                        }
                        return false;
                    }
                })
                .into(topicHolder.coverImageView);

        topicHolder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), TopicDetailActivity.class);
            intent.putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, topic.getId());
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        if (topics == null) return 0;
        return topics.size();
    }
    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof TopicViewHolder) {
            clearCard(holder.itemView);
        }
        super.onViewRecycled(holder);
    }

    public static void clearCard(@NonNull View card) {
        ImageView cover = card.findViewById(R.id.coverImageView);
        Glide.with(card.getContext().getApplicationContext()).clear(cover);
        if (cover instanceof ParallaxImageView) ((ParallaxImageView) cover).setParallaxEnabled(false);
    }

    private static void bindCoverTag(@Nullable TextView view, @Nullable String text) {
        if (view == null) return;
        boolean hasText = text != null && TextUtils.getTrimmedLength(text) > 0;
        view.setText(hasText ? text : null);
        view.setVisibility(hasText ? View.VISIBLE : View.GONE);
    }

    private static void addTagChip(ChipGroup group, String text, boolean isStatus) {
        Chip chip = new Chip(group.getContext());
        chip.setText(text);
        chip.setCheckable(false);
        chip.setClickable(!isStatus);
        chip.setChipStrokeWidth(0);
        chip.setEnsureMinTouchTargetSize(false);
        
        chip.setChipStartPadding(12f);
        chip.setChipEndPadding(12f);
        chip.setChipMinHeight(24f);
        
        if (isStatus) {
            TypedValue typedValue = new TypedValue();
            group.getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true);
            chip.setChipBackgroundColor(ColorStateList.valueOf(typedValue.data));
            
            group.getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, typedValue, true);
            chip.setTextColor(typedValue.data);
        } else {
            TypedValue typedValue = new TypedValue();
            group.getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, typedValue, true);
            chip.setChipBackgroundColor(ColorStateList.valueOf(typedValue.data));
            
            group.getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true);
            chip.setTextColor(typedValue.data);
        }

        if (!isStatus) chip.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), TagArticlesActivity.class);
            intent.putExtra(TagArticlesActivity.EXTRA_TAG_NAME, text);
            v.getContext().startActivity(intent);
        });
        
        group.addView(chip);
    }

    public static class TopicViewHolder extends RecyclerView.ViewHolder {
        ImageView coverImageView;
        TextView titleTextView;
        TextView authorTextView;
        TextView wordCountTextView;
        TextView viewCountTextView;
        TextView commentCountTextView;
        TextView favoriteCountTextView;
        TextView tagType, tagSource, tagLength, tagRate;
        ChipGroup tagChipGroup;

        public TopicViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImageView = itemView.findViewById(R.id.coverImageView);
            titleTextView = itemView.findViewById(R.id.titleTextView);
            authorTextView = itemView.findViewById(R.id.authorTextView);
            wordCountTextView = itemView.findViewById(R.id.wordCountTextView);
            viewCountTextView = itemView.findViewById(R.id.viewCountTextView);
            commentCountTextView = itemView.findViewById(R.id.commentCountTextView);
            favoriteCountTextView = itemView.findViewById(R.id.favoriteCountTextView);
            
            tagType = itemView.findViewById(R.id.tagType);
            tagSource = itemView.findViewById(R.id.tagSource);
            tagLength = itemView.findViewById(R.id.tagLength);
            tagRate = itemView.findViewById(R.id.tagRate);
            tagChipGroup = itemView.findViewById(R.id.tagChipGroup);
        }
    }
}
