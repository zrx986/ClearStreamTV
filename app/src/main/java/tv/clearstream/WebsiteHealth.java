package tv.clearstream;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONException;
import org.json.JSONObject;
import tv.clearstream.core.WebsitePolicy;

/** Foreground website reachability is separate from observing an actual decoded video. */
public final class WebsiteHealth implements AutoCloseable {
    private static final String PREFS="website-health-v1";
    private static final long HTTP_TTL_MS=10*60*1000;
    private static final int MAX_PREFIX=64*1024;
    private static final Object CACHE_LOCK=new Object();
    private static final Pattern TITLE=Pattern.compile("<title(?:\\s[^>]*)?>([\\s\\S]*?)</title\\s*>",Pattern.CASE_INSENSITIVE);
    public enum State { UNKNOWN,PAGE_OK,VERIFY_REQUIRED,UNAVAILABLE }
    public enum PlaybackIssue { MEDIA_ERROR, START_TIMEOUT, NO_VIDEO, PAGE_ERROR, LEFT_SITE, CATALOG_UNREADABLE }
    /** Local callback keeps this API usable on Android 6 without Consumer desugaring. */
    public interface Callback { void accept(Snapshot snapshot); }
    public static final class Snapshot {
        public final State state;
        public final String label,reason,finalHost,playbackPage;
        public final boolean checking;
        public final int httpCode,playbackWidth,playbackHeight;
        public final long timestamp,playbackTimestamp;
        public final double playbackTime;
        public final long playbackIssueTimestamp;
        Snapshot(State state,String label,String reason,String finalHost,boolean checking,int code,long timestamp,String page,long played,int width,int height,double time,long issueAt){this.state=state;this.label=label;this.reason=reason;this.finalHost=finalHost;this.checking=checking;this.httpCode=code;this.timestamp=timestamp;this.playbackPage=page;this.playbackTimestamp=played;this.playbackWidth=width;this.playbackHeight=height;this.playbackTime=time;this.playbackIssueTimestamp=issueAt;}
    }
    private static final class Result {
        final State state;final String reason,host;final int code;final long timestamp;
        Result(State state,String reason,int code,String host){this(state,reason,code,host,System.currentTimeMillis());}
        Result(State state,String reason,int code,String host,long timestamp){this.state=state;this.reason=reason;this.code=code;this.host=host;this.timestamp=timestamp;}
    }
    private static final class Job {
        final String url;final List<Callback> callbacks=new ArrayList<>();
        Job(String url,Callback callback){this.url=url;callbacks.add(callback);}
    }
    private final SharedPreferences prefs;
    private final Handler main;
    private final OkHttpClient client;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final Map<String,Job> jobs=new HashMap<>();
    private final Set<Call> ownedCalls=new HashSet<>();
    private final Set<Runnable> posted=new HashSet<>();
    private final long callMs;
    private boolean closed;

