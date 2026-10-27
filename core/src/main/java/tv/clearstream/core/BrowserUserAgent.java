package tv.clearstream.core;
/** TV presentation keeps the actual installed Chromium version. */
public final class BrowserUserAgent {
    public static String desktop(String original){if(original==null||!original.contains("Chrome/"))return original;return original.replaceFirst("\\([^)]*Android[^)]*\\)","(X11; Linux x86_64)").replace(" Version/4.0","").replace(" Mobile","");}
}
