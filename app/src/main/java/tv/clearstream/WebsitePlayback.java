package tv.clearstream;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import tv.clearstream.core.WebsitePolicy;

/** Keep modern website playback inside the standard app without loading its engine on old boxes. */
public final class WebsitePlayback {
    private WebsitePlayback() {}
    public static boolean modernAvailable() {
        if(BuildConfig.LOW_MEMORY)return false;
        for(String abi:Build.SUPPORTED_ABIS)if(abi.equals("arm64-v8a")||abi.equals("armeabi-v7a")||abi.equals("x86_64"))return true;
        return false;
    }
    public static Intent intent(Context context,String url,String name) {
        String valid=WebsitePolicy.normalize(url);
        if(valid==null)throw new IllegalArgumentException("无效的网站地址");
        return new Intent().setClassName(context,modernAvailable()?"tv.clearstream.ModernWebActivity":"tv.clearstream.WebActivity")
                .putExtra("url",valid).putExtra("name",name).putExtra("officialCctv",tv.clearstream.core.CctvPagePolicy.isOfficialPage(valid));
    }
    public static Intent portalIntent(Context context,String url,String name){
        String valid=WebsitePolicy.normalize(url);if(valid==null)throw new IllegalArgumentException("无效的网站地址");
        android.net.Uri uri=android.net.Uri.parse(valid);String host=uri.getHost(),path=uri.getPath();
        if("tv.cctv.com".equalsIgnoreCase(host)&&path!=null&&path.startsWith("/live/"))return intent(context,valid,name);
        return new Intent(context,SiteActivity.class).putExtra("url",valid).putExtra("name",name);
    }
    public static Intent directoryIntent(Context context,String url,String name){String valid=WebsitePolicy.normalize(url);if(valid==null)throw new IllegalArgumentException("无效的网站地址");return new Intent(context,DirectoryActivity.class).putExtra("url",valid).putExtra("name",name).putExtra("officialCctv",tv.clearstream.core.CctvPagePolicy.isOfficialPage(valid));}
}
