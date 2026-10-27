package tv.clearstream;

import android.content.*;
import android.os.*;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class SitePortalTest {
    @Test public void harmlessAddressNoticeDoesNotHideFilmsButVerificationStillBlocksReading()throws Exception{
        org.junit.Assume.assumeTrue(provider());
        app.startActivity(new Intent(app,WebActivity.class).putExtra("url",server.url("/unknown")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.textContains("周末电影")),10000));
        String script;try(InputStream in=app.getAssets().open("site-reader.js")){script=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);}
        String film="<article><a href='/movie/77'><img src='/poster.png' alt='真正影片' width=120 height=160></a></article>";
        JSONObject visible=readHtml(film+"<div role='dialog' aria-modal='true'><h2>收藏最新发布页 · 防止走丢</h2><a href='/movie/fake'>最新发布页</a><button>我知道了</button></div>",script);
        assertEquals("Ordinary address announcements must not remove the native film list",1,visible.getJSONArray("items").length());assertEquals("真正影片",visible.getJSONArray("items").getJSONObject(0).getString("title"));
        JSONObject blocked=readHtml(film+"<div role='dialog' aria-modal='true'><h2>请登录完成安全验证</h2><input type=password><button>我知道了</button></div>",script);
        assertEquals(0,blocked.getJSONArray("items").length());assertTrue(blocked.optString("notice").contains("弹窗"));
    }
    @Test public void plainAnnouncementAndMacCmsRouteTabsAreRemoteTargets()throws Exception{
        org.junit.Assume.assumeTrue(provider());app.startActivity(new Intent(app,WebActivity.class).putExtra("url",server.url("/unknown")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.textContains("周末电影")),10000));
        readHtml("<div id='notice-ok' class='popup-btn close-pop' onclick=\"document.title='公告已确认'\" style='width:220px;height:70px'>好的，我记住啦</div><div id='route-tab' class='module-tab-item tab-item' style='width:220px;height:70px'>无广告1</div>","({items:[]})");
        String script;try(InputStream in=app.getAssets().open("tv-adapter.js")){script=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);}evaluate(script);
        assertEquals("\"1\"",evaluate("document.getElementById('notice-ok').getAttribute('data-cs-target')"));assertEquals("\"1\"",evaluate("document.getElementById('route-tab').getAttribute('data-cs-target')"));
        device.pressDPadCenter();long until=SystemClock.elapsedRealtime()+3000;String title="";while(SystemClock.elapsedRealtime()<until){title=evaluate("document.title");if(title.contains("公告已确认"))break;SystemClock.sleep(100);}assertTrue(title,title.contains("公告已确认"));
    }
    @Rule public org.junit.rules.TestName testName=new org.junit.rules.TestName();
    private ClearApp app;
    private UiDevice device;
    private Server server;
    @Before public void prepare() throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.pressHome();
        app.stopService(new Intent(app,PlaybackService.class));app.store.replaceAll(Collections.emptyList());
        for(Website site:app.store.websites())app.store.deleteWebsite(site);
        app.settings.edit().clear().putBoolean("simpleMode",true).commit();app.network.refreshPolicy();
        server=new Server();server.start();
    }
    @After public void cleanup() throws Exception {
        System.out.println("site-fixture-requests: "+server.requests);
        screenshot(testName.getMethodName()+"-end.png");File dir=new File(app.getExternalFilesDir(null),"evidence");device.dumpWindowHierarchy(new File(dir,testName.getMethodName()+".xml"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity)try {
            for(String name:new String[]{"currentUrl","detectedTemplate","generation","loading"}){java.lang.reflect.Field f=SiteActivity.class.getDeclaredField(name);f.setAccessible(true);System.out.println("site-state "+name+"="+f.get(a));}
        }catch(Exception ignored){}});
        device.pressBack();server.close();
    }
    private boolean provider(){return Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null;}
    @Test public void dynamicCardsArrivingAfterEightSecondsRemainObservable()throws Exception {assertLateCards("/latecards",false);}
    @Test public void redirectedLateCardsUseTheActiveDocumentRatherThanTheOriginalUrl()throws Exception {assertLateCards("/late-redirect",true);}
    private void assertLateCards(String path,boolean redirected)throws Exception {
        Assume.assumeTrue(provider());launchPortal(path);
        assertTrue("The initial empty five-second scan must not end the full observation window",device.wait(Until.hasObject(By.desc("网站内容：八秒后到达的影片")),15000));
        assertFalse(device.hasObject(By.textContains("读取超时")));
        if(redirected){assertTrue(server.requests.toString(),server.requests.contains("/late-redirect"));assertTrue(server.requests.toString(),server.requests.contains("/latecards"));}
        final String[] active={""};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(activity instanceof SiteActivity)try{java.lang.reflect.Field field=SiteActivity.class.getDeclaredField("currentUrl");field.setAccessible(true);active[0]=(String)field.get(activity);}catch(ReflectiveOperationException error){throw new AssertionError(error);}});
        assertTrue("Metadata must belong to the actual redirected document",active[0].endsWith("/latecards"));screenshot(redirected?"late-card-after-redirect.png":"late-card-after-empty-final-scan.png");
    }
    @Test public void decorativeNavigationImagesNeverBecomeFilmsAndRealPostersStillWork()throws Exception {
        Assume.assumeTrue(provider());app.startActivity(new Intent(app,WebActivity.class).putExtra("url",server.url("/unknown")).putExtra("name","装饰与影片边界").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));assertTrue(device.wait(Until.hasObject(By.textContains("周末电影")),10000));
        String script;try(InputStream in=app.getAssets().open("site-reader.js")){script=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);}
        String html="<article class='movie-item'><a href='/movie/77'><img src='/poster.png' alt='真正影片' width='120' height='160'></a><p>影片公开简介</p></article><aside style='position:fixed;right:0;bottom:0'><a href='/movie/decoration'><img src='/girls/girl_16.webp' alt='decoration' width='40' height='40'></a></aside><div class='grid'><a href='https://external.invalid/movie/' target='_blank' class='group relative block rounded-xl'><div class='absolute pointer-events-none'><img alt='decoration' src='/girls/girl_1.webp' width='128' height='200'></div><img alt='外部网站 logo' src='/favicon.ico' width='32' height='32'><h3>外部网站</h3><p>网站导航简介</p><div>https://external.invalid/movie/</div></a></div>";
        JSONObject result=readHtml(html,script);assertEquals("Decorative art and website directory links must not be film previews",1,result.getJSONArray("items").length());JSONObject film=result.getJSONArray("items").getJSONObject(0);assertEquals("真正影片",film.getString("title"));assertTrue(film.getString("url").endsWith("/movie/77"));assertTrue(film.getString("image").endsWith("/poster.png"));
    }
    private void launchPortal(String path) {
        app.store.addWebsite(server.url(path));
        app.startActivity(Intent.makeMainActivity(new ComponentName(app,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.text("影视网站")),10000));
        activate(By.text("影视网站"),By.text("选择网站 · 搜索影片 · 按确定播放"));
        UiObject2 card=device.findObject(By.textContains("127.0.0.1"));assertNotNull(card);card.click();device.waitForIdle();
        if(!device.hasObject(By.text("站内搜索 · 遥控器选片")))device.pressDPadCenter();
        assertTrue("Website card should open its independent native TV space",device.wait(Until.hasObject(By.text("站内搜索 · 遥控器选片")),7000));
    }
    private void activate(BySelector target,BySelector arrived) {
        UiObject2 button=device.wait(Until.findObject(target),5000);assertNotNull(button);button.click();device.waitForIdle();
        if(!device.hasObject(arrived))device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(arrived),5000));
    }
    private void search(String query) {
        UiObject2 input=device.findObject(By.res("tv.clearstream:id/site_query"));assertNotNull(input);input.setText(query);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity)a.findViewById(tv.clearstream.R.id.site_search).requestFocus();});
        device.pressDPadCenter();
    }
    @Test public void savedWebsiteHasNativeSearchAndDetailReturnsToResultFocus() throws Exception {
        launchPortal("/home");
        if(!provider()) {
            assertTrue(device.wait(Until.hasObject(By.textContains("设备缺少可用网页组件")),5000));
            assertTrue(device.hasObject(By.text("外部打开")));screenshot("29-site-no-webview.png");return;
        }
        assertTrue(device.wait(Until.hasObject(By.desc("网站内容：周末电影")),10000));screenshot("29-site-home.png");
        search("三体 &+#");assertTrue(device.wait(Until.hasObject(By.desc("网站内容：三体 &+# · 第一部")),10000));
        assertTrue(server.requests.toString().contains("q=%E4%B8%89%E4%BD%93+%26%2B%23"));assertTrue(server.requests.toString().contains("lang=zh"));
        assertTrue(device.hasObject(By.text("找到 2 项内容")));screenshot("30-site-native-search.png");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity){ArrayList<android.view.View> cards=new ArrayList<>();a.getWindow().getDecorView().findViewsWithText(cards,"网站内容：三体 &+# · 第一部",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);assertEquals(1,cards.size());cards.get(0).requestFocus();}});device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("影视预览")),5000));assertEquals("进入网站播放",device.findObject(By.focused(true)).getText());
        screenshot("34-site-film-preview.png");device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("网站详情 · 第一部")),10000));screenshot("31-site-original-detail.png");
        device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("影视预览")),5000));device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("站内搜索 · 遥控器选片")),5000));
        assertEquals("三体 &+#",device.findObject(By.res("tv.clearstream:id/site_query")).getText());
        assertEquals("网站内容：三体 &+# · 第一部",device.findObject(By.focused(true)).getContentDescription());
    }
    @Test public void categoryThumbnailCardsRetainCoverPolicyAndExcludeCategoryNavigation() throws Exception {
        org.junit.Assume.assumeTrue(provider());
        app.startActivity(new Intent(app,WebActivity.class).putExtra("url",server.url("/unknown")).putExtra("name","分类结构回归").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.textContains("周末电影")),10000));
        String script;try(InputStream in=app.getAssets().open("site-reader.js")){script=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);}
        JSONObject value=readHtml("<ul><li><a class=thumbnail href='/dm/12345.html' target=_blank><img src='/poster.png' alt='分类测试影片' referrerpolicy=no-referrer><div>第8集</div></a><h2><a href='/dm/12345.html'>分类测试影片</a></h2><footer>8.0</footer></li><li><a class=thumbnail href='/dm/0-0-0-0?page=2'>下一页</a></li><li><a href='/dm/999.html'>没有封面的推荐链接</a></li></ul>",script);
        assertEquals("Numeric category detail links with a real thumbnail are content",1,value.getJSONArray("items").length());
        JSONObject card=value.getJSONArray("items").getJSONObject(0);assertEquals("分类测试影片",card.getString("title"));assertTrue(card.getString("url").endsWith("/dm/12345.html"));assertEquals("no-referrer",card.getString("referrerPolicy"));
        JSONObject publicFilm=new JSONObject().put("vodId",77).put("vodName","元数据测试影片").put("vodPic",server.url("/poster.png")).put("vodBlurb","网页公开简介");
        String flight=new JSONArray().put(1).put("8:"+new JSONArray().put(publicFilm)).toString();
        JSONObject hydrated=readHtml("<a href='/detail/77' class='content-card'><img src='/site-logo.png' alt='元数据测试影片'></a><script>self.__next_f.push("+flight+")</script>",script);
        assertEquals(1,hydrated.getJSONArray("items").length());JSONObject actualCover=hydrated.getJSONArray("items").getJSONObject(0);
        assertTrue("Published page JSON must enrich a DOM-matched card rather than retaining its site-logo placeholder",actualCover.getString("image").endsWith("/poster.png"));assertEquals("网页公开简介",actualCover.getString("summary"));
    }
    @Test public void publicReaderFiltersNavigationHiddenAndDuplicateLinksAndReadsGetForm() throws Exception {
        org.junit.Assume.assumeTrue(provider());
        app.startActivity(new Intent(app,WebActivity.class).putExtra("url",server.url("/home")).putExtra("name","读取测试").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.textContains("周末电影")),10000));
        String script;try(InputStream in=app.getAssets().open("site-reader.js")){script=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);}
        JSONObject value=new JSONObject(evaluate(script));
        assertEquals(1,value.getJSONArray("items").length());assertEquals("周末电影",value.getJSONArray("items").getJSONObject(0).getString("title"));
        assertEquals("q",value.getJSONObject("form").getString("query"));assertEquals("get",value.getJSONObject("form").getString("method"));
        assertTrue(value.getJSONObject("form").getJSONArray("fixed").toString().contains("zh"));
        assertTrue(value.getJSONArray("items").getJSONObject(0).getString("url").contains("sig=a%2Bb"));
        assertTrue(value.getJSONArray("items").getJSONObject(0).getString("image").endsWith("/poster.png"));
        assertEquals("这是网站提供的影片简介。",value.getJSONArray("items").getJSONObject(0).getString("summary"));
    }
    @Test public void directoryDivLinksBecomeRemoteTargetsWithoutBeingImportedAsFilms() throws Exception {
        org.junit.Assume.assumeTrue(provider());
        app.startActivity(new Intent(app,WebActivity.class).putExtra("url",server.url("/unknown")).putExtra("name","导航遥控复核").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.textContains("周末电影")),10000));
        readHtml("<div id=directory-link data-url='https://example.org/' onclick=\"document.title='导航已选择'\" style='width:220px;height:90px;background:#456'>测试网站入口</div>","({items:[]})");
        String adapter;try(InputStream in=app.getAssets().open("tv-adapter.js")){adapter=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);}
        evaluate(adapter);assertEquals("\"1\"",evaluate("document.getElementById('directory-link').getAttribute('data-cs-target')"));
        device.pressDPadCenter();long end=SystemClock.elapsedRealtime()+3000;String title="";while(SystemClock.elapsedRealtime()<end){title=evaluate("document.title");if(title.contains("导航已选择"))break;SystemClock.sleep(100);}assertTrue(title,title.contains("导航已选择"));
    }
    @Test public void nativePreviewActuallyDecodesWebsitePosterAndDisplaysItsSummary() throws Exception {
        org.junit.Assume.assumeTrue(provider());launchPortal("/home");
        assertTrue(device.wait(Until.hasObject(By.desc("网站内容：周末电影")),10000));
        assertTrue("Website cover must appear in native result cards",device.wait(Until.hasObject(By.desc("封面：周末电影")),8000));
        activate(By.desc("网站内容：周末电影"),By.text("影视预览"));
        assertTrue(device.hasObject(By.text("这是网站提供的影片简介。")));
        long until=SystemClock.elapsedRealtime()+8000;boolean decoded=false;
        while(!decoded&&SystemClock.elapsedRealtime()<until){final boolean[] ready={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity){android.widget.ImageView poster=a.findViewById(R.id.site_preview_poster);if(poster!=null&&poster.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)ready[0]=((android.graphics.drawable.BitmapDrawable)poster.getDrawable()).getBitmap().getPixel(0,0)==0xff64b6f7;}});decoded=ready[0];if(!decoded)SystemClock.sleep(100);}
        assertTrue("Must display the downloaded PNG rather than a static placeholder",decoded);assertTrue(server.requests.contains("/poster.png"));screenshot("35-site-poster-preview.png");
        device.pressBack();assertEquals("网站内容：周末电影",device.findObject(By.focused(true)).getContentDescription());
    }
    @Test public void filmCanBeFavoritedFromPreviewAndFoundAfterReopeningWebsite() throws Exception {
        org.junit.Assume.assumeTrue(provider());app.settings.edit().remove("siteBookmarks").commit();launchPortal("/home");
        assertTrue(device.wait(Until.hasObject(By.desc("网站内容：周末电影")),10000));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity){ArrayList<android.view.View> matches=new ArrayList<>();a.getWindow().getDecorView().findViewsWithText(matches,"网站内容：周末电影",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);assertEquals(1,matches.size());matches.get(0).requestFocus();}});device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("影视预览")),5000));device.pressDPadRight();assertEquals("收藏影片",device.findObject(By.focused(true)).getText());device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("取消收藏")),3000));assertEquals(1,new SiteBookmarks(app.settings).films(server.url("/home")).size());screenshot("43-film-favorited.png");
        app.startActivity(new Intent(app,SiteActivity.class).putExtra("url",server.url("/home")).putExtra("name","重开网站").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.text("站内搜索 · 遥控器选片")),5000));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity){ArrayList<android.view.View> matches=new ArrayList<>();a.getWindow().getDecorView().findViewsWithText(matches,"收藏",android.view.View.FIND_VIEWS_WITH_TEXT);for(android.view.View v:matches)if(v instanceof android.widget.TextView&&"收藏".contentEquals(((android.widget.TextView)v).getText())){v.requestFocus();break;}}});device.pressDPadCenter();
        assertTrue(device.wait(Until.hasObject(By.text("我的影片收藏")),3000));assertTrue(device.hasObject(By.text("周末电影")));screenshot("44-film-favorites-list.png");device.pressBack();app.settings.edit().remove("siteBookmarks").commit();
    }
    @Test public void brokenOrOversizePostersKeepPreviewAndPlayUsable() throws Exception {
        org.junit.Assume.assumeTrue(provider());launchPortal("/badposters");
        assertTrue(device.wait(Until.hasObject(By.desc("网站内容：损坏封面影片")),10000));
        activate(By.desc("网站内容：损坏封面影片"),By.text("影视预览"));
        assertTrue(device.hasObject(By.res("tv.clearstream:id/site_preview_placeholder").text("暂无封面")));
        assertEquals("进入网站播放",device.findObject(By.focused(true)).getText());device.pressBack();
        activate(By.desc("网站内容：超大封面影片"),By.text("影视预览"));
        assertTrue(device.hasObject(By.res("tv.clearstream:id/site_preview_placeholder").text("暂无封面")));screenshot("36-site-cover-fallback.png");
        device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("网站详情 · 第一部")),10000));
    }
    @Test public void coverDownloadsAreBoundedAndCannotUpdateViewsAfterClose() throws Exception {
        final SiteImages[] loader={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->loader[0]=new SiteImages(app,new Handler(Looper.getMainLooper())));
        try {
            for(String path:new String[]{"/poster.png","/broken.png","/oversize.png"}) {
                java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);final boolean[] success={false};final android.widget.ImageView[] view={null};
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{view[0]=new android.widget.ImageView(app);loader[0].bind(view[0],server.url(path),server.url("/home"),"TV test",()->true,ok->{success[0]=ok;done.countDown();});});
                assertTrue("Bounded cover request should complete",done.await(8,java.util.concurrent.TimeUnit.SECONDS));assertEquals(path,path.equals("/poster.png"),success[0]);
            }
            java.util.concurrent.CountDownLatch policyDone=new java.util.concurrent.CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->loader[0].bind(new android.widget.ImageView(app),server.url("/poster.png?policy=no-referrer"),server.url("/home"),"TV test","no-referrer",()->true,ok->{assertTrue(ok);policyDone.countDown();}));
            assertTrue(policyDone.await(8,java.util.concurrent.TimeUnit.SECONDS));assertEquals("",server.referrers.get("/poster.png?policy=no-referrer"));
            final android.widget.ImageView[] late={null};java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{late[0]=new android.widget.ImageView(app);loader[0].bind(late[0],server.url("/slow-poster.png"),server.url("/home"),"TV test",()->true,ok->callbacks.incrementAndGet());});
            long until=SystemClock.elapsedRealtime()+3000;while(!server.requests.contains("/slow-poster.png")&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
            assertTrue(server.requests.contains("/slow-poster.png"));InstrumentationRegistry.getInstrumentation().runOnMainSync(()->loader[0].close());SystemClock.sleep(1400);
            assertEquals(0,callbacks.get());assertNull(late[0].getDrawable());
        }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->loader[0].close());}
    }
    @Test public void longPreviewAtLargeFontKeepsPlayButtonFullyVisible() throws Exception {
        org.junit.Assume.assumeTrue(provider());String original=device.executeShellCommand("settings get system font_scale").trim();
        try {
            device.executeShellCommand("wm size 960x540");device.executeShellCommand("settings put system font_scale 1.5");launchPortal("/longpreview");
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("网站内容：长标题")),10000));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity){ArrayList<android.view.View> cards=new ArrayList<>();a.getWindow().getDecorView().findViewsWithText(cards,"网站内容：长标题",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);assertFalse(cards.isEmpty());cards.get(0).requestFocus();}});device.pressDPadCenter();
            assertTrue(device.wait(Until.hasObject(By.text("影视预览")),5000));
            final boolean[] visible={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity)try{java.lang.reflect.Field f=SiteActivity.class.getDeclaredField("previewPlay");f.setAccessible(true);android.view.View play=(android.view.View)f.get(a);int[] xy=new int[2];play.getLocationOnScreen(xy);visible[0]=play.getHeight()>0&&xy[1]>=0&&xy[1]+play.getHeight()<=a.getResources().getDisplayMetrics().heightPixels;}catch(Exception e){throw new AssertionError(e);}});
            assertTrue("Default play focus must remain completely inside the TV screen",visible[0]);assertEquals("进入网站播放",device.findObject(By.focused(true)).getText());screenshot("37-site-large-font-preview.png");
            final boolean[] backVisible={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity)try{java.lang.reflect.Field f=SiteActivity.class.getDeclaredField("previewPanel");f.setAccessible(true);android.view.View panel=(android.view.View)f.get(a);ArrayList<android.view.View> found=new ArrayList<>();panel.findViewsWithText(found,"返回选片",android.view.View.FIND_VIEWS_WITH_TEXT);if(!found.isEmpty()){android.view.View back=found.get(0);int[] xy=new int[2];back.getLocationOnScreen(xy);backVisible[0]=back.getHeight()>0&&xy[1]>=0&&xy[1]+back.getHeight()<=a.getResources().getDisplayMetrics().heightPixels;}}catch(Exception e){throw new AssertionError(e);}});
            assertTrue("Preview return action must also remain completely visible",backVisible[0]);
            assertTrue("Long website descriptions must remain scrollable in preview",device.hasObject(By.scrollable(true)));
        }finally{device.executeShellCommand("wm size reset");device.executeShellCommand(original.equals("null")?"settings delete system font_scale":"settings put system font_scale "+original);}
    }
    @Test public void rapidSearchKeepsNewResultsAndEmptyPageOffersOriginalWebsite() throws Exception {
        org.junit.Assume.assumeTrue(provider());launchPortal("/home");
        assertTrue(device.wait(Until.hasObject(By.desc("网站内容：周末电影")),10000));
        search("OLD");search("FAST");assertTrue(device.wait(Until.hasObject(By.desc("网站内容：FAST · 第一部")),10000));
        SystemClock.sleep(1800);assertFalse(device.hasObject(By.desc("网站内容：OLD · 第一部")));
        search("EMPTY");assertTrue(device.wait(Until.hasObject(By.textContains("暂时没有可读取的影视卡片")),10000));
        assertFalse(device.hasObject(By.desc("网站内容：FAST · 第一部")));assertTrue(device.hasObject(By.text("原网页")));screenshot("32-site-empty-fallback.png");
    }
    @Test public void readerRejectsAmbiguousFormsAndNavigationButKeepsRealContent() throws Exception {
        org.junit.Assume.assumeTrue(provider());
        app.startActivity(new Intent(app,WebActivity.class).putExtra("url",server.url("/unknown")).putExtra("name","边界读取测试").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.textContains("周末电影")),10000));
        String script;try(InputStream in=app.getAssets().open("site-reader.js")){script=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);}
        String[] unsupported={
            "<form><input type=search name=q><input type=password name=p></form>",
            "<form><input type=search name=q><input type=file name=f></form>",
            "<form><fieldset disabled><input type=search name=q></fieldset></form>",
            "<form action=/wrong><input type=search name=q><button formaction=/search name=scope value=film>搜索</button></form>"};
        for(String html:unsupported)assertTrue(html,readHtml(html,script).isNull("form"));
        JSONObject fields=readHtml("<form><input type=checkbox name=q><input type=search name=wd><fieldset disabled><input name=ignore value=secret></fieldset><input type=hidden name=tag value=keep></form>",script).getJSONObject("form");
        assertEquals("wd",fields.getString("query"));assertEquals(1,fields.getJSONArray("fixed").length());
        JSONObject modal=readHtml("<form><input type=search name=q></form><article><a href=/detail/1>背景电影</a></article><div role=alertdialog>请登录</div>",script);
        assertTrue(modal.isNull("form"));assertEquals(0,modal.getJSONArray("items").length());
        JSONObject cards=readHtml("<main><a href='/about?next=/watch/42'>网页介绍</a><a href=/account><img src=/avatar.jpg alt=个人中心></a><article><a href='/detail/42?file=trailer.mp4&amp;sig=a%2Bb'>登录圆鱼洲</a></article></main>",script);
        assertEquals(1,cards.getJSONArray("items").length());assertEquals("登录圆鱼洲",cards.getJSONArray("items").getJSONObject(0).getString("title"));
        assertTrue(cards.getJSONArray("items").getJSONObject(0).getString("url").endsWith("?file=trailer.mp4&sig=a%2Bb"));
        StringBuilder many=new StringBuilder();for(int i=0;i<150;i++)many.append("<article><a href=/detail/").append(i).append(">影片").append(i).append("</a></article>");
        assertEquals(120,readHtml(many.toString(),script).getJSONArray("items").length());
    }
    private JSONObject readHtml(String html,String script) throws Exception{return new JSONObject(evaluate("document.body.innerHTML="+JSONObject.quote(html)+";"+script));}
    @Test public void unknownSearchCanUseOneFamilyConfiguredTemplate() throws Exception {
        org.junit.Assume.assumeTrue(provider());launchPortal("/unknown");
        assertTrue(device.wait(Until.hasObject(By.desc("网站内容：周末电影")),10000));
        search("朋友电影");assertTrue(device.wait(Until.hasObject(By.text("这个网站暂未识别搜索入口")),5000));
        activate(By.text("家人设置"),By.text("站内搜索地址"));
        UiObject2 template=device.findObject(By.clazz("android.widget.EditText"));assertNotNull(template);template.setText(server.url("/search?q={query}"));
        activate(By.text("保存"),By.text("站内搜索 · 遥控器选片"));
        search("朋友电影");assertTrue(device.wait(Until.hasObject(By.desc("网站内容：朋友电影 · 第一部")),10000));
    }
    @Test public void obsoleteMainFrameErrorCannotCancelANewerSearch() throws Exception {
        org.junit.Assume.assumeTrue(provider());launchPortal("/home");
        assertTrue(device.wait(Until.hasObject(By.desc("网站内容：周末电影")),10000));search("OLD");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {
            for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof SiteActivity)try {
                java.lang.reflect.Field field=SiteActivity.class.getDeclaredField("reader");field.setAccessible(true);WebView web=(WebView)field.get(a);
                android.webkit.WebResourceRequest old=new android.webkit.WebResourceRequest(){public android.net.Uri getUrl(){return android.net.Uri.parse(server.url("/home"));}public boolean isForMainFrame(){return true;}public boolean isRedirect(){return false;}public boolean hasGesture(){return false;}public String getMethod(){return "GET";}public Map<String,String> getRequestHeaders(){return Collections.emptyMap();}};
                web.getWebViewClient().onReceivedHttpError(web,old,new android.webkit.WebResourceResponse("text/html","UTF-8",503,"Unavailable",Collections.emptyMap(),new ByteArrayInputStream(new byte[0])));
            }catch(Exception e){throw new AssertionError(e);}
        });
        assertFalse("Obsolete document must not replace current search state",device.hasObject(By.textContains("HTTP 503")));
        assertTrue("A late error for the old home document must not cancel current search",device.wait(Until.hasObject(By.desc("网站内容：OLD · 第一部")),10000));
    }
    @Test public void returningFromOriginalRestartsInterruptedDynamicReading() throws Exception {
        org.junit.Assume.assumeTrue(provider());launchPortal("/dynamic");
        activate(By.text("原网页"),By.text("延迟页面"));server.released=true;SystemClock.sleep(700);device.pressBack();
        assertTrue(device.wait(Until.hasObject(By.text("站内搜索 · 遥控器选片")),5000));
        assertTrue("Returning must resume a pending public DOM read",device.wait(Until.hasObject(By.desc("网站内容：延迟电影")),10000));screenshot("33-site-resumed-dynamic.png");
    }
    private String evaluate(String script) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> value=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {
            for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof WebActivity)try{
                java.lang.reflect.Field field=WebActivity.class.getDeclaredField("web");field.setAccessible(true);((WebView)field.get(a)).evaluateJavascript(script,r->{value.set(r);latch.countDown();});
            }catch(Exception e){throw new AssertionError(e);}
        });assertTrue(latch.await(8,java.util.concurrent.TimeUnit.SECONDS));return value.get();
    }
    private void screenshot(String name){File dir=new File(app.getExternalFilesDir(null),"evidence");dir.mkdirs();assertTrue(device.takeScreenshot(new File(dir,name)));}
    private final class Server implements AutoCloseable {
        private final ServerSocket socket;
        private volatile boolean running=true;
        volatile boolean released;
        final List<String> requests=Collections.synchronizedList(new ArrayList<>());
        final java.util.Map<String,String> referrers=new java.util.concurrent.ConcurrentHashMap<>();
        Server() throws IOException{socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));}
        String url(String path){return "http://127.0.0.1:"+socket.getLocalPort()+path;}
        void start(){Thread thread=new Thread(()->{while(running)try{Socket c=socket.accept();Thread child=new Thread(()->serve(c));child.setDaemon(true);child.start();}catch(IOException ignored){}});thread.setDaemon(true);thread.start();}
        private String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
        void serve(Socket c){try(Socket client=c){client.setSoTimeout(3000);BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream()));String first=in.readLine();if(first==null)return;String target=first.split(" ")[1];requests.add(target);String line,referer="";while((line=in.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("referer:"))referer=line.substring(8).trim();referrers.put(target,referer);
            String q="";URI uri=URI.create(target);if(uri.getRawQuery()!=null)for(String part:uri.getRawQuery().split("&"))if(part.startsWith("q="))q=URLDecoder.decode(part.substring(2),"UTF-8");
            if(q.equals("OLD"))SystemClock.sleep(1200);
            if(uri.getPath().equals("/late-redirect")){client.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /latecards\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();return;}
            if(uri.getPath().equals("/poster.png")||uri.getPath().equals("/slow-poster.png")||uri.getPath().equals("/broken.png")||uri.getPath().equals("/oversize.png")){
                if(uri.getPath().equals("/slow-poster.png"))SystemClock.sleep(1200);
                byte[] image;if(uri.getPath().equals("/poster.png")||uri.getPath().equals("/slow-poster.png")){android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(240,360,android.graphics.Bitmap.Config.ARGB_8888);b.eraseColor(0xff64b6f7);android.graphics.Canvas canvas=new android.graphics.Canvas(b);android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);paint.setColor(0xff14263b);canvas.drawRect(8,8,232,352,paint);paint.setColor(0xffeeeeee);paint.setTextSize(36);canvas.drawText("测试海报",26,74,paint);paint.setTextSize(18);canvas.drawText("网页封面读取",28,108,paint);paint.setColor(0xff4c9aff);canvas.drawCircle(160,188,46,paint);paint.setColor(0xff243e5d);android.graphics.Path mountains=new android.graphics.Path();mountains.moveTo(16,296);mountains.lineTo(94,178);mountains.lineTo(166,296);mountains.lineTo(210,234);mountains.lineTo(226,324);mountains.lineTo(16,324);mountains.close();canvas.drawPath(mountains,paint);paint.setColor(0xffa9b7c7);paint.setTextSize(16);canvas.drawText("仅用于运行验证",30,342,paint);ByteArrayOutputStream bytes=new ByteArrayOutputStream();b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);b.recycle();image=bytes.toByteArray();}else image=uri.getPath().equals("/broken.png")?"not an image".getBytes(StandardCharsets.UTF_8):new byte[1024*1024+1];
                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: "+image.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(image);return;
            }
            if(uri.getPath().equals("/gate")){byte[] gate=(released?"ready":"wait").getBytes(StandardCharsets.UTF_8);client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "+gate.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(gate);return;}
            String form="<form action='/search?discard=1' method='get'><input type='hidden' name='lang' value='zh'><input type='search' name='q'><button>搜索</button></form>";
            String film="<article class='video-card'><a title='周末电影' href='/detail/first?sig=a%2Bb'><img src='/broken.png' data-src='/poster.png' width=80 height=120 alt='周末电影'>周末电影</a><p class='description'>这是网站提供的影片简介。</p></article>";
            String body=uri.getPath().equals("/unknown")?film:form+film;
            if(uri.getPath().equals("/search"))body=q.equals("EMPTY")?form:form+"<article><a title='"+esc(q)+" · 第一部' href='/detail/first?sig=a%2Bb'>"+esc(q)+" · 第一部</a></article><article><a href='/detail/second'>"+esc(q)+" · 第二部</a></article>";
            if(uri.getPath().equals("/dynamic"))body=form+"<h1>延迟页面</h1><script>function check(){var r=new XMLHttpRequest();r.open('GET','/gate');r.onload=function(){if(r.responseText==='ready'){var a=document.createElement('article');a.innerHTML='<a href=\"/detail/dynamic\">延迟电影</a>';document.body.appendChild(a)}else setTimeout(check,300)};r.send()}check()</script>";
            if(uri.getPath().equals("/latecards"))body=form+"<h1>动态片库加载中</h1><script>setTimeout(function(){var a=document.createElement('article');a.className='movie-item';a.innerHTML='<a href=\"/detail/late-card\"><img src=\"/poster.png\" alt=\"八秒后到达的影片\" width=\"120\" height=\"160\"></a><p>公开动态卡片，在首次空扫描后插入</p>';document.body.appendChild(a)},8500)</script>";
            if(uri.getPath().equals("/badposters"))body="<article><a href='/detail/bad'><img src='/broken.png' alt='损坏封面影片'>损坏封面影片</a></article><article><a href='/detail/huge'><img src='/oversize.png' alt='超大封面影片'>超大封面影片</a></article>";
            if(uri.getPath().equals("/longpreview")){StringBuilder title=new StringBuilder("长标题"),summary=new StringBuilder();for(int i=0;i<25;i++)title.append("电影故事");for(int i=0;i<150;i++)summary.append("公开简介");body="<article><a href='/detail/long' title='"+title.substring(0,100)+"'>"+title.substring(0,100)+"</a><p class='description'>"+summary+"</p></article>";}
            if(uri.getPath().startsWith("/detail/"))body="<h1>网站详情 · 第一部</h1><button onclick=\"this.textContent='网站按钮可操作'\">开始播放测试</button>";
            String html="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>朋友影视</title></head><body style='background:#14202f;color:white;font-size:24px'>"+body+"<nav><a href='/detail/not-a-film'>站点导航</a></nav><footer><a href='/watch/legal'>隐私政策</a></footer><article hidden><a href='/detail/hidden'>隐藏电影</a></article><a href='javascript:alert(1)'>不安全</a>"+(uri.getPath().equals("/home")?"<article><a href='/detail/first?sig=a%2Bb'>周末电影</a></article>":"")+"</body></html>";
            byte[] data=html.getBytes(StandardCharsets.UTF_8);client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(data);client.getOutputStream().flush();
        }catch(Exception ignored){}}
        @Override public void close() throws IOException{running=false;socket.close();}
    }
}
