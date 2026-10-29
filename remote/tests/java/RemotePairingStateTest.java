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

package com.defold.editor;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Holds persistence briefly to observe a real accepted pairing in flight, without a timing sleep. */
public final class RemotePairingStateTest {
    public static void main(String[] args) throws Exception {
        Path manifest=Path.of(args[0]), state=Path.of(args[1]);
        try(RemoteManager manager=new RemoteManager(manifest,state,18620)) {
            Map<String,Object> pairing=manager.gateway.pairing();
            String query=URI.create(pairing.get("uri").toString()).getRawQuery(), code="";
            for(String field:query.split("&"))if(field.startsWith("code="))code=URLDecoder.decode(field.substring(5),StandardCharsets.UTF_8);
            final String secret=code;
            SSLContext context=SSLContext.getInstance("TLS");
            context.init(null,new TrustManager[]{new X509TrustManager(){
                public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                public void checkClientTrusted(X509Certificate[] chain,String auth){}
                public void checkServerTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException {
                    if(chain.length==0||!manager.gateway.fingerprint.equals(RemoteManager.hash(chain[0].getEncoded())))throw new java.security.cert.CertificateException("pin mismatch");
                }
            }},null);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<Integer> response;
            try {
                synchronized(manager) {
                    response=worker.submit(()->{
                        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://127.0.0.1:"+manager.gateway.port()+"/pair").openConnection();
                        connection.setSSLSocketFactory(context.getSocketFactory());connection.setHostnameVerifier((host,session)->true);
                        connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setRequestMethod("POST");connection.setDoOutput(true);
                        try {try(var out=connection.getOutputStream()){out.write(RemoteManager.JSON.writeValueAsBytes(Map.of("code",secret,"name","Busy pairing test")));}
                            int result=connection.getResponseCode();connection.getInputStream().close();return result;
                        }finally{connection.disconnect();}
                    });
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(!manager.gateway.pairingStatus().get("state").equals("pairing")) {
                        if(System.nanoTime()>deadline)throw new AssertionError("accepted handshake was not observed");
                        Thread.onSpinWait();
                    }
                    try{manager.gateway.pairing();throw new AssertionError("rotated a credential during accepted handshake");}catch(IllegalStateException expected){}
                    try{manager.gateway.selectAddress(pairing.get("address").toString());throw new AssertionError("changed origin during accepted handshake");}catch(IllegalStateException expected){}
                }
                if(response.get(10,TimeUnit.SECONDS)!=200||!manager.gateway.pairingStatus().get("state").equals("paired"))throw new AssertionError("pairing did not complete after persistence was released");
            }finally{worker.shutdownNow();}
            System.out.println("RemotePairingStateTest: accepted pairing blocks QR rotation/address change; completes after persistence release");
        }
    }
}
