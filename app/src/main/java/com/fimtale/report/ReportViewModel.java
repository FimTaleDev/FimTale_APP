package com.fimtale.report;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import com.fimtale.R;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Fragment-scoped state keeps a single submission alive through configuration changes. */
public class ReportViewModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public boolean submitting, needsLogin, complete, successAnnounced;
    public String error = "", contentError = "";
    private final SavedStateHandle saved;
    private String session;
    private Call<ReportRequest.Result> request;

    public ReportViewModel(@NonNull Application application, SavedStateHandle saved) {
        super(application);
        this.saved = saved;
        session = UserPreferences.getToken(application);
        needsLogin = session.isEmpty();
        complete = Boolean.TRUE.equals(saved.get("complete"));
        // Process recreation cannot determine whether the server accepted the previous POST.
        if (Boolean.TRUE.equals(saved.get("pending"))) error = application.getString(R.string.report_uncertain);
        saved.set("pending", false);
    }
    public String content() { String value = saved.get("content"); return value == null ? "" : value; }
    public void setContent(String value) {
        saved.set("content", value);
        if (!contentError.isEmpty()) { contentError = ""; changed(); }
    }
    private void changed() { changes.setValue(changes.getValue() + 1); }
    private boolean current() { return session.equals(UserPreferences.getToken(getApplication())); }
    public void connect() {
        String next = UserPreferences.getToken(getApplication());
        if (!next.equals(session)) {
            boolean wasSubmitting = submitting;
            cancel(); session = next;
            error = wasSubmitting ? getApplication().getString(R.string.report_session_changed) : "";
        }
        needsLogin = session.isEmpty(); changed();
    }
    public void submit(int targetType, long targetId) {
        if (submitting || complete) return;
        if (!current()) { connect(); return; }
        if (needsLogin) return;
        if (ReportRequest.trim(content()).isEmpty()) {
            contentError = getApplication().getString(R.string.report_content_required); changed(); return;
        }
        if ((targetType != ReportRequest.WORK && targetType != ReportRequest.COMMENT && targetType != ReportRequest.USER) || targetId <= 0) {
            error = getApplication().getString(R.string.report_invalid_target); changed(); return;
        }
        submitting = true; error = ""; contentError = ""; saved.set("pending", true); changed();
        request = RetrofitClient.getInstance().createReport(session, new ReportRequest(targetType, targetId, content()));
        request.enqueue(new Callback<ReportRequest.Result>() {
            @Override public void onResponse(Call<ReportRequest.Result> call, Response<ReportRequest.Result> response) {
                if (request != call) return;
                if (!current()) { connect(); return; }
                request = null; submitting = false; saved.set("pending", false);
                if (response.code() == 401) {
                    UserPreferences.clearSession(getApplication()); connect(); return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().id > 0) {
                    complete = true; saved.set("complete", true);
                } else error = response.isSuccessful() ? getApplication().getString(R.string.report_uncertain) : ApiErrors.message(response);
                changed();
            }
            @Override public void onFailure(Call<ReportRequest.Result> call, Throwable failure) {
                if (request != call) return;
                if (!current()) { connect(); return; }
                request = null; submitting = false; saved.set("pending", false);
                error = getApplication().getString(R.string.report_uncertain); changed();
            }
        });
    }
    private void cancel() {
        if (request != null) { request.cancel(); request = null; }
        submitting = false; saved.set("pending", false);
    }
    @Override protected void onCleared() { cancel(); }
}
