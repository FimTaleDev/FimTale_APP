package com.fimtale.channels;

import android.animation.ObjectAnimator;
import android.content.*;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.ViewModelProvider;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.fimtale.*;
import com.fimtale.account.AccountActivity;
import com.fimtale.adapter.TopicAdapter;
import com.fimtale.model.Topic;
import com.fimtale.model.TopicViewItem;
import com.fimtale.modules.*;
import com.fimtale.network.SiteUrls;
import com.fimtale.report.*;
import com.fimtale.ui.ButtonLoadingIcon;
import com.fimtale.ui.FtemojiPicker;
import com.fimtale.ui.ImagePreview;
import com.fimtale.ui.ShimmerSkeletonView;
import com.fimtale.utils.BbCodeRendering;
import com.fimtale.utils.MdiIcons;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.*;
import io.noties.markwon.Markwon;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.fimtale.modules.ModuleApi.*;

/** Native channel browsing, discussion, collaboration, moderation and folder ballots. */
public class ChannelActivity extends ModuleActivity {
    public static final String EXTRA_ID="channel_id", EXTRA_COMMENT_ID="channel_comment_id";
    public static final String EXTRA_TAB="channel_tab", EXTRA_FOLDER_ID="channel_folder_id", EXTRA_BALLOT_ID="channel_ballot_id";
    private ChannelModel state;
    private Markwon renderer;
    private TextInputEditText input;
    private TextView context;
    private ImageView coverImage;
    private MaterialCardView toolbarContainer;
    private ObjectAnimator toolbarElevationAnimator;
    private boolean toolbarElevated;
    private String coverUrl;
    private final List<View> workCards = new ArrayList<>();
    private final List<View> commentRows = new ArrayList<>();
    private final List<ButtonLoadingIcon> commentIcons = new ArrayList<>();
    private MenuItem moreAction;
    private MaterialButton send,cancel,commentTags,emoji;
    private final ButtonLoadingIcon sendIcon=new ButtonLoadingIcon();
    private boolean bindingComposer;
    private String pendingCsv="";
    private final ActivityResultLauncher<String> exporter=registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),uri->{
        if(uri==null||pendingCsv.isEmpty())return;String csv=pendingCsv;pendingCsv="";
        new Thread(()->{
            boolean success=false;try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out!=null){out.write(csv.getBytes(StandardCharsets.UTF_8));success=true;}}catch(IOException|RuntimeException ignored){}
            boolean done=success;runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())toast(done?R.string.channel_ballot_exported:R.string.channel_ballot_export_failed);});
        },"channel-csv").start();
    });
    public static Intent intent(Context context,int id){return new Intent(context,ChannelActivity.class).putExtra(EXTRA_ID,id);}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);int id=getIntent().getIntExtra(EXTRA_ID,0);if(id<=0){finish();return;}
        state=new ViewModelProvider(this).get(ChannelModel.class);
        state.init(id,getIntent().getIntExtra(EXTRA_COMMENT_ID,0),getIntent().getIntExtra(EXTRA_TAB,0),
                getIntent().getIntExtra(EXTRA_FOLDER_ID,-1),getIntent().getIntExtra(EXTRA_BALLOT_ID,0));
        if(saved!=null)pendingCsv=saved.getString("csv","");
        bind(state,R.string.channels_title,R.layout.activity_channel);
        setupHeader();renderer=BbCodeRendering.create(this);tabLabels(R.array.channel_tabs,state.tab,state::select);centerTabs();centerPagers();composer();
        moreAction=toolbar.getMenu().add(R.string.common_more);
        moreAction.setIcon(MdiIcons.drawable(this,"dots-vertical"));
        moreAction.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        moreAction.setOnMenuItemClickListener(item->{showChannelMenu();return true;});
        startPage();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("csv",pendingCsv);}
    @Override protected void refresh(){state.refresh();}
    @Override protected boolean usesHeaderLoadingIndicator(){return false;}
    @Override protected boolean supportsPullToRefresh(){return true;}
    @Override protected boolean usesToolbarRefresh(){return false;}
    private void showChannelMenu() {
        if (!state.ready() || state.deleted() || state.busy()) return;
        menu(new String[]{getString(R.string.channel_report)}, selected -> {
            if (state.ready() && !state.deleted())
                ReportDialog.show(this, ReportRequest.CHANNEL, state.id, str(state.channel, "name"));
        });
    }
    private void setupHeader() {
        coverImage = findViewById(R.id.channelCoverImage);
        coverImage.setImageDrawable(MdiIcons.coverPlaceholder(this));
        toolbarContainer = findViewById(R.id.channelToolbarContainer);
        scroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (view, x, y, oldX, oldY) -> {
            boolean elevated = y > 0;
            if (elevated == toolbarElevated) return;
            toolbarElevated = elevated;
            if (toolbarElevationAnimator != null) toolbarElevationAnimator.cancel();
            toolbarElevationAnimator = ObjectAnimator.ofFloat(toolbarContainer, "cardElevation",
                    toolbarContainer.getCardElevation(), elevated ? dp(4) : 0);
            toolbarElevationAnimator.setDuration(200);
            toolbarElevationAnimator.start();
        });
    }
    private void renderHeader() {
        moreAction.setEnabled(state.ready() && !state.deleted() && !state.busy());
        toolbar.setTitle(state.ready() ? str(state.channel, "name") : getString(R.string.channels_title));
        coverImage.setContentDescription(state.ready()
                ? getString(R.string.channel_cover_image, str(state.channel, "name")) : null);
        String nextCover = state.ready() ? SiteUrls.media(str(state.channel, "cover_image")) : null;
        if (Objects.equals(coverUrl, nextCover)) return;
        coverUrl = nextCover;
        Glide.with(this).clear(coverImage);
        if (coverUrl == null) coverImage.setImageDrawable(MdiIcons.coverPlaceholder(this));
        else Glide.with(this).load(coverUrl).placeholder(MdiIcons.coverPlaceholder(this))
                .error(MdiIcons.coverPlaceholder(this)).into(coverImage);
        ImagePreview.bind(coverImage, coverUrl);
    }
    @Override protected void render(){
        clearWorkCards();
        clearCommentRows();
        selectTab(state.tab);renderHeader();
        renderComposer();errors();
        if(!state.ready()){if(state.loading())loadingBlocks(3);else loginRequired();return;}
        if(state.tab==0)overview();else if(state.tab==1)works();else if(state.tab==2)discussion();else if(state.tab==3)subscribers();else if(state.tab==4)manage();else ballots();
    }
    private void overview(){
        heading(content,str(state.channel,"name"));text(content,str(state.channel,"description"));
        text(content,getString(R.string.channel_stats,num(state.channel,"count_works"),num(state.channel,"count_user_fav"),num(state.channel,"count_comments")));
        if(state.deleted())text(content,R.string.channel_deleted);
        else{
            reaction(content,getString(state.faved()?R.string.channel_subscribed:R.string.channel_subscribe),"favorite",state.faved()?"bookmark":"bookmark-outline",state.faved(),()->state.react(true,0));
            LinearLayout votes=row(content);
            reaction(votes,getString(R.string.channel_upvote,num(state.channel,"count_upvote")),"vote:1",state.voted(1)?"thumb-up":"thumb-up-outline",state.voted(1),()->state.react(false,1));
            reaction(votes,getString(R.string.channel_downvote),"vote:2",state.voted(2)?"thumb-down":"thumb-down-outline",state.voted(2),()->state.react(false,2));
        }
        JsonObject invite=obj(obj(state.channel,"viewer"),"collaborator");String until=str(invite,"pending_until");
        if(!until.isEmpty()){
            text(content,getString(R.string.channel_pending_invite,until));
            if(state.inviteExpired())text(content,R.string.channel_invite_expired);
            else button(content,R.string.channel_accept_invite,"invite:accept",false,true,()->state.invite("accept"));
            button(content,R.string.channel_reject_invite,"invite:reject",false,true,()->state.invite("reject"));
        }
        rich(content,str(state.channel,"preface"));
        heading(content,getString(R.string.channel_collaborators));for(JsonElement user:array(state.channel,"collaborators"))user(content,obj(user));
        for(JsonElement value:state.folders){JsonObject f=obj(value);button(content,folderLabel(f),"folder",false,false,()->state.showFolder(num(f,"id")));}
        if(state.deleted()&&state.manager())recovery();
    }
    private void folderSelect(){
        List<String> labels=new ArrayList<>();List<Integer> ids=new ArrayList<>();labels.add(getString(R.string.channel_all_folders));ids.add(-1);labels.add(getString(R.string.channel_no_folder));ids.add(0);
        for(JsonElement value:state.folders){JsonObject f=obj(value);labels.add(folderLabel(f));ids.add(num(f,"id"));}
        select(content,labels,Math.max(0,ids.indexOf(state.folder)),i->state.folder(ids.get(i)));
    }
    private String folderLabel(JsonObject f){return getString(R.string.channel_folder_info,str(f,"name"),num(f,"count_works"),getString(bool(f,"is_public")?R.string.channel_public:R.string.channel_private));}
    private void works(){
        folderSelect();workSearchBar();
        if(state.canAdd())button(content,R.string.channel_add_work,"add_work",false,true,()->form("add_work",R.string.channel_add_work,ModuleFormDialog.field("work_id",R.string.channel_work_id,"")));
        JsonObject active=state.activeBallot();if(num(active,"id")>0){text(content,getString(R.string.channel_ballot_info,num(obj(active,"config"),"min_votes_per_user"),num(obj(active,"config"),"max_votes_per_user"),num(obj(active,"config"),"bits_cost")));button(content,R.string.channel_ballot_results,"ballot",false,false,()->state.openBallot(num(active,"id")));}
        if(state.rows().size()==0&&state.loading("page")&&!state.errors.containsKey("page")){loadingBlocks(3);return;}
        if(state.rows().size()==0&&!state.loading("page"))text(content,R.string.modules_empty);
        Gson gson=new Gson();
        for(JsonElement value:state.rows()){
            JsonObject work=obj(value);int id=num(work,"id");if(id<=0)continue;
            String title=str(work,"title");boolean pinned=contains(array(state.pageData,"pinned_work_ids"),id);
            Topic topic=gson.fromJson(work,Topic.class);
            if(pinned)topic.setTitle(getString(R.string.channel_pinned,title));
            View card=getLayoutInflater().inflate(R.layout.grid_item_topic,content,false);
            TopicAdapter.bindCard(card,new TopicViewItem(topic));
            content.addView(card);workCards.add(card);
            card.setEnabled(!state.busy());
            card.findViewById(R.id.authorTextView).setOnClickListener(view->openUser(obj(work,"user")));
            LinearLayout box=card.findViewById(R.id.topicCardContent);
            if(state.canVote(state.activeDetail,id)||state.hasBallotVote(state.activeDetail,id)){
                boolean voted=state.hasBallotVote(state.activeDetail,id);
                MaterialButton vote=reaction(box,getString(voted?R.string.channel_ballot_voted:R.string.channel_ballot_vote),"cast:"+id,voted?"check-circle":"vote-outline",voted,()->voteConfirm(state.activeDetail,id));vote.setEnabled(!state.busy()&&!voted&&state.canVote(state.activeDetail,id));
            }
        }
        pager(state.page,state.pages(),state::load);
    }
    private void workSearchBar() {
        View bar = getLayoutInflater().inflate(R.layout.view_channel_search, content, false);
        TextInputLayout layout = bar.findViewById(R.id.channelSearchInputLayout);
        layout.setHint(getString(R.string.channel_work_search));
        TextInputEditText query = bar.findViewById(R.id.channelSearchInput);
        MaterialButton submit = bar.findViewById(R.id.channelSearchButton);
        query.setTag("module_field:" + R.string.channel_work_search);
        query.setSaveEnabled(false); query.setText(state.draft("work_query"));
        query.setEnabled(!state.busy()); submit.setEnabled(!state.busy());
        watch(query, value -> state.draft("work_query", value));
        submit.setOnClickListener(view -> searchWorks(query));
        query.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                searchWorks(query); return true;
            }
            return false;
        });
        content.addView(bar);
    }
    private void searchWorks(TextInputEditText query) {
        if (state.busy()) return;
        query.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
        state.searchWorks(state.draft("work_query"));
    }
    private void clearWorkCards() {
        for (View card : workCards) TopicAdapter.clearCard(card);
        workCards.clear();
    }
    private void discussion(){
        TextView title=heading(content,getString(R.string.comments_count,state.total));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        title.setAccessibilityHeading(true);
        List<String> labels=new ArrayList<>(),values=new ArrayList<>();labels.add(getString(R.string.channel_comment_tag_filter));values.add("");
        for(JsonElement e:state.tags){String tag=str(obj(e),"tag");labels.add(tag);values.add(tag);}
        select(content,labels,Math.max(0,values.indexOf(state.commentFilter)),i->state.filter(values.get(i)));
        if(state.deleted())text(content,R.string.channel_deleted);else if(num(state.channel,"status_locked")!=0)text(content,R.string.channel_locked);
        else if(!state.signedIn())loginRequired();
        else if(!state.canCompose()){
            text(content,R.string.channel_comment_qualification);
            button(content,R.string.account_title,"account",false,false,()->startActivity(new Intent(this,AccountActivity.class).putExtra(AccountActivity.EXTRA_TAB,1)));
        }
        if(state.locatingComment())text(content,R.string.channel_comment_locating);
        if(state.rows().size()==0&&state.loading("page")&&!state.errors.containsKey("page")){
            ShimmerSkeletonView skeleton=new ShimmerSkeletonView(this);
            skeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.COMMENTS);
            content.addView(skeleton,new LinearLayout.LayoutParams(-1,dp(420)));return;
        }
        if(state.rows().size()==0&&!state.loading("page")&&!state.errors.containsKey("page")){
            TextView empty=text(content,R.string.comments_empty_pull);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(24),dp(24),dp(24));
        }
        for(JsonElement value:state.rows())commentRow(obj(value));
        pager(state.page,state.pages(),state::load);
        if(state.history!=null){heading(content,getString(R.string.channel_comment_history));for(JsonElement e:state.history){JsonObject h=obj(e);text(content,str(h,"created_at"));rich(content,str(h,"previous_content"));}}
    }
    private void commentRow(JsonObject comment) {
        int id = num(comment, "id");
        if (id <= 0) return;
        View row = getLayoutInflater().inflate(R.layout.item_comment, content, false);
        row.setTag("comment:" + id); content.addView(row); commentRows.add(row);
        JsonObject author = obj(comment, "user");
        ImageView avatar = row.findViewById(R.id.ivAvatar);
        TextView name = row.findViewById(R.id.tvUserName);
        String username = str(author, "username");
        name.setText(username.isEmpty() ? getString(R.string.profile_unknown_user) : username);
        View.OnClickListener openAuthor = view -> openUser(author);
        avatar.setOnClickListener(openAuthor); name.setOnClickListener(openAuthor);
        Glide.with(this).load(SiteUrls.media(str(author, "avatar"))).transform(new CircleCrop())
                .placeholder(MdiIcons.drawable(this, "account")).error(MdiIcons.drawable(this, "account")).into(avatar);
        TextView time = row.findViewById(R.id.tvTime);
        time.setText(commentTime(str(comment, "created_at")));
        int replyId = num(comment, "reply_comment_id");
        TextView reference = row.findViewById(R.id.tvChapter);
        reference.setText(getString(R.string.channel_comment_context, id,
                replyId > 0 ? getString(R.string.comments_reply_to, replyId) : ""));
        if (replyId > 0) reference.setOnClickListener(view -> state.locate(replyId));
        TextView body = row.findViewById(R.id.tvContent);
        if (num(comment, "status_del") != 0) {
            body.setText(R.string.channel_comment_deleted);
            row.findViewById(R.id.commentActions).setVisibility(View.GONE); return;
        }
        BbCodeRendering.setText(renderer, body, str(comment, "content"));
        String tagText = ChannelModel.joinTags(array(comment, "tags"));
        if (!tagText.isEmpty()) {
            TextView tags = new TextView(this); tags.setText(tagText);
            tags.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            tags.setTextColor(com.google.android.material.color.MaterialColors.getColor(tags, com.google.android.material.R.attr.colorOnSurfaceVariant));
            LinearLayout details = (LinearLayout) body.getParent();
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(8);
            details.addView(tags, details.indexOfChild(body) + 1, params);
        }
        TextView edited = row.findViewById(R.id.commentEdited);
        String editedAt = str(comment, "edited_at");
        edited.setVisibility(!editedAt.isEmpty() && !editedAt.equals(str(comment, "created_at")) ? View.VISIBLE : View.GONE);
        edited.setEnabled(!state.busy()); edited.setOnClickListener(view -> state.history(id));
        row.findViewById(R.id.commentFavorite).setVisibility(View.GONE);
        row.findViewById(R.id.commentStar).setVisibility(View.GONE);
        bindCommentVote(row.findViewById(R.id.commentLike), comment, 1);
        bindCommentVote(row.findViewById(R.id.commentDislike), comment, 2);
        MaterialButton reply = row.findViewById(R.id.commentReply);
        reply.setEnabled(!state.busy() && (!state.signedIn() || state.canCompose()));
        reply.setOnClickListener(view -> {
            if (state.busy()) return;
            if (!state.signedIn()) startActivity(new Intent(this, LoginActivity.class));
            else if (state.canCompose()) { state.reply(comment); focusInput(); }
        });
        MaterialButton more = row.findViewById(R.id.commentMore);
        more.setEnabled(!state.busy()); more.setOnClickListener(view -> commentMenu(comment));
        ButtonLoadingIcon loading = new ButtonLoadingIcon(); commentIcons.add(loading);
        loading.bind(more, "dots-horizontal", false, state.action.equals("delete_comment:" + id));
    }
    private void bindCommentVote(MaterialButton button, JsonObject comment, int operation) {
        int id = num(comment, "id"); boolean active = state.commentVote(id) == operation;
        String icon = operation == 1 ? "thumb-up" : "thumb-down";
        button.setText(Integer.toString(num(comment, operation == 1 ? "count_upvote" : "count_downvote")));
        button.setEnabled(!state.busy() && !state.deleted());
        ButtonLoadingIcon loading = new ButtonLoadingIcon(); commentIcons.add(loading);
        loading.bind(button, active ? icon : icon + "-outline", active, state.action.equals("comment:" + id + ":" + operation));
        button.setOnClickListener(view -> {
            if (state.busy() || state.deleted()) return;
            if (!state.signedIn()) startActivity(new Intent(this, LoginActivity.class));
            else state.voteComment(id, operation);
        });
    }
    private String commentTime(String value) {
        try {
            return java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern(getString(R.string.common_date_time)));
        } catch (RuntimeException ignored) { return value; }
    }
    private void clearCommentRows() {
        for (ButtonLoadingIcon icon : commentIcons) icon.close();
        commentIcons.clear();
        for (View row : commentRows) {
            ImageView avatar = row.findViewById(R.id.ivAvatar);
            Glide.with(getApplicationContext()).clear(avatar);
            ((TextView) row.findViewById(R.id.tvContent)).setText(null);
        }
        commentRows.clear();
    }
    @Override protected void afterRender(){
        if(state.tab==2&&state.focusComment>0){
            View target=content.findViewWithTag("comment:"+state.focusComment);
            if(target!=null){
                state.focusComment=0;
                Rect bounds=new Rect();target.getDrawingRect(bounds);
                scroll.offsetDescendantRectToMyCoords(target,bounds);
                scroll.smoothScrollTo(0,Math.max(0,bounds.top-scroll.getPaddingTop()));
            }
        }
    }
    private void commentMenu(JsonObject comment){
        int id=num(comment,"id");List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        labels.add(getString(R.string.channel_comment_history));actions.add(()->state.history(id));
        if(!state.deleted()&&state.userId()==num(comment,"user_id")&&state.canCompose()){labels.add(getString(R.string.channel_comment_edit));actions.add(()->{state.editComment(comment);focusInput();});}
        if(!state.deleted()&&state.signedIn()&&(state.userId()==num(comment,"user_id")||state.role>=4)){
            labels.add(getString(R.string.channel_comment_delete));actions.add(()->confirm(getString(R.string.channel_comment_delete_confirm),()->state.mutate("delete_comment:"+id,Route.DELETE,object("type",6,"id",id))));}
        if(!state.deleted()){labels.add(getString(R.string.community_report_comment));actions.add(()->ReportDialog.show(this,ReportRequest.CHANNEL_COMMENT,id,str(comment,"content")));}
        menu(labels.toArray(new String[0]),i->actions.get(i).run());
    }
    private void subscribers(){if(state.rows().size()==0&&!state.loading("page"))text(content,R.string.modules_empty);for(JsonElement value:state.rows())user(content,obj(value));pager(state.page,state.pages(),state::load);}
    private void manage(){
        if(!state.manager()){text(content,R.string.channel_no_management);return;}
        if(state.deleted()){text(content,R.string.channel_deleted);recovery();return;}
        button(content,R.string.channel_edit,Route.UPDATE_CHANNEL.name(),false,true,()->channelForm("metadata",R.string.channel_edit,state.channel));
        button(content,num(state.channel,"status_locked")==0?R.string.channel_lock:R.string.channel_unlock,"lock",false,true,()->confirm(getString(R.string.channel_lock_confirm),()->state.mutate("lock",Route.LOCK,object("channel_id",state.id))));
        heading(content,getString(R.string.channel_all_folders));
        button(content,R.string.channel_create_folder,"folder_create",false,true,()->folderForm(0,new JsonObject()));
        for(JsonElement value:state.folders){JsonObject folder=obj(value);int id=num(folder,"id");
            button(content,folderLabel(folder),"folder:"+id,false,true,()->menu(new String[]{getString(R.string.channel_edit_folder),getString(R.string.channel_delete_folder)},i->{
                if(i==0)folderForm(id,folder);else confirm(getString(R.string.channel_delete_folder_confirm,str(folder,"name")),()->state.mutate("folder:"+id,Route.DELETE_FOLDER,object("channel_id",state.id,"folder_id",id)));}));}
        heading(content,getString(R.string.channel_collaborators));
        button(content,R.string.channel_invite_collaborator,"invite_user",false,true,()->form("invite",R.string.channel_invite_collaborator,ModuleFormDialog.field("user_id",R.string.channel_user_id,"")));
        for(JsonElement value:state.collaborators){JsonObject collaborator=obj(value);JsonObject u=obj(collaborator,"user");user(content,u);if(!str(collaborator,"pending_until").isEmpty())text(content,R.string.channel_pending);
            int uid=num(collaborator,"user_id");button(content,R.string.channel_remove_collaborator,"remove_user:"+uid,false,true,()->confirm(getString(R.string.channel_remove_collaborator_confirm,str(u,"username")),()->state.mutate("remove_user:"+uid,Route.REMOVE_COLLABORATOR,object("channel_id",state.id,"user_id",uid))));}
        heading(content,getString(R.string.channel_blacklist));
        button(content,R.string.channel_block_user,"block_user",false,true,()->form("block",R.string.channel_block_user,ModuleFormDialog.field("user_id",R.string.channel_user_id,"")));
        for(JsonElement value:state.blacklist){JsonObject blocked=obj(value),u=obj(blocked,"user");user(content,u);int uid=num(blocked,"user_id");button(content,R.string.channel_unblock_user,"block:"+uid,false,true,()->confirm(getString(R.string.channel_unblock_confirm,str(u,"username")),()->state.mutate("block:"+uid,Route.BLOCK,object("channel_id",state.id,"user_id",uid))));}
        button(content,R.string.channel_delete,"delete_channel",false,true,()->confirm(getString(R.string.channel_delete_confirm,str(state.channel,"name")),()->state.mutate("delete_channel",Route.DELETE,object("type",5,"id",state.id))));
    }
    private void recovery(){
        if(state.role>=4||num(state.channel,"status_del")==1)button(content,R.string.recycle_restore,"recover",false,true,()->confirm(getString(R.string.recycle_restore_confirm,str(state.channel,"name")),()->state.mutate("recover",Route.RECOVER,object("type",5,"id",state.id))));
        else button(content,R.string.recycle_request,"request",false,true,()->ReportDialog.showRecovery(this,5,state.id,str(state.channel,"name")));
    }
    private void folderForm(int id,JsonObject f){form("folder:"+id,id>0?R.string.channel_edit_folder:R.string.channel_create_folder,
            ModuleFormDialog.field("name",R.string.channel_folder_name,str(f,"name")),ModuleFormDialog.check("is_public",R.string.channel_folder_public,bool(f,"is_public")));}
    static Bundle[] channelFields(JsonObject source){return new Bundle[]{ModuleFormDialog.field("name",R.string.channel_name,str(source,"name")),
            ModuleFormDialog.field("description",R.string.channel_description,str(source,"description"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            ModuleFormDialog.field("cover_image",R.string.channel_cover,str(source,"cover_image"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI),
            ModuleFormDialog.field("preface",R.string.channel_preface,str(source,"preface"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE)};}
    private void channelForm(String key,int title,JsonObject source){form(key,title,channelFields(source));}
    private void ballots(){
        if(state.selectedBallotId>0){ballotDetails();return;}
        if(state.manager()&&!state.deleted())for(JsonElement value:state.folders){JsonObject f=obj(value);JsonObject active=obj(f,"active_ballot");heading(content,str(f,"name"));
            if(num(active,"id")>0)button(content,R.string.channel_ballot_stop,"ballot_toggle",false,true,()->confirm(getString(R.string.channel_ballot_stop_confirm),()->state.mutate("ballot_toggle",Route.TOGGLE_BALLOT,object("channel_id",state.id,"folder_id",num(f,"id"),"ballot_id",num(active,"id")))));
            else button(content,R.string.channel_ballot_start,"ballot_toggle",false,true,()->form("ballot:"+num(f,"id"),R.string.channel_ballot_start,
                    ModuleFormDialog.field("bits_cost",R.string.channel_ballot_cost,"0",InputType.TYPE_CLASS_NUMBER),ModuleFormDialog.field("min_votes_per_user",R.string.channel_ballot_min,"0",InputType.TYPE_CLASS_NUMBER),ModuleFormDialog.field("max_votes_per_user",R.string.channel_ballot_max,"1",InputType.TYPE_CLASS_NUMBER)));}
        if(state.ballots.size()==0&&!state.loading("page"))text(content,R.string.modules_empty);
        for(JsonElement value:state.ballots){JsonObject ballot=obj(value);int id=num(ballot,"id");button(content,getString(R.string.channel_ballot_title,id,str(ballot,"created_at")),"ballot",false,false,()->state.openBallot(id));}
    }
    private void ballotDetails(){
        button(content,R.string.channel_ballot_back,"back",false,false,state::closeBallotDetails);
        JsonObject detail=state.ballotDetail,ballot=obj(detail,"ballot"),config=obj(ballot,"config");if(num(ballot,"id")==0)return;
        heading(content,getString(R.string.channel_ballot_title,num(ballot,"id"),getString(str(detail,"status").equals("active")?R.string.channel_ballot_active:R.string.channel_ballot_ended)));
        text(content,getString(R.string.channel_ballot_info,num(config,"min_votes_per_user"),num(config,"max_votes_per_user"),num(config,"bits_cost")));
        text(content,getString(R.string.channel_ballot_totals,num(state.ballotStats,"count_voters"),num(state.ballotStats,"count_votes")));text(content,R.string.channel_ballot_effective_hint);
        List<JsonObject> stats=new ArrayList<>();for(JsonElement e:array(state.ballotStats,"vote_stats_by_work"))stats.add(obj(e));stats.sort((a,b)->Integer.compare(num(b,"effective_votes"),num(a,"effective_votes")));
        Set<Integer> listed=new HashSet<>();for(JsonObject stat:stats){int id=num(stat,"work_id");listed.add(id);ballotWork(detail,id,stat);}
        for(JsonElement e:array(detail,"eligible_works")){int id=num(obj(e),"work_id");if(!listed.contains(id))ballotWork(detail,id,obj(e));}
        button(content,R.string.channel_ballot_export,"export",false,false,()->{
            StringBuilder csv=new StringBuilder("\uFEFF").append(getString(R.string.channel_ballot_csv_header)).append('\n');for(JsonObject stat:stats)csv.append(num(stat,"work_id")).append(',').append(num(stat,"effective_votes")).append(',').append(num(stat,"raw_votes")).append('\n');
            pendingCsv=csv.toString();exporter.launch("channel-"+state.id+"-ballot-"+state.selectedBallotId+".csv");
        }).setEnabled(!state.busy()&&state.ballotStats.size()>0&&!state.loading("statistics"));
    }
    private void ballotWork(JsonObject detail,int id,JsonObject stat){LinearLayout box=column(content);heading(box,"#"+id).setOnClickListener(v->openWork(id));text(box,getString(R.string.channel_ballot_votes,num(stat,"effective_votes"),num(stat,"raw_votes")));
        boolean voted=state.hasBallotVote(detail,id);if(voted||state.canVote(detail,id))reaction(box,getString(voted?R.string.channel_ballot_voted:R.string.channel_ballot_vote),"cast:"+id,voted?"check-circle":"vote-outline",voted,()->voteConfirm(detail,id)).setEnabled(!state.busy()&&!voted&&state.canVote(detail,id));}
    private void voteConfirm(JsonObject detail,int workId){confirm(getString(R.string.channel_ballot_vote_confirm,workId,num(obj(obj(detail,"ballot"),"config"),"bits_cost")),()->state.cast(detail,workId));}
    private void composer(){
        context=new TextView(this);footer.addView(context);
        View bar=getLayoutInflater().inflate(R.layout.view_work_comment_composer,footer,false);
        bar.setVisibility(View.VISIBLE);footer.addView(bar);
        TextInputLayout layout=bar.findViewById(R.id.commentComposerInputLayout);
        layout.setHint(getString(R.string.channel_comment_hint));
        input=bar.findViewById(R.id.commentComposerInput);
        send=bar.findViewById(R.id.commentComposerSend);
        emoji=bar.findViewById(R.id.commentComposerEmoji);
        input.setSaveEnabled(false);
        watch(input,value->{if(!bindingComposer){state.draft("comment",value);if(send!=null)send.setEnabled(!state.busy()&&!value.trim().isEmpty());}});LinearLayout line=row(footer);
        send.setOnClickListener(v->state.sendComment());
        input.setOnEditorActionListener((view,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEND||action==EditorInfo.IME_ACTION_DONE){state.sendComment();return true;}
            return false;
        });
        emoji.setOnClickListener(v->FtemojiPicker.show(this,name->{
            if(state.busy()||!state.canCompose()||input.getText()==null)return;
            int start=Math.max(0,input.getSelectionStart()),end=Math.max(0,input.getSelectionEnd());
            input.getText().replace(Math.min(start,end),Math.max(start,end),":ftemoji_"+name+":");
        }));
        commentTags=new MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle);commentTags.setText(R.string.channel_comment_tags);line.addView(commentTags);commentTags.setOnClickListener(v->form("comment_tags",R.string.channel_comment_tags,ModuleFormDialog.field("tags",R.string.channel_comment_tags,state.draft("comment_tags"))));
        cancel=new MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle);cancel.setText(R.string.common_cancel);line.addView(cancel);cancel.setOnClickListener(v->state.cancelContext());
    }
    private void renderComposer(){
        if(input==null)return;boolean visible=state.tab==2&&state.canCompose();footer.setVisibility(visible?View.VISIBLE:View.GONE);bindingComposer=true;
        if(!input.getText().toString().equals(state.draft("comment"))){input.setText(state.draft("comment"));input.setSelection(input.length());}
        boolean editing=!state.draft("editing").isEmpty(),reply=!state.draft("reply").isEmpty();
        context.setText(editing?getString(R.string.channel_editing_comment,ChannelModel.number(state.draft("editing"))):reply?getString(R.string.channel_replying,state.draft("reply_name"),ChannelModel.number(state.draft("reply"))):"");context.setVisibility(editing||reply?View.VISIBLE:View.GONE);
        cancel.setVisibility(editing||reply?View.VISIBLE:View.GONE);input.setEnabled(!state.busy());send.setEnabled(!state.busy()&&!state.draft("comment").trim().isEmpty());emoji.setEnabled(!state.busy());commentTags.setEnabled(!state.busy());cancel.setEnabled(!state.busy());
        send.setText(null);send.setContentDescription(getString(editing?R.string.channel_comment_save:R.string.channel_comment_send));
        sendIcon.bind(send,editing?"content-save":"send",false,state.action.equals("send"));bindingComposer=false;
    }
    private void focusInput(){footer.setVisibility(View.VISIBLE);input.requestFocus();input.post(()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT));}
    @Override protected boolean onForm(String key,JsonObject values){
        if(key.equals("comment_tags")){state.draft("comment_tags",str(values,"tags"));return true;}
        if(key.equals("metadata")){if(!validateChannel(this,values,false))return false;values.addProperty("channel_id",state.id);state.mutate(Route.UPDATE_CHANNEL.name(),Route.UPDATE_CHANNEL,values);return true;}
        if(key.startsWith("folder:")){String name=str(values,"name").trim();if(name.isEmpty()||name.codePointCount(0,name.length())>100){toast(R.string.channel_folder_invalid);return false;}int id=ChannelModel.number(key.substring(7));values.addProperty("channel_id",state.id);values.addProperty("name",name);if(id>0)values.addProperty("folder_id",id);state.mutate(id>0?"folder:"+id:"folder_create",id>0?Route.UPDATE_FOLDER:Route.CREATE_FOLDER,values);return true;}
        if(key.equals("add_work")){int id=parseId(str(values,"work_id"),"work");if(id<=0){toast(R.string.modules_invalid_id);return false;}state.work(Route.ADD_WORK,id,state.folder,null);return true;}
        if(key.equals("invite")||key.equals("block")){
            String input=str(values,"user_id");int uid=parseId(input,"user");
            if(uid>0)state.mutate(key.equals("invite")?"invite_user":"block_user",key.equals("invite")?Route.INVITE:Route.BLOCK,object("channel_id",state.id,"user_id",uid));
            else {String username=parseUsername(input);if(username.isEmpty()){toast(R.string.channel_invalid_user);return false;}state.resolveMember(username,key.equals("block"));}
            return true;
        }
        if(key.startsWith("ballot:")){int cost=parseNonNegative(str(values,"bits_cost")),min=parseNonNegative(str(values,"min_votes_per_user")),max=parseNonNegative(str(values,"max_votes_per_user"));
            if(cost<0||min<0||max<=0||max<min){toast(R.string.channel_ballot_invalid);return false;}state.mutate("ballot_toggle",Route.TOGGLE_BALLOT,object("channel_id",state.id,"folder_id",ChannelModel.number(key.substring(7)),"config",object("bits_cost",cost,"min_votes_per_user",min,"max_votes_per_user",max)));return true;}
        return false;
    }
    private int parseNonNegative(String s){try{int value=Integer.parseInt(s);return value>=0?value:-1;}catch(NumberFormatException failure){return -1;}}
    public static boolean validateChannel(Context context,JsonObject values){return validateChannel(context,values,true);}
    private static boolean validateChannel(Context context,JsonObject values,boolean create){
        for(String key:new String[]{"name","description","cover_image","preface"}){
            String value=str(values,key);if(!key.equals("preface"))values.addProperty(key,value.trim());
            if((create||key.equals("name"))&&value.trim().isEmpty()){Toast.makeText(context,R.string.modules_required,Toast.LENGTH_LONG).show();return false;}
        }
        String cover=str(values,"cover_image");
        boolean relative=cover.startsWith("/")&&!cover.startsWith("//")&&SiteUrls.media(cover)!=null;
        if(!cover.isEmpty()&&!AccountActivity.webUrl(cover)&&!relative){Toast.makeText(context,R.string.modules_invalid_url,Toast.LENGTH_LONG).show();return false;}
        return true;
    }
    private static String parseUsername(String value){
        value=value.trim();
        if(SiteUrls.isSite(value)){
            List<String> parts=Uri.parse(value).getPathSegments();
            return parts.size()==2&&parts.get(0).equals("user")&&parts.get(1).startsWith("@")?parts.get(1).substring(1):"";
        }
        if(value.startsWith("@"))value=value.substring(1);
        return value.contains("/")||value.contains(":")||value.matches("[0-9]+")?"":value;
    }
    public static int parseId(String value,String type){value=value.trim();if(value.matches("[0-9]+"))return Math.max(0,ChannelModel.number(value));Uri uri=Uri.parse(value);if(!SiteUrls.isSite(value))return 0;String path=uri.getPath();if(path==null||!path.matches("/"+type+"/[0-9]+/?"))return 0;List<String> segments=uri.getPathSegments();return ChannelModel.number(segments.get(1));}
    private void openWork(int id){if(id>0)startActivity(new Intent(this,TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID,id));}
    private void openUser(JsonObject user){String name=str(user,"username");if(!name.isEmpty())startActivity(new Intent(this,UserDetailActivity.class).putExtra(UserDetailActivity.EXTRA_USERNAME,name));}
    private void user(LinearLayout parent,JsonObject user){String name=str(user,"username");if(!name.isEmpty())text(parent,name).setOnClickListener(v->openUser(user));}
    private void rich(LinearLayout parent,String source){if(!source.isEmpty())BbCodeRendering.setText(renderer,text(parent,""),source);}
    @Override protected void onDestroy(){
        clearWorkCards();
        clearCommentRows();
        if(toolbarElevationAnimator!=null)toolbarElevationAnimator.cancel();
        sendIcon.close();super.onDestroy();
    }
}
