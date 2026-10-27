package tv.clearstream;

import android.graphics.*;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import okhttp3.*;
import tv.clearstream.core.WebsitePolicy;

/** Bounded, activity-owned cover downloads. Never cancels the shared media client. */
final class SiteImages implements AutoCloseable {
    interface IsCurrent {boolean get();}
    interface Result {void ready(boolean success);}
    private static final int MAX_BYTES=1024*1024;
    private final Handler main;
    private final OkHttpClient client;
    private final ThreadPoolExecutor workers;
    private final Set<Call> calls=java.util.Collections.newSetFromMap(new ConcurrentHashMap<Call,Boolean>());
    private final LruCache<String,Bitmap> cache;
    private volatile boolean closed;
    SiteImages(ClearApp app,Handler main) {
        this.main=main;
        client=app.network.client("poster").newBuilder().callTimeout(8,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).build();
        cache=new LruCache<String,Bitmap>(BuildConfig.LOW_MEMORY?2*1024*1024:4*1024*1024){@Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}};
        workers=new ThreadPoolExecutor(2,2,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),r->{Thread t=new Thread(r,"site-cover");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    }
    void bind(ImageView view,String url,String page,String userAgent,IsCurrent current,Result result) {
        bind(view,url,page,userAgent,"",current,result);
    }
    void bind(ImageView view,String url,String page,String userAgent,String referrerPolicy,IsCurrent current,Result result) {
        Object identity=new Object();view.setTag(identity);view.setImageDrawable(null);
        if(closed||WebsitePolicy.normalize(url)==null){result.ready(false);return;}
        Bitmap hit=cache.get(url);if(hit!=null){view.setImageBitmap(hit);result.ready(true);return;}
        workers.execute(()->{
            if(closed||!current.get())return;
            Bitmap bitmap=null;Call call=null;
            try {
                Request.Builder request=new Request.Builder().url(url);
                if(userAgent!=null)request.header("User-Agent",userAgent);
                if(!"no-referrer".equals(referrerPolicy)&&WebsitePolicy.normalize(page)!=null){URI origin=URI.create(page);request.header("Referer",origin.getScheme()+"://"+origin.getRawAuthority()+"/");}
                call=client.newCall(request.build());calls.add(call);
                if(closed){call.cancel();return;}
                try(Response response=call.execute()) {
                    if(!response.isSuccessful()||response.body()==null||response.body().contentLength()>MAX_BYTES)throw new java.io.IOException("cover response");
                    byte[] bytes=NetworkEngine.readBounded(response.body().byteStream(),MAX_BYTES,true);
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                    if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>16384||bounds.outHeight>16384||(long)bounds.outWidth*bounds.outHeight>32000000L)throw new java.io.IOException("cover dimensions");
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
                    while(bounds.outWidth/options.inSampleSize>512||bounds.outHeight/options.inSampleSize>512)options.inSampleSize*=2;
                    bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    if(bitmap!=null&&!closed)cache.put(url,bitmap);
                }
            }catch(java.io.IOException|RuntimeException ignored){}catch(OutOfMemoryError tooLarge){cache.evictAll();}
            finally{if(call!=null)calls.remove(call);}
            final Bitmap ready=bitmap;
            main.post(()->{if(closed||view.getTag()!=identity||!current.get())return;if(ready!=null)view.setImageBitmap(ready);result.ready(ready!=null);});
        });
    }
    @Override public void close(){
        final List<Call> cancel;
        synchronized(this){if(closed)return;closed=true;cancel=new ArrayList<>(calls);calls.clear();workers.getQueue().clear();workers.shutdown();cache.evictAll();}
        // The inherited pool and dispatcher belong to NetworkEngine. Cancel only our own calls,
        // off the UI thread, without interrupting a running OkHttp fast-fallback wait.
        Thread cleanup=new Thread(()->{for(Call call:cancel)call.cancel();},"site-cover-cleanup");
        cleanup.setDaemon(true);cleanup.start();
    }
}
