package tv.clearstream.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class HtmlSourceLinksTest {
    @Test public void usesRealHrefRatherThanDataAttributesOrQuotedTitleText() {
        java.util.List<SourceEntry> links=HtmlSourceLinks.find("<a data-href='old.txt' title=\" href='fake.txt' \" href='new.m3u'>我的源</a>","https://example.org/");
        assertEquals(1,links.size());assertEquals("https://example.org/new.m3u",links.get(0).url);
    }
    @Test public void ignoresAnchorsInsideScriptsAndComments() {
        assertTrue(HtmlSourceLinks.find("<script>let x=\"<a href='/bad.m3u'>bad</a>\";</script><!-- <a href='/hidden.txt'>hidden</a> -->", "https://example.org/").isEmpty());
    }
    @Test public void discoversListsWithRelativeUrlsAndPreservesQuery() {
        java.util.List<SourceEntry> links=HtmlSourceLinks.find("<html><a href='../tv/list.m3u?x=1&amp;y=2'> 高清直播 </a><a href=\"/config.json\">线路</a><a href='javascript:alert(1)'>危险</a><a href='/movie.m3u8'>影片</a></html>","https://example.org/sources/index.html");
        assertEquals(2,links.size());assertEquals("https://example.org/tv/list.m3u?x=1&y=2",links.get(0).url);assertEquals("高清直播",links.get(0).name);
    }
    @Test public void doesNotImportScriptsOrdinaryPagesOrDuplicateLinks() {
        java.util.List<SourceEntry> links=HtmlSourceLinks.find("<a href='/a.txt'>甲</a><a href='/a.txt'>重复</a><a href='/home'>首页</a><script src='/plugin.js'></script>","https://example.org/");assertEquals(1,links.size());
    }
}
