package tv.clearstream.core;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Conservative name aliases; original URLs and editable source records remain untouched. */
public final class ChannelNames {
    private static final String[] TITLES={"","综合","财经","综艺","中文国际","体育","电影","国防军事","电视剧","纪录","科教","戏曲","社会与法","新闻","少儿","音乐","奥林匹克","农业农村"};
    private static final String[] NUMERALS={"","一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七"};
    private static final Pattern CCTV=Pattern.compile("^(?:CCTV|央视|中央电视台|中央)([0-9]{1,2})(\\+|PLUS)?(.*)$");
    private static String normal(String name){return Normalizer.normalize(name==null?"":name,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).trim();}
    private static String compact(String name){return normal(name).replaceAll("[\\s_·\\-]","");}
    private static String code(String name) {
        String n=compact(name);
        if(!(n.startsWith("CCTV")||n.startsWith("央视")||n.startsWith("中央")))return null;
        for(String prefix:new String[]{"CCTV","央视","中央电视台","中央"}) {
            if(n.startsWith(prefix+"4K"))return "4K";if(n.startsWith(prefix+"8K"))return "8K";
        }
        for(int i=n.startsWith("CCTV")?0:17;i>=1;i--)for(String prefix:new String[]{"央视","中央电视台","中央"})if(n.startsWith(prefix+NUMERALS[i]+"套")||n.equals(prefix+NUMERALS[i])){if(i==4&&n.contains("欧洲"))return "4EU";if(i==4&&n.contains("美洲"))return "4AM";return i==5&&(n.contains("+")||n.endsWith("PLUS"))?"5+":Integer.toString(i);}
        for(int i=1;!n.startsWith("CCTV")&&i<TITLES.length;i++)for(String prefix:new String[]{"央视","中央电视台","中央"})if(n.equals(prefix+TITLES[i])||n.equals(prefix+TITLES[i]+"频道"))return Integer.toString(i);
        Matcher m=CCTV.matcher(n);if(!m.matches())return null;
        int number=Integer.parseInt(m.group(1));if(number<1||number>17)return null;
        if(number==4){if(m.group(3).contains("欧洲")||m.group(3).contains("EUROPE"))return "4EU";if(m.group(3).contains("美洲")||m.group(3).contains("AMERICA"))return "4AM";}
        return number==5&&m.group(2)!=null?"5+":Integer.toString(number);
    }
    private static String cleanLabel(String name) {
        String n=normal(name).replaceAll("(?:[\\s\\[\\(【]*)(?:高清|超清|标清|超高清|蓝光|FHD|UHD|HD|SD|HEVC|H265|H264)(?:[\\s\\]\\)】]*)$","").trim();
        return n.replaceAll("\\s+"," ");
    }
    public static String key(String name,String group) {
        String c=code(name);if(c!=null)return "cctv:"+c;
        String n=compact(cleanLabel(name));
        String generic=n.replaceFirst("(?:频道|台)$","");
        if(Arrays.asList("综合","新闻综合","新闻","生活","公共","影视","电影","电视剧","少儿","教育","体育").contains(generic))return "local:"+normal(group)+":"+n;
        return "name:"+n;
    }
    public static String key(String name,String group,String url){String key=key(name,group);return key.startsWith("local:")?key+":url:"+url:key;}
    public static boolean isCctv(String name){return code(name)!=null;}
    public static String display(String name) {
        String c=code(name);if(c==null)return cleanLabel(name);
        if(c.equals("4K")||c.equals("8K"))return "CCTV-"+c+" 超高清";
        if(c.equals("5+"))return "CCTV-5+ 体育赛事";
        if(c.equals("4EU"))return "CCTV-4 欧洲";if(c.equals("4AM"))return "CCTV-4 美洲";
        return "CCTV-"+c+" "+TITLES[Integer.parseInt(c)];
    }
    public static String searchText(String name,String group){return compact(name)+"\n"+compact(display(name))+"\n"+compact(group);}
    public static final class Query {
        private final String needle,requested;private final boolean cctv;
        public Query(String query){needle=compact(query);requested=code(query);cctv=needle.equals("央视")||needle.equals("中央")||needle.equals("CCTV");}
        public boolean matches(String key,String searchable){return needle.isEmpty()||(cctv?key.startsWith("cctv:"):requested!=null&&key.equals("cctv:"+requested)||searchable.contains(needle));}
    }
    public static boolean matches(String name,String group,String query) {return new Query(query).matches(key(name,group),searchText(name,group));}
    public static int cctvOrder(String name) {
        String c=code(name);if(c==null)return 999;if(c.equals("4K"))return 180;if(c.equals("8K"))return 190;
        if(c.equals("4EU"))return 41;if(c.equals("4AM"))return 42;if(c.equals("5+"))return 51;return Integer.parseInt(c)*10;
    }
}
