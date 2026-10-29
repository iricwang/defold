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
import java.nio.file.Path;
import java.security.cert.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import javax.net.ssl.*;

/** Real HTTPS handshake with a deliberately blocked reverse resolver; no DNS settings changed. */
public final class RemoteSlowDnsTest {
    private static final CountDownLatch reverseEntered = new CountDownLatch(1);
    private static final CountDownLatch releaseReverse = new CountDownLatch(1);
    private static volatile boolean fixed;

    // Registered only in the test classpath, never in the deliverable.
    public static final class Provider extends InetAddressResolverProvider {
        public String name() { return "Test blocked reverse DNS"; }
        public InetAddressResolver get(Configuration configuration) {
            InetAddressResolver platform = configuration.builtinResolver();
            InetAddressResolver blocked = new InetAddressResolver() {
                public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                    return platform.lookupByName(host, policy);
                }
                public String lookupByAddress(byte[] address) throws UnknownHostException {
                    reverseEntered.countDown();
                    try {
                        if (!releaseReverse.await(15, TimeUnit.SECONDS)) throw new UnknownHostException("test reverse DNS deadline");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new UnknownHostException("interrupted"); }
                    return InetAddress.getByAddress(address).getHostAddress();
                }
            };
            InetAddressResolver numeric = RemoteAddressResolver.withNumericLanPeers(blocked);
            return new InetAddressResolver() {
                public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                    return platform.lookupByName(host, policy);
                }
                public String lookupByAddress(byte[] address) throws UnknownHostException {
                    return (fixed ? numeric : blocked).lookupByAddress(address);
                }
            };
        }
    }

    private static int request(RemoteManager manager, SSLContext context) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) URI.create("https://127.0.0.1:" + manager.gateway.port() + "/games").toURL().openConnection();
        c.setSSLSocketFactory(context.getSocketFactory());
        c.setHostnameVerifier((host, session) -> host.equals("127.0.0.1"));
        c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestProperty("Connection", "close");
        try { int code = c.getResponseCode(); c.getErrorStream().close(); return code; }
        finally { c.disconnect(); }
    }

    public static void main(String[] args) throws Exception {
        try (RemoteManager manager = new RemoteManager(Path.of(args[0]), Path.of(args[1]), 18660);
                ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("not used"); }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    if (chain.length == 0 || !manager.gateway.fingerprint.equals(RemoteManager.hash(chain[0].getEncoded())))
                        throw new CertificateException("pin mismatch");
                }
            }}, null);
            try {
                Future<Integer> original = calls.submit(() -> request(manager, context));
                if (!reverseEntered.await(5, TimeUnit.SECONDS)) throw new AssertionError("HTTPS did not enter the reverse resolver");
                if (original.isDone()) throw new AssertionError("original handshake should be blocked on reverse DNS");
                fixed = true;
                Future<Integer> repaired = calls.submit(() -> request(manager, context));
                if (repaired.get(3, TimeUnit.SECONDS) != 403) throw new AssertionError("unpaired access should still be denied");
                if (original.isDone() || releaseReverse.getCount() != 1) throw new AssertionError("repair depended on releasing old DNS request");
                releaseReverse.countDown();
                if (original.get(5, TimeUnit.SECONDS) != 403) throw new AssertionError("baseline did not recover after DNS release");
                System.out.println("RemoteSlowDnsTest: original HTTPS blocked; repaired HTTPS completed with DNS still blocked; certificate pin and authorization checked");
            } finally { releaseReverse.countDown(); }
        }
    }
}
