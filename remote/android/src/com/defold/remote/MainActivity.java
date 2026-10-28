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

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
    private static volatile String internalGame = "";
    private String homeTab="computers";
    static void openManagedGame(android.content.Context context,String base){internalGame=base;context.startActivity(new Intent(context,MainActivity.class).putExtra("manager_game",base));}
    private static final int BG=0xff181818, PANEL=0xff222222, INPUT=0xff333333, LINE=0xff303030,
        TEXT=0xffededed, MUTED=0xffaaa9a6, ACCENT=0xff4386e5, PRIMARY=0xff286bcc, WARM=0xff233650;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService commands=Executors.newSingleThreadExecutor();
    private final ExecutorService frames=Executors.newSingleThreadExecutor();
    private final ExecutorService discovery=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService inputs=Executors.newSingleThreadScheduledExecutor();
    private final PointerBuffer pointerBuffer=new PointerBuffer();
    private final FrameRate frameRate=new FrameRate();
    private final LinkedHashMap<String,Game> games=new LinkedHashMap<>();
    private final LinkedHashMap<String,Bitmap> thumbnails=new LinkedHashMap<>();
    private SharedPreferences preferences;
    private FrameLayout root;
    private StreamView screen;
    private VideoSurface videoSurface;
    private volatile String videoMode="JPEG";
    private TextView homeStatus, overlay;
    private LinearLayout cards;
    private ScrollView scroll;
    private QRScanner scanner;
    private volatile Session session;
    private volatile String page="home";
    private volatile boolean foreground, discovering;
    private volatile int generation;
    private volatile HttpURLConnection streamConnection;
    private RemoteAudioPlayer audioPlayer;
    private boolean pressed, resumeStream, resumePaused, controlBusy;
    private volatile boolean paused;
    private volatile int inputEpoch;
    private LinearLayout controlPanel;
    private TextView fpsLabel,controlStatus;
    private Button runButton,stopButton,qualityButton;
    private volatile String streamQuality="720p";
    private int frameWidth,frameHeight;
    private int pointerX,pointerY;
    private long lastMove;
    private String resumeAddress="", lastName="";
    private RemoteAddress pendingExternal;
    private Dialog activeSheet;

    private static final class Game {
        String id,address,name,computer,state; long seen;
        Game(String id,String address,String name,String computer,String state,long seen){this.id=id;this.address=address;this.name=name;this.computer=computer;this.state=state;this.seen=seen;}
    }
    private static final class Session {
        final String base,token,id,name;
        final boolean qualitySupported,h264,audio;
        int deviceRevision;
        Session(String base,String token,String id,String name,boolean qualitySupported,boolean h264,boolean audio){this.audio=audio;this.h264=h264;this.base=base;this.token=token;this.id=id;this.name=name;this.qualitySupported=qualitySupported;}
    }
    private static final class Reply {
        final int status; final byte[] data;
        Reply(int status,byte[] data){this.status=status;this.data=data;}
        JSONObject json() throws Exception {return new JSONObject(new String(data,StandardCharsets.UTF_8));}
    }
    private static final class RequestError extends Exception {
        final int status;
        RequestError(int status,String message){super(message);this.status=status;}
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable outlined(int color,int radius){GradientDrawable d=background(color,radius);d.setStroke(dp(1),LINE);return d;}
    private void clickable(View view,int color,int radius,Runnable action){
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),outlined(color,radius),background(Color.WHITE,radius)));
        view.setOnClickListener(v->action.run());view.setFocusable(true);
    }
    private Button button(String text,Runnable action){
        Button b=new Button(this);b.setText(text);b.setTextColor(TEXT);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setAllCaps(false);
        b.setMinWidth(0);b.setMinimumHeight(dp(48));b.setPadding(dp(16),0,dp(16),0);b.setStateListAnimator(null);clickable(b,PANEL,14,action);return b;
    }
    private Button primary(String text,Runnable action){Button b=button(text,action);clickable(b,PRIMARY,14,action);b.setTextColor(Color.WHITE);return b;}
    private View icon(String kind,int color){return new RemoteIcon(this,kind,color);}
    private View iconButton(String kind,String description,Runnable action){
        FrameLayout holder=new FrameLayout(this);holder.setContentDescription(description);clickable(holder,Color.TRANSPARENT,12,action);
        FrameLayout.LayoutParams symbol=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER);holder.addView(icon(kind,TEXT),symbol);return holder;
    }
    private LinearLayout column(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);return box;}
    private TextView title(String text,int size){TextView t=label(text,size,TEXT);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void gap(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private TextView badge(String text,int color,int fill){TextView t=label(text,11,color);t.setPadding(dp(9),dp(5),dp(9),dp(5));t.setBackground(background(fill,7));return t;}
    private LinearLayout pageHeader(String name,Runnable back){
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(8),dp(16),dp(8));
        bar.addView(iconButton("back","返回",back),new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView text=title(name,18);text.setPadding(dp(8),0,0,0);bar.addView(text,new LinearLayout.LayoutParams(0,-2,1));return bar;
    }
    private Dialog sheet(String heading,LinearLayout content){
        if(activeSheet!=null)activeSheet.dismiss();
        Dialog dialog=new Dialog(this);activeSheet=dialog;dialog.setOnDismissListener(d->{if(activeSheet==dialog)activeSheet=null;});dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout surface=column();surface.setPadding(dp(20),dp(10),dp(20),dp(20));surface.setBackground(outlined(PANEL,24));
        View handle=new View(this);handle.setBackground(background(0xff555451,2));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(32),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;surface.addView(handle,hp);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(title(heading,20),new LinearLayout.LayoutParams(0,-2,1));row.addView(iconButton("close","关闭",dialog::dismiss),new LinearLayout.LayoutParams(dp(48),dp(48)));surface.addView(row);gap(surface,8);surface.addView(content);
        ScrollView area=new ScrollView(this);area.setFillViewport(false);area.addView(surface);dialog.setContentView(area);
        Window window=dialog.getWindow();window.setBackgroundDrawableResource(android.R.color.transparent);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.6f);
        dialog.show();window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(560)),-2);
        return dialog;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);RemoteHosts.init(this);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);preferences=getPreferences(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        streamQuality=preferences.getString("streamQuality","720p");homeTab=preferences.getString("homeTab","computers");loadHistory(); showHome("");
        if(state!=null){resumeStream=state.getBoolean("resumeStream");resumeAddress=state.getString("resumeAddress","");resumePaused=state.getBoolean("resumePaused");}
        if(state!=null || !handleIntent(getIntent())){
            if(state==null) {resumeAddress=preferences.getString("lastAddress","");resumeStream=!resumeAddress.isEmpty()&&!resumeAddress.startsWith("https:");}
        }
        inputs.scheduleWithFixedDelay(this::sendPointer,0,8,TimeUnit.MILLISECONDS);
        monitor.scheduleWithFixedDelay(()->{
            if(!foreground)return;
            if(page.equals("home"))refreshDiscovery();
            else if(page.equals("stream"))pollState();
        },0,2,TimeUnit.SECONDS);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);}
    private boolean handleIntent(Intent intent){
        if(intent==null)return false;
        if(intent.getBooleanExtra("manager_scan",false)){intent.removeExtra("manager_scan");resumeStream=false;ui.post(this::scan);return true;}
        String managed=intent.getStringExtra("manager_game");
        if(managed!=null&&managed.equals(internalGame)){internalGame="";intent.removeExtra("manager_game");try{RemoteAddress target=new RemoteAddress(managed,"");resumeStream=false;if(foreground)connect(target);else pendingExternal=target;}catch(Exception e){showHome(e.getMessage());}return true;}
        if(intent.getData()==null&&!intent.hasExtra("address"))return false;
        try{
            RemoteAddress address=intent.getData()!=null?RemoteAddress.fromLink(intent.getData().toString()):new RemoteAddress(intent.getStringExtra("address"),intent.hasExtra("pairing_code")?intent.getStringExtra("pairing_code"):intent.getStringExtra("code"));
            if(address.code.isEmpty())throw new Exception("外部连接缺少配对码");
            resumeStream=false; if(foreground)connect(address);else pendingExternal=address;
        }catch(Exception e){showHome(e.getMessage());resumeStream=false;}
        return true;
    }
    @Override public void onResume(){super.onResume();foreground=true;ui.post(heartbeat);
        if(pendingExternal!=null){RemoteAddress target=pendingExternal;pendingExternal=null;connect(target);}
        else if(resumeStream&&!resumeAddress.isEmpty()){resumeStream=false;try{connect(new RemoteAddress(resumeAddress,""),!resumePaused);}catch(Exception e){showHome(e.getMessage());}}
        else if(page.equals("home"))refreshDiscovery();
        else if(page.equals("scan")&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)scan();
        applySystemBars();
    }
    @Override public void onStop(){
        resumeStream=page.equals("stream")||page.equals("connecting");resumePaused=paused;
        if(session!=null)resumeAddress=session.base;
        foreground=false;if(activeSheet!=null)activeSheet.dismiss();ui.removeCallbacks(heartbeat);endInput();releaseManaged();++generation;session=null;closeStream();closeCamera();super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("resumeStream",resumeStream||page.equals("stream")||page.equals("connecting"));out.putString("resumeAddress",resumeAddress);out.putBoolean("resumePaused",paused);}
    @Override public void onDestroy(){closeCamera();closeStream();commands.shutdownNow();frames.shutdownNow();discovery.shutdownNow();monitor.shutdownNow();inputs.shutdown();super.onDestroy();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(page.equals("home"))renderCards();else if(page.equals("scan"))scan();else if(controlPanel!=null)positionControls();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)applySystemBars();}
    @Override public void onBackPressed(){if(page.equals("stream"))showControls("");else if(!page.equals("home"))goHome("");else super.onBackPressed();}
    private void applySystemBars(){
        boolean immersive=page.equals("stream")||page.equals("connecting");
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c=getWindow().getInsetsController();
            if(c!=null){c.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);if(immersive){c.hide(WindowInsets.Type.systemBars());c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}else c.show(WindowInsets.Type.systemBars());}
        }else getWindow().getDecorView().setSystemUiVisibility(immersive?View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE:View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private void newRoot(){if(activeSheet!=null)activeSheet.dismiss();controlPanel=null;fpsLabel=null;root=new FrameLayout(this);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{boolean full=page.equals("stream")||page.equals("connecting");v.setPadding(full?0:insets.getSystemWindowInsetLeft(),full?0:insets.getSystemWindowInsetTop(),full?0:insets.getSystemWindowInsetRight(),full?0:insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);applySystemBars();}
    private void goHome(String message){endInput();releaseManaged();++generation;session=null;resumeStream=false;paused=false;controlBusy=false;frameRate.reset();closeStream();closeCamera();if(screen!=null)screen.clear();showHome(message);refreshDiscovery();}
    private void releaseManaged(){Session current=session;if(current!=null&&current.base.startsWith("https:"))try{inputs.execute(()->{try{request(current.base,current.token,"POST","/release",new byte[0]);}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}}
    private void showHome(String message){
        page="home";setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);newRoot();
        LinearLayout body=column();root.addView(body,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),dp(10),dp(12),dp(10));
        ImageView brand=new ImageView(this);brand.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));header.addView(brand,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView heading=title("Defold Remote",19);heading.setPadding(dp(12),0,0,0);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(iconButton("refresh","刷新",()->{if(homeTab.equals("computers"))showHome("");else refreshDiscovery();}),new LinearLayout.LayoutParams(dp(48),dp(48)));body.addView(header);divider(body);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(20),dp(12),dp(20),dp(4));
        for(String tab:new String[]{"computers","discovery"}){Button item=button(tab.equals("computers")?"扫码电脑":"自动发现",()->{homeTab=tab;preferences.edit().putString("homeTab",tab).apply();showHome("");refreshDiscovery();});item.setSelected(homeTab.equals(tab));item.setTextColor(homeTab.equals(tab)?TEXT:MUTED);item.setBackgroundTintList(ColorStateList.valueOf(homeTab.equals(tab)?PRIMARY:PANEL));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.rightMargin=dp(6);tabs.addView(item,lp);}body.addView(tabs);
        if(homeTab.equals("computers")){showComputers(body,message);return;}
        scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setPadding(dp(20),dp(22),dp(20),dp(24));scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content=column();scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout gamesHeading=new LinearLayout(this);gamesHeading.setGravity(Gravity.CENTER_VERTICAL);
        gamesHeading.addView(title("自动发现",28),new LinearLayout.LayoutParams(0,-2,1));
        gamesHeading.addView(iconButton("link","手动连接",()->manual(null)),new LinearLayout.LayoutParams(dp(48),dp(48)));
        content.addView(gamesHeading);gap(content,12);content.addView(label("发现局域网中的独立编辑器游戏。已配对电脑的共享项目请在“扫码电脑”中打开。",13,MUTED));gap(content,20);
        homeStatus=label("",12,ACCENT);homeStatus.setPadding(0,0,0,dp(16));content.addView(homeStatus);
        homeMessage(message);
        cards=column();content.addView(cards);renderCards();
    }
    private void showComputers(LinearLayout body,String message){
        cards=null;scroll=new ScrollView(this);scroll.setPadding(dp(20),dp(22),dp(20),dp(24));LinearLayout content=column();scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content.addView(title("扫码电脑",28));gap(content,10);content.addView(label("扫描电脑管理器的二维码。配对一次，即可连接它共享的游戏。",14,MUTED));gap(content,20);
        content.addView(primary("扫描电脑二维码",this::scan));gap(content,16);homeStatus=label("",12,ACCENT);content.addView(homeStatus);homeMessage(message);
        JSONArray hosts=RemoteHosts.all();
        if(hosts.length()==0){gap(content,24);content.addView(label("还没有配对的电脑",17,MUTED));}
        for(int i=0;i<hosts.length();i++){JSONObject host=hosts.optJSONObject(i);if(host==null)continue;String address=host.optString("address");LinearLayout card=column();card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(outlined(PANEL,12));card.addView(title(host.optString("computer","电脑"),18));gap(card,8);card.addView(label(address,12,MUTED));gap(card,14);card.addView(button("查看共享项目",()->openComputer(address)));content.addView(card);gap(content,12);}
        if(hosts.length()>0)content.addView(button("管理已配对电脑",()->startActivity(new Intent(this,ManagerActivity.class))));
    }
    private void homeMessage(String message){
        boolean useful=message!=null&&!message.isEmpty()&&!message.startsWith("已")&&!message.startsWith("游戏已");
        homeStatus.setText(useful?message:"");homeStatus.setVisibility(useful?View.VISIBLE:View.GONE);
    }
    private void renderCards(){
        if(!page.equals("home")||!homeTab.equals("discovery")||cards==null)return;
        int scrollY=scroll.getScrollY();cards.removeAllViews();
        ArrayList<Game> online=new ArrayList<>(),offline=new ArrayList<>();String last=preferences.getString("lastId","");
        Comparator<Game> order=(a,b)->a.id.equals(b.id)?0:a.id.equals(last)?-1:b.id.equals(last)?1:(a.computer+a.name).compareTo(b.computer+b.name);
        for(Game game:games.values()){
            if(game.address.startsWith("https:"))continue;
            if(game.address.startsWith("http:")&&!RemoteHosts.pairedComputer(game.address).isEmpty())continue;
            (System.currentTimeMillis()-game.seen<10000?online:offline).add(game);
        }
        Collections.sort(online,order);Collections.sort(offline,order);
        if(online.isEmpty()&&offline.isEmpty()){
            LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(38),dp(24),dp(34));empty.setBackground(outlined(PANEL,18));
            empty.addView(icon("monitor",MUTED),new LinearLayout.LayoutParams(dp(44),dp(44)));gap(empty,16);empty.addView(title("暂无游戏",17));cards.addView(empty);
        }else{
            gameRows(online,last,true);
            gameRows(offline,last,false);
        }
        scroll.post(()->scroll.scrollTo(0,scrollY));
    }
    private void gameRows(ArrayList<Game> list,String last,boolean online){
        boolean wide=getResources().getConfiguration().screenWidthDp>=600;LinearLayout row=null;int position=0;
        for(Game game:list){
            LinearLayout card=gameCard(game,last,online);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(wide?0:-1,-2,wide?1:0);size.bottomMargin=dp(16);
            if(wide){if(position%2==0){row=new LinearLayout(this);cards.addView(row);size.rightMargin=dp(12);}row.addView(card,size);}else cards.addView(card,size);position++;
        }
        if(wide&&position%2==1&&row!=null)row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
    }
    private LinearLayout gameCard(Game game,String last,boolean online){
        boolean recent=game.id.equals(last),running=online&&game.state.equals("running");
        String state=!online?"离线":running?"运行中":game.state.equals("starting")?"正在启动":"可启动";
        LinearLayout card=column();clickable(card,running?0xff28211d:PANEL,18,()->openGame(game));card.setClipToOutline(true);
        FrameLayout preview=new FrameLayout(this){@Override protected void onMeasure(int w,int h){super.onMeasure(w,MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w)*9/16,MeasureSpec.EXACTLY));}};
        preview.setBackgroundColor(0xff1c1c1c);Bitmap picture=thumbnails.get(game.id);
        if(picture!=null){ImageView image=new ImageView(this);image.setImageBitmap(picture);image.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.addView(image,new FrameLayout.LayoutParams(-1,-1));}
        else{LinearLayout placeholder=column();placeholder.setGravity(Gravity.CENTER);placeholder.addView(icon("monitor",online?ACCENT:MUTED),new LinearLayout.LayoutParams(dp(36),dp(36)));preview.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));}
        TextView status=badge(state,running?ACCENT:TEXT,running?WARM:0xe6222222);FrameLayout.LayoutParams statusSize=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);statusSize.setMargins(dp(12),dp(12),0,0);preview.addView(status,statusSize);
        if(recent){TextView saved=badge("上次连接",TEXT,0xe6222222);FrameLayout.LayoutParams savedSize=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);savedSize.setMargins(0,dp(12),dp(12),0);preview.addView(saved,savedSize);}card.addView(preview,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout info=column();info.setPadding(dp(16),dp(15),dp(12),dp(12));
        TextView name=title(game.name,18);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);info.addView(name);gap(info,6);
        TextView computer=label(game.computer,12,MUTED);computer.setSingleLine();computer.setEllipsize(TextUtils.TruncateAt.END);info.addView(computer);gap(info,10);
        LinearLayout foot=new LinearLayout(this);foot.setGravity(Gravity.CENTER_VERTICAL);TextView enter=label(online?"进入游戏":"尝试连接",13,online?ACCENT:MUTED);foot.addView(enter);View arrow=icon("arrow",online?ACCENT:MUTED);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(16),dp(16));ap.leftMargin=dp(6);foot.addView(arrow,ap);foot.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));foot.addView(iconButton("more",game.name+"的操作",()->gameMenu(game)),new LinearLayout.LayoutParams(dp(48),dp(48)));info.addView(foot);card.addView(info);
        card.setContentDescription(game.name+"，"+state+"，点击连接");return card;
    }
    private void openGame(Game game){try{
        String computer=RemoteHosts.pairedComputer(game.address);
        if(game.address.startsWith("http:")&&!computer.isEmpty()){openComputer(computer);return;}
        if(game.address.startsWith("https:")){if(token(game.address).isEmpty())openComputer(RemoteTls.origin(game.address));else connect(new RemoteAddress(game.address,""));return;}
        if(token(game.address).isEmpty())manual(game);else connect(new RemoteAddress(game.address,""));
    }catch(Exception e){homeMessage(e.getMessage());}}
    private void openComputer(String address){resumeStream=false;startActivity(new Intent(this,ManagerActivity.class).putExtra("host",address));}
    private String token(String address){return address.startsWith("https:")?RemoteHosts.token(address):preferences.getString("token:"+address,"");}
    private void gameMenu(Game game){
        LinearLayout options=column();Dialog[] holder=new Dialog[1];
        boolean managed=game.address.startsWith("https:")||!RemoteHosts.pairedComputer(game.address).isEmpty();
        String[] names={"连接游戏","停止电脑上的游戏",managed?"查看所属电脑":"重新配对","移除连接记录"};String[] icons={"play","stop","scan","close"};
        for(int index=0;index<names.length;index++){final int choice=index;
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(8),dp(12),dp(8));
            row.addView(icon(icons[index],index==1?ACCENT:MUTED),new LinearLayout.LayoutParams(dp(22),dp(22)));TextView text=label(names[index],15,TEXT);text.setPadding(dp(14),0,0,0);row.addView(text);clickable(row,PANEL,12,()->{holder[0].dismiss();
                if(choice==0)openGame(game);else if(choice==2)manual(game);else if(choice==3){games.remove(game.id);preferences.edit().remove("token:"+game.address).apply();if(preferences.getString("lastId","").equals(game.id))preferences.edit().remove("lastId").remove("lastAddress").apply();saveHistory();renderCards();}
                else commands.execute(()->{try{request(game.address,token(game.address),"POST","/stop",new byte[0]);ui.post(()->{game.state="stopped";renderCards();});}catch(Exception e){ui.post(()->homeMessage(e.getMessage()));}});
            });options.addView(row,new LinearLayout.LayoutParams(-1,dp(56)));if(index<names.length-1)gap(options,4);
        }
        holder[0]=sheet(game.name,options);
    }
    private EditText field(String hint,boolean numeric){
        EditText input=new EditText(this);input.setSingleLine();input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setTextSize(15);input.setTypeface(Typeface.MONOSPACE);input.setPadding(dp(14),dp(12),dp(14),dp(12));
        input.setInputType(numeric?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);input.setBackground(outlined(INPUT,12));input.setHint(hint);return input;
    }
    private void manual(Game game){
        if(game!=null){String host=RemoteHosts.pairedComputer(game.address);if(!host.isEmpty()){openComputer(host);return;}if(game.address.startsWith("https:")){openComputer(RemoteTls.origin(game.address));return;}}
        LinearLayout fields=column();
        fields.addView(label("电脑地址",13,TEXT));gap(fields,8);EditText address=field("192.168.1.10:8390",false);address.setText(game==null?preferences.getString("address",""):game.address);fields.addView(address,new LinearLayout.LayoutParams(-1,dp(52)));gap(fields,20);
        fields.addView(label("配对码",13,TEXT));gap(fields,8);EditText code=field("输入 8 位配对码",true);code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});fields.addView(code,new LinearLayout.LayoutParams(-1,dp(52)));gap(fields,10);
        TextView error=label("",13,ACCENT);error.setVisibility(View.GONE);fields.addView(error);gap(fields,14);
        Dialog[] holder=new Dialog[1];Button connect=primary("连接游戏",()->{try{RemoteAddress target=new RemoteAddress(address.getText().toString(),code.getText().toString());if(target.code.isEmpty())throw new Exception("请输入 Game 面板上的配对码");holder[0].dismiss();connect(target);}catch(Exception e){error.setText(e.getMessage());error.setVisibility(View.VISIBLE);}});fields.addView(connect,new LinearLayout.LayoutParams(-1,dp(50)));
        holder[0]=sheet(game==null?"连接电脑":game.name,fields);
    }
    private void showStream(String message){
        page="connecting";newRoot();videoSurface=new VideoSurface(this);root.addView(videoSurface,new FrameLayout.LayoutParams(-1,-1));screen=new StreamView();root.addView(screen,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout connection=column();connection.setPadding(dp(28),dp(24),dp(28),dp(24));connection.setGravity(Gravity.CENTER);connection.setBackgroundColor(BG);
        connection.addView(icon("monitor",ACCENT),new LinearLayout.LayoutParams(dp(44),dp(44)));gap(connection,22);
        overlay=title(message,20);overlay.setGravity(Gravity.CENTER);connection.addView(overlay);gap(connection,12);
        TextView caption=label("连接成功后自动进入全屏",14,MUTED);caption.setGravity(Gravity.CENTER);connection.addView(caption);gap(connection,24);
        ProgressBar progress=new ProgressBar(this);progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));connection.addView(progress,new LinearLayout.LayoutParams(dp(26),dp(26)));gap(connection,28);
        connection.addView(button("返回首页",()->goHome("已取消连接")),new LinearLayout.LayoutParams(dp(180),dp(48)));ScrollView cover=new ScrollView(this);cover.setFillViewport(true);cover.setBackgroundColor(BG);cover.addView(connection);root.addView(cover,new FrameLayout.LayoutParams(-1,-1));overlay.setTag(cover);
    }
    private void connect(RemoteAddress target){connect(target,true);}
    private void connect(RemoteAddress target,boolean autoStart){
        connect(target,autoStart,false);
    }
    private void connect(RemoteAddress target,boolean autoStart,boolean takeover){
        endInput();releaseManaged();closeCamera();closeStream();session=null;paused=false;controlBusy=false;frameRate.reset();final int attempt=++generation;resumeAddress=target.base;showStream("正在连接电脑…");
        commands.execute(()->{
            try{
                boolean managed=target.base.startsWith("https:");
                if(managed&&!target.base.contains("/games/")){
                    if(!target.code.isEmpty())RemoteHosts.pair(target);
                    if(token(target.base).isEmpty())throw new Exception("请扫描电脑管理器中的二维码");
                    ui.post(()->{if(attempt!=generation||!foreground)return;goHome("");startActivity(new Intent(this,ManagerActivity.class).putExtra("host",target.base));});return;
                }
                String base=target.code.isEmpty()&&!managed?rediscover(target.base):target.base;
                if(!base.equals(target.base)&&!token(target.base).isEmpty())preferences.edit().putString("token:"+base,token(target.base)).apply();
                if(!base.contains("/games/")){
                    try{JSONArray list=request(base,"","GET","/games",null).json().getJSONArray("games");
                        if(list.length()!=1)throw new Exception("这台电脑有多个游戏，请从首页点选或扫描游戏二维码");
                        base=base+list.getJSONObject(0).getString("path");
                    }catch(RequestError e){if(e.status!=404)throw e;}
                }
                if(attempt!=generation || !foreground)return;
                String credential=token(base);
                if(!target.code.isEmpty())credential=request(base,"","POST","/pair",new JSONObject().put("code",target.code).toString().getBytes(StandardCharsets.UTF_8)).json().getString("token");
                if(credential.isEmpty())throw new Exception(managed?"电脑配对已失效，请重新扫描电脑管理器的二维码":"需要重新配对，请扫描电脑 Game 面板的二维码");
                JSONObject status=startupRequest(base,credential,"GET","/status",null,attempt).json();
                if(attempt!=generation || !foreground)return;
                if(autoStart&&!status.optString("state").equals("running")){ui.post(()->{if(attempt==generation)overlay.setText("正在启动游戏…");});startupRequest(base,credential,"POST","/start",new byte[0],attempt);}
                long deadline=System.currentTimeMillis()+(managed?1260000:30000);
                while(autoStart&&!status.optString("state").equals("running")){
                    if(attempt!=generation || !foreground)return;
                    if(System.currentTimeMillis()>deadline)throw new Exception("游戏启动超时，请查看电脑端的构建结果");
                    Thread.sleep(300);status=startupRequest(base,credential,"GET","/status",null,attempt).json();
                    if(managed){String stage=status.optString("stage","正在启动游戏…");ui.post(()->{if(attempt==generation&&!TextUtils.equals(overlay.getText(),stage))overlay.setText(stage);});}
                    if(status.optString("state").equals("error"))throw new Exception(status.optString("failed_stage","")+"\n"+status.optString("error","游戏启动失败"));
                }
                String id=status.optString("id",base),name=status.optString("name","Defold 游戏");
                if(managed&&status.optString("state").equals("running")){
                    try{status=startupRequest(base,credential,"POST","/claim",new JSONObject().put("takeover",takeover).toString().getBytes(StandardCharsets.UTF_8),attempt).json();credential=status.getString("token");}
                    catch(RequestError e){if(e.status!=409)throw e;ui.post(()->{if(attempt!=generation||!foreground)return;new AlertDialog.Builder(this).setTitle("接管游戏？").setMessage(e.getMessage()+"。接管后另一台设备会断开控制。").setNegativeButton("取消",(d,w)->goHome("")).setPositiveButton("接管",(d,w)->connect(target,autoStart,true)).show();});return;}
                }
                Session connected=new Session(base,credential,id,name,status.has("resolutions"),status.optJSONArray("video_codecs")!=null&&status.getJSONArray("video_codecs").toString().contains("h264"),status.optBoolean("audio",false));
                connected.deviceRevision=status.optInt("device_revision",0);
                ui.post(()->{if(attempt!=generation||!foreground)return;session=connected;lastName=connected.name;page="stream";applySystemBars();overlay.setText("正在接收游戏画面…");if(autoStart)frames.execute(()->readStream(connected,attempt));else pauseRemote("游戏已停止，点击运行继续");});
            }catch(Exception e){android.util.Log.e("DefoldRemote","Connection failed: "+RemoteTls.origin(target.base),e);ui.post(()->{if(attempt==generation){if(target.base.startsWith("https:")&&target.base.contains("/games/"))showBuildFailure(target,RemoteTls.explain(e,target.base));else goHome(RemoteTls.explain(e,target.base));}});}
        });
    }
    private void showBuildFailure(RemoteAddress target,String error){
        goHome("连接未完成，请查看错误详情");
        LinearLayout content=column();TextView details=label(error==null?"启动失败":error,12,TEXT);details.setTextIsSelectable(true);
        ScrollView area=new ScrollView(this);area.addView(details);content.addView(area,new LinearLayout.LayoutParams(-1,dp(260)));gap(content,16);
        content.addView(primary("重新连接",()->connect(target)));gap(content,8);
        content.addView(button("返回电脑项目列表",()->{if(activeSheet!=null)activeSheet.dismiss();startActivity(new Intent(this,ManagerActivity.class).putExtra("host",RemoteTls.origin(target.base)));}));
        sheet("无法连接项目",content);
    }
    // A slow status/claim response is not a build failure. These operations are idempotent
    // for this host/device, so retry bounded transport failures while the UI stays cancellable.
    private Reply startupRequest(String base,String token,String method,String path,byte[] body,int attempt)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+45000;
        boolean waitingShown=false;
        for(;;){
            if(attempt!=generation||!foreground)throw new InterruptedException("连接已取消");
            try{return request(base,token,method,path,body);}
            catch(Exception error){
                if(!base.startsWith("https:")||!RemoteTls.transientFailure(error)||SystemClock.elapsedRealtime()>=deadline)throw error;
                android.util.Log.w("DefoldRemote","Retrying startup request "+path,error);
                if(!waitingShown){waitingShown=true;ui.post(()->{if(attempt==generation&&overlay!=null)overlay.setText("正在等待电脑响应，自动重连中…");});}
                Thread.sleep(500);
            }
        }
    }
    private void firstFrame(Session current,int attempt,int width,int height){
        if(session!=current||attempt!=generation||!foreground)return;
        setRequestedOrientation(width>=height?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        ((View)overlay.getTag()).setVisibility(View.GONE);
        frameWidth=width;frameHeight=height;controlBusy=false;hideControls();
        if(current.audio&&audioPlayer==null)audioPlayer=new RemoteAudioPlayer(this,current.base,current.token);
        preferences.edit().putString("lastAddress",current.base).putString("lastId",current.id).apply();
        if(!current.base.startsWith("https:"))preferences.edit().putString("token:"+current.base,current.token).apply();
        Game old=games.get(current.id);games.put(current.id,new Game(current.id,current.base,current.name,old==null?"电脑":old.computer,"running",System.currentTimeMillis()));saveHistory();
        if(!preferences.getBoolean("controlGestureHint",false)){Toast.makeText(this,"四指下滑打开运行、停止和 FPS 控制栏",Toast.LENGTH_LONG).show();preferences.edit().putBoolean("controlGestureHint",true).apply();}
    }
    private void readStream(Session current,int attempt){
        StreamRecovery recovery=new StreamRecovery(current.h264);
        while(session==current&&attempt==generation&&foreground){
            HttpURLConnection connection=null;
            boolean hardware=recovery.hardware();
            String stage=hardware?"H.264 连接":"JPEG 连接";
            recovery.beginAttempt();
            try{
                connection=RemoteTls.open(current.base+(hardware?"/video":"/stream")+(current.qualitySupported?"?resolution="+streamQuality:"")+"");streamConnection=connection;
                connection.setConnectTimeout(2500);connection.setReadTimeout(3500);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Authorization","Bearer "+current.token);
                int response=connection.getResponseCode();
                if(response!=200)throw new RequestError(response,"串流连接失败 ("+response+")");
                if(hardware){stage="H.264 接收/解码";readVideo(connection,current,attempt,recovery);if(attempt==generation&&foreground)throw new EOFException();return;}
                stage="JPEG 接收/解码";
                videoMode="JPEG";ui.post(()->{if(session==current&&attempt==generation){screen.video(false,0,0);videoSurface.configure(1,1,null);frameRate.reset();}});
                boolean first=true;long firstDeadline=System.currentTimeMillis()+10000;
                try(DataInputStream input=new DataInputStream(new BufferedInputStream(connection.getInputStream(),65536))){
                    while(session==current&&attempt==generation&&foreground){
                        int size=input.readInt();input.readInt();
                        if(size<0||size>24*1024*1024)throw new Exception("画面超过大小限制");
                        if(size==0){if(first&&System.currentTimeMillis()>firstDeadline)throw new Exception("没有收到画面，请确认 Game 已运行且内嵌已开启");continue;}
                        byte[] data=new byte[size];input.readFully(data);Bitmap bitmap=decode(data);
                        if(bitmap==null)throw new Exception("无法解码画面");recovery.frame(SystemClock.elapsedRealtime());
                        if(first){first=false;int width=bitmap.getWidth(),height=bitmap.getHeight();ui.post(()->firstFrame(current,attempt,width,height));}
                        screen.offer(current,attempt,bitmap);
                    }
                }
            }catch(Exception e){
                if(session!=current||attempt!=generation)return;
                final String failure=stage+"："+streamError(e);
                android.util.Log.w("DefoldRemote",failure,e);
                if(e instanceof RequestError && (((RequestError)e).status==401 || ((RequestError)e).status==403)){
                    ui.post(()->{if(session==current&&attempt==generation)goHome("配对已失效，请重新扫码");});return;
                }
                try{String state=request(current.base,current.token,"GET","/status",null).json().optString("state");
                    if(state.equals("stopped")||state.equals("error")){ui.post(()->{if(session==current&&attempt==generation)pauseRemote("电脑游戏已停止");});return;}
                }catch(Exception ignored){}
                boolean unsupported=hardware&&e instanceof RequestError&&(((RequestError)e).status==404||((RequestError)e).status==503);
                if(!recovery.failed(unsupported)){ui.post(()->{if(session==current&&attempt==generation)pauseRemote("画面连接中断 · "+failure);});return;}
                if(connection!=null)connection.disconnect();
                ui.post(()->{if(session==current&&attempt==generation){release();overlay.setText(failure+"\n正在重连…");((View)overlay.getTag()).setVisibility(View.VISIBLE);}});
                long retryAt=SystemClock.elapsedRealtime()+recovery.delayMillis();
                while(session==current&&attempt==generation&&foreground&&SystemClock.elapsedRealtime()<retryAt){
                    try{Thread.sleep(Math.min(100,Math.max(1,retryAt-SystemClock.elapsedRealtime())));}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
                }
            }finally{if(connection!=null)connection.disconnect();if(streamConnection==connection)streamConnection=null;}
        }
    }
    private static String streamError(Exception e){
        if(e instanceof SocketTimeoutException)return "等待数据超时";
        if(e instanceof EOFException)return "电脑关闭了视频流";
        if(e instanceof RequestError)return "HTTP "+((RequestError)e).status;
        String message=e.getMessage();
        if("Video surface unavailable".equals(message))return "手机显示表面未就绪";
        if("Hardware H.264 decoder unavailable".equals(message))return "手机硬件解码器不可用";
        if("Decoder produced no picture".equals(message))return "已接收视频但未显示首帧";
        if("Video decoder stalled".equals(message))return "手机解码器阻塞";
        if("Video decoder failed".equals(message))return "手机解码器报错";
        if("Stream must start with SPS/PPS and IDR".equals(message))return "视频首帧缺少解码参数";
        return e.getClass().getSimpleName();
    }
    private void readVideo(HttpURLConnection connection,Session current,int attempt,StreamRecovery recovery)throws Exception{
        if(!"application/x-defold-h264".equals(connection.getContentType()))throw new IOException("Unsupported video protocol");
        final VideoSurface view=videoSurface;final StreamView touch=screen;
        try(DataInputStream input=new DataInputStream(new BufferedInputStream(connection.getInputStream(),65536))){
            final int width=input.readInt(),height=input.readInt();
            if(width<2||height<2||width>4096||height>4096||(long)width*height>8388608)throw new IOException("Invalid video size");
            byte[] firstPacket=videoPacket(input);byte[][] config=AvcConfig.parse(firstPacket);
            try(VideoDecoder decoder=new VideoDecoder(view.acquire(),width,height,config)){
                videoMode="H.264";
                java.util.concurrent.atomic.AtomicBoolean displayed=new java.util.concurrent.atomic.AtomicBoolean();
                long displayDeadline=SystemClock.elapsedRealtime()+5000;
                ui.post(()->{if(session!=current||attempt!=generation)return;
                    touch.video(true,width,height);
                    view.configure(width,height,new Runnable(){public void run(){
                        if(session!=current||attempt!=generation||paused)return;
                        frameRate.presented(SystemClock.elapsedRealtime());
                        recovery.frame(SystemClock.elapsedRealtime());
                        if(displayed.compareAndSet(false,true))firstFrame(current,attempt,width,height);
                    }});
                });
                decoder.feed(firstPacket);
                while(session==current&&attempt==generation&&foreground){decoder.feed(videoPacket(input));if(!displayed.get()&&SystemClock.elapsedRealtime()>displayDeadline)throw new IOException("Decoder produced no picture");}
            }
        }
    }
    private static byte[] videoPacket(DataInputStream input)throws IOException{
        long deadline=SystemClock.elapsedRealtime()+3500;
        do{
            int size=input.readInt();input.readInt();
            if(size<0||size>8*1024*1024)throw new IOException("Invalid video packet");
            if(size>0){byte[] bytes=new byte[size];input.readFully(bytes);return bytes;}
        }while(SystemClock.elapsedRealtime()<deadline);
        throw new IOException("No encoded video frame");
    }
    private static Bitmap decode(byte[] data)throws Exception{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,o);
        if(o.outWidth<1||o.outHeight<1||o.outWidth>4096||o.outHeight>4096||(long)o.outWidth*o.outHeight>8388608)throw new Exception("画面尺寸无效");
        try{return BitmapFactory.decodeByteArray(data,0,data.length);}catch(OutOfMemoryError e){throw new IOException("手机内存不足，请调低串流分辨率");}
    }
    private void closeStream(){if(audioPlayer!=null){audioPlayer.close();audioPlayer=null;}HttpURLConnection old=streamConnection;streamConnection=null;if(old!=null)new Thread(old::disconnect,"remote-disconnect").start();}
    private void pollState(){
        Session current=session;int attempt=generation;if(current==null)return;
        try{JSONObject state=request(current.base,current.token,"GET","/status",null).json();
            if(state.optString("state").equals("stopped")||state.optString("state").equals("error"))ui.post(()->{if(session==current&&attempt==generation&&!controlBusy&&!paused)pauseRemote("电脑游戏已停止");});
        }catch(RequestError e){if(e.status==403)ui.post(()->{if(session==current)goHome("配对已撤销，请重新扫码");});}catch(Exception ignored){}
    }
    private void showControls(String message){
        if(!page.equals("stream"))return;release();
        if(controlPanel==null){
            controlPanel=column();controlPanel.setPadding(dp(12),dp(10),dp(12),dp(10));controlPanel.setElevation(dp(8));controlPanel.setBackground(outlined(PANEL,16));
            LinearLayout info=new LinearLayout(this);info.setGravity(Gravity.CENTER_VERTICAL);controlStatus=label("",12,MUTED);controlStatus.setSingleLine();controlStatus.setEllipsize(TextUtils.TruncateAt.END);info.addView(controlStatus,new LinearLayout.LayoutParams(0,-2,1));
            fpsLabel=label("0 FPS",13,ACCENT);fpsLabel.setTypeface(Typeface.MONOSPACE);fpsLabel.setPadding(dp(10),0,0,0);fpsLabel.setContentDescription("手机实际显示帧率");info.addView(fpsLabel);controlPanel.addView(info);gap(controlPanel,8);
            LinearLayout actions=new LinearLayout(this);runButton=primary("运行",this::runRemote);stopButton=button("停止",this::stopRemote);
            actions.addView(runButton,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams middle=new LinearLayout.LayoutParams(0,dp(48),1);middle.setMargins(dp(8),0,dp(8),0);actions.addView(stopButton,middle);
            actions.addView(button("返回",()->goHome(paused?"已回首页，电脑游戏保持停止":"已回首页，电脑游戏继续运行")),new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(iconButton("close","收起控制栏",this::hideControls),new LinearLayout.LayoutParams(dp(48),dp(48)));controlPanel.addView(actions);gap(controlPanel,8);qualityButton=button("",this::chooseQuality);controlPanel.addView(qualityButton,new LinearLayout.LayoutParams(-1,dp(48)));
            FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);position.setMargins(dp(12),dp(12),dp(12),0);root.addView(controlPanel,position);
        }
        controlStatus.setText(message.isEmpty()?(paused?"游戏已停止":lastName):message);runButton.setEnabled(!controlBusy);stopButton.setEnabled(!controlBusy);runButton.setAlpha(controlBusy?.45f:1);stopButton.setAlpha(controlBusy?.45f:1);
        qualityButton.setText("清晰度 · "+qualityName(streamQuality)+" · "+videoMode+(frameWidth>0?"   "+frameWidth+" × "+frameHeight:""));qualityButton.setEnabled(!controlBusy);
        positionControls();controlPanel.setVisibility(View.VISIBLE);controlPanel.bringToFront();updateFps();
    }
    private void positionControls(){
        if(controlPanel==null)return;int left=0,top=0,right=0;
        if(Build.VERSION.SDK_INT>=28&&root.getRootWindowInsets()!=null){DisplayCutout cutout=root.getRootWindowInsets().getDisplayCutout();if(cutout!=null){left=cutout.getSafeInsetLeft();top=cutout.getSafeInsetTop();right=cutout.getSafeInsetRight();}}
        FrameLayout.LayoutParams position=(FrameLayout.LayoutParams)controlPanel.getLayoutParams();position.setMargins(dp(12)+left,dp(12)+top,dp(12)+right,0);controlPanel.setLayoutParams(position);
    }
    private String qualityName(String quality){return quality.equals("original")?"原始尺寸":quality;}
    private void chooseQuality(){
        Session current=session;if(current==null)return;
        if(!current.qualitySupported){Toast.makeText(this,"请先将电脑 Defold 更新至 patch.93，以启用高清串流",Toast.LENGTH_LONG).show();return;}
        LinearLayout options=column();TextView help=label("更高分辨率更清晰，也需要更好的 Wi-Fi。\n保持游戏比例，不放大原始画面。",13,MUTED);help.setLineSpacing(dp(4),1);options.addView(help);gap(options,16);
        String[] ids={"540p","720p","1080p","original"};String[] names={"流畅 · 540p","均衡 · 720p","高清 · 1080p","原始尺寸"};Dialog[] holder=new Dialog[1];
        for(int i=0;i<ids.length;i++){final String choice=ids[i];Button option=button((choice.equals(streamQuality)?"✓  ":"")+names[i],()->{holder[0].dismiss();if(session!=current||choice.equals(streamQuality))return;streamQuality=choice;preferences.edit().putString("streamQuality",choice).apply();if(paused){showControls("已选择 "+qualityName(choice)+"，运行后生效");return;}endInput();controlBusy=true;int attempt=++generation;closeStream();frameRate.reset();showControls("正在切换清晰度…");frames.execute(()->readStream(current,attempt));});if(choice.equals(streamQuality))option.setTextColor(ACCENT);options.addView(option,new LinearLayout.LayoutParams(-1,dp(52)));gap(options,6);}
        holder[0]=sheet("串流分辨率",options);
    }
    private void hideControls(){if(controlPanel!=null)controlPanel.setVisibility(View.GONE);}
    private void updateFps(){
        Session current=session;
        if(foreground&&!paused&&hasWindowFocus()&&current!=null&&audioPlayer!=null&&audioPlayer.needsFocusRetry())audioPlayer=new RemoteAudioPlayer(this,current.base,current.token);
        if(fpsLabel!=null)fpsLabel.setText(Math.round(paused?0:frameRate.fps(SystemClock.elapsedRealtime()))+" FPS"+(audioPlayer==null?"":" · "+audioPlayer.status()));
    }
    private void pauseRemote(String message){
        endInput();paused=true;controlBusy=false;++generation;closeStream();frameRate.reset();if(screen!=null)screen.discardPending();
        if(overlay!=null)((View)overlay.getTag()).setVisibility(View.GONE);showControls(message);
    }
    private void stopRemote(){
        Session current=session;if(current==null||controlBusy)return;
        if(current.base.startsWith("https:")){
            pauseRemote("正在停止游戏…");controlBusy=true;commands.execute(()->{try{request(current.base,current.token,"POST","/stop",new byte[0]);ui.post(()->{if(session==current){String host=RemoteTls.origin(current.base);goHome("游戏已停止");startActivity(new Intent(this,ManagerActivity.class).putExtra("host",host));}});}catch(Exception e){ui.post(()->{if(session==current){controlBusy=false;showControls(e.getMessage());}});}});return;
        }
        pauseRemote("正在停止游戏…");controlBusy=true;showControls("正在停止游戏…");int attempt=generation;
        commands.execute(()->{try{
            if(session!=current||attempt!=generation)return;
            request(current.base,current.token,"POST","/stop",new byte[0]);long deadline=SystemClock.elapsedRealtime()+6000;
            while(!request(current.base,current.token,"GET","/status",null).json().optString("state").equals("stopped")){
                if(session!=current||attempt!=generation)return;if(SystemClock.elapsedRealtime()>deadline)throw new Exception("停止尚未完成，请重试");Thread.sleep(100);
            }
            ui.post(()->{if(session==current&&attempt==generation){controlBusy=false;showControls("游戏已停止，点击运行继续");}});
        }catch(Exception e){ui.post(()->{if(session==current&&attempt==generation){controlBusy=false;showControls("停止失败："+e.getMessage());}});}});
    }
    private void runRemote(){
        Session current=session;if(current==null||controlBusy)return;
        if(!paused){hideControls();return;}
        if(current.base.startsWith("https:")){try{connect(new RemoteAddress(current.base,""));}catch(Exception e){goHome(e.getMessage());}return;}
        release();pointerBuffer.clear();controlBusy=true;int attempt=++generation;showControls("正在启动游戏…");
        commands.execute(()->{try{
            if(session!=current||attempt!=generation)return;
            request(current.base,current.token,"POST","/start",new byte[0]);long deadline=SystemClock.elapsedRealtime()+30000;
            while(!request(current.base,current.token,"GET","/status",null).json().optString("state").equals("running")){
                if(session!=current||attempt!=generation)return;if(SystemClock.elapsedRealtime()>deadline)throw new Exception("启动超时，请查看电脑的构建结果");Thread.sleep(150);
            }
            ui.post(()->{if(session!=current||attempt!=generation)return;paused=false;frameRate.reset();showControls("正在接收游戏画面…");frames.execute(()->readStream(current,attempt));});
        }catch(Exception e){ui.post(()->{if(session==current&&attempt==generation)pauseRemote("启动失败："+e.getMessage());});}});
    }
    private static Reply request(String base,String token,String method,String path,byte[] body)throws Exception{
        return request(base,token,method,path,body,-1);
    }
    private static Reply request(String base,String token,String method,String path,byte[] body,int revision)throws Exception{
        HttpURLConnection c=RemoteTls.open(base+path);c.setConnectTimeout(2200);c.setReadTimeout(path.equals("/start")?20000:3000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type",path.equals("/pair")||path.equals("/claim")?"application/json":"application/octet-stream");
        if(revision>=0)c.setRequestProperty("X-Defold-Device-Revision",Integer.toString(revision));
        try{
            if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}}
            int status=c.getResponseCode();InputStream source=status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(source!=null)try(InputStream in=source){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>4*1024*1024)throw new Exception("响应过大");bytes.write(b,0,n);}}
            if(status>=300){String message="连接失败 ("+status+")";try{message=new JSONObject(bytes.toString("UTF-8")).optString("error",message);}catch(Exception ignored){}throw new RequestError(status,message);}
            return new Reply(status,bytes.toByteArray());
        }finally{c.disconnect();}
    }
    private void pointer(int x,int y,boolean down){
        if(session==null)return;
        if(!pointerBuffer.offer(x,y,down)){pressed=false;showControls("触控过快，请抬起手指后重试");}
    }
    private void sendPointer(){
        Session current=session;int attempt=generation,epoch=inputEpoch;PointerBuffer.Event event=pointerBuffer.poll();if(current==null||event==null)return;
        byte[] body={(byte)event.x,(byte)(event.x>>8),(byte)event.y,(byte)(event.y>>8),(byte)(event.down?1:0)};
        try{request(current.base,current.token,"POST","/input",body,current.deviceRevision);}
        catch(RequestError e){
            if(session!=current||attempt!=generation||epoch!=inputEpoch)return;
            if(e.status==429){pointerBuffer.retry(event);return;}
            if(e.status==409){ui.post(()->{if(session==current&&attempt==generation&&!controlBusy)pauseRemote("电脑游戏已停止");});return;}
            if(e.status==403){ui.post(()->{if(session==current&&attempt==generation)goHome("配对已撤销，请重新扫码");});return;}
            inputUnavailable(current,attempt);
        }catch(Exception e){if(session==current&&attempt==generation)inputUnavailable(current,attempt);}
    }
    private void inputUnavailable(Session current,int attempt){
        ui.post(()->{if(session==current&&attempt==generation){pressed=false;++inputEpoch;pointerBuffer.cancel(pointerX,pointerY);showControls("触控暂时中断，请稍后重试");}});
    }
    private void endInput(){
        Session current=session;++inputEpoch;pressed=false;pointerBuffer.clear();if(current==null)return;
        int x=pointerX,y=pointerY;
        try{inputs.execute(()->{try{request(current.base,current.token,"POST","/input",new byte[]{(byte)x,(byte)(x>>8),(byte)y,(byte)(y>>8),0},current.deviceRevision);}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}
    }
    private void release(){if(pressed){pressed=false;pointerBuffer.offer(pointerX,pointerY,false);}}
    private final Runnable heartbeat=new Runnable(){public void run(){if(pressed)pointer(pointerX,pointerY,true);updateFps();if(foreground)ui.postDelayed(this,200);}};

    private void refreshDiscovery(){
        if(discovering||!foreground||!page.equals("home")||!homeTab.equals("discovery"))return;discovering=true;
        ArrayList<Game> known=new ArrayList<>(games.values());
        discovery.execute(()->{
            LinkedHashMap<String,Game> found=new LinkedHashMap<>();
            try(DatagramSocket udp=new DatagramSocket()){
                udp.setBroadcast(true);udp.setSoTimeout(180);byte[] query="DEFOLD_REMOTE_DISCOVER_1".getBytes(StandardCharsets.US_ASCII);
                ArrayList<InetAddress> destinations=new ArrayList<>();destinations.add(InetAddress.getByName("239.255.83.91"));destinations.add(InetAddress.getByName("255.255.255.255"));
                for(NetworkInterface adapter:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InterfaceAddress item:adapter.getInterfaceAddresses())if(item.getBroadcast()!=null)destinations.add(item.getBroadcast());
                for(InetAddress destination:destinations)try{udp.send(new DatagramPacket(query,query.length,destination,8391));}catch(Exception ignored){}
                long deadline=System.currentTimeMillis()+1400;
                while(System.currentTimeMillis()<deadline){DatagramPacket packet=new DatagramPacket(new byte[60000],60000);try{udp.receive(packet);}catch(SocketTimeoutException ignored){continue;}
                    JSONObject host=new JSONObject(new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8));if(!host.optString("service").equals("defold-remote"))continue;
                    String base="http://"+packet.getAddress().getHostAddress()+":"+host.getInt("port");new RemoteAddress(base,"");
                    JSONArray list=host.optJSONArray("games");if(list!=null)collect(found,base,list);
                }
            }catch(Exception ignored){}
            HashSet<String> checked=new HashSet<>();
            for(Game old:known){if(found.containsKey(old.id)||old.address.startsWith("https:"))continue;String base=old.address.split("/games/")[0];if(!checked.add(base))continue;try{collect(found,base,request(base,"","GET","/games",null).json().getJSONArray("games"));}catch(Exception ignored){}}
            // Computer catalogs belong to the separate paired-computer page.
            found.values().removeIf(game->game.address.startsWith("http:")&&!RemoteHosts.pairedComputer(game.address).isEmpty());
            HashMap<String,Bitmap> pictures=new HashMap<>();
            for(Game game:found.values()){
                String credential=token(game.address);
                if(credential.isEmpty()&&game.address.startsWith("http:")){for(Game old:known)if(old.address.startsWith("http:")&&old.id.equals(game.id)){credential=token(old.address);if(!credential.isEmpty())preferences.edit().putString("token:"+game.address,credential).apply();}}
                if(!credential.isEmpty())try{Reply response=request(game.address,credential,"GET","/thumbnail",null);if(response.status==200){Bitmap full=decode(response.data);if(full!=null){int w=Math.min(full.getWidth(),400),h=full.getHeight()*w/full.getWidth();pictures.put(game.id,Bitmap.createScaledBitmap(full,w,h,true));}}}catch(Exception ignored){}
            }
            ui.post(()->{discovering=false;if(isFinishing())return;games.putAll(found);for(Map.Entry<String,Bitmap> picture:pictures.entrySet())thumbnails.put(picture.getKey(),picture.getValue());while(thumbnails.size()>12)thumbnails.remove(thumbnails.keySet().iterator().next());saveHistory();if(page.equals("home")){renderCards();}});
        });
    }
    private String rediscover(String address) {
        if(address.startsWith("https:"))return address;
        if(!address.contains("/games/"))return address;
        String id=address.substring(address.lastIndexOf('/')+1);
        try(DatagramSocket udp=new DatagramSocket()) {
            udp.setBroadcast(true);udp.setSoTimeout(150);
            byte[] query="DEFOLD_REMOTE_DISCOVER_1".getBytes(StandardCharsets.US_ASCII);
            ArrayList<InetAddress> destinations=new ArrayList<>();destinations.add(InetAddress.getByName("239.255.83.91"));destinations.add(InetAddress.getByName("255.255.255.255"));
            for(NetworkInterface adapter:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InterfaceAddress item:adapter.getInterfaceAddresses())if(item.getBroadcast()!=null)destinations.add(item.getBroadcast());
            for(InetAddress destination:destinations)try{udp.send(new DatagramPacket(query,query.length,destination,8391));}catch(Exception ignored){}
            long deadline=System.currentTimeMillis()+1100;
            while(System.currentTimeMillis()<deadline) {
                DatagramPacket packet=new DatagramPacket(new byte[60000],60000);try{udp.receive(packet);}catch(SocketTimeoutException ignored){continue;}
                JSONObject host=new JSONObject(new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8));
                if(!host.optString("service").equals("defold-remote"))continue;
                JSONArray list=host.optJSONArray("games");if(list==null)continue;
                for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).optString("id")))return new RemoteAddress("http://"+packet.getAddress().getHostAddress()+":"+host.getInt("port")+list.getJSONObject(i).getString("path"),"").base;
            }
        }catch(Exception ignored){}
        return address;
    }
    private static void collect(Map<String,Game> found,String base,JSONArray list)throws Exception{
        for(int i=0;i<list.length();i++){JSONObject value=list.getJSONObject(i);String address=new RemoteAddress(base+value.getString("path"),"").base;String id=value.getString("id");if(!id.matches("[a-zA-Z0-9_-]{1,64}"))continue;found.put(id,new Game(id,address,value.getString("name"),value.optString("computer","电脑"),value.optString("state","stopped"),System.currentTimeMillis()));}
    }
    private void saveHistory(){JSONArray array=new JSONArray();try{for(Game g:games.values()){if(array.length()>=24)break;array.put(new JSONObject().put("id",g.id).put("address",g.address).put("name",g.name).put("computer",g.computer));}preferences.edit().putString("games",array.toString()).apply();}catch(JSONException ignored){}}
    private void loadHistory(){try{JSONArray list=new JSONArray(preferences.getString("games","[]"));for(int i=0;i<list.length();i++){JSONObject g=list.getJSONObject(i);games.put(g.getString("id"),new Game(g.getString("id"),new RemoteAddress(g.getString("address"),"").base,g.getString("name"),g.optString("computer","电脑"),"offline",0));}}catch(Exception ignored){}}
    private void scan(){endInput();++generation;session=null;closeStream();closeCamera();page="scan";newRoot();
        LinearLayout layout=column();root.addView(layout,new FrameLayout.LayoutParams(-1,-1));layout.addView(pageHeader("扫码连接",()->goHome("")));divider(layout);
        boolean wide=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout content=new LinearLayout(this);content.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);content.setPadding(dp(20),dp(20),dp(20),dp(20));layout.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout copy=column();copy.addView(title("扫码连接电脑",23));gap(copy,8);TextView hint=label("在电脑 Remote Manager 点击“扫码连接电脑”。\n配对成功后显示该电脑的游戏项目列表。\n也支持扫描编辑器 Game 面板的游戏二维码。",14,MUTED);hint.setLineSpacing(dp(4),1);copy.addView(hint);
        FrameLayout cameraArea=new FrameLayout(this);cameraArea.setBackground(background(PANEL,20));cameraArea.setClipToOutline(true);
        TextView tip=label("识别后自动配对并显示项目列表",12,MUTED);
        Button manual=button("手动输入连接信息",()->{goHome("");manual(null);});
        if(wide){
            ScrollView instructions=new ScrollView(this);copy.setPadding(0,0,dp(20),0);instructions.addView(copy);content.addView(instructions,new LinearLayout.LayoutParams(0,-1,1));gap(copy,20);copy.addView(tip);gap(copy,16);copy.addView(manual,new LinearLayout.LayoutParams(-1,dp(50)));content.addView(cameraArea,new LinearLayout.LayoutParams(0,-1,1));
        }else{
            content.addView(copy);gap(content,20);content.addView(cameraArea,new LinearLayout.LayoutParams(-1,0,1));gap(content,16);tip.setGravity(Gravity.CENTER);content.addView(tip);gap(content,16);content.addView(manual,new LinearLayout.LayoutParams(-1,dp(50)));
        }
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},40);return;}
        scanner=new QRScanner(this,new QRScanner.Listener(){public void result(String text){try{RemoteAddress address=RemoteAddress.fromLink(text);closeCamera();connect(address);}catch(Exception e){tip.setText(e.getMessage());}}public void status(String message){tip.setText(message);}public void error(String message){tip.setText(message);}});cameraArea.addView(scanner,new FrameLayout.LayoutParams(-1,-1));cameraArea.addView(new ScanGuide(this),new FrameLayout.LayoutParams(-1,-1));
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==40&&page.equals("scan")){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)scan();else{goHome("相机权限未开启，可手动输入连接信息");manual(null);}}}
    private void closeCamera(){if(scanner!=null){scanner.close();scanner=null;}}

    private final class StreamView extends View {
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF destination=new RectF();
        private final FourFingerGesture gesture=new FourFingerGesture();
        private Bitmap bitmap,pending,lastDrawn;
        private Session pendingSession;
        private int pendingGeneration;
        private boolean posted,multi,hardware;
        private int videoWidth,videoHeight;
        void video(boolean enabled,int width,int height){hardware=enabled;videoWidth=width;videoHeight=height;if(enabled)bitmap=null;setBackgroundColor(enabled?Color.TRANSPARENT:Color.BLACK);invalidate();}
        StreamView(){super(MainActivity.this);setBackgroundColor(Color.BLACK);setContentDescription("远程游戏。四指下滑打开控制栏，返回键打开控制栏");}
        synchronized void offer(Session current,int attempt,Bitmap next){if(pending!=null)pending.recycle();pending=next;pendingSession=current;pendingGeneration=attempt;if(!posted){posted=true;postOnAnimation(()->{synchronized(StreamView.this){if(pending!=null){if(pendingSession==session&&pendingGeneration==generation&&foreground)bitmap=pending;else pending.recycle();}pending=null;posted=false;}invalidate();});}}
        synchronized void discardPending(){if(pending!=null){pending.recycle();pending=null;}}
        synchronized void clear(){bitmap=null;if(pending!=null){pending.recycle();pending=null;}destination.setEmpty();invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(!hardware&&bitmap==null)return;int width=hardware?videoWidth:bitmap.getWidth(),height=hardware?videoHeight:bitmap.getHeight();float scale=Math.min((float)getWidth()/width,(float)getHeight()/height);float w=width*scale,h=height*scale;destination.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);if(hardware)return;canvas.drawBitmap(bitmap,null,destination,paint);if(lastDrawn!=bitmap){lastDrawn=bitmap;frameRate.presented(SystemClock.elapsedRealtime());}}
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked(),count=event.getPointerCount();
            if(count>=2&&!multi){multi=true;release();}
            int remain=action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL?0:count-(action==MotionEvent.ACTION_POINTER_UP?1:0);
            int[] ids=new int[remain];float[] x=new float[remain],y=new float[remain];float density=getResources().getDisplayMetrics().density;
            for(int i=0,j=0;i<count&&j<remain;i++){if(action==MotionEvent.ACTION_POINTER_UP&&i==event.getActionIndex())continue;ids[j]=event.getPointerId(i);x[j]=event.getX(i)/density;y[j]=event.getY(i)/density;j++;}
            if(gesture.update(event.getEventTime(),ids,x,y)){performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);showControls("");return true;}
            if(multi){if(remain==0)multi=false;return true;}
            if(controlPanel!=null&&controlPanel.getVisibility()==View.VISIBLE){if(action==MotionEvent.ACTION_DOWN&&!paused&&!controlBusy)hideControls();return true;}
            if(paused||controlBusy||session==null||(!hardware&&bitmap==null)||destination.isEmpty())return true;
            if(action==MotionEvent.ACTION_DOWN&&!destination.contains(event.getX(),event.getY()))return true;
            pointerX=Math.round(Math.max(0,Math.min(1,(event.getX()-destination.left)/destination.width()))*65535);pointerY=Math.round(Math.max(0,Math.min(1,(event.getY()-destination.top)/destination.height()))*65535);
            if(action==MotionEvent.ACTION_DOWN){pressed=true;pointer(pointerX,pointerY,true);}
            else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){release();performClick();}
            else if(action==MotionEvent.ACTION_MOVE&&pressed&&event.getEventTime()-lastMove>=8){lastMove=event.getEventTime();pointer(pointerX,pointerY,true);}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
