package tv.clearstream;
import android.app.Activity;import android.content.*;import android.os.*;import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.concurrent.*;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicit read-only native-reader diagnostic, not a remote journey or playback acceptance. */
@RunWith(AndroidJUnit4.class) public final class SiteReaderDiagnosticTest {
 @Test public void captureLoadedPublicDocument()throws Exception{
  String url=InstrumentationRegistry.getArguments().getString("readerDiagnostic","");Assume.assumeTrue(url.startsWith("https://feikuai.tv/")||url.startsWith("https://fdzys666.xyz/"));assertTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
  Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();app.startActivity(new Intent(app,SiteActivity.class).putExtra("url",url).putExtra("name","读取诊断").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));SystemClock.sleep(23000);
  String reader;try(InputStream in=app.getAssets().open("site-reader.js")){reader=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);}
  String[] values={"null","null"};CountDownLatch done=new CountDownLatch(2);String script=reader;JSONObject state=new JSONObject();
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{for(Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(activity instanceof SiteActivity){java.lang.reflect.Field field=SiteActivity.class.getDeclaredField("reader");field.setAccessible(true);WebView view=(WebView)field.get(activity);assertNotNull(view);state.put("readerUrl",view.getUrl());for(String name:new String[]{"currentUrl","generation","loading"}){java.lang.reflect.Field f=SiteActivity.class.getDeclaredField(name);f.setAccessible(true);state.put(name,String.valueOf(f.get(activity)));}view.evaluateJavascript("({url:location.href,title:document.title,ready:document.readyState,anchors:document.querySelectorAll('a').length,text:document.body?document.body.innerText.slice(0,2000):''})",value->{values[0]=value;done.countDown();});view.evaluateJavascript("(function(){try{return "+script+"}catch(e){return {error:String(e)}}})()",value->{values[1]=value;done.countDown();});return;}}catch(Exception error){throw new AssertionError(error);}});
  assertTrue(done.await(5,TimeUnit.SECONDS));state.put("dom",new JSONTokener(values[0]).nextValue()).put("nativeReader",new JSONTokener(values[1]).nextValue());File folder=new File(app.getExternalFilesDir(null),"reader-020-diagnostic");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,"result.json"))){out.write(state.toString(2).getBytes(StandardCharsets.UTF_8));}
 }
}
