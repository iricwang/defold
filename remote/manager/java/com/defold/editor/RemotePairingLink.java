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

import java.net.URI;
import java.util.Base64;
import java.util.HexFormat;

/** A smaller QR representation; credentials and the SHA-256 certificate pin retain every bit. */
public final class RemotePairingLink {
    private RemotePairingLink() {}

    public static String compact(String address, String code, String fingerprint) {
        URI uri = URI.create(address);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                || !code.matches("[A-Za-z0-9_-]{43}") || !fingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("电脑配对参数无效");
        String[] octets = uri.getHost().split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("电脑地址必须为局域网 IPv4");
        byte[] payload = new byte[70];
        for (int i = 0; i < 4; i++) {
            int value = Integer.parseInt(octets[i]);
            if (value < 0 || value > 255 || !Integer.toString(value).equals(octets[i]))
                throw new IllegalArgumentException("电脑 IPv4 地址无效");
            payload[i] = (byte)value;
        }
        int first = payload[0] & 255, second = payload[1] & 255;
        if (!(first == 10 || first == 127 || first == 192 && second == 168
                || first == 172 && second >= 16 && second <= 31 || first == 169 && second == 254))
            throw new IllegalArgumentException("仅支持局域网连接");
        byte[] credential = Base64.getUrlDecoder().decode(code);
        if (credential.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(credential).equals(code))
            throw new IllegalArgumentException("电脑配对凭据无效");
        payload[4] = (byte)(uri.getPort() >>> 8);
        payload[5] = (byte)uri.getPort();
        System.arraycopy(credential, 0, payload, 6, 32);
        System.arraycopy(HexFormat.of().parseHex(fingerprint), 0, payload, 38, 32);
        return "defoldremote://connect?v=3&d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }
}
