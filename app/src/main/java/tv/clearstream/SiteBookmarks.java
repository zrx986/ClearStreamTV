package tv.clearstream;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;
import tv.clearstream.core.WebsitePolicy;

/** App-owned website/page bookmarks; never stores cookies or resolves media. */
final class SiteBookmarks {
    static final class Entry {
        final String root,url,title,image,summary,referrerPolicy;
        Entry(String root,String url,String title,String image,String summary,String policy){this.root=root;this.url=url;this.title=title;this.image=image;this.summary=summary;this.referrerPolicy=policy;}
    }
    private final SharedPreferences prefs;
    SiteBookmarks(SharedPreferences prefs){this.prefs=prefs;}
    boolean hasSite(String url){for(Entry e:sites())if(e.url.equals(url))return true;return false;}
    boolean hasFilm(String root,String url){for(Entry e:films(root))if(e.url.equals(url))return true;return false;}
    List<Entry> sites(){return list("site",null);}
    List<Entry> films(String root){return list("film",root);}
    boolean toggleSite(String url,String title){return toggle("site",new Entry(url,url,title,"","",""));}
    boolean toggleFilm(String root,String url,String title,String image,String summary,String policy){return toggle("film",new Entry(root,url,title,image,summary,policy));}
    Entry film(String root,String url){String safeRoot=WebsitePolicy.normalize(root),safeUrl=WebsitePolicy.normalize(url);if(safeRoot==null||safeUrl==null)return null;for(Entry entry:films(safeRoot))if(entry.url.equals(safeUrl))return entry;return null;}
    boolean removeFilm(String root,String url){return remove("film",root,url);}
    boolean removeSite(String url){return remove("site",url,url);}
    private boolean remove(String kind,String root,String url){
        String safeRoot=WebsitePolicy.normalize(root),safeUrl=WebsitePolicy.normalize(url);if(safeRoot==null||safeUrl==null)return false;
        synchronized(SiteBookmarks.class){JSONArray previous=read(),next=new JSONArray();boolean removed=false;
            for(int i=0;i<Math.min(previous.length(),200);i++){JSONObject old=previous.optJSONObject(i);Entry entry=entry(old);if(entry==null)continue;if(kind.equals(old.optString("kind"))&&safeRoot.equals(entry.root)&&safeUrl.equals(entry.url))removed=true;else next.put(old);}
            if(removed)prefs.edit().putString("siteBookmarks",next.toString()).apply();return removed;
        }
    }
    private JSONArray read() {
        String raw=prefs.getString("siteBookmarks","[]");
        if(raw.length()>2*1024*1024)return new JSONArray();
        try{return new JSONArray(raw);}catch(JSONException e){return new JSONArray();}
    }
    private Entry entry(JSONObject value) {
        if(value==null)return null;String kind=value.optString("kind");
        if(!kind.equals("site")&&!kind.equals("film"))return null;
        String root=WebsitePolicy.normalize(value.optString("root")),url=WebsitePolicy.normalize(value.optString("url"));
        if(root==null||url==null||kind.equals("site")&&!root.equals(url))return null;
        String title=trim(value.optString("title"),100);if(title.isEmpty())return null;
        String image=WebsitePolicy.normalize(value.optString("image"));if(image==null||image.length()>2048)image="";
        return new Entry(root,url,title,image,trim(value.optString("summary"),600),"no-referrer".equals(value.optString("policy"))?"no-referrer":"");
    }
    private List<Entry> list(String kind,String root) {
        synchronized(SiteBookmarks.class){List<Entry> result=new ArrayList<>();JSONArray all=read();
            for(int i=0;i<Math.min(all.length(),200);i++){JSONObject object=all.optJSONObject(i);Entry e=entry(object);if(e!=null&&kind.equals(object.optString("kind"))&&(root==null||root.equals(e.root)))result.add(e);}return result;
        }
    }
    private boolean toggle(String kind,Entry input) {
        synchronized(SiteBookmarks.class){
            try {
                JSONObject object=new JSONObject().put("kind",kind).put("root",input.root).put("url",input.url).put("title",input.title).put("image",input.image).put("summary",input.summary).put("policy",input.referrerPolicy);
                Entry valid=entry(object);if(valid==null)throw new IllegalArgumentException("收藏地址或标题无效");
                object=new JSONObject().put("kind",kind).put("root",valid.root).put("url",valid.url).put("title",valid.title).put("image",valid.image).put("summary",valid.summary).put("policy",valid.referrerPolicy);
                JSONArray previous=read(),next=new JSONArray();boolean found=false;
                for(int i=0;i<Math.min(previous.length(),200);i++){JSONObject old=previous.optJSONObject(i);Entry e=entry(old);if(e==null)continue;
                    if(kind.equals(old.optString("kind"))&&e.root.equals(valid.root)&&e.url.equals(valid.url))found=true;else next.put(old);
                }
                if(!found){if(next.length()>=200)throw new IllegalStateException("网站与影片收藏合计最多200项，请先取消一些收藏");next.put(object);}
                String data=next.toString();if(data.length()>2*1024*1024)throw new IllegalStateException("收藏内容过大，请先取消一些收藏");
                prefs.edit().putString("siteBookmarks",data).apply();return !found;
            }catch(JSONException e){throw new IllegalStateException("收藏未能保存",e);}
        }
    }
    private static String trim(String value,int length){String s=value==null?"":value.trim();return s.substring(0,Math.min(s.length(),length));}
}
