package com.fimtale.account;

import android.app.Application;
import android.graphics.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.SavedStateHandle;
import com.fimtale.R;
import com.fimtale.community.Community;
import com.fimtale.model.CurrentUser;
import com.fimtale.modules.*;
import com.fimtale.network.*;
import com.fimtale.utils.UserPreferences;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import static com.fimtale.modules.ModuleApi.*;

public class AccountModel extends ModuleModel {
    public JsonObject user = new JsonObject(), auth = new JsonObject(), draft = new JsonObject();
    public JsonArray badges = new JsonArray();
    public JsonObject badgeDraft = new JsonObject();
    public boolean captchaRequested, passwordChanged, dirty;
    public int tab;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private Call<String> upload;
    private int imageGeneration;
    public AccountModel(Application app, SavedStateHandle saved) {
        super(app, saved); tab = saved.get("tab") == null ? 0 : saved.get("tab");
        String value = saved.get("profile");
        if (value != null) try { draft = new JsonParser().parse(value).getAsJsonObject(); dirty = true; }
        catch (RuntimeException ignored) { saved.remove("profile"); }
        String decoration = saved.get("badge_draft");
        if (decoration != null) try { badgeDraft = new JsonParser().parse(decoration).getAsJsonObject(); }
        catch (RuntimeException ignored) { saved.remove("badge_draft"); }
    }
    @Override protected void reset() {
        imageGeneration++; if (upload != null) upload.cancel(); upload = null;
        user = new JsonObject(); auth = new JsonObject(); draft = new JsonObject(); badgeDraft = new JsonObject(); badges = new JsonArray(); dirty = false; captchaRequested = false; passwordChanged = false;
    }
    public void select(int value) {
        if (busy()) return;
        tab = Math.max(0, Math.min(2, value)); saved.set("tab", tab);
        if (signedIn() && started) {
            if (tab == 1 && auth.size() == 0 && !loading("auth")) read("auth", Route.AUTH, query(), data -> auth = obj(data), null);
            if (tab == 2 && badges.size() == 0 && !loading("badges")) read("badges", Route.BADGES, query(), data -> badges = array(data), null);
        }
        changed();
    }
    public void refresh() {
        if (busy()) return;
        if (!signedIn()) { changed(); return; }
        loadUser(null); read("auth", Route.AUTH, query(), value -> auth = obj(value), null);
        read("badges", Route.BADGES, query(), value -> badges = array(value), null);
    }
    private void loadUser(Runnable done) {
        read("header", Route.USER, query(), value -> {
            JsonObject next = obj(value); if (num(next, "id") <= 0) throw new IllegalStateException();
            user = next;
            CurrentUser current = new Gson().fromJson(user, CurrentUser.class);
            if (current != null && current.id > 0) UserPreferences.saveSession(getApplication(), token, current);
            if (!dirty) {
                draft = new JsonObject();
                for (String key : new String[]{"intro","home_page","sex","birthday","avatar","photo"}) if (user.has(key)) draft.add(key, user.get(key));
            }
        }, done);
    }
    public void edit(String key, String value) { draft.addProperty(key, value); saveDraft(); }
    public void editSex(int value) { draft.addProperty("sex", value); saveDraft(); }
    private void saveDraft() { dirty = true; saved.set("profile", draft.toString()); }
    public void editRace(int badgeId) { badgeDraft.addProperty("race", badgeId); saveBadgeDraft(); }
    public void editBadge(int badgeId, boolean visible) {
        JsonObject visibility = obj(badgeDraft, "visibility"); visibility.addProperty(Integer.toString(badgeId), visible);
        badgeDraft.add("visibility", visibility); saveBadgeDraft();
    }
    private void saveBadgeDraft() {
        if (badgeDraft.size() == 0) saved.remove("badge_draft"); else saved.set("badge_draft", badgeDraft.toString());
    }
    public void saveProfile() {
        if (num(user, "id") <= 0) return;
        post("profile", Route.PROFILE, draft, value -> {
            dirty = false; saved.remove("profile"); notice = getApplication().getString(R.string.modules_saved);
            Community.changed(); loadUser(this::finishAction);
        });
    }
    public void change(ModuleApi.Route route, JsonObject body) {
        post(route.name(), route, body, value -> {
            if (route == Route.SET_BADGES) { badgeDraft.remove("race"); saveBadgeDraft(); }
            if (route == Route.BADGE_VISIBILITY) { badgeDraft.remove("visibility"); saveBadgeDraft(); }
            if (route == Route.PASSWORD) {
                UserPreferences.clearSession(getApplication()); passwordChanged = true; finishAction(); Community.changed(); return;
            }
            notice = getApplication().getString(route == Route.EMAIL ? R.string.account_email_changed : R.string.modules_saved);
            Community.changed();
            read("auth", Route.AUTH, query(), data -> auth = obj(data), () -> loadUser(this::finishAction));
            if (badges.size() == 0) read("badges", Route.BADGES, query(), data -> badges = array(data), null);
        });
    }
    public void requestVerification() { if (!busy() && signedIn()) { captchaRequested = true; changed(); } }
    public void captcha(String response, String provider) {
        if (!captchaRequested) return; captchaRequested = false;
        if (response == null || response.isEmpty()) { changed(); return; }
        post("verify", Route.SEND_VERIFY, object(), query("captcha_response", response, "captcha_type", provider), value -> {
            notice = getApplication().getString(R.string.account_verify_sent); finishAction();
        });
    }
    public void verify(String code) {
        if (busy() || !signedIn() || num(user, "id") <= 0) return;
        // This GET consumes a verification token; retain the request and never replay it on recreation.
        post("verify", Route.VERIFY, object(), query("token", code, "user_id", num(user, "id")), value -> {
            notice = getApplication().getString(R.string.modules_saved);
            Community.changed(); read("auth", Route.AUTH, query(), data -> auth = obj(data), this::finishAction);
        });
    }
    public void saveBadges(JsonArray ids, boolean visibility) {
        change(visibility ? Route.BADGE_VISIBILITY : Route.SET_BADGES,
                visibility ? object("off_badge_ids", ids) : object("user_id", num(user, "id"), "badge_ids", ids));
    }
    public void upload(Uri uri, boolean avatar) {
        if (busy() || !signedIn()) return;
        String owner = token; int generation = ++imageGeneration; holdAction(avatar ? "avatar" : "photo");
        images.execute(() -> {
            File file = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                try (InputStream stream = getApplication().getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(stream, null, options); }
                if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException();
                options.inJustDecodeBounds = false; options.inSampleSize = 1; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2;
                Bitmap bitmap;
                try (InputStream stream = getApplication().getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
                if (bitmap == null) throw new IOException();
                bitmap = orient(bitmap, uri);
                if (avatar) { int size = Math.min(bitmap.getWidth(), bitmap.getHeight()); Bitmap crop = Bitmap.createBitmap(bitmap, (bitmap.getWidth()-size)/2, (bitmap.getHeight()-size)/2, size, size); if (crop != bitmap) bitmap.recycle(); bitmap = crop; }
                int max = avatar ? 512 : 1280; float scale = Math.min(1f, (float) max / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth()*scale)), Math.max(1, Math.round(bitmap.getHeight()*scale)), true);
                if (scaled != bitmap) bitmap.recycle();
                boolean alpha = scaled.hasAlpha();
                file = File.createTempFile("profile_", alpha ? ".png" : ".jpg", getApplication().getCacheDir());
                try (OutputStream out = new FileOutputStream(file)) { if (!scaled.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException(); }
                finally { scaled.recycle(); }
                File ready = file;
                new Handler(Looper.getMainLooper()).post(() -> sendImage(ready, avatar, owner, generation));
            } catch (IOException | RuntimeException failure) {
                if (file != null) file.delete();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (generation != imageGeneration) return;
                    if (!sameSession(owner)) { connect(); return; }
                    notice = getApplication().getString(R.string.account_image_failed); finishAction();
                });
            }
        });
    }
    private Bitmap orient(Bitmap bitmap, Uri uri) {
        int orientation = android.media.ExifInterface.ORIENTATION_NORMAL;
        try (InputStream stream = getApplication().getContentResolver().openInputStream(uri)) {
            if (stream != null) orientation = new android.media.ExifInterface(stream).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) { }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1, -1); break;
            case android.media.ExifInterface.ORIENTATION_TRANSPOSE: matrix.postRotate(90); matrix.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case android.media.ExifInterface.ORIENTATION_TRANSVERSE: matrix.postRotate(270); matrix.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            default: return bitmap;
        }
        Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (oriented != bitmap) bitmap.recycle();
        return oriented;
    }
    private void sendImage(File file, boolean avatar, String owner, int generation) {
        if (generation != imageGeneration || !sameSession(owner)) { file.delete(); connect(); return; }
        upload = RetrofitClient.getInstance().uploadImage(owner, MultipartBody.Part.createFormData("file", file.getName(), RequestBody.create(MediaType.parse(file.getName().endsWith(".png") ? "image/png" : "image/jpeg"), file)));
        upload.enqueue(new Callback<String>() {
            @Override public void onResponse(Call<String> call, Response<String> response) {
                file.delete(); if (call != upload || generation != imageGeneration) return;
                if (!sameSession(owner)) { connect(); return; }
                upload = null;
                if (response.isSuccessful() && response.body() != null && !response.body().trim().isEmpty() && SiteUrls.media(response.body()) != null) { edit(avatar ? "avatar" : "photo", response.body()); notice = getApplication().getString(R.string.account_image_ready); }
                else notice = response.isSuccessful() ? getApplication().getString(R.string.account_image_failed) : ApiErrors.message(response);
                finishAction();
            }
            @Override public void onFailure(Call<String> call, Throwable failure) {
                file.delete(); if (call != upload || generation != imageGeneration) return;
                if (!sameSession(owner)) { connect(); return; }
                upload = null; notice = getApplication().getString(R.string.community_load_failed); finishAction();
            }
        });
    }
    @Override protected void onCleared() { imageGeneration++; if (upload != null) upload.cancel(); images.shutdownNow(); super.onCleared(); }
}
