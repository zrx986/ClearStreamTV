package tv.clearstream.core;
import java.util.*;
import java.net.URI;
/** Ordered website scope for native film search. */
public final class FilmSearchScope {
    private FilmSearchScope() {}
    public static List<String> available(List<String> websites){
        List<String> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(websites!=null)for(String input:websites){String url=WebsitePolicy.normalize(input);if(url==null)continue;URI uri=URI.create(url);String host=uri.getHost().toLowerCase(Locale.ROOT),path=uri.getPath();
            if(host.startsWith("www."))host=host.substring(4);
            if(host.equals("bmmyy.com")||host.equals("i8k.tv")||host.equals("tv.cctv.com")&&path!=null&&path.startsWith("/live/"))continue;
            if(seen.add(host+":"+uri.getPort()))result.add(url);
        }
        return result;
    }
    public static List<String> defaults(List<String> websites){
        List<String> available=available(websites);
        if(available.isEmpty())return Collections.emptyList();
        return new ArrayList<>(available.subList(0,Math.min(3,available.size())));
    }
    public static List<String> selected(List<String> websites,List<String> chosen){
        Set<String> allowed=new HashSet<>(available(websites));List<String> result=new ArrayList<>();
        if(chosen!=null)for(String url:available(chosen))if(allowed.contains(url)&&result.size()<6)result.add(url);
        return result;
    }
}
