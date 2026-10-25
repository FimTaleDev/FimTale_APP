package com.fimtale.network;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
public final class SearchQuery {
    private SearchQuery() {}
    public static String keywords(String text) {
        JsonObject query = new JsonObject();
        if (text != null && !text.trim().isEmpty()) query.addProperty("keywords", text.trim());
        return query.toString();
    }
    public static String type(int type) {
        JsonObject query = new JsonObject();
        query.addProperty("filter", "type:" + type);
        return query.toString();
    }
    public static String tags(java.util.Collection<String> tags, boolean matchAll) {
        if (tags == null || tags.isEmpty()) throw new IllegalArgumentException("A tag scope is required");
        JsonArray children = new JsonArray();
        for (String name : tags) {
            JsonObject term = new JsonObject(); term.addProperty("op", "tag"); term.addProperty("tag", name); children.add(term);
        }
        JsonObject scope = new JsonObject(); scope.addProperty("op", matchAll ? "and" : "or"); scope.add("children", children);
        JsonObject query = new JsonObject(); query.add("tags_expr", scope); return query.toString();
    }
    public static String rank(String field) {
        if (field == null || field.isEmpty()) return null;
        JsonObject term = new JsonObject();
        term.addProperty("field", field);
        term.addProperty("direction", "desc");
        JsonArray terms = new JsonArray(); terms.add(term);
        JsonObject rank = new JsonObject();
        rank.addProperty("mode", "strict"); rank.add("terms", terms);
        return rank.toString();
    }
}
