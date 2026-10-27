package tv.clearstream;

import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.util.*;
import tv.clearstream.core.*;

/** Destructive setup is guarded to run only on a disposable Android emulator. */
@RunWith(AndroidJUnit4.class)
public class TelevisionTest {
    @Rule public org.junit.rules.TestName testName=new org.junit.rules.TestName();
    private ClearApp app;
    private UiDevice device;
    private FixtureServer server;
    @Before public void prepare() throws Exception {
        assertTrue("Use a disposable emulator, never a friend's real box",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("settings put global hide_error_dialogs 1");
        device.pressHome();
        app.stopService(new Intent(app,PlaybackService.class));
        app.store.replaceAll(Collections.emptyList());
        for(Website site:app.store.websites())app.store.deleteWebsite(site);
        app.settings.edit().clear().putBoolean("simpleMode",false).commit();app.network.refreshPolicy();
    }
    @After public void cleanup() throws Exception {
        File dir=new File(app.getExternalFilesDir(null),"evidence");dir.mkdirs();
        device.takeScreenshot(new File(dir,testName.getMethodName()+"-end.png"));
        device.dumpWindowHierarchy(new File(dir,testName.getMethodName()+".xml"));
        device.pressBack();if(server!=null)server.close();
    }
    private void launch() {
        Intent i=Intent.makeMainActivity(new ComponentName(app,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);app.startActivity(i);
        assertTrue(device.wait(Until.hasObject(By.text(app.settings.getBoolean("simpleMode",true)?"看电视":"直播频道")),30000));device.waitForIdle();
    }
    @Test public void remoteCanImportAndPersistAnIpv6Source() throws Exception {
        launch();device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("导入直播源")),3000));
        device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000));
        List<UiObject2> fields=device.findObjects(By.clazz("android.widget.EditText"));
        assertEquals(3,fields.size());fields.get(0).setText("遥控导入测试");fields.get(2).setText("http://[2001:db8::10]/live.m3u8");
        device.findObject(By.text("预览导入")).click();
        assertTrue(device.wait(Until.hasObject(By.text("导入预览")),3000));
        device.findObject(By.text("确认导入")).click();
        assertTrue(device.wait(Until.hasObject(By.text("遥控导入测试")),3000));
        assertEquals(1,app.store.all().size());
        assertEquals("http://[2001:db8::10]/live.m3u8",app.store.all().get(0).url);
        screenshot("01-home-imported.png");
        activate("管理源","我的源");screenshot("03-sources.png");
    }
    @Test public void ipv4HlsActuallyRendersAndRecordsMediaPeer() throws Exception { render("127.0.0.1","IPv4","02-player-ipv4.png"); }
    @Test public void highDensityShortTvKeepsFocusedChannelFullyVisible() throws Exception {
        try {
            app.settings.edit().putBoolean("simpleMode",true).commit();
            List<SourceEntry> sources=new ArrayList<>();for(int i=0;i<6;i++)sources.add(new SourceEntry("测试频道"+i,"https://example.org/"+i,"电视分组"));
            app.store.add(sources);launch();
            device.executeShellCommand("wm size 1920x864");device.executeShellCommand("wm density 384");
            device.executeShellCommand("settings put system font_scale 1.5");device.waitForIdle();launch();device.waitForIdle();
            final android.graphics.Rect bounds=new android.graphics.Rect();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{MainActivity activity=currentActivity();ArrayList<android.view.View> cards=new ArrayList<>();activity.getWindow().getDecorView().findViewsWithText(cards,"看电视，测试频道0",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);assertFalse(cards.isEmpty());assertTrue(cards.get(0).getGlobalVisibleRect(bounds));});
            if(Build.VERSION.SDK_INT>26)screenshot("10-short-tv-layout.png");
            assertTrue("Focused channel is vertically clipped: "+bounds,bounds.height()>=180);
            assertTrue("Channel title must be visible",device.hasObject(By.textContains("即将播放：测试频道0")));
            device.pressDPadDown();device.waitForIdle();
            UiObject2 focused=device.findObject(By.focused(true));assertNotNull(focused);
            assertTrue("Next action must be visible",focused.getVisibleBounds().height()>=50);
        } finally {device.executeShellCommand("settings put system font_scale 1.0");device.executeShellCommand("wm size reset");device.executeShellCommand("wm density reset");}
        launch();screenshot("11-home-default-6channels.png");
    }
    @Test public void ipv6HlsActuallyRendersAndRecordsMediaPeer() throws Exception { render("::1","IPv6","02-player-ipv6.png"); }
    @Test public void networkPolicyChangeRecreatesPlayingMediaSource() throws Exception {
        render("127.0.0.1","IPv4","09-before-network-reconnect.png");
        app.renderedFirstFrame=0;app.network.refreshPolicy();
        long until=SystemClock.elapsedRealtime()+12000;while((app.renderedFirstFrame==0||!"IPv4".equals(app.network.mediaFamily))&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(150);
        assertTrue("Active source must be rebuilt after old Network clients are cancelled",app.renderedFirstFrame>0);
        assertEquals("IPv4",app.network.mediaFamily);
    }
    @Test public void cumulativeImportsCannotCreateAnUnrestorableCheckpoint() throws Exception {
        List<SourceEntry> first=new ArrayList<>(),second=new ArrayList<>();
        for(int i=0;i<6000;i++){first.add(new SourceEntry("A"+i,"https://example.org/a/"+i,"A"));second.add(new SourceEntry("B"+i,"https://example.org/b/"+i,"B"));}
        assertEquals(6000,app.store.add(first));assertEquals(4000,app.store.add(second));assertEquals(10000,app.store.all().size());
        ImportBackup backup=new ImportBackup(app);backup.save(app.store.all());assertEquals(10000,backup.load().size());
        Channel deleted=app.store.all().get(0);app.store.delete(deleted);
        app.store.add(Collections.singletonList(new SourceEntry("replacement","https://example.org/replacement","A")));
        app.store.restore(deleted);
        assertEquals("Undo deletion must also respect the cumulative limit",10000,app.store.all().size());
    }
    @Test public void closedIpv6CandidateFallsBackToWorkingIpv4WithoutChangingHostname() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();
        okhttp3.OkHttpClient client=app.network.client("media").newBuilder()
            .dns(host->Arrays.asList(InetAddress.getByName("::1"),InetAddress.getByName("127.0.0.1"))).build();
        long start=SystemClock.elapsedRealtime();
        try(okhttp3.Response response=client.newCall(new okhttp3.Request.Builder().url("http://fixture.invalid:"+server.port()+"/index.m3u8").build()).execute()) {
            assertEquals(200,response.code());assertTrue(response.body().string().startsWith("#EXTM3U"));
            assertEquals("fixture.invalid",response.request().url().host());assertEquals("IPv4",app.network.mediaFamily);
        }
        assertTrue("Closed IPv6 must not prevent IPv4 fallback",SystemClock.elapsedRealtime()-start<5000);
    }
    @Test public void forcedIpv4DoesNotPretendAnIpv6LiteralHasFallback() throws Exception {
        server=new FixtureServer("::1");server.start();
        String url="http://[::1]:"+server.port()+"/index.m3u8";
        try(okhttp3.Response ignored=app.network.client("probe",AddressPolicy.Mode.IPV4).newCall(new okhttp3.Request.Builder().url(url).build()).execute()) { fail("Must reject mismatched literal"); }
        catch(IOException expected) { assertTrue(server.requests.isEmpty()); }
    }
    @Test public void libraryPreservesEditsFavoriteAndUndoAcrossReopen() throws Exception {
        SourceEntry original=new SourceEntry("原名","http://[2001:db8::1]/live.m3u8","原组");
        assertEquals(1,app.store.add(Collections.singletonList(original)));
        Channel c=app.store.all().get(0);app.store.favorite(c);app.store.rename(c,"朋友的频道","收藏组");
        assertEquals(0,app.store.add(Collections.singletonList(original)));
        try(SourceStore reopened=new SourceStore(app)) {
            Channel saved=reopened.all().get(0);assertEquals("朋友的频道",saved.name);assertEquals("收藏组",saved.group);assertTrue(saved.favorite);
            reopened.delete(saved);assertTrue(reopened.all().isEmpty());reopened.restore(saved);assertEquals(saved.id,reopened.all().get(0).id);
        }
    }
    private void render(String address,String expected,String file) throws Exception {
        server=new FixtureServer(address);server.start();
        String host=address.contains(":")?"["+address+"]":address;
        app.store.add(Collections.singletonList(new SourceEntry("本地合成测试流","http://"+host+":"+server.port()+"/index.m3u8","测试")));
        app.renderedFirstFrame=0;app.videoWidth=0;launch();
        // A direction key and OK are sufficient; no touch event is used to start playback.
        device.pressDPadCenter();
        long until=SystemClock.elapsedRealtime()+20000;
        while(app.renderedFirstFrame==0&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(150);
        assertTrue("First frame must be rendered, not just HTTP 200",app.renderedFirstFrame>0);
        assertEquals(640,app.videoWidth);assertEquals(expected,app.network.mediaFamily);
        assertTrue(server.requests.contains("/index.m3u8"));
        assertTrue(server.requests.stream().anyMatch(x->x.endsWith(".ts")));
        screenshot(file);
        System.out.println("RENDERED "+expected+" width="+app.videoWidth+" requests="+server.requests.size());
    }
    @Test public void settingsAreAccessibleWithRemoteAndHaveOneFocus() throws Exception {
        launch();activate("设置","网络与播放");
        device.pressDPadRight();device.pressDPadDown();
        assertEquals(1,device.findObjects(By.focused(true)).size());screenshot("04-network.png");
    }
    @Test public void simpleModeHidesDangerousControlsAndAdminDefaultsToCancel() throws Exception {
        app.settings.edit().putBoolean("simpleMode",true).commit();launch();
        assertFalse(device.hasObject(By.textContains("管理源")));assertFalse(device.hasObject(By.textContains("测速")));assertFalse(device.hasObject(By.text("网络与播放")));
        activate("家人设置","请家人帮忙设置");device.findObject(By.text("更多设置（管理模式）")).click();
        assertTrue(device.wait(Until.hasObject(By.text("进入管理模式？")),3000));
        assertEquals("继续看电视",device.findObject(By.focused(true)).getText());
        device.pressDPadCenter();assertTrue(app.settings.getBoolean("simpleMode",false));screenshot("05-simple-mode.png");
    }
    @Test public void importedWebsiteOpensInsideAppAndRemoteMouseClicksPage() throws Exception {
        app.settings.edit().putBoolean("simpleMode",true).commit();server=new FixtureServer("127.0.0.1");server.start();launch();
        activate("影视网站","选择网站 · 搜索影片 · 按确定播放");activate("添加网站","添加影视网址");
        device.findObject(By.clazz("android.widget.EditText")).setText("http://127.0.0.1:"+server.port()+"/portal.html");
        device.findObject(By.text("保存入口")).click();
        assertTrue(device.wait(Until.hasObject(By.textContains("127.0.0.1")),3000));assertEquals(1,app.store.websites().size());
        screenshot("07-websites.png");
        UiObject2 card=device.findObject(By.textContains("127.0.0.1"));card.click();device.waitForIdle();
        if(!device.hasObject(By.text("站内搜索 · 遥控器选片")))device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("站内搜索 · 遥控器选片")),7000));
        if(Build.VERSION.SDK_INT>=26&&android.webkit.WebView.getCurrentWebViewPackage()==null) {
            assertTrue("No-provider box must show external-open fallback",device.wait(Until.hasObject(By.textContains("设备缺少可用网页组件")),5000));
            screenshot("08-website-no-webview-fallback.png");device.pressBack();return;
        }
        activate("原网页","按确定测试");
        assertTrue(device.wait(Until.hasObject(By.clazz("android.webkit.WebView")),10000));
        // The OK used to open the card can arrive after WebView starts and already
        // click the central test button. Both states prove the page loaded.
        assertTrue(device.wait(Until.hasObject(By.text("按确定测试")),3000)||device.hasObject(By.text("遥控点击成功")));
        if(device.hasObject(By.text("按确定测试")))device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("遥控点击成功")),5000));screenshot("08-website-remote.png");
        device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("站内搜索 · 遥控器选片")),5000));device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("影视网站")),5000));assertEquals(1,app.store.websites().size());
    }
    @Test public void tvWebsiteAdapterChangesLayoutAndSupportsDirectionalFocusAndRestore() throws Exception {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT<26||android.webkit.WebView.getCurrentWebViewPackage()!=null);
        server=new FixtureServer("127.0.0.1");server.start();
        app.startActivity(new Intent(app,WebActivity.class).putExtra("url","http://127.0.0.1:"+server.port()+"/tvportal.html").putExtra("name","网页适配测试").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.text("新闻")),10000));
        long until=SystemClock.elapsedRealtime()+6000;while(!"true".equals(evaluate("document.documentElement.classList.contains('cs-tv')"))&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
        assertEquals("true",evaluate("document.documentElement.classList.contains('cs-tv')"));
        assertEquals("\"18px\"",evaluate("getComputedStyle(document.querySelector('#first')).fontSize"));
        device.pressDPadRight();device.waitForIdle();assertEquals("\"second\"",evaluate("document.querySelector('[data-cs-focus]').id"));
        device.pressDPadDown();device.waitForIdle();assertEquals("\"play\"",evaluate("document.querySelector('[data-cs-focus]').id"));
        device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("遥控适配成功")),5000));screenshot("12-website-tv-adapted.png");
        device.pressMenu();UiObject2 mode=device.findObject(By.text("电视适配"));assertNotNull(mode);mode.click();device.waitForIdle();if(!device.hasObject(By.text("网页操作模式")))device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("网页操作模式")),3000));device.findObject(By.text("原始网页 · 保留网站布局")).click();device.waitForIdle();
        assertEquals("false",evaluate("document.documentElement.classList.contains('cs-tv')"));
        assertEquals("\"10px\"",evaluate("getComputedStyle(document.querySelector('#first')).fontSize"));
    }
    private String evaluate(String javascript) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> value=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {
            for(android.app.Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                android.webkit.WebView browser=findWebView(activity.getWindow().getDecorView());
                if(browser!=null){browser.evaluateJavascript(javascript,result->{value.set(result);latch.countDown();});return;}
            }
            latch.countDown();
        });
        assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS));return value.get();
    }
    private android.webkit.WebView findWebView(android.view.View view) {
        if(view instanceof android.webkit.WebView)return (android.webkit.WebView)view;
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.webkit.WebView found=findWebView(((android.view.ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    @Test public void quickSubscriptionImportKeepsFavoritesAndCreatesRestorableCheckpoint() throws Exception {
        app.settings.edit().putBoolean("simpleMode",true).commit();
        app.store.add(Collections.singletonList(new SourceEntry("原有收藏","http://[2001:db8::3]/a.m3u8","朋友")));
        app.store.favorite(app.store.all().get(0));server=new FixtureServer("::1");server.start();launch();
        openSubscription("http://[::1]:"+server.port()+"/friends.m3u");assertTrue(device.wait(Until.hasObject(By.text("导入预览")),5000));
        screenshot("06-quick-import-preview.png");device.findObject(By.text("确认导入")).click();
        long until=SystemClock.elapsedRealtime()+5000;while(app.store.all().size()!=3&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
        assertEquals(3,app.store.all().size());
        List<Channel> previous=new ImportBackup(app).load();assertEquals(1,previous.size());assertTrue(previous.get(0).favorite);
        app.store.replaceAll(previous);assertEquals(1,app.store.all().size());assertTrue(app.store.all().get(0).favorite);
    }
    private void activate(String label,String expectedTitle) throws Exception {
        UiObject2 control=device.findObject(By.textContains(label));
        if(control==null&&"影视网站".equals(label)&&app.settings.getBoolean("simpleMode",true))control=device.findObject(By.text("看影视"));
        assertNotNull("Visible entry for "+label,control);
        // Saved-site titles are children of the focusable card that also owns its status line.
        while(!control.isFocusable()&&control.getParent()!=null)control=control.getParent();
        assertTrue("Focusable entry for "+label,control.isFocusable());control.click();device.waitForIdle();
        for(int i=0;i<2&&!device.hasObject(By.text(expectedTitle));i++){device.pressDPadCenter();device.waitForIdle();}
        assertTrue("Expected "+expectedTitle,device.wait(Until.hasObject(By.text(expectedTitle)),3000));
    }
    @Test public void tvboxSelectionImportsOnlyChosenLiveList() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();app.settings.edit().putBoolean("simpleMode",true).commit();launch();
        openSubscription("http://127.0.0.1:"+server.port()+"/collection.json");
        assertTrue(device.wait(Until.hasObject(By.text("识别为 TVBox 配置 · 选择入口")),5000));
        assertEquals(0,app.store.all().size());assertFalse(server.requests.contains("/never.jar"));
        device.findObject(By.text("配置 · 测试线路")).click();
        assertTrue(device.wait(Until.hasObject(By.text("直播 · 朋友直播")),5000));device.findObject(By.text("直播 · 朋友直播")).click();
        assertTrue(device.wait(Until.hasObject(By.text("导入预览")),5000));screenshot("13-tvbox-live-preview.png");
        device.findObject(By.text("确认导入")).click();
        long end=SystemClock.elapsedRealtime()+3000;while(app.store.all().size()!=2&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
        assertEquals(2,app.store.all().size());assertFalse(server.requests.contains("/never.jar"));
        assertTrue(app.settings.getStringSet("subscriptions",Collections.emptySet()).contains("http://127.0.0.1:"+server.port()+"/collection.json"));
    }
    @Test public void failedDownloadShowsHttpStatusAndKeepsLibrary() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();app.settings.edit().putBoolean("simpleMode",true).commit();
        app.store.add(Collections.singletonList(new SourceEntry("保留频道","https://example.org/live.m3u8","朋友")));launch();
        openSubscription("http://127.0.0.1:"+server.port()+"/denied.txt");
        assertTrue(device.wait(Until.hasObject(By.text("订阅未导入")),5000));assertTrue(device.hasObject(By.textContains("HTTP 403")));assertEquals(1,app.store.all().size());screenshot("14-import-failure-status.png");
    }
    @Test public void redirectedPlaylistUsesFinalUrlAsBase() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();
        NetworkEngine.TextResponse document=app.network.fetchDocument("http://127.0.0.1:"+server.port()+"/redirect.m3u");
        assertTrue(document.url.endsWith("/folder/list.m3u"));
        ImportResult r=SubscriptionImporter.parse(document.text,document.url).playlist;
        assertEquals(1,r.entries.size());assertEquals("http://127.0.0.1:"+server.port()+"/folder/stream.m3u8",r.entries.get(0).url);
    }
    @Test public void singleChannelFormRoutesPlaylistUrlToImportPreview() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();launch();device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("导入直播源")),3000));device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000));
        List<UiObject2> fields=device.findObjects(By.clazz("android.widget.EditText"));assertEquals(3,fields.size());
        fields.get(0).setText("不要存成假频道");fields.get(2).setText("http://127.0.0.1:"+server.port()+"/friends.m3u");device.findObject(By.text("预览导入")).click();
        assertTrue(device.wait(Until.hasObject(By.text("导入预览")),5000));assertTrue(device.hasObject(By.textContains("识别 2 条")));assertEquals(0,app.store.all().size());
    }
    private void openSubscription(String url) throws Exception {
        if(!device.hasObject(By.text("一键导入"))){activate("家人设置","请家人帮忙设置");device.findObject(By.text("添加 / 导入频道")).click();}
        else activate("一键导入","一键导入频道");device.findObject(By.text("使用订阅链接")).click();
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000));device.findObject(By.clazz("android.widget.EditText")).setText(url);device.findObject(By.text("识别频道")).click();
    }
    @Test public void savedWebsiteReadsSourceAndUpdatesFromChangedLinkWithoutLosingFavorites() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();app.settings.edit().putBoolean("simpleMode",true).commit();
        String root="http://127.0.0.1:"+server.port()+"/mount.html";assertTrue(app.store.addWebsite(root));launch();
        activate("影视网站","选择网站 · 搜索影片 · 按确定播放");remoteReadSavedWebsite();
        assertTrue(device.wait(Until.hasObject(By.text("发现网页源链接 · 选择一次后记住")),5000));screenshot("15-website-source-discovery.png");device.findObject(By.text("我的直播源")).click();
        assertTrue(device.wait(Until.hasObject(By.text("导入预览")),5000));device.findObject(By.text("确认导入")).click();
        long end=SystemClock.elapsedRealtime()+3000;while(app.store.all().size()!=2&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
        assertEquals(2,app.store.all().size());app.store.favorite(app.store.all().get(0));assertTrue(app.settings.getStringSet("subscriptions",Collections.emptySet()).contains(root));
        assertTrue(device.wait(Until.hasObject(By.text("稍后观看")),3000));device.waitForIdle();device.pressDPadLeft();assertEquals("稍后观看",device.findObject(By.focused(true)).getText());device.pressDPadCenter();
        assertTrue(device.wait(Until.gone(By.text("频道已准备好")),3000));
        server.pageVersion=2;activate("影视网站","选择网站 · 搜索影片 · 按确定播放");remoteReadSavedWebsite();
        assertTrue(device.wait(Until.hasObject(By.text("更新结果")),8000));assertTrue(device.hasObject(By.textContains("读取 3 条，新增 1 条")));
        assertEquals(3,app.store.all().size());assertTrue(app.store.all().get(0).favorite);assertTrue(server.requests.contains("/mounted-v2.m3u"));assertTrue(Collections.frequency(server.requests,"/mount.html")>=2);screenshot("16-website-source-updated.png");
        assertEquals(2,new ImportBackup(app).load().size());
        device.waitForIdle();assertEquals("知道了",device.findObject(By.focused(true)).getText());device.pressDPadCenter();assertTrue(device.wait(Until.gone(By.text("更新结果")),3000));server.pageVersion=3;remoteReadSavedWebsite();
        assertTrue(device.wait(Until.hasObject(By.text("更新结果")),8000));assertTrue(device.hasObject(By.textContains("失败 1 个订阅")));
        assertEquals(3,app.store.all().size());assertTrue(app.store.all().get(0).favorite);assertEquals(2,new ImportBackup(app).load().size());assertTrue(device.hasObject(By.text("重新选择入口")));
    }
    private void remoteReadSavedWebsite() throws Exception {
        UiObject2 focused=device.findObject(By.focused(true));assertNotNull(focused);
        for(int i=0;i<4&&!"更多".equals(focused.getText());i++){device.pressDPadRight();focused=device.findObject(By.focused(true));assertNotNull(focused);}
        assertEquals("更多",focused.getText());device.pressDPadCenter();
        UiObject2 read=device.wait(Until.findObject(By.text("读取/更新源")),3000);assertNotNull(read);read.click();
    }
    private void screenshot(String file) {
        File dir=new File(app.getExternalFilesDir(null),"evidence");dir.mkdirs();assertTrue(device.takeScreenshot(new File(dir,file)));
    }
    @Test public void hiddenControlsDoNotConsumeVolumeKey() throws Exception {
        render("127.0.0.1","IPv4","17-volume-baseline.png");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {
            try {
                MainActivity activity=currentActivity();java.lang.reflect.Field f=MainActivity.class.getDeclaredField("playerControls");f.setAccessible(true);
                android.view.View controls=(android.view.View)f.get(activity);controls.setVisibility(android.view.View.GONE);
                activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_UP));
                assertEquals("Volume must not open controls or swallow first press",android.view.View.GONE,controls.getVisibility());
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
    }
    @Test public void elderlyHomeHasOneObviousWatchActionAndBackStopsPlayback() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();app.settings.edit().putBoolean("simpleMode",true).commit();
        app.store.add(Collections.singletonList(new SourceEntry("老人频道","http://127.0.0.1:"+server.port()+"/index.m3u8","测试")));
        launchSimple();assertEquals("看电视",device.findObject(By.focused(true)).getText());
        assertFalse(device.hasObject(By.textContains("IPv6")));assertFalse(device.hasObject(By.textContains("源测速")));
        app.renderedFirstFrame=0;device.pressDPadCenter();waitForFrame();
        assertTrue(device.hasObject(By.textContains("上下换台")));screenshot("18-elderly-playing.png");device.pressBack();
        assertTrue(device.wait(Until.hasObject(By.text("看电视")),3000));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {assertEquals(0,controller(currentActivity()).getMediaItemCount());});
        screenshot("19-elderly-home.png");
    }
    @Test public void importCompletionOffersWatchingAndSimpleRemoteSkipsAlternateRoutes() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();app.settings.edit().putBoolean("simpleMode",true).commit();launchSimple();
        openSubscription("http://127.0.0.1:"+server.port()+"/watch.m3u");
        assertTrue(device.wait(Until.hasObject(By.text("导入预览")),5000));device.findObject(By.text("确认导入")).click();
        assertTrue(device.wait(Until.hasObject(By.text("开始看电视")),3000));assertEquals("开始看电视",device.findObject(By.focused(true)).getText());screenshot("20-import-start-watching.png");
        app.renderedFirstFrame=0;device.pressDPadCenter();waitForFrame();device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("选择频道 · 共 2 个")),3000));
        assertEquals(1,device.findObjects(By.text("频道甲")).size());device.pressBack();device.waitForIdle();
        assertTrue(device.wait(Until.gone(By.text("选择频道 · 共 2 个")),3000));
        app.renderedFirstFrame=0;device.pressDPadDown();waitForFrame();assertTrue(server.requests.contains("/index2.m3u8"));
        device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("选择频道 · 共 2 个")),3000));
        assertTrue(device.findObject(By.text("频道乙")).isChecked());device.pressBack();device.waitForIdle();
        assertTrue("Closing channel picker should return to video",device.wait(Until.gone(By.text("选择频道 · 共 2 个")),3000));device.pressBack();device.waitForIdle();
        assertTrue(device.wait(Until.hasObject(By.text("看电视")),3000));
        assertTrue(device.hasObject(By.text("即将播放：频道乙")));
    }
    private void launchSimple() {
        app.startActivity(Intent.makeMainActivity(new ComponentName(app,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.text("看电视")),10000));device.waitForIdle();
    }
    @Test public void playbackRequestedWhileConnectingIsQueuedUntilForegroundReady() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();app.store.add(Collections.singletonList(new SourceEntry("等待初始化频道","http://127.0.0.1:"+server.port()+"/index.m3u8","测试")));launch();
        SystemClock.sleep(500);final MainActivity[] activity={null};final androidx.media3.session.MediaController[] saved={null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {
            try{activity[0]=currentActivity();saved[0]=controller(activity[0]);assertNotNull(saved[0]);java.lang.reflect.Field f=MainActivity.class.getDeclaredField("controller");f.setAccessible(true);f.set(activity[0],null);
                java.lang.reflect.Method play=MainActivity.class.getDeclaredMethod("play",Channel.class);play.setAccessible(true);play.invoke(activity[0],app.store.all().get(0));
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
        try{assertTrue(device.wait(Until.hasObject(By.text("正在打开电视…")),2000));}
        finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("controller");f.setAccessible(true);f.set(activity[0],saved[0]);}catch(Exception e){throw new AssertionError(e);}});}
        app.renderedFirstFrame=0;device.pressHome();device.waitForIdle();reopenFromLauncher();waitForFrame();
    }
    @Test public void returningFromLauncherResumesOnlyAnAutomaticallyPausedChannel() throws Exception {
        render("127.0.0.1","IPv4","21-resume-baseline.png");device.pressHome();SystemClock.sleep(400);
        device.waitForIdle();reopenFromLauncher();
        assertTrue(device.wait(Until.hasObject(By.text("本地合成测试流")),5000));SystemClock.sleep(300);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertTrue("Returning from launcher must resume auto-pause",controller(currentActivity()).getPlayWhenReady()));
        device.pressHome();
    }
    @Test public void navigatingAwayCancelsQueuedPlayback() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();app.store.add(Collections.singletonList(new SourceEntry("取消等待频道","http://127.0.0.1:"+server.port()+"/index.m3u8","测试")));launch();SystemClock.sleep(500);
        final MainActivity[] activity={null};final androidx.media3.session.MediaController[] saved={null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {
            try{activity[0]=currentActivity();saved[0]=controller(activity[0]);assertNotNull(saved[0]);java.lang.reflect.Field f=MainActivity.class.getDeclaredField("controller");f.setAccessible(true);f.set(activity[0],null);
                java.lang.reflect.Method play=MainActivity.class.getDeclaredMethod("play",Channel.class);play.setAccessible(true);play.invoke(activity[0],app.store.all().get(0));
                java.lang.reflect.Method sites=MainActivity.class.getDeclaredMethod("websites");sites.setAccessible(true);sites.invoke(activity[0]);f.set(activity[0],saved[0]);
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
        app.renderedFirstFrame=0;device.pressHome();device.waitForIdle();reopenFromLauncher();
        assertTrue(device.wait(Until.hasObject(By.text("影视网站")),3000));SystemClock.sleep(400);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertEquals(0,controller(currentActivity()).getMediaItemCount()));assertEquals(0,app.renderedFirstFrame);
    }
    @Test public void elderlyPlaybackKeepsAllOptionsAndManualPauseSurvivesHome() throws Exception {
        app.settings.edit().putBoolean("simpleMode",true).commit();render("127.0.0.1","IPv4","22-elderly-playing.png");
        device.pressMenu();assertTrue(device.wait(Until.hasObject(By.text("更多播放设置")),3000));device.findObject(By.text("更多播放设置")).click();
        assertTrue(device.wait(Until.hasObject(By.text("播放设置")),3000));
        for(String label:new String[]{"切换线路","清晰度","音轨","画面比例"})assertTrue(device.hasObject(By.text(label)));
        screenshot("23-full-playback-options.png");device.findObject(By.text("画面比例")).click();assertTrue(device.wait(Until.hasObject(By.text("拉伸铺满")),3000));device.findObject(By.text("拉伸铺满")).click();
        device.pressMenu();device.findObject(By.text("暂停")).click();device.pressHome();
        device.waitForIdle();reopenFromLauncher();
        assertTrue(device.wait(Until.hasObject(By.text("本地合成测试流")),3000));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertFalse("A user pause must not auto-resume",controller(currentActivity()).getPlayWhenReady()));
        device.pressMenu();assertTrue(device.wait(Until.hasObject(By.text("继续播放")),3000));assertTrue(device.hasObject(By.text("关闭菜单")));device.findObject(By.text("继续播放")).click();
        final boolean[] resumed={false};long end=SystemClock.elapsedRealtime()+3000;while(!resumed[0]&&SystemClock.elapsedRealtime()<end){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->resumed[0]=controller(currentActivity()).getPlayWhenReady());SystemClock.sleep(50);}assertTrue(resumed[0]);device.pressBack();
    }
    private void reopenFromLauncher() throws Exception {
        device.executeShellCommand("am start -n tv.clearstream/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 270532608");
        assertTrue("Application must return to foreground",device.wait(Until.hasObject(By.pkg(BuildConfig.APPLICATION_ID)),10000));device.waitForIdle();
    }
    private void waitForFrame() throws Exception {
        long end=SystemClock.elapsedRealtime()+20000;while(app.renderedFirstFrame==0&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
        assertTrue("Expected actual HLS first frame",app.renderedFirstFrame>0);
    }
    @Test public void cctvEntryAndSearchMergeSubscriptionsAndKeepMultipleRoutes() throws Exception {
        server=new FixtureServer("127.0.0.1");server.start();app.settings.edit().putBoolean("simpleMode",true).commit();
        String base="http://127.0.0.1:"+server.port();
        app.store.add(Arrays.asList(new SourceEntry("CCTV-1 综合HD",base+"/index.m3u8","源甲"),new SourceEntry("央视一套",base+"/index2.m3u8","源乙"),new SourceEntry("CCTV13",base+"/index3.m3u8","源乙"),new SourceEntry("湖南卫视高清",base+"/index4.m3u8","源甲"),new SourceEntry("湖南卫视HD",base+"/index5.m3u8","源乙")));
        assertEquals(3,ChannelGuide.programs(app.store.all(),null).size());assertEquals(2,ChannelGuide.lines(app.store.all(),app.store.all().get(0)).size());launchSimple();
        app.renderedFirstFrame=0;activate("央视直播","CCTV-1 综合");waitForFrame();device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("央视频道 · 共 2 个")),3000));assertFalse(device.hasObject(By.textContains("湖南")));screenshot("24-cctv-grouped.png");device.pressBack();assertTrue(device.wait(Until.gone(By.text("央视频道 · 共 2 个")),3000));
        app.renderedFirstFrame=0;device.pressDPadDown();waitForFrame();assertTrue(server.requests.contains("/index3.m3u8"));device.pressBack();
        activate("搜台","输入频道名或台号，例如 CCTV1、央视一套、湖南");assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000));device.findObject(By.clazz("android.widget.EditText")).setText("央视一套");
        assertTrue(device.wait(Until.hasObject(By.text("找到 1 个频道")),5000));assertTrue(device.hasObject(By.descContains("CCTV-1 综合，2 条线路")));screenshot("25-channel-search.png");
        UiObject2 search=device.findObject(By.text("搜索"));search.click();device.waitForIdle();
        UiObject2 result=device.findObject(By.descContains("CCTV-1 综合，2 条线路"));result.click();device.waitForIdle();if(!device.hasObject(By.text("CCTV-1 综合")))device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("CCTV-1 综合")),3000));
        device.pressMenu();assertTrue(device.wait(Until.hasObject(By.text("更多播放设置")),3000));device.findObject(By.text("更多播放设置")).click();assertTrue(device.wait(Until.hasObject(By.text("切换线路")),3000));device.findObject(By.text("切换线路")).click();
        assertTrue(device.wait(Until.hasObject(By.text("切换线路")),3000));assertTrue(device.hasObject(By.textContains("线路 2")));screenshot("26-merged-lines.png");
        app.renderedFirstFrame=0;device.findObject(By.textContains("线路 2")).click();
        long routeDeadline=SystemClock.elapsedRealtime()+20000;
        while((app.renderedFirstFrame==0||!server.requests.contains("/index2.m3u8")||!base.concat("/index2.m3u8").equals(app.settings.getString("lastWatchedUrl","")))&&SystemClock.elapsedRealtime()<routeDeadline)SystemClock.sleep(100);
        assertTrue("Expected the selected route request, first frame and recent URL; requests="+server.requests+", recent="+app.settings.getString("lastWatchedUrl",""),app.renderedFirstFrame>0&&server.requests.contains("/index2.m3u8")&&base.concat("/index2.m3u8").equals(app.settings.getString("lastWatchedUrl","")));
    }
    @Test public void channelCatalogShowsOneCardPerStationAndFavoriteOnAnyRoute() throws Exception {
        app.settings.edit().putBoolean("simpleMode",true).commit();
        app.store.add(Arrays.asList(new SourceEntry("CCTV1","https://example.org/1","源甲"),new SourceEntry("央视一套","https://example.org/2","源乙"),new SourceEntry("湖南卫视HD","https://example.org/3","源甲"),new SourceEntry("湖南卫视高清","https://example.org/4","源乙"),new SourceEntry("江苏卫视","https://example.org/5","源甲"),new SourceEntry("新闻综合频道","https://example.org/6","默认"),new SourceEntry("新闻综合频道","https://example.org/7","默认")));
        app.store.favorite(app.store.all().get(1));launchSimple();activate("选频道","分类选台");
        assertTrue(device.wait(Until.hasObject(By.text("全部 · 5 个频道")),5000));assertEquals(1,device.findObjects(By.descContains("CCTV-1 综合，2 条线路")).size());screenshot("27-channel-catalog.png");
        activate("卫视","卫视 · 2 个频道");assertFalse(device.hasObject(By.descContains("CCTV-1 综合，2 条线路")));
        activate("收藏","收藏 · 1 个频道");assertTrue(device.hasObject(By.descContains("CCTV-1 综合，2 条线路")));screenshot("28-favorites-merged.png");
        activate("地方/其他","地方/其他 · 2 个频道");assertEquals(2,device.findObjects(By.descContains("新闻综合频道，1 条线路")).size());
    }
    private MainActivity currentActivity() {
        for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof MainActivity)return (MainActivity)a;
        throw new AssertionError("No foreground MainActivity");
    }
    private androidx.media3.session.MediaController controller(MainActivity a) {
        try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("controller");f.setAccessible(true);return (androidx.media3.session.MediaController)f.get(a);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private final class FixtureServer implements AutoCloseable {
        private final ServerSocket socket;
        private volatile boolean running=true;
        volatile int pageVersion=1;
        final List<String> requests=Collections.synchronizedList(new ArrayList<>());
        FixtureServer(String address) throws Exception {socket=new ServerSocket();socket.setReuseAddress(true);socket.bind(new InetSocketAddress(InetAddress.getByName(address),0));}
        int port(){return socket.getLocalPort();}
        void start() {
            Thread thread=new Thread(()-> {
                while(running)try {Socket client=socket.accept();new Thread(()->serve(client),"fixture-client").start();}catch(IOException ignored){}
            },"fixture-server");thread.setDaemon(true);thread.start();
        }
        void serve(Socket client) {
            try(Socket c=client) {
                c.setSoTimeout(3000);BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream()));String first=reader.readLine();if(first==null)return;
                String path=first.split(" ")[1];requests.add(path);String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                String name=path.substring(1);if(name.contains(".."))return;
                if(name.equals("denied.txt")){c.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));return;}
                if(name.equals("redirect.m3u")){c.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /folder/list.m3u\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));return;}
                byte[] data;
                if(name.equals("watch.m3u"))data=("#EXTM3U\n#EXTINF:-1 group-title=\"测试\",频道甲\nhttp://127.0.0.1:"+port()+"/index.m3u8\n#EXTINF:-1 group-title=\"测试\",频道甲\nhttp://127.0.0.1:"+port()+"/index.m3u8?alt=1\n#EXTINF:-1 group-title=\"测试\",频道乙\nhttp://127.0.0.1:"+port()+"/index2.m3u8\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.matches("index[2-5]\\.m3u8")){try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("fixture/index.m3u8")){data=NetworkEngine.readBounded(in,2*1024*1024,true);}}
                else if(name.equals("mount.html"))data=("<html><a href='/mounted-v"+pageVersion+".m3u'>我的直播源</a><a href='/about'>网站介绍</a></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.equals("mounted-v1.m3u")||name.equals("mounted-v2.m3u"))data=("#EXTM3U\n#EXTINF:-1,频道甲\nhttps://example.org/a.m3u8\n#EXTINF:-1,频道乙\nhttps://example.org/b.m3u8\n"+(name.equals("mounted-v2.m3u")?"#EXTINF:-1,新增频道\nhttps://example.org/c.m3u8\n":"")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.equals("mounted-v3.m3u"))data="<html>下载入口已变更</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.equals("collection.json"))data=("{\"urls\":[{\"name\":\"测试线路\",\"url\":\"./liveconfig.json\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.equals("liveconfig.json"))data=("{\"spider\":\"./never.jar\",\"lives\":[{\"name\":\"朋友直播\",\"url\":\"./friends.m3u\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.equals("folder/list.m3u"))data=("#EXTM3U\n#EXTINF:-1,相对频道\nstream.m3u8\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.equals("tvportal.html"))data=("<!doctype html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>网页适配测试</title></head><body style='background:#14202f;color:white;margin:40px'><nav><a id='first' href='#news' style='font-size:10px;color:white'>新闻</a><a id='second' href='#films' style='color:white'>影视</a></nav><div><button id='play' style='margin-left:80px;width:120px' onclick=\"this.textContent='遥控适配成功'\">播放测试</button></div><form style='margin-top:60px'><input placeholder='保留原有登录框'></form></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.equals("portal.html"))data=("<!doctype html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>电视网页测试</title></head><body style='margin:0;height:100vh;background:#14202f;color:white;display:flex;align-items:center;justify-content:center'><button style='width:360px;height:120px;font-size:32px' onclick=\"this.textContent='遥控点击成功'\">按确定测试</button></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else if(name.equals("friends.m3u"))data=("#EXTM3U\n#EXTINF:-1 group-title=\"朋友准备\",频道甲\nhttp://[2001:db8::10]/a.m3u8\n#EXTINF:-1,频道乙\nhttps://example.org/b.m3u8\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                else try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("fixture/"+name)) {data=NetworkEngine.readBounded(in,2*1024*1024,true);}
                String type=name.endsWith(".html")?"text/html; charset=UTF-8":name.endsWith(".m3u8")?"application/vnd.apple.mpegurl":"video/mp2t";
                c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                c.getOutputStream().write(data);c.getOutputStream().flush();
            }catch(Exception e){System.out.println("fixture: "+e.getClass().getSimpleName());}
        }
        @Override public void close() throws IOException {running=false;socket.close();}
    }
}
