package com.fimtale.community;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.fimtale.R;
import com.fimtale.model.TagInfo;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public final class TagFollowModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public TagInfo info;
    public boolean busy, loading;
    public String error = "";
    private Call<?> activeCall;
    private String session;
    private String currentName;
    private boolean cleared;
    private long version = Community.version();
    public TagFollowModel(@NonNull Application app) { super(app); session = UserPreferences.getToken(app); }
    private void update() { if (!cleared) changes.setValue(changes.getValue() + 1); }
    public void resume(String name) {
        String token = UserPreferences.getToken(getApplication());
        if (!token.equals(session)) {
            if (activeCall != null) activeCall.cancel();
            session = token; busy = loading = false; info = null; load(name);
        } else if (version != Community.version() && !busy) load(name);
    }
    private boolean valid(Call<?> call, String token) {
        if (cleared || call != activeCall || call.isCanceled()) return false;
        if (!token.equals(UserPreferences.getToken(getApplication()))) { resume(currentName); return false; }
        return true;
    }
    public void load(String name) {
        currentName = name;
        if (busy || loading || cleared) return;
        loading = true; update(); String token = session;
        Call<TagInfo> call = RetrofitClient.getInstance().getTagViewer(token, name); activeCall = call;
        call.enqueue(new Callback<TagInfo>() {
            @Override public void onResponse(Call<TagInfo> request, Response<TagInfo> response) {
                if (!valid(request, token)) return;
                loading = false;
                if (response.isSuccessful() && response.body() != null) { info = response.body(); version = Community.version(); }
                else error = ApiErrors.message(response);
                update();
            }
            @Override public void onFailure(Call<TagInfo> request, Throwable failure) {
                if (!valid(request, token)) return;
                loading = false; error = getApplication().getString(R.string.community_load_failed); update();
            }
        });
    }
    public void toggle(String name) {
        if (busy || loading || session.isEmpty() || info == null || info.getId() <= 0) return;
        String token = session; busy = true; update();
        Call<Community.TagFavorite> call = RetrofitClient.getInstance().favoriteTag(token, new Community.TagId(info.getId())); activeCall = call;
        call.enqueue(new Callback<Community.TagFavorite>() {
            @Override public void onResponse(Call<Community.TagFavorite> request, Response<Community.TagFavorite> response) {
                if (!valid(request, token)) return;
                busy = false;
                if (response.isSuccessful() && response.body() != null) {
                    info.faved = response.body().faved; info.count_fav = response.body().count_fav; Community.changed(); version = Community.version();
                } else error = ApiErrors.message(response);
                update();
            }
            @Override public void onFailure(Call<Community.TagFavorite> request, Throwable failure) {
                if (!valid(request, token)) return;
                busy = false; error = getApplication().getString(R.string.community_operation_uncertain); load(name);
            }
        });
    }
    @Override protected void onCleared() { cleared = true; if (activeCall != null) activeCall.cancel(); }
}
