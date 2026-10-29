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

import com.defold.remote.StartupWait;
import com.defold.remote.StartupWait.Decision;

/** Explicit clock/state events reproduce a lost start intent after automatic rebuilding. */
public final class StartupWaitTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
    private static String failure(Runnable action){try{action.run();throw new AssertionError("Expected failure");}catch(IllegalStateException expected){checks++;return expected.getMessage();}}
    public static void main(String[] args){
        StartupWait wait=new StartupWait(0);wait.startRequested(0);
        check(wait.update("preparing",true,"编译原生扩展与游戏资源",100)==Decision.WAIT);
        // The phone missed an error while disconnected; an automatic resource-only build is ready.
        check(wait.update("ready",false,"",1000)==Decision.START);
        wait.startRequested(1000);
        check(wait.update("preparing",true,"启动后台游戏",1100)==Decision.WAIT);
        check(wait.update("running",false,"",1500)==Decision.RUNNING);
        check(wait.startRequests()==2);
        for(String state:new String[]{"ready","dirty","new"}){
            StartupWait candidate=new StartupWait(0);candidate.startRequested(0);
            check(candidate.update(state,false,"",749)==Decision.WAIT);
            check(candidate.update(state,true,"",750)==Decision.WAIT);
            check(candidate.update(state,false,"",750)==Decision.START);
        }
        StartupWait errored=new StartupWait(0);errored.startRequested(0);
        check(errored.update("error",false,"构建游戏资源",1000)==Decision.ERROR);
        check(errored.update("error",false,"构建游戏资源",StartupWait.MAX_WAIT_MILLIS+1)==Decision.ERROR);
        check(errored.startRequests()==1);

        StartupWait bounded=new StartupWait(0);bounded.startRequested(0);long now=0;
        long[] delays={750,1500,3000,6000,8000};
        for(long delay:delays){
            check(bounded.update("ready",false,"",now+delay-1)==Decision.WAIT);
            now+=delay;check(bounded.update("ready",false,"",now)==Decision.START);bounded.startRequested(now);
        }
        check(bounded.startRequests()==6);
        final long exhausted=now+8000;
        String message=failure(()->bounded.update("ready",false,"",exhausted));
        check(message.contains("6 次启动请求")&&message.contains("启动日志"));
        check(exhausted<30_000);

        StartupWait nativeBuild=new StartupWait(100);
        check(nativeBuild.update("preparing",true,"本机构建",100)==Decision.WAIT);
        check(nativeBuild.update("preparing",true,"本机构建",30_100)==Decision.WAIT);
        check(nativeBuild.message(30_100).contains("30 秒")&&nativeBuild.message(30_100).contains("可返回列表"));
        check(nativeBuild.update("preparing",true,"链接原生引擎",30_101)==Decision.WAIT);
        check(!nativeBuild.message(30_101).contains("此阶段仍在处理"));
        check(nativeBuild.update("preparing",true,"链接原生引擎",15*60*1000)==Decision.WAIT);
        check(failure(()->nativeBuild.update("preparing",true,"链接原生引擎",nativeBuild.deadline())).contains("链接原生引擎"));

        StartupWait inactive=new StartupWait(0);
        check(inactive.update("stopped",false,"等待进程",0)==Decision.WAIT);
        check(failure(()->inactive.update("stopped",false,"等待进程",90_000)).contains("长时间未推进"));
        check(new StartupWait(0,1).update("running",false,"",2)==Decision.RUNNING);
        System.out.println("StartupWaitTest: "+checks+" checks passed");
    }
}
