package com.fimtale.channels;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;
import com.bumptech.glide.Glide;
import com.fimtale.*;
import com.fimtale.account.AccountActivity;
import com.fimtale.modules.*;
import com.fimtale.network.SiteUrls;
import com.fimtale.report.*;
import com.fimtale.ui.ButtonLoadingIcon;
import com.fimtale.utils.BbCodeRendering;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
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
    private MaterialButton send,cancel,commentTags;
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
        bind(state,R.string.channels_title);renderer=BbCodeRendering.create(this);tabLabels(R.array.channel_tabs,state.tab,state::select);centerTabs();centerPagers();composer();
        toolbar.getMenu().add(R.string.channel_report).setOnMenuItemClickListener(item->{if(state.ready()&&!state.deleted()&&!state.busy())ReportDialog.show(this,ReportRequest.CHANNEL,state.id,str(state.channel,"name"));return true;});
        startPage();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("csv",pendingCsv);}
    @Override protected void refresh(){state.refresh();}
    @Override protected boolean usesHeaderLoadingIndicator(){return false;}
    @Override protected boolean supportsPullToRefresh(){return true;}
    @Override protected boolean usesToolbarRefresh(){return false;}
    @Override protected void render(){
        selectTab(state.tab);
        renderComposer();errors();
        if(!state.ready()){if(state.loading())loadingBlocks(3);else loginRequired();return;}
        toolbar.setTitle(str(state.channel,"name"));
        if(state.tab==0)overview();else if(state.tab==1)works();else if(state.tab==2)discussion();else if(state.tab==3)subscribers();else if(state.tab==4)manage();else ballots();
    }
    private void overview(){
        String cover=str(state.channel,"cover_image");if(!cover.isEmpty()){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription(getString(R.string.channel_cover));content.addView(image,new LinearLayout.LayoutParams(-1,dp(170)));Glide.with(this).load(SiteUrls.media(cover)).into(image);}
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
        folderSelect();field(content,R.string.channel_work_search,state.draft("work_query"),InputType.TYPE_CLASS_TEXT,value->state.draft("work_query",value));
        button(content,R.string.channel_search_works,"search",false,false,()->state.searchWorks(state.draft("work_query")));
        if(state.canAdd())button(content,R.string.channel_add_work,"add_work",false,true,()->form("add_work",R.string.channel_add_work,ModuleFormDialog.field("work_id",R.string.channel_work_id,"")));
        JsonObject active=state.activeBallot();if(num(active,"id")>0){text(content,getString(R.string.channel_ballot_info,num(obj(active,"config"),"min_votes_per_user"),num(obj(active,"config"),"max_votes_per_user"),num(obj(active,"config"),"bits_cost")));button(content,R.string.channel_ballot_results,"ballot",false,false,()->state.openBallot(num(active,"id")));}
        if(state.rows().size()==0&&!state.loading("page"))text(content,R.string.modules_empty);
        for(JsonElement value:state.rows()){
            JsonObject work=obj(value);int id=num(work,"id");LinearLayout box=column(content);String title=str(work,"title");boolean pinned=contains(array(state.pageData,"pinned_work_ids"),id);
            heading(box,pinned?getString(R.string.channel_pinned,title):title).setOnClickListener(v->openWork(id));
            user(box,obj(work,"user"));String intro=str(work,"intro");if(!intro.isEmpty())text(box,intro);
            if(state.canVote(state.activeDetail,id)||state.hasBallotVote(state.activeDetail,id)){
                boolean voted=state.hasBallotVote(state.activeDetail,id);
                MaterialButton vote=reaction(box,getString(voted?R.string.channel_ballot_voted:R.string.channel_ballot_vote),"cast:"+id,voted?"check-circle":"vote-outline",voted,()->voteConfirm(state.activeDetail,id));vote.setEnabled(!state.busy()&&!voted&&state.canVote(state.activeDetail,id));
            }
            List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
            labels.add(getString(R.string.recycle_view));actions.add(()->openWork(id));
            if(state.manager()&&!state.deleted()&&state.folder>=0){labels.add(getString(pinned?R.string.channel_unpin_work:R.string.channel_pin_work));actions.add(()->state.work(Route.PIN_WORK,id,state.folder,!pinned));
                labels.add(getString(R.string.channel_copy_work));actions.add(()->copyWork(id));}
            if(state.canRemove(work)){labels.add(getString(R.string.channel_remove_work));actions.add(()->confirm(getString(R.string.channel_remove_work_confirm,title),()->state.work(Route.REMOVE_WORK,id,state.folder,null)));}
            button(box,R.string.common_more,Route.PIN_WORK.name()+":"+id,false,false,()->menu(labels.toArray(new String[0]),i->actions.get(i).run()));
        }
        pager(state.page,state.pages(),state::load);
    }
    private void copyWork(int id){List<String> labels=new ArrayList<>();List<Integer> ids=new ArrayList<>();labels.add(getString(R.string.channel_no_folder));ids.add(0);
        for(JsonElement e:state.folders){labels.add(str(obj(e),"name"));ids.add(num(obj(e),"id"));}
        menu(labels.toArray(new String[0]),i->state.work(Route.ADD_WORK,id,ids.get(i),null));
    }
    private void discussion(){
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
        if(state.rows().size()==0&&!state.loading("page"))text(content,R.string.modules_empty);
        for(JsonElement value:state.rows()){
            JsonObject comment=obj(value);int id=num(comment,"id");LinearLayout box=column(content);box.setTag("comment:"+id);
            String name=str(obj(comment,"user"),"username");text(box,getString(R.string.channel_comment_meta,name,id,str(comment,"created_at"))).setOnClickListener(v->openUser(obj(comment,"user")));
            if(num(comment,"reply_comment_id")>0)text(box,getString(R.string.channel_comment_reply_to,num(comment,"reply_comment_id"))).setOnClickListener(v->state.locate(num(comment,"reply_comment_id")));
            if(num(comment,"status_del")!=0){text(box,R.string.channel_comment_deleted);continue;}
            rich(box,str(comment,"content"));String tagText=ChannelModel.joinTags(array(comment,"tags"));if(!tagText.isEmpty())text(box,tagText);
            LinearLayout actions=row(box);int vote=state.commentVote(id);
            reaction(actions,Integer.toString(num(comment,"count_upvote")),"comment:"+id+":1",vote==1?"thumb-up":"thumb-up-outline",vote==1,()->state.voteComment(id,1)).setEnabled(!state.busy()&&!state.deleted());
            reaction(actions,Integer.toString(num(comment,"count_downvote")),"comment:"+id+":2",vote==2?"thumb-down":"thumb-down-outline",vote==2,()->state.voteComment(id,2)).setEnabled(!state.busy()&&!state.deleted());
            button(box,R.string.channel_reply,"reply",false,true,()->{if(state.canCompose()){state.reply(comment);focusInput();}}).setEnabled(!state.busy()&&(!state.signedIn()||state.canCompose()));
            button(box,R.string.common_more,"comment_more",false,false,()->commentMenu(comment));
        }
        pager(state.page,state.pages(),state::load);
        if(state.history!=null){heading(content,getString(R.string.channel_comment_history));for(JsonElement e:state.history){JsonObject h=obj(e);text(content,str(h,"created_at"));rich(content,str(h,"previous_content"));}}
    }
    @Override protected void afterRender(){
        if(state.tab==2&&state.focusComment>0){
            View target=content.findViewWithTag("comment:"+state.focusComment);
            if(target!=null){state.focusComment=0;scroll.smoothScrollTo(0,target.getTop());}
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
        context=new TextView(this);footer.addView(context);input=new TextInputEditText(this);input.setHint(R.string.channel_comment_hint);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setMinLines(1);input.setMaxLines(4);footer.addView(input,new LinearLayout.LayoutParams(-1,-2));
        input.setSaveEnabled(false);
        watch(input,value->{if(!bindingComposer){state.draft("comment",value);if(send!=null)send.setEnabled(!state.busy()&&!value.trim().isEmpty());}});LinearLayout line=row(footer);
        send=new MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle);send.setText(R.string.channel_comment_send);line.addView(send);send.setOnClickListener(v->state.sendComment());
        commentTags=new MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle);commentTags.setText(R.string.channel_comment_tags);line.addView(commentTags);commentTags.setOnClickListener(v->form("comment_tags",R.string.channel_comment_tags,ModuleFormDialog.field("tags",R.string.channel_comment_tags,state.draft("comment_tags"))));
        cancel=new MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle);cancel.setText(R.string.common_cancel);line.addView(cancel);cancel.setOnClickListener(v->state.cancelContext());
    }
    private void renderComposer(){
        if(input==null)return;boolean visible=state.tab==2&&state.canCompose();footer.setVisibility(visible?View.VISIBLE:View.GONE);bindingComposer=true;
        if(!input.getText().toString().equals(state.draft("comment"))){input.setText(state.draft("comment"));input.setSelection(input.length());}
        boolean editing=!state.draft("editing").isEmpty(),reply=!state.draft("reply").isEmpty();
        context.setText(editing?getString(R.string.channel_editing_comment,ChannelModel.number(state.draft("editing"))):reply?getString(R.string.channel_replying,state.draft("reply_name"),ChannelModel.number(state.draft("reply"))):"");context.setVisibility(editing||reply?View.VISIBLE:View.GONE);
        cancel.setVisibility(editing||reply?View.VISIBLE:View.GONE);input.setEnabled(!state.busy());send.setEnabled(!state.busy()&&!state.draft("comment").trim().isEmpty());commentTags.setEnabled(!state.busy());cancel.setEnabled(!state.busy());
        send.setText(editing?R.string.channel_comment_save:R.string.channel_comment_send);sendIcon.bind(send,"send",false,state.action.equals("send"));bindingComposer=false;
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
    @Override protected void onDestroy(){sendIcon.close();super.onDestroy();}
}
