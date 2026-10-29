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

package com.defold.editor;

import java.net.*;
import java.net.spi.*;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Resolver delegation tests plus a fresh-JVM probe using the packaged SPI registration. */
public final class RemoteAddressResolverTest {
    private static int checks;
    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if (args[0].equals("fixed")) RemoteAddressResolver.enableForManager();
            long start = System.nanoTime();
            String host = InetAddress.getByAddress(InetAddress.ofLiteral(args[1]).getAddress()).getHostName();
            System.out.println("{\"mode\":\"" + args[0] + "\",\"host\":\"" + host
                    + "\",\"elapsedMs\":" + (System.nanoTime() - start) / 1_000_000.0 + "}");
            return;
        }
        AtomicInteger forward = new AtomicInteger(), reverse = new AtomicInteger();
        InetAddressResolver builtin = new InetAddressResolver() {
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                forward.incrementAndGet();
                check(host.equals("build.example.test"), "forward name preserved");
                check(policy.characteristics() == LookupPolicy.IPV4, "forward lookup policy preserved");
                return Stream.of(InetAddress.ofLiteral("192.0.2.10"));
            }
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                reverse.incrementAndGet();
                InetAddress peer = InetAddress.getByAddress(address);
                if (peer.isLoopbackAddress() || peer.isSiteLocalAddress() || peer.isLinkLocalAddress())
                    throw new AssertionError("LAN reverse DNS would block the TLS handshake");
                return "public.example.test";
            }
        };
        InetAddressResolver resolver = RemoteAddressResolver.withNumericLanPeers(builtin);
        for (String ip : new String[]{"10.221.157.82", "172.16.0.1", "172.31.255.254", "192.168.1.2",
                "127.0.0.1", "169.254.12.34", "::1", "fe80::12", "fec0::12"}) {
            InetAddress peer = InetAddress.ofLiteral(ip);
            check(resolver.lookupByAddress(peer.getAddress()).equals(peer.getHostAddress()), "numeric LAN peer " + ip);
        }
        check(reverse.get() == 0, "no DNS calls for any accepted LAN peer");
        for (String ip : new String[]{"192.0.2.5", "172.32.0.1", "2001:db8::1"})
            check(resolver.lookupByAddress(InetAddress.ofLiteral(ip).getAddress()).equals("public.example.test"), "non-LAN lookup delegated");
        check(reverse.get() == 3, "non-LAN reverse resolver preserved");
        check(resolver.lookupByName("build.example.test", InetAddressResolver.LookupPolicy.of(InetAddressResolver.LookupPolicy.IPV4))
                .findFirst().orElseThrow().getHostAddress().equals("192.0.2.10"), "forward resolver result preserved");
        check(forward.get() == 1, "forward delegated once");
        try { resolver.lookupByAddress(new byte[3]); throw new AssertionError("invalid address accepted"); }
        catch (UnknownHostException expected) { checks++; }
        check(ServiceLoader.load(InetAddressResolverProvider.class).findFirst().orElseThrow()
                instanceof RemoteAddressResolver, "packaged resolver service is registered first");
        RemoteAddressResolver.enableForManager();
        check(InetAddress.getByAddress(new byte[]{10, (byte)221, (byte)157, 82}).getHostName().equals("10.221.157.82"),
                "actual InetAddress lookup uses packaged provider");
        System.out.println("RemoteAddressResolverTest: " + checks + " checks passed");
    }
}
