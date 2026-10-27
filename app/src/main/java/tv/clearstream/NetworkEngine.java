package tv.clearstream;

import android.content.*;
import android.net.*;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import tv.clearstream.core.*;

/** Per-Network DNS and sockets; all clients retain hostname/SNI/certificate checks. */
public final class NetworkEngine {
    public final ExecutorService io=Executors.newFixedThreadPool(2);
    private final ConnectivityManager cm;
    private final android.content.SharedPreferences settings;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final Map<OkHttpClient,String> clients=new LinkedHashMap<>();
    private long generation;
    public volatile String mediaFamily="未知", mediaSpeed="—";
    public volatile long mediaConnectionTime;
    public volatile String lastProbe="尚未检测\n检测结果仅代表对应目标，不代表所有直播源。";
    public NetworkEngine(Context context, android.content.SharedPreferences prefs) {
        settings=prefs; cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { changed(); }
            @Override public void onLost(Network n) { changed(); }
        };
        if (android.os.Build.VERSION.SDK_INT>=24) cm.registerDefaultNetworkCallback(callback);
        else cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),callback);
    }
    private void changed() {
        synchronized(this) {
            generation++; mediaFamily="未知"; mediaSpeed="—";
            for (OkHttpClient c:clients.keySet()) { c.dispatcher().cancelAll(); c.connectionPool().evictAll(); }
            clients.clear();
        }
        main.post(()-> { for (Runnable listener:listeners) listener.run(); });
    }
    public void refreshPolicy() { changed(); }
    public void observe(Runnable listener) { listeners.add(listener); }
    public void removeObserver(Runnable listener) { listeners.remove(listener); }
    public AddressPolicy.Mode mode() {
        try { return AddressPolicy.Mode.valueOf(settings.getString("networkMode","AUTO")); }
        catch (Exception ignored) { return AddressPolicy.Mode.AUTO; }
    }
    public Network active() { return cm.getActiveNetwork(); }
    public String localSummary() {
        Network n=active(); if (n==null) return "网络未连接";
        LinkProperties p=cm.getLinkProperties(n); if (p==null) return "网络状态未知";
        boolean v4=false,v6=false;
        for (LinkAddress a:p.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) v4=true;
            if (AddressPolicy.isGlobalV6(a.getAddress())) v6=true;
        }
        return v4&&v6?"本地双栈 · 外网待测":v6?"本地 IPv6 · 外网待测":v4?"本地 IPv4":"仅本地地址 · 待检测";
    }
    public synchronized OkHttpClient client(String purpose) { return client(purpose,mode()); }
    public synchronized OkHttpClient client(String purpose, AddressPolicy.Mode requestedMode) {
        final Network n=active(); final long epoch=generation;
        OkHttpClient.Builder b=new OkHttpClient.Builder()
            .fastFallback(true).connectTimeout(4,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .connectionPool(new ConnectionPool(3,30,TimeUnit.SECONDS))
            .dns(host -> {
                InetAddress[] resolved=n==null?InetAddress.getAllByName(host):n.getAllByName(host);
                List<InetAddress> selected=AddressPolicy.select(Arrays.asList(resolved),requestedMode);
                if (selected.isEmpty()) throw new UnknownHostException("当前模式无可用地址");
                return selected;
            })
            .addInterceptor(chain -> {
                Request request=chain.request();
                for (int redirects=0;;redirects++) {
                    String host=request.url().host();
                    // OkHttp bypasses DNS for literals; enforce forced modes here as well.
                    boolean literalV6=host.contains(":"), literalV4=host.matches("[0-9.]+");
                    if ((literalV6&&requestedMode==AddressPolicy.Mode.IPV4)||(literalV4&&requestedMode==AddressPolicy.Mode.IPV6))
                        throw new IOException("该字面量地址与强制网络模式不匹配");
                    if (!UrlPolicy.isSupported(request.url().toString())) throw new IOException("不支持的源地址");
                    Response response=chain.proceed(request);
                    int code=response.code();
                    if (!(code==301||code==302||code==303||code==307||code==308)) return response;
                    String location=response.header("Location");
                    HttpUrl next=location==null?null:request.url().resolve(location);
                    response.close();
                    if (redirects>=5||next==null) throw new IOException("重定向次数过多或目标无效");
                    if (request.isHttps()&&!next.isHttps()) throw new IOException("拒绝 HTTPS 降级重定向");
                    Request.Builder redirect=request.newBuilder().url(next);
                    if (!request.url().host().equals(next.host())) redirect.removeHeader("Authorization").removeHeader("Cookie");
                    request=redirect.build();
                }
            })
            .eventListener(new okhttp3.EventListener() {
                @Override public void connectionAcquired(Call call, Connection connection) {
                    InetAddress peer=connection.route().socketAddress().getAddress();
                    synchronized (NetworkEngine.this) {
                        if (epoch!=generation) return;
                        if ("media".equals(purpose)) {
                            mediaFamily=peer instanceof Inet6Address?"IPv6":peer instanceof Inet4Address?"IPv4":"未知";
                            mediaConnectionTime=System.currentTimeMillis();
                        }
                    }
                }
            });
        if (n!=null) b.socketFactory(n.getSocketFactory());
        OkHttpClient result=b.build(); clients.put(result,purpose);
        // Keep closed clients out of the registry without affecting active playback.
        if (clients.size()>12) {
            Iterator<OkHttpClient> it=clients.keySet().iterator();
            while(it.hasNext()) { OkHttpClient c=it.next(); if(c.dispatcher().runningCallsCount()==0&&c!=result&&!"media".equals(clients.get(c)))it.remove(); }
        }
        return result;
    }
    public String fetchText(String url) throws IOException {
        return fetchDocument(url).text;
    }
    public static final class TextResponse {
        public final String text,url;
        TextResponse(String text,String url){this.text=text;this.url=url;}
    }
    public TextResponse fetchDocument(String url) throws IOException {
        if (!UrlPolicy.isSupported(url)) throw new IOException("请输入完整 HTTP/HTTPS 地址，IPv6 地址需用 [] 包围");
        OkHttpClient c=client("import").newBuilder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build();
        try (Response r=c.newCall(new Request.Builder().url(url).header("User-Agent","ClearStreamTV/0.1.3").build()).execute()) {
            if (!r.isSuccessful()||r.body()==null) throw new IOException("订阅返回 HTTP "+r.code());
            return new TextResponse(new String(readBounded(r.body().byteStream(),2*1024*1024,true),StandardCharsets.UTF_8),r.request().url().toString());
        }
    }
    public static byte[] readBounded(InputStream in,int max,boolean rejectOversize) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192];
        while (out.size()<=max) {
            int read=in.read(buffer,0,Math.min(buffer.length,max+1-out.size()));
            if (read<0) break; out.write(buffer,0,read);
        }
        byte[] data=out.toByteArray();
        if (data.length>max&&rejectOversize) throw new IOException("内容超过 2MB 限制");
        return data.length>max?Arrays.copyOf(data,max):data;
    }
    public String probe(String url) {
        long start=android.os.SystemClock.elapsedRealtime();
        try {
            OkHttpClient c=client("probe").newBuilder().callTimeout(8,TimeUnit.SECONDS).build();
            try (Response r=c.newCall(new Request.Builder().url(url).header("Range","bytes=0-65535").build()).execute()) {
                if (r.code()==401||r.code()==403) return "需认证 · HTTP "+r.code();
                if (!r.isSuccessful()||r.body()==null) return "暂时失败 · HTTP "+r.code();
                byte[] data=readBounded(r.body().byteStream(),64*1024,false);
                String prefix=new String(data,0,Math.min(data.length,2048),StandardCharsets.UTF_8).trim();
                if (prefix.startsWith("#EXTM3U") || prefix.contains("<MPD")) return "可解析 · "+elapsed(start)+" ms";
                if (prefix.startsWith("<")) return "响应为网页 · 不是媒体";
                return data.length>0?"可连接 · 媒体待播放验证":"空响应";
            }
        } catch (Exception e) { return "暂时失败 · "+error(e); }
    }
    private static long elapsed(long start) { return android.os.SystemClock.elapsedRealtime()-start; }
    public String diagnose() {
        StringBuilder report=new StringBuilder(localSummary()).append("\n\n");
        Network n=active();
        try {
            long start=android.os.SystemClock.elapsedRealtime();
            InetAddress[] answers=n==null?InetAddress.getAllByName("www.cloudflare.com"):n.getAllByName("www.cloudflare.com");
            int a=0,aaaa=0; for (InetAddress address:answers) { if(address instanceof Inet6Address) aaaa++; else a++; }
            report.append("系统 DNS：").append(elapsed(start)).append(" ms · A ").append(a).append(" / AAAA ").append(aaaa).append("\n");
        } catch (Exception e) { report.append("DNS：").append(error(e)).append("\n"); }
        for (AddressPolicy.Mode m:new AddressPolicy.Mode[]{AddressPolicy.Mode.IPV6,AddressPolicy.Mode.IPV4}) {
            long start=android.os.SystemClock.elapsedRealtime();
            try {
                OkHttpClient c=client("diagnostic",m).newBuilder().callTimeout(6,TimeUnit.SECONDS).build();
                try (Response r=c.newCall(new Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute()) {
                    report.append(m==AddressPolicy.Mode.IPV6?"IPv6":"IPv4").append(" 目标探测：HTTP ").append(r.code()).append(" · ").append(elapsed(start)).append(" ms\n");
                }
            } catch (Exception e) { report.append(m==AddressPolicy.Mode.IPV6?"IPv6":"IPv4").append(" 目标探测：").append(error(e)).append("\n"); }
        }
        report.append("\n目标：www.cloudflare.com\n仅代表该目标；本地地址不等于公网可达。\n当前媒体连接：").append(mediaFamily);
        lastProbe=report.toString(); return lastProbe;
    }
    public void cancelChecks() {
        synchronized(this) { for (Map.Entry<OkHttpClient,String> e:clients.entrySet()) if(!"media".equals(e.getValue()))e.getKey().dispatcher().cancelAll(); }
    }
    public static String error(Throwable e) {
        for(int depth=0;depth<8;depth++) {
            if (e instanceof UnknownHostException) return "DNS / 地址族不可用";
            if (e instanceof javax.net.ssl.SSLException) return "TLS 证书或握手失败";
            if (e instanceof java.net.SocketTimeoutException || e instanceof java.io.InterruptedIOException) return "请求超时或已取消";
            if (e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException) return "目标连接失败";
            String message=e.getMessage();
            if(message!=null&&(message.startsWith("订阅返回 HTTP ")||message.startsWith("订阅入口")||message.equals("内容超过 2MB 限制")||message.startsWith("重定向")||message.startsWith("拒绝 HTTPS")||message.startsWith("该字面量")))return message;
            if(e.getCause()==null||e.getCause()==e)break;
            e=e.getCause();
        }
        return "请求失败，请检查源与当前网络";
    }
}
