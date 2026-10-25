package com.fimtale.community;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.fimtale.R;
import com.fimtale.model.*;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.*;
import java.util.function.Consumer;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Shared paged state for profile content and favorites; mutations survive rotation. */
public final class CommunityListModel extends AndroidViewModel {
    public static final int PER_PAGE = 20;
    public enum Kind { WORK, COMMENT, CHANNEL, TAG, USER, ACTIVITY }
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public final List<Topic> works = new ArrayList<>();
    public final List<Comment> comments = new ArrayList<>();
    public final List<Community.Channel> channels = new ArrayList<>();
    public final List<Community.Tag> tags = new ArrayList<>();
    public final List<AuthorInfo> users = new ArrayList<>();
    public final List<TimelineItem> activity = new ArrayList<>();
    public final List<WorkInteractions.Folder> folders = new ArrayList<>();
    public final List<Community.Tag> followed = new ArrayList<>();
    public final Set<Integer> selectedTags = new LinkedHashSet<>();
    public boolean tagFeed;
    private boolean scopeLoaded;
    public final Set<String> reposting = new HashSet<>();
    public boolean favorites, loading, foldersLoading, mutating, initialized;
    private boolean reconciling;
    private String mutationKey = "";
    public int tab, page = 1, totalPages = 1, failedPage = 1, dataVersion, folderVersion;
    public Integer folderId;
    public String username, error = "", notice = "";
    private String session;
    private Call<?> dataCall, folderCall, mutationCall;
    private final List<Call<?>> repostCalls = new ArrayList<>();
    private boolean cleared;
    private final Gson gson = new Gson();
    private long communityVersion = Community.version();
    public CommunityListModel(@NonNull Application app) { super(app); session = UserPreferences.getToken(app); }

