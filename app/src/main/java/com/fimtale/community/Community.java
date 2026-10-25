package com.fimtale.community;

import com.fimtale.model.Comment;
import com.fimtale.network.SiteUrls;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Wire models shared by comments, profile tabs and collections. */
public final class Community {
    private Community() { }
    private static final AtomicLong version = new AtomicLong();
    public static final androidx.lifecycle.MutableLiveData<CommentEvent> commentEvents = new androidx.lifecycle.MutableLiveData<>();
    public static final class CommentEvent {
        public final long version; public final int workId;
        CommentEvent(long version, int workId) { this.version = version; this.workId = workId; }
    }
    public static long version() { return version.get(); }
    public static void changed() { version.incrementAndGet(); }
    public static void commentChanged(int workId) {
        changed(); commentEvents.setValue(new CommentEvent(version(), workId));
    }
    public static class CommentId {
        public final int comment_id;
        public CommentId(int id) { comment_id = id; }
    }
    public static class CommentVote extends CommentId {
        public final int operation;
        public CommentVote(int id, int operation) { super(id); this.operation = operation; }
    }
    public static class DeleteComment {
        public final int type = 4;
        public final int id;
        public DeleteComment(int id) { this.id = id; }
    }
    public static class Operation { public int comment_id, operation; }
    public static class CommentFavorite { public int comment_id; }
    public static class CommentViewer {
        public List<Operation> operations;
        public List<CommentFavorite> favs;
        public void apply(Comment comment) {
            comment.viewerLoaded = true;
            comment.liked = comment.disliked = comment.favorited = false;
            if (operations != null) for (Operation item : operations) if (item != null && item.comment_id == comment.id) {
                if (item.operation == 1) comment.liked = true;
                if (item.operation == 2) comment.disliked = true;
            }
            if (favs != null) for (CommentFavorite item : favs)
                if (item != null && item.comment_id == comment.id) comment.favorited = true;
        }
    }
    public static class CommentPage {
        public List<Comment> items;
        public int total;
        public List<Comment> items() { return items == null ? new ArrayList<>() : items; }
    }
    public static class CommentHistory { public int id, comment_id; public String created_at, previous_content; }
    public static class UserId {
        public final int user_id;
        public UserId(int id) { user_id = id; }
    }
    public static class ConversationRequest {
        public final int[] user_ids;
        public ConversationRequest(int id) { user_ids = new int[]{id}; }
    }
    public static class Follow { public int follower_id, followee_id; }
    public static class UserTab { public String current_tab; public JsonElement content; public int count; }
    public static class FolderRequest {
        public final Integer folder_id;
        public final String name;
        public FolderRequest(Integer id, String name) { folder_id = id; this.name = name; }
    }
    public static class ChannelId {
        public final int channel_id;
        public ChannelId(int id) { channel_id = id; }
    }
    public static class Channel {
        public int id, count_works, count_user_fav;
        public String name, description, cover_image;
        public String cover() { return SiteUrls.media(cover_image); }
    }
    public static class Tag {
        public int id, tag_type_id;
        public String name, bg_color, text_color;
    }
    public static class TagId {
        public final int tag_id;
        public TagId(int id) { tag_id = id; }
    }
    public static class TagPage {
        public List<Tag> items;
        public int total;
    }
    public static class TagFavorite { public boolean faved; public int count_fav; }
}
