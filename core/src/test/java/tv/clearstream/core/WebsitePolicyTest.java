package tv.clearstream.core;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebsitePolicyTest {
    @Test public void plainDomainGetsHttpsAndKnownPlatformName() {
        assertEquals("https://v.qq.com",WebsitePolicy.normalize(" v.qq.com "));
        assertEquals("腾讯视频",WebsitePolicy.nameOf("https://v.qq.com/x/cover/test.html"));
        assertEquals("芒果 TV",WebsitePolicy.nameOf("https://www.mgtv.com/"));
    }
    @Test public void webpageQueryAndHashArePreserved() {
        assertEquals("https://example.org/watch?id=3#player",WebsitePolicy.normalize("https://example.org/watch?id=3#player"));
    }
    @Test public void unsafeSchemesAndCredentialsAreRejected() {
        for(String input:new String[]{"javascript:alert(1)","file:///data/local/a","intent://video","https://user:secret@example.org/","data:text/html,test"})
            assertNull(input,WebsitePolicy.normalize(input));
    }
    @Test public void hostnameSuffixCannotImpersonateOfficialPlatform() {
        assertEquals("v.qq.com.evil.example",WebsitePolicy.nameOf("https://v.qq.com.evil.example/"));
    }
}
