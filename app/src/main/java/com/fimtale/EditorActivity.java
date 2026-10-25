package com.fimtale;

import com.fimtale.utils.MdiIcons;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.editor.EditorDocument;
import com.fimtale.editor.BbCodeInsertion;
import com.fimtale.editor.EditorFormatDialog;
import com.fimtale.editor.BbCodeEditText;
import com.fimtale.editor.EditorViewModel;
import com.fimtale.editor.EditorHistory;
import com.fimtale.editor.EditorTextStats;
import com.fimtale.editor.EditorPreviewDialog;
import com.fimtale.ui.captcha.CaptchaDialogFragment;
import com.fimtale.ui.captcha.CaptchaDialogHost;
import com.fimtale.editor.TagPickerDialog;
import com.fimtale.editor.WorkInput;
import com.fimtale.ui.FtemojiPicker;
import com.fimtale.utils.DialogHelper;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Native authoring with the shared captcha dialog. */
public class EditorActivity extends AppCompatActivity implements EditorFormatDialog.Host {
    public static final String EXTRA_WORK_ID = "editor_work_id", EXTRA_CHAPTER_ID = "editor_chapter_id";
    public static final String EXTRA_DRAFT_ID = "editor_draft_id";
    private static final String EXTRA_INITIAL_TYPE = "editor_initial_type";
    private EditorViewModel model;
    private com.fimtale.ui.PageErrorView pageError;
    private MaterialToolbar toolbar;
    private EditText title, intro, cover, originLink, prequel;
    private BbCodeEditText body;
    private Spinner type, length, rating, origin, publish;
    private boolean binding, typeHasAnnouncement;
    private boolean metadataVisible;
    private int boundVersion = -1;
    private TagPickerDialog tagPicker;
    private BottomSheetDialog metadataSheet;
    private com.fimtale.ui.BottomSheetMenu moreMenu;
    private View metadataSheetView;
    private androidx.appcompat.app.AlertDialog previewDialog;
    private String countedSource;
    private int characterCount;
    private final Runnable updateStatistics = this::updateEditorStatistics;
    private int restoredSelectionStart, restoredSelectionEnd, restoredScrollY;
    private boolean restorePositionPending;
    private CaptchaDialogHost captcha;
    private final ActivityResultLauncher<Intent> qualification = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (model != null && result.getResultCode() == RESULT_OK) model.examReturned();
            });
    private final ActivityResultLauncher<Intent> login = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (UserPreferences.isLoggedIn(this)) {
                    if (!model.initialized) initialize(); else { model.needsLogin = false; model.load(); }
                } else if (!model.initialized) finish();
            });
    private final ActivityResultLauncher<String> coverPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> { if (uri != null) model.upload(uri, true); });
    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> { if (uri != null) model.upload(uri, false); });

    public static Intent workIntent(Context context, int workId) {
        Intent intent = new Intent(context, EditorActivity.class).putExtra(EXTRA_WORK_ID, workId).putExtra(EXTRA_CHAPTER_ID, -1);
        if (workId == 0) intent.putExtra(EXTRA_DRAFT_ID, java.util.UUID.randomUUID().toString());
        return intent;
    }
    public static Intent chapterIntent(Context context, int workId, int chapterId) {
        Intent intent = new Intent(context, EditorActivity.class).putExtra(EXTRA_WORK_ID, workId).putExtra(EXTRA_CHAPTER_ID, chapterId);
        if (chapterId == 0) intent.putExtra(EXTRA_DRAFT_ID, java.util.UUID.randomUUID().toString());
        return intent;
    }
    public static Intent postIntent(Context context) {
        return workIntent(context, 0).putExtra(EXTRA_INITIAL_TYPE, 3);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_editor);
        pageError = com.fimtale.ui.PageErrorView.wrap(findViewById(R.id.editorForm));
        com.fimtale.utils.EditorWindowStyle.apply(this);
        model = new ViewModelProvider(this).get(EditorViewModel.class);
        captcha = new CaptchaDialogHost(this, CaptchaDialogFragment.RESULT_KEY,
                () -> model.captchaRequested, model::captchaResult);
        metadataVisible = state != null && state.getBoolean("metadata_visible");
        restorePositionPending = state != null;
        if (state != null) {
            restoredSelectionStart = state.getInt("editor_selection_start");
            restoredSelectionEnd = state.getInt("editor_selection_end");
            restoredScrollY = state.getInt("editor_scroll_y");
        }
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> leave());
        androidx.appcompat.widget.Toolbar.OnMenuItemClickListener menuActions = item -> {
            if (item.getItemId() == R.id.action_more) { moreMenu.show(); return true; }
            if (model.busy) { toast(getString(R.string.editor_wait_operation)); return true; }
            if (item.getItemId() == R.id.action_editor_metadata) { showMetadata(true); }
            else if (item.getItemId() == R.id.action_editor_preview) showPreview();
            else if (item.getItemId() == R.id.action_editor_submit) submit();
            else if (item.getItemId() == R.id.action_save_draft) { collect(); model.saveDraft(() -> toast(model.message)); }
            else if (item.getItemId() == R.id.action_draft_conflict) {
                new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.editor_draft_conflict_title))
                        .setMessage(getString(R.string.editor_draft_conflict_message))
                        .setNegativeButton(getString(R.string.common_cancel), null)
                        .setNeutralButton(getString(R.string.editor_use_online_draft), (d, w) -> model.resolveDraftConflict(false))
                        .setPositiveButton(getString(R.string.editor_use_local_draft), (d, w) -> model.resolveDraftConflict(true)).show();
            }
            else if (item.getItemId() == R.id.action_discard_draft) {
                new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.editor_discard_draft_title))
                        .setMessage(getString(R.string.editor_discard_draft_message))
                        .setNegativeButton(getString(R.string.common_cancel), null).setPositiveButton(getString(R.string.editor_discard_draft), (d, w) -> model.discardDraft()).show();
            }
            else return false;
            return true;
        };
        toolbar.setOnMenuItemClickListener(menuActions);
        moreMenu = new com.fimtale.ui.BottomSheetMenu(this, R.menu.editor_more_menu, menuActions::onMenuItemClick);
        title = findViewById(R.id.editorTitle); body = findViewById(R.id.editorBody);
        body.setParagraphIndentEnabled(true);
        body.setLinkClickListener((editor, link) -> {
            if (canInsertFormat() && editor == formatBody() && !getSupportFragmentManager().isStateSaved()
                    && getSupportFragmentManager().findFragmentByTag(EditorFormatDialog.TAG) == null)
                EditorFormatDialog.editLink(this, link).showNow(getSupportFragmentManager(), EditorFormatDialog.TAG);
        });
        body.setMarkdownClickListener((editor, markdown) -> {
            if (canInsertFormat() && editor == formatBody() && !getSupportFragmentManager().isStateSaved()
                    && getSupportFragmentManager().findFragmentByTag(EditorFormatDialog.TAG) == null)
                EditorFormatDialog.editMarkdown(this, markdown).showNow(getSupportFragmentManager(), EditorFormatDialog.TAG);
        });
        body.setSourceVisible(state != null && state.getBoolean("source_visible"));
        body.setEditingListener(() -> {
            updateEditingControls();
            body.removeCallbacks(updateStatistics); body.postDelayed(updateStatistics, 150);
        });
        updateSourceButton();
        intro = findViewById(R.id.editorIntro); cover = findViewById(R.id.editorCover);
        originLink = findViewById(R.id.editorOriginLink); prequel = findViewById(R.id.editorPrequel);
        type = findViewById(R.id.editorType); length = findViewById(R.id.editorLength);
        rating = findViewById(R.id.editorRating); origin = findViewById(R.id.editorOrigin); publish = findViewById(R.id.editorPublish);
        options(type, getResources().getStringArray(R.array.editor_work_types));
        options(length, getResources().getStringArray(R.array.editor_length_options));
        options(rating, getResources().getStringArray(R.array.editor_rating_options));
        options(origin, getResources().getStringArray(R.array.editor_origin_options));
        options(publish, getResources().getStringArray(R.array.editor_publish_options));
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { collect(); }
            public void afterTextChanged(Editable s) {}
        };
        for (EditText field : new EditText[]{title, body, intro, cover, originLink, prequel}) field.addTextChangedListener(watcher);
        ((MaterialCheckBox) findViewById(R.id.editorHandbook)).setOnCheckedChangeListener((button, checked) -> collect());
        android.widget.AdapterView.OnItemSelectedListener selected = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { collect(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        };
        for (Spinner spinner : new Spinner[]{type, length, rating, origin, publish}) spinner.setOnItemSelectedListener(selected);
        findViewById(R.id.editorBold).setOnClickListener(v -> wrap("b"));
        findViewById(R.id.editorUndo).setOnClickListener(v -> restoreHistory(false));
        findViewById(R.id.editorRedo).setOnClickListener(v -> restoreHistory(true));
        findViewById(R.id.editorItalic).setOnClickListener(v -> wrap("i"));
        findViewById(R.id.editorQuote).setOnClickListener(v -> wrap("quote"));
        findViewById(R.id.editorSpoiler).setOnClickListener(v -> wrap("spoiler"));
        findViewById(R.id.editorEmoji).setOnClickListener(v -> FtemojiPicker.show(this,
                name -> insertAtCaret(formatBody(), ":ftemoji_" + name + ":")));
        findViewById(R.id.editorImage).setOnClickListener(v -> imagePicker.launch("image/*"));
        findViewById(R.id.editorUploadCover).setOnClickListener(v -> coverPicker.launch("image/*"));
        findViewById(R.id.editorSource).setOnClickListener(v -> {
            body.setSourceVisible(!body.isSourceVisible()); updateSourceButton();
        });
        findViewById(R.id.editorMoreFormats).setOnClickListener(v -> {
            if (canInsertFormat() && !getSupportFragmentManager().isStateSaved()
                    && getSupportFragmentManager().findFragmentByTag(EditorFormatDialog.TAG) == null)
                EditorFormatDialog.create(this).showNow(getSupportFragmentManager(), EditorFormatDialog.TAG);
        });
        findViewById(R.id.editorAddTags).setOnClickListener(v -> {
            tagPicker = new TagPickerDialog(this, model.document.tags, model.document.tagGroups, () -> { model.changed(); renderTags(); });
            tagPicker.show();
        });
        findViewById(R.id.editorOpenHandbook).setOnClickListener(v -> DialogHelper.openSite(this, "/work/4"));
        findViewById(R.id.editorRetry).setOnClickListener(v -> {
            if (model.needsLogin) login.launch(new Intent(this, LoginActivity.class)); else model.load();
        });
        findViewById(R.id.editorExam).setOnClickListener(v -> {
            if (!model.busy) { collect(); qualification.launch(new Intent(this, com.fimtale.account.ExamActivity.class)); }
        });
        findViewById(R.id.editorSubmit).setOnClickListener(v -> submit());
        findViewById(R.id.editorMetadataDone).setOnClickListener(v -> showMetadata(false));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leave(); }
        });
        model.changes.observe(this, ignored -> render());
        if (!model.initialized && state == null && !UserPreferences.isLoggedIn(this)) login.launch(new Intent(this, LoginActivity.class));
        else if (!model.initialized && UserPreferences.isLoggedIn(this)) initialize();
    }
    private void initialize() {
        int workId = getIntent().getIntExtra(EXTRA_WORK_ID, 0), chapterId = getIntent().getIntExtra(EXTRA_CHAPTER_ID, -1);
        if (workId < 0 || chapterId < -1 || (chapterId >= 0 && workId == 0)) { finish(); return; }
        if (!getIntent().hasExtra(EXTRA_DRAFT_ID) && (workId == 0 || chapterId == 0))
            getIntent().putExtra(EXTRA_DRAFT_ID, java.util.UUID.randomUUID().toString());
        model.initialize(workId, chapterId, getIntent().getStringExtra(EXTRA_DRAFT_ID),
                getIntent().getIntExtra(EXTRA_INITIAL_TYPE, 1));
    }
    private void options(Spinner spinner, String... labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
    }
    private void render() {
        if (model.finished && !model.busy) {
            toast(model.completionMessage); setResult(RESULT_OK);
            if (model.workId == 0) {
                startActivity(new Intent(this, TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, model.savedId));
            }
            finish(); return;
        }
        boolean chapter = model.isChapter();
        updateToolbar();
        ((TextView) findViewById(R.id.editorStatus)).setText(model.message);
        findViewById(R.id.editorProgress).setVisibility(model.busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.editorRetry).setVisibility(model.error && !model.loadError && !model.needsExam ? View.VISIBLE : View.GONE);
        findViewById(R.id.editorExam).setVisibility(model.needsExam && !model.busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.editorForm).setVisibility(model.ready ? View.VISIBLE : View.GONE);
        findViewById(R.id.editorWorkFields).setVisibility(chapter ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.editorHint)).setText(chapter ? getString(R.string.editor_chapter_hint)
                : getString(R.string.editor_preface_hint));
        ((TextView) findViewById(R.id.editorSubmit)).setText(chapter ? (model.chapterId > 0 ? getString(R.string.editor_save_chapter) : getString(R.string.editor_publish_chapter))
                : (model.workId > 0 ? getString(R.string.editor_save_work) : getString(R.string.editor_publish_work)));
        setEnabled(findViewById(R.id.editorForm), model.ready && !model.busy);
        if (model.loadError && !model.busy) pageError.show(model.message, () -> {
            if (model.needsLogin) login.launch(new Intent(this, LoginActivity.class)); else model.load();
        }, model.ready);
        else pageError.hide();
        for (int i = 0; i < toolbar.getMenu().size(); i++) toolbar.getMenu().getItem(i).setEnabled(!model.busy && model.initialized);
        toolbar.getMenu().findItem(R.id.action_editor_metadata).setEnabled(model.ready && !model.busy);
        toolbar.getMenu().findItem(R.id.action_editor_submit).setEnabled(model.ready && !model.busy);
        for (int i = 0; i < moreMenu.getMenu().size(); i++)
            moreMenu.getMenu().getItem(i).setEnabled(!model.busy && model.initialized);
        moreMenu.getMenu().findItem(R.id.action_draft_conflict).setVisible(model.syncConflict);
        if (moreMenu.isShowing()) moreMenu.refresh();
        if (model.document != null) {
            binding = true;
            boolean announcement = model.allowAnnouncement || model.document.work.type == 4;
            if (announcement != typeHasAnnouncement) {
                typeHasAnnouncement = announcement;
                if (announcement) options(type, getResources().getStringArray(R.array.editor_work_types_all)); else options(type, getResources().getStringArray(R.array.editor_work_types));
                type.setSelection(Math.max(0, model.document.work.type - 1));
            }
            if (boundVersion != model.documentVersion) {
                boolean wasBound = boundVersion >= 0;
                boundVersion = model.documentVersion;
                EditorDocument d = model.document;
                String source = chapter ? d.chapter.content : d.work.preface;
                if (source == null) source = "";
                int start = wasBound ? Math.max(0, body.getSelectionStart())
                        : model.bodyHistory.current() == null ? 0 : model.bodyHistory.current().selectionStart;
                int end = wasBound ? Math.max(start, body.getSelectionEnd())
                        : model.bodyHistory.current() == null ? start : model.bodyHistory.current().selectionEnd;
                int scrollY = wasBound ? findViewById(R.id.editorScroll).getScrollY() : 0;
                body.setUndoHistory(null);
                if (model.historyDocument != d) {
                    model.bodyHistory.reset(source, 0, 0); model.historyDocument = d;
                    start = end = 0;
                } else if (model.bodyHistory.current() != null && !model.bodyHistory.current().text.equals(source)) {
                    model.bodyHistory.breakGroup();
                    model.bodyHistory.record(model.bodyHistory.current().text, source, start, end, start, end,
                            android.os.SystemClock.uptimeMillis());
                    model.bodyHistory.breakGroup();
                }
                title.setText(chapter ? d.chapter.title : d.work.title); body.setText(source);
                body.setUndoHistory(model.bodyHistory);
                if (restorePositionPending) {
                    start = restoredSelectionStart; end = restoredSelectionEnd; scrollY = restoredScrollY;
                    restorePositionPending = false;
                }
                int safeStart = Math.max(0, Math.min(body.length(), start));
                body.setSelection(safeStart, Math.max(safeStart, Math.min(body.length(), end)));
                final int savedScroll = scrollY, version = boundVersion;
                body.post(() -> {
                    if (boundVersion == version) findViewById(R.id.editorScroll).scrollTo(0, savedScroll);
                });
                intro.setText(d.work.intro); cover.setText(d.work.cover); originLink.setText(d.work.originLink); prequel.setText(d.prequelText);
                ((MaterialCheckBox) findViewById(R.id.editorHandbook)).setChecked(d.handbookAccepted);
                type.setSelection(Math.max(0, d.work.type - 1)); length.setSelection(d.work.length);
                rating.setSelection(d.work.rating); origin.setSelection(d.work.origin); publish.setSelection(d.work.publish);
                renderTags();
            }
            binding = false;
            enableClassification();
        }
        findViewById(R.id.editorWritingPanel).setVisibility(metadataVisible ? View.GONE : View.VISIBLE);
        findViewById(R.id.editorMetadataPanel).setVisibility(metadataVisible ? View.VISIBLE : View.GONE);
        renderMetadataSheetState();
        updateEditingControls();
        body.removeCallbacks(updateStatistics); body.postDelayed(updateStatistics, 150);
        captcha.sync();
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        captcha.sync();
    }
    private void enableClassification() {
        if (model.document == null) return;
        boolean enabled = !model.busy && model.document.work.type != 4;
        for (Spinner spinner : new Spinner[]{length, rating, origin, publish}) spinner.setEnabled(enabled);
    }
    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            setEnabled(((ViewGroup) view).getChildAt(i), enabled);
    }
    private String text(EditText field) { return field.getText().toString(); }
    private void collect() {
        if (binding || !model.ready || model.busy || model.document == null) return;
        EditorDocument d = model.document;
        if (model.isChapter()) { d.chapter.title = text(title); d.chapter.content = text(body); }
        else {
            d.work.title = text(title); d.work.preface = text(body); d.work.intro = text(intro);
            d.work.cover = text(cover); d.work.originLink = text(originLink); d.prequelText = text(prequel);
            d.handbookAccepted = ((MaterialCheckBox) findViewById(R.id.editorHandbook)).isChecked();
            d.work.type = type.getSelectedItemPosition() + 1;
            d.work.length = length.getSelectedItemPosition(); d.work.rating = rating.getSelectedItemPosition();
            d.work.origin = origin.getSelectedItemPosition(); d.work.publish = publish.getSelectedItemPosition();
            if (d.work.type == 4) {
                d.work.length = d.work.rating = d.work.origin = d.work.publish = 0;
                binding = true;
                for (Spinner spinner : new Spinner[]{length, rating, origin, publish}) spinner.setSelection(0);
                binding = false;
            }
            d.syncTags(); enableClassification();
        }
        model.changed();
        ((TextView) findViewById(R.id.editorStatus)).setText(model.message);
        updateToolbar();
    }
    private void updateToolbar() {
        if (toolbar == null) return;
        String name = model.document == null ? "" : model.isChapter() ? model.document.chapter.title : model.document.work.title;
        toolbar.setTitle(metadataVisible ? (model.isChapter() ? getString(R.string.editor_chapter_metadata) : getString(R.string.editor_metadata))
                : WorkInput.blank(name) ? (model.isChapter() ? getString(R.string.editor_unnamed_chapter) : getString(R.string.editor_unnamed_work)) : name);
        toolbar.getMenu().findItem(R.id.action_editor_metadata).setTitle(metadataVisible ? getString(R.string.editor_return_body) : model.isChapter() ? getString(R.string.editor_chapter_metadata) : getString(R.string.editor_metadata));
        toolbar.getMenu().findItem(R.id.action_editor_submit).setTitle(
                (model.isChapter() ? model.chapterId > 0 : model.workId > 0)
                        ? R.string.editor_save_action : R.string.editor_submit_action);
    }
    private void showMetadata(boolean visible) {
        if (!visible) {
            if (metadataSheet != null) metadataSheet.dismiss();
            else metadataVisible = false;
            return;
        }
        if (!model.ready || model.busy || (metadataSheet != null && metadataSheet.isShowing())) return;
        collect();
        View focused = getCurrentFocus();
        if (focused != null) {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        metadataSheetView = getLayoutInflater().inflate(R.layout.dialog_editor_metadata, null);
        bindMetadataSheet(metadataSheetView);
        metadataSheet = new BottomSheetDialog(this);
        metadataSheet.setContentView(metadataSheetView);
        metadataSheet.setOnShowListener(dialog -> {
            metadataSheet.getBehavior().setSkipCollapsed(true);
            metadataSheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        metadataSheet.setOnDismissListener(dialog -> {
            copyMetadataFromSheet();
            metadataSheet = null;
            metadataSheetView = null;
            metadataVisible = false;
            if (!model.busy && !model.finished) model.saveDraft(null);
        });
        metadataSheet.show();
    }

    private void bindMetadataSheet(View sheet) {
        EditText sheetTitle = sheet.findViewById(R.id.editorTitle);
        EditText sheetIntro = sheet.findViewById(R.id.editorIntro);
        EditText sheetCover = sheet.findViewById(R.id.editorCover);
        EditText sheetOriginLink = sheet.findViewById(R.id.editorOriginLink);
        EditText sheetPrequel = sheet.findViewById(R.id.editorPrequel);
        Spinner sheetType = sheet.findViewById(R.id.editorType);
        Spinner sheetLength = sheet.findViewById(R.id.editorLength);
        Spinner sheetRating = sheet.findViewById(R.id.editorRating);
        Spinner sheetOrigin = sheet.findViewById(R.id.editorOrigin);
        Spinner sheetPublish = sheet.findViewById(R.id.editorPublish);
        boolean announcement = model.allowAnnouncement || (model.document != null && model.document.work.type == 4);
        options(sheetType, announcement ? getResources().getStringArray(R.array.editor_work_types_all) : getResources().getStringArray(R.array.editor_work_types));
        options(sheetLength, getResources().getStringArray(R.array.editor_length_options));
        options(sheetRating, getResources().getStringArray(R.array.editor_rating_options));
        options(sheetOrigin, getResources().getStringArray(R.array.editor_origin_options));
        options(sheetPublish, getResources().getStringArray(R.array.editor_publish_options));
        binding = true;
        sheetTitle.setText(title.getText()); sheetIntro.setText(intro.getText()); sheetCover.setText(cover.getText());
        sheetOriginLink.setText(originLink.getText()); sheetPrequel.setText(prequel.getText());
        sheetType.setSelection(type.getSelectedItemPosition()); sheetLength.setSelection(length.getSelectedItemPosition());
        sheetRating.setSelection(rating.getSelectedItemPosition()); sheetOrigin.setSelection(origin.getSelectedItemPosition());
        sheetPublish.setSelection(publish.getSelectedItemPosition());
        sheet.findViewById(R.id.editorWorkFields).setVisibility(model.isChapter() ? View.GONE : View.VISIBLE);
        ((TextView) sheet.findViewById(R.id.editorHint)).setText(model.isChapter()
                ? getString(R.string.editor_chapter_hint)
                : getString(R.string.editor_preface_hint));
        ((TextView) sheet.findViewById(R.id.editorSubmit)).setText(model.isChapter()
                ? (model.chapterId > 0 ? getString(R.string.editor_save_chapter) : getString(R.string.editor_publish_chapter))
                : (model.workId > 0 ? getString(R.string.editor_save_work) : getString(R.string.editor_publish_work)));
        ((MaterialCheckBox) sheet.findViewById(R.id.editorHandbook)).setChecked(((MaterialCheckBox) findViewById(R.id.editorHandbook)).isChecked());
        binding = false;
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { copyMetadataFromSheet(); }
            public void afterTextChanged(Editable s) {}
        };
        for (EditText field : new EditText[]{sheetTitle, sheetIntro, sheetCover, sheetOriginLink, sheetPrequel}) field.addTextChangedListener(watcher);
        android.widget.AdapterView.OnItemSelectedListener selected = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { if (!binding) copyMetadataFromSheet(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        };
        for (Spinner spinner : new Spinner[]{sheetType, sheetLength, sheetRating, sheetOrigin, sheetPublish}) spinner.setOnItemSelectedListener(selected);
        ((MaterialCheckBox) sheet.findViewById(R.id.editorHandbook)).setOnCheckedChangeListener((button, checked) -> { if (!binding) copyMetadataFromSheet(); });
        sheet.findViewById(R.id.editorAddTags).setOnClickListener(v -> {
            tagPicker = new TagPickerDialog(this, model.document.tags, model.document.tagGroups, () -> { model.changed(); renderTagsIn(sheet); });
            tagPicker.show();
        });
        sheet.findViewById(R.id.editorOpenHandbook).setOnClickListener(v -> DialogHelper.openSite(this, "/work/4"));
        sheet.findViewById(R.id.editorUploadCover).setOnClickListener(v -> coverPicker.launch("image/*"));
        sheet.findViewById(R.id.editorSubmit).setOnClickListener(v -> submit());
        sheet.findViewById(R.id.editorMetadataDone).setOnClickListener(v -> showMetadata(false));
        renderTagsIn(sheet);
        renderMetadataSheetState();
    }

    private void renderMetadataSheetState() {
        if (metadataSheetView == null) return;
        setEnabled(metadataSheetView, model.ready && !model.busy);
        ((TextView) metadataSheetView.findViewById(R.id.editorMetadataStatus)).setText(model.message);
        metadataSheetView.findViewById(R.id.editorMetadataProgress).setVisibility(model.busy ? View.VISIBLE : View.GONE);
    }

    private void copyMetadataFromSheet() {
        if (binding || model.busy || model.finished || metadataSheetView == null || model.document == null) return;
        View sheet = metadataSheetView;
        EditText sheetTitle = sheet.findViewById(R.id.editorTitle);
        EditText sheetIntro = sheet.findViewById(R.id.editorIntro);
        EditText sheetCover = sheet.findViewById(R.id.editorCover);
        EditText sheetOriginLink = sheet.findViewById(R.id.editorOriginLink);
        EditText sheetPrequel = sheet.findViewById(R.id.editorPrequel);
        Spinner sheetType = sheet.findViewById(R.id.editorType);
        Spinner sheetLength = sheet.findViewById(R.id.editorLength);
        Spinner sheetRating = sheet.findViewById(R.id.editorRating);
        Spinner sheetOrigin = sheet.findViewById(R.id.editorOrigin);
        Spinner sheetPublish = sheet.findViewById(R.id.editorPublish);
        binding = true;
        title.setText(sheetTitle.getText()); intro.setText(sheetIntro.getText()); cover.setText(sheetCover.getText());
        originLink.setText(sheetOriginLink.getText()); prequel.setText(sheetPrequel.getText());
        type.setSelection(sheetType.getSelectedItemPosition()); length.setSelection(sheetLength.getSelectedItemPosition());
        rating.setSelection(sheetRating.getSelectedItemPosition()); origin.setSelection(sheetOrigin.getSelectedItemPosition());
        publish.setSelection(sheetPublish.getSelectedItemPosition());
        ((MaterialCheckBox) findViewById(R.id.editorHandbook)).setChecked(((MaterialCheckBox) sheet.findViewById(R.id.editorHandbook)).isChecked());
        binding = false;
        collect();
    }

    private void renderTagsIn(View root) {
        ChipGroup group = root.findViewById(R.id.editorTags); group.removeAllViews();
        model.document.tags.forEach((id, name) -> {
            Chip chip = new Chip(this); chip.setText(name); chip.setCloseIcon(MdiIcons.drawable(this, "close")); chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> { if (!model.busy) { model.document.tags.remove(id); model.document.tagGroups.remove(id); model.changed(); renderTagsIn(root); } });
            group.addView(chip);
        });
    }
    private void renderTags() {
        ChipGroup group = findViewById(R.id.editorTags); group.removeAllViews();
        model.document.tags.forEach((id, name) -> {
            Chip chip = new Chip(this); chip.setText(name); chip.setCloseIcon(MdiIcons.drawable(this, "close")); chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> {
                if (model.busy) return;
                model.document.tags.remove(id); model.document.tagGroups.remove(id); model.changed(); renderTags();
            });
            group.addView(chip);
        });
    }
    private void submit() {
        if (!model.ready || model.busy) return;
        copyMetadataFromSheet();
        collect();
        title.setError(null); intro.setError(null); prequel.setError(null);
        if (metadataSheetView != null) {
            for (int fieldId : new int[]{R.id.editorTitle, R.id.editorIntro, R.id.editorPrequel})
                MdiIcons.setError((EditText) metadataSheetView.findViewById(fieldId), null);
        }
        if ((model.isChapter() || model.document.work.type != 3) && WorkInput.blank(text(title))) {
            showMetadataError(R.id.editorTitle, getString(R.string.editor_title_required)); return;
        }
        if (!model.isChapter()) {
            if (model.document.work.type != 3 && WorkInput.blank(text(intro))) {
                showMetadataError(R.id.editorIntro, getString(R.string.editor_intro_required)); return;
            }
            try {
                String value = model.document.prequelText.trim();
                model.document.work.prequelId = value.isEmpty() ? 0 : Integer.valueOf(value);
            } catch (NumberFormatException e) { showMetadataError(R.id.editorPrequel, getString(R.string.editor_work_id_invalid)); return; }
            if (!model.document.handbookAccepted) { showMetadata(true); toast(getString(R.string.editor_handbook_required)); return; }
        }
        if (model.document.submissionUncertain) {
            new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.editor_submission_uncertain_title))
                    .setMessage(getString(R.string.editor_resubmit_message))
                    .setNegativeButton(getString(R.string.common_cancel), null).setNeutralButton(getString(R.string.editor_view_published), (d, w) -> checkPublished())
                    .setPositiveButton(getString(R.string.editor_confirm_resubmit), (d, w) -> model.prepareSubmit()).show();
        } else model.prepareSubmit();
    }
    private void showMetadataError(int fieldId, String message) {
        showMetadata(true);
        if (metadataSheetView != null) {
            EditText field = metadataSheetView.findViewById(fieldId);
            if (field != null) { MdiIcons.setError(field, message); field.requestFocus(); }
        }
    }
    private void checkPublished() {
        if (model.workId > 0) DialogHelper.openSite(this, "/work/" + model.workId);
        else startActivity(new Intent(this, UserDetailActivity.class)
                .putExtra(UserDetailActivity.EXTRA_USERNAME, UserPreferences.getUserName(this)));
    }
    private void wrap(String tag) {
        BbCodeEditText target = formatBody();
        int start = Math.max(0, Math.min(target.getSelectionStart(), target.getSelectionEnd()));
        int end = Math.max(start, Math.max(target.getSelectionStart(), target.getSelectionEnd()));
        String selected = target.getText().subSequence(start, end).toString();
        applyFormat(BbCodeInsertion.at(target.getText().toString(), start, end, BbCodeInsertion.wrap(tag, selected, tag.equals("quote"))));
    }
    @Override public BbCodeEditText formatBody() {
        com.fimtale.editor.BbCodeEditorLayout layout = findViewById(R.id.editorVisualLayout);
        return layout == null ? body : layout.activeEditor();
    }
    @Override public int formatVersion() { return model.documentVersion; }
    @Override public boolean canInsertFormat() { return model.ready && !model.busy; }
    @Override public void applyFormat(BbCodeInsertion.Edit edit) {
        if (!canInsertFormat()) return;
        BbCodeEditText target = formatBody();
        model.bodyHistory.breakGroup();
        target.beginBatchEdit();
        try {
            target.getText().replace(edit.start, edit.end, edit.replacement);
            target.requestFocus(); target.setSelection(edit.selectionStart, edit.selectionEnd);
        } finally { target.endBatchEdit(); model.bodyHistory.breakGroup(); }
    }
    private void insertAtCaret(EditText field, String value) {
        int start = Math.max(0, field.getSelectionStart());
        int end = Math.max(start, field.getSelectionEnd());
        model.bodyHistory.breakGroup();
        field.beginBatchEdit();
        try {
            field.getText().replace(start, end, value);
            field.requestFocus();
            field.setSelection(Math.min(field.length(), start + value.length()));
        } finally { field.endBatchEdit(); model.bodyHistory.breakGroup(); }
    }
    private void updateSourceButton() {
        com.google.android.material.button.MaterialButton button = findViewById(R.id.editorSource);
        button.setIcon(MdiIcons.drawable(this, body.isSourceVisible() ? "format-text" : "code-tags"));
        button.setContentDescription(getString(body.isSourceVisible() ? R.string.editor_hide_source_description : R.string.editor_show_source_description));
        button.setTooltipText(button.getContentDescription());
        button.setSelected(body.isSourceVisible());
        button.setStateDescription(getString(body.isSourceVisible() ? R.string.editor_source_mode_active : R.string.editor_visual_mode_active));
    }

    private void updateEditingControls() {
        if (body == null || model == null) return;
        boolean enabled = canInsertFormat();
        findViewById(R.id.editorUndo).setEnabled(enabled && model.bodyHistory.canUndo());
        findViewById(R.id.editorRedo).setEnabled(enabled && model.bodyHistory.canRedo());
        findViewById(R.id.editorCharacterCount).setVisibility(model.ready && !metadataVisible ? View.VISIBLE : View.GONE);
    }

    private void updateEditorStatistics() {
        if (body == null || !model.ready) return;
        String source = body.getText().toString();
        if (!source.equals(countedSource)) {
            countedSource = source; characterCount = EditorTextStats.count(source);
        }
        BbCodeEditText active = formatBody();
        int start = Math.min(active.getSelectionStart(), active.getSelectionEnd());
        int end = Math.max(active.getSelectionStart(), active.getSelectionEnd());
        TextView count = findViewById(R.id.editorCharacterCount);
        count.setText(start >= 0 && end > start
                ? getString(R.string.editor_selection_count, characterCount, EditorTextStats.count(active.getText().toString(), start, end))
                : getString(R.string.editor_character_count, characterCount));
    }

    private void restoreHistory(boolean redo) {
        if (!canInsertFormat()) return;
        ((com.fimtale.editor.BbCodeEditorLayout) findViewById(R.id.editorVisualLayout)).finishCellEditing();
        body.restoreHistory(redo);
        updateEditingControls();
    }

    private void showPreview() {
        if (!canInsertFormat() || previewDialog != null) return;
        model.bodyHistory.breakGroup();
        BbCodeEditText active = formatBody();
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(body);
        boolean wasTyping = insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
        if (keyboard != null) keyboard.hideSoftInputFromWindow(active.getWindowToken(), 0);
        previewDialog = EditorPreviewDialog.show(this, body.getText().toString(),
                body.getTextSize() / getResources().getDisplayMetrics().scaledDensity);
        previewDialog.setOnDismissListener(dialog -> {
            previewDialog = null;
            if (!isFinishing() && !isDestroyed() && canInsertFormat()) {
                BbCodeEditText target = formatBody();
                target.requestFocus();
                if (wasTyping && keyboard != null) target.post(() -> {
                    if (!isFinishing() && target.isAttachedToWindow()) keyboard.showSoftInput(target, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                });
            }
        });
    }

    @Override public boolean onKeyShortcut(int keyCode, android.view.KeyEvent event) {
        if (event.isCtrlPressed() && !event.isAltPressed() && canInsertFormat() && formatBody().hasFocus()) {
            if (keyCode == android.view.KeyEvent.KEYCODE_B) { wrap("b"); return true; }
            if (keyCode == android.view.KeyEvent.KEYCODE_I) { wrap("i"); return true; }
            if (keyCode == android.view.KeyEvent.KEYCODE_P) { showPreview(); return true; }
            if (keyCode == android.view.KeyEvent.KEYCODE_S) { collect(); model.saveDraft(null); return true; }
        }
        return super.onKeyShortcut(keyCode, event);
    }
    private void leave() {
        if (model.busy) { toast(getString(R.string.editor_wait_before_exit)); return; }
        if (metadataSheet != null && metadataSheet.isShowing()) { metadataSheet.dismiss(); return; }
        if (metadataVisible) { showMetadata(false); return; }
        collect(); model.saveDraft(this::finish);
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    @Override protected void onStop() { super.onStop(); model.persistDraft(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("metadata_visible", metadataVisible); out.putBoolean("source_visible", body.isSourceVisible());
        int[] selection = ((com.fimtale.editor.BbCodeEditorLayout) findViewById(R.id.editorVisualLayout)).sourceSelection();
        out.putInt("editor_selection_start", selection[0]); out.putInt("editor_selection_end", selection[1]);
        out.putInt("editor_scroll_y", findViewById(R.id.editorScroll).getScrollY());
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        if (body != null) { body.removeCallbacks(updateStatistics); body.setEditingListener(null); }
        if (previewDialog != null) { previewDialog.setOnDismissListener(null); previewDialog.dismiss(); }
        if (moreMenu != null) moreMenu.dismiss();
        if (tagPicker != null) tagPicker.dismiss();
        if (metadataSheet != null) metadataSheet.dismiss();
        super.onDestroy();
    }
}
