package com.fimtale.community;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.fimtale.R;
import com.fimtale.model.UserDetailResponse;
import com.fimtale.model.BlockedUser;
import com.fimtale.model.BlockUserRequest;
import java.util.List;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.notifications.Inbox;
import com.fimtale.utils.UserPreferences;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Keeps social mutations alive across configuration changes without repeating a toggle. */
public final class ProfileSocialModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public final MutableLiveData<UserDetailResponse> header = new MutableLiveData<>();
    public boolean following, messaging;
    public boolean blocked, blockKnown, blocking, blockChanging;
    private int targetId;
    public String error = "";
    private Call<?> followCall, messageCall;
    private Call<?> blockCall;
    private Inbox.Conversation conversation;
    private String session;
    private boolean cleared;
    public ProfileSocialModel(@NonNull Application app) { super(app); session = UserPreferences.getToken(app); }
    public void syncSession() {
        String token = UserPreferences.getToken(getApplication());
        if (token.equals(session)) return;
        session = token;
        if (followCall != null) followCall.cancel(); if (messageCall != null) messageCall.cancel();
        if (blockCall != null) blockCall.cancel();
        blocked = blockKnown = blocking = blockChanging = false;
        following = messaging = false; conversation = null; error = ""; header.setValue(null); update();
        if (targetId > 0) loadBlocked();
    }
    private void update() { if (!cleared) changes.setValue(changes.getValue() + 1); }
    private boolean valid(String token, Call<?> request) {
        if (cleared || request.isCanceled()) return false;
        if (!token.equals(UserPreferences.getToken(getApplication()))) {
            syncSession(); error = getApplication().getString(R.string.community_account_changed); update(); return false;
        }
        return true;
    }
    public void follow(int id, String username) {
        syncSession(); if (following || session.isEmpty()) return;
        String token = session; following = true; error = ""; update();
        Call<Void> call = RetrofitClient.getInstance().followUser(token, new Community.UserId(id)); followCall = call;
        call.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> request, Response<Void> response) {
                if (!valid(token, request)) return;
                if (response.isSuccessful()) { Community.changed(); refreshHeader(token, username); }
                else { following = false; error = ApiErrors.message(response); update(); }
            }
            @Override public void onFailure(Call<Void> request, Throwable failure) {
                if (!valid(token, request)) return;
                Community.changed();
                error = getApplication().getString(R.string.community_operation_uncertain); refreshHeader(token, username);
            }
        });
    }
    private void refreshHeader(String token, String username) {
        Call<UserDetailResponse> call = RetrofitClient.getInstance().getUserHeader(token, username); followCall = call;
        call.enqueue(new Callback<UserDetailResponse>() {
            @Override public void onResponse(Call<UserDetailResponse> request, Response<UserDetailResponse> response) {
                if (!valid(token, request)) return;
                following = false;
                if (response.isSuccessful() && response.body() != null) header.setValue(response.body());
                else error = getApplication().getString(R.string.community_operation_uncertain);
                update();
            }
            @Override public void onFailure(Call<UserDetailResponse> request, Throwable failure) {
                if (!valid(token, request)) return;
                following = false; error = getApplication().getString(R.string.community_operation_uncertain); update();
            }
        });
    }
    public void message(int id) {
        syncSession(); if (messaging || session.isEmpty()) return;
        String token = session; messaging = true; error = ""; update();
        Call<Inbox.Conversation> call = RetrofitClient.getInstance().createConversation(token, new Community.ConversationRequest(id)); messageCall = call;
        call.enqueue(new Callback<Inbox.Conversation>() {
            @Override public void onResponse(Call<Inbox.Conversation> request, Response<Inbox.Conversation> response) {
                if (!valid(token, request)) return;
                messaging = false;
                if (response.isSuccessful() && response.body() != null && response.body().id > 0) conversation = response.body();
                else error = ApiErrors.message(response);
                update();
            }
            @Override public void onFailure(Call<Inbox.Conversation> request, Throwable failure) {
                if (!valid(token, request)) return;
                messaging = false; error = getApplication().getString(R.string.community_operation_uncertain); update();
            }
        });
    }
    public Inbox.Conversation takeConversation() { Inbox.Conversation result = conversation; conversation = null; return result; }
    public void target(int id) { if (targetId == id && (blockKnown || blocking)) return; targetId = id; loadBlocked(); }
    public void loadBlocked() { blockRequest(false); }
    public void toggleBlock() { if (blockKnown) blockRequest(true); }
    private void blockRequest(boolean toggle) {
        syncSession(); if (blocking || targetId <= 0 || session.isEmpty()) return;
        if (toggle) blockChanging = true;
        String token = session; blocking = true; update();
        Call<List<BlockedUser>> call = RetrofitClient.getInstance().updateBlocklist(token, toggle ? new BlockUserRequest(targetId) : new BlockUserRequest()); blockCall = call;
        call.enqueue(new Callback<List<BlockedUser>>() {
            @Override public void onResponse(Call<List<BlockedUser>> request, Response<List<BlockedUser>> response) {
                if (!valid(token, request)) return;
                blocking = blockChanging = false;
                if (response.isSuccessful()) {
                    blocked = false; blockKnown = true;
                    if (response.body() != null) for (BlockedUser user : response.body()) if (user.userId == targetId) blocked = true;
                    if (toggle) Community.changed();
                } else { blockKnown = false; error = ApiErrors.message(response); }
                update();
            }
            @Override public void onFailure(Call<List<BlockedUser>> request, Throwable failure) {
                if (!valid(token, request)) return;
                blocking = false; blockKnown = false;
                if (!toggle) blockChanging = false;
                error = getApplication().getString(toggle ? R.string.community_operation_uncertain : R.string.community_load_failed); update();
                if (toggle) loadBlocked();
            }
        });
    }
    @Override protected void onCleared() { cleared = true; if (followCall != null) followCall.cancel(); if (messageCall != null) messageCall.cancel(); if (blockCall != null) blockCall.cancel(); }
}
