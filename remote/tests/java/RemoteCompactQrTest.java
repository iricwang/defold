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

import com.defold.editor.RemotePairingLink;
import com.defold.remote.RemoteAddress;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Interoperability and QR density, not a substitute for a physical camera test. */
public final class RemoteCompactQrTest {
    static int checks;
    static void check(boolean value) { checks++; if(!value)throw new AssertionError("check "+checks); }
    static void reject(String link) { try { RemoteAddress.fromLink(link);throw new AssertionError("unexpected accepted malformed QR"); } catch(Exception expected) { checks++; } }
    static String pack(byte[] bytes) { return "defoldremote://connect?v=3&d="+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static void main(String[] args) throws Exception {
        Random random=new Random(832917);
        String address="https://192.168.101.33:8390",code="",pin="",compact="",legacy="";
        for(int i=0;i<300;i++) {
            byte[] credential=new byte[32],fingerprint=new byte[32];random.nextBytes(credential);random.nextBytes(fingerprint);
            code=Base64.getUrlEncoder().withoutPadding().encodeToString(credential);pin=HexFormat.of().formatHex(fingerprint);
            address="https://10."+(i%256)+"."+(i/256)+".33:"+(1024+i);
            compact=RemotePairingLink.compact(address,code,pin);
            RemoteAddress decoded=RemoteAddress.fromLink(compact);
            check(decoded.base.equals(address));check(decoded.code.equals(code));check(decoded.fingerprint.equals(pin));
            legacy="defoldremote://connect?v=2&address="+URLEncoder.encode(address,StandardCharsets.UTF_8)+"&code="+code+"&fingerprint="+pin;
            RemoteAddress old=RemoteAddress.fromLink(legacy);check(old.base.equals(decoded.base)&&old.code.equals(decoded.code)&&old.fingerprint.equals(decoded.fingerprint));
        }
        check(compact.length()<legacy.length()*0.65);
        int compactVersion=Encoder.encode(compact,ErrorCorrectionLevel.M).getVersion().getVersionNumber();
        int legacyVersion=Encoder.encode(legacy,ErrorCorrectionLevel.M).getVersion().getVersionNumber();
        check(compactVersion<legacyVersion);
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,4);hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);
        for(int size:new int[]{160,192,230,320}) {
            BitMatrix qr=new QRCodeWriter().encode(compact,BarcodeFormat.QR_CODE,size,size,hints);
            int[] pixels=new int[size*size];for(int y=0;y<size;y++)for(int x=0;x<size;x++)pixels[y*size+x]=qr.get(x,y)?0xff000000:0xffffffff;
            String found=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size,size,pixels)))).getText();
            RemoteAddress actual=RemoteAddress.fromLink(found);check(found.equals(compact));check(actual.code.equals(code));check(actual.fingerprint.equals(pin));
        }
        byte[] raw=Base64.getUrlDecoder().decode(compact.substring(compact.indexOf("&d=")+3));
        byte[] invalid=raw.clone();invalid[0]=8;invalid[1]=8;reject(pack(invalid));
        invalid=raw.clone();invalid[4]=invalid[5]=0;reject(pack(invalid));
        for(String bad:new String[]{compact+"&d=x",compact+"&address=http%3A%2F%2F10.0.0.1",compact+"#x",compact.replace("connect?","connect/path?"),compact.replace("connect?","user@connect?"),compact.replace("v=3","v=4"),compact.substring(0,compact.length()-1),compact+"=",compact.replace("&d=","&d=%2B"),pack(Arrays.copyOf(raw,69)),pack(Arrays.copyOf(raw,71))})reject(bad);
        String suffix=compact.substring(compact.length()-1);String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";int last=alphabet.indexOf(suffix);
        reject(compact.substring(0,compact.length()-1)+alphabet.charAt(last+1)); // Noncanonical unused bits.
        RemoteAddress game=RemoteAddress.fromLink("defoldremote://connect?v=1&address=http%3A%2F%2F10.0.0.1%3A8390%2Fgames%2Fdemo&code=12345678");check(game.base.endsWith("/games/demo")&&game.fingerprint.isEmpty());
        System.out.println("RemoteCompactQrTest: "+checks+" checks; compact="+compact.length()+" chars/v"+compactVersion+", legacy="+legacy.length()+" chars/v"+legacyVersion);
    }
}
