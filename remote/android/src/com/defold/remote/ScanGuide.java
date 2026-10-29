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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Aim guides; the scanner also analyzes the rest of the visible camera field. */
final class ScanGuide extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ScanGuide(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float density=getResources().getDisplayMetrics().density;
        float size=Math.max(0,Math.min(getWidth(),getHeight())-48*density);
        size=Math.min(size,260*density);float left=(getWidth()-size)/2,top=(getHeight()-size)/2,right=left+size,bottom=top+size;
        paint.setColor(0x88000000);paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0,0,getWidth(),top,paint);canvas.drawRect(0,bottom,getWidth(),getHeight(),paint);
        canvas.drawRect(0,top,left,bottom,paint);canvas.drawRect(right,top,getWidth(),bottom,paint);
        paint.setColor(0xfff5f7fa);paint.setStrokeWidth(3*density);paint.setStrokeCap(Paint.Cap.ROUND);
        float edge=Math.min(24*density,size/4);
        for(float x:new float[]{left,right})for(float y:new float[]{top,bottom}){
            canvas.drawLine(x,y,x+(x==left?edge:-edge),y,paint);canvas.drawLine(x,y,x,y+(y==top?edge:-edge),paint);
        }
    }
}
