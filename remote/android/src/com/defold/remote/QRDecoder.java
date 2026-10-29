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

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;

/** Offline decoder shared by camera frames and regression tests. */
public final class QRDecoder {
    private QRDecoder() {}
    public static String decode(byte[] data,int width,int height)throws NotFoundException {
        return decode(data,width,height,0,0,width,height);
    }
    /** Analyze only the sensor area actually visible in an aspect-fill camera preview. */
    public static String decode(byte[] data,int width,int height,int left,int top,int cropWidth,int cropHeight)throws NotFoundException {
        if(data==null||width<=0||height<=0||(long)width*height>data.length||left<0||top<0||
            cropWidth<=0||cropHeight<=0||(long)left+cropWidth>width||(long)top+cropHeight>height)
            throw new IllegalArgumentException("Invalid camera frame");
        return decodeSource(new PlanarYUVLuminanceSource(data,width,height,left,top,cropWidth,cropHeight,false));
    }
    /** Album pixels use exactly the same QR-only decoder, without requiring Android in tests. */
    public static String decode(int[] argb,int width,int height)throws NotFoundException {
        if(argb==null||width<=0||height<=0||(long)width*height>argb.length)
            throw new IllegalArgumentException("Invalid image");
        return decodeSource(new RGBLuminanceSource(width,height,argb));
    }
    private static String decodeSource(LuminanceSource full)throws NotFoundException {
        MultiFormatReader reader=new MultiFormatReader();
        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);reader.setHints(hints);
        int width=full.getWidth(),height=full.getHeight(),side=Math.min(width,height);
        LuminanceSource center=full.crop((width-side)/2,(height-side)/2,side,side);
        // Full-image inversion is essential: a code can be near the edge of a landscape frame.
        for(LuminanceSource source:new LuminanceSource[]{center,full,center.invert(),full.invert()}){
            try{return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source))).getText();}
            catch(NotFoundException ignored){}finally{reader.reset();}
        }
        throw NotFoundException.getNotFoundInstance();
    }
}
