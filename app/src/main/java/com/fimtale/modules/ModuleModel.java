package com.fimtale.modules;

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import com.fimtale.R;
import com.fimtale.network.ApiErrors;
import com.fimtale.utils.UserPreferences;
import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import retrofit2.*;

/** Retains requests across rotation, isolates account data, and never automatically repeats a POST. */
public abstract class ModuleModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public final Map<String, String> errors = new LinkedHashMap<>();
    protected final SavedStateHandle saved;
    protected String token;
    private final Map<String, Call<?>> requests = new HashMap<>();
    private boolean externalAction;
    public String action = "", notice = "";
    public boolean started, mutationSucceeded;
    public int sessionRevision;
    protected long serverOffset;
    protected ModuleModel(Application app, SavedStateHandle state) {
        super(app); saved = state; token = UserPreferences.getToken(app);
        String owner = state.get("owner");
        if (owner != null && !owner.equals(UserPreferences.getUserId(app))) for (String key : new HashSet<>(state.keys())) state.remove(key);
        state.set("owner", UserPreferences.getUserId(app));
        if (Boolean.TRUE.equals(saved.get("mutation_pending"))) notice = app.getString(R.string.modules_uncertain);
        saved.set("mutation_pending", false);
    }
    public boolean connect() {
        String next = UserPreferences.getToken(getApplication());
        if (next.equals(token)) return false;
        for (Call<?> call : requests.values()) call.cancel(); requests.clear();
        token = next; action = ""; externalAction = false; notice = ""; errors.clear(); mutationSucceeded = false; sessionRevision++;
        for (String key : new HashSet<>(saved.keys())) saved.remove(key);
        saved.set("owner", UserPreferences.getUserId(getApplication()));
        reset(); changed(); return true;
    }
    protected abstract void reset();
    public boolean signedIn() { return !token.isEmpty() && sameSession(token); }
    protected boolean requiresLogin() { return true; }
    public boolean busy() { return !action.isEmpty(); }
    public boolean loading(String key) { return requests.containsKey(key); }
    public boolean loading() { return !requests.isEmpty(); }
    public String session() { return token; }
    public boolean sameSession(String value) { return value.equals(token) && value.equals(UserPreferences.getToken(getApplication())); }
    public void changed() { changes.setValue(changes.getValue() + 1); }
    public void finishAction() { action = ""; externalAction = false; saved.set("mutation_pending", false); changed(); }
    protected void holdAction(String key) { action = key; externalAction = true; changed(); }
    protected void cancel(String... keys) {
        for (String key : keys) {
            Call<?> call = requests.remove(key);
            if (call != null) call.cancel();
            errors.remove(key);
        }
    }
    protected void cancelReads() {
        for (String key : new HashSet<>(requests.keySet())) if (!key.equals("mutation")) cancel(key);
    }
    protected void read(String key, ModuleApi.Route route, Map<String, String> query, Consumer<JsonElement> success, Runnable done) {
        Call<?> old = requests.remove(key); if (old != null) old.cancel();
        perform(key, route, new JsonObject(), query, success, done);
    }
    protected void post(String key, ModuleApi.Route route, JsonObject body, Consumer<JsonElement> accepted) {
        post(key, route, body, Collections.emptyMap(), accepted);
    }
    protected void post(String key, ModuleApi.Route route, JsonObject body, Map<String, String> query, Consumer<JsonElement> accepted) {
        if (busy() || !sameSession(token) || requiresLogin() && !signedIn()) { connect(); return; }
        // A read started before this change must never overwrite its refreshed result.
        cancelReads(); mutationSucceeded = false; action = key; saved.set("mutation_pending", true);
        perform("mutation", route, body.deepCopy(), query, accepted, null);
    }
    private void perform(String key, ModuleApi.Route route, JsonObject body, Map<String, String> query, Consumer<JsonElement> success, Runnable done) {
        String owner = token;
        Call<JsonElement> call = ModuleApi.call(owner, route, body, query);
        requests.put(key, call); errors.remove(key); changed();
        call.enqueue(new Callback<JsonElement>() {
            private boolean valid(Call<JsonElement> c) {
                if (requests.get(key) != c || c.isCanceled()) return false;
                if (!sameSession(owner)) { connect(); return false; }
                requests.remove(key); return true;
            }
            @Override public void onResponse(Call<JsonElement> c, Response<JsonElement> response) {
                if (!valid(c)) { if (response.errorBody() != null) response.errorBody().close(); return; }
                String date = response.headers().get("Date");
                if (date != null) try {
                    serverOffset = java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis();
                } catch (RuntimeException ignored) { }
                if (response.isSuccessful()) {
                    try {
                        if (key.equals("mutation")) { mutationSucceeded = true; saved.set("mutation_pending", false); }
                        success.accept(response.body() == null ? JsonNull.INSTANCE : response.body());
                    } catch (IllegalArgumentException | IllegalStateException failure) {
                        fail(getApplication().getString(key.equals("mutation") ? R.string.modules_uncertain : R.string.error_response_parse));
                    }
                } else fail(ApiErrors.message(response));
                if (done != null) done.run(); settle(); changed();
            }
            private void fail(String message) {
                errors.put(key, message);
                if (key.equals("mutation")) { mutationSucceeded = false; action = ""; saved.set("mutation_pending", false); }
            }
            @Override public void onFailure(Call<JsonElement> c, Throwable failure) {
                if (!valid(c)) return;
                fail(getApplication().getString(key.equals("mutation") ? R.string.modules_uncertain : R.string.community_load_failed));
                if (done != null) done.run(); settle(); changed();
            }
        });
    }
    private void settle() {
        // Also release a mutation whose follow-up read failed or returned no rows.
        if (!externalAction && busy() && requests.isEmpty()) finishAction();
    }
    @Override protected void onCleared() { for (Call<?> c : requests.values()) c.cancel(); requests.clear(); }
}
