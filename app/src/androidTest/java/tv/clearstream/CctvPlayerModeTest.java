package tv.clearstream;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Offline official-page fixture. Only this test intercepts the official main document. */
@RunWith(AndroidJUnit4.class)
public final class CctvPlayerModeTest {
    private static final String FIXTURE_URL="https://tv.cctv.com/live/cctv13/";
    private final Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private Activity activity;
    private final java.util.List<String> console=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    @After public void close(){if(activity!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.finish());}
    @Test public void officialPlayerKeepsItsContainerLocksOtherLinksAndHasSimpleRemoteMenu()throws Exception {
        launch("https://tv.cctv.com/live/cctv13/");
        WebView web=web();
        String html="<!doctype html><meta charset='utf-8'><script>window.__cctvErrors=[];window.addEventListener('error',function(e){window.__cctvErrors.push(String(e.message)+' @'+e.lineno);});</script><body><a id='ad' href='https://example.com'>广告外链</a><div id='player' class='video_flash'><div id='official-wrap'><video controls style='width:600px;height:338px'></video><button class='vjs-big-play-button' onclick='window.officialClick=true'>播放</button></div></div><a id='another' href='https://tv.cctv.com/live/cctv1/'>另一个频道</a></body>";
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage message){console.add(message.messageLevel()+":"+message.message()+" @"+message.sourceId()+":"+message.lineNumber());return true;}}));
        loadOfficialFixture(web,html);
        JSONObject state=await("(function(){var p=document.querySelector('#player'),v=document.querySelector('video');if(!window.__clearCctv||!p||!v)return {ready:false,location:location.href,protocol:location.protocol,documentState:document.readyState,factory:typeof window.__clearCctvFactory,instance:typeof window.__clearCctv,player:!!p,video:!!v,errors:window.__cctvErrors||[]};var r=p.getBoundingClientRect();return {ready:true,parent:v.parentNode.id,width:r.width,height:r.height,viewportWidth:innerWidth,viewportHeight:innerHeight,outsideHidden:getComputedStyle(document.querySelector('#ad')).visibility==='hidden'||getComputedStyle(document.querySelector('#ad')).display==='none'};})()",8000);
        assertEquals("The official player still owns its video","official-wrap",state.getString("parent"));
        assertEquals(state.getDouble("viewportWidth"),state.getDouble("width"),3);
        assertEquals(state.getDouble("viewportHeight"),state.getDouble("height"),3);
        assertTrue("External page controls are hidden and cannot take remote focus",state.getBoolean("outsideHidden"));
        device.pressMenu();
        assertTrue(device.wait(Until.hasObject(By.text("播放 / 暂停")),3000));
        assertTrue(device.hasObject(By.text("重新加载")));
        assertTrue(device.hasObject(By.text("返回选台")));
        assertFalse(device.hasObject(By.text("外部打开")));
        assertFalse(device.hasObject(By.text("电视适配")));
        device.pressBack();long until=SystemClock.elapsedRealtime()+3000;while(!activity.isFinishing()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
        assertTrue("One Back leaves the player even with its toolbar open",activity.isFinishing());
    }
    @Test public void flagOnUnrelatedWebsiteDoesNotEnableOfficialMode()throws Exception {
        launch("https://example.com/");
        assertTrue(device.wait(Until.hasObject(By.text("外部打开")),3000));
        assertFalse(device.hasObject(By.text("返回选台")));
    }
    @Test public void recoveryButtonsKeepRemoteFocusEvenWhileWebsiteFullscreenIsSet()throws Exception {
        launch("https://tv.cctv.com/live/cctv13/");
        loadOfficialFixture(web(),"<!doctype html><meta charset='utf-8'><title>离线恢复控件测试</title><body id='recovery-fixture'><div id='player'></div></body>");
        await("({ready:!!document.getElementById('recovery-fixture')&&document.readyState==='complete'})",5000);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Field field=WebActivity.class.getDeclaredField("customView");field.setAccessible(true);field.set(activity,new View(activity));java.lang.reflect.Field player=WebActivity.class.getDeclaredField("officialPlayer");player.setAccessible(true);((OfficialCctvPlayer)player.get(activity)).failure("测试官网播放失败");}catch(Exception error){throw new AssertionError(error);}});
        assertTrue(device.wait(Until.hasObject(By.text("重试")),3000));
        awaitNativeFocus("重试");
        device.pressDPadRight();
        awaitNativeFocus("返回选台");
        device.pressDPadCenter();long until=SystemClock.elapsedRealtime()+3000;while(!activity.isFinishing()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
        assertTrue("The native recovery button must receive OK",activity.isFinishing());
    }
    private void launch(String url)throws Exception {
        app.startActivity(new Intent(app,WebActivity.class).putExtra("url",url).putExtra("officialCctv",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long until=SystemClock.elapsedRealtime()+6000;while(activity==null&&SystemClock.elapsedRealtime()<until){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof WebActivity&&!a.isFinishing()&&a.hasWindowFocus())activity=a;});SystemClock.sleep(50);}assertNotNull(activity);
    }
    @android.annotation.TargetApi(26)
    private void loadOfficialFixture(WebView web,String html){
        Assume.assumeTrue("Preserving the production WebViewClient requires Android API 26+",android.os.Build.VERSION.SDK_INT>=26);
        final byte[] document=html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            WebViewClient delegate=web.getWebViewClient();
            web.stopLoading();
            web.setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    if(request.isForMainFrame()&&FIXTURE_URL.equals(request.getUrl().toString())){
                        WebResourceResponse response=new WebResourceResponse("text/html","UTF-8",new java.io.ByteArrayInputStream(document));
                        response.setResponseHeaders(java.util.Collections.singletonMap("Cache-Control","no-store"));return response;
                    }
                    return delegate.shouldInterceptRequest(view,request);
                }
                @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){delegate.onPageStarted(view,url,icon);}
                @Override public void onPageFinished(WebView view,String url){delegate.onPageFinished(view,url);}
                @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return delegate.shouldOverrideUrlLoading(view,url);}
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return delegate.shouldOverrideUrlLoading(view,request);}
                @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){delegate.onReceivedError(view,request,error);}
                @Override public void onReceivedError(WebView view,int code,String description,String url){delegate.onReceivedError(view,code,description,url);}
                @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){delegate.onReceivedHttpError(view,request,response);}
            });
            web.loadUrl(FIXTURE_URL);
        });
    }
    private void awaitNativeFocus(String label)throws Exception {long until=SystemClock.elapsedRealtime()+3000;boolean[] match={false};while(SystemClock.elapsedRealtime()<until){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{View focus=activity.getCurrentFocus();match[0]=focus instanceof android.widget.TextView&&label.contentEquals(((android.widget.TextView)focus).getText());});if(match[0])return;SystemClock.sleep(50);}throw new AssertionError("Expected native focus on "+label+"; "+nativeDiagnostic());}
    private WebView web()throws Exception {java.lang.reflect.Field field=WebActivity.class.getDeclaredField("web");field.setAccessible(true);WebView value=(WebView)field.get(activity);Assume.assumeTrue("Controlled website fixture requires an installed Android WebView provider",value!=null);return value;}
    private JSONObject await(String script,long timeout)throws Exception {long until=SystemClock.elapsedRealtime()+timeout;JSONObject last=new JSONObject();while(SystemClock.elapsedRealtime()<until){CountDownLatch done=new CountDownLatch(1);String[] result={null};WebView web=web();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web.evaluateJavascript(script,v->{result[0]=v;done.countDown();}));assertTrue("No JS callback; native="+nativeDiagnostic(),done.await(5,TimeUnit.SECONDS));if(result[0]!=null&&result[0].startsWith("{"))last=new JSONObject(result[0]);if(last.optBoolean("ready"))return last;SystemClock.sleep(100);}throw new AssertionError("Fixture did not enter player mode: "+last+" native="+nativeDiagnostic()+" console="+console);}
    private String nativeDiagnostic()throws Exception {String[] result={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{WebView w=web();JSONObject info=new JSONObject().put("url",w.getUrl()).put("originalUrl",w.getOriginalUrl()).put("progress",w.getProgress()).put("userAgent",w.getSettings().getUserAgentString()).put("windowFocus",activity.hasWindowFocus());View current=activity.getCurrentFocus();info.put("currentFocus",current==null?"null":current.getClass().getName()+":"+(current instanceof android.widget.TextView?((android.widget.TextView)current).getText():current.getContentDescription()));for(String name:new String[]{"officialCctv","mediaForeground","documentGeneration","cctvScript"}){java.lang.reflect.Field f=WebActivity.class.getDeclaredField(name);f.setAccessible(true);Object value=f.get(activity);info.put(name,"cctvScript".equals(name)?value==null?"null":"loaded "+((String)value).length():value);}java.lang.reflect.Field field=WebActivity.class.getDeclaredField("officialPlayer");field.setAccessible(true);Object player=field.get(activity);if(player!=null){java.lang.reflect.Field actions=OfficialCctvPlayer.class.getDeclaredField("actions");actions.setAccessible(true);android.view.ViewGroup controls=(android.view.ViewGroup)actions.get(player);View retry=controls.getChildAt(0);info.put("retry",new JSONObject().put("width",retry.getWidth()).put("height",retry.getHeight()).put("shown",retry.isShown()).put("hasFocus",retry.hasFocus()).put("focusable",retry.isFocusable()));}result[0]=info.toString();}catch(Exception error){result[0]=error.toString();}});return result[0];}
}
