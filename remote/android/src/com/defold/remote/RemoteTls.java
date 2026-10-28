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

import java.net.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.*;

/** Per-computer certificate pinning. Never changes global TLS defaults. */
public final class RemoteTls {
    public static boolean transientFailure(Exception error){
        boolean retry=false;
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof CertificateException || cause instanceof SSLPeerUnverifiedException)return false;
            if(cause instanceof SocketTimeoutException || cause instanceof SocketException || cause instanceof java.io.EOFException)retry=true;
            // Android Conscrypt reports a dropped TCP handshake without a nested socket cause.
            if(cause instanceof SSLHandshakeException){
                String message=String.valueOf(cause.getMessage());
                if(message.equals("Connection closed by peer") || message.startsWith("SSL handshake aborted:")
                        && (message.endsWith("Connection reset by peer") || message.endsWith("Broken pipe")))retry=true;
            }
            if(cause.getCause()==cause)break;
        }
        return retry;
    }
    public static String explain(Exception error,String address){
        if(transientFailure(error))return "无法连接电脑 "+origin(address)+"。请确认手机与电脑网络互通、管理器正在运行且防火墙允许局域网访问。";
        if(error instanceof SSLException)return "电脑安全连接失败，请检查手机时间并重新扫描管理器的二维码。";
        return error.getMessage()==null?error.toString():error.getMessage();
    }
    private static final Map<String,String> pins=new ConcurrentHashMap<>();
    private static final Map<String,SSLSocketFactory> factories=new ConcurrentHashMap<>();
    public static String origin(String url){return url.split("/games/")[0];}
    static void forgetPin(String origin){pins.remove(origin);factories.remove(origin);}
    public static void pin(String origin,String fingerprint)throws Exception{
        RemoteAddress address=new RemoteAddress(origin,"");
        if(!address.base.startsWith("https:")||address.base.contains("/games/")||!fingerprint.matches("[a-f0-9]{64}"))throw new Exception("无效的电脑证书");
        pins.put(address.base,fingerprint);factories.remove(address.base);
    }
    public static HttpURLConnection open(String value)throws Exception{
        URL url=new URL(value);String host=url.getHost();int port=url.getPort()==-1?url.getDefaultPort():url.getPort();String origin=url.getProtocol()+"://"+host+":"+port;
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);
        if(connection instanceof HttpsURLConnection){
            final String fingerprint=pins.get(origin);if(fingerprint==null)throw new SSLException("电脑尚未信任，请重新扫码配对");
            SSLSocketFactory factory=factories.get(origin);
            if(factory==null){
                SSLContext context=SSLContext.getInstance("TLS");
                context.init(null,new TrustManager[]{new X509TrustManager(){
                    public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                    public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{throw new CertificateException("Client certificates unsupported");}
                    public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{
                        try {if(chain==null||chain.length==0)throw new CertificateException("Missing server certificate");chain[0].checkValidity();
                            byte[] digest=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                            if(!MessageDigest.isEqual(hex.toString().getBytes("UTF-8"),fingerprint.getBytes("UTF-8")))throw new CertificateException("电脑证书已变化，请重新扫码");
                        }catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}
                    }
                }},null);factory=context.getSocketFactory();factories.put(origin,factory);
            }
            HttpsURLConnection https=(HttpsURLConnection)connection;https.setSSLSocketFactory(factory);
            // The QR pins a self-signed computer identity; the IP may not occur in its CN.
            https.setHostnameVerifier((hostname,session)->hostname.equals(host));
        }
        return connection;
    }
}
