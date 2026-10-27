package tv.clearstream.core;
import java.net.URI;
/** Recognizes a user-provided official page; never supplies channel addresses. */
public final class CctvPagePolicy {
    private CctvPagePolicy(){}
    public static boolean isOfficialPage(String url){
        if(url==null)return false;
        try{
            URI parsed=URI.create(url);
            if(!"https".equalsIgnoreCase(parsed.getScheme())||!"tv.cctv.com".equalsIgnoreCase(parsed.getHost())||parsed.getRawUserInfo()!=null||parsed.getFragment()!=null||parsed.getQuery()!=null||parsed.getPort()!=-1&&parsed.getPort()!=443)return false;
            String path=parsed.getPath();
            return path!=null&&path.matches("/live/cctv(?:[1-8]|10|11|12|13|15|16|17|5plus|jilu|child|europe|america)/(?:index\\.shtml)?");
        }catch(IllegalArgumentException ignored){return false;}
    }
}
