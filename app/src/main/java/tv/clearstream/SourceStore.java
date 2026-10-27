package tv.clearstream;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import tv.clearstream.core.*;

public final class SourceStore extends SQLiteOpenHelper {
    public SourceStore(Context context) { super(context, "sources.db", null, 4); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sources (id INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL UNIQUE, grp TEXT NOT NULL, favorite INTEGER DEFAULT 0, status TEXT DEFAULT '未检测', checked INTEGER DEFAULT 0, family TEXT DEFAULT '未知')");
        db.execSQL("CREATE TABLE websites (id INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL UNIQUE)");
        createRemovalUndo(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<2)db.execSQL("ALTER TABLE sources ADD COLUMN family TEXT DEFAULT '未知'");
        if(oldVersion<3)db.execSQL("CREATE TABLE websites (id INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL UNIQUE)");
        if(oldVersion<4)createRemovalUndo(db);
    }
    private static void createRemovalUndo(SQLiteDatabase db){
        db.execSQL("CREATE TABLE source_removal_undo (url TEXT PRIMARY KEY NOT NULL, old_id INTEGER NOT NULL, name TEXT NOT NULL, grp TEXT NOT NULL, favorite INTEGER DEFAULT 0, status TEXT DEFAULT '未检测', checked INTEGER DEFAULT 0, family TEXT DEFAULT '未知')");
    }
    public synchronized List<Channel> all() {
        List<Channel> channels = new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT * FROM sources ORDER BY id", null)) {
            while (c.moveToNext()) {
                Channel v=new Channel(); v.id=c.getLong(0); v.name=c.getString(1); v.url=c.getString(2);
                v.group=c.getString(3); v.favorite=c.getInt(4)!=0; v.status=c.getString(5); v.checked=c.getLong(6); v.observedFamily=c.getString(7); channels.add(v);
            }
        }
        return channels;
    }
    public synchronized int add(List<SourceEntry> entries) {
        SQLiteDatabase db=getWritableDatabase(); int added=0; db.beginTransaction();
        try {
            long count=android.database.DatabaseUtils.queryNumEntries(db,"sources");
            for (SourceEntry e:entries) {
                if(count>=10000)break;
                if (!UrlPolicy.isSupported(e.url)) continue;
                ContentValues v=new ContentValues(); v.put("name",e.name); v.put("url",e.url); v.put("grp",e.group);
                if (db.insertWithOnConflict("sources", null, v, SQLiteDatabase.CONFLICT_IGNORE)!=-1) {added++;count++;}
            }
            db.setTransactionSuccessful(); return added;
        } finally { db.endTransaction(); }
    }
    public synchronized void favorite(Channel c) {
        ContentValues v=new ContentValues(); v.put("favorite", c.favorite?0:1);
        if(getWritableDatabase().update("sources",v,"id=? AND url=?",new String[]{Long.toString(c.id),c.url})>0)c.favorite=!c.favorite;
    }
    public synchronized void rename(Channel c, String name, String group) {
        ContentValues v=new ContentValues(); v.put("name",name); v.put("grp",group);
        getWritableDatabase().update("sources",v,"id=? AND url=?",new String[]{Long.toString(c.id),c.url});
    }
    public synchronized void status(Channel c, String status) {
        ContentValues v=new ContentValues(); v.put("status",status); v.put("checked",System.currentTimeMillis());
        getWritableDatabase().update("sources",v,"id=? AND url=?",new String[]{Long.toString(c.id),c.url});
    }
    public synchronized void delete(Channel c) { getWritableDatabase().delete("sources","id=?",new String[]{Long.toString(c.id)}); }
    public synchronized void played(String id,String family) {
        ContentValues v=new ContentValues();v.put("family",family);v.put("status","已播放 · "+family);v.put("checked",System.currentTimeMillis());
        getWritableDatabase().update("sources",v,"id=?",new String[]{id});
    }
    public synchronized void replaceAll(List<Channel> channels) {
        if(channels.size()>10000)throw new IllegalArgumentException("源库最多一万条");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {db.delete("sources",null,null);for(Channel c:channels)restore(c);db.setTransactionSuccessful();}
        finally {db.endTransaction();}
    }
    public synchronized List<Website> websites() {
        List<Website> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,url FROM websites ORDER BY id",null)) {
            while(c.moveToNext()){Website w=new Website();w.id=c.getLong(0);w.name=c.getString(1);w.url=c.getString(2);result.add(w);}
        }
        return result;
    }
    public synchronized boolean addWebsite(String url) {
        String normalized=WebsitePolicy.normalize(url);if(normalized==null)return false;
        ContentValues v=new ContentValues();v.put("name",WebsitePolicy.nameOf(normalized));v.put("url",normalized);
        return getWritableDatabase().insertWithOnConflict("websites",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    public synchronized void deleteWebsite(Website site) {getWritableDatabase().delete("websites","id=?",new String[]{Long.toString(site.id)});}
    public synchronized boolean restore(Channel c) {
        if(android.database.DatabaseUtils.queryNumEntries(getReadableDatabase(),"sources")>=10000)return false;
        ContentValues v=new ContentValues(); v.put("id",c.id); v.put("name",c.name); v.put("url",c.url); v.put("grp",c.group); v.put("favorite",c.favorite?1:0); v.put("status",c.status); v.put("checked",c.checked); v.put("family",c.observedFamily);
        return getWritableDatabase().insertWithOnConflict("sources",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    /** Rechecks the current row; a stale confirmation must never target an ID's replacement URL. */
    public synchronized int removeSources(Collection<Channel> snapshot,boolean invalidOnly,boolean keepFavorites){
        if(snapshot==null||snapshot.isEmpty())return 0;
        SQLiteDatabase db=getWritableDatabase();int removed=0;db.beginTransaction();
        try{
            for(Channel selected:snapshot){
                if(selected==null||selected.url==null)continue;
                String[] identity={Long.toString(selected.id),selected.url};Channel current=null;
                try(Cursor cursor=db.rawQuery("SELECT id,name,url,grp,favorite,status,checked,family FROM sources WHERE id=? AND url=?",identity)){if(cursor.moveToFirst())current=readChannel(cursor);}
                if(current==null||(invalidOnly&&!SourceManagement.clearable(current.status))||(keepFavorites&&current.favorite))continue;
                if(db.delete("sources","id=? AND url=?",identity)==0)continue;
                if(removed==0)db.delete("source_removal_undo",null,null);
                ContentValues undo=channelValues(current);undo.remove("id");undo.put("old_id",current.id);
                db.insertOrThrow("source_removal_undo",null,undo);removed++;
            }
            db.setTransactionSuccessful();return removed;
        }finally{db.endTransaction();}
    }
    public synchronized List<Channel> removalUndo(){
        List<Channel> result=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT old_id,name,url,grp,favorite,status,checked,family FROM source_removal_undo ORDER BY old_id,url",null)){while(cursor.moveToNext())result.add(readChannel(cursor));}
        return result;
    }
    /** Restores missing URLs only; a full source library leaves the remaining journal intact. */
    public synchronized int undoRemoval(){
        SQLiteDatabase db=getWritableDatabase();int restored=0;db.beginTransaction();
        try{
            long count=android.database.DatabaseUtils.queryNumEntries(db,"sources");
            for(Channel removed:removalUndo()){
                String[] url={removed.url};
                if(android.database.DatabaseUtils.queryNumEntries(db,"sources","url=?",url)>0){db.delete("source_removal_undo","url=?",url);continue;}
                if(count>=10000)continue;
                ContentValues values=channelValues(removed);
                if(android.database.DatabaseUtils.queryNumEntries(db,"sources","id=?",new String[]{Long.toString(removed.id)})>0)values.remove("id");
                db.insertOrThrow("sources",null,values);db.delete("source_removal_undo","url=?",url);count++;restored++;
            }
            db.setTransactionSuccessful();return restored;
        }finally{db.endTransaction();}
    }
    public synchronized int regroupSources(Collection<Channel> snapshot,String group){
        if(group==null)throw new IllegalArgumentException("分组名称不能为空");String normalized=group.trim();
        if(normalized.isEmpty()||normalized.codePointCount(0,normalized.length())>80)throw new IllegalArgumentException("分组名称需要 1 至 80 个字");
        if(snapshot==null||snapshot.isEmpty())return 0;
        SQLiteDatabase db=getWritableDatabase();Set<Long> changed=new HashSet<>();ContentValues values=new ContentValues();values.put("grp",normalized);db.beginTransaction();
        try{
            for(Channel selected:snapshot){if(selected==null||selected.url==null||changed.contains(selected.id))continue;if(db.update("sources",values,"id=? AND url=?",new String[]{Long.toString(selected.id),selected.url})>0)changed.add(selected.id);}
            db.setTransactionSuccessful();return changed.size();
        }finally{db.endTransaction();}
    }
    public synchronized void played(String id,String url,String family){
        ContentValues values=new ContentValues();values.put("family",family);values.put("status","已播放 · "+family);values.put("checked",System.currentTimeMillis());
        getWritableDatabase().update("sources",values,"id=? AND url=?",new String[]{id,url});
    }
    private static Channel readChannel(Cursor cursor){
        Channel row=new Channel();row.id=cursor.getLong(0);row.name=cursor.getString(1);row.url=cursor.getString(2);row.group=cursor.getString(3);row.favorite=cursor.getInt(4)!=0;row.status=cursor.getString(5);row.checked=cursor.getLong(6);row.observedFamily=cursor.getString(7);return row;
    }
    private static ContentValues channelValues(Channel row){
        ContentValues values=new ContentValues();values.put("id",row.id);values.put("name",row.name);values.put("url",row.url);values.put("grp",row.group);values.put("favorite",row.favorite?1:0);values.put("status",row.status);values.put("checked",row.checked);values.put("family",row.observedFamily);return values;
    }
}
