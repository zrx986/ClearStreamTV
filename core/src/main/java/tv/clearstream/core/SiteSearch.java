package tv.clearstream.core;
import java.util.List;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
/** Search addresses only; never constructs a media URL. */
public final class SiteSearch {
    private static final String SLOT="{query}";
    public static String builtIn(String url) {
        String normalized=WebsitePolicy.normalize(url);if(normalized==null)return null;
        String host=URI.create(normalized).getHost().toLowerCase(Locale.ROOT);
        if(host.equals("v.qq.com")||host.endsWith(".v.qq.com"))return "https://v.qq.com/x/search/?q="+SLOT;
        if(host.equals("mgtv.com")||host.endsWith(".mgtv.com"))return "https://so.mgtv.com/so?k="+SLOT;
        return null;
    }
    public static boolean validTemplate(String template) {
        if(template==null||template.length()>8192||!template.matches("(?is)^https?://.*"))return false;
        int at=template.indexOf(SLOT);
        if(at<0||template.indexOf(SLOT,at+SLOT.length())>=0)return false;
        int authorityEnd=template.length();for(char delimiter:new char[]{'/','?','#'}){int p=template.indexOf(delimiter,template.indexOf("://")+3);if(p>=0)authorityEnd=Math.min(authorityEnd,p);}
        if(at<authorityEnd)return false;
        int fragment=template.indexOf('#');
        return (fragment<0||at<fragment)&&WebsitePolicy.normalize(template.replace(SLOT,"clearstreamquery"))!=null;
    }
    public static String apply(String template,String query) {
        if(!validTemplate(template)||query==null||query.trim().isEmpty()||query.trim().length()>200)return null;
        String keyword=encode(query.trim());int queryAt=template.indexOf('?');
        if(queryAt<0||template.indexOf(SLOT)<queryAt)keyword=keyword.replace("+","%20");
        String result=template.replace(SLOT,keyword);
        return WebsitePolicy.normalize(result)==null?null:result;
    }
    public static String fromGetForm(String base,String action,String method,String queryName,List<String[]> fixed) {
        if(method!=null&&!method.isEmpty()&&!method.equalsIgnoreCase("get"))return null;
        if(queryName==null||queryName.isEmpty()||queryName.length()>80||fixed==null||fixed.size()>32||WebsitePolicy.normalize(base)==null)return null;
        try {
            String target=URI.create(base).resolve(action==null||action.isEmpty()?base:action).toString();
            if(WebsitePolicy.normalize(target)==null)return null;
            // Match a browser GET form: replace action query with successful controls.
            int end=target.length(),q=target.indexOf('?'),hash=target.indexOf('#');
            String fragment=hash>=0?target.substring(hash):"";
            if(q>=0)end=Math.min(end,q);if(hash>=0)end=Math.min(end,hash);
            StringBuilder result=new StringBuilder(target.substring(0,end)).append('?');
            for(String[] field:fixed) {
                if(field==null||field.length!=2||field[0]==null||field[1]==null||field[0].length()>80||field[1].length()>1024)return null;
                if(field[0].isEmpty()||field[0].equals(queryName))continue;
                result.append(encode(field[0])).append('=').append(encode(field[1])).append('&');
            }
            result.append(encode(queryName)).append('=').append(SLOT).append(fragment);
            return validTemplate(result.toString())?result.toString():null;
        }catch(IllegalArgumentException e){return null;}
    }
    private static String encode(String value) {
        try{return URLEncoder.encode(value,StandardCharsets.UTF_8.name());}catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
}
