package tv.clearstream.core;
import java.net.*;
import java.util.*;
public final class AddressPolicy {
    public enum Mode { AUTO, IPV6, IPV4 }
    public static List<InetAddress> select(List<InetAddress> addresses, Mode mode) {
        List<InetAddress> v6=new ArrayList<>(), v4=new ArrayList<>();
        for (InetAddress a:addresses) {
            if (a instanceof Inet6Address && mode!=Mode.IPV4) v6.add(a);
            if (a instanceof Inet4Address && mode!=Mode.IPV6) v4.add(a);
        }
        List<InetAddress> result=new ArrayList<>();
        for (int i=0;i<Math.max(v6.size(),v4.size());i++) {
            if (i<v6.size()) result.add(v6.get(i));
            if (i<v4.size()) result.add(v4.get(i));
        }
        return result;
    }
    public static boolean isGlobalV6(InetAddress address) {
        if (!(address instanceof Inet6Address) || address.isAnyLocalAddress()
            || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        return (address.getAddress()[0] & 0xfe) != 0xfc;
    }
}
