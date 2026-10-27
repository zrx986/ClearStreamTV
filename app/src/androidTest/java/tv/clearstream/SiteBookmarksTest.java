package tv.clearstream;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SiteBookmarksTest {
    private SharedPreferences prefs;
    @Before public void prepare(){prefs=InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("isolated-site-bookmarks-test",0);prefs.edit().clear().commit();}
    @After public void cleanup(){prefs.edit().clear().commit();}
    @Test public void siteAndFilmBookmarksPersistIndependentlyAndCanBeRemoved() {
        SiteBookmarks store=new SiteBookmarks(prefs);assertTrue(store.toggleSite("https://example.org/dm","朋友网站"));
        assertTrue(store.toggleFilm("https://example.org/dm","https://example.org/dm/123.html?sig=a%2Bb","测试影片","https://img.example.org/poster.jpg","公开简介","no-referrer"));
        store=new SiteBookmarks(prefs);assertTrue(store.hasSite("https://example.org/dm"));assertTrue(store.hasFilm("https://example.org/dm","https://example.org/dm/123.html?sig=a%2Bb"));
        assertEquals(1,store.sites().size());assertEquals(1,store.films("https://example.org/dm").size());assertTrue(store.films("https://another.example/").isEmpty());
        SiteBookmarks.Entry item=store.films("https://example.org/dm").get(0);assertEquals("公开简介",item.summary);assertEquals("no-referrer",item.referrerPolicy);assertTrue(item.url.endsWith("sig=a%2Bb"));
        assertFalse(store.toggleSite("https://example.org/dm","朋友网站"));assertFalse(store.toggleFilm(item.root,item.url,item.title,item.image,item.summary,item.referrerPolicy));assertTrue(store.sites().isEmpty());assertTrue(store.films(item.root).isEmpty());
    }
    @Test public void invalidEntriesAndCorruptStoredDataDoNotBecomePlayableBookmarks() {
        SiteBookmarks store=new SiteBookmarks(prefs);
        try{store.toggleFilm("https://example.org/","javascript:alert(1)","错误","","","");fail("Invalid URL must be rejected");}catch(IllegalArgumentException expected){}
        prefs.edit().putString("siteBookmarks","not-json").commit();assertTrue(store.sites().isEmpty());assertTrue(store.films("https://example.org/").isEmpty());
    }
    @Test public void bookmarkLimitStillAllowsRemoval() {
        SiteBookmarks store=new SiteBookmarks(prefs);for(int i=0;i<200;i++)assertTrue(store.toggleFilm("https://example.org/","https://example.org/detail/"+i,"测试"+i,"","",""));
        try{store.toggleSite("https://another.example/","超限");fail("Bounded bookmarks");}catch(IllegalStateException expected){}
        assertFalse(store.toggleFilm("https://example.org/","https://example.org/detail/0","测试0","","",""));assertTrue(store.toggleSite("https://another.example/","网站"));
    }
}
