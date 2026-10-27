package tv.clearstream.core;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

public final class PlaylistParser {
    private static final Pattern GROUP=Pattern.compile("group-title=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    public static ImportResult parse(String text, String baseUrl) {
        ImportResult result=new ImportResult();
        if (text==null || text.length()>2*1024*1024) { result.errors.add("内容为空或超过 2MB 限制"); return result; }
        String clean=text.replace("\uFEFF","").trim();
        if(clean.startsWith("{")||clean.startsWith("[")) {
            result.errors.add("这是 JSON / TVBox 配置，不是 M3U/TXT 频道表；请使用订阅导入选择其中的静态直播入口。影视站点和爬虫插件不属于直播源。"); return result;
        }
        if(clean.startsWith("<")) {result.errors.add("服务器返回网页，不是频道表；可能需要登录或地址已变更。");return result;}
        String[] lines=clean.split("\\r?\\n",50002);
        for (String line:lines) {
            if (line.trim().startsWith("#EXT-X-")) {
                if (UrlPolicy.isSupported(baseUrl)) result.entries.add(new SourceEntry("直播流",baseUrl,"默认"));
                else result.errors.add("本地 HLS 媒体清单不能作为频道表导入，请添加原始网络地址");
                return result;
            }
        }
        Set<String> seen=new HashSet<>(); String group="默认", nextName=null, nextGroup=null;
        for (int i=0;i<Math.min(lines.length,50000);i++) {
            if (result.entries.size()>=10000) { result.errors.add("超过 10000 条上限，其余未导入"); break; }
            String line=lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                int comma=metadataComma(line);
                nextName=comma>=0?line.substring(comma+1).trim():null;
                Matcher m=GROUP.matcher(line); nextGroup=m.find()?m.group(1):group;
                continue;
            }
            if (line.startsWith("#")) continue;
            String name=nextName, itemGroup=nextGroup==null?group:nextGroup, url=line;
            if (nextName==null && !line.regionMatches(true,0,"http://",0,7) && !line.regionMatches(true,0,"https://",0,8)) {
                int comma=line.indexOf(',');
                if (comma>=0) {
                    String value=line.substring(comma+1).trim();
                    if ("#genre#".equalsIgnoreCase(value)) { group=line.substring(0,comma).trim(); continue; }
                    name=line.substring(0,comma).trim(); url=value;
                }
            }
            nextName=null; nextGroup=null;
            if (itemGroup==null||itemGroup.trim().isEmpty()) itemGroup="默认";
            // Some IPTV lists use #http(s):// for alternate lines and a trailing # marker.
            // Only recognize these conventions; never strip arbitrary fragments or signed %23.
            String[] alternatives=url.split("#@?(?=[a-zA-Z][a-zA-Z0-9+.-]*://)",33);
            for(int route=0;route<Math.min(alternatives.length,32)&&result.entries.size()<10000;route++) {
                String candidate=alternatives[route].trim();if(candidate.endsWith("#"))candidate=candidate.substring(0,candidate.length()-1);
                boolean absolute=UrlPolicy.isSupported(candidate);
                if (!absolute && baseUrl!=null && !candidate.startsWith("<") && !candidate.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
                    try { candidate=URI.create(baseUrl).resolve(candidate).toString(); } catch (Exception ignored) { }
                }
                if (!UrlPolicy.isSupported(candidate) || line.startsWith("<") || (name==null&&!absolute)) {
                    issue(result,"第 "+(i+1)+" 行：无法识别的 HTTP/HTTPS 源");continue;
                }
                if (!seen.add(candidate)) { result.duplicates++; continue; }
                String title=name==null||name.isEmpty()?"直播流 "+(result.entries.size()+1):name;
                result.entries.add(new SourceEntry(title,candidate,itemGroup));
            }
            if(alternatives.length>32)issue(result,"单频道最多识别 32 条线路");
        }
        if(lines.length>50000) {if(result.errors.size()>=9)result.errors.remove(result.errors.size()-1);result.errors.add("最多解析 50000 行，其余未导入");}
        if (clean.isEmpty()) result.errors.add("没有可导入内容");
        return result;
    }
    private static void issue(ImportResult result,String message) {
        if(result.errors.size()<8)result.errors.add(message);
        else if(result.errors.size()==8)result.errors.add("更多格式问题已省略");
    }
    public static boolean isSubscriptionUrl(String url) {
        try {String path=URI.create(url).getPath().toLowerCase(Locale.ROOT);return path.endsWith(".m3u")||path.endsWith(".txt")||path.endsWith(".json");}
        catch(Exception e){return false;}
    }
    private static int metadataComma(String line) {
        boolean quoted=false;
        for (int i=0;i<line.length();i++) {
            if (line.charAt(i)=='\"') quoted=!quoted;
            if (line.charAt(i)==','&&!quoted) return i;
        }
        return -1;
    }
}
