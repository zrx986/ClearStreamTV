package tv.clearstream.core;
public final class WebsitePolicy {
    public static String normalize(String input) {
        if(input==null)return null;String s=input.trim();
        if(s.length()>8192||s.isEmpty())return null;
        if(!s.contains("://")) {if(s.contains(":"))return null;s="https://"+s;}
        try {
            java.net.URI uri=new java.net.URI(s);String scheme=uri.getScheme(),host=uri.getHost();
            if(!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))||host==null||uri.getUserInfo()!=null||host.contains("%")||uri.getPort()>65535||uri.getPort()==0)return null;
            return s;
        } catch(Exception e){return null;}
    }
    public static String nameOf(String url) {
        try {
            String host=java.net.URI.create(url).getHost().toLowerCase(java.util.Locale.ROOT);
            if(host.equals("v.qq.com")||host.endsWith(".v.qq.com"))return "腾讯视频";
            if(host.equals("mgtv.com")||host.endsWith(".mgtv.com"))return "芒果 TV";
            return host;
        }catch(Exception e){return "影视网站";}
    }
}
