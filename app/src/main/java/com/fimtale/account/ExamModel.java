package com.fimtale.account;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.lifecycle.SavedStateHandle;
import com.fimtale.community.Community;
import com.fimtale.modules.*;
import com.fimtale.utils.UserPreferences;
import com.google.gson.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.fimtale.modules.ModuleApi.*;

/** Retains a single attempt, frozen submitted answers, and its server deadline across recreation. */
public class ExamModel extends ModuleModel {
    private static final Pattern BLANK = Pattern.compile("_{3,}");
    public JsonArray questions = new JsonArray(), answers = new JsonArray();
    public JsonObject result, auth = new JsonObject();
    public int index;
    public boolean submitAttempted;
    private boolean expiredRendered;
    private long deadline, expiresAt, duration;
    private final SharedPreferences store;

    public ExamModel(Application app, SavedStateHandle state) {
        super(app, state);
        store = app.getSharedPreferences("fimtale_exam", Context.MODE_PRIVATE);
        restore();
    }
    private void clearAttempt() {
        questions = new JsonArray(); answers = new JsonArray(); result = null;
        index = 0; deadline = expiresAt = duration = 0; submitAttempted = expiredRendered = false;
    }
    @Override protected void reset() { clearAttempt(); auth = new JsonObject(); restore(); }

