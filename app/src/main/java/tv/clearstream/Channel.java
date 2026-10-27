package tv.clearstream;

public final class Channel {
    public long id, checked;
    public String name, url, group, status, observedFamily="未知";
    public boolean favorite;
    public String hint() {
        if (!"未知".equals(observedFamily)) return observedFamily+" · 上次播放";
        if (url.contains("[")) return "IPv6";
        String host = java.net.URI.create(url).getHost();
        return host != null && host.matches("[0-9.]+") ? "IPv4" : "域名 · 自动双栈";
    }
    public boolean isV6() { return "IPv6".equals(observedFamily)||url.contains("["); }
}
