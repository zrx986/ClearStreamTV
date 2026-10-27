package tv.clearstream.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class PlaylistParserTest {
    @Test public void nakedUrlWithCommaAndTrailingMarkerKeepsQueryIntact() {
        ImportResult r=PlaylistParser.parse("http://example.org/live.m3u8?sig=A%23B&pair=1,2#",null);
        assertEquals(1,r.entries.size());assertEquals("http://example.org/live.m3u8?sig=A%23B&pair=1,2",r.entries.get(0).url);
    }
    @Test public void importsIptvTrailingHashAndAlternateLinesWithoutChangingSignedQueries() {
        ImportResult r=PlaylistParser.parse("频道,http://example.org/a.m3u8?sig=A%23B#http://example.org/b.m3u8#\n末尾标记,http://example.org/c.m3u8#",null);
        assertEquals(3,r.entries.size());assertEquals("http://example.org/a.m3u8?sig=A%23B",r.entries.get(0).url);assertEquals("频道",r.entries.get(1).name);assertTrue(r.errors.isEmpty());
    }
    @Test public void unsupportedAlternateDoesNotDiscardWorkingHttpLine() {
        ImportResult r=PlaylistParser.parse("频道,http://example.org/a.m3u8#rtmp://example.org/live\n备用,http://example.org/b.m3u8#@http://example.org/c.m3u8",null);
        assertEquals(3,r.entries.size());assertFalse(r.errors.isEmpty());
    }
    @Test public void invalidLinesCannotAccumulateUnboundedErrors() {
        ImportResult r=PlaylistParser.parse("not a channel\n".repeat(60000),null);
        assertTrue(r.entries.isEmpty());assertTrue(r.errors.size()<=9);assertTrue(r.errors.toString().contains("50000"));
    }
    @Test public void subscriptionHintsDoNotConfuseHlsWithChannelLists() {
        assertTrue(PlaylistParser.isSubscriptionUrl("https://example.org/list.M3U?token=test"));
        assertTrue(PlaylistParser.isSubscriptionUrl("http://example.org/config.json"));
        assertFalse(PlaylistParser.isSubscriptionUrl("http://example.org/live.m3u8"));
        assertFalse(PlaylistParser.isSubscriptionUrl("http://example.org/play?x=list.txt"));
    }
    @Test public void jsonConfigMustNotBecomeFakeChannels() {
        ImportResult result=PlaylistParser.parse("{\n  \"urls\": [\n    {\"url\":\"https://example.org/config.json\",\"name\":\"线路\"}\n  ]\n}","https://example.org/root.json");
        assertTrue(result.entries.isEmpty());
        assertTrue(result.errors.toString(),result.errors.toString().contains("TVBox"));
    }
    @Test public void htmlResponseMustNotLeakEmbeddedUrlsIntoLibrary() {
        ImportResult result=PlaylistParser.parse("<!doctype html>\nhttps://example.org/tracker\n</html>","https://example.org/list");
        assertTrue(result.entries.isEmpty());
        assertTrue(result.errors.toString().contains("网页"));
    }
    @Test public void unsupportedProtocolMustNotBecomeRelativeHttpUrl() {
        ImportResult result=PlaylistParser.parse("#EXTM3U\n#EXTINF:-1,组播频道\nudp://239.1.1.1:1234", "https://example.org/list");
        assertTrue(result.entries.isEmpty());
    }
    @Test public void importsIpv6WithoutChangingSignedUrl() {
        ImportResult result = PlaylistParser.parse("#EXTM3U\n#EXTINF:-1 group-title=\"纪录\",自然,纪实\nhttps://[2001:db8::1]:8443/live?a=1,b=2&sig=A%2FB\n", null);
        assertEquals(1, result.entries.size());
        assertEquals("自然,纪实", result.entries.get(0).name);
        assertEquals("纪录", result.entries.get(0).group);
        assertEquals("https://[2001:db8::1]:8443/live?a=1,b=2&sig=A%2FB", result.entries.get(0).url);
    }
    @Test public void mediaManifestBecomesOneChannelRatherThanSegments() {
        ImportResult result = PlaylistParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts", "https://example.org/live/index.m3u8");
        assertEquals(1, result.entries.size());
        assertEquals("https://example.org/live/index.m3u8", result.entries.get(0).url);
    }
    @Test public void localHlsWithoutBaseReportsUsefulError() {
        ImportResult result = PlaylistParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\na.ts", null);
        assertTrue(result.entries.isEmpty()); assertFalse(result.errors.isEmpty());
    }
    @Test public void importsTxtGroupsAndDeduplicatesExactUrls() {
        ImportResult result = PlaylistParser.parse("纪录,#genre#\n自然,https://example.org/a,b.ts\n重复,https://example.org/a,b.ts\n错误,http://[oops\n", null);
        assertEquals(1, result.entries.size()); assertEquals("纪录", result.entries.get(0).group);
        assertEquals(1, result.duplicates); assertEquals(1, result.errors.size());
    }
    @Test public void resolvesRelativeChannelUrlsAndHandlesBom() {
        ImportResult result = PlaylistParser.parse("\uFEFF#EXTM3U\n#EXTINF:-1,频道\n../live.m3u8", "https://example.org/sub/list.m3u");
        assertEquals("https://example.org/live.m3u8", result.entries.get(0).url);
    }
    @Test public void rejectsDangerousOrMalformedUrls() {
        for (String url : List.of("javascript:alert(1)", "file:///etc/passwd", "http://", "http://user:secret@example.org/a", "https://fe80::1/a", "https://[fe80::1%25wlan0]/a")) {
            assertFalse(url, UrlPolicy.isSupported(url));
        }
    }
    @Test public void acceptsDomainAndBothIpLiteralFamilies() {
        assertTrue(UrlPolicy.isSupported("https://example.org/a.m3u8"));
        assertTrue(UrlPolicy.isSupported("http://192.168.1.2/live.ts"));
        assertTrue(UrlPolicy.isSupported("http://[2001:db8::1]/live.ts"));
    }
    @Test public void rawUrlImportsWithoutFetchingInfiniteLiveBody() {
        ImportResult result = PlaylistParser.parse("http://[2001:db8::2]/live.ts", null);
        assertEquals(1, result.entries.size());
    }
    @Test public void invalidPlaylistDoesNotProduceFakeChannel() {
        ImportResult result = PlaylistParser.parse("<html>登录</html>", "https://example.org/login");
        assertTrue(result.entries.isEmpty()); assertFalse(result.errors.isEmpty());
    }
}
