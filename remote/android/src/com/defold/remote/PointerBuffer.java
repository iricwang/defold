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

import java.util.ArrayDeque;

/** Coalesces motion while preserving press/release ordering under a slow network. */
public final class PointerBuffer {
    public static final class Event {
        public final int x,y;
        public final boolean down;
        final boolean move;
        Event(int x,int y,boolean down,boolean move){this.x=x;this.y=y;this.down=down;this.move=move;}
    }
    private final ArrayDeque<Event> events=new ArrayDeque<>();
    private boolean down;
    public synchronized boolean offer(int x,int y,boolean pressed){
        if(!pressed&&!down)return true;
        boolean move=pressed&&down;
        if(!events.isEmpty()&&events.peekLast().move)events.removeLast();
        if(events.size()>=32){cancel(x,y);return false;}
        events.addLast(new Event(x,y,pressed,move));down=pressed;return true;
    }
    public synchronized Event poll(){return events.pollFirst();}
    public synchronized void retry(Event event){
        // A newer position supersedes rejected motion; edges must retain their order.
        if(event.move)return;
        if(events.size()>=32){cancel(event.x,event.y);return;}
        events.addFirst(event);
    }
    public synchronized void cancel(int x,int y){events.clear();down=false;events.add(new Event(x,y,false,false));}
    public synchronized void clear(){events.clear();down=false;}
    public synchronized int size(){return events.size();}
}
