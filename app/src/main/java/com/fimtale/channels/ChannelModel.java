package com.fimtale.channels;

import android.app.Application;
import androidx.lifecycle.SavedStateHandle;
import com.fimtale.R;
import com.fimtale.community.Community;
import com.fimtale.modules.*;
import com.fimtale.utils.UserPreferences;
import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import static com.fimtale.modules.ModuleApi.*;

/** Account-scoped channel state; folder and ballot authorization is enforced again by the server. */
public class ChannelModel extends ModuleModel {
    public int id, tab, page = 1, total, folder = -1, role, qualify, focusComment, selectedBallotId;
    public JsonObject channel = new JsonObject(), pageData = new JsonObject(), auth = new JsonObject();
    public JsonArray folders = new JsonArray(), tags = new JsonArray(), collaborators = new JsonArray();
    public JsonArray blacklist = new JsonArray(), ballots = new JsonArray(), history;
    public JsonObject ballotDetail = new JsonObject(), ballotStats = new JsonObject(), activeDetail = new JsonObject();
    public String search = "", commentFilter = "", historyTitle = "";
    private int seeking, lastPage;

    public ChannelModel(Application app, SavedStateHandle state) { super(app, state); }

    public void init(int channelId, int commentId, int initialTab, int initialFolder, int ballotId) {
        if (id > 0) return;
        id = channelId;
        tab = Math.max(0, Math.min(5, integer("tab", commentId > 0 ? 2 : ballotId > 0 ? 5 : initialTab)));
        page = Math.max(1, integer("page", 1));
        folder = Math.max(-1, integer("folder", initialFolder));
        selectedBallotId = integer("ballot_id", ballotId);
        search = draft("search"); commentFilter = draft("filter");
        seeking = integer("seeking", commentId);
    }

    private int integer(String key, int fallback) { Integer value = saved.get(key); return value == null ? fallback : value; }
    public String draft(String key) { String value = saved.get(key); return value == null ? "" : value; }
    public void draft(String key, String value) { saved.set(key, value); }

    @Override protected void reset() {
        role = qualify = 0; page = 1; total = lastPage = 0; seeking = focusComment = 0;
        channel = new JsonObject(); pageData = new JsonObject(); auth = new JsonObject();
        folders = new JsonArray(); tags = new JsonArray(); collaborators = new JsonArray();
        blacklist = new JsonArray(); ballots = new JsonArray();
        ballotDetail = new JsonObject(); ballotStats = new JsonObject(); activeDetail = new JsonObject();
        history = null; search = commentFilter = "";
    }

    public boolean ready() { return num(channel, "id") == id && id > 0; }
    public boolean deleted() { return num(channel, "status_del") != 0; }
    public int userId() { return number(UserPreferences.getUserId(getApplication())); }
    public boolean collaborator() {
        JsonObject member = obj(obj(channel, "viewer"), "collaborator");
        return signedIn() && userId() > 0 && num(member, "user_id") == userId()
                && (!member.has("pending_until") || member.get("pending_until").isJsonNull());
    }
    public boolean manager() { return ready() && signedIn() && (role >= 4 || collaborator()); }
    public boolean faved() { return signedIn() && array(obj(channel, "viewer"), "favs").size() > 0; }
    public boolean voted(int operation) {
        if (!signedIn()) return false;
        for (JsonElement value : array(obj(channel, "viewer"), "operations"))
            if (num(obj(value), "operation") == operation) return true;
        return false;
    }
    public JsonObject selectedFolder() {
        for (JsonElement value : folders) if (num(obj(value), "id") == folder) return obj(value);
        return new JsonObject();
    }
    public JsonObject activeBallot() { return obj(selectedFolder(), "active_ballot"); }
    public boolean canAdd() {
        return ready() && !deleted() && folder >= 0 && signedIn()
                && (manager() || bool(selectedFolder(), "is_public"));
    }
    public boolean canRemove(JsonObject work) {
        return canAdd() && (manager() || faved() || num(obj(work, "user"), "user_id") == userId());
    }
    public boolean canCompose() {
        return ready() && signedIn() && qualify >= 1 && !deleted() && num(channel, "status_locked") == 0;
    }

