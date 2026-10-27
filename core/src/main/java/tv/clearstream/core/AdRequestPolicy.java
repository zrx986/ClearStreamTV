package tv.clearstream.core;
import java.net.URI;
import java.util.*;
/** Conservative resource filtering: main documents and media routes stay intact. */
public final class AdRequestPolicy {
    private AdRequestPolicy(){}
    public static boolean shouldBlock(String address,boolean mainFrame,Collection<String> domains){
        if(mainFrame||address==null||domains==null)return false;
        try{
            URI uri=URI.create(address);String scheme=uri.getScheme(),host=uri.getHost();
            if(host==null||!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)))return false;
            String path=uri.getPath()==null?"":uri.getPath().toLowerCase(Locale.ROOT);
            if(path.matches(".*\\.(?:m3u8|mpd|mp4|m4s|ts|aac|m4a|webm|mp3)$")||path.contains("/ssai/")||path.contains("/ondemand/hls/"))return false;
            host=host.toLowerCase(Locale.ROOT).replaceFirst("\\.$","");
            for(String domain:domains)if(host.equals(domain)||host.endsWith("."+domain))return true;
        }catch(IllegalArgumentException ignored){}
        return false;
    }
}
