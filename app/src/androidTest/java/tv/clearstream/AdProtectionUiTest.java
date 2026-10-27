package tv.clearstream;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import androidx.test.uiautomator.*;
import java.io.File;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Fixture link points nowhere; selection must not navigate to it. */
@RunWith(AndroidJUnit4.class)
public class AdProtectionUiTest {
    private ClearApp app;private UiDevice device;private ModernBrowserTest.Server server;private Activity activity;
    @Before public void setup()throws Exception {
        assertTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        Assume.assumeTrue(WebsitePlayback.modernAvailable()||Build.VERSION.SDK_INT<26||android.webkit.WebView.getCurrentWebViewPackage()!=null);app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Set<String> keys=new HashSet<>(app.settings.getAll().keySet());android.content.SharedPreferences.Editor edit=app.settings.edit();for(String key:keys)if(key.startsWith("adProtection:"))edit.remove(key);edit.commit();
        server=new ModernBrowserTest.Server();server.start();
    }
    @After public void cleanup()throws Exception {if(activity!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.finish());if(server!=null)server.close();}
    @Test public void remoteCanPickPersistAndClearAnAdWithoutFollowingIt()throws Exception {
        launch();assertTrue(device.wait(Until.hasObject(By.text("手动屏蔽测试广告")),20000));
        device.pressMenu();for(int i=0;i<8;i++)device.pressDPadRight();assertEquals("广告防护",device.findObject(By.focused(true)).getText());device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("点选网页广告并隐藏")),3000));device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("点选广告：方向键移动光标 · 确定选择 · 返回取消")),4000));device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("屏蔽这个区域？")),4000));assertEquals("取消",device.findObject(By.focused(true)).getText());
        File out=new File(app.getExternalFilesDir(null),"sourcefree-013");out.mkdirs();device.takeScreenshot(new File(out,"ad-pick-confirm.png"));
        device.pressDPadRight();assertEquals("保存并屏蔽",device.findObject(By.focused(true)).getText());device.pressDPadCenter();
        assertTrue(device.wait(Until.gone(By.text("手动屏蔽测试广告")),5000));assertFalse(device.hasObject(By.text("不应进入的广告页面")));
        String saved=app.settings.getString("adProtection:127.0.0.1:custom","[]");assertTrue(saved,saved.contains("manualAd"));
        device.pressBack();launch();assertTrue(device.wait(Until.hasObject(By.text("普通网页内容仍保留")),15000));assertTrue(device.wait(Until.gone(By.text("手动屏蔽测试广告")),5000));
        device.pressMenu();for(int i=0;i<8;i++)device.pressDPadRight();device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("清除本站点选规则（1 条）")),3000));
        device.pressDPadDown();device.pressDPadDown();device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("清除本站点选规则？")),3000));activateDialog("清除并刷新");
        assertTrue(device.wait(Until.hasObject(By.text("手动屏蔽测试广告")),15000));assertEquals("[]",app.settings.getString("adProtection:127.0.0.1:custom","[]"));
    }
    @Test public void basicProtectionBlocksKnownResourceAndHidesCommonAdSlot()throws Exception {
        launchPath("/adfilters");assertTrue(device.wait(Until.hasObject(By.text("普通网页内容仍保留")),15000));
        long until=SystemClock.elapsedRealtime()+12000;org.json.JSONObject state=new org.json.JSONObject();
        while(SystemClock.elapsedRealtime()<until){if(WebsitePlayback.modernAvailable())state=new org.json.JSONObject(activity.getClass().getField("auditState").get(activity).toString());else{java.lang.reflect.Field field=WebActivity.class.getDeclaredField("adProtection");field.setAccessible(true);AdProtection protection=(AdProtection)field.get(activity);state=new org.json.JSONObject(((WebActivity)activity).adAudit.toString());state.put("adBlocked",protection.blocked.get());state.put("adHidden",state.optInt("hidden"));}if(state.optInt("adBlocked")>0&&state.optInt("adHidden")>0)break;SystemClock.sleep(150);}
        assertTrue(state.toString(),state.optInt("adBlocked")>0&&state.optInt("adHidden")>0);assertFalse(device.hasObject(By.text("自动屏蔽测试广告")));assertTrue(device.hasObject(By.text("普通网页内容仍保留")));
    }
    private void launch()throws Exception {launchPath("/ads");}
    private void launchPath(String path)throws Exception {app.startActivity(WebsitePlayback.intent(app,"http://127.0.0.1:"+server.socket.getLocalPort()+path,"广告防护演示").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));long until=SystemClock.elapsedRealtime()+9000;Activity[] found={null};while(found[0]==null&&SystemClock.elapsedRealtime()<until){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a.getClass().getName().equals(WebsitePlayback.modernAvailable()?"tv.clearstream.ModernWebActivity":"tv.clearstream.WebActivity"))found[0]=a;});SystemClock.sleep(50);}activity=found[0];assertNotNull(activity);}
    private void activateDialog(String name)throws Exception {boolean[] ok={false};android.view.accessibility.AccessibilityNodeInfo node=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();for(android.view.accessibility.AccessibilityNodeInfo child:node.findAccessibilityNodeInfosByText(name))if(name.contentEquals(child.getText()==null?"":child.getText())&&child.isFocusable())ok[0]|=child.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);assertTrue(ok[0]);device.pressDPadCenter();}
}
