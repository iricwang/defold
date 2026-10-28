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
        if(width<=0||height<=0||(long)width*height>data.length)throw new IllegalArgumentException("Invalid camera frame");
        MultiFormatReader reader=new MultiFormatReader();
        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);reader.setHints(hints);
        LuminanceSource full=new PlanarYUVLuminanceSource(data,width,height,0,0,width,height,false);
        int side=Math.min(width,height);
        LuminanceSource center=full.crop((width-side)/2,(height-side)/2,side,side);
        for(LuminanceSource source:new LuminanceSource[]{full,center,center.invert()}){
            try{return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source))).getText();}
            catch(NotFoundException ignored){}finally{reader.reset();}
        }
        throw NotFoundException.getNotFoundInstance();
    }
}
