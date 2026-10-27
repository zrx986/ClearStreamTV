package tv.clearstream;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import tv.clearstream.core.WebsitePolicy;
import tv.clearstream.core.BrowserUserAgent;

/** TV website portal. It does not extract media URLs or bypass a platform player. */
public final class WebActivity extends Activity {
    private WebView web;
    private FrameLayout root,webHost;
    private LinearLayout toolbar;
    private TextView title,hint,pointerButton,versionButton;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private CursorView cursor;
    private String initialUrl;
    private String siteRoot;
    private boolean mediaForeground;
    private boolean officialCctv,pickingAds,previousPointer;
    private AdProtection adProtection;
    private String adScript;
    public volatile org.json.JSONObject adAudit=new org.json.JSONObject();
    private OfficialCctvPlayer officialPlayer;
    private String cctvScript,layoutScript;
    private int actualMediaWidth,actualMediaHeight;
    private boolean layoutFullscreen;
    public volatile org.json.JSONObject layoutAudit=new org.json.JSONObject();
    private long documentGeneration;
    private WebsitePlaybackMonitor playbackMonitor;
    private final tv.clearstream.core.PlaybackProof playbackProof=new tv.clearstream.core.PlaybackProof();
    private final Runnable observeMedia=new Runnable(){public void run(){
        if(!mediaForeground||web==null)return;if(layoutFullscreen)web.evaluateJavascript("window.__clearPlayerLayout&&window.__clearPlayerLayout.snapshot()",raw->{try{layoutAudit=new org.json.JSONObject(raw);}catch(org.json.JSONException ignored){}});String page=web.getUrl();long epoch=documentGeneration;
        web.evaluateJavascript(officialCctv?"window.__clearCctv?window.__clearCctv.sample():{video:false}":"(function(){var v=document.querySelector('video');return v?{width:v.videoWidth,height:v.videoHeight,time:v.currentTime,paused:v.paused,ready:v.readyState,error:v.error?v.error.code:0}:null})()",value->{
            if(!mediaForeground||web==null||epoch!=documentGeneration||!java.util.Objects.equals(page,web.getUrl()))return;
            if(officialCctv&&value!=null)try{org.json.JSONObject state=new org.json.JSONObject(value);if(officialPlayer!=null)officialPlayer.sample(state);if("paused".equals(state.optString("cctvStatus")))playbackMonitor.cancelAttempt();org.json.JSONObject click=state.optJSONObject("cctvClick");if(click!=null){double x=click.optDouble("x",-1),y=click.optDouble("y",-1);if(x>0&&x<1&&y>0&&y<1)touch((float)x*web.getWidth(),(float)y*web.getHeight());}}catch(org.json.JSONException ignored){}
            if(value==null||!tv.clearstream.core.PlaybackProof.canAssociate(siteRoot,initialUrl,page)){playbackProof.reset();return;}
            try{org.json.JSONObject v=new org.json.JSONObject(value);actualMediaWidth=v.optInt("width");actualMediaHeight=v.optInt("height");playbackMonitor.sample(page,true,v.optInt("width"),v.optInt("height"),v.optDouble("time"),v.optBoolean("paused",true),v.optInt("ready"),v.optInt("error"));if(playbackProof.observe(page,v.optInt("width"),v.optInt("height"),v.optDouble("time"),v.optBoolean("paused",true),v.optInt("ready"),v.optInt("error"),SystemClock.elapsedRealtime()))WebsiteHealth.recordPlayback(WebActivity.this,siteRoot,page,v.optInt("width"),v.optInt("height"),v.optDouble("time"));}catch(org.json.JSONException ignored){playbackProof.reset();}
        });main.postDelayed(this,750);
    }};
    private String originalAgent,versionKey;
    private boolean desktop;
    private boolean pointer=false,tvMode=true,nativeFullscreen;
    private String adapterScript;
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);initialUrl=WebsitePolicy.normalize(getIntent().getStringExtra("url"));
        if(initialUrl==null){finish();return;}
        officialCctv=getIntent().getBooleanExtra("officialCctv",false)&&tv.clearstream.core.CctvPagePolicy.isOfficialPage(initialUrl);if(officialCctv)tvMode=false;
        siteRoot=WebsitePolicy.normalize(getIntent().getStringExtra("siteRoot"));if(siteRoot==null)siteRoot=initialUrl;
        adProtection=new AdProtection(this,siteRoot);
        playbackMonitor=new WebsitePlaybackMonitor(this,siteRoot,initialUrl,main,30000);
        versionKey="siteDesktop:"+Uri.parse(initialUrl).getHost();desktop=officialCctv||((ClearApp)getApplication()).settings.getBoolean(versionKey,true);
        if(officialCctv)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root=new FrameLayout(this);root.setBackgroundColor(0xff101418);setContentView(root);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);root.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(12),dp(6),dp(12),dp(6));layout.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(58)));
        if(officialCctv){addButton("播放 / 暂停",()->{toggleOfficial();toolbar.setVisibility(View.GONE);if(web!=null)web.requestFocus();});addButton("重新加载",this::reloadOfficial);addButton("返回选台",this::back);toolbar.setVisibility(View.GONE);}
        else {
        addButton("返回",this::back);addButton("刷新",()->{if(web!=null)web.reload();});
        pointerButton=addButton("电视适配",this::chooseWebMode);
        versionButton=addButton(desktop?"桌面版":"手机版",this::chooseVersion);
        addButton("全屏",this::fitPlayer);
        addButton("外部打开",this::external);addButton("关闭",this::finish);
        }
        if(!officialCctv){TextView quality=addButton("画质",()->VideoQualityDialog.show(this,actualMediaWidth>0?actualMediaWidth:layoutAudit.optInt("mediaWidth"),actualMediaHeight>0?actualMediaHeight:layoutAudit.optInt("mediaHeight"),()->{hideVideoFullscreen();if(layoutFullscreen)exitPlayerFit();web.requestFocus();Toast.makeText(this,"已回到原站，请在播放器菜单选择网站提供的更高画质",Toast.LENGTH_LONG).show();}));quality.setContentDescription("视频画质");}
        if(!officialCctv)addButton("广告防护",()->adProtection.choose(()->{if(web!=null)web.reload();},this::beginAdPicker));
        title=new TextView(this);title.setTextColor(0xffb1bfce);font(title,12);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(12),0,0,0);title.setText(getIntent().getStringExtra("name"));toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        webHost=new FrameLayout(this);layout.addView(webHost,new LinearLayout.LayoutParams(-1,0,1));
        try {web=new WebView(this);}catch(RuntimeException e){TextView failure=new TextView(this);failure.setText(officialCctv?"设备缺少可用网页组件。\n请更新系统 Android System WebView 后重试。\n按返回键回到选台。":"设备缺少可用网页组件。\n可以用顶部“外部打开”，或更新系统 Android System WebView。");failure.setTextColor(Color.WHITE);font(failure,22);webHost.addView(failure);return;}
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setMediaPlaybackRequiresUserGesture(true);
        originalAgent=settings.getUserAgentString();if(desktop)settings.setUserAgentString(BrowserUserAgent.desktop(originalAgent));
        settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setBackgroundColor(0xff101418);web.setFocusable(true);web.setFocusableInTouchMode(true);webHost.addView(web,new FrameLayout.LayoutParams(-1,-1));
        cursor=new CursorView();cursor.setVisibility(View.GONE);webHost.addView(cursor,new FrameLayout.LayoutParams(-1,-1));
        hint=new TextView(this);hint.setText("方向键选择 · 确定进入 · 菜单键打开工具栏 · 可切换鼠标或原网页");hint.setTextColor(Color.WHITE);font(hint,12);hint.setPadding(dp(12),dp(6),dp(12),dp(6));hint.setBackgroundColor(0xc0101418);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);webHost.addView(hint,hp);
        if(officialCctv){hint.setVisibility(View.GONE);officialPlayer=new OfficialCctvPlayer(this,webHost,this::reloadOfficial,this::back);}
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){documentGeneration++;layoutFullscreen=false;nativeFullscreen=false;toolbar.setVisibility(officialCctv?View.GONE:View.VISIBLE);if(hint!=null)hint.setVisibility(officialCctv?View.GONE:View.VISIBLE);playbackProof.reset();playbackMonitor.newDocument(url);if(officialPlayer!=null)officialPlayer.loading();}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return blockUnsupported(url);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return blockUnsupported(request.getUrl().toString());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){if(adProtection.shouldBlock(request.getUrl().toString(),request.isForMainFrame()))return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));return null;}
            @Override public void onPageFinished(WebView view,String url){installAds();title.setText(view.getTitle()==null?Uri.parse(url).getHost():view.getTitle());if(officialCctv)installOfficialPlayer();else if(tvMode)installAdapter();}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()&&java.util.Objects.equals(view.getUrl(),request.getUrl().toString())){title.setText("页面未能加载 · 可刷新或外部打开");playbackMonitor.failed(request.getUrl().toString(),WebsiteHealth.PlaybackIssue.PAGE_ERROR);if(officialPlayer!=null)officialPlayer.failure("央视官网暂时未能连接\n请检查网络后重试");}}
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view,int progress){if(progress<100)title.setText("正在加载 "+progress+"%");}
            @Override public void onShowCustomView(View view,CustomViewCallback callback) {
                if(customView!=null){callback.onCustomViewHidden();return;}
                customView=view;customCallback=callback;root.addView(view,new FrameLayout.LayoutParams(-1,-1));
            }
            @Override public void onHideCustomView(){hideVideoFullscreen();}
            @Override public void onPermissionRequest(PermissionRequest request) {
                Uri origin=request.getOrigin();String current=web.getUrl();
                boolean protectedMediaOnly=request.getResources().length==1&&PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(request.getResources()[0]);
                if(protectedMediaOnly&&"https".equals(origin.getScheme())&&current!=null&&origin.getHost()!=null&&origin.getHost().equals(Uri.parse(current).getHost()))request.grant(request.getResources());
                else request.deny();
            }
        });
        web.loadUrl(initialUrl);web.requestFocus();
    }
    private boolean blockUnsupported(String url) {
        if(officialCctv)return !tv.clearstream.core.CctvPagePolicy.isOfficialPage(url)||!initialUrl.equals(url);
        if(WebsitePolicy.normalize(url)!=null)return false;
        Toast.makeText(this,"此链接需要其他应用，可使用顶部“外部打开”",Toast.LENGTH_SHORT).show();return true;
    }
    private void installOfficialPlayer(){
        if(web==null||!officialCctv||!tv.clearstream.core.CctvPagePolicy.isOfficialPage(web.getUrl()))return;
        try{if(cctvScript==null)try(java.io.InputStream in=getAssets().open("cctv-player.js")){cctvScript=new String(NetworkEngine.readBounded(in,128*1024,true),java.nio.charset.StandardCharsets.UTF_8);}web.evaluateJavascript(cctvScript+";window.__clearCctvFactory({enabled:true,foreground:"+mediaForeground+"});",null);}
        catch(Exception error){if(officialPlayer!=null)officialPlayer.failure("播放控制暂时未能启动\n请重试，或返回选台");}
    }
    private void toggleOfficial(){if(!mediaForeground||web==null)return;playbackMonitor.request(web.getUrl());web.evaluateJavascript("window.__clearCctv&&window.__clearCctv.request('toggle')",null);}
    private void reloadOfficial(){if(web==null)return;toolbar.setVisibility(View.GONE);hideVideoFullscreen();if(officialPlayer!=null)officialPlayer.loading();web.loadUrl(initialUrl);web.requestFocus();}
    private void installAdapter() {
        if(web==null||!tvMode)return;
        try {
            if(adapterScript==null)try(java.io.InputStream in=getAssets().open("tv-adapter.js")) {
                adapterScript=new String(NetworkEngine.readBounded(in,128*1024,true),java.nio.charset.StandardCharsets.UTF_8);
            }
            StringBuilder full=new StringBuilder();for(String asset:new String[]{"site-plugins.js","site-plugins-custom.js"})try(java.io.InputStream in=getAssets().open(asset)){full.append(new String(NetworkEngine.readBounded(in,128*1024,true),java.nio.charset.StandardCharsets.UTF_8)).append(";\n");}full.append("ClearStreamTVPlugins.foreground(").append(mediaForeground&&adProtection.plugins).append(");").append(adapterScript);web.evaluateJavascript(full.toString(),null);
        }catch(Exception e){Toast.makeText(this,"此页未能启用适配，可切换鼠标模式",Toast.LENGTH_LONG).show();}
    }
    private void chooseWebMode() {
        if(web==null)return;
        int selected=tvMode?0:pointer?1:2;
        new AlertDialog.Builder(this).setTitle("网页操作模式").setSingleChoiceItems(new String[]{"电视适配 · 大按钮与方向键焦点","遥控鼠标 · 适配不佳时使用","原始网页 · 保留网站布局"},selected,(d,which)-> {
            tvMode=which==0;pointer=which==1;
            pointerButton.setText(tvMode?"电视适配":pointer?"遥控鼠标":"原始网页");cursor.setVisibility(pointer?View.VISIBLE:View.GONE);
            if(tvMode)installAdapter();else web.evaluateJavascript("window.__clearTV&&window.__clearTV.enable(false)",null);
            hint.setText(tvMode?"方向键选择 · 确定进入 · 菜单键打开工具栏":pointer?"方向键移动鼠标 · 确定点击 · 菜单键打开工具栏":"原始网页模式 · 菜单键可切回电视适配");
            d.dismiss();web.requestFocus();
        }).setNegativeButton("返回",null).show();
    }
    private void installAds(){if(web==null)return;try{if(adScript==null)try(java.io.InputStream in=getAssets().open("adblock-dom.js")){adScript=new String(NetworkEngine.readBounded(in,128*1024,true),java.nio.charset.StandardCharsets.UTF_8);}web.evaluateJavascript(adScript+";window.__clearAds.configure("+adProtection.config()+");({hidden:window.__clearAds.hidden()})",value->{try{adAudit=new org.json.JSONObject(value);}catch(org.json.JSONException ignored){}});}catch(java.io.IOException ignored){}}
    private void beginAdPicker(){if(web==null)return;if(layoutFullscreen)exitPlayerFit();hideVideoFullscreen();installAds();pickingAds=true;previousPointer=pointer;pointer=true;cursor.setVisibility(View.VISIBLE);toolbar.setVisibility(View.GONE);hint.setVisibility(View.VISIBLE);hint.setText("点选广告：方向键移动光标 · 确定选择 · 返回取消");cursor.initialize();web.requestFocus();web.evaluateJavascript("window.__clearAds&&window.__clearAds.startPicker()",null);sendAdPoint(false);}
    private void sendAdPoint(boolean select){if(web==null)return;String script="window.__clearAds&&window.__clearAds."+(select?"pickAt":"point")+"("+(cursor.x/web.getWidth())+","+(cursor.y/web.getHeight())+")";web.evaluateJavascript(script,value->{if(!select||!pickingAds||!mediaForeground)return;try{org.json.JSONObject result=new org.json.JSONObject(value);if(!result.optBoolean("ok")){Toast.makeText(this,"请选择广告区域，不能选择整个网页或视频",Toast.LENGTH_LONG).show();return;}endAdPicker();adProtection.confirmPick(result,this::installAds);}catch(org.json.JSONException ignored){}});}
    private void endAdPicker(){if(web!=null)web.evaluateJavascript("window.__clearAds&&window.__clearAds.cancelPicker()",null);pickingAds=false;pointer=previousPointer;cursor.setVisibility(pointer?View.VISIBLE:View.GONE);toolbar.setVisibility(View.VISIBLE);hint.setText("方向键选择 · 确定进入 · 菜单键打开工具栏");if(web!=null)web.requestFocus();}
    private void fitPlayer(){
        if(web==null)return;
        try{
            if(layoutScript==null)try(java.io.InputStream in=getAssets().open("player-layout.js")){layoutScript=new String(NetworkEngine.readBounded(in,128*1024,true),java.nio.charset.StandardCharsets.UTF_8);}
            long epoch=documentGeneration;
            web.evaluateJavascript(layoutScript+";window.__clearPlayerLayout.enter()",value->{
                if(web==null||epoch!=documentGeneration)return;
                try{org.json.JSONObject result=new org.json.JSONObject(value);layoutAudit=result;if(!result.optBoolean("active")){Toast.makeText(this,"未找到播放画面，请先进入影片播放页",Toast.LENGTH_LONG).show();return;}
                    layoutFullscreen=true;nativeFullscreen=true;toolbar.setVisibility(View.GONE);hint.setVisibility(View.GONE);web.requestFocus();
                }catch(org.json.JSONException error){Toast.makeText(this,"此页播放器暂不能放大，请尝试原站全屏按钮",Toast.LENGTH_LONG).show();}
            });
        }catch(java.io.IOException error){Toast.makeText(this,"全屏组件未能加载，请重试",Toast.LENGTH_LONG).show();}
    }
    private void exitPlayerFit(){if(web!=null)web.evaluateJavascript("window.__clearPlayerLayout&&window.__clearPlayerLayout.exit()",null);layoutFullscreen=false;nativeFullscreen=false;toolbar.setVisibility(View.VISIBLE);if(hint!=null)hint.setVisibility(View.VISIBLE);toolbar.getChildAt(0).requestFocus();}
    private void chooseVersion(){if(web==null)return;new AlertDialog.Builder(this).setTitle("网站版本（重新打开当前入口）").setSingleChoiceItems(new String[]{"桌面版 · 电视优先","手机版 · 站点需要时使用"},desktop?0:1,(d,i)->{desktop=i==0;((ClearApp)getApplication()).settings.edit().putBoolean(versionKey,desktop).apply();web.getSettings().setUserAgentString(desktop?BrowserUserAgent.desktop(originalAgent):originalAgent);versionButton.setText(desktop?"桌面版":"手机版");d.dismiss();web.loadUrl(initialUrl);web.requestFocus();}).setNegativeButton("返回",null).show();}
    private void touch(float x,float y) {
        if(officialCctv&&!mediaForeground)return;
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0);
        web.dispatchTouchEvent(down);web.dispatchTouchEvent(up);down.recycle();up.recycle();
    }
    private float layoutScale(){android.util.DisplayMetrics m=getResources().getDisplayMetrics();return Math.min(1f,Math.min(m.widthPixels/m.density/960f,m.heightPixels/m.density/540f));}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density*layoutScale());}
    private void font(TextView view,int size){view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(size)*Math.min(1.15f,getResources().getConfiguration().fontScale));}
    private TextView addButton(String name,Runnable action) {
        TextView b=new TextView(this);b.setText(name);b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(14)*Math.min(1.15f,getResources().getConfiguration().fontScale));b.setSingleLine(true);b.setTextColor(Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(dp(10),dp(8),dp(10),dp(8));b.setFocusable(true);b.setFocusableInTouchMode(true);
        b.setBackground(background(false));b.setOnFocusChangeListener((v,focus)->v.setBackground(background(focus)));b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-1);p.setMargins(dp(3),0,dp(3),0);toolbar.addView(b,p);return b;
    }
    private GradientDrawable background(boolean focus){GradientDrawable d=new GradientDrawable();d.setColor(focus?0xff243b55:0xff1b222b);d.setCornerRadius(dp(8));if(focus)d.setStroke(dp(2),0xff4c9aff);return d;}
    private void external(){
        String url=web==null?initialUrl:web.getUrl();if(WebsitePolicy.normalize(url)==null)url=initialUrl;
        try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW,Uri.parse(url)),"选择已安装的官方客户端或浏览器"));}
        catch(ActivityNotFoundException e){Toast.makeText(this,"设备没有可处理此网址的应用",Toast.LENGTH_LONG).show();}
    }
    private void hideVideoFullscreen(){if(customView!=null){root.removeView(customView);customView=null;if(customCallback!=null){customCallback.onCustomViewHidden();customCallback=null;}}}
    private void back(){
        if(officialCctv){if(web!=null)web.evaluateJavascript("window.__clearCctv&&window.__clearCctv.foreground(false)",null);hideVideoFullscreen();finish();return;}
        if(pickingAds){endAdPicker();return;}
        if(customView!=null){hideVideoFullscreen();return;}
        if(layoutFullscreen){exitPlayerFit();return;}
        if(nativeFullscreen){nativeFullscreen=false;toolbar.setVisibility(View.VISIBLE);toolbar.getChildAt(0).requestFocus();return;}
        if(web!=null&&web.canGoBack())web.goBack();else finish();
    }
    @Override public void onBackPressed(){back();}
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int officialKey=event.getKeyCode();
        if(pickingAds&&officialKey==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)endAdPicker();return true;}
        if(officialCctv){
            if(officialKey==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)back();return true;}
            if(officialKey==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){toolbar.setVisibility(View.VISIBLE);toolbar.getChildAt(0).requestFocus();}return true;}
            if(web!=null&&web.hasFocus()){
                if(officialKey==KeyEvent.KEYCODE_DPAD_CENTER||officialKey==KeyEvent.KEYCODE_ENTER||officialKey==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)toggleOfficial();return true;}
                if(officialKey==KeyEvent.KEYCODE_DPAD_UP||officialKey==KeyEvent.KEYCODE_DPAD_DOWN||officialKey==KeyEvent.KEYCODE_DPAD_LEFT||officialKey==KeyEvent.KEYCODE_DPAD_RIGHT)return true;
            }
        }
        if(web==null)return super.dispatchKeyEvent(event);
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()==KeyEvent.KEYCODE_MENU){if(layoutFullscreen)exitPlayerFit();nativeFullscreen=false;toolbar.setVisibility(View.VISIBLE);if(hint!=null)hint.setVisibility(View.VISIBLE);toolbar.getChildAt(0).requestFocus();return true;}
        int tvKey=event.getKeyCode();
        if(tvMode&&!pickingAds&&customView==null&&web.hasFocus()&&(tvKey==19||tvKey==20||tvKey==21||tvKey==22||tvKey==23||tvKey==66)) {
            if(event.getAction()==KeyEvent.ACTION_DOWN) {
                web.evaluateJavascript("window.__clearTV?window.__clearTV.move("+tvKey+"):'native'",result->{
                    if(isFinishing()||isDestroyed()||!tvMode)return;
                    if("\"toolbar\"".equals(result)){nativeFullscreen=false;toolbar.setVisibility(View.VISIBLE);toolbar.getChildAt(0).requestFocus();}
                    else if("\"native\"".equals(result)){
                        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,tvKey));web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,tvKey));
                    }else if(result!=null&&result.startsWith("{"))try {
                        org.json.JSONObject click=new org.json.JSONObject(result);
                        if("click".equals(click.optString("action")))touch((float)click.getDouble("x")*web.getWidth(),(float)click.getDouble("y")*web.getHeight());
                    }catch(org.json.JSONException ignored){}
                });
            }
            return true;
        }
        if(pointer&&customView==null&&web.hasFocus()) {
            int key=event.getKeyCode();boolean arrow=key==19||key==20||key==21||key==22,ok=key==23||key==66;
            if(arrow||ok) {
                if(event.getAction()==KeyEvent.ACTION_DOWN) {
                    cursor.initialize();float step=dp(event.getRepeatCount()>5?32:18);
                    if(key==19) {if(cursor.y<dp(24)&&toolbar.getVisibility()==View.VISIBLE){toolbar.getChildAt(0).requestFocus();return true;}cursor.y-=step;}
                    if(key==20)cursor.y+=step;if(key==21)cursor.x-=step;if(key==22)cursor.x+=step;
                    if(cursor.y>web.getHeight()-dp(35)){web.scrollBy(0,dp(80));cursor.y=web.getHeight()-dp(45);}
                    if(cursor.y<dp(12)){web.scrollBy(0,-dp(80));cursor.y=dp(12);}
                    cursor.x=Math.max(dp(12),Math.min(web.getWidth()-dp(12),cursor.x));cursor.invalidate();
                    if(pickingAds)sendAdPoint(ok);else if(ok)touch(cursor.x,cursor.y);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onPause(){if(pickingAds)endAdPicker();mediaForeground=false;documentGeneration++;if(officialPlayer!=null)officialPlayer.foreground(false);if(playbackMonitor!=null)playbackMonitor.pause();playbackProof.reset();main.removeCallbacks(observeMedia);if(web!=null){web.evaluateJavascript("window.__clearPlayerLayout&&window.__clearPlayerLayout.pause();window.ClearStreamTVPlugins&&window.ClearStreamTVPlugins.foreground(false);window.__clearCctv&&window.__clearCctv.foreground(false);document.querySelectorAll('video,audio').forEach(function(v){v.pause();})",null);web.onPause();}super.onPause();}
    @Override protected void onResume(){super.onResume();mediaForeground=true;if(officialPlayer!=null)officialPlayer.foreground(true);if(playbackMonitor!=null)playbackMonitor.resume();if(web!=null){web.onResume();web.evaluateJavascript("window.ClearStreamTVPlugins&&window.ClearStreamTVPlugins.foreground("+adProtection.plugins+")",null);if(officialCctv)web.evaluateJavascript("window.__clearCctv&&window.__clearCctv.foreground(true)",null);main.removeCallbacks(observeMedia);main.post(observeMedia);}}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);if(officialPlayer!=null)officialPlayer.destroy();hideVideoFullscreen();if(web!=null){web.stopLoading();webHost.removeView(web);web.destroy();}getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onDestroy();}
    private final class CursorView extends View {
        float x,y;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        CursorView(){super(WebActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);setClickable(false);}
        void initialize(){if(x==0&&getWidth()>0){x=getWidth()/2f;y=getHeight()/2f;}}
        @Override protected void onDraw(Canvas c){initialize();paint.setStyle(Paint.Style.FILL);paint.setColor(0xb0101418);c.drawCircle(x,y,dp(10),paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(Color.WHITE);c.drawCircle(x,y,dp(8),paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(2),paint);}
    }
}
