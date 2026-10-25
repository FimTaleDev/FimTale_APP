package com.fimtale.model;

/** Single comment returned by work/get_comment, used to resolve a BBCode reference. */
public class CommentResponse {
    public Comment comment;
    public com.fimtale.community.Community.CommentViewer viewer;
    @com.google.gson.annotations.SerializedName("page_number") public Integer pageNumber;
}
