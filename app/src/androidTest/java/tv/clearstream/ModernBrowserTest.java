package tv.clearstream;

import android.app.Activity;
import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Controlled media is separate from the opt-in public-site playback audit. */
@RunWith(AndroidJUnit4.class)
public final class ModernBrowserTest {
    @Test public void remoteDownLeavesWebsiteSearchAndReachesTheNextControl()throws Exception{
        launch("/input-trap");Activity activity=awaitActivity();awaitState(activity,value->value.optJSONObject("navigation")!=null,15000);device.pressDPadDown();
        assertEquals("继续选片",awaitState(activity,value->value.optJSONObject("navigation")!=null&&"继续选片".equals(value.optJSONObject("navigation").optString("focus")),4000).getJSONObject("navigation").getString("focus"));
    }
    @Test public void offscreenEpisodeCanBeReachedByThePlayCommand()throws Exception{
        launch("/offscreen");Activity activity=awaitActivity();awaitState(activity,value->value.has("extensionVersion"),15000);activatePlay();
        assertTrue(awaitState(activity,value->value.optBoolean("video"),10000).optBoolean("video"));
    }
    @Test public void commonEpisodeLinkStartsTheSelectedSitesPlaybackPage()throws Exception{
        launch("/maccms");Activity activity=awaitActivity();awaitState(activity,value->value.has("extensionVersion"),15000);activatePlay();
        assertTrue("The play command must enter the visible first episode rather than report no video",awaitState(activity,value->value.optBoolean("video"),10000).optBoolean("video"));
    }
    @Test public void bannerVideoIsSkippedWhenTheFilmPlayerExists()throws Exception{
        launch("/banner");Activity activity=awaitActivity();JSONObject state=awaitState(activity,value->value.optInt("width")>0,15000);
        assertTrue("Playback controls must target the main film, not the advertising strip",state.optDouble("cy")>.3);
    }
    @Test public void cancelledPageStopDoesNotBecomeAWebsiteFailure()throws Exception{
        launch("/page");Activity activity=awaitActivity();String root=activity.getIntent().getStringExtra("url");
        Object session=activity.getClass().getField("session").get(activity);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{Object progress=session.getClass().getMethod("getProgressDelegate").invoke(session);java.lang.reflect.Method method=progress.getClass().getMethod("onPageStop",session.getClass(),boolean.class);method.setAccessible(true);method.invoke(progress,session,false);}catch(Exception error){throw new AssertionError(error);}});
        try(WebsiteHealth health=new WebsiteHealth(app,new Handler(Looper.getMainLooper()))){assertEquals("Cancelling a load is not a real load error",0,health.status(root).playbackIssueTimestamp);}
    }
    @Test public void explicitUserPauseWhileBufferingCancelsTheDeadline()throws Exception{
        launch("/page");Activity activity=awaitActivity();String root=activity.getIntent().getStringExtra("url");awaitState(activity,value->value.has("extensionVersion"),15000);
        final WebsitePlaybackMonitor[] monitor={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Field field=activity.getClass().getDeclaredField("playbackMonitor");field.setAccessible(true);monitor[0]=(WebsitePlaybackMonitor)field.get(activity);monitor[0].request(root);}catch(Exception error){throw new AssertionError(error);}});
        deliverState(activity,new JSONObject().put("kind","userPaused"));
        java.lang.reflect.Field armed=WebsitePlaybackMonitor.class.getDeclaredField("armed");armed.setAccessible(true);assertFalse("A user pause while buffering must cancel the pending deadline",armed.getBoolean(monitor[0]));
    }
    private Context app;private UiDevice device;private Server server;
    @Before public void prepare() throws Exception {
        Assume.assumeTrue("Modern engine is standard-only and requires a supported ABI",WebsitePlayback.modernAvailable());
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        server=new Server();server.start();
    }
    @After public void cleanup() throws Exception {if(device!=null){File dir=new File(app.getExternalFilesDir(null),"evidence");dir.mkdirs();device.takeScreenshot(new File(dir,"modern-controlled-last.png"));device.dumpWindowHierarchy(new File(dir,"modern-controlled-last.xml"));device.pressBack();device.pressHome();}if(server!=null)server.close();}
    @Test public void modernJavascriptAndRemoteLinkWorkAndBackReturns() throws Exception {
        launch("/page");assertTrue(device.wait(Until.hasObject(By.text("现代网页已就绪")),20000));
        device.pressDPadCenter();assertTrue("Genuine remote click must follow the link",device.wait(Until.hasObject(By.text("遥控进入详情")),10000));
        device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("现代网页已就绪")),10000));
    }
    @Test public void queuedExtensionCallbacksAfterPortCloseAreSafelyIgnored() throws Exception {
        launch("/page");Activity activity=awaitActivity();awaitState(activity,s->s.has("extensionVersion"),20000);
        final Throwable[] failure={null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{
            java.lang.reflect.Field field=activity.getClass().getDeclaredField("port");field.setAccessible(true);Object port=field.get(activity);assertNotNull(port);
            java.lang.reflect.Method drop=activity.getClass().getDeclaredMethod("dropPort");drop.setAccessible(true);drop.invoke(activity);
            assertNull(field.get(activity));
            Class<?> bundleClass=Class.forName("org.mozilla.gecko.util.GeckoBundle");Object bundle=bundleClass.getConstructor().newInstance();bundleClass.getMethod("putString",String.class,String.class).invoke(bundle,"data","queued-message");
            for(java.lang.reflect.Method method:port.getClass().getDeclaredMethods())if(method.getName().equals("portMessage")){method.setAccessible(true);method.invoke(port,bundle,null);}
        }catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("A callback already queued before close must be harmless",failure[0]);
        launch("/page");assertTrue(device.wait(Until.hasObject(By.text("现代网页已就绪")),15000));
    }
    @Test public void nestedIframeFullscreenFillsScreenAndBackRestoresPage()throws Exception {
        launch("/embedded-hd");Activity activity=awaitActivity();awaitState(activity,v->v.optInt("frames")>0&&v.has("extensionVersion"),20000);
        device.pressMenu();device.pressDPadRight();device.pressDPadRight();device.pressDPadRight();assertEquals("全屏",device.findObject(By.focused(true)).getText());device.pressDPadCenter();
        JSONObject first=awaitState(activity,v->v.optBoolean("layoutFullscreen")&&v.optInt("layoutWidth")>=1278&&v.optInt("layoutHeight")>=718&&v.optInt("layoutInnerWidth")>=1278&&v.optInt("layoutInnerHeight")>=718&&v.optInt("layoutMediaWidth")==1920&&v.optInt("layoutMediaHeight")==1080,15000);
        device.pressDPadCenter();double time=first.optDouble("layoutMediaTime");JSONObject advanced=awaitState(activity,v->v.optDouble("layoutMediaTime")>time+2,8000);
        assertFalse(device.hasObject(By.text("电视适配")));File dir=new File(app.getExternalFilesDir(null),"sourcefree-013");dir.mkdirs();device.takeScreenshot(new File(dir,"fullscreen-embedded.png"));
        try(FileOutputStream out=new FileOutputStream(new File(dir,"fullscreen-embedded.json"))){out.write(advanced.toString(2).getBytes(StandardCharsets.UTF_8));}
        device.pressBack();awaitState(activity,v->!v.optBoolean("layoutFullscreen"),5000);assertTrue(device.wait(Until.hasObject(By.text("电视适配")),5000));
    }
    @Test public void videoDecodesAdvancesPausesAndStopsWhenLeaving() throws Exception {
        launch("/video");Activity activity=awaitActivity();
        awaitState(activity,s->s.optInt("width")==320&&s.optInt("ready")>=2,20000);
        activatePlay();JSONObject first=awaitState(activity,s->!s.optBoolean("paused",true)&&s.optDouble("time")>=2,12000);
        assertEquals("trusted-play",first.optString("playbackStatus"));
        SystemClock.sleep(5000);JSONObject second=state(activity);
        assertEquals(320,second.optInt("width"));assertEquals(180,second.optInt("height"));assertTrue(second.optDouble("time")>first.optDouble("time")+2);
        device.pressMenu();device.pressDPadRight();device.pressDPadRight();device.pressDPadRight();assertEquals("全屏",device.findObject(By.focused(true)).getText());device.pressDPadCenter();
        assertEquals("trusted-click",awaitState(activity,s->s.optBoolean("fullscreen"),7000).optString("fullscreenStatus"));
        File dir=new File(app.getExternalFilesDir(null),"evidence");dir.mkdirs();device.takeScreenshot(new File(dir,"50-modern-controlled-video.png"));
        try(FileOutputStream out=new FileOutputStream(new File(dir,"modern-controlled-video.json"))){out.write(second.toString(2).getBytes(StandardCharsets.UTF_8));}
        device.pressBack();awaitState(activity,s->!s.optBoolean("fullscreen"),7000);
        device.pressMenu();for(int i=0;i<7;i++)device.pressDPadRight();assertEquals("视频画质",device.findObject(By.focused(true)).getContentDescription());device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.textContains("当前视频：320×180")),4000));device.pressBack();
        activatePlay();awaitState(activity,s->s.optBoolean("paused"),7000);
        activatePlay();awaitState(activity,s->!s.optBoolean("paused",true),7000);
        device.pressHome();long until=SystemClock.elapsedRealtime()+8000;boolean paused=false;
        while(!paused&&SystemClock.elapsedRealtime()<until){final boolean[] result={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{result[0]=activity.getClass().getField("backgroundPauseConfirmed").getBoolean(activity);}catch(Exception e){throw new AssertionError(e);}});paused=result[0];if(!paused)SystemClock.sleep(100);}
        assertTrue("Leaving must receive the native media pause callback",paused);
    }
    @Test public void transientMediaErrorHintClearsAfterThePageRecovers() throws Exception {
        launch("/page");Activity activity=awaitActivity();awaitState(activity,s->s.has("extensionVersion"),20000);
        deliverState(activity,new JSONObject().put("kind","state").put("video",true).put("error",3));
        assertTrue(device.wait(Until.hasObject(By.textContains("这条视频线路暂时无法播放")),3000));
        deliverState(activity,new JSONObject().put("kind","state").put("video",true).put("error",0).put("ready",4).put("width",320).put("height",180));
        assertTrue("A recovered player must not keep telling seniors the video cannot play",device.wait(Until.gone(By.textContains("这条视频线路暂时无法播放")),3000));
    }
    private void deliverState(Activity activity,JSONObject state) throws Exception {
        final Throwable[] failure={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{
            java.lang.reflect.Field delegate=activity.getClass().getDeclaredField("portDelegate"),port=activity.getClass().getDeclaredField("port");delegate.setAccessible(true);port.setAccessible(true);Object receiver=delegate.get(activity),actualPort=port.get(activity);assertNotNull(actualPort);
            java.lang.reflect.Method message=receiver.getClass().getMethod("onPortMessage",Object.class,actualPort.getClass());message.setAccessible(true);message.invoke(receiver,state,actualPort);
        }catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private void launch(String path){app.startActivity(WebsitePlayback.intent(app,"http://127.0.0.1:"+server.socket.getLocalPort()+path,"现代内核回归").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));}
    private void activatePlay(){device.pressMenu();device.pressDPadRight();UiObject2 focused=device.findObject(By.focused(true));assertNotNull(focused);assertEquals("播放 / 暂停",focused.getText());device.pressDPadCenter();}
    private Activity awaitActivity() throws Exception {long until=SystemClock.elapsedRealtime()+12000;Activity[] found={null};while(found[0]==null&&SystemClock.elapsedRealtime()<until){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a.getClass().getName().equals("tv.clearstream.ModernWebActivity"))found[0]=a;});if(found[0]==null)SystemClock.sleep(100);}assertNotNull("Modern website activity must open",found[0]);return found[0];}
    private interface Match {boolean accepts(JSONObject value);}
    private JSONObject awaitState(Activity a,Match match,long timeout) throws Exception {JSONObject last=new JSONObject();long until=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<until){last=state(a);if(match.accepts(last))return last;SystemClock.sleep(100);}throw new AssertionError("Player state did not arrive: "+last);}
    private JSONObject state(Activity a) throws Exception {String[] value={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{value[0]=a.getClass().getField("auditState").get(a).toString();}catch(Exception e){throw new AssertionError(e);}});return new JSONObject(value[0]);}
    static final class Server implements AutoCloseable {
        final ServerSocket socket;final ExecutorService workers=Executors.newCachedThreadPool();volatile boolean closed;
        Server() throws IOException {socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));}
        void start(){workers.execute(()->{while(!closed)try{Socket client=socket.accept();workers.execute(()->serve(client));}catch(IOException e){if(!closed)throw new RuntimeException(e);}});}
        void serve(Socket client){try(Socket c=client){c.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.US_ASCII));String first=in.readLine();if(first==null)return;String path=first.split(" ")[1],line,range=null;int size=0;while((line=in.readLine())!=null&&!line.isEmpty()){if((size+=line.length())>8192)return;if(line.toLowerCase(java.util.Locale.ROOT).startsWith("range:"))range=line.substring(6).trim();}
            byte[] body;String type;
            if(path.equals("/clip.mp4")||path.equals("/hd.mp4")){try(InputStream asset=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(path.equals("/hd.mp4")?"fixture/hd.mp4":"fixture/website.mp4")){ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=asset.read(b))!=-1)data.write(b,0,n);body=data.toByteArray();}type="video/mp4";}
            else {String html=path.equals("/embedded-hd")?"<h1>原生1080p全屏验证</h1><div style='width:600px;transform:translateX(40px);overflow:hidden'><iframe src='http://localhost:"+socket.getLocalPort()+"/inner-hd' style='width:480px;height:270px;border:0'></iframe></div>":path.equals("/inner-hd")?"<video autoplay muted loop controls style='width:320px;height:180px' src='/hd.mp4'></video>":path.equals("/adfilters")?"<div class='adsbygoogle'>自动屏蔽测试广告</div><p>普通网页内容仍保留</p><script>setTimeout(function(){var i=new Image();i.src='https://ad.doubleclick.net/clearstream-test-pixel.gif';document.body.appendChild(i)},2000)</script>":path.equals("/ads")?"<a id='manualAd' href='/ad-follow' style='position:fixed;left:25vw;top:20vh;width:50vw;height:60vh;background:#b45;color:white;display:grid;place-items:center'>手动屏蔽测试广告</a><p>普通网页内容仍保留</p>":path.equals("/ad-follow")?"不应进入的广告页面":path.equals("/small")?"<title>小播放器测试</title><div class='player' style='width:480px;height:270px'><video controls preload='auto' style='width:100%;height:100%' src='/clip.mp4'></video></div>":path.equals("/embedded")?"<h1>网页播放页</h1><div style='width:600px;transform:translateX(40px);overflow:hidden'><iframe src='http://localhost:"+socket.getLocalPort()+"/inner' style='width:480px;height:270px;border:0'></iframe></div>":path.equals("/inner")?"<video autoplay muted loop controls style='width:320px;height:180px' src='/clip.mp4'></video>":path.equals("/video")||path.startsWith("/vodplay/")?"<video controls preload='auto' style='width:100%;height:540px' src='/clip.mp4'></video>":path.equals("/input-trap")?"<input type='search' placeholder='站内搜索' autofocus style='display:block;width:300px;height:50px'><a href='/detail' style='display:block;margin-top:40px;padding:30px'>继续选片</a>":path.equals("/offscreen")?"<a href='/vodplay/1-1-1.html' style='display:block;margin-top:1000px;padding:40px'>第1集</a>":path.equals("/maccms")?"<a href='/vodplay/1-1-1.html' style='display:block;padding:40px'>第1集</a>":path.equals("/banner")?"<div class='advertisement'><video preload='auto' style='width:100%;height:80px' src='/clip.mp4'></video></div><video controls preload='auto' style='width:100%;height:540px' src='/clip.mp4'></video>":"<a href='/detail' style='display:block;padding:40px;font-size:32px;color:white'>进入详情</a>";String title=path.equals("/detail")?"遥控进入详情":"现代网页已就绪";body=("<!doctype html><meta charset='utf-8'><body style='margin:0;background:#101418;color:white'>"+html+"<script>const v={title:'"+title+"'};document.title=v?.title ?? '脚本未兼容';</script></body>").getBytes(StandardCharsets.UTF_8);type="text/html; charset=utf-8";}
            int begin=0,end=body.length-1;boolean partial=false;if(range!=null&&range.matches("bytes=\\d+-\\d*")){String[] r=range.substring(6).split("-",-1);begin=Math.min(Integer.parseInt(r[0]),end);if(!r[1].isEmpty())end=Math.min(Integer.parseInt(r[1]),end);partial=begin<=end;}
            String head="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+(end-begin+1)+"\r\nAccept-Ranges: bytes\r\nConnection: close\r\n"+(partial?"Content-Range: bytes "+begin+"-"+end+"/"+body.length+"\r\n":"")+"\r\n";OutputStream out=c.getOutputStream();out.write(head.getBytes(StandardCharsets.US_ASCII));out.write(body,begin,end-begin+1);out.flush();
        }catch(IOException|RuntimeException ignored){}}
        @Override public void close() throws IOException {closed=true;socket.close();workers.shutdownNow();}
    }
}
