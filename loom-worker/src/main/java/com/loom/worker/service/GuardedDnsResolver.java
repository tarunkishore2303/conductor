package com.loom.worker.service;

import org.apache.hc.client5.http.DnsResolver;
import com.loom.capability.HttpAddressPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Every address handed to the connection operator is checked, avoiding a second unguarded DNS lookup. */
final class GuardedDnsResolver implements DnsResolver {
    private final boolean allowPrivate;
    private final java.util.concurrent.Semaphore lookups = new java.util.concurrent.Semaphore(8);
    GuardedDnsResolver(boolean allowPrivate) { this.allowPrivate = allowPrivate; }
    @Override public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!lookups.tryAcquire()) throw new UnknownHostException("HTTP DNS lookup capacity is unavailable");
        try {
            var addresses = InetAddress.getAllByName(host);
            if (addresses.length > 8) throw new UnknownHostException("HTTP destination resolves to too many addresses");
            for (var address : addresses) requireAllowed(address, allowPrivate);
            return addresses;
        } finally { lookups.release(); }
    }
    @Override public String resolveCanonicalHostname(String host) { return host; }
    static void requireAllowed(InetAddress address, boolean allowPrivate) throws UnknownHostException {
        try { HttpAddressPolicy.requireAllowed(address, allowPrivate); }
        catch (IllegalArgumentException blocked) { throw new UnknownHostException("HTTP destination is blocked by network policy"); }
    }
}
