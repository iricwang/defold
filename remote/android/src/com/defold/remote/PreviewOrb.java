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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** A 34 dp floating control inside a 48 dp touch target. Position is shared with the cloud preview. */
final class PreviewOrb extends View {
    private final SharedPreferences position;
    private final Runnable dragStarted;
    private final int slop;
    private final float density;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float downX,downY,startX,startY,heightFraction;
    private int left,top,right,bottom;
    private boolean rightSide,dragging,cancelled;

    PreviewOrb(Context context,Runnable dragStarted){
        super(context);
        this.dragStarted=dragStarted;
        position=context.getSharedPreferences("preview-orb",Context.MODE_PRIVATE);
        rightSide=position.getBoolean("right",true);
        heightFraction=position.getFloat("height",0.18f);
        density=getResources().getDisplayMetrics().density;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription("预览控制");setClickable(true);setFocusable(true);
    }
    boolean isRight(){return rightSide;}
    void setTravel(int left,int top,int right,int bottom){
        this.left=left;this.top=top;this.right=right;this.bottom=bottom;
        if(!dragging)dock();
    }
    private void dock(){
        setTranslationX(rightSide?right:left);
        setTranslationY(top+(bottom-top)*heightFraction);
    }
    @Override protected void onDraw(Canvas c){
        float x=getWidth()/2f,y=getHeight()/2f;
        paint.setStyle(Paint.Style.FILL);paint.setColor(isPressed()?0xeb30343a:0xc924272c);c.drawCircle(x,y,17*density,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);paint.setColor(0x80ffffff);c.drawCircle(x,y,16.5f*density,paint);
        paint.setStrokeWidth(1.5f*density);paint.setColor(0xcce7e9ed);c.drawCircle(x,y,8*density,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xf2e7e9ed);c.drawCircle(x,y,4.5f*density,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=e.getRawX();downY=e.getRawY();startX=getX();startY=getY();dragging=false;cancelled=false;
                getParent().requestDisallowInterceptTouchEvent(true);setPressed(true);invalidate();return true;
            case MotionEvent.ACTION_MOVE:
                if(cancelled)return true;
                if(!dragging&&dx*dx+dy*dy>slop*slop){dragging=true;dragStarted.run();}
                if(dragging){setTranslationX(Math.max(left,Math.min(right,startX+dx)));setTranslationY(Math.max(top,Math.min(bottom,startY+dy)));}
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                cancelled=true;dragging=false;dock();setPressed(false);invalidate();return true;
            case MotionEvent.ACTION_UP:
                if(dragging&&!cancelled){
                    rightSide=getX()>=(left+right)/2f;
                    if(bottom>top)heightFraction=Math.max(0,Math.min(1,(getY()-top)/(bottom-top)));
                    position.edit().putBoolean("right",rightSide).putFloat("height",heightFraction).apply();
                    dragging=false;dock();
                }else if(!cancelled)performClick();
                getParent().requestDisallowInterceptTouchEvent(false);setPressed(false);invalidate();return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){return super.performClick();}
}
