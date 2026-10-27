package tv.clearstream;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WebsiteHealthUiTest {
 @Test public void lateWebsiteRefreshWhileStoppedDoesNotStartChecksAndResumesNormally()throws Exception{
  assertTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
  Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());final MainActivity[] activity={null};
  ((ClearApp)app.getApplicationContext()).settings.edit().putBoolean("simpleMode",true).commit();
  app.startActivity(Intent.makeMainActivity(new ComponentName(app,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
  try{
   assertTrue(device.wait(Until.hasObject(By.text("看影视")),10000));device.pressDPadRight();device.pressDPadCenter();assertTrue(device.wait(Until.hasObject(By.text("选择网站 · 搜索影片 · 按确定播放")),5000));
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof MainActivity)activity[0]=(MainActivity)a;});assertNotNull(activity[0]);
   device.pressHome();long until=SystemClock.elapsedRealtime()+5000;while(!mainStopped(activity[0])&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);assertTrue(mainStopped(activity[0]));assertNull(mainHealth(activity[0]));
   // The subscription-completion callback calls this same UI refresh after its network job finishes.
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Method refresh=MainActivity.class.getDeclaredMethod("websites");refresh.setAccessible(true);refresh.invoke(activity[0]);}catch(ReflectiveOperationException error){throw new AssertionError(error);}});
   assertNull("A delayed website refresh while stopped must not recreate checking",mainHealth(activity[0]));
   app.startActivity(Intent.makeMainActivity(new ComponentName(app,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));assertTrue("Launcher must resume the existing website page",device.wait(Until.hasObject(By.text("选择网站 · 搜索影片 · 按确定播放")),5000));until=SystemClock.elapsedRealtime()+3000;while(mainHealth(activity[0])==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);assertNotNull("Returning must restart foreground checks",mainHealth(activity[0]));
  }finally{if(activity[0]!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity[0].finish());}
 }
 private static boolean mainStopped(MainActivity activity){final boolean[] value={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->value[0]=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)==androidx.test.runner.lifecycle.Stage.STOPPED);return value[0];}
 private static Object mainHealth(MainActivity activity){final Object[] value={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("websiteHealth");field.setAccessible(true);value[0]=field.get(activity);}catch(ReflectiveOperationException error){throw new AssertionError(error);}});return value[0];}
 @Test public void directoryChecksAutomaticallyWithoutTakingRemoteFocus()throws Exception{
  assertTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));Assume.assumeTrue(Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
  Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice d=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
  try(Fixture f=new Fixture()){
   app.startActivity(new Intent(app,DirectoryActivity.class).putExtra("url",f.url("127.0.0.1","/directory")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
   assertTrue(d.wait(Until.hasObject(By.desc("导航网站：可访问测试站")),10000));d.pressDPadRight();
   assertTrue("Remote should focus the add button before the result arrives",d.hasObject(By.desc("添加网站：可访问测试站").focused(true)));
   f.release.countDown();assertTrue("Website checks start without asking the user to find a test action",d.wait(Until.hasObject(By.textContains("网页可访问")),10000));
   assertTrue(d.wait(Until.hasObject(By.textContains("暂不可访问")),10000));
   assertTrue("Updating labels must not rebuild the list or steal add-button focus",d.hasObject(By.desc("添加网站：可访问测试站").focused(true)));
   File out=new File(app.getExternalFilesDir(null),"remote-only-018");out.mkdirs();d.takeScreenshot(new File(out,"health-labels-preserve-focus.png"));d.pressBack();
  }
 }
 @Test public void delayedDirectoryResponseCannotRestartChecksAfterHomeAndResumesWhenReturned()throws Exception{
  assertTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));Assume.assumeTrue(Build.VERSION.SDK_INT<26||WebView.getCurrentWebViewPackage()!=null);
  Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());final DirectoryActivity[] activity={null};
  try(Fixture f=new Fixture()){
   String url=f.url("127.0.0.1","/delayed-directory");f.release.countDown();
   try{
    app.startActivity(new Intent(app,DirectoryActivity.class).putExtra("url",url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
    assertTrue(device.wait(Until.hasObject(By.text("发现影视网站")),10000));assertTrue("Directory HTTP request must be held before its response",f.directoryRequested.await(5,TimeUnit.SECONDS));
    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof DirectoryActivity)activity[0]=(DirectoryActivity)a;});assertNotNull(activity[0]);
    device.pressHome();long until=SystemClock.elapsedRealtime()+5000;
    while(!stopped(activity[0])&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
    assertTrue("The activity must really stop before the server releases the directory",stopped(activity[0]));assertNull("Leaving must close active website checking",health(activity[0]));
    f.directoryResponse.countDown();assertTrue("The held HTML response must now be attempted (a lifecycle cancellation is allowed)",f.directoryResponseWritten.await(3,TimeUnit.SECONDS));
    until=SystemClock.elapsedRealtime()+6000;
    while(SystemClock.elapsedRealtime()<until){assertEquals("A late directory response must not start destination checks in the background",0,f.targetRequests.get());assertNull("A stopped directory must not recreate WebsiteHealth",health(activity[0]));SystemClock.sleep(80);}
    app.startActivity(new Intent(app,DirectoryActivity.class).putExtra("url",url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    assertTrue("Returning from Home must continue or restart the unfinished directory rather than leave a blank page",device.wait(Until.hasObject(By.desc("导航网站：可访问测试站")),12000));
    assertTrue("Foreground website checking should resume normally",device.wait(Until.hasObject(By.textContains("网页可访问")),10000));assertTrue(f.targetRequests.get()>0);
    File out=new File(app.getExternalFilesDir(null),"remote-only-018");out.mkdirs();device.takeScreenshot(new File(out,"directory-checks-return-from-home.png"));
   }finally{if(activity[0]!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity[0].finish());f.directoryResponse.countDown();}
  }
 }
 private static boolean stopped(DirectoryActivity activity){final boolean[] stopped={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->stopped[0]=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)==androidx.test.runner.lifecycle.Stage.STOPPED);return stopped[0];}
 private static Object health(DirectoryActivity activity){final Object[] health={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{java.lang.reflect.Field field=DirectoryActivity.class.getDeclaredField("health");field.setAccessible(true);health[0]=field.get(activity);}catch(ReflectiveOperationException e){throw new AssertionError(e);}});return health[0];}
 private static final class Fixture implements AutoCloseable{
  final ServerSocket socket=new ServerSocket(0,12,InetAddress.getByName("127.0.0.1"));final ExecutorService workers=Executors.newCachedThreadPool();final CountDownLatch release=new CountDownLatch(1),directoryRequested=new CountDownLatch(1),directoryResponse=new CountDownLatch(1),directoryResponseWritten=new CountDownLatch(1);final AtomicInteger targetRequests=new AtomicInteger();volatile boolean closed;
  Fixture()throws IOException{workers.execute(()->{while(!closed)try{Socket s=socket.accept();workers.execute(()->serve(s));}catch(IOException e){if(!closed)throw new RuntimeException(e);}});}
  String url(String host,String path){return "http://"+host+":"+socket.getLocalPort()+path;}
  void serve(Socket s){boolean delayed=false;try(Socket client=s){BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));String first=in.readLine();if(first==null)return;String path=first.split(" ")[1],line;delayed=path.equals("/delayed-directory");while((line=in.readLine())!=null&&!line.isEmpty()){}
   String body;int code=200;
   if(path.equals("/directory")||path.equals("/delayed-directory")){if(path.equals("/delayed-directory")){directoryRequested.countDown();if(!directoryResponse.await(15,TimeUnit.SECONDS))return;}body="<html><div class='sites-list'><h4>在线影院</h4><div class='xe-card'><div class='box2' data-url='"+url("localhost","/good")+"'><strong>可访问测试站</strong></div></div><div class='xe-card'><div class='box2' data-url='"+url("localhost","/bad")+"'><strong>失效测试站</strong></div></div></div></html>";}
   else{targetRequests.incrementAndGet();release.await(5,TimeUnit.SECONDS);body="<html><title>公开页面</title><h1>公开网页内容</h1></html>";if(path.equals("/bad"))code=404;}
   byte[] data=body.getBytes(StandardCharsets.UTF_8);OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 "+code+" OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();if(path.equals("/delayed-directory"))directoryResponseWritten.countDown();
  }catch(Exception ignored){}finally{if(delayed)directoryResponseWritten.countDown();}}
  public void close()throws IOException{closed=true;release.countDown();directoryResponse.countDown();socket.close();workers.shutdownNow();}
 }
}
