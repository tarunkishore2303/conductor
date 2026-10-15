package com.loom.worker.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import static org.assertj.core.api.Assertions.*;

class GuardedDnsResolverTest {
    @ParameterizedTest @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "192.168.1.1", "172.16.0.1", "::1", "fc00::1", "100.64.0.1"})
    void blocksPrivateByDefault(String address) throws Exception {
        var ip = InetAddress.getByName(address);
        assertThatThrownBy(() -> GuardedDnsResolver.requireAllowed(ip, false)).isInstanceOf(UnknownHostException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"169.254.169.254", "0.0.0.0", "224.0.0.1", "fe80::1", "::", "168.63.129.16", "100.100.100.200", "fd00:ec2::254", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1"})
    void blocksDangerousTargetsEvenWhenPrivateEnabled(String address) throws Exception {
        var ip = InetAddress.getByName(address);
        assertThatThrownBy(() -> GuardedDnsResolver.requireAllowed(ip, true)).isInstanceOf(UnknownHostException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "::1", "fc00::1"})
    void allowsExplicitPrivateOptIn(String address) throws Exception {
        GuardedDnsResolver.requireAllowed(InetAddress.getByName(address), true);
    }
    @ParameterizedTest @ValueSource(strings = {"8.8.8.8", "2606:4700:4700::1111"})
    void allowsPublicAddressesWithoutConnecting(String address) throws Exception {
        GuardedDnsResolver.requireAllowed(InetAddress.getByName(address), false);
    }
}
