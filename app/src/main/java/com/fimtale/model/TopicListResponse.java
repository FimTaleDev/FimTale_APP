package com.fimtale.model;
import java.util.ArrayList;
import java.util.List;
public class TopicListResponse {
    private List<Topic> items;
    private int total;
    public List<Topic> getTopicArray() { return items == null ? new ArrayList<>() : items; }
    public int getTotalPage() { return (total + 19) / 20; }
    public int getTotal() { return total; }
}
