package tv.clearstream.core;
public final class UrlPolicy {
    public static boolean isSupported(String url) {
        if (url == null || url.length() > 8192) return false;
        try {
            java.net.URI u=new java.net.URI(url);
            String scheme=u.getScheme(), host=u.getHost();
            return ("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))
                && host!=null && !host.isEmpty() && u.getRawUserInfo()==null
                && u.getFragment()==null && !host.contains("%")
                && u.getPort() <= 65535 && u.getPort()!=0;
        } catch (Exception e) { return false; }
    }
}
