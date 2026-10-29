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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/** Avoid blocking the LAN HTTPS handshake on a phone's missing reverse DNS record. */
public final class RemoteAddressResolver extends InetAddressResolverProvider {
    private static volatile boolean managerProcess;

    static void enableForManager() { managerProcess = true; }

    @Override public String name() { return "Defold Remote LAN peers"; }

    @Override public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        InetAddressResolver numeric = withNumericLanPeers(builtin);
        return new InetAddressResolver() {
            @Override public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                return builtin.lookupByName(host, policy);
            }

            @Override public String lookupByAddress(byte[] address) throws UnknownHostException {
                // The worker shares the packaged classpath, but keeps normal resolver behaviour.
                return (managerProcess ? numeric : builtin).lookupByAddress(address);
            }
        };
    }

    static InetAddressResolver withNumericLanPeers(InetAddressResolver builtin) {
        return new InetAddressResolver() {
            @Override public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                return builtin.lookupByName(host, policy);
            }

            @Override public String lookupByAddress(byte[] address) throws UnknownHostException {
                // getByAddress/getHostAddress perform no DNS lookup. SSLStreams calls
                // getHostName before the HTTPS configurator or our route handler runs.
                InetAddress peer = InetAddress.getByAddress(address);
                if (peer.isLoopbackAddress() || peer.isSiteLocalAddress() || peer.isLinkLocalAddress())
                    return peer.getHostAddress();
                return builtin.lookupByAddress(address);
            }
        };
    }
}
