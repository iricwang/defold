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

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Base64;

/** Validated LAN-only connection parameters shared by manual input, QR and Intents. */
public final class RemoteAddress {
    public final String base, code;
    public String fingerprint = "";
    /** Routes legacy discovery to a trusted computer catalog, never its HTTP endpoint. */
    public static String pairedComputer(String game, String[] computers) {
        try {
            RemoteAddress address = new RemoteAddress(game, "");
            URI target = new URI(address.base);
            for (String computer : computers) {
                RemoteAddress paired = new RemoteAddress(computer, "");
                URI host = new URI(paired.base);
                if (!"https".equals(host.getScheme()) || !host.getPath().isEmpty()) continue;
                if (target.getHost().equals(host.getHost())
                        && ("http".equals(target.getScheme()) || target.getPort() == host.getPort())) return paired.base;
            }
        } catch (Exception ignored) {}
        return "";
    }
    public RemoteAddress(String address, String pairingCode) throws Exception {
        if (address == null || address.length() > 512) throw new Exception("连接地址无效");
        URI uri = new URI(address.contains("://") ? address.trim() : "http://" + address.trim());
        String host = uri.getHost();
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || host == null || !host.matches("[0-9.]+")
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new Exception("仅支持局域网 HTTP / HTTPS 地址");
        String[] parts = host.split("\\.");
        if (parts.length != 4) throw new Exception("IP 地址无效");
        int[] octets = new int[4];
        for (int i=0;i<4;i++) { octets[i]=Integer.parseInt(parts[i]); if(octets[i]<0||octets[i]>255) throw new Exception("IP 地址无效"); }
        if (!(octets[0]==10 || octets[0]==127 || octets[0]==192&&octets[1]==168
                || octets[0]==172&&octets[1]>=16&&octets[1]<=31 || octets[0]==169&&octets[1]==254))
            throw new Exception("仅支持局域网连接");
        int port=uri.getPort()==-1?8390:uri.getPort(); if(port<1||port>65535) throw new Exception("端口无效");
        String path=uri.getPath(); if(path==null||path.equals("/")) path="";
        if(!path.isEmpty()&&!path.matches("/games/[a-zA-Z0-9_-]{1,64}")) throw new Exception("游戏连接路径无效");
        base=uri.getScheme()+"://"+host+":"+port+path;
        code=pairingCode==null?"":pairingCode.trim().replace(" ","");
        if(!code.isEmpty()&&!code.matches(base.startsWith("https:")?"[A-Za-z0-9_-]{43}":"[0-9]{8}")) throw new Exception("配对码格式无效");
    }
    public static RemoteAddress fromLink(String value) throws Exception {
        if(value==null||value.length()>2048) throw new Exception("二维码内容无效");
        URI uri=new URI(value);
        if(!"defoldremote".equals(uri.getScheme())||!"connect".equals(uri.getHost())
                || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getFragment()!=null
                || uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()) throw new Exception("这不是 Defold Remote 二维码");
        Map<String,String> fields=new HashMap<>();
        if(uri.getRawQuery()!=null) for(String pair:uri.getRawQuery().split("&")) {
            String[] item=pair.split("=",2);
            if(item.length!=2) throw new Exception("二维码内容不完整");
            String key=URLDecoder.decode(item[0],"UTF-8");
            if(fields.put(key,URLDecoder.decode(item[1],"UTF-8"))!=null) throw new Exception("重复的连接参数");
        }
        if("3".equals(fields.get("v"))) return compact(fields);
        if(!"1".equals(fields.get("v"))&&!"2".equals(fields.get("v"))) throw new Exception("二维码版本不支持");
        RemoteAddress address=new RemoteAddress(fields.get("address"),fields.get("code"));
        if("2".equals(fields.get("v"))) {
            String pin=fields.get("fingerprint");
            if(!address.base.startsWith("https:")||address.base.contains("/games/")||pin==null||!pin.matches("[a-f0-9]{64}")) throw new Exception("电脑二维码缺少有效证书指纹");
            address.fingerprint=pin;
        } else if(address.base.startsWith("https:")) throw new Exception("HTTPS 连接需要电脑二维码");
        if(address.code.isEmpty()) throw new Exception("二维码缺少配对码");
        return address;
    }
    private static RemoteAddress compact(Map<String,String> fields) throws Exception {
        String encoded=fields.get("d");
        if(fields.size()!=2||encoded==null||!encoded.matches("[A-Za-z0-9_-]{94}")) throw new Exception("电脑二维码内容不完整");
        byte[] data;
        try { data=Base64.getUrlDecoder().decode(encoded); }
        catch(IllegalArgumentException e) { throw new Exception("电脑二维码内容无效"); }
        if(data.length!=70||!Base64.getUrlEncoder().withoutPadding().encodeToString(data).equals(encoded))
            throw new Exception("电脑二维码内容无效");
        int port=((data[4]&255)<<8)|(data[5]&255);
        String host=(data[0]&255)+"."+(data[1]&255)+"."+(data[2]&255)+"."+(data[3]&255);
        String credential=Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(data,6,38));
        RemoteAddress address=new RemoteAddress("https://"+host+":"+port,credential);
        StringBuilder pin=new StringBuilder(64);
        for(int i=38;i<70;i++)pin.append(Character.forDigit((data[i]&255)>>>4,16)).append(Character.forDigit(data[i]&15,16));
        address.fingerprint=pin.toString();
        return address;
    }
}
