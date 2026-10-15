package com.loom.capability;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Address policy shared by static literal validation and connection-time DNS validation. */
public final class HttpAddressPolicy {
    private HttpAddressPolicy() { }

    public static void requireAllowed(InetAddress address, boolean allowPrivate) {
        if (address == null) reject();
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && mappedV4(bytes)) {
            try { requireAllowed(InetAddress.getByAddress(java.util.Arrays.copyOfRange(bytes, 12, 16)), allowPrivate); }
            catch (UnknownHostException impossible) { throw new IllegalArgumentException("HTTP target is not allowed"); }
            return;
        }
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) reject();
        if (bytes.length == 4) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            int third = bytes[2] & 255;
            int fourth = bytes[3] & 255;
            boolean metadata = (first == 168 && second == 63 && third == 129 && fourth == 16)
                    || (first == 100 && second == 100 && third == 100 && fourth == 200);
            boolean shared = first == 100 && second >= 64 && second <= 127;
            boolean reserved = first == 0 || first >= 240 || (first == 198 && (second == 18 || second == 19));
            if (metadata || reserved || (!allowPrivate && (address.isLoopbackAddress() || address.isSiteLocalAddress() || shared))) reject();
            return;
        }
        if (bytes.length != 16) reject();
        boolean awsMetadata = (bytes[0] & 255) == 253 && bytes[1] == 0 && (bytes[2] & 255) == 14 && (bytes[3] & 255) == 194
                && zeroes(bytes, 4, 14) && (bytes[14] & 255) == 2 && (bytes[15] & 255) == 84;
        boolean sixToFour = (bytes[0] & 255) == 32 && (bytes[1] & 255) == 2;
        boolean teredo = (bytes[0] & 255) == 32 && (bytes[1] & 255) == 1 && bytes[2] == 0 && bytes[3] == 0;
        if (awsMetadata || sixToFour || teredo) reject();
        boolean globalUnicast = (bytes[0] & 224) == 32; // 2000::/3
        boolean uniqueLocal = (bytes[0] & 254) == 252;
        if (!globalUnicast && !(allowPrivate && (address.isLoopbackAddress() || uniqueLocal))) reject();
    }

    private static boolean mappedV4(byte[] bytes) {
        return zeroes(bytes, 0, 10) && (bytes[10] & 255) == 255 && (bytes[11] & 255) == 255;
    }
    private static boolean zeroes(byte[] bytes, int from, int to) {
        for (int index = from; index < to; index++) if (bytes[index] != 0) return false;
        return true;
    }
    private static void reject() { throw new IllegalArgumentException("HTTP target is not allowed"); }
}
