package tv.clearstream.core;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
/** Static anchors only; no scripts, crawling, login or video URL extraction. */
public final class HtmlSourceLinks {
    private static final Pattern ANCHOR=Pattern.compile("<a\\b([^>]{1,8192})>(.{0,2048}?)</a\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern ATTRIBUTE=Pattern.compile("(?:^|\\s)([a-zA-Z_:][a-zA-Z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]{0,8192})\"|'([^']{0,8192})'|([^\\s>]{1,8192}))",Pattern.CASE_INSENSITIVE);
    public static List<SourceEntry> find(String html,String base) {
        List<SourceEntry> links=new ArrayList<>();if(html==null||html.length()>2*1024*1024||base==null)return links;
        String visible=html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->","");
        Matcher a=ANCHOR.matcher(visible);Set<String> seen=new HashSet<>();int scanned=0;
        while(a.find()&&scanned++<2000&&links.size()<100) {
            Matcher attribute=ATTRIBUTE.matcher(a.group(1));String raw=null;
            while(attribute.find())if(attribute.group(1).equalsIgnoreCase("href")) {raw=attribute.group(2)!=null?attribute.group(2):attribute.group(3)!=null?attribute.group(3):attribute.group(4);break;}
            if(raw==null)continue;
            try {
                String url=URI.create(base).resolve(decode(raw)).toString();
                if(!UrlPolicy.isSupported(url)||!PlaylistParser.isSubscriptionUrl(url)||!seen.add(url))continue;
                String name=decode(a.group(2).replaceAll("<[^>]{0,2048}>"," ")).replaceAll("\\s+"," ").trim();
                if(name.isEmpty())name=URI.create(url).getPath();if(name.length()>80)name=name.substring(0,80);
                links.add(new SourceEntry(name,url,"网页链接"));
            }catch(Exception ignored){}
        }
        return links;
    }
    private static String decode(String value) {return value.replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">");}
}
