package tv.clearstream.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class CctvPagePolicyTest {
    @Test public void onlyExplicitOfficialPageInputsCanUseTheLayoutAdapter(){
        assertTrue(CctvPagePolicy.isOfficialPage("https://tv.cctv.com/live/cctv13/"));
        assertTrue(CctvPagePolicy.isOfficialPage("https://tv.cctv.com/live/cctveurope/index.shtml"));
        for(String bad:new String[]{null,"http://tv.cctv.com/live/cctv13/","https://tv.cctv.com.evil.example/live/cctv13/","https://user@tv.cctv.com/live/cctv13/","https://tv.cctv.com/live/cctv13/?x=1","https://tv.cctv.com/live/cctv13/#x","https://tv.cctv.com:444/live/cctv13/","https://tv.cctv.com/live/cctv99/"})assertFalse(bad,CctvPagePolicy.isOfficialPage(bad));
    }
}
