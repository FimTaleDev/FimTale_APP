package com.fimtale.account;

import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.R;
import com.fimtale.TopicDetailActivity;
import com.fimtale.modules.*;
import com.fimtale.utils.BbCodeRendering;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.*;
import io.noties.markwon.Markwon;
import java.util.*;
import static com.fimtale.modules.ModuleApi.*;

public class ExamActivity extends ModuleActivity {
    private ExamModel state;
    private TextView timer;
    private Markwon renderer;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { state.tick(); showTime(); clock.postDelayed(this, 1000); }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = new ViewModelProvider(this).get(ExamModel.class); bind(state, R.string.exam_title);
        centerPagers();
        renderer = BbCodeRendering.create(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (state.busy()) { toast(R.string.common_loading_active); return; }
                if (state.questions.size() > 0 && state.result == null && state.remaining() > 0)
                    confirm(getString(R.string.exam_leave), () -> finish());
                else finish();
            }
        });
        startPage();
    }
    @Override protected void onResume() {
        super.onResume();
        if (state != null) { clock.removeCallbacks(tick); clock.post(tick); }
    }
    @Override protected void onPause() { clock.removeCallbacks(tick); super.onPause(); }
    @Override public void finish() {
        if (state != null && (state.result != null || state.qualified())) setResult(RESULT_OK);
        super.finish();
    }
    @Override protected void refresh() { state.refresh(); }
    @Override protected boolean usesHeaderLoadingIndicator() { return false; }
    @Override protected boolean supportsPullToRefresh() { return true; }
    @Override protected boolean usesToolbarRefresh() { return false; }
    @Override protected void render() {
        timer = null;
        if (loginRequired()) return;
        if ((state.loading("header") || state.action.equals("start")) && state.questions.size() == 0 && state.result == null) { loadingBlocks(2); return; }
        errors();
        if (state.result != null) { result(); return; }
        if (state.qualified() && (state.questions.size() == 0 || state.submitAttempted)) {
            heading(content, getString(R.string.exam_qualified));
            if (state.submitAttempted) text(content, R.string.exam_result_unavailable);
            backButton(); return;
        }
        if (state.questions.size() == 0) {
            text(content, R.string.exam_instructions);
            button(content, R.string.exam_handbook, "handbook", false, false,
                    () -> startActivity(new Intent(this, TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, 4)));
            startButton(false); return;
        }
        timer = text(content, ""); showTime();
        JsonObject question = obj(state.questions.get(state.index)); int type = num(question, "type");
        String[] types = getResources().getStringArray(R.array.exam_types);
        heading(content, getString(R.string.exam_question, state.index + 1, state.questions.size(), types[type - 1]));
        renderer.setMarkdown(text(content, ""), str(question, "stem"));
        JsonArray current = state.currentAnswers();
        if (type == 1 || type == 3) singleChoice(question, current, type == 3);
        else if (type == 2) multipleChoice(question, current);
        else fillBlanks(question, current);
        pager(state.index + 1, state.questions.size(), page -> state.select(page - 1));
        if (state.remaining() == 0) text(content, R.string.exam_expired);
        if (state.submitAttempted && !state.busy()) text(content, R.string.exam_unknown);
        button(content, state.submitAttempted ? R.string.exam_retry_submit : R.string.exam_submit, "submit", false, true,
                () -> confirm(state.submitAttempted ? getString(R.string.exam_retry_confirm)
                        : getString(R.string.exam_submit_confirm, state.answered(), state.questions.size()), state::submit));
        if (state.remaining() == 0 && !state.busy()) startButton(true);
    }
    private void singleChoice(JsonObject question, JsonArray current, boolean judgment) {
        RadioGroup choices = new RadioGroup(this); content.addView(choices);
        List<String> values = new ArrayList<>();
        if (judgment) { values.add("true"); values.add("false"); }
        else for (JsonElement option : array(question, "options")) values.add(option.getAsString());
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            RadioButton choice = new com.google.android.material.radiobutton.MaterialRadioButton(this);
            choice.setId(View.generateViewId()); choice.setTag(value);
            if (judgment) choice.setText(i == 0 ? R.string.exam_true : R.string.exam_false);
            else renderer.setMarkdown(choice, value);
            choice.setEnabled(state.canAnswer()); choices.addView(choice);
            if (current.size() > 0 && current.get(0).getAsString().equals(value)) choices.check(choice.getId());
        }
        choices.setOnCheckedChangeListener((group, id) -> {
            RadioButton selected = group.findViewById(id);
            if (selected != null) {
                JsonArray answer = new JsonArray(); answer.add(selected.getTag().toString()); state.answer(answer); showTime();
            }
        });
    }
    private void multipleChoice(JsonObject question, JsonArray current) {
        Set<String> selected = new LinkedHashSet<>();
        for (JsonElement answer : current) selected.add(answer.getAsString());
        for (JsonElement item : array(question, "options")) {
            String value = item.getAsString();
            CheckBox option = new com.google.android.material.checkbox.MaterialCheckBox(this);
            renderer.setMarkdown(option, value); option.setChecked(selected.contains(value)); option.setEnabled(state.canAnswer());
            content.addView(option);
            option.setOnCheckedChangeListener((view, checked) -> {
                if (checked) selected.add(value); else selected.remove(value);
                JsonArray answer = new JsonArray(); for (String chosen : selected) answer.add(chosen);
                state.answer(answer); showTime();
            });
        }
    }
    private void fillBlanks(JsonObject question, JsonArray current) {
        int count = ExamModel.blankCount(question);
        JsonArray values = current.deepCopy(); while (values.size() < count) values.add("");
        for (int i = 0; i < count; i++) {
            final int position = i;
            TextInputLayout box = new TextInputLayout(this);
            box.setHint(getString(R.string.exam_blank, i + 1)); box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
            TextInputEditText input = new TextInputEditText(box.getContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT); input.setSaveEnabled(false);
            input.setTag("exam:" + state.index + ":" + i);
            input.setText(values.get(i).getAsString()); input.setEnabled(state.canAnswer());
            box.addView(input); content.addView(box);
            watch(input, value -> { values.set(position, new JsonPrimitive(value)); state.answer(values); showTime(); });
        }
    }
    private void startButton(boolean again) {
        if (state.qualified()) return;
        if (again) text(content, R.string.exam_again_hint);
        button(content, again ? R.string.exam_again : R.string.exam_start, "start", false, true,
                () -> confirm(getString(again ? R.string.exam_again_confirm : R.string.exam_start_confirm), state::start))
                .setEnabled(!state.busy() && !state.loading("header") && state.canStart());
    }
    private void showTime() {
        if (timer == null) return;
        long seconds = state.remaining();
        timer.setText(getString(R.string.exam_timer, (int) (seconds / 60), (int) (seconds % 60), state.answered(), state.questions.size()));
    }
    private void result() {
        heading(content, getString(bool(state.result, "pass") ? R.string.exam_passed : R.string.exam_failed, num(state.result, "score")));
        Map<Integer, JsonObject> questions = new HashMap<>();
        for (JsonElement question : state.questions) questions.put(num(obj(question), "id"), obj(question));
        int index = 0;
        for (JsonElement value : array(state.result, "questions")) {
            JsonObject answer = obj(value), question = questions.get(num(answer, "question_id"));
            heading(content, getString(R.string.exam_result_question, ++index, "",
                    getString(bool(answer, "correct") ? R.string.exam_true : R.string.exam_false)));
            if (question != null) renderer.setMarkdown(text(content, ""), str(question, "stem"));
            int type = question == null ? 0 : num(question, "type");
            text(content, getString(R.string.exam_answers, joined(array(answer, "user_answers"), type),
                    joined(array(answer, "correct_answers"), type)));
        }
        if (!bool(state.result, "pass")) startButton(true);
        backButton();
    }
    private void backButton() {
        button(content, R.string.exam_return, "return", false, false, this::finish);
    }
    private String joined(JsonArray values, int type) {
        List<String> labels = new ArrayList<>();
        for (JsonElement value : values) {
            String answer = value.getAsString();
            if (answer.trim().isEmpty()) labels.add(getString(R.string.exam_unanswered));
            else labels.add(type == 3 ? getString("true".equals(answer) ? R.string.exam_true : R.string.exam_false) : answer);
        }
        return labels.isEmpty() ? getString(R.string.exam_unanswered) : android.text.TextUtils.join("、", labels);
    }
}
