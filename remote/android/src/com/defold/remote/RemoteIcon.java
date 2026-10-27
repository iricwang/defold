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
import android.graphics.Path;
import android.view.View;

/** Small monochrome symbols drawn at any density without bitmap assets. */
final class RemoteIcon extends View {
    private final String kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    RemoteIcon(Context context,String kind,int color){
        super(context);this.kind=kind;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private void line(Canvas c,float a,float b,float x,float y){c.drawLine(a,b,x,y,paint);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();float scale=Math.min(getWidth(),getHeight())/24f;
        canvas.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2);canvas.scale(scale,scale);
        switch(kind){
        case "monitor":canvas.drawRoundRect(3,4,21,17,2,2,paint);line(canvas,12,17,12,21);line(canvas,8,21,16,21);break;
        case "back":line(canvas,14,5,7,12);line(canvas,7,12,14,19);break;
        case "arrow":line(canvas,4,12,20,12);line(canvas,14,6,20,12);line(canvas,20,12,14,18);break;
        case "close":line(canvas,6,6,18,18);line(canvas,18,6,6,18);break;
        case "more":paint.setStyle(Paint.Style.FILL);for(int x=5;x<=19;x+=7)canvas.drawCircle(x,12,1.5f,paint);paint.setStyle(Paint.Style.STROKE);break;
        case "refresh":canvas.drawArc(4,4,20,20,40,280,false,paint);line(canvas,20,4,20,10);line(canvas,14,10,20,10);break;
        case "stop":canvas.drawRoundRect(5,5,19,19,2,2,paint);break;
        case "play":Path p=new Path();p.moveTo(8,4);p.lineTo(20,12);p.lineTo(8,20);p.close();canvas.drawPath(p,paint);break;
        case "link":canvas.rotate(-40,12,12);canvas.drawRoundRect(2,8,13,16,4,4,paint);canvas.drawRoundRect(11,8,22,16,4,4,paint);line(canvas,8,12,16,12);break;
        case "scan":
            for(int x:new int[]{4,20})for(int y:new int[]{4,20}){line(canvas,x,y,x+(x==4?5:-5),y);line(canvas,x,y,x,y+(y==4?5:-5));}
            line(canvas,8,12,16,12);break;
        default:break;
        }
        canvas.restore();
    }
}
