package tv.clearstream;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Fixture typing is setup; navigation/confirmation itself uses remote key events. */
@RunWith(AndroidJUnit4.class)
public class FilmSearchUiTest {
    private ClearApp app;private UiDevice device;private Server first,second;
    @Before public void prepare() throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        finishOwned();app.stopService(new Intent(app,PlaybackService.class));app.store.replaceAll(Collections.emptyList());for(Website site:app.store.websites())app.store.deleteWebsite(site);
        app.settings.edit().clear().putBoolean("simpleMode",true).commit();app.network.refreshPolicy();
        first=new Server(false);second=new Server(true);first.start();second.start();app.store.addWebsite(first.url("/"));
    }
    @After public void cleanup() throws Exception {
        File dir=new File(app.getExternalFilesDir(null),"search-021");dir.mkdirs();device.takeScreenshot(new File(dir,"last-screen.png"));device.dumpWindowHierarchy(new File(dir,"last-screen.xml"));
        finishOwned();first.close();second.close();
    }
    @Test public void homeAndWebsitePageExposeOneClickFilmSearch()throws Exception {
        app.startActivity(Intent.makeMainActivity(new ComponentName(app,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue("Simple home needs its own film-search entry",device.wait(Until.hasObject(By.text("一键搜片")),8000));
        activate("一键搜片");assertTrue(device.wait(Until.hasObject(By.text("动漫 / 电影 / 电视剧，输入名字就能找")),4000));
        device.pressBack();activate("看影视");assertTrue(device.wait(Until.hasObject(By.text("选择网站 · 搜索影片 · 按确定播放")),4000));assertTrue(device.hasObject(By.text("一键搜片")));
    }
    @Test public void twoSiteSearchHasRealResultsFailureIsolationAndFavoritePreview()throws Exception {
        Assume.assumeTrue(provider());app.store.addWebsite(second.url("/"));launch();type("海风");activate("一键搜索");
        assertTrue("Search result must be a native remote card",device.wait(Until.hasObject(By.desc("搜索影片：测试动漫海风")),12000));
        assertFalse("Home recommendations are not search results",device.hasObject(By.desc("搜索影片：首页推荐不能算结果")));
        assertTrue("One failing site cannot block the successful result",device.wait(Until.hasObject(By.textContains("HTTP 503")),12000));
        assertTrue(first.requests.toString(),first.requests.contains("/search?type=all&wd=%E6%B5%B7%E9%A3%8E"));assertFalse(second.requests.toString(),second.requests.contains("/movie/503"));
        // From the still-focused search button, down enters the native result.
        activate("一键搜索");assertTrue(device.wait(Until.hasObject(By.desc("搜索影片：测试动漫海风")),12000));
        device.pressDPadDown();assertEquals("搜索影片：测试动漫海风",device.findObject(By.focused(true)).getContentDescription());device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("影视预览")),4000));assertTrue(device.hasObject(By.text("返回搜索")));assertTrue(device.hasObject(By.text("测试动漫海风")));
        assertFalse("Preview must not reread the site home",device.hasObject(By.text("站内搜索 · 遥控器选片"))&&device.hasObject(By.text("首页精选")));
        device.pressDPadRight();assertEquals("收藏影片",device.findObject(By.focused(true)).getText());device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("取消收藏")),3000));
        assertTrue(new SiteBookmarks(app.settings).hasFilm(first.url("/"),first.url("/movie/1")));
        device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("一键搜片")),4000));assertEquals("搜索影片：测试动漫海风",device.findObject(By.focused(true)).getContentDescription());
        activate("我的收藏");assertTrue(device.wait(Until.hasObject(By.desc("收藏影片：测试动漫海风")),4000));
    }
    @Test public void scopeCanAddWebsiteAndPersistTheChosenRange()throws Exception {
        launch();activate("搜索范围");assertTrue(device.wait(Until.hasObject(By.text("选择搜索网站")),4000));activate("添加网站");
        assertTrue(device.wait(Until.hasObject(By.text("添加到搜索范围")),3000));UiObject2 input=device.findObject(By.desc("网站网址"));assertNotNull(input);input.setText(second.url("/"));activate("添加并选中");
        assertTrue(device.wait(Until.hasObject(By.text("已添加并选入搜索范围")),3000));
        org.json.JSONArray scope=new org.json.JSONArray(app.settings.getString("filmSearchScope","[]"));assertEquals(2,scope.length());assertEquals(second.url("/"),scope.getString(1));assertEquals(2,app.store.websites().size());
        device.pressBack();launch();activate("搜索范围");assertTrue(device.wait(Until.hasObject(By.text("选择搜索网站")),4000));assertEquals(2,device.findObjects(By.checkable(true).checked(true)).size());
    }
    @Test public void emptySavedScopeDoesNotSilentlySearchDefaultWebsites()throws Exception {
        app.settings.edit().putString("filmSearchScope","[]").commit();launch();type("海风");activate("一键搜索");
        assertTrue(device.wait(Until.hasObject(By.text("请先选择搜索网站")),3000));assertTrue(first.requests.isEmpty());assertTrue(second.requests.isEmpty());
    }
    @Test public void cancellingWebsiteAdditionPreservesExistingResults()throws Exception {
        Assume.assumeTrue(provider());launch();type("海风");activate("一键搜索");assertTrue(device.wait(Until.hasObject(By.desc("搜索影片：测试动漫海风")),12000));
        activate("搜索范围");activate("添加网站");assertTrue(device.wait(Until.hasObject(By.text("添加到搜索范围")),3000));activate("取消");
        assertTrue("Cancelling an addition must retain the previous native search results",device.wait(Until.hasObject(By.desc("搜索影片：测试动漫海风")),1500));
    }
    @Test public void progressiveResultsSurviveOpeningPreviewBeforeSiteFinishes()throws Exception {
        Assume.assumeTrue(provider());first.dynamic=true;launch();type("海风");activate("一键搜索");assertTrue(device.wait(Until.hasObject(By.desc("搜索影片：测试动漫海风")),12000));
        device.pressDPadDown();device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("影视预览")),4000));device.pressBack();
        assertTrue("An in-progress search must keep delivered cards on preview return",device.wait(Until.hasObject(By.desc("搜索影片：测试动漫海风")),500));
        assertEquals("搜索影片：测试动漫海风",device.findObject(By.focused(true)).getContentDescription());
    }
    @Test public void lateDescriptionAndPosterPolicyReachThePreview()throws Exception {
        Assume.assumeTrue(provider());first.lateMetadata=true;launch();type("海风");activate("一键搜索");assertTrue(device.wait(Until.hasObject(By.textContains("搜索完成")),12000));
        device.pressDPadDown();device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("影视预览")),4000));
        assertTrue("Progressive description must replace the earlier blank/old description",device.hasObject(By.text("迟到的影片简介")));assertEquals("no-referrer",await().getIntent().getStringExtra("filmPolicy"));
    }
    @Test public void readmeMultiSiteSearchPreviewAndFavoritesExamples()throws Exception {
        Assume.assumeTrue(provider());second.close();second=new Server(false);second.start();app.store.addWebsite(second.url("/"));
        first.demoTitle="海风日记 · 动漫示例";second.demoTitle="海风来信 · 电影示例";second.accent=0xffb38969;
        android.content.ContentValues a=new android.content.ContentValues();a.put("name","演示平台 A");app.store.getWritableDatabase().update("websites",a,"url=?",new String[]{first.url("/")});
        a.put("name","演示平台 B");app.store.getWritableDatabase().update("websites",a,"url=?",new String[]{second.url("/")});
        launch();type("海风");activate("一键搜索");assertTrue(device.wait(Until.hasObject(By.textContains("搜索完成")),15000));
        assertTrue(device.hasObject(By.desc("搜索影片："+first.demoTitle)));assertTrue(device.hasObject(By.desc("搜索影片："+second.demoTitle)));
        SystemClock.sleep(600);File dir=new File(app.getExternalFilesDir(null),"sourcefree-013");dir.mkdirs();device.takeScreenshot(new File(dir,"search.png"));
        device.pressDPadDown();assertEquals("搜索影片："+first.demoTitle,device.findObject(By.focused(true)).getContentDescription());device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("影视预览")),5000));SystemClock.sleep(500);device.takeScreenshot(new File(dir,"preview.png"));
        device.pressDPadRight();device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("取消收藏")),3000));device.pressBack();
        activate("我的收藏");assertTrue(device.wait(Until.hasObject(By.desc("收藏影片："+first.demoTitle)),5000));SystemClock.sleep(500);device.takeScreenshot(new File(dir,"favorites.png"));
    }
    private boolean provider(){return Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null;}
    private void launch()throws Exception {app.startActivity(new Intent().setClassName(app,"tv.clearstream.FilmSearchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));assertTrue(device.wait(Until.hasObject(By.text("一键搜片")),8000));await();}
    private void type(String value){UiObject2 input=device.findObject(By.desc("搜索片名"));assertNotNull(input);input.setText(value);}
    private void activate(String text)throws Exception {
        Activity activity=await();boolean[] focused={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{View view=find(activity.getWindow().getDecorView(),text);focused[0]=view!=null&&view.requestFocus();});
        if(!focused[0]){android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(node.getText()==null?"":node.getText())&&node.isFocusable())focused[0]|=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);}
        assertTrue("Focusable remote control: "+text,focused[0]);device.pressDPadCenter();device.waitForIdle();
    }
    private static View find(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText())&&root.isFocusable())return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private Activity await()throws Exception {long until=SystemClock.elapsedRealtime()+6000;Activity[] found={null};while(found[0]==null&&SystemClock.elapsedRealtime()<until){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))found[0]=a;});if(found[0]==null)SystemClock.sleep(50);}assertNotNull("Foreground app activity",found[0]);return found[0];}
    private void finishOwned()throws Exception {InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Set<Activity> all=new HashSet<>();for(Stage stage:Stage.values())all.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));for(Activity a:all)if(!a.isFinishing())a.finish();});InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(250);}
    private static final class Server implements AutoCloseable {
        final ServerSocket listener;final ExecutorService pool=Executors.newCachedThreadPool();final List<String> requests=Collections.synchronizedList(new ArrayList<>());final boolean fail;volatile boolean closed,dynamic,lateMetadata;volatile String demoTitle="测试动漫海风";volatile int accent=0xff4d8290;
        Server(boolean fail)throws IOException{this.fail=fail;listener=new ServerSocket(0,12,InetAddress.getByName("127.0.0.1"));}
        String url(String path){return "http://127.0.0.1:"+listener.getLocalPort()+path;}
        void start(){pool.execute(()->{while(!closed)try{Socket client=listener.accept();pool.execute(()->serve(client));}catch(IOException ignored){}});}
        void serve(Socket client){try(Socket socket=client){socket.setSoTimeout(2000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line=reader.readLine();if(line==null)return;String path=line.split(" ")[1];requests.add(path);while((line=reader.readLine())!=null&&!line.isEmpty()){}
            int code=path.startsWith("/search")&&fail?503:200;String html=path.startsWith("/search")?"<article><a href='/movie/1'><img src='/poster.png' alt='测试动漫海风' width=160 height=240></a><p>搜索结果简介</p></article>":"<form action='/search' method=get><input type=hidden name=type value=all><input name=wd placeholder='搜索'></form><article><a href='/movie/home'><img src='/poster.png' alt='首页推荐不能算结果' width=160 height=240></a></article>";
            html=html.replace("测试动漫海风",demoTitle);
            if(dynamic&&path.startsWith("/search"))html+="<script>var n=0;setInterval(function(){if(++n<24){var a=document.createElement('article');a.innerHTML='<a href=\"/movie/extra'+n+'\"><img src=\"/poster.png\" alt=\"海风新结果'+n+'\" width=160 height=240></a>';document.body.appendChild(a)}},350)</script>";
            if(lateMetadata&&path.startsWith("/search"))html+="<script>setTimeout(function(){document.querySelector('p').textContent='迟到的影片简介';document.querySelector('img').setAttribute('referrerpolicy','no-referrer')},350)</script>";
            byte[] body=html.getBytes(StandardCharsets.UTF_8);String mime="text/html; charset=utf-8";
            if(path.startsWith("/poster.png")){
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(320,480,android.graphics.Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);android.graphics.Paint paint=new android.graphics.Paint(3);
                paint.setShader(new android.graphics.LinearGradient(0,0,320,480,new int[]{accent,0xff162534},null,android.graphics.Shader.TileMode.CLAMP));canvas.drawRect(0,0,320,480,paint);paint.setShader(null);
                paint.setColor(0x446ad4d0);canvas.drawCircle(260,80,130,paint);paint.setColor(0x66ffffff);canvas.drawCircle(250,100,37,paint);
                paint.setColor(0xff142b3c);android.graphics.Path shape=new android.graphics.Path();shape.moveTo(0,330);shape.lineTo(100,240);shape.lineTo(190,350);shape.lineTo(260,280);shape.lineTo(320,350);shape.lineTo(320,480);shape.lineTo(0,480);shape.close();canvas.drawPath(shape,paint);
                paint.setColor(0xfff5f1e6);paint.setTextSize(60);canvas.drawText("海风",30,200,paint);paint.setTextSize(18);canvas.drawText("本地演示 / DEMO",32,438,paint);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();body=bytes.toByteArray();mime="image/png";
            }
            OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" "+(code==200?"OK":"Unavailable")+"\r\nContent-Type: "+mime+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
        }catch(IOException ignored){}}
        @Override public void close()throws IOException{closed=true;listener.close();pool.shutdownNow();}
    }
}
