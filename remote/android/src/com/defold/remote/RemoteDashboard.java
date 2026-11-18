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
        void cloud(); void quality(); String qualityLabel(); void stop(String base,Runnable complete);
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
    private TextView hostTitle,hostStatus,message,qualityValue;
    private LinearLayout hostPicker; private View statusDot; private EditText search;
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
        filter=preferences.getString("filter","");tab=preferences.getString("tab","games");if(!"settings".equals(tab))tab="games";
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
    private View hostHeader(){
        LinearLayout header=new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16),dp(6),dp(8),dp(6));
        hostPicker=new LinearLayout(activity);
        hostPicker.setGravity(Gravity.CENTER_VERTICAL);
        hostPicker.setFocusable(true);
        hostPicker.setOnClickListener(v->chooseComputer());
        hostTitle=heading("",18);
        hostTitle.setSingleLine();
        hostTitle.setEllipsize(TextUtils.TruncateAt.END);
        hostPicker.addView(hostTitle);
        statusDot=new View(activity);
        LayoutParams dot=new LayoutParams(dp(6),dp(6));
        dot.setMargins(dp(8),0,dp(6),0);
        hostPicker.addView(statusDot,dot);
        hostStatus=text("",12,MUTED);
        hostPicker.addView(hostStatus);
        View arrow=symbol("down",MUTED);
        LayoutParams arrowSize=new LayoutParams(dp(16),dp(16));
        arrowSize.leftMargin=dp(4);
        hostPicker.addView(arrow,arrowSize);
        header.addView(hostPicker,new LayoutParams(-2,dp(48)));
        View route=iconButton("wifi","Wi-Fi · 切换到服务器",actions::cloud);
        route.setTooltipText("Wi-Fi · 切换到服务器");
        GradientDrawable badge=bg(0xff202020);
        badge.setCornerRadius(dp(6));
        route.setBackground(new android.graphics.drawable.InsetDrawable(badge,dp(9),dp(10),dp(9),dp(10)));
        header.addView(route,new LayoutParams(dp(48),dp(48)));
        header.addView(new View(activity),new LayoutParams(0,1,1));
        header.addView(iconButton("plus","添加电脑",this::addComputer),new LayoutParams(dp(48),dp(48)));
        // Leave room for both independent touch targets even with long computer names.
        Runnable sizeTitle=()->{
            int available=Math.max(dp(90),header.getWidth()-header.getPaddingLeft()-header.getPaddingRight()-dp(96));
            hostTitle.setMaxWidth(Math.max(dp(36),available-hostStatus.getMeasuredWidth()-dp(44)));
        };
        header.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->sizeTitle.run());
        hostStatus.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->sizeTitle.run());
        return header;
    }
    private void updateHostHeader(){
        Catalog c=catalogs.get(filter);
        String name=c==null?"选择电脑":c.name;
        String status=c==null?"未连接":c.online?"已连接":c.fresh?"连接中":"离线";
        hostTitle.setText(name);
        hostStatus.setText(status);
        GradientDrawable dot=new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(c!=null&&c.online?GREEN:MUTED);
        statusDot.setBackground(dot);
        hostPicker.setContentDescription(name+" · "+status+" · 选择电脑");
    }
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
        body.addView(hostHeader());
        View line=new View(activity);line.setBackgroundColor(LINE);body.addView(line,new LayoutParams(-1,dp(1)));
        if(scroll.getParent()!=null)((android.view.ViewGroup)scroll.getParent()).removeView(scroll);if(rows.getParent()!=null)((android.view.ViewGroup)rows.getParent()).removeView(rows);
        LinearLayout content=column();content.setPadding(dp(20),dp(22),dp(20),dp(24));scroll.removeAllViews();scroll.addView(content);body.addView(scroll,new LayoutParams(-1,0,1));
        content.addView(heading(tab.equals("games")?"你的游戏":"设置",26));gap(content,16);
        message=text("",13,BLUE);message.setVisibility(GONE);content.addView(message);
        if(tab.equals("games")){
            search=new EditText(activity);search.setSingleLine();search.setTextColor(TEXT);search.setHintTextColor(MUTED);search.setTextSize(14);search.setHint("搜索游戏或电脑");search.setPadding(dp(14),0,dp(14),0);search.setBackground(bg(PANEL));search.setText(query);search.setContentDescription("搜索游戏");content.addView(search,new LayoutParams(-1,dp(48)));gap(content,20);
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();renderRows();}public void afterTextChanged(Editable e){}});
        }else{search=null;}
        content.addView(rows);renderRows();renderTabs();scroll.post(()->scroll.scrollTo(0,scrolls.getOrDefault(tab,0)));
    }
    private String tabName(String id){return id.equals("games")?"游戏":"设置";}
    private void renderTabs(){
        String[] ids={"games","settings"},icons={"game","settings"};
        if(tabItems.isEmpty())for(int i=0;i<ids.length;i++){
            final String id=ids[i];LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setContentDescription(tabName(id));item.setFocusable(true);
            RemoteIcon icon=new RemoteIcon(activity,icons[i],MUTED);item.addView(icon,new LayoutParams(dp(23),dp(23)));
            TextView caption=text(tabName(id),11,MUTED);caption.setGravity(Gravity.CENTER);item.addView(caption);
            item.setOnClickListener(v->showTab(id));tabs.addView(item,new LayoutParams(0,-1,1));tabItems.put(id,item);tabCaptions.put(id,caption);tabIcons.put(id,icon);
        }
        for(String id:ids){boolean selected=tab.equals(id);int color=selected?BLUE:MUTED;tabItems.get(id).setSelected(selected);tabIcons.get(id).setColor(color);TextView caption=tabCaptions.get(id);caption.setTextColor(color);}
    }
    private void hydrateHosts(){
        HashSet<String> retained=new HashSet<>();JSONArray hosts=RemoteHosts.all();for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h==null)continue;String address=h.optString("address");if(address.isEmpty())continue;retained.add(address);Catalog c=catalogs.get(address);if(c==null){c=new Catalog();c.address=address;catalogs.put(address,c);}c.name=h.optString("computer","电脑");}
        catalogs.keySet().retainAll(retained);
        if(!catalogs.containsKey(filter)){
            String next=catalogs.isEmpty()?"":catalogs.keySet().iterator().next();
            if(!next.equals(filter)){filter=next;preferences.edit().putString("filter",filter).apply();}
        }
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
        boolean first=catalog.fresh;String before=catalog.name+catalog.online+catalog.error+catalog.projects;catalog.fresh=false;catalog.online=value!=null;catalog.error=error;
        if(value!=null){catalog.name=value.optString("computer",catalog.name);JSONArray list=value.optJSONArray("games");catalog.projects=list==null?new JSONArray():list;}
        if(first||!before.equals(catalog.name+catalog.online+catalog.error+catalog.projects))scheduleRows();
    }
    private void scheduleRows(){if(renderPending)return;renderPending=true;ui.post(()->{renderPending=false;if(tab.equals("settings"))updateHostHeader();else renderRows();renderTabs();});}
    private void renderRows(){
        int y=scroll.getScrollY();rows.removeAllViews();updateHostHeader();
        if(tab.equals("settings")){settings();return;}
        int count=0;for(Catalog c:catalogs.values()){if(!filter.equals(c.address))continue;for(int i=0;i<c.projects.length();i++){JSONObject p=c.projects.optJSONObject(i);if(p==null||!p.optString("path").matches("/games/[a-zA-Z0-9_-]+"))continue;if(!query.isEmpty()&&tab.equals("games")&&!(p.optString("name")+" "+c.name).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;projectRow(c,p);count++;}}
        if(count==0){LinearLayout empty=card();empty.addView(heading(catalogs.isEmpty()?"把电脑上的游戏带到手机上":"暂无匹配的项目",20));gap(empty,12);info(empty,catalogs.isEmpty()?"添加一台电脑，选择游戏后电脑会自动准备并运行。":"项目由电脑 Remote Manager 共享。电脑离线时保留配对和已有项目。");gap(empty,20);empty.addView(button(catalogs.isEmpty()?"添加电脑":"查看电脑",()->{if(catalogs.isEmpty())addComputer();else chooseComputer();},true));}
        for(Catalog c:catalogs.values())if(!c.online&&!c.error.isEmpty()&&(filter.isEmpty()||filter.equals(c.address))){LinearLayout warning=card();warning.addView(heading(c.name+" · 连接中断",16));gap(warning,8);info(warning,c.error);gap(warning,12);warning.addView(button("重试连接",this::refresh,false));}
        scroll.post(()->scroll.scrollTo(0,y));
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
    private static String projectName(JSONObject project){String name=project.optString("name","").trim();return name.isEmpty()?"Defold 游戏":name;}
    private void option(LinearLayout content,String label,boolean primary,Runnable run){Button button=button(label,run,primary);button.setContentDescription(label);content.addView(button,new LayoutParams(-1,dp(52)));gap(content,8);}
    void chooseComputer(){
        LinearLayout content=column();Dialog[] dialog=new Dialog[1];
        for(Catalog catalog:catalogs.values()){
            LinearLayout row=new LinearLayout(activity);
            String label=catalog.name+(filter.equals(catalog.address)?"  ✓":"");
            row.addView(button(label,()->{dialog[0].dismiss();showComputer(catalog.address);},false),new LayoutParams(0,dp(48),1));
            row.addView(iconButton("more",catalog.name+" 的更多操作",()->{dialog[0].dismiss();computerMenu(catalog);}),new LayoutParams(dp(48),dp(48)));
            content.addView(row);gap(content,8);
        }
        option(content,"添加电脑",false,()->{dialog[0].dismiss();addComputer();});
        dialog[0]=actions.sheet("选择电脑",content);
    }
    void addComputer(){
        LinearLayout content=column();info(content,"在电脑 Remote Manager 打开“连接手机”。手机与电脑需要处于可互通的局域网。");gap(content,18);Dialog[] dialog=new Dialog[1];
        option(content,"发现编辑器游戏",false,()->{dialog[0].dismiss();actions.discover();});
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
    private LinearLayout settingsGroup(String title){
        TextView caption=heading(title,13);
        caption.setTextColor(MUTED);caption.setPadding(dp(4),0,0,dp(8));
        rows.addView(caption);
        LinearLayout group=column();group.setBackground(bg(PANEL));
        group.setClipToOutline(true);rows.addView(group);
        return group;
    }
    private LinearLayout settingsRow(LinearLayout group,String label){
        if(group.getChildCount()>0){
            View line=new View(activity);line.setBackgroundColor(LINE);
            LayoutParams lp=new LayoutParams(-1,dp(1));lp.setMargins(dp(16),0,dp(16),0);
            group.addView(line,lp);
        }
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));row.setPadding(dp(16),0,dp(16),0);
        row.setForeground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x20ffffff),null,new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        row.addView(text(label,15,TEXT),new LayoutParams(0,-2,1));
        group.addView(row,new LayoutParams(-1,-2));
        return row;
    }
    private void settingsAction(LinearLayout group,String label,Runnable action,boolean quality){
        LinearLayout row=settingsRow(group,label);
        if(quality){
            qualityValue=text(actions.qualityLabel(),14,MUTED);
            qualityValue.setPadding(0,0,dp(8),0);row.addView(qualityValue);
        }
        View arrow=symbol("down",MUTED);arrow.setRotation(-90);
        row.addView(arrow,new LayoutParams(dp(16),dp(16)));
        row.setFocusable(true);row.setOnClickListener(v->action.run());
    }
    void updateQuality(){if(qualityValue!=null)qualityValue.setText(actions.qualityLabel());}
    private void settingsSwitch(LinearLayout group,String label,String key){
        LinearLayout row=settingsRow(group,label);
        Switch toggle=new Switch(activity);
        toggle.setContentDescription(label);toggle.setChecked(preferences.getBoolean(key,key.equals("keepAwake")));
        int[][] states={{android.R.attr.state_checked},{}};
        toggle.setThumbTintList(new android.content.res.ColorStateList(states,new int[]{TEXT,0xffb7b7b7}));
        toggle.setTrackTintList(new android.content.res.ColorStateList(states,new int[]{BLUE,0xff535353}));
        row.addView(toggle);
        row.setOnClickListener(v->toggle.toggle());
        toggle.setOnCheckedChangeListener((v,on)->{
            preferences.edit().putBoolean(key,on).apply();
            if(key.equals("keepAwake")){
                if(on)activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }
    private void settings(){
        LinearLayout preview=settingsGroup("游戏预览");
        settingsAction(preview,"预览清晰度",actions::quality,true);
        settingsSwitch(preview,"显示帧率","showFps");
        settingsSwitch(preview,"屏幕常亮","keepAwake");
        gap(rows,24);
        LinearLayout connections=settingsGroup("连接");
        settingsAction(connections,"连接诊断",this::showDiagnostics,false);
        settingsAction(connections,"扫码帮助",()->{
            LinearLayout content=column();
            info(content,"在电脑的 Remote Manager 打开“连接手机”，点手机右上角的 ＋ 扫描二维码。");
            gap(content,12);info(content,"手机和电脑需要能通过局域网互访。也可以从相册识别二维码或粘贴完整配对链接。");
            actions.sheet("扫码帮助",content);
        },false);
    }
    private void showDiagnostics(){
        LinearLayout content=column();
        if(catalogs.isEmpty())info(content,"还没有配对电脑。");
        for(Catalog c:catalogs.values()){
            content.addView(heading(c.name,16));gap(content,4);
            info(content,(c.online?"已连接":"未连接")+" · "+c.address);
            if(!c.error.isEmpty())info(content,c.error);
            gap(content,12);
        }
        Dialog[] sheet=new Dialog[1];
        option(content,"刷新连接",false,()->{sheet[0].dismiss();refresh();});
        sheet[0]=actions.sheet("连接诊断",content);
    }
}
