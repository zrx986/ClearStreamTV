package tv.clearstream;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import androidx.test.uiautomator.*;
import java.io.File;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public final class WebFullscreenTest {
    private ModernBrowserTest.Server server;private Context app;private UiDevice device;private Activity activity;
    @Before public void setup()throws Exception {
        assertTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        Assume.assumeTrue(Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());server=new ModernBrowserTest.Server();server.start();
    }
    @After public void close()throws Exception {if(activity!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.finish());if(server!=null)server.close();}
    @Test public void compatibleFullscreenExpandsThePlayerAndBackRestoresItsSize()throws Exception {
        app.startActivity(new Intent(app,WebActivity.class).putExtra("url","http://127.0.0.1:"+server.socket.getLocalPort()+"/small").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long until=SystemClock.elapsedRealtime()+10000;Activity[] found={null};while(found[0]==null&&SystemClock.elapsedRealtime()<until){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof WebActivity)found[0]=a;});SystemClock.sleep(50);}activity=found[0];assertNotNull(activity);
        assertTrue(device.wait(Until.hasObject(By.text("小播放器测试")),10000));device.pressMenu();for(int i=0;i<4;i++)device.pressDPadRight();assertEquals("全屏",device.findObject(By.focused(true)).getText());device.pressDPadCenter();
        until=SystemClock.elapsedRealtime()+8000;JSONObject state=new JSONObject();while(SystemClock.elapsedRealtime()<until){state=new JSONObject(((WebActivity)activity).layoutAudit.toString());if(state.optInt("width")>=state.optInt("viewportWidth")-2&&state.optInt("height")>=state.optInt("viewportHeight")-2&&state.optInt("width")>400)break;SystemClock.sleep(100);}
        assertTrue(state.toString(),state.optBoolean("active")&&state.optInt("width")>=state.optInt("viewportWidth")-2&&state.optInt("height")>=state.optInt("viewportHeight")-2&&state.optInt("width")>400);assertFalse(device.hasObject(By.text("电视适配")));
        File dir=new File(app.getExternalFilesDir(null),"sourcefree-013");dir.mkdirs();device.takeScreenshot(new File(dir,"fullscreen-webview.png"));
        device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("电视适配")),4000));
        final String[] result={null};CountDownLatch done=new CountDownLatch(1);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Field field=WebActivity.class.getDeclaredField("web");field.setAccessible(true);((WebView)field.get(activity)).evaluateJavascript("Math.round(document.querySelector('.player').getBoundingClientRect().width)",v->{result[0]=v;done.countDown();});}catch(Exception e){throw new AssertionError(e);}});
        assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals("480",result[0]);
    }
}
