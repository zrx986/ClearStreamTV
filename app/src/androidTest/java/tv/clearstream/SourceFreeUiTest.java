package tv.clearstream;

import android.app.Activity;
import android.content.*;
import android.os.Build;
import android.os.SystemClock;
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
import tv.clearstream.core.*;
import static org.junit.Assert.*;

/** All data is reserved example input, created only on disposable emulators. */
@RunWith(AndroidJUnit4.class)
public final class SourceFreeUiTest {
    private ClearApp app; private UiDevice device;
    @Before public void setup()throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());finishOwned();
        app.stopService(new Intent(app,PlaybackService.class));app.store.replaceAll(Collections.emptyList());
        for(Website site:app.store.websites())app.store.deleteWebsite(site);
        app.settings.edit().clear().putBoolean("simpleMode",true).commit();app.network.refreshPolicy();
    }
    @After public void cleanup()throws Exception {finishOwned();}
    @Test public void emptyFirstLaunchConfirmDirectlyOffersImport()throws Exception {
        launch();assertEquals("tv.clearstream.nosource",app.getPackageName());
        assertTrue(app.store.all().isEmpty());assertTrue(app.store.websites().isEmpty());
        assertTrue(app.settings.getStringSet("subscriptions",Collections.emptySet()).isEmpty());
        assertEquals("看电视",device.findObject(By.focused(true)).getText());screenshot("empty-home.png");
        device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("一键导入频道")),4000));
        assertTrue(device.hasObject(By.text("使用订阅链接")));assertTrue(device.hasObject(By.text("选择源文件")));
    }
    @Test public void remoteFilmsPageHasOnlyUserSitesAndFocusesAdd()throws Exception {
        launch();device.pressDPadRight();assertEquals("看影视",device.findObject(By.focused(true)).getText());
        device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("还没有网站，请选择“添加网站”保存你自己的网址。")),5000));
        assertEquals("＋ 添加网站",device.findObject(By.focused(true)).getText());
        assertFalse(device.hasObject(By.descContains("网站入口：")));assertTrue(app.store.websites().isEmpty());
        screenshot("empty-websites.png");device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("添加影视网址")),4000));
    }
    @Test public void filmSearchHasNoSuggestedTargetsAndOnlySavesExplicitInput()throws Exception {
        app.startActivity(new Intent(app,FilmSearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.text("尚未选择网站，点右上角“搜索范围”添加")),5000));
        activate("搜索范围");assertTrue(device.wait(Until.hasObject(By.text("选择搜索网站")),3000));
        assertEquals(0,device.findObjects(By.checkable(true)).size());activate("添加网站");
        UiObject2 input=device.wait(Until.findObject(By.desc("网站网址")),3000);assertNotNull(input);
        input.setText("https://my-site.example/");activate("添加并选中");
        assertTrue(device.wait(Until.hasObject(By.text("已添加并选入搜索范围")),3000));
        assertEquals(1,app.store.websites().size());assertEquals("https://my-site.example/",app.store.websites().get(0).url);
        org.json.JSONArray scope=new org.json.JSONArray(app.settings.getString("filmSearchScope","[]"));
        assertEquals(1,scope.length());assertEquals("https://my-site.example/",scope.getString(0));
    }
    @Test public void importedSameChannelRoutesStayMergedInNativeCatalog()throws Exception {
        app.store.add(Arrays.asList(new SourceEntry("CCTV1","https://first.example/one.m3u8","甲"),new SourceEntry("央视一套","http://[2001:db8::1]/two.m3u8","乙"),new SourceEntry("湖南卫视","https://third.example/three.m3u8","甲")));
        launch();device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("分类选台")),5000));
        assertTrue(device.wait(Until.hasObject(By.descContains("CCTV-1 综合，2 条线路")),5000));
        assertEquals(3,app.store.all().size());assertEquals(2,ChannelGuide.programs(app.store.all(),null).size());
        assertFalse(device.hasObject(By.text("选择频道，按确定就能看")));
    }
    private void launch(){app.startActivity(Intent.makeMainActivity(new ComponentName(app,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));assertTrue(device.wait(Until.hasObject(By.text("看影视")),8000));device.waitForIdle();}
    private void screenshot(String name)throws Exception {File dir=new File(app.getExternalFilesDir(null),"sourcefree-013");dir.mkdirs();device.takeScreenshot(new File(dir,name));}
    private void activate(String label)throws Exception {
        Activity[] active={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))active[0]=a;});
        assertNotNull(active[0]);boolean[] found={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{View v=find(active[0].getWindow().getDecorView(),label);found[0]=v!=null&&v.requestFocus();});
        if(!found[0]){android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.contentEquals(node.getText()==null?"":node.getText())&&node.isFocusable())found[0]|=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);}
        assertTrue(label,found[0]);device.pressDPadCenter();device.waitForIdle();
    }
    private View find(View root,String label){if(root instanceof TextView&&label.contentEquals(((TextView)root).getText())&&root.isFocusable())return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=find(((ViewGroup)root).getChildAt(i),label);if(v!=null)return v;}return null;}
    private void finishOwned()throws Exception {InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Set<Activity> all=new HashSet<>();for(Stage s:Stage.values())all.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(s));for(Activity a:all)if(!a.isFinishing())a.finish();});InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(150);}
}
