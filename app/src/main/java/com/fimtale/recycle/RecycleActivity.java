package com.fimtale.recycle;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.*;
import com.fimtale.community.CommentDetailActivity;
import com.fimtale.channels.ChannelActivity;
import com.fimtale.modules.*;
import com.fimtale.report.ReportDialog;
import com.google.gson.*;
import java.util.*;
import static com.fimtale.modules.ModuleApi.*;

public class RecycleActivity extends ModuleActivity {
    public static final String EXTRA_ALL="recycle_all";
    private RecycleModel state;
    private io.noties.markwon.Markwon renderer;
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);state=new ViewModelProvider(this).get(RecycleModel.class);bind(state,R.string.recycle_title);
        if(getIntent().getBooleanExtra(EXTRA_ALL,false))state.all=true;
        renderer=com.fimtale.utils.BbCodeRendering.create(this);
        tabLabels(R.array.recycle_types,state.tab,state::select);centerTabs();centerPagers();
        startPage();
    }
    @Override protected void refresh(){state.refresh();}
    @Override protected boolean usesHeaderLoadingIndicator(){return false;}
    @Override protected boolean supportsPullToRefresh(){return true;}
    @Override protected boolean usesToolbarRefresh(){return false;}
    @Override protected void render(){
        if(loginRequired())return;selectTab(state.tab);errors();
        if(state.entries.size()==0&&(state.loading("header")||state.loading("page"))&&state.errors.isEmpty()){loadingBlocks(3);return;}
        String[] statuses=getResources().getStringArray(R.array.recycle_statuses);
        if(!state.loading("page")&&state.entries.size()==0&&state.errors.isEmpty())text(content,R.string.recycle_empty);
        for(JsonElement value:state.entries){JsonObject entry=obj(value);int id=num(entry,"id"),type=num(entry,"type"),status=num(entry,"status_del");
            LinearLayout box=column(content);String title=str(entry,"title");if(title.isEmpty())title="#"+id;heading(box,title);
            text(box,getString(R.string.recycle_meta,id,str(entry,"username"),status>=0&&status<statuses.length?statuses[status]:"",str(entry,"updated_at")));
            if(num(entry,"belongs_to_id")>0)text(box,getString(R.string.recycle_parent,str(entry,"belongs_to_title").isEmpty()?Integer.toString(num(entry,"belongs_to_id")):str(entry,"belongs_to_title")));
            String preview=str(entry,"content_preview");if(!preview.isEmpty())com.fimtale.utils.BbCodeRendering.setText(renderer,text(box,""),preview);
            button(box,R.string.recycle_view,"view",false,false,()->open(entry));
            String label=title;
            if(state.canRecover(entry))button(box,R.string.recycle_restore,"recover:"+type+":"+id,false,true,()->confirm(getString(R.string.recycle_restore_confirm,label),()->state.act(entry,false)));
            if(state.canRequest(entry))button(box,R.string.recycle_request,"request",false,true,()->ReportDialog.showRecovery(this,type,id,label));
            if(state.canDelete())button(box,R.string.recycle_delete,"delete:"+type+":"+id,false,true,()->confirm(getString(R.string.recycle_delete_confirm,label),()->state.act(entry,true)));
        }
        pager(state.page,state.pages(),state::load);
    }
    private void open(JsonObject entry){int type=num(entry,"type"),id=num(entry,"id"),parent=num(entry,"belongs_to_id");
        if(type==5)startActivity(ChannelActivity.intent(this,id));
        else if(type==6)startActivity(ChannelActivity.intent(this,parent).putExtra(ChannelActivity.EXTRA_COMMENT_ID,id));
        else if(type==4)startActivity(CommentDetailActivity.intent(this,id));
        else if(type==3&&parent>0)startActivity(new Intent(this,ReaderActivity.class).putExtra(ReaderActivity.EXTRA_WORK_ID,parent).putExtra(ReaderActivity.EXTRA_CHAPTER_ID,id));
        else if(type==1)startActivity(new Intent(this,TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID,id));
    }
}
