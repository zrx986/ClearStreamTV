package tv.clearstream;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.FrameLayout;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import tv.clearstream.core.BrowserUserAgent;
import tv.clearstream.core.SiteSearch;
import tv.clearstream.core.WebsitePolicy;

/** Serial public-DOM searches. The native UI, website login and playback remain separate. */
final class WebsiteSearch implements AutoCloseable {
    private static final long SITE_TIMEOUT=18000;
    static final class Target {
        final String name,url;
        Target(String name,String url){this.name=name;this.url=url;}
    }
    static final class Item {
        final String root,name,title,url,image,summary,referrerPolicy;
        Item(String root,String name,String title,String url,String image,String summary,String referrerPolicy){this.root=root;this.name=name;this.title=title;this.url=url;this.image=image;this.summary=summary;this.referrerPolicy=referrerPolicy;}
    }
    static final class Result {
        final Target site;
        final List<Item> items;
        final String status,searchUrl;
        final boolean done;
        Result(Target site,List<Item> items,String status,String searchUrl,boolean done){this.site=site;this.items=items;this.status=status;this.searchUrl=searchUrl;this.done=done;}
    }
    interface Callback {void onResult(Result result);}

    private final Activity activity;
    private final FrameLayout host;
    private final Callback callback;
    private final SharedPreferences settings;
    private final Handler main=new Handler(Looper.getMainLooper());
    private WebView reader;
    private String script,originalAgent,query,root,requestedUrl,previousUrl,currentUrl,searchUrl;
    private String itemSignature="",deliveredSignature="";
    private List<Target> targets=Collections.emptyList();
    private final List<Item> items=new ArrayList<>();
    private final Set<String> homeUrls=new HashSet<>();
    private Target site;
    private long run,siteEpoch,documentEpoch,firstItemsAt;
    private int position;
    private boolean searching,siteDone,pageFinished,documentStarted,scanInFlight,readSucceeded;
    private volatile boolean closed;

    WebsiteSearch(Activity activity,FrameLayout host,Callback callback){
        this.activity=activity;this.host=host;this.callback=callback;
        settings=((ClearApp)activity.getApplication()).settings;
    }

