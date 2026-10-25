package com.fimtale;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.fimtale.model.AuthorInfo;
import com.fimtale.notifications.*;
import com.fimtale.ui.PullToRefresh;
import com.fimtale.ui.ShimmerSkeletonView;
import com.fimtale.utils.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import io.noties.markwon.Markwon;
import java.util.*;

public abstract class InboxActivity extends AppCompatActivity {
    private InboxViewModel model;
    private MaterialToolbar toolbar;
    private MaterialCardView header;
    private TabLayout tabs;
    private RecyclerView list;
    private TextInputEditText input;
    private MaterialButton send;
    private View composer;
    private InboxAdapter adapter;
    private Markwon renderer;
    private boolean binding;
    private long boundConversation;
    private int bottomVersion;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() { model.poll(!list.canScrollVertically(1)); handler.postDelayed(this, 30000); }
    };
    protected int pageLayout() { return R.layout.activity_notifications; }
    protected Inbox.Conversation initialConversation() { return null; }
    protected boolean standaloneConversation() { return false; }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(pageLayout()); EditorWindowStyle.apply(this);
        model = new ViewModelProvider(this).get(InboxViewModel.class);
        renderer = BbCodeRendering.create(this);
        toolbar = findViewById(R.id.toolbar); header = findViewById(R.id.toolbarContainer);
        tabs = findViewById(R.id.inboxTabs); list = findViewById(R.id.inboxList);
        input = findViewById(R.id.inboxInput); composer = findViewById(R.id.inboxComposer); send = findViewById(R.id.inboxSend);
        toolbar.setNavigationOnClickListener(v -> back());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        for (int title : Inbox.TITLES) tabs.addTab(tabs.newTab().setText(title));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (!binding) { model.select(tab.getPosition()); render(); }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        list.setLayoutManager(new LinearLayoutManager(this)); list.setItemAnimator(null);
        adapter = new InboxAdapter(); list.setAdapter(adapter);
        if (header != null) header.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> findViewById(R.id.inboxContent).setPadding(0, b + dp(16), 0, 0));
        PullToRefresh.attach(list, () -> model.load(false), () -> !model.loading && !model.mutating && !model.needsLogin);
        send.setOnClickListener(v -> model.send(input.getText() == null ? "" : input.getText().toString()));
        MaterialButton emoji = findViewById(R.id.inboxEmoji); emoji.setIcon(MdiIcons.drawable(this, "emoticon-outline"));
        emoji.setOnClickListener(v -> com.fimtale.ui.FtemojiPicker.show(this, name -> {
            Editable text = input.getText(); if (text == null) return;
            int start = Math.max(0, input.getSelectionStart()), end = Math.max(start, input.getSelectionEnd());
            text.replace(start, end, ":ftemoji_" + name + ":");
        }));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                if (!binding && model.conversation != null) model.drafts.put(model.conversation.id, s.toString());
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        model.changes.observe(this, ignored -> render());
    }
    private void back() {
        if (model.mutating) { Toast.makeText(this, getString(R.string.common_submitting_wait), Toast.LENGTH_SHORT).show(); return; }
        if (standaloneConversation() || model.conversation == null) finish(); else model.back();
    }
    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        model.connect(initialConversation());
        if (!standaloneConversation() && model.tab == 3 && model.loaded && model.conversation == null) model.load(false);
        handler.postDelayed(poll, 30000);
    }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void render() {
        if (isDestroyed()) return;
        if (list.isComputingLayout()) { list.post(this::render); return; }
        binding = true;
        boolean chat = model.conversation != null;
        String title = chat ? model.conversation.title(UserPreferences.getUserId(this)) : getString(R.string.inbox_title);
        TextView conversationTitle = findViewById(R.id.conversationTitle);
        if (conversationTitle != null) conversationTitle.setText(title); else toolbar.setTitle(title);
        tabs.setVisibility(chat ? View.GONE : View.VISIBLE);
        list.setBackgroundColor(chat ? getColor(R.color.message_chat_background)
                : MaterialColors.getColor(list, android.R.attr.colorBackground));
        for (int i = 0; i < Inbox.KEYS.length; i++) {
            TabLayout.Tab tab = tabs.getTabAt(i); int count = model.counts.getOrDefault(Inbox.KEYS[i], 0);
            if (count > 0) { tab.getOrCreateBadge().setNumber(count); tab.getOrCreateBadge().setMaxCharacterCount(3); }
            else tab.removeBadge();
        }
        tabs.selectTab(tabs.getTabAt(model.tab));
        composer.setVisibility(chat && !model.needsLogin ? View.VISIBLE : View.GONE);
        send.setEnabled(!model.loading && !model.mutating);
        input.setEnabled(!model.mutating);
        if (chat) {
            String draft = model.drafts.getOrDefault(model.conversation.id, "");
            if (boundConversation != model.conversation.id || !draft.equals(String.valueOf(input.getText()))) {
                input.setText(draft); input.setSelection(draft.length());
            }
            boundConversation = model.conversation.id;
        } else boundConversation = 0;
        LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
        int first = layout.findFirstVisibleItemPosition();
        Object anchor = first >= 0 && first < adapter.rows.size() ? adapter.rows.get(first) : null;
        View firstView = layout.findViewByPosition(first);
        int offset = firstView == null ? 0 : firstView.getTop() - list.getPaddingTop();
        if (!model.loading) PullToRefresh.finish(list);
        if (!model.loading || !PullToRefresh.isRefreshing(list)) adapter.rebuild();
        if (anchor instanceof Inbox.Message && bottomVersion == model.scrollToBottom) {
            long id = ((Inbox.Message) anchor).id;
            for (int i = 0; i < adapter.rows.size(); i++) if (adapter.rows.get(i) instanceof Inbox.Message && ((Inbox.Message) adapter.rows.get(i)).id == id) {
                layout.scrollToPositionWithOffset(i, offset); break;
            }
        }
        if (chat && bottomVersion != model.scrollToBottom) { bottomVersion = model.scrollToBottom; list.scrollToPosition(Math.max(0, adapter.getItemCount() - 1)); }
        if (model.notice != null) { Toast.makeText(this, model.notice, Toast.LENGTH_SHORT).show(); model.notice = null; }
        binding = false;
    }
    private void open(Inbox.Notice n) {
        String path = n.path(UserPreferences.getUserId(this), model.manager); if (path == null) return;
        if (n.type == 21 && path.startsWith("/user/")) startActivity(new Intent(this, ReviewQueueActivity.class).putExtra(ReviewQueueActivity.EXTRA_REVIEW_ID, (int) Inbox.number(n.object(), "id")));
        else if (n.type == 22 && path.startsWith("/user/reports")) startActivity(new Intent(this, ReportDetailActivity.class)
                .putExtra(ReportDetailActivity.EXTRA_REPORT_ID, Inbox.number(n.object(), "id")));
        else if (path.matches("/work/[0-9]+")) startActivity(new Intent(this, TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, Integer.parseInt(path.substring(6))));
        else if (!com.fimtale.community.CommunityLinks.openComment(this, path)) startActivity(new Intent(this, SiteActivity.class).putExtra(SiteActivity.EXTRA_PATH, path));
    }
    private TextView text(LinearLayout row, String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurface));
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(0, dp(4), 0, dp(4)); row.addView(view); return view;
    }
    private MaterialButton button(LinearLayout row, String label, Runnable action) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        button.setText(label); button.setOnClickListener(v -> action.run()); button.setEnabled(!model.mutating && !model.loading);
        row.addView(button); return button;
    }
    private void user(LinearLayout row, AuthorInfo author) {
        if (author == null) { text(row, getString(R.string.app_name), 15, true); return; }
        LinearLayout identity = new LinearLayout(this); identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this); identity.addView(avatar, new LinearLayout.LayoutParams(dp(36), dp(36)));
        Glide.with(this).load(author.getAvatar()).circleCrop().into(avatar);
        TextView name = text(identity, "  " + author.getUserName(), 15, true);
        identity.setOnClickListener(v -> startActivity(new Intent(this, UserDetailActivity.class).putExtra(UserDetailActivity.EXTRA_USERNAME, author.getUserName())));
        row.addView(identity);
    }
    private String date(String iso) {
        if (iso == null) return "";
        try { return android.text.format.DateUtils.getRelativeTimeSpanString(java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli(), System.currentTimeMillis(), 60000).toString(); }
        catch (RuntimeException e) { return iso; }
    }
    private void loadAvatar(ImageView view, AuthorInfo author) {
        view.setContentDescription(author == null ? getString(R.string.profile_avatar_description) : getString(R.string.profile_named_avatar_description, author.getUserName()));
        Glide.with(this).load(author == null ? null : author.getAvatar())
                .placeholder(MdiIcons.drawable(this, "account")).circleCrop().into(view);
    }
    private String chatTime(String iso) {
        if (iso == null) return "";
        try {
            java.time.ZonedDateTime time = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault());
            java.time.LocalDate today = java.time.LocalDate.now();
            String clock = time.format(java.time.format.DateTimeFormatter.ofPattern(getString(R.string.common_time)));
            if (time.toLocalDate().equals(today)) return clock;
            if (time.toLocalDate().equals(today.minusDays(1))) return getString(R.string.inbox_yesterday, clock);
            return time.format(java.time.format.DateTimeFormatter.ofPattern(time.getYear() == today.getYear() ? getString(R.string.inbox_date_current_year) : getString(R.string.inbox_date_full)));
        } catch (RuntimeException ignored) { return date(iso); }
    }
    private void bindConversation(LinearLayout row, Inbox.Conversation conversation) {
        View view = getLayoutInflater().inflate(R.layout.item_inbox_conversation, row, false);
        String self = UserPreferences.getUserId(this);
        ((TextView) view.findViewById(R.id.conversationName)).setText(conversation.title(self));
        TextView time = view.findViewById(R.id.conversationTime);
        TextView preview = view.findViewById(R.id.conversationPreview);
        Inbox.Message latest = conversation.latest_message;
        time.setText(latest == null ? "" : chatTime(latest.created_at));
        String content = latest == null || latest.message == null ? getString(R.string.inbox_no_messages)
                : com.fimtale.utils.BbCodeText.plainPreview(renderer.toMarkdown(BbCode.toMarkdown(latest.message)));
        boolean group = conversation.participants != null && conversation.participants.size() > 2;
        if (group && latest != null && latest.user != null) content = getString(R.string.inbox_sender_preview, latest.user.getUserName(), content);
        preview.setText(content);
        view.findViewById(R.id.conversationUnread).setVisibility(conversation.latest_unread_message_id == null ? View.GONE : View.VISIBLE);
        FrameLayout avatars = view.findViewById(R.id.conversationAvatars);
        List<AuthorInfo> others = new ArrayList<>();
        if (conversation.participants != null) for (AuthorInfo user : conversation.participants)
            if (user != null && !String.valueOf(user.getId()).equals(self)) others.add(user);
        int count = Math.max(1, Math.min(4, others.size()));
        for (int i = 0; i < count; i++) {
            ImageView avatar = new ImageView(this);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(count == 1 ? 52 : 25), dp(count == 1 ? 52 : 25));
            if (count > 1) { params.leftMargin = dp((i % 2) * 27); params.topMargin = dp((i / 2) * 27 + (count == 2 ? 13 : 0)); }
            avatars.addView(avatar, params); loadAvatar(avatar, others.isEmpty() ? null : others.get(i));
        }
        view.setOnClickListener(v -> startActivity(ConversationActivity.intent(this, conversation)));
        row.addView(view);
    }
    private void bindMessage(LinearLayout row, Inbox.Message message, Inbox.Message previous) {
        View view = getLayoutInflater().inflate(R.layout.item_inbox_message, row, false);
        boolean mine = message.user != null && String.valueOf(message.user.getId()).equals(UserPreferences.getUserId(this));
        TextView time = view.findViewById(R.id.messageTime);
        boolean showTime = previous == null;
        if (previous != null) {
            try {
                long gap = java.time.Duration.between(java.time.OffsetDateTime.parse(previous.created_at),
                        java.time.OffsetDateTime.parse(message.created_at)).toMinutes();
                showTime = Math.abs(gap) >= 5;
            } catch (RuntimeException ignored) { showTime = true; }
        }
        time.setText(chatTime(message.created_at)); time.setVisibility(showTime ? View.VISIBLE : View.GONE);
        LinearLayout line = view.findViewById(R.id.messageLine); line.setGravity((mine ? Gravity.END : Gravity.START) | Gravity.TOP);
        ImageView left = view.findViewById(R.id.messageAvatarLeft), right = view.findViewById(R.id.messageAvatarRight);
        left.setVisibility(mine ? View.GONE : View.VISIBLE); right.setVisibility(mine ? View.VISIBLE : View.GONE);
        ImageView avatar = mine ? right : left; loadAvatar(avatar, message.user);
        if (message.user != null) avatar.setOnClickListener(v -> startActivity(new Intent(this, UserDetailActivity.class)
                .putExtra(UserDetailActivity.EXTRA_USERNAME, message.user.getUserName())));
        int availableWidth = list.getWidth() > 0 ? list.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int maxWidth = Math.max(dp(80), Math.min(availableWidth - dp(112), (int) (availableWidth * 0.72f)));
        TextView sender = view.findViewById(R.id.messageSender);
        sender.setMaxWidth(maxWidth);
        sender.setText(message.user == null ? getString(R.string.profile_user_label) : message.user.getUserName());
        boolean group = model.conversation != null && model.conversation.participants != null && model.conversation.participants.size() > 2;
        sender.setVisibility(!mine && group ? View.VISIBLE : View.GONE);
        TextView bubble = view.findViewById(R.id.messageBubble); bubble.setMaxWidth(maxWidth);
        bubble.setBackgroundResource(mine ? R.drawable.bg_message_sent : R.drawable.bg_message_received);
        bubble.setTextColor(MaterialColors.getColor(row, mine ? com.google.android.material.R.attr.colorOnPrimary : com.google.android.material.R.attr.colorOnSurface));
        BbCodeRendering.setText(renderer, bubble, message.message == null ? "" : message.message);
        row.addView(view);
    }
    private final class InboxAdapter extends RecyclerView.Adapter<InboxAdapter.Holder> {
        private final List<Object> rows = new ArrayList<>();
        void rebuild() {
            rows.clear();
            if (model.needsLogin) rows.add("login");
            else if (model.loading && !model.loaded) rows.add("loading");
            else {
                if (model.tab != 3) rows.add("summary");
                if (model.conversation != null) {
                    if (model.loading) rows.add("loading"); else if (model.more) rows.add("earlier");
                    rows.addAll(model.messages);
                }
                else if (model.tab == 3) rows.addAll(model.conversations);
                else rows.addAll(Inbox.groups(model.notices));
                if (!model.error.isEmpty()) rows.add("error");
                if (model.loading && model.conversation == null) rows.add("loading");
                else if (!model.loading && model.loaded && (model.conversation == null ? (model.tab == 3 ? model.conversations.isEmpty() : model.notices.isEmpty()) : model.messages.isEmpty())) rows.add("empty");
                if (model.more && model.conversation == null) rows.add("more");
            }
            notifyDataSetChanged();
        }
        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int position) {
            Object row = rows.get(position);
            return row instanceof Inbox.Conversation ? 1 : row instanceof Inbox.Message ? 2 : "loading".equals(row) ? 3 : 0;
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            MaterialCardView card = new MaterialCardView(InboxActivity.this);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2); params.setMargins(dp(16), dp(4), dp(16), dp(4)); card.setLayoutParams(params);
            card.setRadius(dp(16)); card.setCardElevation(0); card.setStrokeWidth(0);
            LinearLayout body = new LinearLayout(InboxActivity.this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(12), dp(16), dp(12)); card.addView(body);
            if (type != 0) {
                params.setMargins(0, 0, 0, 0); card.setLayoutParams(params);
                card.setRadius(0); body.setPadding(0, 0, 0, 0);
            }
            return new Holder(card, body);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            LinearLayout row = holder.body; row.setGravity(Gravity.START); row.removeAllViews(); Object item = rows.get(position);
            holder.card.setCardBackgroundColor(item instanceof Inbox.Message || "loading".equals(item) && model.conversation != null ? getColor(R.color.message_chat_background)
                    : MaterialColors.getColor(row, com.google.android.material.R.attr.colorSurface));
            if ("loading".equals(item) && (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
                holder.card.setCardBackgroundColor(MaterialColors.getColor(row, android.R.attr.colorBackground));
            }
            if (item instanceof String) {
                switch ((String)item) {
                    case "login": text(row, getString(R.string.inbox_login_message), 18, true); button(row, getString(R.string.login_title), () -> DialogHelper.openLogin(InboxActivity.this)); break;
                    case "loading":
                        ShimmerSkeletonView skeleton = new ShimmerSkeletonView(InboxActivity.this);
                        skeleton.setSkeletonLayout(model.conversation != null ? ShimmerSkeletonView.Layout.CHAT
                                : model.tab == 3 ? ShimmerSkeletonView.Layout.CONVERSATIONS : ShimmerSkeletonView.Layout.COMMENTS);
                        int height = model.loaded ? dp(160) : Math.max(dp(320), list.getHeight());
                        row.addView(skeleton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
                        break;
                    case "error": text(row, model.error, 14, false); button(row, getString(R.string.common_retry), model::retry); break;
                    case "empty": text(row, model.tab == 3 ? getString(R.string.inbox_no_conversations) : getString(R.string.inbox_no_notifications), 16, false); break;
                    case "more": button(row, getString(R.string.common_load_more), () -> model.load(true)); break;
                    case "earlier": button(row, getString(R.string.inbox_load_earlier), () -> model.load(true)); break;
                    case "summary":
                        if (model.tab != 3) {
                            int count = Inbox.unread(model.notices).size(); text(row, getString(R.string.inbox_page_unread, count), 13, false);
                            if (count > 0) button(row, getString(R.string.inbox_mark_all_read), () -> model.markRead(model.notices));
                        }
                        break;
                }
            } else if (item instanceof Inbox.Conversation) {
                bindConversation(row, (Inbox.Conversation) item);
            } else if (item instanceof Inbox.Message) {
                Inbox.Message previous = position > 0 && rows.get(position - 1) instanceof Inbox.Message
                        ? (Inbox.Message) rows.get(position - 1) : null;
                bindMessage(row, (Inbox.Message) item, previous);
            } else {
                @SuppressWarnings("unchecked") List<Inbox.Notice> group = (List<Inbox.Notice>)item;
                Inbox.Notice n = group.get(0);
                if (!Inbox.unread(group).isEmpty()) holder.card.setCardBackgroundColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorSurfaceVariant));
                for (Inbox.Notice event : group) user(row, event.sender);
                text(row, n.action(UserPreferences.getUserId(InboxActivity.this)), 14, false);
                if (n.path() != null) button(row, n.label(), () -> open(n));
                if (!n.preview().isEmpty()) {
                    TextView body = text(row, "", 14, false);
                    if (n.type == 23) renderer.setMarkdown(body, n.preview()); else BbCodeRendering.setText(renderer, body, n.preview());
                }
                if (n.type == 24 && Inbox.number(n.object(), "id") > 0) {
                    if (n.acted == null) { button(row, getString(R.string.common_accept), () -> model.invite(n, "accept")); button(row, getString(R.string.common_reject), () -> model.invite(n, "reject")); }
                    else text(row, n.acted.equals("accept") ? getString(R.string.inbox_invite_accepted) : getString(R.string.inbox_invite_rejected), 14, false);
                }
                text(row, date(n.created_at), 12, false);
                if (!Inbox.unread(group).isEmpty()) button(row, getString(R.string.inbox_mark_read), () -> model.markRead(group));
            }
        }
        final class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card; final LinearLayout body;
            Holder(MaterialCardView card, LinearLayout body) { super(card); this.card = card; this.body = body; }
        }
    }
}
