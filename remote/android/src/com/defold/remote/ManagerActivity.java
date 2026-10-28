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
import android.os.*;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Computer catalog and shared project selector for the standalone desktop manager. */
public final class ManagerActivity extends Activity {
    private static final int BG=0xff1e2025,PANEL=0xff292d35,TEXT=0xffe5e8ed,MUTED=0xff929dac,BLUE=0xff4386e5;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView subtitle;
    private String address="",computer="",lastCatalog="";
    private int generation;
    private boolean active,loading;
    private final Runnable poll=new Runnable(){public void run(){if(active){refresh();ui.postDelayed(this,3000);}}};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private void gap(LinearLayout parent,int n){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(n)));}
    private Button button(String label,Runnable click){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(Color.WHITE);b.setBackgroundTintList(ColorStateList.valueOf(BLUE));b.setOnClickListener(v->click.run());return b;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);RemoteHosts.init(this);address=getIntent().getStringExtra("host");if(address==null)address="";if(saved!=null)address=saved.getString("host",address);show();}
    @Override public void onResume(){super.onResume();active=true;ui.post(poll);}
    @Override public void onPause(){active=false;++generation;loading=false;ui.removeCallbacks(poll);super.onPause();}
    @Override public void onDestroy(){network.shutdownNow();super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle saved){super.onSaveInstanceState(saved);saved.putString("host",address);}
    @Override public void onBackPressed(){if(!address.isEmpty()){address="";++generation;loading=false;show();refresh();}else super.onBackPressed();}
    private void scan(){Intent intent=new Intent(this,MainActivity.class);intent.putExtra("manager_scan",true);startActivity(intent);finish();}
    private void show(){
        lastCatalog="";
        LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(20),dp(12),dp(20),dp(16));setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20)+insets.getSystemWindowInsetLeft(),dp(12)+insets.getSystemWindowInsetTop(),dp(20)+insets.getSystemWindowInsetRight(),dp(16)+insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);Button back=button("‹",()->onBackPressed());top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView title=text(address.isEmpty()?"我的电脑":"游戏项目",24,TEXT);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setPadding(dp(10),0,0,0);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));top.addView(button("扫码",this::scan));root.addView(top);gap(root,12);
        subtitle=text(address.isEmpty()?"扫码配对电脑，选择项目开始体验。":address,13,MUTED);root.addView(subtitle);gap(root,20);
        ScrollView scroll=new ScrollView(this);list=column();scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(address.isEmpty())renderHosts();else list.addView(text("正在读取共享项目…",14,MUTED));
    }
    private LinearLayout card(){LinearLayout box=column();box.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable bg=new GradientDrawable();bg.setColor(PANEL);bg.setCornerRadius(dp(9));box.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);list.addView(box,lp);return box;}
    private void renderHosts(){
        list.removeAllViews();JSONArray hosts=RemoteHosts.all();
        if(hosts.length()==0){gap(list,36);list.addView(text("还没有连接的电脑",20,TEXT));gap(list,12);list.addView(text("在电脑上打开 Defold Remote Manager，点击“扫码连接电脑”。",14,MUTED));gap(list,24);list.addView(button("扫描电脑二维码",this::scan));return;}
        for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h==null)continue;String host=h.optString("address");LinearLayout c=card();TextView name=text(h.optString("computer","Defold Computer"),19,TEXT);c.addView(name);gap(c,5);c.addView(text(host,12,MUTED));gap(c,14);c.addView(button("查看项目",()->{address=host;computer=h.optString("computer");++generation;loading=false;show();refresh();}));c.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("忘记这台电脑？").setMessage("本机配对记录会移除，下次需要重新扫码。").setNegativeButton("取消",null).setPositiveButton("忘记",(d,w)->{RemoteHosts.remove(host);renderHosts();}).show();return true;});}
    }
    private void refresh(){
        if(address.isEmpty()||loading||!active)return;loading=true;final String host=address;final int epoch=generation;
        network.execute(()->{try{JSONObject result=RemoteHosts.request(host,RemoteHosts.token(host),"/games",null);ui.post(()->{if(epoch!=generation||!active)return;loading=false;computer=result.optString("computer","电脑");subtitle.setText(computer+" · 已连接");renderGames(result.optJSONArray("games"));});}
        catch(Exception e){ui.post(()->{if(epoch!=generation||!active)return;loading=false;lastCatalog="";subtitle.setText("连接中断 · "+RemoteTls.explain(e,host));list.removeAllViews();LinearLayout c=card();c.addView(text("正在尝试恢复电脑连接",17,TEXT));gap(c,12);c.addView(text("请保持电脑管理器运行，并确认手机和电脑处于可互通的局域网。",13,MUTED));c.addView(button("重试",this::refresh));});}});
    }
    private void renderGames(JSONArray games){
        String value=games==null?"[]":games.toString();if(value.equals(lastCatalog))return;lastCatalog=value;
        list.removeAllViews();if(games==null||games.length()==0){gap(list,24);list.addView(text("这台电脑还没有共享的项目",18,TEXT));gap(list,10);list.addView(text("在电脑管理器添加工程，并开启“在手机项目列表中显示”。",14,MUTED));return;}
        for(int i=0;i<games.length();i++){JSONObject game=games.optJSONObject(i);if(game==null)continue;String path=game.optString("path");if(!path.matches("/games/[a-zA-Z0-9-]+"))continue;String base=address+path;
            LinearLayout c=card();c.addView(text(game.optString("name","Defold 游戏"),19,TEXT));gap(c,8);String state=game.optString("state");String label=state.equals("running")?"运行中":state.equals("preparing")?"正在准备":state.equals("error")?"需要处理":state.equals("ready")?"可运行":"待准备";
            String controller=game.optString("controller");c.addView(text(label+(controller.isEmpty()?"":" · "+controller+" 正在操作"),12,state.equals("running")?0xff66ba98:MUTED));
            if(game.optBoolean("modified")){gap(c,6);c.addView(text("资源有更新，下次运行时使用新版本",12,MUTED));}
            if(!game.optString("error").isEmpty()){gap(c,8);TextView error=text(game.optString("failed_stage")+"\n"+game.optString("error"),12,0xffed8585);error.setMaxLines(6);c.addView(error);c.addView(button("查看完整错误",()->new AlertDialog.Builder(this).setTitle(game.optString("name")).setMessage(game.optString("failed_stage")+"\n"+game.optString("error")).setPositiveButton("关闭",null).show()));}
            gap(c,12);c.addView(button(state.equals("error")?"重新连接":"连接游戏",()->MainActivity.openManagedGame(this,base)));
        }
    }
}
