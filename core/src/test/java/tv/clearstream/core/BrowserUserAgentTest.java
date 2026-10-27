package tv.clearstream.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class BrowserUserAgentTest {
    @Test public void desktopPresentationRemovesMobilePlatformWithoutInventingANewerEngine() {
        String source="Mozilla/5.0 (Linux; Android 9; sdk Build/PI; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/66.0.3359.158 Mobile Safari/537.36";
        String result=BrowserUserAgent.desktop(source);assertFalse(result.contains("Android"));assertFalse(result.contains("Mobile"));assertFalse(result.contains("Version/4.0"));assertTrue(result.contains("Chrome/66.0.3359.158"));assertTrue(result.contains("AppleWebKit/537.36"));
    }
    @Test public void existingDesktopIdentityAndUnknownAgentAreNotUpgraded() {String source="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";assertEquals(source,BrowserUserAgent.desktop(source));assertEquals("Custom engine",BrowserUserAgent.desktop("Custom engine"));}
}
