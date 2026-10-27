package tv.clearstream;

import java.net.URI;
import java.util.*;
import org.json.*;
import tv.clearstream.core.*;

/** Reads data only. TVBox spider/jar/js, parsing APIs and VOD sites are never executed. */
public final class SubscriptionImporter {
    public static final class Option {
        public final String name,url;
        Option(String name,String url){this.name=name;this.url=url;}
    }
    public static final class Result {
        public final ImportResult playlist=new ImportResult();
        public final List<Option> options=new ArrayList<>();
        public String type="频道表";
    }
    public static Result parse(String text,String base) {
        Result result=new Result();
        if(text==null||text.length()>2*1024*1024){result.playlist.errors.add("内容为空或超过 2MB 限制");return result;}
        String clean=text.replace("\uFEFF","").trim();
        if(clean.startsWith("<")) {
            result.type="源网页";
            for(SourceEntry link:HtmlSourceLinks.find(clean,base))result.options.add(new Option(link.name,link.url));
            if(result.options.isEmpty())result.playlist.errors.add("网页中没有找到公开的 M3U/TXT/JSON 源链接。登录、脚本生成的下载页需提供实际订阅地址；不把影视网页当直播流。");
            return result;
        }
        if(!clean.startsWith("{")&&!clean.startsWith("[")) {
            ImportResult p=PlaylistParser.parse(clean,base);
            result.playlist.entries.addAll(p.entries);result.playlist.errors.addAll(p.errors);result.playlist.duplicates=p.duplicates;
            result.type=clean.startsWith("#EXTM3U")?"M3U / HLS":clean.startsWith("<")?"网页响应":"TXT / 直播链接";return result;
        }
        result.type="TVBox / JSON 配置";
        try {
            if(!clean.startsWith("{"))throw new JSONException("Object required");
            JSONObject root=new JSONObject(clean);
            JSONArray configs=root.optJSONArray("urls");
            if(configs!=null)for(int i=0;i<Math.min(configs.length(),100);i++) {
                JSONObject item=configs.optJSONObject(i);if(item!=null)addOption(result,"配置 · "+item.optString("name","线路 "+(i+1)),item.optString("url"),base);
            }
            Set<String> seen=new HashSet<>();
            JSONArray lives=root.optJSONArray("lives");
            if(lives!=null)for(int i=0;i<Math.min(lives.length(),100);i++) {
                JSONObject live=lives.optJSONObject(i);if(live==null)continue;
                String group=live.optString("name","直播");
                addOption(result,"直播 · "+group,live.optString("url"),base);
                channels(result,live.optJSONArray("channels"),group,base,seen);
                JSONArray groups=live.optJSONArray("groups");
                if(groups!=null)for(int j=0;j<Math.min(groups.length(),100);j++) {
                    JSONObject g=groups.optJSONObject(j);if(g!=null)channels(result,g.optJSONArray("channels"),g.optString("name",group),base,seen);
                }
            }
            if(root.has("sites")||root.has("spider")||root.has("parses"))result.playlist.errors.add("仅支持静态直播频道 / 直播表入口；此配置的影视站点、解析接口、爬虫插件未加载。");
            if(result.options.isEmpty()&&result.playlist.entries.isEmpty())result.playlist.errors.add("未找到可用的 HTTP/HTTPS 静态直播入口。这不是可直接播放的频道表。");
        }catch(JSONException e){result.playlist.errors.add("JSON 配置无法读取；请提供完整、有效的 TVBox JSON 或 M3U/TXT。加密、JS 配置不支持。");}
        return result;
    }
    private static void channels(Result result,JSONArray channels,String group,String base,Set<String> seen) {
        if(channels==null)return;
        for(int i=0;i<Math.min(channels.length(),10000)&&result.playlist.entries.size()<10000;i++) {
            JSONObject channel=channels.optJSONObject(i);if(channel==null)continue;
            JSONArray urls=channel.optJSONArray("urls");
            if(urls==null){urls=new JSONArray();String single=channel.optString("url");if(!single.isEmpty())urls.put(single);}
            for(int j=0;j<Math.min(urls.length(),32)&&result.playlist.entries.size()<10000;j++) {
                String url=resolve(urls.optString(j),base);
                if(url==null){result.playlist.errors.add("已跳过非 HTTP/HTTPS 或无效的直播地址");continue;}
                if(!seen.add(url)){result.playlist.duplicates++;continue;}
                result.playlist.entries.add(new SourceEntry(channel.optString("name","直播频道"),url,group));
            }
        }
        // Bound error text as well as entries on untrusted configurations.
        while(result.playlist.errors.size()>8)result.playlist.errors.remove(result.playlist.errors.size()-1);
    }
    private static void addOption(Result result,String name,String raw,String base) {
        String url=resolve(raw,base);if(url==null||result.options.size()>=100)return;
        for(Option old:result.options)if(old.url.equals(url))return;
        result.options.add(new Option(name,url));
    }
    private static String resolve(String raw,String base) {
        if(raw==null||raw.trim().isEmpty())return null;
        try {String url=raw.trim();if(base!=null)url=URI.create(base).resolve(url).toString();return UrlPolicy.isSupported(url)?url:null;}
        catch(Exception e){return null;}
    }
}