    public void init(boolean favorites, String username, int tab, Integer folderId) {
        if (initialized) return;
        initialized = true; this.favorites = favorites; this.username = username;
        this.tab = Math.max(0, Math.min(tab, favorites ? 3 : 6)); this.folderId = folderId;
        load(1); if (favorites) loadFolders();
    }
    public void initTagFeed(int tab) {
        if (initialized) return;
        initialized = tagFeed = true; this.tab = Math.max(0, Math.min(1, tab)); load(1);
    }
    public Kind kind() {
        if (tagFeed) return Kind.WORK;
        if (favorites) return new Kind[]{Kind.WORK, Kind.COMMENT, Kind.CHANNEL, Kind.TAG}[tab];
        return new Kind[]{Kind.ACTIVITY, Kind.WORK, Kind.WORK, Kind.COMMENT, Kind.CHANNEL, Kind.USER, Kind.USER}[tab];
    }
    public String token() { return session; }
    public boolean pending(String key) { return mutating && mutationKey.equals(key); }
    private void finishReconciliation() {
        if (reconciling && !loading && !foldersLoading) {
            reconciling = mutating = false; mutationKey = "";
        }
    }
    public int size() {
        switch (kind()) {
            case WORK: return works.size(); case COMMENT: return comments.size(); case CHANNEL: return channels.size();
            case TAG: return tags.size(); case USER: return users.size(); default: return activity.size();
        }
    }
    private void update() { if (!cleared) changes.setValue(changes.getValue() + 1); }
    private boolean valid(String token, Call<?> call) {
        if (cleared || call.isCanceled()) return false;
        if (!token.equals(UserPreferences.getToken(getApplication()))) { resume(); return false; }
        return true;
    }
    private void clearData() {
        works.clear(); comments.clear(); channels.clear(); tags.clear(); users.clear(); activity.clear();
        dataVersion++; page = totalPages = 1; error = "";
    }
    public void resume() {
        String token = UserPreferences.getToken(getApplication());
        if (!session.equals(token)) {
            if (dataCall != null) dataCall.cancel(); if (folderCall != null) folderCall.cancel(); if (mutationCall != null) mutationCall.cancel();
            for (Call<?> call : repostCalls) call.cancel(); repostCalls.clear(); reposting.clear();
            session = token; mutating = foldersLoading = loading = false;
            reconciling = false; mutationKey = "";
            folders.clear(); followed.clear(); selectedTags.clear(); scopeLoaded = false; folderId = null; folderVersion++; clearData();
            if (initialized) { load(1); if (favorites) loadFolders(); }
        } else if (communityVersion != Community.version() && initialized && !mutating) {
            if (tagFeed) scopeLoaded = false;
            communityVersion = Community.version(); load(page); if (favorites) loadFolders();
        }
        update();
    }
    public void select(int requestedTab) {
        if (tab == requestedTab) return;
        tab = requestedTab; clearData(); load(1);
    }
    public void folder(Integer id) {
        if (Objects.equals(folderId, id)) return;
        folderId = id; clearData(); load(1);
    }
    public void refresh() { if (tagFeed) scopeLoaded = false; load(1); if (favorites) loadFolders(); }
    public void scope(Set<Integer> ids) { selectedTags.clear(); selectedTags.addAll(ids); clearData(); load(1); }
    public void load(int requestedPage) {
        if (!initialized || cleared || requestedPage < 1) return;
        if (dataCall != null) dataCall.cancel();
        if ((favorites || tagFeed) && session.isEmpty()) { clearData(); loading = false; update(); return; }
        if (tagFeed && !scopeLoaded) { followed.clear(); readScope(1); return; }
        loading = true; error = ""; failedPage = requestedPage; update();
        if (tagFeed) {
            List<String> names = new ArrayList<>();
            for (Community.Tag tag : followed) if (selectedTags.isEmpty() || selectedTags.contains(tag.id)) names.add(tag.name);
            if (names.isEmpty()) { clearData(); loading = false; update(); return; }
            fetch(RetrofitClient.getInstance().getTopicList(requestedPage, com.fimtale.network.SearchQuery.tags(names, tab == 1), null), requestedPage, result -> {
                works.clear(); works.addAll(result.getTopicArray()); totalPages = pages(result.getTotal());
            });
        } else if (!favorites) {
            fetch(RetrofitClient.getInstance().getUserTab(session, username, UserContentActivity.TABS[tab], requestedPage, PER_PAGE), requestedPage,
                    result -> acceptUser(result, requestedPage));
        } else switch (kind()) {
            case WORK:
                fetch(RetrofitClient.getInstance().getFavoriteWorks(session, folderId, requestedPage, PER_PAGE), requestedPage, result -> {
                    works.clear(); works.addAll(result.getTopicArray()); totalPages = pages(result.getTotal());
                }); break;
            case COMMENT:
                fetch(RetrofitClient.getInstance().getFavoriteComments(session, requestedPage, PER_PAGE), requestedPage, result -> {
                    comments.clear(); comments.addAll(result.items()); for (Comment comment : comments) comment.favorited = true;
                    totalPages = pages(result.total);
                }); break;
            case CHANNEL:
                fetch(RetrofitClient.getInstance().getFavoriteChannels(session), requestedPage, result -> {
                    channels.clear(); if (result != null) channels.addAll(result); totalPages = 1;
                }); break;
            case TAG:
                fetch(RetrofitClient.getInstance().getFavoriteTags(session, requestedPage, PER_PAGE), requestedPage, result -> {
                    tags.clear(); if (result.items != null) tags.addAll(result.items); totalPages = pages(result.total);
                }); break;
            default: break;
        }
    }
    private void readScope(int requestedPage) {
        String token = session; loading = true; error = ""; update();
        Call<Community.TagPage> call = RetrofitClient.getInstance().getFavoriteTags(token, requestedPage, 50); dataCall = call;
        call.enqueue(new Callback<Community.TagPage>() {
            @Override public void onResponse(Call<Community.TagPage> request, Response<Community.TagPage> response) {
                if (request != dataCall || !valid(token, request)) return;
                Community.TagPage result = response.body();
                if (!response.isSuccessful() || result == null) { loading = false; error = ApiErrors.message(response); update(); return; }
                Set<Integer> known = new HashSet<>(); for (Community.Tag tag : followed) known.add(tag.id);
                if (result.items != null) for (Community.Tag tag : result.items) if (known.add(tag.id)) followed.add(tag);
                if (requestedPage * 50 < result.total && result.items != null && !result.items.isEmpty()) { readScope(requestedPage + 1); return; }
                selectedTags.retainAll(known); scopeLoaded = true; communityVersion = Community.version(); load(1);
            }
            @Override public void onFailure(Call<Community.TagPage> request, Throwable failure) {
                if (request != dataCall || !valid(token, request)) return;
                loading = false; error = getApplication().getString(R.string.community_load_failed); update();
            }
        });
    }
    private static int pages(int total) { return Math.max(1, (total + PER_PAGE - 1) / PER_PAGE); }
    private void acceptUser(Community.UserTab result, int requestedPage) {
        JsonElement content = result.content;
        if (content == null || content.isJsonNull()) { clearData(); totalPages = 1; return; }
        switch (kind()) {
            case WORK:
                TopicListResponse topics = gson.fromJson(content, TopicListResponse.class);
                works.clear(); works.addAll(topics.getTopicArray()); totalPages = pages(topics.getTotal()); break;
            case COMMENT:
                comments.clear(); comments.addAll(Arrays.asList(gson.fromJson(content, Comment[].class))); totalPages = pages(result.count); break;
            case CHANNEL:
                channels.clear(); channels.addAll(Arrays.asList(gson.fromJson(content, Community.Channel[].class))); totalPages = pages(result.count); break;
            case USER:
                users.clear(); users.addAll(Arrays.asList(gson.fromJson(content, AuthorInfo[].class))); totalPages = pages(result.count); break;
            case ACTIVITY:
                activity.clear(); activity.addAll(Arrays.asList(gson.fromJson(content, TimelineItem[].class)));
                totalPages = result.count > 0 ? pages(result.count) : requestedPage + (activity.size() >= PER_PAGE ? 1 : 0); break;
            default: break;
        }
    }
    private <T> void fetch(Call<T> call, int requestedPage, Consumer<T> accept) {
        String token = session; dataCall = call;
        call.enqueue(new Callback<T>() {
            @Override public void onResponse(Call<T> request, Response<T> response) {
                if (request != dataCall || !valid(token, request)) return;
                loading = false;
                if (response.isSuccessful() && (response.body() != null || favorites && kind() == Kind.CHANNEL)) {
                    try { accept.accept(response.body()); }
                    catch (RuntimeException invalidPayload) { error = getApplication().getString(R.string.community_load_failed); finishReconciliation(); update(); return; }
                    if (requestedPage > totalPages) { load(totalPages); return; }
                    page = requestedPage; dataVersion++; communityVersion = Community.version();
                } else error = ApiErrors.message(response);
                finishReconciliation(); update();
            }
            @Override public void onFailure(Call<T> request, Throwable failure) {
                if (request != dataCall || !valid(token, request)) return;
                loading = false; error = getApplication().getString(R.string.community_load_failed); finishReconciliation(); update();
            }
        });
    }
    public void loadFolders() {
        if (!favorites || session.isEmpty() || cleared) return;
        if (folderCall != null) folderCall.cancel();
        String token = session; foldersLoading = true; update();
        Call<List<WorkInteractions.Folder>> call = RetrofitClient.getInstance().getFavoriteFolders(token); folderCall = call;
        call.enqueue(new Callback<List<WorkInteractions.Folder>>() {
            @Override public void onResponse(Call<List<WorkInteractions.Folder>> request, Response<List<WorkInteractions.Folder>> response) {
                if (request != folderCall || !valid(token, request)) return;
                foldersLoading = false;
                if (response.isSuccessful()) {
                    folders.clear(); if (response.body() != null) folders.addAll(response.body()); folderVersion++;
                    if (folderId != null && folders.stream().noneMatch(f -> f.id == folderId)) folder(null);
                } else notice = ApiErrors.message(response);
                finishReconciliation(); update();
            }
            @Override public void onFailure(Call<List<WorkInteractions.Folder>> request, Throwable failure) {
                if (request != folderCall || !valid(token, request)) return;
                foldersLoading = false; notice = getApplication().getString(R.string.community_load_failed); finishReconciliation(); update();
            }
        });
    }
    public void saveFolder(Integer id, String name) {
        if (name.trim().isEmpty() || name.length() > 191) return;
        Community.FolderRequest request = new Community.FolderRequest(id, name.trim());
        mutate(id == null ? RetrofitClient.getInstance().createFavoriteFolder(session, request)
                : RetrofitClient.getInstance().updateFavoriteFolder(session, request), "folders");
    }
    public void deleteFolder(int id) { mutate(RetrofitClient.getInstance().deleteFavoriteFolder(session, new Community.FolderRequest(id, null)), "folders"); }
    public void removeChannel(int id) { mutate(RetrofitClient.getInstance().favoriteChannel(session, new Community.ChannelId(id)), "channel:" + id); }
    public void removeTag(int id) { mutate(RetrofitClient.getInstance().favoriteTag(session, new Community.TagId(id)), "tag:" + id); }
    private <T> void mutate(Call<T> call, String key) {
        if (mutating || session.isEmpty()) return;
        String token = session; mutating = true; mutationKey = key; notice = ""; update(); mutationCall = call;
        call.enqueue(new Callback<T>() {
            @Override public void onResponse(Call<T> request, Response<T> response) {
                if (!valid(token, request)) return;
                if (response.isSuccessful()) { Community.changed(); notice = getApplication().getString(R.string.community_changed); }
                else notice = ApiErrors.message(response);
                completeMutation();
            }
            @Override public void onFailure(Call<T> request, Throwable failure) {
                if (!valid(token, request)) return;
                Community.changed();
                notice = getApplication().getString(R.string.community_operation_uncertain); completeMutation();
            }
        });
    }
    private void completeMutation() { reconciling = true; load(page); if (favorites) loadFolders(); finishReconciliation(); update(); }
    public void assignWork(int workId, Set<Integer> before, Set<Integer> selected) {
        if (mutating || session.isEmpty()) return;
        List<WorkInteractions.Change> changes = WorkInteractions.changes(before, selected);
        if (changes.isEmpty()) return;
        mutating = true; mutationKey = "work:" + workId; update(); assignNext(session, workId, changes, 0);
    }
    private void assignNext(String token, int id, List<WorkInteractions.Change> changes, int index) {
        if (index == changes.size()) { Community.changed(); notice = getApplication().getString(R.string.community_changed); completeMutation(); return; }
        WorkInteractions.Change change = changes.get(index);
        WorkInteractions.FavoriteRequest body = new WorkInteractions.FavoriteRequest(id, change.folderId);
        Call<Void> call = change.add ? RetrofitClient.getInstance().addFavoriteWork(token, body) : RetrofitClient.getInstance().removeFavoriteWork(token, body);
        mutationCall = call;
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> request, Response<Void> response) {
                if (!valid(token, request)) return;
                if (response.isSuccessful()) { Community.changed(); assignNext(token, id, changes, index + 1); }
                else { notice = ApiErrors.message(response); completeMutation(); }
            }
            @Override public void onFailure(Call<Void> request, Throwable failure) {
                if (!valid(token, request)) return;
                Community.changed();
                notice = getApplication().getString(R.string.community_operation_uncertain); completeMutation();
            }
        });
    }
    public void repost(TimelineItem item) {
        if (session.isEmpty() || !item.canHighlight() || !reposting.add(item.key())) return;
        String token = session; update();
        TimelineItem.Highlight body = new TimelineItem.Highlight(item.entity.id);
        Call<Void> call = item.type == TimelineItem.CHANNEL_COMMENT ? RetrofitClient.getInstance().highlightChannelComment(token, body)
                : RetrofitClient.getInstance().highlightWorkComment(token, body); repostCalls.add(call);
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> request, Response<Void> response) {
                if (!valid(token, request)) return;
                reposting.remove(item.key()); notice = response.isSuccessful() ? getApplication().getString(R.string.community_changed) : ApiErrors.message(response); update();
            }
            @Override public void onFailure(Call<Void> request, Throwable failure) {
                if (!valid(token, request)) return;
                reposting.remove(item.key()); notice = getApplication().getString(R.string.community_operation_uncertain); update();
            }
        });
    }
    @Override protected void onCleared() {
        cleared = true;
        if (dataCall != null) dataCall.cancel(); if (folderCall != null) folderCall.cancel(); if (mutationCall != null) mutationCall.cancel();
        for (Call<?> call : repostCalls) call.cancel();
    }
}
