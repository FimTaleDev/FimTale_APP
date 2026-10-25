package com.fimtale.model;

import com.google.gson.annotations.SerializedName;

/** Payload for the current work/create_update_comment endpoint. */
public final class WorkCommentRequest {
    @SerializedName("work_id") public final int workId;
    public final String content;
    public final Integer id;
    @SerializedName("chapter_id") public final Integer chapterId;
    @SerializedName("reply_comment_id") public final Integer replyCommentId;
    public WorkCommentRequest(int workId, String content) {
        this(workId, content, null, null);
    }
    public WorkCommentRequest(int workId, String content, Integer chapterId, Integer replyCommentId) {
        this(workId, content, chapterId, replyCommentId, null);
    }
    public WorkCommentRequest(int workId, String content, Integer chapterId, Integer replyCommentId, Integer id) {
        this.workId = workId; this.content = content; this.chapterId = chapterId; this.replyCommentId = replyCommentId;
        this.id = id;
    }
}
