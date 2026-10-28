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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** Host credentials survive game switching; control leases are never persisted. */
final class RemoteHosts {
    private static SharedPreferences preferences;
    static synchronized void init(Context context){
        preferences=context.getApplicationContext().getSharedPreferences("remote-hosts",0);
        for(int i=0;i<all().length();i++)try{JSONObject h=all().getJSONObject(i);RemoteTls.pin(h.getString("address"),h.getString("fingerprint"));}catch(Exception ignored){}
    }
    static synchronized JSONArray all(){try{return new JSONArray(preferences.getString("hosts","[]"));}catch(Exception e){return new JSONArray();}}
    static synchronized String token(String base){String origin=RemoteTls.origin(base);JSONArray all=all();for(int i=0;i<all.length();i++){JSONObject h=all.optJSONObject(i);if(h!=null&&origin.equals(h.optString("address")))return h.optString("token");}return "";}
    static synchronized String pairedComputer(String game){JSONArray known=all();String[] addresses=new String[known.length()];for(int i=0;i<known.length();i++)addresses[i]=known.optJSONObject(i).optString("address");return RemoteAddress.pairedComputer(game,addresses);}
    static synchronized void remove(String address){JSONArray next=new JSONArray(),all=all();for(int i=0;i<all.length();i++)if(!address.equals(all.optJSONObject(i).optString("address")))next.put(all.optJSONObject(i));preferences.edit().putString("hosts",next.toString()).apply();}
    static JSONObject pair(RemoteAddress target)throws Exception{
        if(target.fingerprint.isEmpty())throw new Exception("请扫描电脑管理器中的二维码");
        String oldPin="";JSONArray known=all();for(int i=0;i<known.length();i++)if(target.base.equals(known.getJSONObject(i).optString("address")))oldPin=known.getJSONObject(i).optString("fingerprint");
        RemoteTls.pin(target.base,target.fingerprint);
        JSONObject response;
        try{
            JSONObject body=new JSONObject().put("code",target.code).put("name",Build.MANUFACTURER+" "+Build.MODEL);
            int tries=0;
            for(;;)try{response=request(target.base,"","/pair",body);break;}
            catch(Exception e){
                // Retry only before HTTP can be sent. An ambiguous POST response must not
                // consume the one-use pairing secret again.
                if(++tries>=3 || !RemoteTls.transientFailure(e) || !(e instanceof javax.net.ssl.SSLHandshakeException || e instanceof java.net.ConnectException))throw e;
                Thread.sleep(500);
            }
        }
        catch(Exception e){if(oldPin.isEmpty())RemoteTls.forgetPin(target.base);else RemoteTls.pin(target.base,oldPin);throw e;}
        response.put("address",target.base).put("fingerprint",target.fingerprint);
        synchronized(RemoteHosts.class){JSONArray all=all(),next=new JSONArray();for(int i=0;i<all.length();i++)if(!response.optString("host_id").equals(all.getJSONObject(i).optString("host_id"))&&!target.base.equals(all.getJSONObject(i).optString("address")))next.put(all.getJSONObject(i));next.put(response);preferences.edit().putString("hosts",next.toString()).apply();}
        return response;
    }
    static JSONObject request(String base,String token,String path,JSONObject body)throws Exception{
        HttpURLConnection c=RemoteTls.open(base+path);c.setConnectTimeout(2500);c.setReadTimeout(5000);c.setRequestProperty("Authorization","Bearer "+token);
        try{if(body!=null){c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();InputStream stream=status>=300?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(stream!=null)try(InputStream in=stream){byte[] b=new byte[8192];for(int n;(n=in.read(b))>=0;){if(out.size()+n>1024*1024)throw new IOException("响应过大");out.write(b,0,n);}}
            JSONObject result=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));if(status>=300)throw new IOException(result.optString("error","连接失败 ("+status+")"));return result;
        }finally{c.disconnect();}
    }
}
