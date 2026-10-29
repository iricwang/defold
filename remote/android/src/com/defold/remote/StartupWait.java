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

/** One user connection attempt. All times are monotonic and supplied by its caller. */
public final class StartupWait {
    public enum Decision { WAIT, START, RUNNING, ERROR }
    public static final long MAX_WAIT_MILLIS=21*60*1000L;
    private static final long INACTIVE_LIMIT=90_000L, SLOW_PHASE_HINT=30_000L;
    private static final int MAX_START_REQUESTS=6;
    private final long began,deadline;
    private long lastStart=-1,lastProgress;
    private int starts;
    private String fingerprint="",state="",stage="";
    private boolean busy;

    public StartupWait(long now){this(now,MAX_WAIT_MILLIS);}
    public StartupWait(long now,long timeout){
        if(now<0||timeout<1||now>Long.MAX_VALUE-timeout)throw new IllegalArgumentException("Invalid startup deadline");
        began=lastProgress=now;deadline=now+timeout;
    }
    public long deadline(){return deadline;}
    public int startRequests(){return starts;}
    public void startRequested(long now){starts++;lastStart=now;}

    /** Errors always remain errors; only an idle, prepared project can request Start again. */
    public Decision update(String nextState,boolean nextBusy,String nextStage,long now){
        state=nextState==null?"":nextState;busy=nextBusy;stage=nextStage==null?"":nextStage.trim();
        String next=state+'\n'+busy+'\n'+stage;
        if(!fingerprint.equals(next)){fingerprint=next;lastProgress=now;}
        if(state.equals("error"))return Decision.ERROR;
        if(state.equals("running"))return Decision.RUNNING;
        if(now>=deadline)throw new IllegalStateException("等待游戏启动超时。最后阶段："+phase()+"（已用 "+seconds(now-began)+" 秒）。可返回项目列表查看构建日志后重试。");
        if(idle()){
            long delay=Math.min(8000L,750L<<Math.min(4,Math.max(0,starts-1)));
            if(lastStart<0||now-lastStart>=delay){
                if(starts>=MAX_START_REQUESTS)throw new IllegalStateException("游戏资源已准备完成，但 "+starts+" 次启动请求后仍未进入运行状态。最后阶段："+phase()+"。请查看电脑端启动日志后重试。");
                return Decision.START;
            }
        }else if(!busy&&now-lastProgress>=INACTIVE_LIMIT){
            throw new IllegalStateException("游戏启动阶段长时间未推进："+phase()+"（已等待 "+seconds(now-lastProgress)+" 秒）。请查看电脑端项目状态后重试。");
        }
        return Decision.WAIT;
    }
    public String message(long now){
        String value=phase();
        if(idle())value="准备已完成，正在请求启动（"+starts+"/"+MAX_START_REQUESTS+"）";
        if(now-began>=5000)value+=" · 已用 "+seconds(now-began)+" 秒";
        if(busy&&now-lastProgress>=SLOW_PHASE_HINT)value+="\n此阶段仍在处理，可返回列表让电脑继续准备。";
        return value;
    }
    private boolean idle(){return !busy&&(state.equals("ready")||state.equals("dirty")||state.equals("new"));}
    private String phase(){return !stage.isEmpty()?stage:state.equals("preparing")?"电脑正在准备游戏":"等待后台游戏启动";}
    private static long seconds(long elapsed){return Math.max(0,elapsed/1000);}
}
