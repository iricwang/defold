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

import android.app.Activity;
import android.os.SystemClock;
import android.webkit.*;
import android.view.MotionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bundled, navigation-isolated WebRTC receiver. No microphone/camera permission. */
final class RemoteCloudView extends WebView {
    private static final String ORIGIN="https://localhost/defold-remote/";
    private volatile String metrics="{}";
    private final FourFingerGesture gesture=new FourFingerGesture();
    private boolean consumed;
    RemoteCloudView(Activity activity,Runnable close) {
        super(activity);
        getSettings().setJavaScriptEnabled(true);getSettings().setDomStorageEnabled(true);
        getSettings().setMediaPlaybackRequiresUserGesture(false);getSettings().setAllowFileAccess(false);getSettings().setAllowContentAccess(false);
        getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        setBackgroundColor(0xff181818);
        addJavascriptInterface(new Object(){
            @JavascriptInterface public String metrics(){return metrics;}
            @JavascriptInterface public void close(){activity.runOnUiThread(close);}
        },"DefoldRemote");
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                String url=request.getUrl().toString();if(!url.startsWith(ORIGIN))return null;
                String file=url.substring(ORIGIN.length());
                if(!Arrays.asList("index.html","remote.js","remote.css","livekit-client.umd.min.js").contains(file))return new WebResourceResponse("text/plain","UTF-8",404,"Not found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
                try{return new WebResourceResponse(file.endsWith(".js")?"application/javascript":file.endsWith(".css")?"text/css":"text/html","UTF-8",activity.getAssets().open("cloud/"+file));}
                catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream("Remote resources unavailable".getBytes(StandardCharsets.UTF_8)));}
            }
        });
        setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public boolean onConsoleMessage(ConsoleMessage message){
                String value=message.message();
                if(value.startsWith("[Remote]")||message.messageLevel()==ConsoleMessage.MessageLevel.ERROR||message.messageLevel()==ConsoleMessage.MessageLevel.WARNING){
                    value=value.replaceAll("(?:https?|wss?)://[^\\s\"']+","<server>").replaceAll("[A-Za-z0-9_-]{32,}","<id>");
                    android.util.Log.i("DefoldCloud",value.length()>1000?value.substring(0,1000):value);
                }
                return true;
            }
        });
        loadUrl(ORIGIN+"index.html");
    }
    void metrics(String value){metrics=value;evaluateJavascript("window.remoteMetricsChanged?.()",null);}
    void menu(){evaluateJavascript("window.remoteMenu?.()",null);}
    void pauseRemote(){evaluateJavascript("window.remotePause?.()",null);}
    void resumeRemote(){evaluateJavascript("window.remoteResume?.()",null);}
    @Override public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked(),count=e.getPointerCount();
        if(action==MotionEvent.ACTION_DOWN){gesture.reset();consumed=false;}
        float density=getResources().getDisplayMetrics().density;int[] ids=new int[count];float[] x=new float[count],y=new float[count];
        for(int i=0;i<count;i++){ids[i]=e.getPointerId(i);x[i]=e.getX(i)/density;y[i]=e.getY(i)/density;}
        if(count>=4&&!consumed){consumed=true;MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.onTouchEvent(cancel);cancel.recycle();evaluateJavascript("window.remoteEndPointer?.()",null);}
        if(gesture.update(SystemClock.elapsedRealtime(),ids,x,y))menu();
        boolean eat=consumed;
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){gesture.reset();consumed=false;}
        return eat||super.onTouchEvent(e);
    }
}
