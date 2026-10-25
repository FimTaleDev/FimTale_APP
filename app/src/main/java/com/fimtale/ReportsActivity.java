package com.fimtale;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.report.*;
import com.fimtale.ui.FtemojiPicker;
import com.fimtale.ui.PullToRefresh;
import com.fimtale.ui.ShimmerSkeletonView;
import com.fimtale.utils.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import io.noties.markwon.Markwon;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Reporter-facing list and details. No moderation controls are exposed here. */
public abstract class ReportsActivity extends AppCompatActivity {
    public static final String EXTRA_REPORT_ID = "report_id";
    private MyReportsViewModel model;
    private RecyclerView list;
    private TabLayout tabs;
    private MaterialToolbar toolbar;
    private MaterialCardView header;
    private ObjectAnimator headerElevation;
    private boolean headerRaised;
    private TextInputEditText input;
    private MaterialButton send;
    private View composer;
    private ReportsAdapter adapter;
    private Markwon renderer;
    private boolean binding, debugExpanded;
    private long displayedReport = -1;
    private int displayedPage = -1, displayedStatus = -1;
    private android.os.Parcelable listPosition;

    protected boolean isDetailPage() { return false; }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_my_reports); EditorWindowStyle.apply(this);
        model = new ViewModelProvider(this).get(MyReportsViewModel.class);
        if (isDetailPage()) model.initializeDetail(getIntent().getLongExtra(EXTRA_REPORT_ID, 0));
        else model.initialize(0);
        renderer = BbCodeRendering.create(this);
        toolbar = findViewById(R.id.toolbar); toolbar.setNavigationOnClickListener(v -> back());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        header = findViewById(R.id.toolbarContainer);
        header.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> updateHeaderLayout());
        list = findViewById(R.id.myReportsList); list.setLayoutManager(new LinearLayoutManager(this)); list.setItemAnimator(null);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) { updateHeaderElevation(); }
        });
        tabs = findViewById(R.id.myReportsTabs);
        for (String title : getResources().getStringArray(R.array.report_status_options)) tabs.addTab(tabs.newTab().setText(title));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { if (!binding) { model.filter(tab.getPosition()); render(); } }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        input = findViewById(R.id.myReportReply); send = findViewById(R.id.myReportSend); composer = findViewById(R.id.myReportComposer);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (!binding) model.setDraft(s.toString()); }
            @Override public void afterTextChanged(Editable text) {}
        });
        send.setOnClickListener(v -> model.send());
        setupFormatting();
        adapter = new ReportsAdapter(); list.setAdapter(adapter);
        PullToRefresh.attach(list, model::refresh, () -> !model.loading && !model.submitting && !model.needsLogin);
        if (state != null) { listPosition = state.getParcelable("listPosition"); debugExpanded = state.getBoolean("debugExpanded"); }
        model.changes.observe(this, ignored -> render());
    }
    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        model.connect();
        if (!isDetailPage() && model.loaded) model.refresh();
    }
    private void back() {
        if (model.submitting) { Toast.makeText(this, getString(R.string.common_sending_wait), Toast.LENGTH_SHORT).show(); return; }
        finish();
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putParcelable("listPosition", listPosition); out.putBoolean("debugExpanded", debugExpanded); super.onSaveInstanceState(out);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void render() {
        if (isDestroyed()) return;
        if (list.isComputingLayout()) { list.post(this::render); return; }
        binding = true;
        boolean detail = isDetailPage() && model.selected != null;
        toolbar.setTitle(isDetailPage() ? getString(R.string.report_number, getIntent().getLongExtra(EXTRA_REPORT_ID, 0)) : getString(R.string.my_reports_title));
        tabs.setVisibility(isDetailPage() ? View.GONE : View.VISIBLE); tabs.selectTab(tabs.getTabAt(model.status));
        composer.setVisibility(detail && !model.needsLogin && model.selected.status == MyReport.PENDING ? View.VISIBLE : View.GONE);
        send.setEnabled(model.canReply()); send.setText(model.submitting ? getString(R.string.common_sending) : getString(R.string.report_send_message)); input.setEnabled(!model.submitting);
        LinearLayout formatting = findViewById(R.id.myReportFormatting);
        for (int i=0;i<formatting.getChildCount();i++) formatting.getChildAt(i).setEnabled(!model.submitting);
        String draft = model.draft();
        if (!draft.equals(String.valueOf(input.getText()))) { input.setText(draft); input.setSelection(draft.length()); }
        TextView replyError = findViewById(R.id.myReportReplyError); replyError.setText(model.replyError);
        replyError.setVisibility(model.replyError.isEmpty() ? View.GONE : View.VISIBLE);
        long nextReport = detail ? model.selected.id : 0;
        if (nextReport != displayedReport && displayedReport == 0) listPosition = list.getLayoutManager().onSaveInstanceState();
        updateHeaderLayout();
        if (!model.loading) PullToRefresh.finish(list);
        if (!model.loading || !PullToRefresh.isRefreshing(list)) adapter.rebuild();
        if (nextReport != displayedReport || displayedPage != model.page || displayedStatus != model.status) {
            if (nextReport == 0 && displayedReport > 0 && listPosition != null && displayedPage == model.page && displayedStatus == model.status)
                list.getLayoutManager().onRestoreInstanceState(listPosition);
            else list.scrollToPosition(0);
        }
        displayedReport = nextReport; displayedPage = model.page; displayedStatus = model.status;
        if (model.notice != null) { Toast.makeText(this, model.notice, Toast.LENGTH_SHORT).show(); model.notice = null; }
        binding = false;
        list.post(this::updateHeaderElevation);
    }
    private void updateHeaderLayout() {
        if (list == null) return;
        boolean detail = isDetailPage();
        int inset = header.getBottom() > 0 ? header.getBottom() + dp(16) : dp(88);
        // In details the list occupies the full viewport and scrolls behind the floating card.
        findViewById(R.id.myReportsContent).setPadding(0, detail ? 0 : inset, 0, 0);
        list.setPadding(list.getPaddingLeft(), detail ? inset : 0, list.getPaddingRight(), list.getPaddingBottom());
        header.setCardBackgroundColor(MaterialColors.getColor(header, android.R.attr.colorBackground));
        updateHeaderElevation();
    }
    private void updateHeaderElevation() {
        boolean raised = isDetailPage() && list.canScrollVertically(-1);
        if (raised == headerRaised) return;
        headerRaised = raised;
        if (headerElevation != null) headerElevation.cancel();
        headerElevation = ObjectAnimator.ofFloat(header, "cardElevation", header.getCardElevation(), raised ? dp(4) : 0);
        headerElevation.setDuration(200); headerElevation.start();
    }
    @Override protected void onDestroy() {
        if (headerElevation != null) headerElevation.cancel();
        super.onDestroy();
    }
    private void setupFormatting() {
        LinearLayout bar = findViewById(R.id.myReportFormatting);
        formatButton(bar, getString(R.string.editor_format_bold_action), "format-bold", () -> surround("[b]", "[/b]"));
        formatButton(bar, getString(R.string.editor_tool_italic), "format-italic", () -> surround("[i]", "[/i]"));
        formatButton(bar, getString(R.string.editor_format_quote), "format-quote-close", () -> surround("[quote]", "[/quote]"));
        formatButton(bar, getString(R.string.editor_format_link), "link", () -> surround("[url=https://]", "[/url]"));
        formatButton(bar, getString(R.string.emoji_title), "emoticon-outline", () -> FtemojiPicker.show(this, name -> surround(":ftemoji_" + name + ":", "")));
        formatButton(bar, getString(R.string.common_preview), "eye-outline", () -> {
            TextView preview = new TextView(this); preview.setPadding(dp(20), dp(16), dp(20), dp(16));
            BbCodeRendering.setText(renderer, preview, model.draft());
            ScrollView scroll = new ScrollView(this); scroll.addView(preview);
            SpoilerSpan.observe(new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.report_message_preview))
                    .setView(scroll).setPositiveButton(getString(R.string.common_close), null).show().getWindow());
        });
    }
    private void formatButton(LinearLayout bar, String label, String icon, Runnable action) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        button.setIcon(MdiIcons.drawable(this, icon)); button.setIconPadding(0); button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setContentDescription(label); button.setTooltipText(label); button.setMinWidth(0); button.setPadding(dp(12),0,dp(12),0);
        bar.addView(button, new LinearLayout.LayoutParams(dp(48),dp(48))); button.setOnClickListener(v -> action.run());
    }
    private void surround(String before, String after) {
        Editable text = input.getText(); if (text == null) return;
        int start = Math.max(0,input.getSelectionStart()), end = Math.max(start,input.getSelectionEnd());
        String selected = text.subSequence(start,end).toString(); text.replace(start,end,before+selected+after);
        input.requestFocus(); input.setSelection(start+before.length()+selected.length());
    }
    private String date(String value) {
        if (value == null || value.isEmpty()) return getString(R.string.common_missing_value);
        try { return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(getString(R.string.common_date_time))); }
        catch (RuntimeException ignored) { return value; }
    }
    private void openTarget(MyReport report) {
        String path = report.targetPath(); if (path == null) return;
        if (report.target_type == 1 && report.target_id <= Integer.MAX_VALUE)
            startActivity(new Intent(this, TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID,(int)report.target_id));
        else startActivity(new Intent(this, SiteActivity.class).putExtra(SiteActivity.EXTRA_PATH,path));
    }
    private TextView text(LinearLayout row, String value, int size, boolean bold) {
        TextView text = new TextView(this); text.setText(value == null ? "" : value); text.setTextSize(size);
        text.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurface));
        text.setPadding(0,dp(4),0,dp(4)); if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD); row.addView(text); return text;
    }
    private void button(LinearLayout row, String label, Runnable action) {
        MaterialButton button = new MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle);
        button.setText(label); button.setEnabled(!model.loading && !model.submitting); button.setOnClickListener(v -> action.run()); row.addView(button);
    }
    private void copy(String label, String value) {
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label,value));
        Toast.makeText(this,getString(R.string.common_copied),Toast.LENGTH_SHORT).show();
    }
    private final class ReportsAdapter extends RecyclerView.Adapter<ReportsAdapter.Holder> {
        final List<Object> rows = new ArrayList<>();
        void rebuild() {
            rows.clear();
            if (model.needsLogin) rows.add("login");
            else if (model.loading && !model.loaded) rows.add("loading");
            else {
                if (isDetailPage() && model.selected != null) {
                    rows.add("detail");
                    if (!model.selected.failures().isEmpty()) rows.add("debug");
                    rows.add("messages");
                    for (MyReport.Message message : model.selected.messages()) if (message != null) rows.add(message);
                    if (model.selected.status != MyReport.PENDING) rows.add("closed");
                } else if (isDetailPage()) {
                    if (model.loaded && !model.loading && model.error.isEmpty()) rows.add("unavailable");
                } else {
                    rows.addAll(model.reports);
                    if (!model.loading && model.loaded && model.reports.isEmpty()) rows.add("empty");
                    if (model.page > 1 || model.hasNext()) rows.add("pagination");
                }
                if (model.loading) rows.add("loading");
                if (!model.error.isEmpty()) rows.add("error");
            }
            notifyDataSetChanged();
        }
        @Override public int getItemCount() { return rows.size(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            MaterialCardView card = new MaterialCardView(ReportsActivity.this); card.setRadius(dp(16)); card.setCardElevation(0); card.setStrokeWidth(0);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1,-2); params.setMargins(dp(16),dp(4),dp(16),dp(4)); card.setLayoutParams(params);
            LinearLayout body = new LinearLayout(ReportsActivity.this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16),dp(12),dp(16),dp(12)); card.addView(body);
            return new Holder(card,body);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            LinearLayout row = holder.body; row.removeAllViews(); Object item = rows.get(position);
            if ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES) {
                ((MaterialCardView) holder.itemView).setCardBackgroundColor(MaterialColors.getColor(row,
                        "loading".equals(item) ? android.R.attr.colorBackground : com.google.android.material.R.attr.colorSurface));
            }
            holder.itemView.setOnClickListener(null); holder.itemView.setClickable(false); holder.itemView.setFocusable(false);
            if (item instanceof MyReport) {
                MyReport report = (MyReport)item;
                text(row,getString(R.string.report_status_heading, report.id, report.statusLabel()),17,true);
                text(row,getString(R.string.report_kind_target, report.kindLabel(), report.targetLabel()),14,false);
                text(row,getString(R.string.common_updated_at, date(report.updated_at)),12,false);
                holder.itemView.setFocusable(true); holder.itemView.setOnClickListener(v -> {
                    if (!model.loading) startActivity(new Intent(ReportsActivity.this, ReportDetailActivity.class).putExtra(EXTRA_REPORT_ID, report.id));
                });
            } else if (item instanceof MyReport.Message) {
                MyReport.Message message = (MyReport.Message)item;
                String user = message.user_id <= 0 ? getString(R.string.inbox_system) : message.username == null || message.username.isEmpty() ? getString(R.string.profile_user_number, message.user_id) : message.username;
                TextView author = text(row,getString(R.string.report_message_author, user, date(message.created_at)),12,false);
                if (message.user_id > 0) author.setOnClickListener(v -> startActivity(new Intent(ReportsActivity.this,SiteActivity.class).putExtra(SiteActivity.EXTRA_PATH,"/user/"+message.user_id)));
                BbCodeRendering.setText(renderer,text(row,"",15,false),message.content == null ? "" : message.content);
            } else switch ((String)item) {
                case "login": text(row,getString(R.string.report_login_message),17,true); button(row,getString(R.string.login_title),() -> DialogHelper.openLogin(ReportsActivity.this)); break;
                case "loading": ShimmerSkeletonView skeleton = new ShimmerSkeletonView(ReportsActivity.this); skeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.DRAFTS); row.addView(skeleton,new LinearLayout.LayoutParams(-1,dp(model.loaded?160:400))); break;
                case "error": text(row,model.error,14,false); button(row,getString(R.string.common_retry),model::retry); break;
                case "unavailable": text(row,getString(R.string.report_empty),18,true); text(row,getString(R.string.report_unavailable),14,false); break;
                case "empty": text(row,getString(R.string.report_empty),18,true); text(row,getString(R.string.report_empty_filtered),14,false); break;
                case "pagination":
                    text(row,getString(R.string.common_page_number, model.page),14,false);
                    LinearLayout navigation = new LinearLayout(ReportsActivity.this); row.addView(navigation);
                    if (model.page>1) button(navigation,getString(R.string.common_previous_page),() -> model.goToPage(model.page-1));
                    if (model.hasNext()) button(navigation,getString(R.string.common_next_page),() -> model.goToPage(model.page+1));
                    break;
                case "detail": detail(row,model.selected); break;
                case "messages": text(row,getString(R.string.report_message_history),16,true); if (model.selected.messages().isEmpty()) text(row,getString(R.string.inbox_no_messages),14,false); break;
                case "closed": text(row,getString(R.string.report_closed_message, model.selected.statusLabel()),14,false); break;
                case "debug": debug(row,model.selected); break;
            }
        }
        private void detail(LinearLayout row, MyReport report) {
            text(row,getString(R.string.report_status_heading, report.id, report.statusLabel()),18,true);
            text(row,getString(R.string.report_timestamps, date(report.created_at), date(report.updated_at)),12,false);
            text(row,report.kindLabel(),14,false);
            if (report.targetPath()!=null) button(row,report.targetLabel(),() -> openTarget(report)); else text(row,report.targetLabel(),14,false);
            if (report.payload != null && report.payload.content_snapshot != null && !report.payload.content_snapshot.isEmpty()) {
                text(row,getString(R.string.report_content_snapshot),16,true); BbCodeRendering.setText(renderer,text(row,"",15,false),report.payload.content_snapshot);
            }
        }
        private void debug(LinearLayout row, MyReport report) {
            button(row,getString(debugExpanded ? R.string.report_hide_debug : R.string.report_show_debug, report.failures().size()),() -> { debugExpanded=!debugExpanded; render(); });
            if (!debugExpanded) return;
            List<MyReport.Failure> failures = report.failures();
            for(int i=failures.size()-1;i>=0;i--) {
                MyReport.Failure failure=failures.get(i); if(failure==null) continue;
                String time=failure.ts==null?getString(R.string.common_missing_value):date(Instant.ofEpochMilli(failure.ts).atOffset(ZoneOffset.UTC).toString());
                TextView description=text(row,getString(R.string.report_debug_request, time, value(failure.path), value(failure.api_path), failure.status == 0 ? getString(R.string.error_network) : failure.status),12,false);
                description.setTextIsSelectable(true);
                if(failure.rid!=null&&!failure.rid.isEmpty()) { text(row,getString(R.string.report_debug_request_id, failure.rid),12,false).setTextIsSelectable(true); button(row,getString(R.string.report_copy_request_id),() -> copy(getString(R.string.report_rid_clipboard_label),failure.rid)); }
                if(failure.trace_id!=null&&!failure.trace_id.isEmpty()) { text(row,getString(R.string.report_debug_trace_id, failure.trace_id),12,false).setTextIsSelectable(true); button(row,getString(R.string.report_copy_trace_id),() -> copy(getString(R.string.report_trace_clipboard_label),failure.trace_id)); }
            }
        }
        private String value(String text) { return text==null||text.isEmpty()?getString(R.string.common_missing_value):text; }
        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout body;
            Holder(MaterialCardView card, LinearLayout body) { super(card); this.body=body; }
        }
    }
}
