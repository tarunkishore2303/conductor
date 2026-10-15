package com.loom.capability;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class HttpAddressPolicyTest {
    @ParameterizedTest @ValueSource(strings = {"::ffff:168.63.129.16", "::ffff:100.100.100.200", "fd00:ec2::254", "64:ff9b::7f00:1", "::127.0.0.1", "2002:7f00:1::", "2001:0000:1234::1", "0.1.2.3", "240.0.0.1"})
    void dangerousAddressesRemainBlockedEvenWithPrivateOptIn(String literal) throws Exception {
        var address = InetAddress.getByName(literal);
        assertThatThrownBy(() -> HttpAddressPolicy.requireAllowed(address, true)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"::ffff:127.0.0.1", "::ffff:10.1.2.3", "fc00::1", "::1"})
    void privateAddressesRequireExplicitOptIn(String literal) throws Exception {
        var address = InetAddress.getByName(literal);
        assertThatThrownBy(() -> HttpAddressPolicy.requireAllowed(address, false)).isInstanceOf(IllegalArgumentException.class);
        HttpAddressPolicy.requireAllowed(address, true);
    }
    @ParameterizedTest @ValueSource(strings = {"8.8.8.8", "2606:4700:4700::1111"})
    void publicAddressesAreAcceptedWithoutNetworkCalls(String literal) throws Exception {
        HttpAddressPolicy.requireAllowed(InetAddress.getByName(literal), false);
    }
}
