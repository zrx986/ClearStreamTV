package tv.clearstream;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real system WebView + local public HTML; no production testing hooks or remote services. */
@RunWith(AndroidJUnit4.class)
public final class WebsiteSearchTest {
    private ClearApp app;
    private Activity activity;
    private FrameLayout host;
    private TextView focused;
    private WebsiteSearch search;
    private Server server;
    private final List<WebsiteSearch.Result> results=Collections.synchronizedList(new ArrayList<>());

    @Before public void prepare() throws Exception {
        assertTrue("Disposable emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        Assume.assumeTrue(Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
        app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        app.settings.edit().clear().putBoolean("simpleMode",true).commit();
        activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            host=new FrameLayout(activity);
            focused=new TextView(activity);focused.setText("搜索测试原生界面");focused.setBackgroundColor(Color.BLACK);focused.setFocusable(true);focused.setFocusableInTouchMode(true);
            host.addView(focused,new FrameLayout.LayoutParams(-1,-1));
            ((FrameLayout)activity.findViewById(android.R.id.content)).addView(host,new FrameLayout.LayoutParams(-1,-1));focused.requestFocus();
        });
        server=new Server();server.start();
    }

    @After public void cleanup() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(search!=null)search.close();if(activity!=null)activity.finish();});
        if(server!=null){System.out.println("website-search-fixture-requests: "+server.requests);server.close();}
    }

    @Test public void actualGetFormsReturnFirstSitePromptlyAndKeepEverySiteSeparate() throws Exception {
        CountDownLatch first=new CountDownLatch(1),done=new CountDownLatch(2);
        List<WebsiteSearch.Target> targets=Arrays.asList(target("甲站","/one"),target("乙站","/two"));
        start(targets,"星河",r->{if(!r.items.isEmpty()&&r.site.url.equals(server.url("/one")))first.countDown();if(r.done)done.countDown();});
        assertTrue("First website must produce native data before the later slow website",first.await(7,TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            assertSame("Background reader cannot steal remote focus",focused,activity.getCurrentFocus());
            assertTrue("Reader must stay behind the opaque native interface",host.getChildAt(0) instanceof WebView);
            assertEquals(View.INVISIBLE,host.getChildAt(0).getVisibility());assertFalse(host.getChildAt(0).isFocusable());
        });
        assertTrue("One slow website must not prevent the remaining terminal callbacks",done.await(22,TimeUnit.SECONDS));
        WebsiteSearch.Result one=terminal(server.url("/one")),two=terminal(server.url("/two"));
        assertEquals(1,one.items.size());assertEquals(1,two.items.size());
        assertEquals("星河 · 甲站",one.items.get(0).title);assertEquals("星河 · 乙站",two.items.get(0).title);
        assertEquals(server.url("/one"),one.items.get(0).root);assertEquals("甲站",one.items.get(0).name);
        assertTrue("GET fixed fields must replace the form action's stale query",server.requests.stream().anyMatch(p->p.startsWith("/search?")&&p.contains("lang=zh")&&p.contains("kind=film")&&p.contains("q=%E6%98%9F%E6%B2%B3")&&!p.contains("discard=")));
        assertFalse("Home recommendations are never search results",snapshot().stream().flatMap(r->r.items.stream()).anyMatch(item->item.title.contains("首页推荐")));
        assertEquals("Each website completes exactly once",2,snapshot().stream().filter(r->r.done).count());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{assertEquals("Completed searches release the idle WebView",1,host.getChildCount());assertSame(focused,activity.getCurrentFocus());});
    }

    @Test public void customTemplateSkipsHomeAndAnEmptySearchNeverReturnsRecommendations() throws Exception {
        String root=server.url("/one");app.settings.edit().putString("siteSearch:"+root,server.url("/empty?q={query}")).commit();
        CountDownLatch done=new CountDownLatch(1);start(Collections.singletonList(target("甲站","/one")),"不存在的影片",r->{if(r.done)done.countDown();});
        assertTrue(done.await(10,TimeUnit.SECONDS));WebsiteSearch.Result result=terminal(root);
        assertTrue("Explicit no-result page must not export its recommendation cards",result.items.isEmpty());
        assertTrue(result.status,result.status.contains("未找到"));assertTrue(result.searchUrl,result.searchUrl.contains("/empty?q="));
        assertFalse("Saved valid search address has priority over reading home",server.requests.contains("/one"));
    }

    @Test public void postFormIsNotGuessedAndItsHomeFilmsAreNeverReturned() throws Exception {
        CountDownLatch done=new CountDownLatch(1);start(Collections.singletonList(target("未知站","/post")),"星河",r->{if(r.done)done.countDown();});
        assertTrue(done.await(12,TimeUnit.SECONDS));WebsiteSearch.Result result=terminal(server.url("/post"));
        assertTrue(result.items.isEmpty());assertTrue(result.status,result.status.contains("搜索入口"));
        assertEquals("An unrecognized search must offer the original website rather than claim a submitted search", "",result.searchUrl);
        assertFalse(server.requests.stream().anyMatch(p->p.startsWith("/unobserved-search")));
    }

    @Test public void verificationStopsNativeReadingAndDoesNotBlockTheNextWebsite() throws Exception {
        CountDownLatch done=new CountDownLatch(2);start(Arrays.asList(target("验证站","/verification"),target("甲站","/one")),"星河",r->{if(r.done)done.countDown();});
        assertTrue(done.await(16,TimeUnit.SECONDS));WebsiteSearch.Result blocked=terminal(server.url("/verification"));
        assertTrue(blocked.items.isEmpty());assertTrue(blocked.status,blocked.status.contains("原网站"));assertEquals(1,terminal(server.url("/one")).items.size());
        assertFalse("Verification forms cannot be submitted",server.requests.stream().anyMatch(p->p.startsWith("/login")));
    }

    @Test public void closeDestroysReaderAndRejectsLateCallbacks() throws Exception {
        String root=server.url("/two");app.settings.edit().putString("siteSearch:"+root,server.url("/slow-search?q={query}")).commit();
        start(Collections.singletonList(target("乙站","/two")),"星河",r->{});
        long until=SystemClock.elapsedRealtime()+4000;while(SystemClock.elapsedRealtime()<until&&!server.requests.stream().anyMatch(p->p.startsWith("/slow-search?")))SystemClock.sleep(40);
        assertTrue("Reader has started a real request",server.requests.stream().anyMatch(p->p.startsWith("/slow-search?")));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{search.close();search.close();assertEquals(1,host.getChildCount());assertSame(focused,host.getChildAt(0));});
        int callbacks=results.size();SystemClock.sleep(3500);assertEquals("No work may reach a paused/closed UI",callbacks,results.size());
    }

    @Test public void restartingRejectsThePreviousSearchAndKeepsTheNewQuery() throws Exception {
        String root=server.url("/two");app.settings.edit().putString("siteSearch:"+root,server.url("/slow-search?q={query}")).commit();
        CountDownLatch done=new CountDownLatch(1);
        start(Collections.singletonList(target("乙站","/two")),"旧词",r->{if(r.done&&r.site.url.equals(server.url("/one")))done.countDown();});
        long until=SystemClock.elapsedRealtime()+4000;while(SystemClock.elapsedRealtime()<until&&!server.requests.stream().anyMatch(p->p.startsWith("/slow-search?")))SystemClock.sleep(40);
        assertTrue(server.requests.stream().anyMatch(p->p.startsWith("/slow-search?")));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{results.clear();search.start(Collections.singletonList(target("甲站","/one")),"新词");});
        assertTrue(done.await(10,TimeUnit.SECONDS));SystemClock.sleep(1000);
        assertEquals("新词 · 甲站",terminal(server.url("/one")).items.get(0).title);
        assertFalse("Old responses may not contaminate a later search",snapshot().stream().anyMatch(r->!r.site.url.equals(server.url("/one"))));
    }

    @Test public void redirectedSearchReadsTheActualResultDocument() throws Exception {
        String root=server.url("/one");app.settings.edit().putString("siteSearch:"+root,server.url("/redirect-search?q={query}")).commit();
        CountDownLatch done=new CountDownLatch(1);start(Collections.singletonList(target("甲站","/one")),"星河",r->{if(r.done)done.countDown();});
        assertTrue("A real HTTP redirect must not strand the reader",done.await(10,TimeUnit.SECONDS));
        WebsiteSearch.Result result=terminal(root);assertEquals(1,result.items.size());assertEquals("星河 · 甲站",result.items.get(0).title);
        assertTrue("Open-original must use the active result URL: "+result.searchUrl,result.searchUrl.contains("/search?"));
        assertTrue(server.requests.toString(),server.requests.stream().anyMatch(p->p.startsWith("/redirect-search?")));
        assertTrue(server.requests.toString(),server.requests.stream().anyMatch(p->p.startsWith("/search?lang=zh&q=")));
    }

    @Test public void verificationAppearingAfterTheFirstCardsClearsTheTerminalResult() throws Exception {
        String root=server.url("/one");app.settings.edit().putString("siteSearch:"+root,server.url("/late-verification?q={query}")).commit();
        CountDownLatch early=new CountDownLatch(1),done=new CountDownLatch(1);
        start(Collections.singletonList(target("甲站","/one")),"星河",r->{if(!r.done&&!r.items.isEmpty())early.countDown();if(r.done)done.countDown();});
        assertTrue("Fixture must first expose actual public result cards",early.await(5,TimeUnit.SECONDS));
        assertTrue(done.await(8,TimeUnit.SECONDS));WebsiteSearch.Result result=terminal(root);
        assertTrue("A later verification gate invalidates previously exposed native cards",result.items.isEmpty());
        assertTrue(result.status,result.status.contains("原网站"));
    }

    private WebsiteSearch.Target target(String name,String path){return new WebsiteSearch.Target(name,server.url(path));}
    private void start(List<WebsiteSearch.Target> targets,String query,WebsiteSearch.Callback callback){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{search=new WebsiteSearch(activity,host,r->{results.add(r);callback.onResult(r);});search.start(targets,query);});
    }
    private List<WebsiteSearch.Result> snapshot(){synchronized(results){return new ArrayList<>(results);}}
    private WebsiteSearch.Result terminal(String root){for(WebsiteSearch.Result r:snapshot())if(r.done&&r.site.url.equals(root))return r;throw new AssertionError("Missing terminal result for "+root);}

    private static final class Server implements AutoCloseable {
        private final ServerSocket socket;
        private volatile boolean running=true;
        final List<String> requests=new CopyOnWriteArrayList<>();
        Server() throws IOException{socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));}
        String url(String path){return "http://127.0.0.1:"+socket.getLocalPort()+path;}
        void start(){Thread t=new Thread(()->{while(running)try{Socket client=socket.accept();Thread worker=new Thread(()->serve(client));worker.setDaemon(true);worker.start();}catch(IOException ignored){}});t.setDaemon(true);t.start();}
        private void serve(Socket connection){try(Socket client=connection){
            client.setSoTimeout(2500);BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));String first=in.readLine();if(first==null)return;
            String request=first.split(" ")[1];requests.add(request);String line;while((line=in.readLine())!=null&&!line.isEmpty()){}
            URI uri=URI.create(request);String path=uri.getPath(),query="";if(uri.getRawQuery()!=null)for(String field:uri.getRawQuery().split("&"))if(field.startsWith("q="))query=URLDecoder.decode(field.substring(2),"UTF-8");
            if(path.equals("/redirect-search")){
                client.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: /search?lang=zh&q="+URLEncoder.encode(query,"UTF-8")+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();return;
            }
            String form="<form action='/search?discard=1' method='get'><input type='hidden' name='lang' value='zh'><select name='kind'><option value='film' selected>影片</option></select><input type='search' name='q'><button>搜索</button></form>";
            String home="<article><a href='/detail/home'>首页推荐电影</a></article>";
            String body=form+home;
            if(path.equals("/two"))body=body.replace("/search?discard=1","/slow-search?discard=1");
            if(path.equals("/search"))body=form+film(query+" · 甲站","/detail/one");
            if(path.equals("/slow-search")){SystemClock.sleep(2700);body=form+film(query+" · 乙站","/detail/two");}
            if(path.equals("/empty"))body=form+"<h1>未找到相关影片</h1><section><h2>推荐影片</h2>"+home+"</section>";
            if(path.equals("/post"))body="<form action='/unobserved-search' method='post'><input type='search' name='q'><button>搜索</button></form>"+home;
            if(path.equals("/verification"))body="<div role='dialog' aria-modal='true'><h2>请登录完成安全验证</h2><form action='/login' method='get'><input name='q'><input type='password' name='password'><button>登录</button></form></div>"+home;
            if(path.equals("/late-verification"))body=film(query+" · 甲站","/detail/one")+"<script>setTimeout(function(){var gate=document.createElement('div');gate.setAttribute('role','dialog');gate.setAttribute('aria-modal','true');gate.innerHTML='<h2>请登录完成安全验证</h2><input type=password>';document.body.appendChild(gate)},450)</script>";
            byte[] html=("<!doctype html><html><head><meta charset='utf-8'><title>公开网站</title></head><body>"+body+"</body></html>").getBytes(StandardCharsets.UTF_8);
            OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+html.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(html);out.flush();
        }catch(Exception ignored){}}
        private static String film(String title,String path){String safe=title.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");return "<article><a href='"+path+"'><img src='/poster.png' alt='"+safe+"' referrerpolicy='no-referrer'>"+safe+"</a><p class='description'>公开影片简介</p></article>";}
        @Override public void close() throws IOException{running=false;socket.close();}
    }
}
