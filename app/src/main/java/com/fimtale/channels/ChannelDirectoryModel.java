package com.fimtale.channels;

import android.app.Application;
import androidx.lifecycle.SavedStateHandle;
import com.fimtale.community.Community;
import com.fimtale.modules.*;
import com.google.gson.*;
import static com.fimtale.modules.ModuleApi.*;

public class ChannelDirectoryModel extends ModuleModel {
    public JsonObject auth = new JsonObject();
    public JsonArray channels = new JsonArray();
    public int tab, page = 1, total, createdId;
    public String search = "", input = "";
    private int lastPage;

    public ChannelDirectoryModel(Application app, SavedStateHandle state) {
        super(app, state);
        Integer selected = state.get("tab"), savedPage = state.get("page");
        tab = selected != null && selected == 1 ? 1 : 0;
        page = savedPage == null ? 1 : Math.max(1, savedPage);
        String query = state.get("search"), draft = state.get("search_input");
        search = query == null ? "" : query; input = draft == null ? search : draft;
    }
    @Override protected void reset() { auth = new JsonObject(); channels = new JsonArray(); page = 1; total = createdId = lastPage = 0; }
    public void input(String value) { input = value; saved.set("search_input", value); }
    public void search() { search = input.trim(); saved.set("search", search); total = lastPage = 0; load(1); }
    public void select(int value) { if (busy()) return; tab = value; saved.set("tab", tab); total = lastPage = 0; load(1); }
    public void refresh() {
        if (busy()) return;
        cancelReads(); auth = new JsonObject(); lastPage = 0;
        if (signedIn()) read("header", Route.AUTH, query(), value -> auth = obj(value), null);
        load(page);
    }
    public void load(int requested) {
        cancel("page"); channels = new JsonArray(); page = Math.max(1, requested); saved.set("page", page);
        if (tab == 1 && !signedIn()) { changed(); return; }
        read("page", tab == 0 ? Route.CHANNEL_SEARCH : Route.FAVORITE_CHANNELS,
                tab == 0 ? query("page", page, "per_page", 20, "lucene_query", search.isEmpty() ? null : search) : query(), value -> {
                    if (tab == 0) {
                        JsonObject data = obj(value);
                        if (!data.has("items")) throw new IllegalStateException();
                        channels = array(data, "items"); total = num(data, "total");
                        if (total <= 0 && channels.size() < 20) lastPage = channels.size() == 0 && page > 1 ? page - 1 : page;
                    } else { channels = array(value); total = channels.size(); }
                }, () -> {
                    if (!errors.containsKey("page") && page > 1 && (channels.size() == 0 || page > pages())) load(Math.max(1, page - 1));
                });
    }
    public int pages() { return tab == 1 ? 1 : total > 0 ? Math.max(1, (total + 19) / 20) : lastPage > 0 ? lastPage : page + (channels.size() == 20 ? 1 : 0); }
    public boolean canCreate() { return signedIn() && num(auth, "qualify_status") >= 2; }
    public void create(JsonObject values) {
        if (!canCreate()) return;
        post("create", Route.CREATE_CHANNEL, values, value -> {
            int id = num(obj(value), "id");
            if (id <= 0) throw new IllegalStateException();
            createdId = id; Community.changed(); total = lastPage = 0; load(1);
        });
    }
}
