package com.fimtale.modules;

import com.fimtale.network.RetrofitClient;
import com.google.gson.*;
import java.util.*;
import retrofit2.Call;

/** Native module contracts, matched to ft-front/schema/openapi.json. Paths are never supplied by a link. */
public final class ModuleApi {
    private ModuleApi() { }
    public enum Route {
        USER("user/get_user"), USER_HEADER("user/get_user_page_header"), AUTH("user/get_user_auth"), BADGES("user/get_badges"),
        PROFILE("user/update_basic_user_info", true), USERNAME("user/change_username", true),
        EMAIL("user/change_email", true), PASSWORD("user/change_password", true),
        SEND_VERIFY("user/send_verify_email", true), VERIFY("user/verify_email"),
        RESET_PASSWORD("user/reset_password", true), SET_BADGES("user/set_user_badges", true),
        BADGE_VISIBILITY("user/set_user_badge_visibility", true),
        START_EXAM("qualify/start_exam", true), SUBMIT_EXAM("qualify/submit_exam", true),
        RECYCLE("work/get_recycle_bin"), RECOVER("work/recover", true), DELETE("work/delete", true),
        CHANNEL_SEARCH("search/search_channels"), FAVORITE_CHANNELS("channel/get_favorite_channels"), CHANNEL("channel/get_channel"),
        CREATE_CHANNEL("channel/create_channel", true), UPDATE_CHANNEL("channel/update_channel", true),
        FAVORITE("channel/favorite_channel", true), CHANNEL_VOTE("channel/do_channel_vote", true),
        INVITE_ACTION("channel/act_on_channel_collaborator_invite", true),
        FOLDERS("channel/get_channel_folders"), CREATE_FOLDER("channel/create_channel_folder", true),
        UPDATE_FOLDER("channel/update_channel_folder", true), DELETE_FOLDER("channel/delete_channel_folder", true),
        WORKS("channel/get_channel_works"), ADD_WORK("channel/add_channel_work", true),
        REMOVE_WORK("channel/remove_channel_work", true), PIN_WORK("channel/pin_channel_work", true),
        COMMENTS("channel/get_channel_comments"), COMMENT_TAGS("channel/get_channel_comment_tags"),
        CREATE_COMMENT("channel/create_channel_comment", true), UPDATE_COMMENT("channel/update_channel_comment", true),
        COMMENT_VOTE("channel/do_channel_comment_vote", true), COMMENT_HISTORY("channel/get_channel_comment_edit_history"),
        SUBSCRIBERS("channel/get_channel_fav_users"), COLLABORATORS("channel/get_channel_collaborators"),
        INVITE("channel/add_collaborator", true), REMOVE_COLLABORATOR("channel/remove_collaborator", true),
        BLACKLIST("channel/get_channel_blacklist"), BLOCK("channel/toggle_channel_blacklist_user", true),
        LOCK("channel/toggle_channel_lock", true), BALLOTS("channel/get_channel_ballots"),
        BALLOT("channel/get_channel_ballot"), BALLOT_STATS("channel/get_channel_ballot_stat"),
        TOGGLE_BALLOT("channel/toggle_channel_ballot", true), CAST_VOTE("channel/cast_channel_ballot_vote", true);
        public final String path;
        public final boolean post;
        Route(String path) { this(path, false); }
        Route(String path, boolean post) { this.path = path; this.post = post; }
    }
    public static Call<JsonElement> call(String token, Route route, JsonObject body, Map<String, String> query) {
        if (route.post) return RetrofitClient.getInstance().modulePost(token, route.path, query, body);
        Map<String,String> params = new LinkedHashMap<>(query); List<String> tags = null;
        String encoded = params.remove("tags");
        if (encoded != null) { tags = new ArrayList<>(); for (JsonElement tag : new JsonParser().parse(encoded).getAsJsonArray()) tags.add(tag.getAsString()); }
        return RetrofitClient.getInstance().moduleGet(token, route.path, params, tags);
    }
    public static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object v = pairs[i + 1]; if (v == null) continue;
            String key = (String) pairs[i];
            if (v instanceof JsonElement) result.add(key, (JsonElement) v);
            else if (v instanceof Boolean) result.addProperty(key, (Boolean) v);
            else if (v instanceof Number) result.addProperty(key, (Number) v);
            else result.addProperty(key, v.toString());
        }
        return result;
    }
    public static Map<String, String> query(Object... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) if (pairs[i + 1] != null) result.put((String) pairs[i], pairs[i + 1].toString());
        return result;
    }
    public static JsonObject obj(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject(); }
    public static JsonObject obj(JsonObject value, String key) { return obj(value.get(key)); }
    public static JsonArray array(JsonElement value) { return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray(); }
    public static JsonArray array(JsonObject value, String key) { return array(value.get(key)); }
    public static String str(JsonObject value, String key) {
        JsonElement v = value.get(key); return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
    }
    public static int num(JsonObject value, String key) {
        try { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsInt() : 0; }
        catch (RuntimeException ignored) { return 0; }
    }
    public static boolean bool(JsonObject value, String key) {
        JsonElement item = value.get(key); return item != null && item.isJsonPrimitive() && item.getAsBoolean();
    }
    public static boolean contains(JsonArray values, int id) {
        for (JsonElement v : values) if (v.isJsonPrimitive() && v.getAsInt() == id) return true;
        return false;
    }
}
