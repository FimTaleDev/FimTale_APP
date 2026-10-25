package com.fimtale.utils;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UserPreferences {
    private static final String PREF_NAME = "fimtale_prefs";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_SEARCH_HISTORY = "search_history";
    private static final String KEY_MAX_CACHE_SIZE = "max_cache_size_bytes";
    private static final int MAX_SEARCH_HISTORY = 10;
    private static final long DEFAULT_MAX_CACHE_SIZE = 100L * 1024 * 1024; // 100 MB

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences session(Context context) {
        return context.getSharedPreferences("fimtale_session", Context.MODE_PRIVATE);
    }
    /** Publish a complete, verified session in one preferences transaction. */
    public static void saveSession(Context context, String token, com.fimtale.model.CurrentUser user) {
        session(context).edit().putString("token", token)
                .putString(KEY_USER_ID, String.valueOf(user.id)).putString(KEY_USER_NAME, user.username)
                .putString("avatar", user.getAvatar()).apply();
    }
    public static void saveToken(Context context, String token) {
        session(context).edit().putString("token", token).apply();
    }
    public static String getToken(Context context) {
        return session(context).getString("token", "");
    }
    public static void saveAvatar(Context context, String avatar) {
        session(context).edit().putString("avatar", avatar).apply();
    }
    public static String getAvatar(Context context) {
        return session(context).getString("avatar", "");
    }
    public static void clearSession(Context context) {
        session(context).edit().clear().apply();
        context.getSharedPreferences("fimtale_exam", Context.MODE_PRIVATE).edit().clear().apply();
        android.webkit.CookieManager.getInstance().setCookie(
                com.fimtale.network.SiteUrls.SITE, "ft_token=; Path=/; Max-Age=0; Secure");
        android.webkit.CookieManager.getInstance().flush();
    }
    public static boolean isLoggedIn(Context context) { return !getToken(context).isEmpty(); }

    public static void setAutoUpdate(Context context, boolean autoUpdate) {
        getPrefs(context).edit()
                .putBoolean("auto_update", autoUpdate)
                .apply();
    }

    public static boolean isAutoUpdateEnabled(Context context) {
        return getPrefs(context).getBoolean("auto_update", true);
    }

    public static void setShowReaderProgress(Context context, boolean show) {
        getPrefs(context).edit()
                .putBoolean("show_reader_progress", show)
                .apply();
    }

    public static boolean isShowReaderProgress(Context context) {
        return getPrefs(context).getBoolean("show_reader_progress", false);
    }

    public static void setLineSpacing(Context context, float spacing) {
        getPrefs(context).edit()
                .putFloat("reader_line_spacing", spacing)
                .apply();
    }

    public static float getLineSpacing(Context context) {
        return getPrefs(context).getFloat("reader_line_spacing", 1.4f);
    }

    public static void setReaderTheme(Context context, int theme) {
        getPrefs(context).edit()
                .putInt("reader_theme_preset", theme)
                .apply();
    }

    public static int getReaderTheme(Context context) {
        return getPrefs(context).getInt("reader_theme_preset", 0);
    }

    public static void saveUserId(Context context, String userId) {
        session(context).edit()
                .putString(KEY_USER_ID, userId)
                .apply();
    }

    public static String getUserId(Context context) {
        return session(context).getString(KEY_USER_ID, "");
    }

    public static void saveUserName(Context context, String userName) {
        session(context).edit()
                .putString(KEY_USER_NAME, userName)
                .apply();
    }

    public static String getUserName(Context context) {
        return session(context).getString(KEY_USER_NAME, "");
    }

    public static void saveSearchHistory(Context context, String query) {
        if (query == null || query.trim().isEmpty()) return;
        query = query.trim();
        
        List<String> history = getSearchHistory(context);
        history.remove(query);
        history.add(0, query);
        
        if (history.size() > MAX_SEARCH_HISTORY) {
            history = history.subList(0, MAX_SEARCH_HISTORY);
        }
        
        saveSearchHistoryList(context, history);
    }

    public static List<String> getSearchHistory(Context context) {
        String historyStr = getPrefs(context).getString(KEY_SEARCH_HISTORY, "");
        if (historyStr.isEmpty()) {
            return new ArrayList<>();
        }
        if (historyStr.startsWith("[")) {
            try {
                JSONArray values = new JSONArray(historyStr);
                List<String> history = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) {
                    String value = values.optString(i, "").trim();
                    if (!value.isEmpty()) history.add(value);
                }
                return history;
            } catch (JSONException ignored) {
                // Fall through to the legacy comma-delimited format.
            }
        }
        return new ArrayList<>(Arrays.asList(historyStr.split(",", -1)));
    }

    public static void clearSearchHistory(Context context) {
        getPrefs(context).edit().remove(KEY_SEARCH_HISTORY).apply();
    }

    public static void removeSearchHistoryItem(Context context, String query) {
        List<String> history = getSearchHistory(context);
        history.remove(query);
        saveSearchHistoryList(context, history);
    }

    private static void saveSearchHistoryList(Context context, List<String> history) {
        JSONArray values = new JSONArray();
        for (String value : history) values.put(value);
        getPrefs(context).edit().putString(KEY_SEARCH_HISTORY, values.toString()).apply();
    }

    public static long getMaxCacheSize(Context context) {
        return getPrefs(context).getLong(KEY_MAX_CACHE_SIZE, DEFAULT_MAX_CACHE_SIZE);
    }

    public static void setMaxCacheSize(Context context, long bytes) {
        getPrefs(context).edit().putLong(KEY_MAX_CACHE_SIZE, bytes).apply();
    }
}