    public WebsiteHealth(Context context,Handler main){this(context,main,5000,5000,8000);}
    WebsiteHealth(Context context,Handler handler,long connectMs,long readMs,long callMs){
        this.main=handler==null?new Handler(Looper.getMainLooper()):handler;
        if(this.main.getLooper()!=Looper.getMainLooper())throw new IllegalArgumentException("WebsiteHealth requires a main-thread Handler");
        this.prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);this.callMs=callMs;
        client=new OkHttpClient.Builder().connectTimeout(connectMs,TimeUnit.MILLISECONDS).readTimeout(readMs,TimeUnit.MILLISECONDS).callTimeout(callMs,TimeUnit.MILLISECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }
    public Snapshot status(String input){
        String url=WebsitePolicy.normalize(input);boolean checking;
        synchronized(this){checking=url!=null&&jobs.containsKey(url);}
        if(url==null)return snapshot(null,new Result(State.UNAVAILABLE,"网址无效",0,"",0),false);
        synchronized(CACHE_LOCK){return snapshot(url,readResult(url),checking);}
    }
    /** One final snapshot arrives on the main thread; status(url).checking is immediate. */
    public void check(String input,boolean force,Callback callback){
        if(callback==null)throw new IllegalArgumentException("Callback required");
        String url=WebsitePolicy.normalize(input);
        synchronized(this){
            if(closed)return;
            if(url==null){post(callback,status(input));return;}
            Job existing=jobs.get(url);if(existing!=null){existing.callbacks.add(callback);return;}
            Snapshot cached=status(url);long age=System.currentTimeMillis()-cached.timestamp;
            if(!force&&cached.timestamp>0&&age>=0&&age<HTTP_TTL_MS){post(callback,cached);return;}
            Job job=new Job(url,callback);jobs.put(url,job);
            try{workers.execute(()->run(job));}catch(RejectedExecutionException ignored){jobs.remove(url);}
        }
    }
    private void run(Job job){
        Result result=inspect(job.url);
        synchronized(this){
            if(closed||jobs.get(job.url)!=job)return;
            jobs.remove(job.url);
            synchronized(CACHE_LOCK){writeResult(job.url,result);Snapshot finalState=snapshot(job.url,result,false);for(Callback callback:job.callbacks)post(callback,finalState);}
        }
    }
    private Result inspect(String original){
        String current=original,originalHost=HttpUrl.get(original).host();long deadline=SystemClock.elapsedRealtime()+callMs;
        try{
            for(int redirects=0;;redirects++){
                if(WebsitePolicy.normalize(current)==null)return new Result(State.UNAVAILABLE,"重定向地址无效",0,"");
                long remaining=deadline-SystemClock.elapsedRealtime();if(remaining<=0)return new Result(State.UNAVAILABLE,"连接或读取超时",0,HttpUrl.get(current).host());
                HttpUrl address=HttpUrl.get(current);
                Request request=new Request.Builder().url(address).get().header("User-Agent","ClearStreamTV/"+BuildConfig.VERSION_NAME+" (WebsiteCheck)").header("Accept","text/html,application/xhtml+xml").build();
                Call call=client.newCall(request);call.timeout().timeout(remaining,TimeUnit.MILLISECONDS);
                synchronized(this){if(closed)return new Result(State.UNKNOWN,"",0,address.host());ownedCalls.add(call);}
                try(Response response=call.execute()){
                    int code=response.code();String host=address.host();
                    if(code==301||code==302||code==303||code==307||code==308){
                        HttpUrl target=address.resolve(response.header("Location", ""));
                        if(target==null||WebsitePolicy.normalize(target.toString())==null)return new Result(State.UNAVAILABLE,"重定向地址无效",code,host);
                        if(redirects>=5)return new Result(State.UNAVAILABLE,"重定向次数过多",code,host);
                        if(address.isHttps()&&!target.isHttps())return new Result(State.UNAVAILABLE,"拒绝 HTTPS 降级重定向",code,host);
                        if(!host.equals(target.host())&&genericSearch(target.host()))return new Result(State.VERIFY_REQUIRED,"入口已跳转到其他网站",code,target.host());
                        current=target.toString();continue;
                    }
                    if(code==401||code==403||code==429)return new Result(State.VERIFY_REQUIRED,"需要登录或安全确认 · HTTP "+code,code,host);
                    ResponseBody body=response.body();MediaType media=body==null?null:body.contentType();
                    boolean html=media!=null&&("text/html".equalsIgnoreCase(media.type()+"/"+media.subtype())||"application/xhtml+xml".equalsIgnoreCase(media.type()+"/"+media.subtype()));
                    String prefix="";
                    if(html&&body!=null){Charset charset=media.charset(StandardCharsets.UTF_8);byte[] sample=readPrefix(body.byteStream());if(sample.length==MAX_PREFIX)call.cancel();prefix=new String(sample,charset);if(verificationWall(prefix))return new Result(State.VERIFY_REQUIRED,"需在网页完成登录或安全验证",code,host);}
                    if(code==404||code==410)return new Result(State.UNAVAILABLE,"页面不存在 · HTTP "+code,code,host);
                    if(code>=500)return new Result(State.UNAVAILABLE,"网站暂时异常 · HTTP "+code,code,host);
                    if(code<200||code>=300)return new Result(State.UNAVAILABLE,"访问失败 · HTTP "+code,code,host);
                    if(!html||prefix.trim().startsWith("#EXTM3U"))return new Result(State.UNAVAILABLE,"返回的不是网页",code,host);
                    if(prefix.trim().isEmpty())return new Result(State.UNAVAILABLE,"网页响应为空",code,host);
                    return new Result(State.PAGE_OK,originalHost.equals(host)?"":"入口已转至 "+host,code,host);
                }finally{synchronized(this){ownedCalls.remove(call);}}
            }
        }catch(UnknownHostException error){return new Result(State.UNAVAILABLE,"域名解析失败",0,hostOf(current));}
        catch(SSLException error){return new Result(State.UNAVAILABLE,"安全连接失败",0,hostOf(current));}
        catch(InterruptedIOException error){return new Result(State.UNAVAILABLE,"连接或读取超时",0,hostOf(current));}
        catch(ConnectException error){return new Result(State.UNAVAILABLE,"无法连接网站",0,hostOf(current));}
        catch(IOException|IllegalArgumentException error){return new Result(State.UNAVAILABLE,"网络连接失败",0,hostOf(current));}
    }
    private static String hostOf(String url){try{return HttpUrl.get(url).host();}catch(IllegalArgumentException error){return "";}}
    private static boolean genericSearch(String host){for(String root:new String[]{"baidu.com","bing.com","google.com"})if(host.equals(root)||host.endsWith("."+root))return true;return false;}
    private static boolean verificationWall(String html){
        String lower=html.toLowerCase(Locale.ROOT);Matcher title=TITLE.matcher(lower);String heading=title.find()?title.group(1).replaceAll("<[^>]*>",""):"";
        if(heading.matches("[\\s\\S]*(captcha|just a moment|access denied|verify you are|security verification|安全验证|人机验证|访问验证|登录后|请登录|sign in|log in)[\\s\\S]*"))return true;
        return lower.contains("请进行安全验证")||lower.contains("请完成安全验证")||lower.contains("请完成验证后继续")||lower.contains("id=\"challenge-form\"")||lower.contains("id='challenge-form'")||Pattern.compile("<input\\b[^>]*type\\s*=\\s*['\"]?password\\b",Pattern.CASE_INSENSITIVE).matcher(lower).find();
    }
    private static byte[] readPrefix(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int read;
        while(out.size()<MAX_PREFIX&&(read=in.read(buffer,0,Math.min(buffer.length,MAX_PREFIX-out.size())))!=-1)out.write(buffer,0,read);
        return out.toByteArray();
    }
    private void post(Callback callback,Snapshot snapshot){
        Runnable task=new Runnable(){@Override public void run(){synchronized(WebsiteHealth.this){posted.remove(this);if(!closed)callback.accept(snapshot);}}};
        posted.add(task);main.post(task);
    }
    private Result readResult(String url){
        JSONObject data=json(prefs.getString("h:"+key(url),null));
        try{return new Result(State.valueOf(data.optString("state","UNKNOWN")),data.optString("reason",""),data.optInt("code"),data.optString("host",""),data.optLong("timestamp"));}
        catch(IllegalArgumentException error){return new Result(State.UNKNOWN,"",0,"",0);}
    }
    private void writeResult(String url,Result result){
        try{JSONObject data=new JSONObject().put("state",result.state.name()).put("reason",result.reason).put("host",result.host).put("code",result.code).put("timestamp",result.timestamp);prefs.edit().putString("h:"+key(url),data.toString()).apply();}catch(JSONException ignored){}
    }
    private Snapshot snapshot(String url,Result result,boolean checking){
        JSONObject proof=url==null?new JSONObject():json(prefs.getString("p:"+key(url),null));
        JSONObject issue=url==null?new JSONObject():json(prefs.getString("a:"+key(url),null));
        if(result==null)result=new Result(State.UNKNOWN,"",0,"",0);
        long played=proof.optLong("timestamp");String label;
        if(result.state==State.PAGE_OK)label=played>0?"网页可访问 · 有实播记录":"网页可访问 · 待播放验证";
        else if(result.state==State.VERIFY_REQUIRED)label="需在网页确认";
        else if(result.state==State.UNAVAILABLE)label="暂不可访问";
        else label=checking?"正在检测网页":"尚未检测";
        String reason=result.reason;long issueAt=issue.optLong("timestamp");
        if(issueAt>0){
            String issueLabel="CATALOG_UNREADABLE".equals(issue.optString("kind"))?"选片未识别 · 可用原网页":"NO_VIDEO".equals(issue.optString("kind"))?"播放待确认 · 可选剧集或原站按钮":"上次播放未成功";
            if(result.state==State.PAGE_OK||result.state==State.UNKNOWN)label=issueLabel;
            reason+=(reason.isEmpty()?"":"；")+issueReason(issue.optString("kind"));
        }
        return new Snapshot(result.state,label,reason,result.host,checking,result.code,result.timestamp,proof.optString("page",""),played,proof.optInt("width"),proof.optInt("height"),proof.optDouble("time",0),issueAt);
    }
    private static JSONObject json(String text){try{return text==null?new JSONObject():new JSONObject(text);}catch(JSONException error){return new JSONObject();}}
    private static String key(String url){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    /** Caller first observes decoded dimensions and playback advancing for at least five seconds. */
    public static void recordPlayback(Context context,String siteRoot,String pageUrl,int width,int height,double time){
        String root=WebsitePolicy.normalize(siteRoot),page=WebsitePolicy.normalize(pageUrl);
        if(root==null||page==null||width<=0||height<=0||time<0||Double.isNaN(time)||Double.isInfinite(time))return;
        synchronized(CACHE_LOCK){try{JSONObject proof=new JSONObject().put("page",page).put("timestamp",System.currentTimeMillis()).put("width",width).put("height",height).put("time",time);context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("p:"+key(root),proof.toString()).remove("a:"+key(root)).apply();}catch(JSONException ignored){}}
    }
    public static void recordPlaybackIssue(Context context,String siteRoot,String pageUrl,PlaybackIssue issue) {
        String root=WebsitePolicy.normalize(siteRoot),page=WebsitePolicy.normalize(pageUrl);if(root==null||page==null||issue==null)return;
        synchronized(CACHE_LOCK){try{JSONObject value=new JSONObject().put("page",page).put("kind",issue.name()).put("timestamp",System.currentTimeMillis());context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("a:"+key(root),value.toString()).apply();}catch(JSONException ignored){}}
    }
    public static void clearCatalogIssue(Context context,String siteRoot){
        String root=WebsitePolicy.normalize(siteRoot);if(root==null)return;synchronized(CACHE_LOCK){SharedPreferences saved=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);if("CATALOG_UNREADABLE".equals(json(saved.getString("a:"+key(root),null)).optString("kind")))saved.edit().remove("a:"+key(root)).apply();}
    }
    private static String issueReason(String kind){
        if("MEDIA_ERROR".equals(kind))return "视频线路报错，可重试或换线路";
        if("START_TIMEOUT".equals(kind))return "播放等待超时，可重试或换线路";
        if("NO_VIDEO".equals(kind))return "未检测到视频，请选择剧集或换线路";
        if("LEFT_SITE".equals(kind))return "播放页跳到其他网站，请换一个入口";
        if("CATALOG_UNREADABLE".equals(kind))return "暂未识别影片，可用原网页选片";
        return "播放页面加载失败，可刷新重试";
    }
    @Override public void close(){
        final List<Call> cancel;
        synchronized(this){if(closed)return;closed=true;cancel=new ArrayList<>(ownedCalls);ownedCalls.clear();jobs.clear();for(Runnable task:posted)main.removeCallbacks(task);posted.clear();}
        // OkHttp fast-fallback waits are not safe to interrupt: cancel only the owned sockets.
        ((ThreadPoolExecutor)workers).getQueue().clear();workers.shutdown();
        // TLS close can perform network I/O. Lifecycle callers must only invalidate UI state here.
        Thread cleanup=new Thread(()->{
            try{for(Call call:cancel)call.cancel();}
            finally{try{client.connectionPool().evictAll();}finally{client.dispatcher().executorService().shutdown();}}
        },"ClearStreamWebsiteCleanup");
        cleanup.setDaemon(true);cleanup.start();
    }
}
