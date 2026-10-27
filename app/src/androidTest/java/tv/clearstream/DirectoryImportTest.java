package tv.clearstream;

import android.content.*;
import android.os.*;
import android.view.View;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DirectoryImportTest {
    @Test public void parsedDirectoryRejectsUnsafeAndSelfLinksAndDeduplicatesEquivalentRoots() throws Exception {
        JSONArray cards=new JSONArray();
        cards.put(item("示例影院","https://example.org/","在线影院"));cards.put(item("重复根网址","https://example.org","在线影院"));
        cards.put(item("坏协议","javascript:alert(1)","在线影院"));cards.put(item("站内页面","https://www.bmmyy.com/about","常用推荐"));
        cards.put(item("带账号","https://user:password@example.net","在线影院"));cards.put(item("推广","https://advert.invalid/","友情链接"));
        cards.put(item("直接安装包","https://download.example.org/player.apk?download=1","在线影院"));
        cards.put(item("央视直播","https://tv.cctv.com/live/","卫视直播"));
        List<DirectoryActivity.Entry> result=DirectoryActivity.parseEntries(new JSONObject().put("items",cards).toString(),"https://www.bmmyy.com/");
        assertEquals(2,result.size());assertEquals("示例影院",result.get(0).name);assertEquals("卫视直播",result.get(1).category);
        char[] oversized=new char[193*1024];Arrays.fill(oversized,' ');try{DirectoryActivity.parseEntries(new String(oversized),"https://www.bmmyy.com/");fail("Oversize directory must be rejected");}catch(JSONException expected){}
    }
    private static JSONObject item(String name,String url,String category)throws JSONException{return new JSONObject().put("name",name).put("url",url).put("category",category).put("summary","公开网站入口");}

    /** Reduced from i8k's public anchor/h3/p/printed-URL cards; no site scripts are executed. */
    private static String modernDirectoryHtml(int port){
        StringBuilder html=new StringBuilder("<html><head><title>示例影视网站导航</title></head><body><header><a href='https://outside.invalid/movie/'><h3>顶部非卡片</h3></a></header><h1>影视网站导航</h1><div class='grid gap-6 sm:grid-cols-2 lg:grid-cols-3'>");
        String[] names={"示例电影站","示例动漫站","示例直播站"};
        for(int i=0;i<names.length;i++){String url="http://127.0.0."+(i+2)+":"+port+(i==0?"/movie/":"/website/"+i);html.append("<a href='").append(url).append("' target='_blank' rel='noopener noreferrer' class='group relative block rounded-xl border p-5'><div class='absolute pointer-events-none'><img alt='decoration' src='/girls/girl_16.webp' width='128' height='200'></div><div class='flex'><img alt='").append(names[i]).append(" logo' src='/favicon.ico' width='32' height='32'><h3>").append(names[i]).append("</h3><p>网站公开简介，不是某部影片</p></div><div class='mt-3 text-xs truncate'>").append(url).append("</div></a>");}
        return html.append("</div><aside style='position:fixed;right:0;bottom:0'><a href='https://decor.invalid/movie/'><img alt='decoration' src='/girls/girl_1.webp' width='40' height='40'></a></aside></body></html>").toString();
    }
    @Test public void modernNavigationCardsReadWebsiteNamesAndAllowRemoteAdding() throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));Assume.assumeTrue(Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();ClearApp app=(ClearApp)target.getApplicationContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());Assume.assumeTrue(app.store.websites().size()<100);
        try(Server fixture=new Server()){
            fixture.body=modernDirectoryHtml(fixture.port);String added="http://127.0.0.2:"+fixture.port+"/movie/";
            try{launch(target,fixture.url());assertTrue("A modern navigation grid must be read as multiple websites",device.wait(Until.hasObject(By.desc("导航网站：示例电影站")),12000));assertTrue(device.hasObject(By.desc("导航网站：示例动漫站")));assertFalse(device.hasObject(By.desc("导航网站：decoration")));assertFalse(device.hasObject(By.text("影视预览")));
                focus("导航网站：示例电影站");device.pressDPadRight();assertTrue(device.hasObject(By.desc("添加网站：示例电影站").focused(true)));device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("已添加")),5000));assertEquals(1,count(app.store,added));device.pressDPadCenter();assertEquals(1,count(app.store,added));
                File evidence=new File(target.getExternalFilesDir(null),"evidence");evidence.mkdirs();device.takeScreenshot(new File(evidence,"i8k-style-directory-add.png"));
            }finally{device.pressBack();for(Website site:app.store.websites())if(site.url.equals(added))app.store.deleteWebsite(site);}
        }
    }
    @Test public void unlistedSavedNavigationIsDetectedInsteadOfOpeningDecorationAsFilm() throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));Assume.assumeTrue(Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();ClearApp app=(ClearApp)target.getApplicationContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());Assume.assumeTrue(app.store.websites().size()<100);
        try(Server fixture=new Server()){
            fixture.body=modernDirectoryHtml(fixture.port);String directory=fixture.url();app.store.addWebsite(directory);
            try{target.startActivity(WebsitePlayback.portalIntent(target,directory,"示例影视网站导航").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                assertTrue("A saved directory on an unknown host must enter the native website list through DOM detection",device.wait(Until.hasObject(By.text("发现影视网站")),15000));assertTrue(device.wait(Until.hasObject(By.desc("导航网站：示例电影站")),12000));assertFalse(device.hasObject(By.desc("网站内容：decoration")));assertFalse(device.hasObject(By.text("影视预览")));
            }finally{device.pressBack();for(Website site:app.store.websites())if(site.url.equals(directory))app.store.deleteWebsite(site);}
        }
    }
    @Test public void nativeNavigationDoesNotWaitForUnfinishedDecorativeImage()throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));Assume.assumeTrue(Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());File evidence=new File(target.getExternalFilesDir(null),"evidence");evidence.mkdirs();
        try(Server fixture=new Server();DelayedImage image=new DelayedImage()){
            fixture.body=modernDirectoryHtml(fixture.port).replace("</body>","<img src='"+image.url()+"' alt='decoration' width='1' height='1'></body>");
            try{
                target.startActivity(WebsitePlayback.portalIntent(target,fixture.url(),"未完成图片的导航").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                assertTrue("The document must request its delayed image before the response is released",image.requested.await(5,TimeUnit.SECONDS));assertEquals(1L,image.release.getCount());
                assertTrue("Navigation metadata already in the DOM must enter the native directory without waiting for decorative images",device.wait(Until.hasObject(By.text("发现影视网站")),6000));
                assertEquals("The image has intentionally not completed",1L,image.release.getCount());assertTrue(device.wait(Until.hasObject(By.desc("导航网站：示例电影站")),6000));assertFalse(device.hasObject(By.desc("网站内容：decoration")));
            }finally{device.takeScreenshot(new File(evidence,"navigation-before-decoration-finishes.png"));device.pressBack();image.release.countDown();}
        }
    }

    @Test public void readmeDirectoryExample()throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Assume.assumeTrue(Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
        try(Server fixture=new Server()){
            fixture.body="<html><head><title>本地影视导航示例</title></head><body><div class='sites-list'><h4>影视网站</h4><div class='row'><div class='xe-card'><div class='box2' data-url='https://cinema-a.example/'><strong>示例影院 A</strong><p>电影与电视剧入口</p></div></div><div class='xe-card'><div class='box2' data-url='https://animation-b.example/'><strong>示例动漫站 B</strong><p>动漫分类入口</p></div></div></div><h4>源集合</h4><div class='row'><div class='xe-card'><div class='box2' data-url='https://collection-c.example/'><strong>示例源集合 C</strong><p>用户自行提供的集合页</p></div></div></div></div></body></html>";
            launch(target,fixture.url());assertTrue(device.wait(Until.hasObject(By.desc("导航网站：示例影院 A")),12000));focus("导航网站：示例影院 A");
            File dir=new File(target.getExternalFilesDir(null),"sourcefree-013");dir.mkdirs();device.takeScreenshot(new File(dir,"directory.png"));device.pressBack();
        }
    }
    @Test public void remoteCanReadDirectoryAddOneWebsiteAndFindItAfterReopening() throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        Assume.assumeTrue("A system page reader is required",Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();ClearApp app=(ClearApp)target.getApplicationContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try(Server fixture=new Server()) {
            String site="https://directory-fixture.invalid/movie-"+fixture.port;fixture.body="<html><head><title>导航夹具</title></head><body><div class='sites-list'><h4>在线影院</h4><div class='row'><div class='xe-card'><div class='box2' data-url='"+site+"' onclick=\"document.title='不应执行onclick'\"><strong>遥控器测试影院</strong><p>清单中的示例网站</p></div></div><div class='xe-card'><div class='box2' data-url='javascript:alert(1)'><strong>不安全入口</strong></div></div></div><h4>卫视直播</h4><div class='row'><div class='xe-card'><div class='box2' data-url='https://tv.cctv.com/live/'><strong>央视直播</strong></div></div></div></div><script>document.querySelector('.sites-list').remove();</script></body></html>";
            try {
                launch(target,fixture.url());assertTrue("Directory cards should render without running inline page scripts",device.wait(Until.hasObject(By.desc("导航网站：遥控器测试影院")),12000));
                assertFalse(device.hasObject(By.desc("导航网站：不安全入口")));
                focus("导航网站：遥控器测试影院");device.pressDPadRight();assertTrue("Right moves to add action",device.hasObject(By.desc("添加网站：遥控器测试影院").focused(true)));device.pressDPadCenter();
                assertTrue(device.wait(Until.hasObject(By.text("已添加")),5000));assertEquals(1,count(app.store,site));device.pressDPadCenter();assertEquals("A second remote confirmation cannot duplicate the entry",1,count(app.store,site));
                SourceStore reopened=new SourceStore(target);try{assertEquals(1,count(reopened,site));for(Website saved:reopened.websites())if(saved.url.equals(site))assertEquals("遥控器测试影院",saved.name);}finally{reopened.close();}
                File evidence=new File(target.getExternalFilesDir(null),"evidence");evidence.mkdirs();device.takeScreenshot(new File(evidence,"46-directory-import.png"));
                device.pressBack();launch(target,fixture.url());assertTrue(device.wait(Until.hasObject(By.text("已添加")),12000));assertEquals(1,count(app.store,site));
                UiObject2 input=device.findObject(By.clazz("android.widget.EditText"));assertNotNull(input);input.setText("央视");assertTrue(device.wait(Until.hasObject(By.desc("导航网站：央视直播")),3000));assertFalse(device.hasObject(By.desc("导航网站：遥控器测试影院")));
            }finally{device.pressBack();for(Website saved:app.store.websites())if(saved.url.equals(site))app.store.deleteWebsite(saved);}
        }
    }
    @Test public void addedDirectoryWebsiteAppearsImmediatelyWhenReturningToMainWebsitePage() throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        Assume.assumeTrue("A system page reader is required",Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();ClearApp app=(ClearApp)target.getApplicationContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Assume.assumeTrue("There must be room for one fixture website without deleting saved sites",app.store.websites().size()<100);
        try(Server fixture=new Server()) {
            String site="https://directory-return.invalid/movie-"+fixture.port,name="导航新增影院-"+fixture.port;
            fixture.body="<html><head><title>返回网站列表夹具</title></head><body><div class='sites-list'><h4>在线影院</h4><div class='row'><div class='xe-card'><div class='box2' data-url='"+site+"'><strong>"+name+"</strong><p>添加后返回首页应立即可见</p></div></div></div></div></body></html>";
            File evidence=new File(target.getExternalFilesDir(null),"evidence");evidence.mkdirs();
            try {
                target.startActivity(Intent.makeMainActivity(new ComponentName(target,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                assertTrue(device.wait(Until.hasObject(By.text("影视网站")),10000));
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(activity instanceof MainActivity){ArrayList<View> matches=new ArrayList<>();activity.getWindow().getDecorView().findViewsWithText(matches,"影视网站",View.FIND_VIEWS_WITH_TEXT);boolean focused=false;for(View view:matches)if(view.isFocusable()){focused=view.requestFocus();break;}assertTrue("Main website navigation must be focusable",focused);}});
                device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("选择网站 · 搜索影片 · 按确定播放")),5000));assertFalse(mainWebsiteCardPresent(name));
                // Keep MainActivity's existing website page underneath this directory Activity.
                target.startActivity(new Intent(target,DirectoryActivity.class).putExtra("url",fixture.url()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                assertTrue(device.wait(Until.hasObject(By.desc("导航网站："+name)),12000));focus("导航网站："+name);device.pressDPadRight();assertTrue(device.hasObject(By.desc("添加网站："+name).focused(true)));device.pressDPadCenter();
                assertTrue(device.wait(Until.hasObject(By.text("已添加")),5000));assertEquals(1,count(app.store,site));
                device.pressBack();assertTrue("Back should return to the existing Main website page",device.wait(Until.hasObject(By.text("选择网站 · 搜索影片 · 按确定播放")),5000));
                boolean rendered=false;long until=SystemClock.elapsedRealtime()+5000;
                while(!rendered&&SystemClock.elapsedRealtime()<until){rendered=mainWebsiteCardPresent(name);if(!rendered)SystemClock.sleep(100);}
                assertTrue("The newly added website must be rendered immediately, without pressing website navigation again",rendered);
                boolean focused=false;until=SystemClock.elapsedRealtime()+3000;
                while(!focused&&SystemClock.elapsedRealtime()<until){focused=mainWebsiteCardFocused(name);if(!focused)SystemClock.sleep(100);}
                assertTrue("Returning should focus the newly added website so one confirmation opens it",focused);
            }finally{device.takeScreenshot(new File(evidence,"52-directory-return-main.png"));for(Website saved:app.store.websites())if(saved.url.equals(site))app.store.deleteWebsite(saved);}
        }
    }
    private static boolean mainWebsiteCardPresent(String name){final boolean[] present={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(activity instanceof MainActivity){ArrayList<View> found=new ArrayList<>();activity.getWindow().getDecorView().findViewsWithText(found,name,View.FIND_VIEWS_WITH_TEXT);present[0]=!found.isEmpty();}});return present[0];}
    private static boolean mainWebsiteCardFocused(String name){final boolean[] focused={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(activity instanceof MainActivity){ArrayList<View> found=new ArrayList<>();activity.getWindow().getDecorView().findViewsWithText(found,name,View.FIND_VIEWS_WITH_TEXT);for(View view:found){View candidate=view;while(candidate!=null){if(candidate.isFocused()){focused[0]=true;break;}candidate=candidate.getParent() instanceof View?(View)candidate.getParent():null;}}}});return focused[0];}
    private static int count(SourceStore store,String url){int count=0;for(Website site:store.websites())if(site.url.equals(url))count++;return count;}
    private static void launch(Context app,String url){app.startActivity(new Intent(app,DirectoryActivity.class).putExtra("url",url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));}
    private static void focus(String description){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(activity instanceof DirectoryActivity){ArrayList<View> found=new ArrayList<>();activity.getWindow().getDecorView().findViewsWithText(found,description,View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);assertEquals(1,found.size());assertTrue(found.get(0).requestFocus());}});}
    private static final class DelayedImage implements AutoCloseable {
        final ServerSocket socket;final CountDownLatch requested=new CountDownLatch(1),release=new CountDownLatch(1);final Thread worker;
        DelayedImage()throws IOException{socket=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));worker=new Thread(()->{try(Socket client=socket.accept()){client.setSoTimeout(3000);BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=in.readLine())!=null&&!line.isEmpty()){}requested.countDown();release.await(15,TimeUnit.SECONDS);byte[] bytes={0};OutputStream out=client.getOutputStream();out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 1\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();}catch(IOException ignored){}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}},"delayed-navigation-image");worker.start();}
        String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/decoration.png";}
        @Override public void close()throws IOException{release.countDown();socket.close();try{worker.join(1500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    }
    private static final class Server implements AutoCloseable {
        final ServerSocket socket;final int port;final Thread worker;volatile boolean running=true;volatile String body="";
        Server()throws IOException{socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));port=socket.getLocalPort();worker=new Thread(()->{while(running)try(Socket client=socket.accept()){client.setSoTimeout(4000);BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=in.readLine())!=null&&!line.isEmpty()){}byte[] html=body.getBytes(StandardCharsets.UTF_8);OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "+html.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(html);out.flush();}catch(IOException ignored){}} ,"directory-fixture");worker.start();}
        String url(){return "http://127.0.0.1:"+port+"/directory";}
        @Override public void close()throws IOException{running=false;socket.close();try{worker.join(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    }
}
