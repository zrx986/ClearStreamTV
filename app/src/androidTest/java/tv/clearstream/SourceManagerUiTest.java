package tv.clearstream;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import tv.clearstream.core.SourceEntry;
import static org.junit.Assert.*;

/** Synthetic sources only. All management clicks are remote keys; group text is a separate input check. */
@RunWith(AndroidJUnit4.class)
public final class SourceManagerUiTest {
 @Rule public org.junit.rules.TestName testName=new org.junit.rules.TestName();
 private ClearApp app;private UiDevice device;private final JSONArray keys=new JSONArray();
 @Before public void setup(){
  assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
  app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.pressHome();app.stopService(new Intent(app,PlaybackService.class));
  app.store.replaceAll(Collections.emptyList());app.store.getWritableDatabase().delete("source_removal_undo",null,null);for(Website s:app.store.websites())app.store.deleteWebsite(s);
  app.settings.edit().clear().putBoolean("simpleMode",true).commit();app.network.refreshPolicy();
  app.store.add(Arrays.asList(new SourceEntry("CCTV-1 高清","https://example.org/live/one.m3u8","央视"),new SourceEntry("央视一套 备用","https://example.org/live/backup.m3u8","央视"),new SourceEntry("CCTV-2","https://example.org/live/timeout.m3u8","央视"),new SourceEntry("测试卫视","http://[2001:db8::19]/gone.m3u8","卫视")));
  List<Channel> c=app.store.all();app.store.favorite(c.get(0));app.store.status(c.get(0),"已播放 · IPv4");app.store.status(c.get(1),"暂时失败 · HTTP 404");app.store.status(c.get(2),"暂时失败 · 请求超时或已取消");app.store.favorite(c.get(3));app.store.status(c.get(3),"暂时失败 · HTTP 410");
  app.store.addWebsite("https://example.org/movies/");SiteBookmarks bookmarks=new SiteBookmarks(app.settings);bookmarks.toggleSite("https://example.org/movies/","网站收藏");bookmarks.toggleFilm("https://example.org/movies/","https://example.org/movies/one","影片收藏","","","");
 }
 @After public void finish()throws Exception{
  File dir=new File(app.getExternalFilesDir(null),"source-manager-019");dir.mkdirs();device.takeScreenshot(new File(dir,testName.getMethodName()+".png"));device.dumpWindowHierarchy(new File(dir,testName.getMethodName()+".xml"));try(FileOutputStream out=new FileOutputStream(new File(dir,testName.getMethodName()+"-keys.json"))){out.write(keys.toString(2).getBytes(StandardCharsets.UTF_8));}device.pressHome();
 }
 @Test public void clearAllHasCancelWarningAndPersistentUndoWithoutDeletingWebsites()throws Exception{
  launchManager();go(By.text("清空所有 IPTV 源"));key("ok");assertTrue(device.wait(Until.hasObject(By.text("清空所有 IPTV 源？")),3000));assertTrue(device.hasObject(By.textContains("4 条")));assertTrue(device.hasObject(By.textContains("收藏 2 条")));assertEquals("取消",focusText());shot("clear-all-warning");key("ok");assertEquals(4,app.store.all().size());
  go(By.text("清空所有 IPTV 源"));key("ok");go(By.text("确认清空"));key("ok");awaitCount(0);assertEquals(1,app.store.websites().size());assertEquals(1,new SiteBookmarks(app.settings).sites().size());assertEquals(1,new SiteBookmarks(app.settings).films("https://example.org/movies/").size());assertEquals(4,app.store.removalUndo().size());shot("cleared-with-undo");
  launchManager();go(By.desc("撤销上次删除"));key("ok");awaitCount(4);assertEquals(2,app.store.all().stream().filter(c->c.favorite).count());assertEquals(0,app.store.removalUndo().size());
 }
 @Test public void invalidCleanupProtectsFavoritesAndKeepsTimeouts()throws Exception{
  launchManager();go(By.text("清理失效源"));key("ok");assertTrue(device.wait(Until.hasObject(By.text("清理失效源？")),3000));UiObject2 keep=device.findObject(By.text("保留收藏源"));assertNotNull(keep);assertTrue(keep.isChecked());assertEquals("取消",focusText());shot("invalid-cleanup-warning");go(By.text("确认清理"));key("ok");awaitCount(3);
  assertTrue(app.store.all().stream().anyMatch(c->c.url.contains("timeout")));assertTrue(app.store.all().stream().anyMatch(c->c.url.contains("gone")&&c.favorite));assertFalse(app.store.all().stream().anyMatch(c->c.url.contains("backup")));
 }
 @Test public void filteredBatchSelectionCanRegroupAlternateRoutesWithoutChangingUrls()throws Exception{
  launchManager();go(By.desc("筛选检测状态"));key("ok");key("down");key("ok");assertTrue(device.wait(Until.hasObject(By.textContains("结果 2 条")),3000));go(By.text("批量选择"));key("ok");go(By.text("全选当前结果"));key("ok");assertTrue(device.wait(Until.hasObject(By.textContains("已选 2 条")),3000));go(By.desc("批量改组"));key("ok");assertTrue(device.wait(Until.hasObject(By.desc("目标分组")),3000));device.findObject(By.desc("目标分组")).setText("待修复");
  // Accessibility text entry can open the phone emulator's IME. Remote Back closes
  // that input surface; the app's actual Down handler then reaches Save directly.
  device.waitForIdle();if(device.hasObject(By.pkg("com.android.inputmethod.latin"))){key("back");assertTrue("Back must close the text-entry keyboard",device.wait(Until.gone(By.pkg("com.android.inputmethod.latin")),3000));}
  assertTrue("The group field must retain focus after text entry",device.findObject(By.desc("目标分组")).isFocused());key("down");assertEquals("保存分组",focusText());key("ok");long until=SystemClock.elapsedRealtime()+5000;while(app.store.all().stream().filter(c->c.group.equals("待修复")).count()!=2&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(60);assertEquals(2,app.store.all().stream().filter(c->c.group.equals("待修复")).count());assertEquals(4,app.store.all().size());assertEquals(2,app.store.all().stream().filter(c->c.favorite).count());assertEquals(4,new HashSet<>(urls()).size());
 }
 @Test public void familyCanReachManagementAndSearchDownReachesRowsWithRemoteOnly()throws Exception{
  launch();go(By.text("家人设置"));key("ok");assertTrue(device.wait(Until.hasObject(By.text("管理直播源")),3000));key("down");key("ok");assertTrue(device.wait(Until.hasObject(By.text("清理失效源")),3000));go(By.desc("搜索直播源"));key("up");assertEquals("＋ 导入源",focusText());key("down");key("down");assertTrue("Down must leave the search field and reach a source",focusedDescription().startsWith("管理直播源："));assertFalse(device.hasObject(By.pkg("com.android.inputmethod.latin")));
 }
 @Test public void selectionDoesNotFollowAnOldIdOntoANewlyImportedUrl()throws Exception{
  launchManager();go(By.text("批量选择"));key("ok");go(By.desc("管理直播源：CCTV-1 高清"));key("ok");assertTrue(device.wait(Until.hasObject(By.textContains("已选 1 条")),3000));
  app.store.removeSources(app.store.all(),false,false);app.store.add(Collections.singletonList(new SourceEntry("后来新导入","https://example.org/new-line.m3u8","新分组")));
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("sourceManager");field.setAccessible(true);((SourceManager)field.get(current())).refresh();}catch(Exception e){throw new AssertionError(e);}});
  assertTrue("A new URL reusing a deleted row ID must not inherit a user's old selection",device.wait(Until.hasObject(By.textContains("已选 0 条")),3000));assertEquals("https://example.org/new-line.m3u8",app.store.all().get(0).url);
 }
 @Test public void singleSourceRecheckBlocksCleanupUntilTheResultArrives()throws Exception{
  try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
   CountDownLatch requested=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
   worker.execute(()->{try(Socket socket=server.accept()){BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}requested.countDown();release.await(15,TimeUnit.SECONDS);byte[] body="#EXTM3U\n#EXT-X-TARGETDURATION:5\n".getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}catch(Exception ignored){}});
   try{
    app.store.replaceAll(Collections.emptyList());app.store.add(Collections.singletonList(new SourceEntry("正在复检测试","http://127.0.0.1:"+server.getLocalPort()+"/live.m3u8","测试")));app.store.status(app.store.all().get(0),"暂时失败 · HTTP 404");launchManager();key("ok");key("down");key("down");key("ok");assertTrue("The recheck request must really be pending",requested.await(5,TimeUnit.SECONDS));
    assertFalse("Clearing during a pending single-source recheck could delete a recovered source",device.findObject(By.text("清空所有 IPTV 源")).isEnabled());assertFalse(device.findObject(By.text("清理失效源")).isEnabled());
    release.countDown();long until=SystemClock.elapsedRealtime()+8000;while(!app.store.all().get(0).status.startsWith("可解析")&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(60);assertTrue(app.store.all().get(0).status.startsWith("可解析"));assertEquals(1,app.store.all().size());assertTrue(device.wait(Until.hasObject(By.text("清空所有 IPTV 源").enabled(true)),3000));
   }finally{release.countDown();worker.shutdown();}
  }
 }
 @Test public void sourceSearchFindsChannelAliasesAndProtocolFilteringKeepsTheLibrary()throws Exception{
  launchManager();go(By.desc("搜索直播源"));device.findObject(By.desc("搜索直播源")).setText("央视1");assertTrue(device.wait(Until.hasObject(By.textContains("结果 2 条")),4000));assertTrue("Updating search results must keep the input focused",device.findObject(By.desc("搜索直播源")).isFocused());go(By.text("重置筛选"));key("ok");assertTrue(device.wait(Until.hasObject(By.textContains("结果 4 条")),3000));go(By.desc("筛选网络协议"));key("ok");key("down");key("ok");assertTrue(device.wait(Until.hasObject(By.textContains("结果 1 条")),3000));assertTrue(device.hasObject(By.desc("管理直播源：测试卫视")));assertEquals(4,app.store.all().size());
 }
 private void launch(){app.startActivity(Intent.makeMainActivity(new ComponentName(app,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));assertTrue(device.wait(Until.hasObject(By.text("看电视")),10000));}
 private void launchManager(){launch();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("sources",boolean.class);m.setAccessible(true);m.invoke(current(),false);}catch(Exception e){throw new AssertionError(e);}});assertTrue(device.wait(Until.hasObject(By.text("清理失效源")),3000));}
 private Activity current(){for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof MainActivity)return a;throw new AssertionError("Main not resumed");}
 private List<String> urls(){List<String> out=new ArrayList<>();for(Channel c:app.store.all())out.add(c.url);return out;}
 private void shot(String name){File out=new File(app.getExternalFilesDir(null),"source-manager-019");out.mkdirs();device.takeScreenshot(new File(out,name+".png"));}
 private void awaitCount(int count){long until=SystemClock.elapsedRealtime()+6000;while(app.store.all().size()!=count&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(60);assertEquals(count,app.store.all().size());SystemClock.sleep(250);}
 private String focusText(){UiObject2 f=device.findObject(By.focused(true));return f==null?"":f.getText();}
 private String focusedDescription(){UiObject2 f=device.findObject(By.focused(true));return f==null||f.getContentDescription()==null?"":f.getContentDescription();}
 private void key(String key)throws Exception{switch(key){case "up":device.pressDPadUp();break;case "down":device.pressDPadDown();break;case "left":device.pressDPadLeft();break;case "right":device.pressDPadRight();break;case "ok":device.pressDPadCenter();break;case "back":device.pressBack();break;default:throw new AssertionError(key);}SystemClock.sleep(130);keys.put(new JSONObject().put("key",key).put("focus",focusText()).put("description",focusedDescription()));}
 private void go(BySelector selector)throws Exception{for(int i=0;i<50;i++){UiObject2 target=device.findObject(selector);assertNotNull("Target must be visible: "+selector,target);if(target.isFocused())return;UiObject2 focused=device.findObject(By.focused(true));assertNotNull("Focus lost",focused);android.graphics.Rect a=focused.getVisibleBounds(),b=target.getVisibleBounds();int dx=b.centerX()-a.centerX(),dy=b.centerY()-a.centerY();key(Math.abs(dy)>30?dy<0?"up":"down":dx<0?"left":"right");}throw new AssertionError("Remote cannot reach "+selector+", focused="+focusText());}
}
