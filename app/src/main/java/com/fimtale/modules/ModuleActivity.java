package com.fimtale.modules;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import com.fimtale.LoginActivity;
import com.fimtale.R;
import com.fimtale.ui.BottomSheetMenu;
import com.fimtale.ui.ButtonLoadingIcon;
import com.fimtale.utils.MdiIcons;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.*;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.*;

/** Small shared native page shell. The footer is stable while lists refresh (including while the IME is open). */
public abstract class ModuleActivity extends AppCompatActivity {
    protected ModuleModel model;
    protected LinearLayout content, footer;
    protected MaterialToolbar toolbar;
    protected TabLayout tabs;
    protected NestedScrollView scroll;
    protected com.fimtale.ui.PullRefreshLayout swipeRefresh;
    private final List<ButtonLoadingIcon> icons = new ArrayList<>();
    private final List<android.app.Dialog> dialogs = new ArrayList<>();
    private boolean queued;
    private boolean bindingTabs, scrollReset;
    private boolean centeredPager;
    private int selectedTab, sessionRevision;
    private long communityRevision;
    protected void bind(ModuleModel state, int title) {
        model = state; setContentView(R.layout.activity_modules);
        com.fimtale.utils.EditorWindowStyle.apply(this);
        sessionRevision = state.sessionRevision;
        communityRevision = com.fimtale.community.Community.version();
        toolbar = findViewById(R.id.modulesToolbar); toolbar.setTitle(title);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        if (usesToolbarRefresh()) toolbar.getMenu().add(R.string.community_refresh).setOnMenuItemClickListener(item -> { if (!model.busy()) refresh(); return true; });
        content = findViewById(R.id.modulesContent); footer = findViewById(R.id.modulesFooter);
        tabs = findViewById(R.id.modulesTabs); scroll = findViewById(R.id.modulesScroll);
        swipeRefresh = findViewById(R.id.modulesSwipeRefresh);
        swipeRefresh.setEnabled(supportsPullToRefresh());
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> model.busy() || scroll.canScrollVertically(-1));
        swipeRefresh.setOnRefreshListener(() -> {
            if (model.busy()) { swipeRefresh.setRefreshing(false); return; }
            refresh();
        });
        model.changes.observe(this, value -> scheduleRender());
    }
    protected abstract void render();
    protected abstract void refresh();
    protected boolean usesHeaderLoadingIndicator() { return true; }
    protected boolean supportsPullToRefresh() { return false; }
    protected boolean usesToolbarRefresh() { return true; }
    /** Adds neutral content placeholders for initial page loads. */
    protected final void loadingBlocks(int count) {
        int surface = com.google.android.material.color.MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurface);
        int variant = com.google.android.material.color.MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurfaceVariant);
        for (int i = 0; i < count; i++) {
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(16), dp(16), dp(16));
            GradientDrawable background = new GradientDrawable(); background.setColor(variant); background.setCornerRadius(dp(16)); card.setBackground(background);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.bottomMargin = dp(16); content.addView(card, cardParams);
            skeletonBlock(card, surface, i == 0 ? 0.58f : 0.42f, 18);
            skeletonBlock(card, surface, 0.94f, 12); skeletonBlock(card, surface, 0.78f, 12);
            if (i == 0) skeletonBlock(card, surface, 0.35f, 36);
        }
    }
    private void skeletonBlock(LinearLayout parent, int color, float widthFraction, int heightDp) {
        View block = new View(this); GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(6)); block.setBackground(shape);
        int width = (int) (getResources().getDisplayMetrics().widthPixels * widthFraction);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(heightDp)); params.bottomMargin = dp(10); parent.addView(block, params);
    }
    protected void sessionChanged() { refresh(); }
    protected void afterRender() { }
    protected final void startPage() { if (!model.started) { model.started = true; refresh(); } }
    @Override protected void onResume() {
        super.onResume();
        if (model == null) return;
        if (model.connect()) { sessionRevision = model.sessionRevision; sessionChanged(); }
        else if (!model.busy() && communityRevision != com.fimtale.community.Community.version()) refresh();
        communityRevision = com.fimtale.community.Community.version();
    }
    @Override protected void onPause() { communityRevision = com.fimtale.community.Community.version(); super.onPause(); }
    protected final void scheduleRender() {
        if (queued || content == null) return; queued = true;
        content.post(() -> {
            queued = false; if (isFinishing() || isDestroyed()) return;
            if (sessionRevision != model.sessionRevision) {
                sessionRevision = model.sessionRevision;
                for (android.app.Dialog dialog : dialogs) dialog.dismiss();
                dialogs.clear(); sessionChanged();
            }
            for (ButtonLoadingIcon icon : icons) icon.close(); icons.clear();
            View focus = getCurrentFocus();
            Object focusTag = focus instanceof EditText ? focus.getTag() : null;
            int selection = focus instanceof EditText ? ((EditText) focus).getSelectionStart() : 0;
            int y = scrollReset ? 0 : scroll.getScrollY(); scrollReset = false;
            content.removeAllViews(); render();
            if (focusTag != null) {
                View replacement = content.findViewWithTag(focusTag);
                if (replacement instanceof EditText && replacement.isEnabled()) {
                    replacement.requestFocus();
                    ((EditText) replacement).setSelection(Math.max(0, Math.min(selection, ((EditText) replacement).length())));
                }
            }
            scroll.post(() -> { if (!isDestroyed()) { scroll.scrollTo(0, y); afterRender(); } });
            findViewById(R.id.modulesProgress).setVisibility(usesHeaderLoadingIndicator() && (model.busy() || model.loading()) ? View.VISIBLE : View.GONE);
            if (swipeRefresh.isRefreshing() && !model.busy() && !model.loading()) swipeRefresh.setRefreshing(false);
            if (!model.notice.isEmpty()) { toast(model.notice); model.notice = ""; }
        });
    }
    protected boolean loginRequired() {
        if (model.signedIn()) return false;
        text(content, R.string.modules_login); button(content, R.string.login_title, "login", false, false,
                () -> startActivity(new Intent(this, LoginActivity.class))); return true;
    }
    protected void errors() {
        for (String message : new LinkedHashSet<>(model.errors.values())) text(content, message);
        if (!model.errors.isEmpty()) button(content, R.string.modules_retry, "refresh", false, false, this::refresh);
    }
    protected void tabLabels(int array, int selected, IntConsumer change) {
        tabs.setVisibility(View.VISIBLE);
        for (String label : getResources().getStringArray(array)) tabs.addTab(tabs.newTab().setText(label));
        selectTab(selected);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (bindingTabs) return;
                if (model.busy()) { selectTab(selectedTab); return; }
                selectedTab = tab.getPosition(); scrollReset = true; change.accept(selectedTab);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
    }
    protected void selectTab(int selected) {
        bindingTabs = true; selectedTab = Math.max(0, Math.min(selected, tabs.getTabCount() - 1));
        tabs.selectTab(tabs.getTabAt(selectedTab)); bindingTabs = false;
    }
    protected final void centerTabs() {
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_CENTER);
    }
    protected final void centerPagers() { centeredPager = true; }
    protected LinearLayout column(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(16);
        parent.addView(box, lp); return box;
    }
    protected LinearLayout row(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2)); return box;
    }
    protected TextView text(LinearLayout parent, int id) { return text(parent, getString(id)); }
    protected TextView text(LinearLayout parent, CharSequence value) {
        TextView view = new TextView(this); view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        view.setText(value); view.setPadding(0, dp(6), 0, dp(6)); view.setTextIsSelectable(true);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    protected TextView heading(LinearLayout parent, CharSequence value) {
        TextView view = text(parent, value); view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium); return view;
    }
    protected MaterialButton button(LinearLayout parent, int title, String key, boolean active, boolean secured, Runnable action) {
        return button(parent, getString(title), key, active, secured, action);
    }
    protected MaterialButton button(LinearLayout parent, CharSequence title, String key, boolean active, boolean secured, Runnable action) {
        MaterialButton view = new MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        view.setText(title); view.setMinHeight(dp(48)); view.setEnabled(!model.busy());
        view.setOnClickListener(v -> {
            if (model.busy()) return;
            if (secured && !model.signedIn()) startActivity(new Intent(this, LoginActivity.class)); else action.run();
        });
        ButtonLoadingIcon loading = new ButtonLoadingIcon(); icons.add(loading);
        view.setSelected(active);
        loading.setIcon(view, active ? MdiIcons.drawable(this, "check-circle") : null);
        if (model.action.equals(key)) loading.start(view);
        parent.addView(view, new LinearLayout.LayoutParams(-2, -2)); return view;
    }
    protected MaterialButton reaction(LinearLayout parent, CharSequence title, String key, String icon, boolean active, Runnable action) {
        MaterialButton view = button(parent, title, key, active, true, action);
        // Keep the common busy drawable; a new binding supplies the filled/outline icon.
        ButtonLoadingIcon loading = icons.get(icons.size() - 1); loading.bind(view, icon, active, model.action.equals(key)); return view;
    }
    protected TextInputEditText field(LinearLayout parent, int label, String value, int inputType, Consumer<String> changed) {
        TextInputLayout layout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle);
        layout.setHint(getString(label)); layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
        TextInputEditText input = new TextInputEditText(layout.getContext()); input.setInputType(inputType);
        input.setTag("module_field:" + label); input.setSaveEnabled(false);
        input.setText(value); input.setEnabled(!model.busy());
        if ((inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) { input.setMinLines(3); input.setMaxLines(7); }
        layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(12); parent.addView(layout, lp);
        watch(input, changed); return input;
    }
    public static void watch(EditText input, Consumer<String> changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changed.accept(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }
    protected Spinner select(LinearLayout parent, List<String> labels, int selected, IntConsumer change) {
        Spinner view = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); view.setAdapter(adapter);
        final int[] lastSelection = {Math.max(0, Math.min(selected, labels.size() - 1))};
        view.setSelection(lastSelection[0]); view.setEnabled(!model.busy()); parent.addView(view, new LinearLayout.LayoutParams(-1, dp(52)));
        view.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (position != lastSelection[0]) { lastSelection[0] = position; change.accept(position); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        }); return view;
    }
    protected void pager(int page, int total, IntConsumer load) {
        if (total <= 1) return;
        LinearLayout line = row(content);
        if (centeredPager) line.setGravity(android.view.Gravity.CENTER);
        MaterialButton previous = button(line, R.string.common_previous_page, "prev", false, false, () -> { scrollReset = true; load.accept(page - 1); });
        previous.setEnabled(!model.busy() && !model.loading("page") && page > 1);
        TextView count = text(line, getString(R.string.common_pagination, page, total)); count.setGravity(android.view.Gravity.CENTER);
        count.setLayoutParams(centeredPager ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton next = button(line, R.string.common_next_page, "next", false, false, () -> { scrollReset = true; load.accept(page + 1); });
        next.setEnabled(!model.busy() && !model.loading("page") && page < total);
    }
    protected void confirm(String message, Runnable action) {
        String owner = model.session();
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setMessage(message)
                .setNegativeButton(R.string.common_cancel, null).setPositiveButton(R.string.common_confirm,
                        (d, which) -> { if (model.sameSession(owner) && !model.busy()) action.run(); }).show();
        dialogs.add(dialog);
    }
    protected void menu(String[] titles, IntConsumer selected) {
        String owner = model.session(); BottomSheetMenu dialog = new BottomSheetMenu(this, titles,
                index -> { if (model.sameSession(owner) && !model.busy()) selected.accept(index); });
        dialogs.add(dialog); dialog.show();
    }
    protected void form(String key, int title, Bundle... fields) {
        if (model.busy() || getSupportFragmentManager().isStateSaved() || getSupportFragmentManager().findFragmentByTag(ModuleFormDialog.TAG) != null) return;
        ModuleFormDialog.show(this, key, title, fields);
    }
    protected boolean onForm(String key, JsonObject values) { return false; }
    protected void toast(int id) { toast(getString(id)); }
    protected void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    protected int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() {
        for (ButtonLoadingIcon icon : icons) icon.close(); for (android.app.Dialog d : dialogs) d.dismiss(); super.onDestroy();
    }
}
