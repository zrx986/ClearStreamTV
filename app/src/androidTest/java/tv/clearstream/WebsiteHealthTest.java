package tv.clearstream;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.StrictMode;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WebsiteHealthTest {
    private Context isolated;private Handler main;private WebsiteHealth health;private Server server;
    @Before public void prepare() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();String prefix="health-test-"+UUID.randomUUID()+"-";
        isolated=new ContextWrapper(base){@Override public Context getApplicationContext(){return this;}@Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+name,mode);}};
        main=new Handler(Looper.getMainLooper());health=new WebsiteHealth(isolated,main,500,500,1000);server=new Server();server.start();
    }
    @After public void cleanup() throws Exception {if(health!=null)health.close();if(server!=null)server.close();}
    @Test public void htmlGetIsReachableButDoesNotClaimVideoAndCachesForTenMinutes() throws Exception {
        String url=server.url("/html");WebsiteHealth.Snapshot result=check(url,true);
        assertEquals(WebsiteHealth.State.PAGE_OK,result.state);assertEquals("网页可访问 · 待播放验证",result.label);assertEquals(200,result.httpCode);assertTrue(result.timestamp>0);assertFalse(result.checking);assertEquals("GET",server.lastMethod);
        assertEquals(0,result.playbackTimestamp);assertEquals(1,server.count.get());assertEquals(result.timestamp,check(url,false).timestamp);assertEquals(1,server.count.get());
        check(url,true);assertEquals(2,server.count.get());
        health.close();health=new WebsiteHealth(isolated,main,500,500,1000);assertEquals(WebsiteHealth.State.PAGE_OK,health.status(url).state);
    }
    @Test public void playlistsAndMediaAreNotReportedAsPlayableWebsites() throws Exception {
        WebsiteHealth.Snapshot plain=check(server.url("/playlist"),true),masquerading=check(server.url("/playlist-as-html"),true);
        assertEquals(WebsiteHealth.State.UNAVAILABLE,plain.state);assertEquals("返回的不是网页",plain.reason);assertEquals(WebsiteHealth.State.UNAVAILABLE,masquerading.state);
    }
    @Test public void verificationWallsAndMissingPagesRemainDistinct() throws Exception {
        for(String path:new String[]{"/status/401","/status/403","/status/429","/captcha","/login","/cloudflare-521"})assertEquals(path,WebsiteHealth.State.VERIFY_REQUIRED,check(server.url(path),true).state);
        WebsiteHealth.Snapshot missing=check(server.url("/status/404"),true);assertEquals(WebsiteHealth.State.UNAVAILABLE,missing.state);assertEquals(404,missing.httpCode);assertEquals("页面不存在 · HTTP 404",missing.reason);
        WebsiteHealth.Snapshot failed=check(server.url("/status/503"),true);assertEquals(WebsiteHealth.State.UNAVAILABLE,failed.state);assertEquals("网站暂时异常 · HTTP 503",failed.reason);
    }
    @Test public void redirectsAreValidatedAndLoopsTimeoutsAndLargeBodiesAreBounded() throws Exception {
        WebsiteHealth.Snapshot redirected=check(server.url("/redirect"),true);assertEquals(WebsiteHealth.State.PAGE_OK,redirected.state);assertEquals("127.0.0.1",redirected.finalHost);
        WebsiteHealth.Snapshot fallback=check(server.url("/search-fallback"),true);assertEquals(WebsiteHealth.State.VERIFY_REQUIRED,fallback.state);assertEquals("入口已跳转到其他网站",fallback.reason);assertEquals("www.baidu.com",fallback.finalHost);
        WebsiteHealth.Snapshot moved=check(server.url("/moved"),true);assertEquals(WebsiteHealth.State.PAGE_OK,moved.state);assertEquals("localhost",moved.finalHost);assertEquals("入口已转至 localhost",moved.reason);
        assertEquals("重定向地址无效",check(server.url("/unsafe"),true).reason);
        int before=server.count.get();WebsiteHealth.Snapshot loop=check(server.url("/loop"),true);assertEquals("重定向次数过多",loop.reason);assertTrue(server.count.get()-before<=6);
        assertEquals("连接或读取超时",check(server.url("/slow"),true).reason);
        long start=SystemClock.elapsedRealtime();assertEquals(WebsiteHealth.State.PAGE_OK,check(server.url("/large"),true).state);assertTrue("A 64KiB prefix must not wait for the rest of a huge body",SystemClock.elapsedRealtime()-start<1000);
        int requests=server.count.get();assertEquals("网址无效",check("javascript:alert(1)",true).reason);assertEquals(requests,server.count.get());
    }
    @Test public void playbackEvidencePersistsWithoutOverridingLatestNetworkFailure() throws Exception {
        String root=server.url("/status/404"),page=server.url("/film/one");
        WebsiteHealth.recordPlayback(isolated,root,page,864,486,8.5);WebsiteHealth.Snapshot failed=check(root,true);
        assertEquals(WebsiteHealth.State.UNAVAILABLE,failed.state);assertEquals("暂不可访问",failed.label);assertTrue(failed.playbackTimestamp>0);assertEquals(page,failed.playbackPage);assertEquals(864,failed.playbackWidth);
        health.close();health=new WebsiteHealth(isolated,main,500,500,1000);assertEquals(page,health.status(root).playbackPage);assertEquals(WebsiteHealth.State.UNAVAILABLE,health.status(root).state);
        String good=server.url("/html");WebsiteHealth.recordPlayback(isolated,good,server.url("/film/two"),320,180,6);assertEquals("网页可访问 · 有实播记录",check(good,true).label);
        WebsiteHealth.recordPlayback(isolated,good,"javascript:bad",320,180,6);assertTrue(health.status(good).playbackPage.endsWith("/film/two"));
    }
    @Test public void closeCancelsQueuedCallbacksAndDoesNotPublishLateResults() throws Exception {
        CountDownLatch blockerStarted=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger();
        main.post(()->{blockerStarted.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        assertTrue(blockerStarted.await(2,TimeUnit.SECONDS));
        try {health.check(server.url("/html"),true,s->callbacks.incrementAndGet());long until=SystemClock.elapsedRealtime()+2000;while(server.count.get()==0&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(10);assertEquals(1,server.count.get());SystemClock.sleep(150);health.close();}finally{release.countDown();}
        CountDownLatch drained=new CountDownLatch(1);main.post(drained::countDown);assertTrue(drained.await(2,TimeUnit.SECONDS));assertEquals(0,callbacks.get());
    }
    @Test public void closeDoesNotInterruptAnAlreadyRunningNetworkWorker() throws Exception {
        java.lang.reflect.Field field=WebsiteHealth.class.getDeclaredField("workers");field.setAccessible(true);ExecutorService executor=(ExecutorService)field.get(health);
        CountDownLatch running=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();AtomicInteger callbacks=new AtomicInteger();
        executor.submit(()->{running.countDown();try{release.await(3,TimeUnit.SECONDS);interrupted.set(Thread.currentThread().isInterrupted());}catch(InterruptedException error){interrupted.set(true);}finally{finished.countDown();}});
        assertTrue("Worker must already be executing",running.await(2,TimeUnit.SECONDS));
        try{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{health.check("javascript:invalid",true,value->callbacks.incrementAndGet());health.close();});}finally{release.countDown();}
        assertTrue(finished.await(2,TimeUnit.SECONDS));assertFalse("Closing must cancel owned calls without interrupting OkHttp's running worker",interrupted.get());
        CountDownLatch drained=new CountDownLatch(1);main.post(drained::countDown);assertTrue(drained.await(2,TimeUnit.SECONDS));assertEquals("No late callback after close",0,callbacks.get());
    }
    @Test public void tlsCleanupAuditClosesAnIdleHttpsConnectionOffTheMainThread() throws Exception {
        String address=InstrumentationRegistry.getArguments().getString("tlsCleanupAudit","");
        Assume.assumeTrue("Explicit public TLS cleanup audit URL required",address.startsWith("https://"));
        health.close();health=new WebsiteHealth(isolated,main);CountDownLatch done=new CountDownLatch(1);WebsiteHealth.Snapshot[] result={null};
        health.check(address,true,value->{result[0]=value;done.countDown();});assertTrue("Public HTTPS check must finish",done.await(12,TimeUnit.SECONDS));assertNotNull(result[0]);assertTrue("TLS must establish and receive an HTTP response",result[0].httpCode>0);
        java.lang.reflect.Field field=WebsiteHealth.class.getDeclaredField("client");field.setAccessible(true);okhttp3.OkHttpClient actual=(okhttp3.OkHttpClient)field.get(health);
        assertTrue("Audit endpoint must leave an idle TLS socket to exercise pool eviction",actual.connectionPool().idleConnectionCount()>0);
        Throwable[] failure={null};long[] elapsed={0};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            StrictMode.ThreadPolicy previous=StrictMode.getThreadPolicy();
            try{StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build());long started=SystemClock.elapsedRealtime();health.close();elapsed[0]=SystemClock.elapsedRealtime()-started;}catch(Throwable error){failure[0]=error;}finally{StrictMode.setThreadPolicy(previous);}
        });
        if(failure[0]!=null)throw new AssertionError("Closing website checks on the main thread must not close TLS there",failure[0]);
        assertTrue("Lifecycle close must return without waiting for sockets",elapsed[0]<500);
        long until=SystemClock.elapsedRealtime()+3000;while(actual.connectionPool().connectionCount()>0&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
        assertEquals("Background cleanup must evict the owned TLS pool",0,actual.connectionPool().connectionCount());
    }
    private WebsiteHealth.Snapshot check(String url,boolean force) throws Exception {
        CountDownLatch done=new CountDownLatch(1);WebsiteHealth.Snapshot[] value={null};AtomicBoolean onMain=new AtomicBoolean();
        health.check(url,force,s->{onMain.set(Looper.myLooper()==Looper.getMainLooper());value[0]=s;done.countDown();});assertTrue("Website check must finish within its deadline",done.await(4,TimeUnit.SECONDS));assertTrue("Callback must run on main thread",onMain.get());assertNotNull(value[0]);return value[0];
    }
    private static final class Server implements AutoCloseable {
        final ServerSocket socket;final ExecutorService workers=Executors.newCachedThreadPool();final AtomicInteger count=new AtomicInteger();volatile boolean closed;volatile String lastMethod;
        Server() throws IOException {socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));}
        String url(String path){return "http://127.0.0.1:"+socket.getLocalPort()+path;}
        void start(){workers.execute(()->{while(!closed)try{Socket client=socket.accept();workers.execute(()->serve(client));}catch(IOException ignored){}});}
        void serve(Socket client){try(Socket c=client){c.setSoTimeout(2000);BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.US_ASCII));String first=reader.readLine();if(first==null)return;String[] parts=first.split(" ");lastMethod=parts[0];String path=parts[1],line;int size=0;while((line=reader.readLine())!=null&&!line.isEmpty()){if((size+=line.length())>8192)return;}count.incrementAndGet();
            if(path.equals("/slow")){SystemClock.sleep(1600);return;}
            int code=200;String type="text/html; charset=utf-8",extra="",body="<!doctype html><title>示例网站</title><p>电影列表</p>";
            if(path.startsWith("/status/"))code=Integer.parseInt(path.substring(8));
            if(path.equals("/redirect")){code=302;extra="Location: /html\r\n";}
            if(path.equals("/search-fallback")){code=302;extra="Location: https://www.baidu.com/\r\n";}
            if(path.equals("/moved")){code=302;extra="Location: http://localhost:"+socket.getLocalPort()+"/html\r\n";}
            if(path.equals("/unsafe")){code=302;extra="Location: javascript:alert(1)\r\n";}
            if(path.equals("/loop")){code=302;extra="Location: /loop\r\n";}
            if(path.equals("/playlist")||path.equals("/playlist-as-html")){body="#EXTM3U\n#EXTINF:-1,CCTV1\nhttps://example.test/live.m3u8\n";if(path.equals("/playlist"))type="application/vnd.apple.mpegurl";}
            if(path.equals("/captcha"))body="<html><title>Just a moment...</title><form id='challenge-form'>Verify you are human</form></html>";
            if(path.equals("/login"))body="<html><title>登录后观看</title><form><input type='password'></form></html>";
            if(path.equals("/cloudflare-521")){code=521;body="<html><title>请进行安全验证</title><p>确认后继续</p></html>";}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream out=c.getOutputStream();
            if(path.equals("/large")){out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 100000000\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));byte[] large=new byte[65536];Arrays.fill(large,(byte)' ');byte[] prefix="<!doctype html><title>大页面</title>".getBytes(StandardCharsets.UTF_8);System.arraycopy(prefix,0,large,0,prefix.length);out.write(large);out.flush();SystemClock.sleep(1600);return;}
            out.write(("HTTP/1.1 "+code+" Test\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n"+extra+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(IOException|RuntimeException ignored){}}
        @Override public void close() throws IOException {closed=true;socket.close();workers.shutdownNow();}
    }
}
