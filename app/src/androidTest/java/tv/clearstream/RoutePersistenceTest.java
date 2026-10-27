package tv.clearstream;

import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.clearstream.core.*;
import static org.junit.Assert.*;

/** Uses a separately named test database; never changes the app's library. */
@RunWith(AndroidJUnit4.class)
public final class RoutePersistenceTest {
    @Test public void sameChannelDifferentUrlsSurviveSequentialImportsAndDatabaseReopen() {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        Context isolated=new android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public java.io.File getDatabasePath(String name){return super.getDatabasePath("route-persistence-"+name);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase("route-persistence-"+name,mode,factory);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return super.openOrCreateDatabase("route-persistence-"+name,mode,factory,handler);}
        };
        SourceStore store=new SourceStore(isolated);
        store.replaceAll(Collections.emptyList());
        try {
            ImportResult first=PlaylistParser.parse("央视,#genre#\nCCTV1,https://example.org/route-a.m3u8?token=one",null);
            assertEquals(1,first.entries.size());assertEquals(1,store.add(first.entries));
            ImportResult second=PlaylistParser.parse("央视,#genre#\nCCTV1,https://example.org/route-b.m3u8?token=two",null);
            assertEquals("Second distinct address must be inserted, even with identical channel name",1,store.add(second.entries));
            ImportResult third=PlaylistParser.parse("#EXTM3U\n#EXTINF:-1 group-title=\"另一份订阅\",央视一套\nhttp://[2001:db8::1]/route-c.m3u8?token=three",null);
            assertEquals(1,store.add(third.entries));
            assertEquals("Only an identical URL is duplicate",0,store.add(first.entries));
            store.close();store=new SourceStore(isolated);
            List<Channel> retained=store.all();assertEquals(3,retained.size());
            assertEquals("Watching should show one station",1,ChannelGuide.programs(retained,null).size());
            assertEquals("All three routes must remain after reopening SQLite",3,ChannelGuide.lines(retained,retained.get(0)).size());
            Set<String> exact=new HashSet<>();for(Channel channel:retained)exact.add(channel.url);
            assertTrue(exact.contains(first.entries.get(0).url));assertTrue(exact.contains(second.entries.get(0).url));assertTrue(exact.contains(third.entries.get(0).url));
        }finally {store.replaceAll(Collections.emptyList());store.close();}
    }
    @Test public void tvboxSameNameDifferentUrlsAreNotDeduplicatedByName() {
        SubscriptionImporter.Result result=SubscriptionImporter.parse("{\"lives\":[{\"name\":\"央视\",\"channels\":[{\"name\":\"CCTV1\",\"urls\":[\"https://example.org/a.m3u8?token=1\",\"https://example.org/a.m3u8?token=2\",\"http://[2001:db8::1]/a.m3u8\",\"https://example.org/a.m3u8?token=1\"]}]}]}",null);
        assertEquals(3,result.playlist.entries.size());assertEquals(1,result.playlist.duplicates);
        assertTrue(result.playlist.errors.toString(),result.playlist.errors.isEmpty());
    }
}