    private void restore() {
        String owner = UserPreferences.getUserId(getApplication());
        if (owner.isEmpty() || !owner.equals(store.getString("owner", ""))) return;
        try {
            JsonObject data = new JsonParser().parse(store.getString("state", "{}")).getAsJsonObject();
            JsonArray restoredQuestions = array(data, "questions");
            if (restoredQuestions.size() == 0) return;
            validateQuestions(restoredQuestions);
            JsonArray restoredAnswers = array(data, "answers");
            if (restoredAnswers.size() != restoredQuestions.size()) throw new IllegalStateException();
            for (JsonElement answer : restoredAnswers) {
                if (!answer.isJsonArray()) throw new IllegalStateException();
                for (JsonElement item : answer.getAsJsonArray()) if (!item.isJsonPrimitive()) throw new IllegalStateException();
            }
            questions = restoredQuestions; answers = restoredAnswers;
            index = Math.max(0, Math.min(num(data, "index"), questions.size() - 1));
            submitAttempted = bool(data, "submit_attempted");
            expiresAt = number(data, "expires_at"); duration = number(data, "duration");
            serverOffset = number(data, "server_offset");
            int currentBoot = bootCount();
            if (currentBoot >= 0 && data.has("boot_count") && num(data, "boot_count") == currentBoot) {
                deadline = number(data, "deadline");
            } else {
                long remaining = Math.min(duration, expiresAt - (System.currentTimeMillis() + serverOffset));
                deadline = SystemClock.elapsedRealtime() + Math.max(0, remaining);
            }
            if (validReport(obj(data, "result"))) result = obj(data, "result");
        } catch (RuntimeException failure) {
            clearAttempt(); store.edit().clear().apply();
        }
    }
    private static long number(JsonObject data, String key) {
        return data.has(key) && data.get(key).isJsonPrimitive() ? data.get(key).getAsLong() : 0;
    }
    private int bootCount() {
        try { return Settings.Global.getInt(getApplication().getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (SecurityException failure) { return -1; }
    }
    public void refresh() {
        if (busy()) return;
        if (signedIn()) refreshAuth(); else changed();
    }
    private void refreshAuth() {
        read("header", Route.AUTH, query(), value -> auth = obj(value), null);
    }
    private void persist() {
        if (!signedIn()) return;
        JsonObject value = object("questions", questions, "answers", answers, "index", index,
                "deadline", deadline, "expires_at", expiresAt, "duration", duration,
                "server_offset", serverOffset, "boot_count", bootCount(),
                "submit_attempted", submitAttempted, "result", result);
        store.edit().putString("owner", UserPreferences.getUserId(getApplication())).putString("state", value.toString()).apply();
    }
    public boolean qualified() { return num(auth, "qualify_status") >= 2; }
    public boolean canStart() {
        return signedIn() && auth.size() > 0 && !qualified()
                && (questions.size() == 0 || result != null || remaining() == 0);
    }
    public boolean canAnswer() {
        return signedIn() && !busy() && result == null && !submitAttempted && remaining() > 0;
    }
    public void start() {
        if (busy() || !canStart()) return;
        post("start", Route.START_EXAM, object(), value -> {
            JsonObject exam = obj(value); JsonArray next = array(exam, "questions").deepCopy();
            validateQuestions(next);
            long expires = Instant.parse(str(exam, "expires_at")).toEpochMilli();
            long started = Instant.parse(str(exam, "started_at")).toEpochMilli();
            if (expires <= started) throw new IllegalStateException();
            // The client only needs the question and options; result answers arrive with the report.
            for (JsonElement question : next) obj(question).remove("answers");
            long remaining = Math.min(expires - started, expires - (System.currentTimeMillis() + serverOffset));
            clearAttempt();
            expiresAt = expires; duration = expires - started;
            deadline = SystemClock.elapsedRealtime() + Math.max(0, remaining);
            questions = next;
            for (JsonElement ignored : questions) answers.add(new JsonArray());
            persist();
        });
    }
    private static void validateQuestions(JsonArray values) {
        if (values.size() == 0) throw new IllegalStateException();
        Set<Integer> ids = new HashSet<>();
        for (JsonElement value : values) {
            JsonObject question = obj(value);
            int id = num(question, "id"), type = num(question, "type");
            if (id <= 0 || !ids.add(id) || type < 1 || type > 4 || str(question, "stem").trim().isEmpty()) throw new IllegalStateException();
            if ((type == 1 || type == 2) && array(question, "options").size() == 0) throw new IllegalStateException();
            for (JsonElement option : array(question, "options")) if (!option.isJsonPrimitive()) throw new IllegalStateException();
        }
    }
    public void select(int value) {
        if (busy() || questions.size() == 0) return;
        index = Math.max(0, Math.min(value, questions.size() - 1)); persist(); changed();
    }
    public JsonArray currentAnswers() { return index >= 0 && index < answers.size() ? array(answers.get(index)) : new JsonArray(); }
    public void answer(JsonArray value) {
        if (canAnswer() && index >= 0 && index < answers.size()) { answers.set(index, value.deepCopy()); persist(); }
    }
    public long remaining() { return Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000); }
    public static int blankCount(JsonObject question) {
        Matcher matcher = BLANK.matcher(str(question, "stem")); int count = 0;
        while (matcher.find()) count++;
        return Math.max(1, count);
    }
    public int answered() {
        int count = 0;
        for (int i = 0; i < answers.size(); i++) {
            JsonArray answer = array(answers.get(i)); boolean complete = answer.size() > 0;
            if (num(obj(questions.get(i)), "type") == 4) complete &= answer.size() == blankCount(obj(questions.get(i)));
            for (JsonElement value : answer) if (!value.isJsonPrimitive() || value.getAsString().trim().isEmpty()) complete = false;
            if (complete) count++;
        }
        return count;
    }
    public void tick() {
        if (questions.size() == 0 || result != null || remaining() > 0) return;
        if (!expiredRendered) { expiredRendered = true; changed(); }
        if (!busy() && !submitAttempted && signedIn()) submit();
    }
    public void submit() {
        if (busy() || !signedIn() || result != null || questions.size() == 0 || submitAttempted && qualified()) return;
        JsonArray submitted = new JsonArray();
        for (int i = 0; i < questions.size(); i++) {
            JsonArray answer = array(answers.get(i)).deepCopy();
            // The wire contract requires at least one string even for an unanswered question.
            if (answer.size() == 0) answer.add("");
            submitted.add(object("question_id", num(obj(questions.get(i)), "id"), "answers", answer));
        }
        // Once sent, retries use exactly these answers. A lost response must not silently re-submit.
        submitAttempted = true; persist();
        post("submit", Route.SUBMIT_EXAM, object("answers", submitted), value -> {
            JsonObject report = obj(value);
            if (!validReport(report)) throw new IllegalStateException();
            result = report; persist(); Community.changed(); refreshAuth();
        });
    }
    private static boolean validReport(JsonObject report) {
        JsonElement pass = report.get("pass"), score = report.get("score");
        return pass != null && pass.isJsonPrimitive() && pass.getAsJsonPrimitive().isBoolean()
                && score != null && score.isJsonPrimitive() && score.getAsJsonPrimitive().isNumber();
    }
}
