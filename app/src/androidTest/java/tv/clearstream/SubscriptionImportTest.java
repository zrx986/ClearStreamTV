package tv.clearstream;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class SubscriptionImportTest {
    @Test public void remembersRouteAndFollowsUniqueNameWhenFileUrlChanges() {
        android.content.SharedPreferences prefs=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext().getSharedPreferences("source-route-test",0);prefs.edit().clear().commit();
        SourceSubscriptions.Session first=new SourceSubscriptions.Session("https://example.org/",prefs,true);
        first.chooseManually(new SubscriptionImporter.Option("直播订阅","https://example.org/day1.m3u"));first.save(prefs);
        SourceSubscriptions.Session next=new SourceSubscriptions.Session("https://example.org/",prefs,false);
        SubscriptionImporter.Option changed=new SubscriptionImporter.Option("直播订阅","https://example.org/day2.m3u");
        assertSame(changed,next.remembered(java.util.Collections.singletonList(changed)));
        assertTrue(prefs.getStringSet("subscriptions",java.util.Collections.emptySet()).contains("https://example.org/"));
    }
    @Test public void ambiguousOrMissingRememberedLinkRequiresSelection() {
        android.content.SharedPreferences prefs=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext().getSharedPreferences("source-route-test",0);prefs.edit().clear().commit();
        SourceSubscriptions.Session first=new SourceSubscriptions.Session("https://example.org/",prefs,true);first.chooseManually(new SubscriptionImporter.Option("同名","https://example.org/old.m3u"));first.save(prefs);
        SourceSubscriptions.Session next=new SourceSubscriptions.Session("https://example.org/",prefs,false);
        assertNull(next.remembered(java.util.Arrays.asList(new SubscriptionImporter.Option("同名","https://example.org/a.m3u"),new SubscriptionImporter.Option("同名","https://example.org/b.m3u"))));
        assertNull(next.remembered(java.util.Collections.emptyList()));
    }
    @Test public void htmlPageOffersSourceLinksInsteadOfImportingThePage() {
        SubscriptionImporter.Result r=SubscriptionImporter.parse("<html><a href='./live.m3u'>直播订阅</a><a href='/about'>关于</a></html>","https://example.org/source/index.html");
        assertTrue(r.playlist.entries.isEmpty());assertEquals(1,r.options.size());assertEquals("https://example.org/source/live.m3u",r.options.get(0).url);
    }
    @Test public void multiConfigIsChoiceNotFakeChannel() {
        SubscriptionImporter.Result r=SubscriptionImporter.parse("{\"urls\":[{\"name\":\"线路甲\",\"url\":\"./a.json\"},{\"url\":\"javascript:alert(1)\"}]}","https://example.org/root/config.json");
        assertTrue(r.playlist.entries.isEmpty());assertEquals(1,r.options.size());assertEquals("https://example.org/root/a.json",r.options.get(0).url);
    }
    @Test public void readsStaticLivesAndNeverLoadsSpiderOrVod() {
        String json="{\"spider\":\"https://example.org/unsafe.jar\",\"sites\":[{\"api\":\"https://example.org/vod\"}],\"lives\":[{\"name\":\"直播表\",\"url\":\"list.m3u\"},{\"name\":\"分组\",\"channels\":[{\"name\":\"频道\",\"urls\":[\"http://[2001:db8::1]/a.m3u8\",\"rtmp://example.org/live\",\"http://[2001:db8::1]/a.m3u8\"]}]}]}";
        SubscriptionImporter.Result r=SubscriptionImporter.parse(json,"https://example.org/path/config.json");
        assertEquals(1,r.options.size());assertEquals("https://example.org/path/list.m3u",r.options.get(0).url);
        assertEquals(1,r.playlist.entries.size());assertEquals("分组",r.playlist.entries.get(0).group);assertEquals(1,r.playlist.duplicates);assertFalse(r.playlist.errors.isEmpty());
    }
    @Test public void malformedAndUnsupportedJsonAreReadableFailures() {
        for(String value:new String[]{"{oops","[]","{\"sites\":[]}"}){SubscriptionImporter.Result r=SubscriptionImporter.parse(value,null);assertTrue(r.playlist.entries.isEmpty());assertTrue(r.options.isEmpty());assertFalse(r.playlist.errors.isEmpty());}
    }
    @Test public void httpStatusAndRootCauseArePreservedForUser() {
        assertTrue(NetworkEngine.error(new IOException("订阅返回 HTTP 403")).contains("403"));
        assertTrue(NetworkEngine.error(new IOException(new javax.net.ssl.SSLHandshakeException("test"))).contains("TLS"));
        javax.net.ssl.SSLHandshakeException ssl=new javax.net.ssl.SSLHandshakeException("test");ssl.initCause(new java.io.EOFException());assertTrue(NetworkEngine.error(new IOException(ssl)).contains("TLS"));
    }
}
