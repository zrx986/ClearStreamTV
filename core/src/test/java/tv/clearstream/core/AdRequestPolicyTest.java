package tv.clearstream.core;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class AdRequestPolicyTest {
    private final List<String> domains=Arrays.asList("doubleclick.net","googlesyndication.com");
    @Test public void exactAdHostsAndSubdomainsAreBlockedOnlyAsResources(){
        assertTrue(AdRequestPolicy.shouldBlock("https://ad.doubleclick.net/pixel.gif",false,domains));
        assertTrue(AdRequestPolicy.shouldBlock("https://PAGEAD2.GOOGLESYNDICATION.COM./ads.js",false,domains));
        assertFalse(AdRequestPolicy.shouldBlock("https://ad.doubleclick.net/",true,domains));
    }
    @Test public void hostBoundariesAndNormalSitesArePreserved(){
        for(String u:new String[]{"https://notdoubleclick.net/a","https://doubleclick.net.example/a","https://doubleclick.net@normal.example/a","https://normal.example/?to=doubleclick.net","http://[2001:db8::1]/live.m3u8",null})assertFalse(u,AdRequestPolicy.shouldBlock(u,false,domains));
    }
    @Test public void mediaAndServerSideInsertedStreamsAreNotCutApart(){
        for(String path:new String[]{"/live.m3u8?token=example","/video.MP4","/segment.m4s","/seg.ts","/ssai/session","/ondemand/hls/session","/audio.aac"})assertFalse(path,AdRequestPolicy.shouldBlock("https://pubads.g.doubleclick.net"+path,false,domains));
    }
}