    public void refresh() {
        if (busy() || id <= 0) return;
        cancelReads();
        role = qualify = 0; auth = new JsonObject();
        if (signedIn()) {
            read("auth", Route.AUTH, query(), value -> {
                auth = obj(value);
                if (num(auth, "user_id") == userId()) {
                    role = num(auth, "role_id"); qualify = num(auth, "qualify_status");
                }
            }, this::refreshChannel);
        } else refreshChannel();
    }

    private void refreshChannel() {
        lastPage = 0;
        read("header", Route.CHANNEL, query("channel_id", id), value -> {
            JsonObject next = obj(value);
            if (num(next, "id") != id) throw new IllegalStateException();
            channel = next;
        }, () -> {
            if (errors.containsKey("header")) { channel = new JsonObject(); return; }
            read("folders", Route.FOLDERS, query("channel_id", id), value -> folders = array(value), () -> {
                if (errors.containsKey("folders")) folders = new JsonArray();
                if (folder > 0 && selectedFolder().size() == 0) { folder = -1; saved.set("folder", folder); }
                load(page);
            });
        });
    }

    private void cancelPage() {
        cancel("page", "tags", "collaborators", "blacklist", "active_ballot", "ballot", "statistics", "history");
    }
    public void select(int value) {
        if (busy()) return;
        tab = Math.max(0, Math.min(5, value)); saved.set("tab", tab);
        seeking = focusComment = 0; saved.set("seeking", 0);
        selectedBallotId = 0; saved.set("ballot_id", 0); history = null; total = lastPage = 0;
        load(1);
    }
    public void folder(int value) {
        if (busy()) return;
        folder = value; saved.set("folder", folder);
        search = ""; draft("search", ""); draft("work_query", ""); total = lastPage = 0;
        load(1);
    }
    public void showFolder(int value) {
        if (busy()) return;
        tab = 1; saved.set("tab", tab); folder(value);
    }
    public void filter(String value) {
        if (busy()) return;
        commentFilter = value; draft("filter", value);
        seeking = focusComment = 0; saved.set("seeking", 0); total = lastPage = 0; load(1);
    }
    public void searchWorks(String value) {
        if (busy()) return;
        search = value.trim(); draft("search", search); total = lastPage = 0; load(1);
    }
    public int pages() {
        return total > 0 ? Math.max(1, (total + 19) / 20) : lastPage > 0 ? lastPage : Math.max(1, page + (rows().size() == 20 ? 1 : 0));
    }
    public JsonArray rows() { return array(pageData, "items"); }

    public void load(int value) {
        cancelPage(); page = Math.max(1, value); saved.set("page", page);
        pageData = new JsonObject(); activeDetail = new JsonObject();
        if (!ready()) { changed(); return; }
        int requested = page;
        if (tab == 1) {
            Integer requestedFolder = folder < 0 ? Integer.valueOf(0) : folder == 0 ? null : Integer.valueOf(folder);
            read("page", Route.WORKS, query("channel_id", id, "folder_id", requestedFolder,
                    "page", requested, "per_page", 20, "lucene_query", search.isEmpty() ? null : search),
                    this::acceptPage, this::pageLoaded);
            int ballotId = num(activeBallot(), "id");
            if (ballotId > 0) read("active_ballot", Route.BALLOT, query("channel_id", id, "ballot_id", ballotId),
                    data -> activeDetail = obj(data), null);
        } else if (tab == 2) {
            JsonArray selected = new JsonArray(); if (!commentFilter.isEmpty()) selected.add(commentFilter);
            read("page", Route.COMMENTS, query("channel_id", id, "page", requested, "per_page", 20, "tags", selected),
                    this::acceptPage, () -> {
                        if (!errors.containsKey("page") && seeking > 0) {
                            boolean found = false;
                            for (JsonElement row : rows()) if (num(obj(row), "id") == seeking) found = true;
                            if (found) { focusComment = seeking; seeking = 0; }
                            else if (page < pages()) { load(page + 1); return; }
                            else { notice = getApplication().getString(R.string.channel_comment_not_found); seeking = 0; }
                            saved.set("seeking", seeking);
                        }
                        pageLoaded();
                    });
            read("tags", Route.COMMENT_TAGS, query("channel_id", id), data -> tags = array(data), null);
        } else if (tab == 3) {
            read("page", Route.SUBSCRIBERS, query("channel_id", id, "page", requested, "per_page", 20),
                    this::acceptPage, this::pageLoaded);
        } else if (tab == 4 && manager()) {
            collaborators = new JsonArray(); blacklist = new JsonArray();
            read("collaborators", Route.COLLABORATORS, query("channel_id", id), data -> collaborators = array(data), null);
            read("blacklist", Route.BLACKLIST, query("channel_id", id), data -> blacklist = array(data), null);
        } else if (tab == 5) {
            ballots = new JsonArray();
            read("page", Route.BALLOTS, query("channel_id", id), data -> ballots = array(data), null);
            if (selectedBallotId > 0) loadBallot(selectedBallotId);
        }
        changed();
    }
    private void acceptPage(JsonElement value) {
        JsonObject data = obj(value);
        if (!data.has("items")) throw new IllegalStateException();
        pageData = data; total = num(data, "total");
        if (total <= 0 && rows().size() < 20) lastPage = rows().size() == 0 && page > 1 ? page - 1 : page;
    }
    private void pageLoaded() {
        if (!errors.containsKey("page") && (tab == 1 || tab == 2 || tab == 3)) {
            int last = total > 0 ? pages() : rows().size() == 0 && page > 1 ? page - 1 : page;
            if (page > last) { load(last); return; }
        }
        changed();
    }

