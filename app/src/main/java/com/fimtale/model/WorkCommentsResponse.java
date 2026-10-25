package com.fimtale.model;

import java.util.ArrayList;
import java.util.List;

public class WorkCommentsResponse {
    private List<Comment> items;
    public int total;
    public com.fimtale.community.Community.CommentViewer viewer;
    public List<Comment> getItems() { return items == null ? new ArrayList<>() : items; }
    public int totalPages(int perPage) { return Math.max(1, (total + perPage - 1) / perPage); }
}
