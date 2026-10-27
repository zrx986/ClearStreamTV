package tv.clearstream;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Fixture checks preserve the real app's existing bookmark preference values. */
@RunWith(AndroidJUnit4.class)
public final class FavoritesActivityTest {
    @Rule public final TestName testName=new TestName();
    private ClearApp app;private UiDevice device;private Server server;private String firstRoot,secondRoot;private final Map<String,Object> previous=new HashMap<>();
    private Activity lastFavorites;
    private static final String[] KEYS={"siteBookmarks","favoritesTab","favoritesFilmFocus","favoritesSiteFocus"};
    @Before public void prepare() throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());server=new Server();server.start();firstRoot=server.url("/first");secondRoot=server.url("/second");
        Map<String,?> current=app.settings.getAll();SharedPreferences.Editor editor=app.settings.edit();for(String key:KEYS){previous.put(key,current.get(key));editor.remove(key);}editor.putString("siteBookmarks","[]").commit();
        SiteBookmarks saved=new SiteBookmarks(app.settings);saved.toggleSite(firstRoot,"朋友站甲");saved.toggleSite(secondRoot,"朋友站乙");saved.toggleFilm(firstRoot,server.url("/film/one"),"收藏电影甲",server.url("/poster.png"),"保存的影片简介甲","no-referrer");saved.toggleFilm(secondRoot,server.url("/film/two"),"收藏电影乙",server.url("/poster.png"),"保存的影片简介乙","");
    }
    @After public void cleanup() throws Exception {
        capture();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Set<Activity> owned=new LinkedHashSet<>();for(androidx.test.runner.lifecycle.Stage stage:new androidx.test.runner.lifecycle.Stage[]{androidx.test.runner.lifecycle.Stage.CREATED,androidx.test.runner.lifecycle.Stage.STARTED,androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STOPPED})owned.addAll(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));
            for(Activity activity:owned)if(activity instanceof FavoritesActivity||activity instanceof SiteActivity)activity.finish();
        });
        // HOME temporarily suppresses app switches; starting the next fixture from a background
        // application then races its window-ready timeout. Finishing our windows is sufficient.
        long deadline=SystemClock.elapsedRealtime()+5000;boolean[] remaining={true};
        while(remaining[0]&&SystemClock.elapsedRealtime()<deadline){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{remaining[0]=false;for(androidx.test.runner.lifecycle.Stage stage:new androidx.test.runner.lifecycle.Stage[]{androidx.test.runner.lifecycle.Stage.CREATED,androidx.test.runner.lifecycle.Stage.STARTED,androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STOPPED})for(Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))if(activity instanceof FavoritesActivity||activity instanceof SiteActivity)remaining[0]=true;});if(remaining[0])SystemClock.sleep(50);}
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();SharedPreferences.Editor editor=app.settings.edit();for(String key:KEYS){editor.remove(key);Object old=previous.get(key);if(old instanceof String)editor.putString(key,(String)old);else if(old instanceof Integer)editor.putInt(key,(Integer)old);}editor.commit();server.close();
        assertFalse("Owned activities must finish before restoring bookmark preferences",remaining[0]);
    }
    @Test public void favoriteHomeShowsAllSavedFilmsAndOpensSavedPreviewWithoutReadingWebsiteHome() throws Exception {
        launch();assertTrue("Native favorites title",device.wait(Until.hasObject(By.text("我的收藏")),4000));assertTrue("Film tab text",device.hasObject(By.text("影片")));assertTrue("Website tab text",device.hasObject(By.text("网站")));assertTrue("Channel shortcut text",device.hasObject(By.text("频道收藏")));
        assertTrue(device.hasObject(By.desc("收藏影片：收藏电影甲")));assertTrue(device.hasObject(By.desc("收藏影片：收藏电影乙")));
        assertNotNull("Website-root check must appear on film card",device.wait(Until.findObject(By.desc("收藏网站状态：收藏电影甲").textContains("网页可访问")),4000));server.requests.clear();
        activate("收藏影片：收藏电影甲");assertTrue(device.wait(Until.hasObject(By.text("影视预览")),4000));assertTrue(device.hasObject(By.text("收藏电影甲")));assertTrue(device.hasObject(By.text("保存的影片简介甲")));assertTrue(device.hasObject(By.desc("预览封面：收藏电影甲")));assertTrue(device.hasObject(By.text("返回收藏")));
        SystemClock.sleep(900);assertFalse("A saved preview must not reload the website home",server.requests.contains("/first"));
        device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("我的收藏")),4000));assertEquals("收藏影片：收藏电影甲",device.findObject(By.focused(true)).getContentDescription());
        device.pressBack();launch();assertTrue(device.wait(Until.hasObject(By.desc("收藏影片：收藏电影甲")),4000));assertTrue(device.hasObject(By.desc("收藏影片：收藏电影乙")));
    }
    @Test public void websiteTabAndChosenCardFocusSurviveReturnAndReopen() throws Exception {
        launch();assertTrue(device.wait(Until.hasObject(By.text("我的收藏")),4000));activate("收藏标签：网站");assertTrue(device.hasObject(By.desc("收藏网站：朋友站乙")));
        activate("收藏网站：朋友站乙");Activity portal=await(SiteActivity.class);assertEquals(secondRoot,portal.getIntent().getStringExtra("url"));assertNull(portal.getIntent().getStringExtra("bookmarkUrl"));
        device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("我的收藏")),4000));assertTrue(device.findObject(By.desc("收藏标签：网站")).isSelected());assertEquals("收藏网站：朋友站乙",device.findObject(By.focused(true)).getContentDescription());
        device.pressBack();launch();assertTrue(device.wait(Until.hasObject(By.desc("收藏网站：朋友站乙")),4000));assertTrue(device.findObject(By.desc("收藏标签：网站")).isSelected());assertEquals("收藏网站：朋友站乙",device.findObject(By.focused(true)).getContentDescription());
    }
    @Test public void removingFilmRequiresConfirmationDefaultsToCancelAndPreservesOtherBookmarks() throws Exception {
        launch();assertTrue(device.wait(Until.hasObject(By.text("我的收藏")),4000));activate("取消影片收藏：收藏电影甲");assertTrue(device.wait(Until.hasObject(By.text("取消收藏？")),3000));assertEquals("取消",device.findObject(By.focused(true)).getText());device.pressDPadCenter();assertTrue("Cancel must dismiss the confirmation",device.wait(Until.gone(By.text("取消收藏？")),3000));assertTrue(new SiteBookmarks(app.settings).hasFilm(firstRoot,server.url("/film/one")));
        activate("取消影片收藏：收藏电影甲");assertTrue(device.wait(Until.hasObject(By.text("取消收藏？")),3000));device.pressDPadRight();assertEquals("取消收藏",device.findObject(By.focused(true)).getText());device.pressDPadCenter();
        assertTrue("Remote confirmation must dismiss the dialog",device.wait(Until.gone(By.text("取消收藏？")),3000));assertTrue("Removed film must disappear from the native list",device.wait(Until.gone(By.desc("收藏影片：收藏电影甲")),3000));
        assertFalse(new SiteBookmarks(app.settings).hasFilm(firstRoot,server.url("/film/one")));assertTrue(new SiteBookmarks(app.settings).hasFilm(secondRoot,server.url("/film/two")));assertEquals(2,new SiteBookmarks(app.settings).sites().size());
        device.pressBack();launch();assertTrue(device.wait(Until.hasObject(By.desc("收藏影片：收藏电影乙")),4000));assertFalse(device.hasObject(By.desc("收藏影片：收藏电影甲")));
    }
    @Test public void unbookmarkedPreviewExtraCannotOpenAnArbitraryPageOrReadHome() throws Exception {
        app.startActivity(new Intent(app,SiteActivity.class).putExtra("url",firstRoot).putExtra("name","朋友站甲").putExtra("bookmarkUrl",server.url("/not-bookmarked")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await(SiteActivity.class);assertTrue("Unsaved bookmark URL must show a local missing-bookmark page",device.wait(Until.hasObject(By.text("收藏已不存在")),4000));SystemClock.sleep(300);assertFalse(server.requests.contains("/first"));assertFalse(server.requests.contains("/not-bookmarked"));assertFalse(device.hasObject(By.text("进入网站播放")));
    }
    @Test public void exactRemovalIsIdempotentAndDoesNotRemoveSameFilmFromAnotherRoot(){
        SiteBookmarks saved=new SiteBookmarks(app.settings);String same=server.url("/film/shared");saved.toggleFilm(firstRoot,same,"同片甲","","甲简介","");saved.toggleFilm(secondRoot,same,"同片乙","","乙简介","");
        assertTrue(saved.removeFilm(firstRoot,same));assertFalse(saved.removeFilm(firstRoot,same));assertFalse(saved.hasFilm(firstRoot,same));assertTrue(saved.hasFilm(secondRoot,same));assertEquals("乙简介",saved.film(secondRoot,same).summary);
        int sources=app.store.all().size(),websites=app.store.websites().size();assertTrue(saved.removeSite(firstRoot));assertFalse(saved.removeSite(firstRoot));assertTrue(saved.hasFilm(firstRoot,server.url("/film/one")));assertEquals(sources,app.store.all().size());assertEquals(websites,app.store.websites().size());
    }
    private void launch() throws Exception {Activity previous=lastFavorites;app.startActivity(new Intent(app,FavoritesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));long deadline=SystemClock.elapsedRealtime()+5000;Activity ready=null;while(SystemClock.elapsedRealtime()<deadline){ready=foreground(FavoritesActivity.class);if(ready!=null&&ready!=previous)break;SystemClock.sleep(50);}assertNotNull("A new favorites window must be ready",ready);assertNotSame(previous,ready);lastFavorites=ready;InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    /** Focus setup is local; every activation itself goes through the real remote confirm event. */
    private void activate(String description) throws Exception {
        Activity activity=await(FavoritesActivity.class);boolean[] focused={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{View target=find(activity.getWindow().getDecorView(),description);focused[0]=target!=null&&target.requestFocus();});assertTrue("Focusable control "+description,focused[0]);InstrumentationRegistry.getInstrumentation().waitForIdleSync();device.pressDPadCenter();device.waitForIdle();
    }
    private static View find(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View result=find(((ViewGroup)root).getChildAt(i),description);if(result!=null)return result;}return null;}
    private Activity foreground(Class<? extends Activity> wanted){Activity[] activity={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity candidate:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if((wanted==null||wanted.isInstance(candidate))&&!candidate.isFinishing()&&candidate.hasWindowFocus())activity[0]=candidate;});return activity[0];}
    private Activity await(Class<? extends Activity> wanted) throws Exception {Activity activity=null;long deadline=SystemClock.elapsedRealtime()+5000;while(activity==null&&SystemClock.elapsedRealtime()<deadline){activity=foreground(wanted);if(activity==null)SystemClock.sleep(50);}assertNotNull("Focused foreground activity "+wanted,activity);return activity;}
    private void capture(){try{File directory=new File(app.getExternalFilesDir(null),"evidence");directory.mkdirs();String name="favorites-"+testName.getMethodName();device.takeScreenshot(new File(directory,name+".png"));device.dumpWindowHierarchy(new File(directory,name+".xml"));StringBuilder report=new StringBuilder();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)){View focused=activity.getCurrentFocus();report.append(activity.getClass().getName()).append(" windowFocus=").append(activity.hasWindowFocus()).append(" finishing=").append(activity.isFinishing()).append(" focused=").append(focused==null?"none":focused.getContentDescription()).append(" tag=").append(focused==null?"":focused.getTag()).append('\n');}report.append("tab=").append(app.settings.getInt("favoritesTab",0)).append(" filmFocus=").append(app.settings.getString("favoritesFilmFocus","")).append(" siteFocus=").append(app.settings.getString("favoritesSiteFocus","")).append('\n');});report.append("requests=").append(server.requests);try(FileOutputStream out=new FileOutputStream(new File(directory,name+".txt"))){out.write(report.toString().getBytes(StandardCharsets.UTF_8));}}catch(IOException|RuntimeException ignored){}}
    private static final class Server implements AutoCloseable {
        final ServerSocket socket;final ExecutorService workers=Executors.newCachedThreadPool();final Set<String> requests=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());volatile boolean closed;
        Server() throws IOException{socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));}
        String url(String path){return "http://127.0.0.1:"+socket.getLocalPort()+path;}
        void start(){workers.execute(()->{while(!closed)try{Socket client=socket.accept();workers.execute(()->serve(client));}catch(IOException ignored){}});}
        void serve(Socket client){try(Socket socket=client){socket.setSoTimeout(2000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=reader.readLine();if(first==null)return;String path=first.split(" ")[1];requests.add(path);String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}byte[] data;String type;
            if(path.equals("/poster.png")){android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(16,24,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff4c9aff);ByteArrayOutputStream output=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);bitmap.recycle();data=output.toByteArray();type="image/png";}
            else{data="<!doctype html><meta charset='utf-8'><title>测试收藏网站</title><p>网站内容</p>".getBytes(StandardCharsets.UTF_8);type="text/html; charset=utf-8";}
            OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
        }catch(IOException ignored){}}
        @Override public void close() throws IOException{closed=true;socket.close();workers.shutdownNow();}
    }
}
