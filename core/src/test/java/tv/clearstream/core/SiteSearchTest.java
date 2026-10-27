package tv.clearstream.core;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SiteSearchTest {
    @Test public void officialTemplatesAndStrictDomainBoundaries() {
        assertEquals("https://v.qq.com/x/search/?q={query}",SiteSearch.builtIn("https://v.qq.com/"));
        assertEquals("https://v.qq.com/x/search/?q={query}",SiteSearch.builtIn("v.qq.com"));
        assertEquals("https://so.mgtv.com/so?k={query}",SiteSearch.builtIn("https://www.mgtv.com/"));
        assertNull(SiteSearch.builtIn("https://v.qq.com.evil.example/"));
        assertNull(SiteSearch.builtIn("https://notmgtv.com/"));
    }
    @Test public void keywordEncodedOnceWithoutRewritingFixedSignature() {
        assertEquals("https://example.org/so?sig=a%2Bb&wd=%E4%B8%89%E4%BD%93+%26%2B%23",SiteSearch.apply("https://example.org/so?sig=a%2Bb&wd={query}","三体 &+#"));
        assertEquals("https://example.org/search/a%2520b",SiteSearch.apply("https://example.org/search/{query}","a%20b"));
        assertEquals("https://example.org/search/a%20b",SiteSearch.apply("https://example.org/search/{query}","a b"));
    }
    @Test public void templateRequiresOneHttpPlaceholderOutsideFragment() {
        assertTrue(SiteSearch.validTemplate("https://example.org/?q={query}"));
        for(String value:new String[]{"https://example.org/", "example.org/?q={query}","https://{query}.example.org/search", "https://example.org/?a={query}&b={query}","javascript:{query}","https://user:pass@example.org/?q={query}","https://example.org/#q={query}"})assertFalse(value,SiteSearch.validTemplate(value));
        assertNull(SiteSearch.apply("https://example.org/?q={query}","  "));
        assertNull(SiteSearch.apply("https://example.org/?q={query}",new String(new char[201]).replace('\0','x')));
    }
    @Test public void actualGetFormRetainsHiddenFieldsAndRepeatedValues() {
        String t=SiteSearch.fromGetForm("https://example.org/catalog/", "../search", "GET", "wd",Arrays.asList(new String[]{"type","电影"},new String[]{"token","a+b"},new String[]{"tag","1"},new String[]{"tag","2"}));
        assertEquals("https://example.org/search?type=%E7%94%B5%E5%BD%B1&token=a%2Bb&tag=1&tag=2&wd={query}",t);
        assertTrue(SiteSearch.validTemplate(t));
    }
    @Test public void getFormReplacesActionQueryAsBrowserDoes() {
        assertEquals("https://example.org/search?lang=zh&q={query}#result",SiteSearch.fromGetForm("https://example.org/", "/search?unused=1#result", "get", "q",Collections.singletonList(new String[]{"lang","zh"})));
        assertNull(SiteSearch.fromGetForm("https://example.org/", "/search", "post", "q",Collections.emptyList()));
        assertNull(SiteSearch.fromGetForm("https://example.org/", "javascript:submit()", "get", "q",Collections.emptyList()));
    }
    @Test public void malformedOrOversizeFormDoesNotCreateAnInventedSearch() {
        assertNull(SiteSearch.fromGetForm("https://example.org/", "/search", "GET", "",Collections.emptyList()));
        assertNull(SiteSearch.fromGetForm("https://example.org/", "/search", "GET", "q",Collections.singletonList(new String[]{"token"})));
        List<String[]> fields=new ArrayList<>();for(int i=0;i<33;i++)fields.add(new String[]{"a"+i,"x"});
        assertNull(SiteSearch.fromGetForm("https://example.org/", "/search", "GET", "q",fields));
    }
}
