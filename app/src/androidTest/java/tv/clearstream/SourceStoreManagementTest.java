package tv.clearstream;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import tv.clearstream.core.SourceEntry;
import static org.junit.Assert.*;

/** All changes use a uniquely named test database on a disposable emulator. */
@RunWith(AndroidJUnit4.class)
public final class SourceStoreManagementTest {
    private Context base,isolated;private String prefix;private SourceStore store;
    @Before public void prepare(){
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        base=InstrumentationRegistry.getInstrumentation().getTargetContext();prefix="source-management-"+UUID.randomUUID()+"-";
        isolated=new ContextWrapper(base){
            @Override public File getDatabasePath(String name){return base.getDatabasePath(prefix+name);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return base.openOrCreateDatabase(prefix+name,mode,factory);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return base.openOrCreateDatabase(prefix+name,mode,factory,handler);}
        };
        store=new SourceStore(isolated);
    }
    @After public void cleanup(){if(store!=null)store.close();if(base!=null&&prefix!=null)base.deleteDatabase(prefix+"sources.db");}
    @Test public void invalidCleanupRechecksLatestStatusAndProtectsLatestFavorites(){
        String[] statuses={"暂时失败 · HTTP 404","暂时失败 · HTTP 410","响应为网页 · 不是媒体","暂时失败 · 连接超时","需认证 · HTTP 403","暂时失败 · DNS 解析失败","暂时失败 · HTTP 503","未检测","已播放 · IPv6","暂时失败 · HTTP 404"};
        for(int i=0;i<statuses.length;i++)assertTrue(store.restore(channel(i+1,"台"+i,"line"+i,"央视",false,statuses[i],100+i,"未知")));
        assertTrue(store.addWebsite("https://example.org/movies"));List<Channel> snapshot=store.all();
        store.favorite(snapshot.get(0));store.status(snapshot.get(1),"已播放 · IPv4");
        assertEquals("Only definite invalid, currently unstarred lines may be deleted",2,store.removeSources(snapshot,true,true));
        assertEquals(8,store.all().size());assertTrue(find("line0").favorite);assertNotNull(find("line1"));assertNull(find("line2"));assertNull(find("line9"));assertEquals(2,store.removalUndo().size());assertEquals(1,store.websites().size());
    }
    @Test public void undoSurvivesReopenAndRestoresLatestFieldsWhilePreservingReusedIdsAndNewImports(){
        Channel first=channel(1,"旧名称","first","旧组",false,"暂时失败 · HTTP 404",111,"IPv4"),second=channel(2,"CCTV1","second","高清",true,"已播放 · IPv6",222,"IPv6");
        assertTrue(store.restore(first));assertTrue(store.restore(second));assertTrue(store.restore(channel(3,"保留台","retained","其他",false,"未检测",0,"未知")));
        List<Channel> snapshot=Arrays.asList(store.all().get(0),store.all().get(1));store.rename(first,"更新名称","最新分组");store.favorite(first);store.played("1","IPv6");Channel latest=find("first");
        assertEquals(2,store.removeSources(snapshot,false,false));
        assertTrue(store.restore(channel(1,"新台占号","new-id-one","新组",false,"未检测",0,"未知")));
        assertEquals(1,store.add(Collections.singletonList(new SourceEntry("新导入",url("new-import"),"新组"))));
        store.close();store=new SourceStore(isolated);assertEquals(2,store.removalUndo().size());assertEquals(2,store.undoRemoval());assertTrue(store.removalUndo().isEmpty());
        Channel restored=find("first");assertNotNull(restored);assertNotEquals("Old ID occupied by a new source must not be overwritten",1,restored.id);assertEquals("更新名称",restored.name);assertEquals("最新分组",restored.group);assertTrue(restored.favorite);assertEquals(latest.status,restored.status);assertEquals(latest.checked,restored.checked);assertEquals("IPv6",restored.observedFamily);
        Channel back=find("second");assertEquals(2,back.id);assertTrue(back.favorite);assertEquals(222,back.checked);assertNotNull(find("new-id-one"));assertNotNull(find("new-import"));assertNotNull(find("retained"));assertEquals(5,store.all().size());
    }
    @Test public void undoKeepsNewerSameUrlEditsAndConsumesThatJournalEntry(){
        assertTrue(store.restore(channel(7,"原名","same-url","原组",false,"未检测",0,"未知")));assertEquals(1,store.removeSources(store.all(),false,false));
        assertEquals(1,store.add(Collections.singletonList(new SourceEntry("用户新名称",url("same-url"),"用户新组"))));Channel edited=find("same-url");store.favorite(edited);store.status(edited,"已播放 · IPv6");
        assertEquals("The same URL already exists, so no old row should be restored",0,store.undoRemoval());assertTrue(store.removalUndo().isEmpty());Channel retained=find("same-url");assertEquals("用户新名称",retained.name);assertEquals("用户新组",retained.group);assertTrue(retained.favorite);assertEquals("已播放 · IPv6",retained.status);assertEquals(1,store.all().size());
    }
    @Test public void zeroMatchKeepsPreviousUndoAndClearAllOnlyDeletesConfirmedSnapshot(){
        assertTrue(store.restore(channel(1,"A","a","组",false,"未检测",0,"未知")));assertTrue(store.restore(channel(2,"B","b","组",false,"未检测",0,"未知")));assertTrue(store.addWebsite("https://example.org/site"));
        Channel a=store.all().get(0);assertEquals(1,store.removeSources(Collections.singletonList(a),false,false));
        assertEquals(0,store.removeSources(Collections.singletonList(a),false,false));assertEquals(url("a"),store.removalUndo().get(0).url);
        assertEquals(0,store.removeSources(store.all(),true,true));assertEquals(url("a"),store.removalUndo().get(0).url);
        assertEquals(1,store.add(Collections.singletonList(new SourceEntry("C",url("c"),"组"))));List<Channel> confirmed=store.all();
        assertEquals(1,store.add(Collections.singletonList(new SourceEntry("确认后新增",url("late"),"组"))));
        assertEquals(2,store.removeSources(confirmed,false,false));assertEquals(1,store.all().size());assertEquals(url("late"),store.all().get(0).url);assertEquals(2,store.removalUndo().size());assertEquals(1,store.websites().size());assertEquals(2,store.undoRemoval());assertNotNull(find("late"));
    }
    @Test public void undoAtTenThousandCapacityKeepsUnrestoredRowsForLater(){
        assertTrue(store.restore(channel(1,"待恢复一","old-one","原组",true,"已播放 · IPv6",33,"IPv6")));assertTrue(store.restore(channel(2,"待恢复二","old-two","原组",false,"未检测",44,"未知")));
        assertEquals(2,store.removeSources(store.all(),false,false));List<SourceEntry> fill=new ArrayList<>();for(int i=0;i<9999;i++)fill.add(new SourceEntry("占位"+i,url("fill-"+i),"占位"));assertEquals(9999,store.add(fill));
        assertEquals(1,store.undoRemoval());assertEquals(10000,store.all().size());assertEquals(1,store.removalUndo().size());
        Channel free=null;for(Channel row:store.all())if(row.url.contains("/fill-")){free=row;break;}assertNotNull(free);store.delete(free);
        assertEquals(1,store.undoRemoval());assertTrue(store.removalUndo().isEmpty());assertEquals(10000,store.all().size());assertNotNull(find("old-one"));assertNotNull(find("old-two"));assertTrue(find("old-one").favorite);assertEquals("IPv6",find("old-one").observedFamily);
    }
    @Test public void regroupMatchesIdAndUrlAndRetainsSameChannelRoutesAndFavorites(){
        assertTrue(store.restore(channel(1,"CCTV1","route-a","原组A",true,"已播放 · IPv6",11,"IPv6")));assertTrue(store.restore(channel(2,"CCTV1","route-b","原组B",false,"未检测",22,"未知")));assertTrue(store.restore(channel(3,"别台","other","保留组",false,"未检测",0,"未知")));
        List<Channel> snapshot=new ArrayList<>(store.all());snapshot.get(2).url=url("wrong-url");
        assertEquals(2,store.regroupSources(snapshot,"  央视常看  "));Channel a=find("route-a"),b=find("route-b");assertEquals("央视常看",a.group);assertEquals("央视常看",b.group);assertEquals("保留组",find("other").group);assertTrue(a.favorite);assertFalse(b.favorite);assertEquals("已播放 · IPv6",a.status);assertEquals(11,a.checked);assertEquals(3,store.all().size());assertNotEquals(a.url,b.url);
        try{store.regroupSources(store.all(),"   ");fail("Blank group must be rejected");}catch(IllegalArgumentException expected){}
        char[] longName=new char[81];Arrays.fill(longName,'组');try{store.regroupSources(store.all(),new String(longName));fail("Group over 80 characters must be rejected");}catch(IllegalArgumentException expected){}
    }
    @Test public void upgradingVersionThreeKeepsSourcesAndWebsitesAndCreatesPersistentUndo(){
        store.close();try(SQLiteDatabase db=isolated.openOrCreateDatabase("sources.db",Context.MODE_PRIVATE,null)){
            db.execSQL("CREATE TABLE sources (id INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL UNIQUE, grp TEXT NOT NULL, favorite INTEGER DEFAULT 0, status TEXT DEFAULT '未检测', checked INTEGER DEFAULT 0, family TEXT DEFAULT '未知')");db.execSQL("CREATE TABLE websites (id INTEGER PRIMARY KEY,name TEXT NOT NULL,url TEXT NOT NULL UNIQUE)");
            db.execSQL("INSERT INTO sources(id,name,url,grp,favorite,status,checked,family) VALUES(9,'迁移台',?,'旧组',1,'已播放 · IPv6',1234,'IPv6')",new Object[]{url("migrated")});db.execSQL("INSERT INTO websites(id,name,url) VALUES(4,'保留网站','https://example.org/movies')");db.setVersion(3);
        }
        store=new SourceStore(isolated);assertEquals(1,store.all().size());assertEquals(9,store.all().get(0).id);assertEquals(1,store.websites().size());assertEquals(4,store.getReadableDatabase().getVersion());
        try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='source_removal_undo'",null)){assertTrue(cursor.moveToFirst());}
        assertEquals(1,store.removeSources(store.all(),false,false));store.close();store=new SourceStore(isolated);assertEquals(1,store.removalUndo().size());assertEquals(1,store.websites().size());assertEquals(1,store.undoRemoval());assertEquals(9,store.all().get(0).id);assertEquals(1234,store.all().get(0).checked);assertTrue(store.all().get(0).favorite);
    }
    @Test public void staleStatusRenameAndFavoriteCannotChangeANewUrlReusingTheSameId(){
        Channel stale=channel(1,"旧台","removed","旧组",false,"未检测",0,"未知");assertTrue(store.restore(stale));store.delete(stale);assertTrue(store.restore(channel(1,"新台","replacement","新组",false,"未检测",0,"未知")));
        store.status(stale,"暂时失败 · HTTP 404");store.rename(stale,"旧操作改名","旧操作分组");store.favorite(stale);
        Channel current=find("replacement");assertNotNull(current);assertEquals("新台",current.name);assertEquals("新组",current.group);assertEquals("未检测",current.status);assertEquals(0,current.checked);assertFalse(current.favorite);assertFalse("A stale model must not be toggled as though a row was changed",stale.favorite);
    }
    @Test public void playbackStatusUrlOverloadOnlyUpdatesTheCurrentMatchingRoute(){
        Channel current=channel(1,"新台","replacement","新组",false,"未检测",0,"未知");assertTrue(store.restore(current));
        store.played("1",url("removed"),"IPv6");assertEquals("未检测",find("replacement").status);
        store.played("1",current.url,"IPv6");assertEquals("已播放 · IPv6",find("replacement").status);assertEquals("IPv6",find("replacement").observedFamily);assertTrue(find("replacement").checked>0);
    }
    private static String url(String suffix){return "https://example.org/"+suffix+".m3u8";}
    private static Channel channel(long id,String name,String suffix,String group,boolean favorite,String status,long checked,String family){Channel row=new Channel();row.id=id;row.name=name;row.url=url(suffix);row.group=group;row.favorite=favorite;row.status=status;row.checked=checked;row.observedFamily=family;return row;}
    private Channel find(String suffix){String expected=url(suffix);for(Channel row:store.all())if(expected.equals(row.url))return row;return null;}
}
