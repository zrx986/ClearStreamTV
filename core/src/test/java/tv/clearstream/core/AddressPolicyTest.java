package tv.clearstream.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.util.*;

public class AddressPolicyTest {
    @Test public void automaticPrefersIpv6AndKeepsIpv4Fallback() throws Exception {
        InetAddress v4 = InetAddress.getByName("192.0.2.1");
        InetAddress v6 = InetAddress.getByName("2001:db8::1");
        assertEquals(List.of(v6, v4), AddressPolicy.select(List.of(v4, v6), AddressPolicy.Mode.AUTO));
    }
    @Test public void forcedModeNeverPretendsToTranslateIpv6() throws Exception {
        assertTrue(AddressPolicy.select(List.of(InetAddress.getByName("2001:db8::1")), AddressPolicy.Mode.IPV4).isEmpty());
    }
    @Test public void networkHasOnlyIpv4StillConnectsInAutomaticMode() throws Exception {
        List<InetAddress> addresses = List.of(InetAddress.getByName("192.0.2.1"));
        assertEquals(addresses, AddressPolicy.select(addresses, AddressPolicy.Mode.AUTO));
    }
    @Test public void rejectsLinkLocalAsPublicIpv6Evidence() throws Exception {
        assertFalse(AddressPolicy.isGlobalV6(InetAddress.getByName("fe80::1")));
        assertFalse(AddressPolicy.isGlobalV6(InetAddress.getByName("::1")));
        assertFalse(AddressPolicy.isGlobalV6(InetAddress.getByName("fd00::1")));
        assertTrue(AddressPolicy.isGlobalV6(InetAddress.getByName("2001:db8::1")));
    }
}