    public void mutate(String key, Route route, JsonObject body) {
        if (!ready() || deleted() && route != Route.RECOVER) return;
        if (EnumSet.of(Route.UPDATE_CHANNEL, Route.CREATE_FOLDER, Route.UPDATE_FOLDER, Route.DELETE_FOLDER,
                Route.INVITE, Route.REMOVE_COLLABORATOR, Route.BLOCK, Route.LOCK, Route.TOGGLE_BALLOT,
                Route.RECOVER).contains(route) && !manager()) return;
        post(key, route, body, value -> { Community.changed(); refreshChannel(); });
    }
    public void react(boolean favorite, int operation) {
        mutate(favorite ? "favorite" : "vote:" + operation, favorite ? Route.FAVORITE : Route.CHANNEL_VOTE,
                favorite ? object("channel_id", id) : object("channel_id", id, "operation", operation));
    }
    public void invite(String action) {
        mutate("invite:" + action, Route.INVITE_ACTION, object("channel_id", id, "action", action));
    }
    public void resolveMember(String username, boolean block) {
        if (busy() || !manager() || deleted()) return;
        mutationSucceeded = false; action = block ? "block_user" : "invite_user";
        read("user_lookup", Route.USER_HEADER, query("username", username), value -> {
            int userId = num(obj(value), "user_id");
            if (userId <= 0) throw new IllegalStateException();
            action = "";
            mutate(block ? "block_user" : "invite_user", block ? Route.BLOCK : Route.INVITE,
                    object("channel_id", id, "user_id", userId));
        }, null);
    }
    public void work(Route route, int workId, Integer target, Boolean pin) {
        if (workId <= 0 || deleted()) return;
        if (route == Route.PIN_WORK && !manager()) return;
        if (route == Route.ADD_WORK && !canAdd() && !manager()) return;
        mutate(route.name() + ":" + workId, route,
                object("channel_id", id, "work_id", workId, "folder_id", target != null && target > 0 ? target : null, "pin", pin));
    }
    public int commentVote(int commentId) {
        if (!signedIn()) return 0;
        for (JsonElement value : array(obj(pageData, "viewer"), "operations"))
            if (num(obj(value), "comment_id") == commentId) return num(obj(value), "operation");
        return 0;
    }
    public void voteComment(int commentId, int operation) {
        mutate("comment:" + commentId + ":" + operation, Route.COMMENT_VOTE, object("comment_id", commentId, "operation", operation));
    }
    public void reply(JsonObject comment) {
        if (!canCompose()) return;
        restoreComposer();
        draft("reply", Integer.toString(num(comment, "id"))); draft("reply_name", str(obj(comment, "user"), "username")); changed();
    }
    public void editComment(JsonObject comment) {
        if (num(comment, "user_id") != userId() || !canCompose()) return;
        if (draft("editing").isEmpty()) {
            draft("original_draft", draft("comment")); draft("original_tags", draft("comment_tags"));
        }
        draft("editing", Integer.toString(num(comment, "id"))); draft("reply", ""); draft("reply_name", "");
        draft("comment", str(comment, "content")); draft("comment_tags", joinTags(array(comment, "tags"))); changed();
    }
    private void restoreComposer() {
        if (!draft("editing").isEmpty()) {
            draft("comment", draft("original_draft")); draft("comment_tags", draft("original_tags"));
        }
        draft("editing", ""); draft("reply", ""); draft("reply_name", "");
        draft("original_draft", ""); draft("original_tags", "");
    }
    public void cancelContext() { if (!busy()) { restoreComposer(); changed(); } }
    public void sendComment() {
        if (!canCompose() || busy() || draft("comment").trim().isEmpty()) return;
        int editing = number(draft("editing")), reply = number(draft("reply"));
        JsonArray commentTags = new JsonArray(); Set<String> unique = new LinkedHashSet<>();
        for (String tag : draft("comment_tags").split("[,，]")) if (!tag.trim().isEmpty()) unique.add(tag.trim());
        for (String tag : unique) commentTags.add(tag);
        JsonObject body = editing > 0 ? object("comment_id", editing, "content", draft("comment"), "tags", commentTags)
                : object("channel_id", id, "content", draft("comment"), "reply_comment_id", reply > 0 ? reply : null, "tags", commentTags);
        post("send", editing > 0 ? Route.UPDATE_COMMENT : Route.CREATE_COMMENT, body, value -> {
            if (editing > 0) restoreComposer();
            else {
                draft("comment", ""); draft("comment_tags", ""); restoreComposer();
                commentFilter = ""; draft("filter", ""); seeking = num(obj(value), "id"); page = 1;
            }
            Community.changed(); refreshChannel();
        });
    }
    public void history(int commentId) {
        if (busy()) return;
        history = null; historyTitle = Integer.toString(commentId);
        read("history", Route.COMMENT_HISTORY, query("comment_id", commentId), data -> history = array(data), null);
    }
    public void locate(int commentId) {
        if (commentId <= 0 || busy()) return;
        tab = 2; saved.set("tab", tab); seeking = commentId; saved.set("seeking", seeking); focusComment = 0;
        commentFilter = ""; draft("filter", ""); total = lastPage = 0; load(1);
    }
    public boolean locatingComment() { return seeking > 0 && loading("page"); }
    public void openBallot(int ballotId) {
        if (busy() || ballotId <= 0) return;
        selectedBallotId = ballotId; saved.set("ballot_id", ballotId); tab = 5; saved.set("tab", tab);
        load(1);
    }
    private void loadBallot(int ballotId) {
        ballotDetail = new JsonObject(); ballotStats = new JsonObject();
        read("ballot", Route.BALLOT, query("channel_id", id, "ballot_id", ballotId), value -> ballotDetail = obj(value), null);
        read("statistics", Route.BALLOT_STATS, query("channel_id", id, "ballot_id", ballotId), value -> ballotStats = obj(value), null);
    }
    public void closeBallotDetails() {
        if (busy()) return;
        selectedBallotId = 0; saved.set("ballot_id", 0); cancel("ballot", "statistics"); changed();
    }
    public boolean hasBallotVote(JsonObject detail, int workId) {
        if (!signedIn()) return false;
        for (JsonElement value : array(detail, "user_votes")) if (num(obj(value), "work_id") == workId) return true;
        return false;
    }
    public boolean canVote(JsonObject detail, int workId) {
        if (!ready() || deleted() || !"active".equals(str(detail, "status")) || hasBallotVote(detail, workId)) return false;
        if (array(detail, "user_votes").size() >= num(obj(obj(detail, "ballot"), "config"), "max_votes_per_user")) return false;
        for (JsonElement value : array(detail, "eligible_works")) if (num(obj(value), "work_id") == workId) return true;
        return false;
    }
    public void cast(JsonObject detail, int workId) {
        if (!canVote(detail, workId)) return;
        mutate("cast:" + workId, Route.CAST_VOTE,
                object("channel_id", id, "folder_id", num(obj(detail, "ballot"), "folder_id"), "work_id", workId));
    }
    public boolean inviteExpired() {
        try { return Instant.parse(str(obj(obj(channel, "viewer"), "collaborator"), "pending_until")).toEpochMilli() <= System.currentTimeMillis() + serverOffset; }
        catch (RuntimeException failure) { return true; }
    }
    public static int number(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException failure) { return 0; } }
    public static String joinTags(JsonArray tags) {
        List<String> names = new ArrayList<>();
        for (JsonElement tag : tags) if (tag.isJsonPrimitive()) names.add(tag.getAsString());
        return android.text.TextUtils.join(", ", names);
    }
}
