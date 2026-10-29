// Copyright 2020-2026 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.defold.remote;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Stable navigation shell. Catalog polling updates rows, never the search field or camera. */
final class RemoteDashboard extends LinearLayout {
    interface Actions {
        void scan(); void album(); void paste(); void connect(String base); void discover();
        void quality(); void stop(String base,Runnable complete);
        Dialog sheet(String title,LinearLayout content);
    }
    private static final int BG=0xff181818,PANEL=0xff222222,LINE=0xff303030,TEXT=0xffededed,MUTED=0xffaaa9a6,BLUE=0xff4386e5,GREEN=0xff6bc6a5;
    private final Activity activity; private final Actions actions;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor network=new ThreadPoolExecutor(3,3,0,TimeUnit.MILLISECONDS,new PriorityBlockingQueue<Runnable>());
    private final LinkedHashMap<String,Catalog> catalogs=new LinkedHashMap<>();
    private final HashMap<String,CatalogRequest> inFlight=new HashMap<>();
    private final HashMap<String,Integer> scrolls=new HashMap<>();
    private final SharedPreferences preferences;
    private final LinearLayout tabs,body,rows;
    private final HashMap<String,LinearLayout> tabItems=new HashMap<>();
    private final HashMap<String,TextView> tabCaptions=new HashMap<>();
    private final HashMap<String,RemoteIcon> tabIcons=new HashMap<>();
    private final ScrollView scroll;
    private TextView subtitle,message; private EditText search;
    private String tab="games",filter="",query="";
    private boolean active,renderPending; private int epoch; private long requestSequence;
    private final Runnable poll=new Runnable(){public void run(){if(active){refresh();ui.postDelayed(this,2500);}}};
    private static final class Catalog {String address,name,error="";JSONArray projects=new JSONArray();boolean online,fresh=true;long revision;}
    private final class CatalogRequest implements Runnable,Comparable<CatalogRequest> {
        final Catalog catalog;final int requestEpoch;final long sequence,revision;int priority;
        CatalogRequest(Catalog catalog){this.catalog=catalog;requestEpoch=epoch;sequence=requestSequence++;revision=catalog.revision;priority=priority(catalog);}
        @Override public int compareTo(CatalogRequest other){int order=Integer.compare(priority,other.priority);return order==0?Long.compare(sequence,other.sequence):order;}
        @Override public void run(){
            JSONObject response=null;String failure="";
            try{response=RemoteHosts.request(catalog.address,RemoteHosts.token(catalog.address),"/games",null);}catch(Exception e){failure=RemoteTls.explain(e,catalog.address);}
            final JSONObject value=response;final String error=failure;
            ui.post(()->{if(inFlight.get(catalog.address)!=this)return;inFlight.remove(catalog.address);if(requestEpoch!=epoch||catalogs.get(catalog.address)!=catalog||revision!=catalog.revision)return;updateCatalog(catalog,value,error);});
        }
    }
    RemoteDashboard(Activity activity,Actions actions){
        super(activity);this.activity=activity;this.actions=actions;preferences=activity.getSharedPreferences("remote-dashboard",0);
        filter=preferences.getString("filter","");tab=preferences.getString("tab","games");if(!Arrays.asList("games","running","computers","settings").contains(tab))tab="games";
        setOrientation(VERTICAL);setBackgroundColor(BG);
        body=column();addView(body,new LayoutParams(-1,0,1));
        scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);rows=column();
        tabs=new LinearLayout(activity);tabs.setPadding(dp(8),dp(5),dp(8),dp(6));View line=new View(activity);line.setBackgroundColor(LINE);addView(line,new LayoutParams(-1,dp(1)));addView(tabs,new LayoutParams(-1,dp(64)));
        hydrateHosts();showTab(tab);
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(VERTICAL);return v;}
    private TextView text(String value,int size,int color){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private TextView heading(String value,int size){TextView v=text(value,size,TEXT);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(13));d.setStroke(dp(1),LINE);return d;}
    private void gap(LinearLayout parent,int n){parent.addView(new View(activity),new LayoutParams(1,dp(n)));}
    private Button button(String title,Runnable action,boolean primary){Button b=new Button(activity);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(TEXT);b.setMinWidth(0);b.setPadding(dp(12),0,dp(12),0);b.setMinimumHeight(dp(48));b.setStateListAnimator(null);b.setBackground(bg(primary?0xff286bcc:PANEL));b.setOnClickListener(v->action.run());return b;}
    private View symbol(String name,int color){return new RemoteIcon(activity,name,color);}
    private View iconButton(String name,String label,Runnable action){FrameLayout v=new FrameLayout(activity);v.setContentDescription(label);v.setFocusable(true);v.setOnClickListener(w->action.run());v.addView(symbol(name,TEXT),new android.widget.FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(16),dp(16),dp(16),dp(16));v.setBackground(bg(PANEL));LayoutParams p=new LayoutParams(-1,-2);p.bottomMargin=dp(14);rows.addView(v,p);return v;}
    private void info(LinearLayout parent,String value){TextView t=text(value,13,MUTED);t.setLineSpacing(dp(3),1);parent.addView(t);}
    void resume(){if(active)return;active=true;ui.post(poll);}
    void pause(){active=false;ui.removeCallbacks(poll);}
    void close(){pause();++epoch;inFlight.clear();network.shutdownNow();}
    String currentTab(){return tab;}
    String projectName(String base){for(Catalog catalog:catalogs.values())for(int i=0;i<catalog.projects.length();i++){JSONObject project=catalog.projects.optJSONObject(i);if(project!=null&&base.equals(catalog.address+project.optString("path")))return projectName(project);}return "Defold 游戏";}
    void showComputer(String address){filter=RemoteTls.origin(address);preferences.edit().putString("filter",filter).apply();showTab("games");refresh();}
    void seedCatalog(String address,JSONObject value){hydrateHosts();Catalog catalog=catalogs.get(RemoteTls.origin(address));if(catalog!=null){catalog.revision++;updateCatalog(catalog,value,"");}}
    void setMessage(String value){if(message!=null){message.setText(value==null?"":value);message.setVisibility(value==null||value.isEmpty()?GONE:VISIBLE);}}
    void showTab(String next){
        scrolls.put(tab,scroll.getScrollY());tab=next;preferences.edit().putString("tab",tab).apply();body.removeAllViews();
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(6),dp(12),dp(6));
        header.addView(symbol(tab.equals("computers")?"monitor":tab.equals("settings")?"settings":tab.equals("running")?"layers":"game",TEXT),new LayoutParams(dp(30),dp(30)));
        LinearLayout names=column();names.setPadding(dp(12),0,0,0);names.addView(heading(tabName(tab),18));subtitle=text("",12,MUTED);names.addView(subtitle);header.addView(names,new LayoutParams(0,-2,1));
        header.addView(iconButton(tab.equals("settings")?"refresh":"plus",tab.equals("settings")?"刷新连接":"添加电脑",()->{if(tab.equals("settings"))refresh();else addComputer();}),new LayoutParams(dp(48),dp(48)));body.addView(header);
        View line=new View(activity);line.setBackgroundColor(LINE);body.addView(line,new LayoutParams(-1,dp(1)));
        if(scroll.getParent()!=null)((android.view.ViewGroup)scroll.getParent()).removeView(scroll);if(rows.getParent()!=null)((android.view.ViewGroup)rows.getParent()).removeView(rows);
        LinearLayout content=column();content.setPadding(dp(20),dp(22),dp(20),dp(24));scroll.removeAllViews();scroll.addView(content);body.addView(scroll,new LayoutParams(-1,0,1));
        LinearLayout titleRow=new LinearLayout(activity);titleRow.setGravity(Gravity.CENTER_VERTICAL);titleRow.addView(heading(tab.equals("games")?"你的游戏":tab.equals("running")?"随时继续游戏":tab.equals("computers")?"你的电脑":"按你的习惯使用",26),new LayoutParams(0,-2,1));
        if(tab.equals("games")){Button choose=button(filter.isEmpty()?"全部电脑 ▾":hostName(filter)+" ▾",this::chooseComputer,false);choose.setTextSize(12);choose.setMaxWidth(dp(180));choose.setContentDescription("筛选电脑");titleRow.addView(choose,new LayoutParams(-2,dp(48)));}content.addView(titleRow);gap(content,16);
        message=text("",13,BLUE);message.setVisibility(GONE);content.addView(message);
        if(tab.equals("games")){
            search=new EditText(activity);search.setSingleLine();search.setTextColor(TEXT);search.setHintTextColor(MUTED);search.setTextSize(14);search.setHint("搜索游戏或电脑");search.setPadding(dp(14),0,dp(14),0);search.setBackground(bg(PANEL));search.setText(query);search.setContentDescription("搜索游戏");content.addView(search,new LayoutParams(-1,dp(48)));gap(content,20);
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();renderRows();}public void afterTextChanged(Editable e){}});
        }else{search=null;info(content,tab.equals("running")?"每个项目独立运行。返回列表和切换游戏都会保留电脑上的游戏。":tab.equals("computers")?"配对一次，即可查看这台电脑的全部共享项目。":"在手机上操作，在电脑上运行。");gap(content,18);}
        content.addView(rows);renderRows();renderTabs();scroll.post(()->scroll.scrollTo(0,scrolls.getOrDefault(tab,0)));
    }
    private String tabName(String id){return id.equals("games")?"游戏":id.equals("running")?"运行中":id.equals("computers")?"电脑":"设置";}
    private String hostName(String address){Catalog c=catalogs.get(address);return c==null?"电脑":c.name;}
    private int runningCount(){int n=0;for(Catalog c:catalogs.values())for(int i=0;i<c.projects.length();i++){JSONObject p=c.projects.optJSONObject(i);if(p!=null&&p.optString("state").equals("running"))n++;}return n;}
    private void renderTabs(){
        String[] ids={"games","running","computers","settings"},icons={"game","layers","monitor","settings"};
        if(tabItems.isEmpty())for(int i=0;i<ids.length;i++){
            final String id=ids[i];LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setContentDescription(tabName(id));item.setFocusable(true);
            RemoteIcon icon=new RemoteIcon(activity,icons[i],MUTED);item.addView(icon,new LayoutParams(dp(23),dp(23)));
            TextView caption=text(tabName(id),11,MUTED);caption.setGravity(Gravity.CENTER);item.addView(caption);
            item.setOnClickListener(v->showTab(id));tabs.addView(item,new LayoutParams(0,-1,1));tabItems.put(id,item);tabCaptions.put(id,caption);tabIcons.put(id,icon);
        }
        int count=runningCount();
        for(String id:ids){boolean selected=tab.equals(id);int color=selected?BLUE:MUTED;tabItems.get(id).setSelected(selected);tabIcons.get(id).setColor(color);TextView caption=tabCaptions.get(id);caption.setTextColor(color);String value=tabName(id)+(id.equals("running")&&count>0?" · "+count:"");if(!caption.getText().toString().equals(value))caption.setText(value);}
    }
    private void hydrateHosts(){
        HashSet<String> retained=new HashSet<>();JSONArray hosts=RemoteHosts.all();for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h==null)continue;String address=h.optString("address");if(address.isEmpty())continue;retained.add(address);Catalog c=catalogs.get(address);if(c==null){c=new Catalog();c.address=address;catalogs.put(address,c);}c.name=h.optString("computer","电脑");}
        catalogs.keySet().retainAll(retained);if(!filter.isEmpty()&&!catalogs.containsKey(filter)){filter="";preferences.edit().putString("filter","").apply();}
        for(CatalogRequest request:new ArrayList<>(inFlight.values()))if(catalogs.get(request.catalog.address)!=request.catalog){network.remove(request);inFlight.remove(request.catalog.address);}
    }
    void refresh(){
        hydrateHosts();if(!active)return;
        for(CatalogRequest request:new ArrayList<>(inFlight.values()))if(network.remove(request)){request.priority=priority(request.catalog);network.execute(request);}
        ArrayList<Catalog> ordered=new ArrayList<>(catalogs.values());Collections.sort(ordered,(a,b)->Integer.compare(priority(a),priority(b)));
        for(Catalog catalog:ordered)if(!inFlight.containsKey(catalog.address)){CatalogRequest request=new CatalogRequest(catalog);inFlight.put(catalog.address,request);network.execute(request);}
    }
    private int priority(Catalog catalog){return catalog.address.equals(filter)?0:catalog.fresh?1:catalog.online?2:3;}
    private void updateCatalog(Catalog catalog,JSONObject value,String error){
        String before=catalog.name+catalog.online+catalog.error+catalog.projects;catalog.fresh=false;catalog.online=value!=null;catalog.error=error;
        if(value!=null){catalog.name=value.optString("computer",catalog.name);JSONArray list=value.optJSONArray("games");catalog.projects=list==null?new JSONArray():list;}
        if(!before.equals(catalog.name+catalog.online+catalog.error+catalog.projects))scheduleRows();
    }
    private void scheduleRows(){if(renderPending)return;renderPending=true;ui.post(()->{renderPending=false;renderRows();renderTabs();});}
    private void renderRows(){
        int y=scroll.getScrollY();rows.removeAllViews();int online=0,projectCount=0;for(Catalog c:catalogs.values()){if(c.online)online++;projectCount+=c.projects.length();}subtitle.setText(tab.equals("computers")?"配对记录保存在本机":tab.equals("settings")?"Defold Remote":online+" 台电脑在线 · "+projectCount+" 个项目");
        if(tab.equals("settings")){settings();return;}
        if(tab.equals("computers")){renderComputers();return;}
        int count=0;for(Catalog c:catalogs.values()){if(tab.equals("games")&&!filter.isEmpty()&&!filter.equals(c.address))continue;for(int i=0;i<c.projects.length();i++){JSONObject p=c.projects.optJSONObject(i);if(p==null||!p.optString("path").matches("/games/[a-zA-Z0-9_-]+"))continue;String state=p.optString("state");if(tab.equals("running")&&!state.equals("running")&&!state.equals("preparing"))continue;if(!query.isEmpty()&&tab.equals("games")&&!(p.optString("name")+" "+c.name).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;project(c,p);count++;}}
        if(count==0){LinearLayout empty=card();empty.addView(heading(catalogs.isEmpty()?"把电脑上的游戏带到手机上":tab.equals("running")?"还没有运行中的游戏":"暂无匹配的项目",20));gap(empty,12);info(empty,catalogs.isEmpty()?"添加一台电脑，选择游戏后电脑会自动准备并运行。":"项目由电脑 Remote Manager 共享。电脑离线时保留配对和已有项目。");gap(empty,20);empty.addView(button(catalogs.isEmpty()?"添加电脑":"查看电脑",()->{if(catalogs.isEmpty())addComputer();else showTab("computers");},true));}
        for(Catalog c:catalogs.values())if(!c.online&&!c.error.isEmpty()&&(filter.isEmpty()||filter.equals(c.address))){LinearLayout warning=card();warning.addView(heading(c.name+" · 连接中断",16));gap(warning,8);info(warning,c.error);gap(warning,12);warning.addView(button("重试连接",this::refresh,false));}
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private void project(Catalog c,JSONObject p){
        String base=c.address+p.optString("path"),name=projectName(p),state=p.optString("state");
        if(tab.equals("games")){projectRow(c,p);return;}LinearLayout v=card();v.setContentDescription("项目 "+name);
        LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(heading(name,18),new LayoutParams(0,-2,1));top.addView(iconButton("more",name+" 的更多操作",()->projectMenu(c,p)),new LayoutParams(dp(48),dp(48)));v.addView(top);info(v,c.name);gap(v,8);
        TextView status=text((!c.online?"电脑离线 · ":"")+(state.equals("running")?"运行中":state.equals("preparing")?"正在准备":state.equals("error")?"需要处理":state.equals("ready")?"可运行":"待准备"),12,state.equals("running")?GREEN:state.equals("error")?0xffed8585:MUTED);v.addView(status);
        String controller=p.optString("controller");if(!controller.isEmpty())info(v,controller+" 正在操作");
        if(state.equals("preparing")){gap(v,8);info(v,p.optString("stage","电脑正在自动准备…"));}
        if(p.optBoolean("modified")){gap(v,8);info(v,"资源有更新，下次启动时使用新版本");}
        if(!p.optString("error").isEmpty()){gap(v,8);TextView error=text(p.optString("failed_stage")+"\n"+p.optString("error"),12,0xffed8585);error.setMaxLines(4);v.addView(error);}
        gap(v,14);String action=state.equals("running")?"继续游戏":state.equals("preparing")?"查看准备进度":state.equals("error")?"查看错误并重试":"开始游戏";Button enter=button(action,()->{if(state.equals("error"))projectError(c,p);else actions.connect(base);},state.equals("running")||!state.equals("preparing"));enter.setContentDescription(action+" · "+name);v.addView(enter,new LayoutParams(-1,dp(48)));
    }
    private void projectRow(Catalog c,JSONObject p){
        String base=c.address+p.optString("path"),name=projectName(p),state=p.optString("state");
        LinearLayout item=column();item.setPadding(0,dp(14),0,dp(14));item.setContentDescription("项目 "+name);rows.addView(item,new LayoutParams(-1,-2));
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);TextView art=heading(name.isEmpty()?"G":name.substring(0,1).toUpperCase(Locale.ROOT),25);art.setGravity(Gravity.CENTER);art.setTextColor(0xffacc9ee);art.setBackground(bg(0xff263b53));row.addView(art,new LayoutParams(dp(56),dp(56)));
        LinearLayout labels=column();labels.setPadding(dp(12),0,dp(4),0);TextView title=heading(name,16);title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);gap(labels,5);TextView computer=text(c.name,12,MUTED);computer.setSingleLine();labels.addView(computer);gap(labels,6);
        String status=!c.online?"电脑离线":state.equals("running")?"● 运行中":state.equals("preparing")?"正在准备":state.equals("error")?"编译失败":"未运行";
        labels.addView(text(status,11,state.equals("running")?GREEN:state.equals("error")?0xffed8585:MUTED));row.addView(labels,new LayoutParams(0,-2,1));
        Runnable open=()->{if(state.equals("error"))projectError(c,p);else actions.connect(base);};Button enter=button(state.equals("running")?"继续 ›":state.equals("preparing")?"查看 ›":state.equals("error")?"处理 ›":"开始 ▷",open,false);enter.setContentDescription((state.equals("running")?"继续游戏":state.equals("preparing")?"查看准备进度":state.equals("error")?"查看错误并重试":"开始游戏")+" · "+name);enter.setTextSize(12);enter.setTextColor(BLUE);enter.setBackgroundColor(Color.TRANSPARENT);row.addView(enter,new LayoutParams(dp(76),dp(48)));item.addView(row);item.setOnClickListener(v->open.run());item.setOnLongClickListener(v->{projectMenu(c,p);return true;});
        if(state.equals("preparing")&&!p.optString("stage").isEmpty()){gap(item,8);info(item,p.optString("stage"));}if(state.equals("error")&&!p.optString("error").isEmpty()){gap(item,8);TextView error=text(p.optString("failed_stage")+" · "+p.optString("error"),11,0xffed8585);error.setMaxLines(2);item.addView(error);}
        View line=new View(activity);line.setBackgroundColor(LINE);rows.addView(line,new LayoutParams(-1,dp(1)));
    }
    private void renderComputers(){
        for(Catalog c:catalogs.values()){LinearLayout v=card();LinearLayout head=new LinearLayout(activity);head.setGravity(Gravity.CENTER_VERTICAL);head.addView(heading(c.name,18),new LayoutParams(0,-2,1));head.addView(iconButton("more",c.name+" 的更多操作",()->computerMenu(c)),new LayoutParams(dp(48),dp(48)));v.addView(head);info(v,c.projects.length()+" 个共享项目");gap(v,10);v.addView(text((c.online?"在线 · ":"离线 · ")+c.address,12,c.online?GREEN:MUTED));if(!c.error.isEmpty()){gap(v,8);info(v,c.error);}gap(v,16);v.addView(button(c.online?"查看游戏":"检查连接",()->{if(c.online)showComputer(c.address);else computerMenu(c);},false),new LayoutParams(-1,dp(48)));}
        if(catalogs.isEmpty()){LinearLayout v=card();v.addView(heading("还没有连接的电脑",20));gap(v,12);info(v,"在电脑 Remote Manager 打开“连接手机”，然后添加电脑。");gap(v,18);v.addView(button("添加电脑",this::addComputer,true));}
        rows.addView(button("发现编辑器游戏  ›",actions::discover,false),new LayoutParams(-1,dp(52)));gap(rows,8);info(rows,"兼容原有编辑器 Game 面板直连模式。");
    }
    private static String projectName(JSONObject project){String name=project.optString("name","").trim();return name.isEmpty()?"Defold 游戏":name;}
    private void option(LinearLayout content,String label,boolean primary,Runnable run){Button button=button(label,run,primary);button.setContentDescription(label);content.addView(button,new LayoutParams(-1,dp(52)));gap(content,8);}
    private void chooseComputer(){
        LinearLayout content=column();Dialog[] dialog=new Dialog[1];
        option(content,(filter.isEmpty()?"✓  ":"")+"全部电脑",false,()->{dialog[0].dismiss();filter="";preferences.edit().putString("filter",filter).apply();showTab("games");refresh();});
        for(Catalog catalog:catalogs.values())option(content,(filter.equals(catalog.address)?"✓  ":"")+catalog.name,false,()->{dialog[0].dismiss();showComputer(catalog.address);});
        dialog[0]=actions.sheet("显示哪台电脑的游戏",content);
    }
    void addComputer(){
        LinearLayout content=column();info(content,"在电脑 Remote Manager 打开“连接手机”。手机与电脑需要处于可互通的局域网。");gap(content,18);Dialog[] dialog=new Dialog[1];
        option(content,"扫描电脑二维码",true,()->{dialog[0].dismiss();actions.scan();});
        option(content,"从相册识别",false,()->{dialog[0].dismiss();actions.album();});
        option(content,"粘贴完整配对链接",false,()->{dialog[0].dismiss();actions.paste();});
        dialog[0]=actions.sheet("添加电脑",content);
    }
    private void computerMenu(Catalog catalog){
        LinearLayout content=column();info(content,catalog.address+"\n"+(catalog.online?"已连接":catalog.error.isEmpty()?"等待连接":catalog.error));gap(content,16);Dialog[] dialog=new Dialog[1];
        option(content,"查看游戏",true,()->{dialog[0].dismiss();showComputer(catalog.address);});
        option(content,"重试连接",false,()->{dialog[0].dismiss();refresh();});
        option(content,"重新扫码更新地址",false,()->{dialog[0].dismiss();actions.scan();});
        option(content,"忘记这台电脑",false,()->{dialog[0].dismiss();new AlertDialog.Builder(activity).setTitle("忘记 "+catalog.name+"？").setMessage("只移除本机配对，下次需要重新扫码。不会删除电脑中的工程。").setPositiveButton("忘记",(d,w)->{RemoteHosts.remove(catalog.address);hydrateHosts();renderRows();renderTabs();}).setNegativeButton("取消",null).show();});
        dialog[0]=actions.sheet(catalog.name,content);
    }
    private void projectMenu(Catalog catalog,JSONObject project){
        LinearLayout content=column();info(content,catalog.name);gap(content,12);Dialog[] dialog=new Dialog[1];
        option(content,"继续或开始游戏",true,()->{dialog[0].dismiss();actions.connect(catalog.address+project.optString("path"));});
        option(content,"查看本次状态与错误",false,()->{dialog[0].dismiss();projectError(catalog,project);});
        option(content,"停止这款游戏",false,()->{dialog[0].dismiss();confirmStop(catalog,project);});
        dialog[0]=actions.sheet(projectName(project),content);
    }
    private void projectError(Catalog catalog,JSONObject project){
        String details=catalog.name+"\n"+project.optString("failed_stage",project.optString("stage",""))+"\n"+project.optString("error","当前没有错误。");
        LinearLayout content=column();TextView error=text(details,13,project.optString("error").isEmpty()?TEXT:0xffed8585);error.setTextIsSelectable(true);error.setTypeface(Typeface.MONOSPACE);error.setHorizontallyScrolling(false);ScrollView area=new ScrollView(activity);area.addView(error);content.addView(area,new LayoutParams(-1,Math.min(dp(220),getResources().getDisplayMetrics().heightPixels/3)));gap(content,14);Dialog[] dialog=new Dialog[1];
        option(content,"重试",true,()->{dialog[0].dismiss();actions.connect(catalog.address+project.optString("path"));});
        option(content,"复制完整日志",false,()->{((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Defold Remote",details));Toast.makeText(activity,"已复制日志",Toast.LENGTH_SHORT).show();});
        dialog[0]=actions.sheet(projectName(project),content);
    }
    private void confirmStop(Catalog catalog,JSONObject project){new AlertDialog.Builder(activity).setTitle("停止这款游戏？").setMessage(projectName(project)+" 将在 "+catalog.name+" 上停止。其他游戏继续运行。").setPositiveButton("停止游戏",(d,w)->actions.stop(catalog.address+project.optString("path"),this::refresh)).setNegativeButton("继续运行",null).show();}
    private void settings(){
        rows.addView(button("默认画质",actions::quality,false),new LayoutParams(-1,dp(52)));gap(rows,12);
        for(String key:new String[]{"showFps","keepAwake"}){Switch sw=new Switch(activity);sw.setText(key.equals("showFps")?"显示帧率":"游戏时屏幕常亮");sw.setTextColor(TEXT);sw.setTextSize(14);sw.setMinHeight(dp(52));sw.setChecked(preferences.getBoolean(key,key.equals("keepAwake")));sw.setOnCheckedChangeListener((v,on)->{preferences.edit().putBoolean(key,on).apply();if(key.equals("keepAwake")){if(on)activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}});rows.addView(sw);gap(rows,8);}
        rows.addView(button("连接与扫码帮助",()->new AlertDialog.Builder(activity).setTitle("连接与扫码").setMessage("1. 手机和电脑处于可互通的局域网。\n2. 电脑管理器打开“连接手机”。\n3. 从电脑页 + 扫码；识别困难时使用补光、2×、相册或完整链接。\n4. 配对成功后选择项目，电脑自动准备并运行。\n5. 返回列表保留电脑游戏；停止为单独操作。").setPositiveButton("关闭",null).show(),false));gap(rows,12);
        rows.addView(button("连接诊断",()->{StringBuilder value=new StringBuilder();for(Catalog c:catalogs.values())value.append(c.name).append(" · ").append(c.online?"在线":"离线").append("\n").append(c.address).append("\n").append(c.error).append("\n\n");new AlertDialog.Builder(activity).setTitle("连接诊断").setMessage(value.length()==0?"还没有配对电脑。":value.toString()).setPositiveButton("刷新",(d,w)->refresh()).setNegativeButton("关闭",null).show();},false));gap(rows,24);info(rows,"Defold Remote 0.8.1\n游戏和原生扩展在电脑本地构建，源码不上传云构建服务。");
    }
}
