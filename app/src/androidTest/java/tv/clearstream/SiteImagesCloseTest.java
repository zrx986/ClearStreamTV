package tv.clearstream;

import android.os.Handler;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Lifecycle cleanup must not interrupt OkHttp fast-fallback or close TLS on the UI thread. */
@RunWith(AndroidJUnit4.class)
public final class SiteImagesCloseTest {
    private SiteImages images;
    @Before public void prepare(){
        ClearApp app=(ClearApp)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->images=new SiteImages(app,new Handler(Looper.getMainLooper())));
    }
    @After public void cleanup(){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(images!=null)images.close();});}
    @Test public void closeDoesNotInterruptAnAlreadyRunningPosterWorker() throws Exception {
        ExecutorService workers=(ExecutorService)field("workers");CountDownLatch running=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();
        workers.submit(()->{running.countDown();try{release.await(3,TimeUnit.SECONDS);interrupted.set(Thread.currentThread().isInterrupted());}catch(InterruptedException error){interrupted.set(true);}finally{finished.countDown();}});
        assertTrue("Poster worker must already be running",running.await(2,TimeUnit.SECONDS));
        try{InstrumentationRegistry.getInstrumentation().runOnMainSync(images::close);}finally{release.countDown();}
        assertTrue(finished.await(2,TimeUnit.SECONDS));assertFalse("Closing posters must cancel owned calls without interrupting the running worker",interrupted.get());
    }
    @Test public void ownedCallCancellationRunsOffMainAndLeavesSharedDispatcherOpen() throws Exception {
        CountDownLatch canceled=new CountDownLatch(1);AtomicBoolean mainThread=new AtomicBoolean();
        Call owned=(Call)Proxy.newProxyInstance(Call.class.getClassLoader(),new Class<?>[]{Call.class},(proxy,method,args)->{
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            if(method.getName().equals("cancel")){mainThread.set(Looper.myLooper()==Looper.getMainLooper());canceled.countDown();return null;}
            if(method.getReturnType()==boolean.class)return false;
            return null;
        });
        @SuppressWarnings("unchecked") Set<Call> calls=(Set<Call>)field("calls");calls.add(owned);OkHttpClient client=(OkHttpClient)field("client");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(images::close);
        assertTrue("The activity-owned call must be canceled",canceled.await(2,TimeUnit.SECONDS));assertFalse("TLS cancellation must not run on the main thread",mainThread.get());
        assertFalse("Poster clients share the base dispatcher's executor",client.dispatcher().executorService().isShutdown());
    }
    private Object field(String name) throws Exception {Field field=SiteImages.class.getDeclaredField(name);field.setAccessible(true);return field.get(images);}
}
