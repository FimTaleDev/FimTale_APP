package com.fimtale.editor;

import com.fimtale.R;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.fimtale.db.CacheManager;
import com.fimtale.model.ChapterResponse;
import com.fimtale.model.UserAuth;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.FimTaleApiService;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import com.fimtale.utils.UserPreferences;
import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Retains a single submission across configuration changes; writes drafts off the UI thread. */
public class EditorViewModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public EditorDocument document;
    public final EditorHistory bodyHistory = new EditorHistory();
    public EditorDocument historyDocument;
    public int workId, chapterId, documentVersion, savedId;
    public String userId, message = getApplication().getString(R.string.common_loading_active);
    public boolean initialized, ready, busy, error, needsLogin, captchaRequested;
    public boolean dirty, finished, allowAnnouncement;
    public boolean loadError, needsExam;
    private boolean cleared, loading;
    private int ownerId;
    private int initialWorkType = 1;
    private EditorDraftStore drafts;
    private DraftSync draftSync;
    public boolean syncConflict;
    public String completionMessage = getApplication().getString(R.string.editor_submission_success);
    private long changeVersion;
    private final Runnable autoSync = () -> saveDraft(null);
    private final FimTaleApiService api = RetrofitClient.getInstance();
    private final Gson gson = new Gson();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Call<?> active;
    private final Runnable autoSave = this::persistDraft;

    public EditorViewModel(@NonNull Application application) { super(application); }
    public boolean isChapter() { return chapterId >= 0; }
    private void notifyUi() { if (!cleared) changes.setValue(changes.getValue() + 1); }
    private void fail(String text) {
        loadError = loading;
        busy = false; loading = false; captchaRequested = false; error = true; message = text; notifyUi();
    }
    private boolean sameAccount() {
        return UserPreferences.isLoggedIn(getApplication())
                && userId.equals(UserPreferences.getUserId(getApplication()));
    }
    public void initialize(int workId, int chapterId) {
        initialize(workId, chapterId, null);
    }
    public void initialize(int workId, int chapterId, String draftId) {
        initialize(workId, chapterId, draftId, 1);
    }
    public void initialize(int workId, int chapterId, String draftId, int initialWorkType) {
        if (initialized) return;
        this.initialWorkType = initialWorkType == 3 ? 3 : 1;
        initialized = true; this.workId = workId; this.chapterId = chapterId;
        userId = UserPreferences.getUserId(getApplication());
        drafts = new EditorDraftStore(new File(getApplication().getNoBackupFilesDir(), "editor_drafts"),
                SiteUrls.API, userId, workId, chapterId, draftId);
        draftSync = new DraftSync(drafts, DraftCodec.key(workId, chapterId, draftId), workId, chapterId);
        load();
    }
    public void load() {
        if (busy || loading || finished) return;
        loadError = false; needsExam = false;
        if (ready) persistDraft();
        busy = true; loading = true; error = false; message = getApplication().getString(R.string.editor_loading_online_draft); notifyUi();
        io.execute(() -> {
            try {
                EditorDocument draft = draftSync.load(remote());
                main.post(() -> {
                    if (cleared) return;
                    document = draft; ready = draft != null; documentVersion++;
                    syncConflict = draftSync.conflict;
                    loadRemote();
                });
            } catch (Exception e) { main.post(() -> fail(getApplication().getString(R.string.editor_draft_load_failed, e.getMessage()))); }
        });
    }
    private void loadRemote() {
        if (!sameAccount()) { needsLogin = true; fail(getApplication().getString(R.string.editor_original_login_required)); return; }
        if (workId == 0) {
            if (document == null) {
                document = new EditorDocument();
                if (initialWorkType == 3) {
                    document.work.type = 3;
                    document.work.length = document.work.rating = document.work.origin = document.work.publish = 0;
                }
                documentVersion++;
            }
            loaded(); return;
        }
        request(api.getWorkForEdit(workId, true), result -> {
            if (result.work == null || result.work.id == null || result.work.id != workId || result.work.user == null) {
                fail(getApplication().getString(R.string.editor_work_incomplete)); return;
            }
            ownerId = result.work.user.getId();
            if (!isChapter()) {
                if (document == null) { document = result.toDocument(); documentVersion++; }
                loaded();
            } else if (chapterId == 0) {
                if (document == null) {
                    document = new EditorDocument(); document.chapter.workId = workId; documentVersion++;
                }
                loaded();
            } else {
                request(api.getChapterForEdit(chapterId, true), chapter -> {
                    if (chapter.chapter == null || chapter.chapter.workId != workId || chapter.chapter.id != chapterId) {
                        fail(getApplication().getString(R.string.editor_chapter_mismatch)); return;
                    }
                    if (document == null) {
                        document = new EditorDocument(); document.chapter.workId = workId;
                        document.chapter.id = chapterId; document.chapter.title = chapter.chapter.title;
                        document.chapter.content = chapter.chapter.content; documentVersion++;
                    }
                    loaded();
                });
            }
        });
    }
    private void loaded() {
        ready = true; error = false;
        if (document.savedAt == 0 && (workId == 0 || chapterId == 0)) saveDraft(null);
        message = document.submissionUncertain ? getApplication().getString(R.string.editor_submission_uncertain_notice)
                : draftSync.warning != null ? draftSync.warning
                : document.pendingSync ? getApplication().getString(R.string.editor_draft_waiting) : getApplication().getString(R.string.editor_draft_synced);
        notifyUi();
        request(api.getUserAuth(null), auth -> {
            allowAnnouncement = auth.roleId >= 4;
            if (workId > 0 && !auth.canEdit(ownerId)) { fail(getApplication().getString(R.string.editor_no_permission)); return; }
            busy = false; loading = false;
            if (document.pendingSync && !syncConflict) { main.removeCallbacks(autoSync); main.postDelayed(autoSync, 10000); }
            notifyUi();
        });
    }
    private DraftRemote remote() throws java.io.IOException {
        if (!sameAccount()) throw new java.io.IOException(getApplication().getString(R.string.editor_sync_login_required));
        return new DraftRemote(api, UserPreferences.getToken(getApplication()));
    }
    public void changed() {
        if (!ready || busy || finished) return;
        dirty = true; changeVersion++; document.pendingSync = true;
        if (!error && !syncConflict && !document.submissionUncertain
                && getApplication().getString(R.string.editor_draft_synced).equals(message))
            message = getApplication().getString(R.string.editor_draft_changes_pending);
        main.removeCallbacks(autoSave); main.postDelayed(autoSave, 500);
        main.removeCallbacks(autoSync);
        if (!syncConflict) main.postDelayed(autoSync, 10000);
    }
    private EditorDocument snapshot() {
        document.savedAt = System.currentTimeMillis();
        return gson.fromJson(gson.toJson(document), EditorDocument.class);
    }
    public void persistDraft() {
        main.removeCallbacks(autoSave);
        if (cleared || !ready || document == null || finished || draftSync == null) return;
        EditorDocument copy = snapshot();
        io.execute(() -> {
            try { draftSync.persist(copy); }
            catch (Exception e) { main.post(() -> fail(getApplication().getString(R.string.editor_local_backup_failed))); }
        });
    }
    public void saveDraft(Runnable after) { saveDraft(after, false); }
    private void saveDraft(Runnable after, boolean requireSync) {
        main.removeCallbacks(autoSave); main.removeCallbacks(autoSync);
        if (cleared) return;
        if (!ready || document == null || finished || draftSync == null) { if (after != null) after.run(); return; }
        EditorDocument copy = snapshot(); long version = changeVersion;
        if (!busy && !error) { message = getApplication().getString(R.string.editor_syncing_draft); notifyUi(); }
        io.execute(() -> {
            try { draftSync.persist(copy); }
            catch (Exception e) { main.post(() -> fail(getApplication().getString(R.string.editor_local_backup_failed_retry))); return; }
            try {
                EditorDocument saved = draftSync.save(remote(), copy);
                main.post(() -> {
                    if (cleared) return;
                    document.draftKey = saved.draftKey; document.revision = saved.revision; document.onlinePayload = saved.onlinePayload;
                    document.pendingSync = version != changeVersion; syncConflict = false;
                    if (!busy && !finished && !error) { message = document.pendingSync ? getApplication().getString(R.string.editor_draft_changes_pending) : getApplication().getString(R.string.editor_draft_synced); notifyUi(); }
                    if (after != null) after.run();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (cleared) return;
                    syncConflict = draftSync.conflict;
                    String detail = syncConflict ? getApplication().getString(R.string.editor_draft_conflict_menu)
                            : getApplication().getString(R.string.editor_draft_unsynced, e.getMessage());
                    if (requireSync) fail(detail);
                    else { if (!busy) { message = detail; notifyUi(); } if (after != null) after.run(); }
                });
            }
        });
    }
    public void resolveDraftConflict(boolean keepLocal) {
        if (busy || !ready || finished) return;
        busy = true; main.removeCallbacks(autoSave); main.removeCallbacks(autoSync); notifyUi();
        EditorDocument copy = snapshot();
        io.execute(() -> {
            try {
                EditorDocument resolved = draftSync.resolve(remote(), copy, keepLocal);
                main.post(() -> {
                    if (cleared) return;
                    syncConflict = false; document = resolved; ready = resolved != null; documentVersion++;
                    busy = false; error = false; message = getApplication().getString(R.string.editor_draft_synced);
                    if (resolved == null) load(); else notifyUi();
                });
            } catch (Exception e) { main.post(() -> fail(getApplication().getString(R.string.editor_conflict_failed, e.getMessage()))); }
        });
    }
    public void discardDraft() {
        if (busy || finished) return;
        main.removeCallbacks(autoSave); main.removeCallbacks(autoSync); busy = true; notifyUi();
        io.execute(() -> {
            try {
                draftSync.discard(remote());
                main.post(() -> {
                    if (cleared) return;
                    document = null; ready = false; dirty = false; busy = false; syncConflict = false; load();
                });
            } catch (Exception e) { main.post(() -> { syncConflict = draftSync.conflict; fail(getApplication().getString(R.string.editor_draft_delete_failed, e.getMessage())); }); }
        });
    }
    public void prepareSubmit() {
        if (busy || !ready || finished) return;
        needsExam = false;
        if (syncConflict) { fail(getApplication().getString(R.string.editor_conflict_before_publish)); return; }
        String validation = isChapter() ? document.chapter.validate() : document.work.validate();
        if (validation != null) { fail(validation); return; }
        if (!sameAccount()) { needsLogin = true; fail(getApplication().getString(R.string.editor_original_account_required)); return; }
        busy = true; error = false; message = getApplication().getString(R.string.editor_checking_permission); notifyUi();
        String token = UserPreferences.getToken(getApplication());
        request(api.getUserAuth(token), auth -> {
            if (!String.valueOf(auth.userId).equals(userId) || !sameAccount()) { fail(getApplication().getString(R.string.error_account_changed)); return; }
            if (workId > 0 && !auth.canEdit(ownerId)) { fail(getApplication().getString(R.string.editor_permission_reload)); return; }
            if (!isChapter() && !auth.canPublish()) { needsExam = true; fail(getApplication().getString(R.string.editor_level_required)); return; }
            if (!isChapter() && document.work.type == 4 && auth.roleId < 4) { fail(getApplication().getString(R.string.editor_announcement_restricted)); return; }
            if (!isChapter() && document.work.type == 3 && auth.spaceStatus != 1) { fail(getApplication().getString(R.string.editor_space_required)); return; }
            if (isChapter()) submit(null, null);
            else {
                captchaRequested = true; message = getApplication().getString(R.string.captcha_title); notifyUi();
            }
        });
    }
    public void examReturned() {
        if (busy || !needsExam || !sameAccount()) return;
        needsExam = false; error = false; message = getApplication().getString(R.string.editor_exam_return); notifyUi();
    }
    public void captchaResult(String token, String provider) {
        // A token returned after process recreation cannot resume an unretained submission.
        if (!captchaRequested || document == null || finished) return;
        captchaRequested = false;
        if (token == null || token.isEmpty()) { busy = false; message = getApplication().getString(R.string.editor_captcha_cancelled); notifyUi(); return; }
        submit(token, provider);
    }
    private void submit(String captcha, String provider) {
        if (!sameAccount()) { fail(getApplication().getString(R.string.editor_session_changed)); return; }
        document.syncTags();
        // Identity always comes from navigation, never from a draft or editable field.
        document.work.id = workId > 0 ? workId : null;
        document.chapter.id = chapterId > 0 ? chapterId : null; document.chapter.workId = workId;
        document.submissionUncertain = true;
        message = getApplication().getString(R.string.editor_submitting); notifyUi();
        saveDraft(() -> {
            if (cleared || !sameAccount()) { fail(getApplication().getString(R.string.editor_session_changed)); return; }
            String token = UserPreferences.getToken(getApplication());
            Call<SaveResult> call = isChapter() ? api.saveChapter(token, document.chapter)
                    : api.saveWork(token, document.work, captcha, provider);
            active = call;
            call.enqueue(new Callback<SaveResult>() {
                @Override public void onResponse(Call<SaveResult> request, Response<SaveResult> response) {
                    if (cleared) return;
                    int expected = isChapter() ? chapterId : workId;
                    if (response.isSuccessful() && response.body() != null && response.body().id > 0
                            && (expected <= 0 || response.body().id == expected)) {
                        savedId = response.body().id; complete();
                    } else {
                        if (response.code() >= 400 && response.code() < 500) document.submissionUncertain = false;
                        needsLogin = response.code() == 401;
                        fail(ApiErrors.message(response)); saveDraft(null);
                    }
                }
                @Override public void onFailure(Call<SaveResult> request, Throwable t) {
                    if (cleared) return;
                    fail(getApplication().getString(R.string.editor_submission_uncertain));
                    saveDraft(null);
                }
            });
        }, true);
    }
    private void complete() {
        finished = true; main.removeCallbacks(autoSave); main.removeCallbacks(autoSync);
        int changedWork = isChapter() ? workId : savedId;
        io.execute(() -> {
            try { draftSync.discard(remote()); }
            catch (Exception ignored) { completionMessage = getApplication().getString(R.string.editor_draft_cleanup_failed); }
            CacheManager.getInstance(getApplication()).invalidateWork(changedWork, () -> {
                EditorChanges.mark(changedWork); busy = false; notifyUi();
            });
        });
    }
    public void upload(Uri uri, boolean cover) {
        if (busy || !ready || !sameAccount()) return;
        busy = true; error = false; message = getApplication().getString(R.string.editor_uploading_image); notifyUi();
        String token = UserPreferences.getToken(getApplication());
        io.execute(() -> {
            try (InputStream in = getApplication().getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new java.io.IOException();
                String mime = getApplication().getContentResolver().getType(uri);
                if (mime == null || !mime.startsWith("image/")) throw new java.io.IOException();
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 4 * 1024 * 1024) {
                        main.post(() -> fail(getApplication().getString(R.string.editor_image_size_invalid))); return;
                    }
                    out.write(buffer, 0, count);
                }
                byte[] data = out.toByteArray();
                main.post(() -> {
                    if (cleared || !sameAccount()) { fail(getApplication().getString(R.string.editor_upload_session_changed)); return; }
                    String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                    request(api.uploadImage(token, MultipartBody.Part.createFormData("file", "image." + (extension == null ? "jpg" : extension),
                            RequestBody.create(MediaType.parse(mime), data))), path -> {
                        if (SiteUrls.media(path) == null) { fail(getApplication().getString(R.string.editor_image_url_invalid)); return; }
                        if (cover) document.work.cover = path;
                        else if (isChapter()) document.chapter.content += "\n[img]" + path + "[/img]\n";
                        else document.work.preface += "\n[img]" + path + "[/img]\n";
                        documentVersion++; busy = false; message = getApplication().getString(R.string.editor_image_inserted); changed(); notifyUi();
                    });
                });
            } catch (Exception e) { main.post(() -> fail(getApplication().getString(R.string.editor_image_read_failed))); }
        });
    }
    private interface Success<T> { void accept(T body); }
    private <T> void request(Call<T> call, Success<T> success) {
        active = call;
        call.enqueue(new Callback<T>() {
            @Override public void onResponse(Call<T> c, Response<T> response) {
                if (cleared) return;
                if (response.isSuccessful() && response.body() != null) success.accept(response.body());
                else { needsLogin = response.code() == 401; fail(ApiErrors.message(response)); }
            }
            @Override public void onFailure(Call<T> c, Throwable t) {
                if (!cleared && !c.isCanceled()) fail(getApplication().getString(R.string.editor_network_error));
            }
        });
    }
    @Override protected void onCleared() {
        persistDraft(); cleared = true; main.removeCallbacks(autoSave); main.removeCallbacks(autoSync);
        if (active != null && !finished) active.cancel();
        io.shutdown();
    }
}
