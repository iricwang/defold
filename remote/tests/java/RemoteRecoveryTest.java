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

import com.defold.remote.StreamRecovery;

public class RemoteRecoveryTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Recovery check " + checks);
    }
    // Brief transport loss retains H.264; repeated startup failures fall back and stop within a finite budget.
    public static void main(String[] args) {
        StreamRecovery recovery = new StreamRecovery(true);
        recovery.beginAttempt();
        check(recovery.failed(false)); check(recovery.hardware()); check(recovery.delayMillis() == 250);
        recovery.beginAttempt(); recovery.frame(0); recovery.frame(100);
        check(recovery.failed(false)); check(recovery.hardware()); check(recovery.delayMillis() == 500);
        recovery.beginAttempt();
        check(recovery.failed(false)); check(!recovery.hardware()); check(recovery.delayMillis() == 1000);
        check(recovery.failed(false)); check(recovery.delayMillis() == 2000);
        check(!recovery.failed(false)); check(recovery.delayMillis() == 2000);

        recovery = new StreamRecovery(true);
        recovery.failed(false); recovery.failed(false); recovery.beginAttempt();
        recovery.frame(100); recovery.frame(5100);
        check(recovery.failed(false)); check(recovery.hardware()); check(recovery.delayMillis() == 250);
        recovery.beginAttempt(); recovery.frame(20000); // Time spent disconnected must not reset failures.
        check(recovery.failed(false)); check(recovery.hardware()); check(recovery.delayMillis() == 500);
        check(recovery.failed(true)); check(!recovery.hardware());

        recovery = new StreamRecovery(false);
        check(recovery.failed(false)); check(!recovery.hardware());
        System.out.println(checks + " Remote reconnect checks passed");
    }
}