    void start(List<Target> requested,String word){
        if(closed)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){
            List<Target> copy=requested==null?Collections.emptyList():new ArrayList<>(requested);
            main.post(()->start(copy,word));return;
        }
        run++;siteEpoch++;documentEpoch++;main.removeCallbacksAndMessages(null);
        if(reader!=null)reader.stopLoading();
        targets=requested==null?Collections.emptyList():new ArrayList<>(requested);
        query=word==null?"":word.trim();position=0;site=null;
        next(run);
    }

    private boolean alive(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed();}
    private boolean active(long epoch){return alive()&&site!=null&&!siteDone&&epoch==siteEpoch;}

    private void next(long currentRun){
        if(!alive()||currentRun!=run)return;
        main.removeCallbacksAndMessages(null);documentEpoch++;
        if(reader!=null)reader.stopLoading();
        if(position>=targets.size()){site=null;destroyReader();return;}
        site=targets.get(position++);siteEpoch++;siteDone=false;searching=false;
        items.clear();homeUrls.clear();itemSignature="";deliveredSignature="";firstItemsAt=0;
        requestedUrl=null;previousUrl=null;currentUrl=null;searchUrl=null;
        long epoch=siteEpoch;
        if(site==null){main.post(()->next(currentRun));return;}
        root=WebsitePolicy.normalize(site.url);
        if(root==null){finish(epoch,"网站地址无效，可在搜索范围中重新添加");return;}
        if(query.isEmpty()||query.length()>200){finish(epoch,"请输入 1–200 字的影片或节目名称");return;}
        if(!ensureReader()){finish(epoch,"设备缺少可用网页组件，请更新系统 Android System WebView");return;}
        reader.getSettings().setUserAgentString(settings.getBoolean("siteDesktop:"+Uri.parse(root).getHost(),true)?BrowserUserAgent.desktop(originalAgent):originalAgent);
        main.postDelayed(()->{
            if(active(epoch))finish(epoch,items.isEmpty()?"读取超时，可打开原网站重试":"找到 "+items.size()+" 项 · 网站读取已结束");
        },SITE_TIMEOUT);
        String custom=settings.getString("siteSearch:"+root,null);
        String template=SiteSearch.validTemplate(custom)?custom:SiteSearch.builtIn(root);
        String address=SiteSearch.apply(template,query);
        load(epoch,address==null?root:address,address!=null);
    }

    private boolean ensureReader(){
        if(reader!=null)return true;
        try{
            if(script==null)try(InputStream in=activity.getAssets().open("site-reader.js")){
                script=new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8);
            }
            WebView web=new WebView(activity);reader=web;
            // A real measured viewport lets public responsive/lazy DOM render behind native cards.
            web.setVisibility(View.INVISIBLE);web.setFocusable(false);web.setFocusableInTouchMode(false);
            web.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);web.setClickable(false);
            web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            host.addView(web,0,new FrameLayout.LayoutParams(-1,-1));
            WebSettings s=web.getSettings();originalAgent=s.getUserAgentString();
            s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(true);
            s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);
            web.setWebChromeClient(new WebChromeClient(){
                @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
                @Override public boolean onJsAlert(WebView v,String url,String message,JsResult result){result.cancel();notice(url);return true;}
                @Override public boolean onJsConfirm(WebView v,String url,String message,JsResult result){result.cancel();notice(url);return true;}
                @Override public boolean onJsPrompt(WebView v,String url,String message,String value,JsPromptResult result){result.cancel();notice(url);return true;}
                private void notice(String url){long epoch=siteEpoch;if(active(epoch)&&reader!=null&&Objects.equals(url,currentUrl)&&Objects.equals(url,reader.getUrl())){items.clear();finish(epoch,"网站有待处理弹窗，请打开原网站操作");}}
            });
            return true;
        }catch(Exception unavailable){destroyReader();return false;}
    }

    private void load(long epoch,String address,boolean search){
        if(!active(epoch)||reader==null)return;
        long document=++documentEpoch;
        previousUrl=WebsitePolicy.normalize(reader.getUrl());requestedUrl=address;currentUrl=address;
        searching=search;if(search)searchUrl=address;
        pageFinished=false;documentStarted=false;scanInFlight=false;readSucceeded=false;
        reader.stopLoading();
        reader.setWebViewClient(new WebViewClient(){
            private boolean current(){return active(epoch)&&document==documentEpoch&&reader!=null;}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return WebsitePolicy.normalize(url)==null;}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return WebsitePolicy.normalize(request.getUrl().toString())==null;}
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){
                if(!current()||!Objects.equals(url,v.getUrl())||WebsitePolicy.normalize(url)==null)return;
                documentStarted=true;currentUrl=url;if(searching)searchUrl=url;
            }
            @Override public void onPageFinished(WebView v,String url){
                if(!current()||!Objects.equals(url,v.getUrl())||WebsitePolicy.normalize(url)==null)return;
                String actual=readUrl(epoch,document);if(actual==null||!actual.equals(url))return;
                pageFinished=true;scan(epoch,document);
                main.postDelayed(()->scan(epoch,document),700);
                main.postDelayed(()->scan(epoch,document),2000);
                if(!searching)main.postDelayed(()->{
                    if(active(epoch)&&document==documentEpoch&&!searching)finish(epoch,readSucceeded?"暂未识别搜索入口，请打开原网站搜索":"暂未能读取网站搜索入口，请打开原网站查看");
                },3000);
            }
            private boolean currentError(WebResourceRequest request){return current()&&request.isForMainFrame()&&Objects.equals(request.getUrl().toString(),currentUrl)&&Objects.equals(currentUrl,reader.getUrl());}
            @Override public void onReceivedError(WebView v,WebResourceRequest request,WebResourceError error){if(currentError(request))finish(epoch,"网站未能加载，可打开原网站重试");}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest request,WebResourceResponse response){if(currentError(request))finish(epoch,"网站返回 HTTP "+response.getStatusCode()+"，可打开原网站查看");}
        });
        emit(epoch,false,search?"正在搜索这个网站…":"正在识别网站搜索入口…");
        if(!active(epoch)||document!=documentEpoch)return;
        try{reader.loadUrl(address);}catch(RuntimeException unavailable){finish(epoch,"网站未能加载，可打开原网站重试");return;}
        for(int delay:new int[]{400,1200,2500,4500,7000,10000,13500,16500,17500})main.postDelayed(()->scan(epoch,document),delay);
    }

    private String readUrl(long epoch,long document){
        if(!active(epoch)||reader==null||document!=documentEpoch)return null;
        String actual=WebsitePolicy.normalize(reader.getUrl());if(actual==null)return null;
        if(!documentStarted&&Objects.equals(actual,previousUrl)&&!Objects.equals(actual,requestedUrl))return null;
        currentUrl=actual;if(searching)searchUrl=actual;return actual;
    }

    private void scan(long epoch,long document){
        String address=readUrl(epoch,document);if(address==null||scanInFlight)return;
        scanInFlight=true;
        try{reader.evaluateJavascript(envelope(),value->{
            if(!active(epoch)||document!=documentEpoch||reader==null)return;
            scanInFlight=false;
            if(!Objects.equals(address,reader.getUrl())||!Objects.equals(address,currentUrl))return;
            if(value==null||value.length()>192*1024)return;
            try{
                JSONObject outer=new JSONObject(value);if(!address.equals(outer.optString("url")))return;
                JSONObject data=outer.optJSONObject("data");if(data==null)return;readSucceeded=true;
                if(!outer.optString("blocked").isEmpty()||!data.optString("notice").isEmpty()){
                    items.clear();finish(epoch,"网站需要在原网站处理弹窗、验证或登录");return;
                }
                if(!searching){
                    String template=formTemplate(address,data.optJSONObject("form"));
                    if(template!=null){
                        JSONArray home=data.optJSONArray("items");if(home!=null)for(int i=0;i<Math.min(home.length(),120);i++){JSONObject item=home.optJSONObject(i);String url=item==null?null:WebsitePolicy.normalize(item.optString("url"));if(url!=null)homeUrls.add(url);}
                        String target=SiteSearch.apply(template,query);if(target!=null)load(epoch,target,true);
                    }
                    return;
                }
                if(returnedHome(address)){
                    items.clear();finish(epoch,"搜索返回了网站首页，请打开原网站继续搜索");return;
                }
                if(outer.optBoolean("empty")){
                    items.clear();finish(epoch,"未找到匹配内容，可打开原网站查看");return;
                }
                Set<String> recommendations=new HashSet<>();JSONArray excluded=outer.optJSONArray("recommendations");
                if(excluded!=null)for(int i=0;i<excluded.length();i++)recommendations.add(excluded.optString(i));
                List<Item> found=readItems(data.optJSONArray("items"),recommendations);
                if(found.isEmpty())return;
                String signature=signature(found);boolean stable=signature.equals(itemSignature);
                if(!stable){itemSignature=signature;items.clear();items.addAll(found);firstItemsAt=SystemClock.elapsedRealtime();emit(epoch,false,"找到 "+items.size()+" 项 · 正在完成网站读取…");}
                if(!active(epoch)||document!=documentEpoch)return;
                long stableFor=SystemClock.elapsedRealtime()-firstItemsAt;
                if(stable&&stableFor>=650&&(pageFinished||stableFor>=2500))finish(epoch,"找到 "+items.size()+" 项");
            }catch(JSONException ignored){}
        });}catch(RuntimeException unavailable){scanInFlight=false;if(active(epoch))finish(epoch,"未能读取网站内容，可打开原网站查看");}
    }

    private String formTemplate(String address,JSONObject form){
        if(form==null)return null;
        JSONArray fields=form.optJSONArray("fixed");if(fields==null||fields.length()>32)return null;
        List<String[]> fixed=new ArrayList<>();
        for(int i=0;i<fields.length();i++){JSONArray field=fields.optJSONArray(i);if(field==null||field.length()!=2)return null;fixed.add(new String[]{field.optString(0),field.optString(1)});}
        return SiteSearch.fromGetForm(address,form.optString("action"),form.optString("method"),form.optString("query"),fixed);
    }

    private List<Item> readItems(JSONArray array,Set<String> recommendations){
        List<Item> found=new ArrayList<>();Set<String> seen=new HashSet<>();if(array==null)return found;
        String name=site.name==null||site.name.trim().isEmpty()?WebsitePolicy.nameOf(root):site.name;
        for(int i=0;i<Math.min(array.length(),120);i++){
            JSONObject item=array.optJSONObject(i);if(item==null)continue;
            String url=WebsitePolicy.normalize(item.optString("url")),title=item.optString("title").trim();
            if(url==null||title.length()<2||title.length()>100||!seen.add(url))continue;
            String summary=item.optString("summary");if(summary.length()>600)summary=summary.substring(0,600);
            boolean matches=compact(title+" "+summary).contains(compact(query));
            if((recommendations.contains(url)||homeUrls.contains(url))&&!matches)continue;
            String image=WebsitePolicy.normalize(item.optString("image"));if(image!=null&&image.length()>2048)image=null;
            String policy="no-referrer".equals(item.optString("referrerPolicy"))?"no-referrer":"";
            found.add(new Item(root,name,title,url,image,summary,policy));
        }
        return found;
    }
    private static String compact(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}\\u3000\\u3001\\u3002]","");}
    private static String signature(List<Item> values){StringBuilder out=new StringBuilder();for(Item item:values)out.append(item.url).append('\n').append(item.title).append('\n').append(item.image).append('\n').append(item.summary).append('\n').append(item.referrerPolicy).append('\n');return out.toString();}
    private boolean returnedHome(String address){
        if(Objects.equals(address,requestedUrl))return false;
        try{
            java.net.URI actual=java.net.URI.create(address),home=java.net.URI.create(root);
            String path=actual.getPath()==null?"":actual.getPath().replaceAll("/$","");
            String homePath=home.getPath()==null?"":home.getPath().replaceAll("/$","");
            return Objects.equals(actual.getAuthority(),home.getAuthority())&&path.equals(homePath)&&Objects.equals(actual.getQuery(),home.getQuery());
        }catch(IllegalArgumentException ignored){return false;}
    }

    /** Additional read-only state excludes explicit empty pages and recommendation sections. */
    private String envelope(){
        return "(function(){var data="+script+";"+
            "var body=String(document.body&&document.body.innerText||'').replace(/\\s+/g,' ').slice(0,24000),title=String(document.title||''),blocked=String(data.notice||'');"+
            "function visible(el){if(!el||el.closest('[hidden],[aria-hidden=\"true\"],template'))return false;for(var n=el,d=0;n&&d<12;n=n.parentElement,d++){var s=getComputedStyle(n);if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return false;}return el.getClientRects().length>0;}"+
            "if(!blocked&&(/(?:安全验证|人机验证|正在验证|验证您是人类|just a moment|verify you are human|checking your browser)/i.test(title)||/^(?:安全验证|人机验证|正在验证|验证您是人类|verify you are human)/i.test(body)))blocked='verification';"+
            "var gates=document.querySelectorAll('input[type=password],iframe[src*=captcha],iframe[src*=recaptcha],.g-recaptcha,.h-captcha');for(var g=0;g<Math.min(gates.length,16);g++)if(visible(gates[g])){blocked='login-or-verification';break;}"+
            "var empty=/(?:未找到(?:任何|相关|符合)|没有找到.{0,16}(?:影片|视频|资源|内容|结果)|暂无(?:相关|搜索)|(?:搜索结果|相关结果).{0,10}(?:为?\\s*0\\s*(?:个|条|部)|为空)|no results|nothing found|0 results)/i.test(body);"+
            "var rec=Object.create(null),normal=Object.create(null),anchors=document.querySelectorAll('a[href]');"+
            "for(var a=0;a<Math.min(anchors.length,800);a++){var link=anchors[a],url;try{url=new URL(link.getAttribute('href'),document.baseURI).href;}catch(_){continue;}var recommendation=false;"+
            "for(var n=link,d=0;n&&n!==document.body&&d<10;n=n.parentElement,d++){var marker=String(n.className||'')+' '+String(n.id||'')+' '+String(n.getAttribute('aria-label')||'');if(/(?:^|[\\s_-])(?:recommend(?:ation)?s?|guess|hot-list|related)(?:[\\s_-]|$)|猜你喜欢|相关推荐|为你推荐/i.test(marker)){recommendation=true;break;}if(n.tagName==='SECTION'||n.tagName==='ASIDE'){var h=n.querySelector('h1,h2,h3,.module-title');if(h&&/^(?:猜你喜欢|相关推荐|为你推荐|推荐影片|热门推荐|热播推荐)$/.test(String(h.textContent||'').replace(/\\s+/g,'').trim())){recommendation=true;break;}}}"+
            "if(recommendation)rec[url]=true;else normal[url]=true;}var excluded=[];for(var key in rec)if(!normal[key])excluded.push(key);"+
            "return {url:location.href,data:data,blocked:blocked,empty:empty,recommendations:excluded};})()";
    }

    private void emit(long epoch,boolean done,String status){
        if(!alive()||site==null||epoch!=siteEpoch)return;
        String key=status+'\n'+done+'\n'+searchUrl+'\n'+signature(items);if(key.equals(deliveredSignature))return;deliveredSignature=key;
        callback.onResult(new Result(site,Collections.unmodifiableList(new ArrayList<>(items)),status,searchUrl==null?"":searchUrl,done));
    }
    private void finish(long epoch,String status){
        if(!active(epoch))return;
        siteDone=true;documentEpoch++;scanInFlight=false;main.removeCallbacksAndMessages(null);
        if(reader!=null)reader.stopLoading();
        long currentRun=run;emit(epoch,true,status);
        if(alive()&&currentRun==run&&epoch==siteEpoch)main.post(()->next(currentRun));
    }

    @Override public void close(){
        closed=true;
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::closeOnMain);return;}
        closeOnMain();
    }
    private void closeOnMain(){run++;siteEpoch++;documentEpoch++;siteDone=true;main.removeCallbacksAndMessages(null);destroyReader();items.clear();homeUrls.clear();targets=Collections.emptyList();site=null;}
    private void destroyReader(){
        WebView web=reader;reader=null;if(web==null)return;
        web.stopLoading();web.setWebViewClient(new WebViewClient());web.setWebChromeClient(null);
        if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);
        web.removeAllViews();web.destroy();
    }
}
