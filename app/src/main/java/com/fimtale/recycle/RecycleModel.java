package com.fimtale.recycle;

import android.app.Application;
import androidx.lifecycle.SavedStateHandle;
import com.fimtale.community.Community;
import com.fimtale.modules.*;
import com.fimtale.utils.UserPreferences;
import com.google.gson.*;
import static com.fimtale.modules.ModuleApi.*;

public class RecycleModel extends ModuleModel {
    public static final int[] TYPES={1,3,4,5,6};
    public int tab, filter, page=1, total, role;
    public boolean all;
    public JsonArray entries=new JsonArray();
    private int lastPage;
    public RecycleModel(Application app,SavedStateHandle state){super(app,state);tab=Math.max(0,Math.min(TYPES.length-1,value("tab",0)));filter=0;page=Math.max(1,value("page",1));all=false;}
    private int value(String key,int fallback){Integer value=saved.get(key);return value==null?fallback:value;}
    @Override protected void reset(){entries=new JsonArray();page=1;total=lastPage=0;role=0;all=false;}
    public void refresh(){
        if(busy())return;if(!signedIn()){changed();return;}
        cancelReads();role=0;lastPage=0;entries=new JsonArray();
        read("header",Route.AUTH,query(),v->{role=num(obj(v),"role_id");if(role<4)all=false;saved.set("all",all);},()->{
            if(errors.containsKey("header")){all=false;return;}load(page);
        });
    }
    public void select(int value){if(busy()||value<0||value>=TYPES.length)return;tab=value;saved.set("tab",value);total=lastPage=0;load(1);}
    public void filter(int value){if(busy()||value<0||value>2)return;filter=value;saved.set("filter",value);total=lastPage=0;load(1);}
    public void scope(boolean value){if(busy()||role<4)return;all=value;saved.set("all",all);total=lastPage=0;load(1);}
    public void load(int value){
        if(!signedIn())return;int requested=Math.max(1,value);page=requested;entries=new JsonArray();
        read("page",Route.RECYCLE,query("type",TYPES[tab],"scope",all&&role>=4?"all":"mine","status_del",filter==0?null:filter,"page",requested,"per_page",20),v->{
            JsonObject data=obj(v);if(!data.has("items"))throw new IllegalStateException();
            total=num(data,"total");page=requested;entries=array(data,"items");saved.set("page",page);
            if(total<=0&&entries.size()<20)lastPage=entries.size()==0&&page>1?page-1:page;
        },()->{if(!errors.containsKey("page")&&page>1&&(entries.size()==0||page>pages()))load(Math.max(1,page-1));});
    }
    public int pages(){return total>0?Math.max(1,(total+19)/20):lastPage>0?lastPage:Math.max(1,page+(entries.size()==20?1:0));}
    public boolean owns(JsonObject entry){return Integer.toString(num(entry,"user_id")).equals(UserPreferences.getUserId(getApplication()));}
    // The mine scope also includes channels where this account is a collaborator.
    public boolean canRecover(JsonObject entry){return signedIn()&&(all?role>=4:num(entry,"status_del")==1);}
    public boolean canRequest(JsonObject entry){return signedIn()&&!all&&num(entry,"status_del")==2;}
    public boolean canDelete(){return signedIn()&&all&&role>=5;}
    public void act(JsonObject entry,boolean permanent){
        if(permanent?!canDelete():!canRecover(entry))return;
        int id=num(entry,"id"),type=num(entry,"type");if(id<=0||type!=TYPES[tab]||!entries.contains(entry))return;
        post((permanent?"delete:":"recover:")+type+":"+id,permanent?Route.DELETE:Route.RECOVER,object("type",type,"id",id,"is_permanent",permanent?true:null),v->{Community.changed();lastPage=0;load(page);});
    }
}
