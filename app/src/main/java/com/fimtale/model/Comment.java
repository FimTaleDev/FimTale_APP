package com.fimtale.model;

import com.fimtale.R;
import com.fimtale.utils.AppStrings;

import com.google.gson.annotations.SerializedName;

/** A comment from work/get_comments, including its author and chapter context. */
public class Comment {
    public int id;
    public AuthorInfo user;
    public String content;
    public String title;
    @SerializedName("work_id") public int workId;
    @SerializedName("chapter_id") public int chapterId;
    @SerializedName("reply_comment_id") public int replyCommentId;
    @SerializedName("created_at") public String createdAt;
    @SerializedName("status_del") public int statusDel;
    @SerializedName("status_top") public boolean pinned;
    @SerializedName("user_id") public int userId;
    @SerializedName("edited_at") public String editedAt;
    @SerializedName("count_like") public int likes;
    @SerializedName("count_dislike") public int dislikes;
    @SerializedName("count_fav") public int favorites;
    @SerializedName("count_star_honor") public int starHonor;
    public transient boolean liked, disliked, favorited, viewerLoaded;
    public int authorId() { return user == null || user.getId() <= 0 ? userId : user.getId(); }
    public String getAvatarUrl() { return user == null ? null : user.getAvatar(); }
    public String getUserName() { return user == null ? AppStrings.get(R.string.profile_unknown_user) : user.getUserName(); }
    public String getContent() { return statusDel != 0 ? AppStrings.get(R.string.comments_deleted) : content; }
    public String getChapterTitle() { return chapterId == 0 ? AppStrings.get(R.string.work_type_article) : title == null || title.isEmpty() ? AppStrings.get(R.string.work_chapter_number, chapterId) : title; }
    public String getTime() {
        try {
            return java.time.OffsetDateTime.parse(createdAt).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern(AppStrings.get(R.string.common_date_time)));
        } catch (RuntimeException ignored) { return ""; }
    }
}
