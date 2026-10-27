package tv.clearstream;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebExtension;
import tv.clearstream.core.WebsitePolicy;

/** Modern website playback: the website remains responsible for its own media player. */
public final class ModernWebActivity extends Activity {
    private static final String EXTENSION_ID="clearstream-tv@local",NATIVE_APP="clearstream";
    private static GeckoRuntime runtime;
    // Gecko152 can deliver already queued callbacks after unregistering a port.
    private static final WebExtension.PortDelegate CLOSED_PORT=new WebExtension.PortDelegate() {};
    public GeckoSession session;
    public GeckoView view;
    /** Read-only diagnostic snapshot from the bundled top-level content script, never media URLs. */
    public volatile JSONObject auditState=new JSONObject();
    public volatile boolean backgroundPauseConfirmed;
    private FrameLayout webHost;
    private LinearLayout toolbar;
    private TextView title,hint,modeButton;
    private CursorView cursor;
    private WebExtension extension;
    private WebExtension.Port port;
    private MediaSession mediaSession;
    private String initialUrl,currentUrl;
    private String siteRoot;
    private final tv.clearstream.core.PlaybackProof playbackProof=new tv.clearstream.core.PlaybackProof();
    private WebsitePlaybackMonitor playbackMonitor;
    private boolean officialCctv,pickingAds,previousPointer,adReloadPending;
    private AdProtection adProtection;
    private OfficialCctvPlayer officialPlayer;
    private boolean canGoBack,tvMode=true,pointer,nativeFullscreen,pageFullscreen,layoutFullscreen,foreground,destroyed,mediaErrorHint;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        initialUrl=WebsitePolicy.normalize(getIntent().getStringExtra("url"));
        if(initialUrl==null){finish();return;}
        officialCctv=getIntent().getBooleanExtra("officialCctv",false)&&tv.clearstream.core.CctvPagePolicy.isOfficialPage(initialUrl);if(officialCctv)tvMode=false;
        currentUrl=initialUrl;
        siteRoot=WebsitePolicy.normalize(getIntent().getStringExtra("siteRoot"));if(siteRoot==null)siteRoot=initialUrl;
        adProtection=new AdProtection(this,siteRoot);
        playbackMonitor=new WebsitePlaybackMonitor(this,siteRoot,initialUrl,new android.os.Handler(android.os.Looper.getMainLooper()),30000);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff101418);setContentView(root);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);root.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(12),dp(6),dp(12),dp(6));layout.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(58)));
        if(officialCctv){addButton("播放 / 暂停",()->{sendCommand("toggleVideo");toolbar.setVisibility(View.GONE);if(view!=null)view.requestFocus();});addButton("重新加载",this::reload);addButton("返回选台",this::back);toolbar.setVisibility(View.GONE);}
        else {
        addButton("返回",this::back);
        addButton("播放 / 暂停",()->{sendCommand("toggleVideo");if(view!=null)view.requestFocus();});
        modeButton=addButton("电视适配",this::chooseMode);
        addButton("全屏",()->sendCommand("fullscreenVideo"));
        addButton("收起工具栏",()->{nativeFullscreen=true;toolbar.setVisibility(View.GONE);if(view!=null)view.requestFocus();});
        addButton("刷新",this::reload);
        addButton("关闭",this::finish);
        }
        if(!officialCctv){TextView quality=addButton("画质",()->{int width=auditState.optInt("width"),height=auditState.optInt("height");if(width<=0){width=auditState.optInt("layoutMediaWidth");height=auditState.optInt("layoutMediaHeight");}VideoQualityDialog.show(this,width,height,()->{showToolbar();view.requestFocus();Toast.makeText(this,"已回到原站，请在播放器菜单选择网站提供的更高画质",Toast.LENGTH_LONG).show();});});quality.setContentDescription("视频画质");}
        if(!officialCctv)addButton("广告防护",()->adProtection.choose(()->{adReloadPending=true;sendEnable();},this::beginAdPicker));
        title=new TextView(this);title.setTextColor(0xffb1bfce);font(title,12);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(12),0,0,0);title.setText("正在准备网页播放器");toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        webHost=new FrameLayout(this);layout.addView(webHost,new LinearLayout.LayoutParams(-1,0,1));
        try {
            if(runtime==null)runtime=GeckoRuntime.create(getApplicationContext(),new GeckoRuntimeSettings.Builder().javaScriptEnabled(true).build());
            view=new GeckoView(this);view.setBackgroundColor(0xff101418);view.setFocusable(true);view.setFocusableInTouchMode(true);webHost.addView(view,new FrameLayout.LayoutParams(-1,-1));
            cursor=new CursorView();cursor.setVisibility(View.GONE);webHost.addView(cursor,new FrameLayout.LayoutParams(-1,-1));
            hint=new TextView(this);hint.setTextColor(Color.WHITE);font(hint,12);hint.setPadding(dp(12),dp(6),dp(12),dp(6));hint.setBackgroundColor(0xc0101418);updateHint();
            webHost.addView(hint,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
            if(officialCctv){hint.setVisibility(View.GONE);officialPlayer=new OfficialCctvPlayer(this,webHost,this::reload,this::back);}
            session=new GeckoSession(new GeckoSessionSettings.Builder().userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP).viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build());
            configureSession();
            session.open(runtime);view.setSession(session);
            runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/tv-engine/",EXTENSION_ID).accept(installed->runOnUiThread(()->{
                if(destroyed||isFinishing())return;
                extension=installed;
                session.getWebExtensionController().setMessageDelegate(installed,messageDelegate,NATIVE_APP);
                session.loadUri(initialUrl);view.requestFocus();
            }),error->runOnUiThread(()->{if(!destroyed){title.setText("遥控适配未能启动 · 可刷新重试");if(officialPlayer!=null)officialPlayer.failure("播放控制暂时未能启动\n请重试，或返回选台");else Toast.makeText(this,"网页控制组件加载失败",Toast.LENGTH_LONG).show();}}));
        }catch(RuntimeException|LinkageError error){
            title.setText("网页内核未能启动");
            if(officialPlayer!=null){officialPlayer.failure("网页内核暂时未能启动\n请重试，或返回选台");return;}
            TextView failure=new TextView(this);failure.setText("此设备暂时无法启动现代网页内核。\n请使用匹配设备架构的安装包，或返回使用兼容版。");failure.setTextColor(Color.WHITE);font(failure,22);failure.setPadding(dp(24),dp(24),dp(24),dp(24));webHost.addView(failure);
        }
    }

    private void configureSession() {
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession source,LoadRequest request){
                boolean allowed=WebsitePolicy.normalize(request.uri)!=null;
                if(officialCctv)allowed=tv.clearstream.core.CctvPagePolicy.isOfficialPage(request.uri)&&initialUrl.equals(request.uri);
                if(!allowed&&!officialCctv)Toast.makeText(ModernWebActivity.this,"此链接需要其他应用，已留在当前页面",Toast.LENGTH_SHORT).show();
                return GeckoResult.fromValue(allowed?AllowOrDeny.ALLOW:AllowOrDeny.DENY);
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession source,String uri){
                String safe=WebsitePolicy.normalize(uri);if(!officialCctv&&safe!=null&&!destroyed)session.loadUri(safe);
                return GeckoResult.fromValue(null);
            }
            @Override public void onCanGoBack(GeckoSession source,boolean value){canGoBack=value;}
            @Override public GeckoResult<String> onLoadError(GeckoSession source,String uri,org.mozilla.geckoview.WebRequestError error){String safe=WebsitePolicy.normalize(uri);if(foreground&&safe!=null&&safe.equals(currentUrl)){playbackMonitor.failed(safe,WebsiteHealth.PlaybackIssue.PAGE_ERROR);if(officialPlayer!=null)officialPlayer.failure("央视官网暂时未能连接\n请检查网络后重试");}return null;}
            @Override public void onLocationChange(GeckoSession source,String url,List<GeckoSession.PermissionDelegate.ContentPermission> permissions,Boolean userGesture){
                String safe=WebsitePolicy.normalize(url);if(safe!=null){currentUrl=safe;playbackMonitor.navigate(safe);}
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate(){
            @Override public void onPageStart(GeckoSession source,String url){
                String safe=WebsitePolicy.normalize(url);if(safe!=null){currentUrl=safe;playbackMonitor.newDocument(safe);}
                dropPort();playbackProof.reset();auditState=new JSONObject();mediaErrorHint=false;updateHint();pageFullscreen=false;layoutFullscreen=false;syncFullscreen();title.setText("正在加载网页");
                if(officialPlayer!=null)officialPlayer.loading();
            }
            @Override public void onPageStop(GeckoSession source,boolean success){if(!success)title.setText("页面未能加载 · 可刷新重试");}
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate(){
            @Override public void onTitleChange(GeckoSession source,String text){title.setText(text==null?WebsitePolicy.nameOf(currentUrl):text);}
            @Override public void onFullScreen(GeckoSession source,boolean fullScreen){pageFullscreen=fullScreen;syncFullscreen();if(fullScreen&&view!=null)view.requestFocus();else if(toolbar.getVisibility()==View.VISIBLE)toolbar.getChildAt(0).requestFocus();}
            @Override public void onCrash(GeckoSession source){contentStopped();}
            @Override public void onKill(GeckoSession source){contentStopped();}
            @Override public void onCloseRequest(GeckoSession source){finish();}
        });
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate(){
            @Override public void onAndroidPermissionsRequest(GeckoSession source,String[] permissions,Callback callback){callback.reject();}
            @Override public void onMediaPermissionRequest(GeckoSession source,String uri,MediaSource[] video,MediaSource[] audio,MediaCallback callback){callback.reject();}
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession source,ContentPermission permission){
                boolean protectedMedia=permission.permission==PERMISSION_MEDIA_KEY_SYSTEM_ACCESS&&sameSecureOrigin(permission.uri,currentUrl);
                return GeckoResult.fromValue(protectedMedia?ContentPermission.VALUE_ALLOW:ContentPermission.VALUE_DENY);
            }
        });
        session.setMediaSessionDelegate(new MediaSession.Delegate(){
            @Override public void onActivated(GeckoSession source,MediaSession active){mediaSession=active;if(!foreground)active.pause();}
            @Override public void onDeactivated(GeckoSession source,MediaSession inactive){if(mediaSession==inactive)mediaSession=null;}
            @Override public void onPlay(GeckoSession source,MediaSession active){if(!foreground)active.pause();}
            @Override public void onPause(GeckoSession source,MediaSession active){if(!foreground)backgroundPauseConfirmed=true;}
        });
    }

    private final WebExtension.MessageDelegate messageDelegate=new WebExtension.MessageDelegate(){
        @Override public void onConnect(WebExtension.Port incoming){
            if(destroyed||incoming.sender.session!=session||!incoming.sender.isTopLevel()||!EXTENSION_ID.equals(incoming.sender.webExtension.id)||WebsitePolicy.normalize(incoming.sender.url)==null){incoming.setDelegate(CLOSED_PORT);incoming.disconnect();return;}
            dropPort();port=incoming;incoming.setDelegate(portDelegate);
            sendEnable();if(!foreground)sendCommand("pause");
        }
    };
    private final WebExtension.PortDelegate portDelegate=new WebExtension.PortDelegate(){
        @Override public void onPortMessage(Object message,WebExtension.Port incoming){
            if(destroyed||incoming!=port||incoming.sender.session!=session||!incoming.sender.isTopLevel()||!(message instanceof JSONObject))return;
            JSONObject data=(JSONObject)message;
            if("adConfigSaved".equals(data.optString("kind"))){if(adReloadPending){adReloadPending=false;reload();}return;}
            if("adPicked".equals(data.optString("kind"))){if(pickingAds&&foreground){if(!data.optBoolean("ok")){Toast.makeText(ModernWebActivity.this,"请选择广告区域，不能选择整个网页或视频",Toast.LENGTH_LONG).show();return;}endAdPicker();adProtection.confirmPick(data,()->sendEnable());}return;}

            if("userPaused".equals(data.optString("kind"))){playbackMonitor.cancelAttempt();return;}
            if("playbackFailed".equals(data.optString("kind"))){playbackMonitor.failed(currentUrl,WebsiteHealth.PlaybackIssue.MEDIA_ERROR);if(officialPlayer!=null)officialPlayer.failure("央视官网暂时无法播放此节目\n请重试，或返回选台");else Toast.makeText(ModernWebActivity.this,"此视频未能开始播放，可尝试原站播放键或其他线路",Toast.LENGTH_LONG).show();return;}
            if("fullscreenFailed".equals(data.optString("kind"))){if(foreground)sendCommand("fitPlayer");return;}
            if("layoutFullscreen".equals(data.optString("kind"))){if(foreground){layoutFullscreen=data.optBoolean("active");syncFullscreen();if(view!=null)view.requestFocus();}return;}
            if("noVideo".equals(data.optString("kind"))){playbackMonitor.noVideo(currentUrl);if(foreground&&!officialCctv)Toast.makeText(ModernWebActivity.this,"请先在网站详情页选择剧集，或进入影片的播放页",Toast.LENGTH_LONG).show();return;}
            if("state".equals(data.optString("kind"))){
                JSONObject safe=new JSONObject();
                try {
                    safe.put("video",data.optBoolean("video")).put("paused",data.optBoolean("paused",true)).put("fullscreen",data.optBoolean("fullscreen")).put("controls",data.optBoolean("controls"));
                    String fullStatus=data.optString("fullscreenStatus","");if(fullStatus.matches("[a-z-]{0,24}"))safe.put("fullscreenStatus",fullStatus);
                    String playStatus=data.optString("playbackStatus","");if(playStatus.matches("[a-z-]{0,24}"))safe.put("playbackStatus",playStatus);
                    String extensionVersion=data.optString("extensionVersion","");if(extensionVersion.matches("[0-9.]{1,32}"))safe.put("extensionVersion",extensionVersion);
                    if(officialCctv){String status=data.optString("cctvStatus","");if(status.matches("[a-z-]{0,24}"))safe.put("cctvStatus",status);safe.put("officialCctv",true);}
                    safe.put("layoutFullscreen",data.optBoolean("layoutFullscreen"));
                    for(String key:new String[]{"width","height","ready","network","error","frames","layoutWidth","layoutHeight","viewportWidth","viewportHeight","layoutInnerWidth","layoutInnerHeight","layoutMediaWidth","layoutMediaHeight","adBlocked","adHidden"})safe.put(key,Math.max(0,data.optInt(key)));
                    for(String key:new String[]{"time","cx","cy","px","py","layoutMediaTime"}){double value=data.optDouble(key,0);safe.put(key,Double.isNaN(value)||Double.isInfinite(value)?0:value);}
                    safe.put("receivedAtMs",SystemClock.elapsedRealtime());auditState=safe;
                    if(officialPlayer!=null){officialPlayer.sample(safe);if("paused".equals(safe.optString("cctvStatus")))playbackMonitor.cancelAttempt();JSONObject click=data.optJSONObject("cctvClick");if(foreground&&click!=null&&tv.clearstream.core.CctvPagePolicy.isOfficialPage(incoming.sender.url)){double x=click.optDouble("x",-1),y=click.optDouble("y",-1);if(validPoint(x)&&validPoint(y))touch((float)x*view.getWidth(),(float)y*view.getHeight());}}
                    JSONObject navigation=data.optJSONObject("navigation");if(navigation!=null&&navigation.toString().length()<16000)safe.put("navigation",navigation);
                    playbackMonitor.sample(currentUrl,safe.optBoolean("video"),safe.optInt("width"),safe.optInt("height"),safe.optDouble("time"),safe.optBoolean("paused",true),safe.optInt("ready"),safe.optInt("error"));
                    boolean belongs=tv.clearstream.core.PlaybackProof.canAssociate(siteRoot,initialUrl,currentUrl);
                    if(!belongs)playbackProof.reset();
                    if(foreground&&belongs&&playbackProof.observe(currentUrl,safe.optInt("width"),safe.optInt("height"),safe.optDouble("time"),safe.optBoolean("paused",true),safe.optInt("ready"),safe.optInt("error"),SystemClock.elapsedRealtime()))WebsiteHealth.recordPlayback(ModernWebActivity.this,siteRoot,currentUrl,safe.optInt("width"),safe.optInt("height"),safe.optDouble("time"));
                    if(!officialCctv&&foreground&&safe.optInt("error")>0&&hint!=null){mediaErrorHint=true;hint.setText("这条视频线路暂时无法播放 · 可返回选片或在网站换线路");hint.setVisibility(View.VISIBLE);}
                    else if(mediaErrorHint&&safe.optBoolean("video")&&safe.optInt("ready")>=2){mediaErrorHint=false;updateHint();syncFullscreen();}
                }catch(JSONException ignored){}
                return;
            }
            if(!foreground||!"action".equals(data.optString("kind")))return;
            String action=data.optString("action");
            if("click".equals(action)){double x=data.optDouble("x",Double.NaN),y=data.optDouble("y",Double.NaN);if(validPoint(x)&&validPoint(y))touch((float)x*view.getWidth(),(float)y*view.getHeight());}
            else if("toolbar".equals(action))showToolbar();
            else if("native".equals(action)){int key=data.optInt("keyCode");if(remoteKey(key))dispatchNativeKey(key);}
        }
        @Override public void onDisconnect(WebExtension.Port incoming){if(port==incoming)port=null;}
    };

    private static boolean validPoint(double value){return !Double.isNaN(value)&&!Double.isInfinite(value)&&value>=0&&value<=1;}
    private static boolean sameSecureOrigin(String first,String second){
        if(WebsitePolicy.normalize(first)==null||WebsitePolicy.normalize(second)==null)return false;
        Uri a=Uri.parse(first),b=Uri.parse(second);
        return "https".equalsIgnoreCase(a.getScheme())&&"https".equalsIgnoreCase(b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&(a.getPort()<0?443:a.getPort())==(b.getPort()<0?443:b.getPort());
    }
    private void dropPort(){WebExtension.Port old=port;port=null;if(old!=null){old.setDelegate(CLOSED_PORT);old.disconnect();}}
    private void send(JSONObject message){if(port!=null&&!destroyed)port.postMessage(message);}
    private void sendCommand(String command){try{if(port==null&&("toggleVideo".equals(command)||"fullscreenVideo".equals(command))){Toast.makeText(this,"页面仍在加载，请稍候再操作",Toast.LENGTH_SHORT).show();return;}if("toggleVideo".equals(command)&&playbackMonitor!=null){if(auditState.optBoolean("paused",true))playbackMonitor.request(currentUrl);else playbackMonitor.cancelAttempt();}send(new JSONObject().put("command",command));}catch(JSONException ignored){}}
    private void sendEnable(){try{send(new JSONObject().put("command","enable").put("enabled",tvMode).put("adProtection",adProtection.config()).put("officialCctv",officialCctv&&port!=null&&tv.clearstream.core.CctvPagePolicy.isOfficialPage(port.sender.url)).put("foreground",foreground));}catch(JSONException ignored){}}
    private void chooseMode(){
        if(view==null||cursor==null||session==null||!session.isOpen())return;
        new AlertDialog.Builder(this).setTitle("网页操作模式").setSingleChoiceItems(new String[]{"电视适配 · 方向键选择","遥控鼠标 · 方向键移动","原始网页 · 网站原有操作"},tvMode?0:pointer?1:2,(dialog,index)->{
            tvMode=index==0;pointer=index==1;modeButton.setText(tvMode?"电视适配":pointer?"遥控鼠标":"原始网页");cursor.setVisibility(pointer?View.VISIBLE:View.GONE);sendEnable();updateHint();dialog.dismiss();view.requestFocus();
        }).setNegativeButton("返回",null).show();
    }
    private void beginAdPicker(){if(view==null||port==null){Toast.makeText(this,"请等网页加载完成后再点选",Toast.LENGTH_LONG).show();return;}showToolbar();pickingAds=true;previousPointer=pointer;pointer=true;cursor.setVisibility(View.VISIBLE);toolbar.setVisibility(View.GONE);hint.setVisibility(View.VISIBLE);hint.setText("点选广告：方向键移动光标 · 确定选择 · 返回取消");cursor.initialize();view.requestFocus();sendCommand("beginAdPick");sendAdPoint(false);}
    private void sendAdPoint(boolean select){try{send(new JSONObject().put("command",select?"pickAdAt":"pointAd").put("x",cursor.x/view.getWidth()).put("y",cursor.y/view.getHeight()));}catch(JSONException ignored){}}
    private void endAdPicker(){sendCommand("cancelAdPick");pickingAds=false;pointer=previousPointer;cursor.setVisibility(pointer?View.VISIBLE:View.GONE);updateHint();syncFullscreen();if(view!=null)view.requestFocus();}
    private void updateHint(){if(hint!=null)hint.setText(tvMode?"方向键选择 · 确定进入 · 菜单键打开工具栏":pointer?"方向键移动鼠标 · 确定点击 · 菜单键打开工具栏":"原始网页模式 · 菜单键可切回电视适配");}
    private void contentStopped(){dropPort();mediaSession=null;auditState=new JSONObject();pageFullscreen=false;nativeFullscreen=false;syncFullscreen();title.setText("网页组件已停止 · 按刷新重试");if(officialPlayer!=null)officialPlayer.failure("网页组件已停止\n请重试，或返回选台");}
    private void reload(){
        if(session==null)return;
        if(officialCctv){toolbar.setVisibility(View.GONE);if(pageFullscreen)session.exitFullScreen();pageFullscreen=false;if(officialPlayer!=null)officialPlayer.loading();}
        if(!session.isOpen()){session.open(runtime);view.setSession(session);session.loadUri(currentUrl);}
        else if(extension==null){finish();startActivity(getIntent());}
        else session.reload();
    }
    private void syncFullscreen(){boolean full=pageFullscreen||nativeFullscreen||layoutFullscreen||officialCctv;if(toolbar!=null)toolbar.setVisibility(full?View.GONE:View.VISIBLE);if(hint!=null)hint.setVisibility(full?View.GONE:View.VISIBLE);}
    private void showToolbar(){if(layoutFullscreen){sendCommand("exitFitPlayer");layoutFullscreen=false;}if(pageFullscreen&&session!=null)session.exitFullScreen();pageFullscreen=false;nativeFullscreen=false;syncFullscreen();if(officialCctv)toolbar.setVisibility(View.VISIBLE);toolbar.getChildAt(0).requestFocus();}
    private void back(){
        if(officialCctv){sendCommand("pause");if(pageFullscreen&&session!=null)session.exitFullScreen();finish();return;}
        if(layoutFullscreen){showToolbar();return;}
        if(pickingAds){endAdPicker();return;}
        if(pageFullscreen){session.exitFullScreen();return;}
        if(nativeFullscreen){showToolbar();return;}
        if(session!=null&&session.isOpen()&&canGoBack)session.goBack();else finish();
    }
    @Override public void onBackPressed(){back();}
    private static boolean remoteKey(int key){return key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER;}
    private void dispatchNativeKey(int key){view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int officialKey=event.getKeyCode();
        if(officialCctv){
            if(officialKey==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)back();return true;}
            if(officialKey==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)showToolbar();return true;}
            if(view!=null&&view.hasFocus()){
                if(officialKey==KeyEvent.KEYCODE_DPAD_CENTER||officialKey==KeyEvent.KEYCODE_ENTER||officialKey==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)sendCommand("toggleVideo");return true;}
                if(officialKey==KeyEvent.KEYCODE_DPAD_UP||officialKey==KeyEvent.KEYCODE_DPAD_DOWN||officialKey==KeyEvent.KEYCODE_DPAD_LEFT||officialKey==KeyEvent.KEYCODE_DPAD_RIGHT)return true;
            }
        }
        if(view==null)return super.dispatchKeyEvent(event);
        int key=event.getKeyCode();
        if(pickingAds&&key==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)endAdPicker();return true;}
        if(event.getAction()==KeyEvent.ACTION_DOWN&&key==KeyEvent.KEYCODE_MENU){if(pickingAds)endAdPicker();showToolbar();return true;}
        if(view.hasFocus()&&remoteKey(key)){
            if(pointer){
                if(event.getAction()==KeyEvent.ACTION_DOWN){
                    cursor.initialize();float step=dp(event.getRepeatCount()>5?32:18);
                    if(key==KeyEvent.KEYCODE_DPAD_UP){if(cursor.y<dp(24)&&toolbar.getVisibility()==View.VISIBLE){showToolbar();return true;}cursor.y-=step;}
                    if(key==KeyEvent.KEYCODE_DPAD_DOWN)cursor.y+=step;if(key==KeyEvent.KEYCODE_DPAD_LEFT)cursor.x-=step;if(key==KeyEvent.KEYCODE_DPAD_RIGHT)cursor.x+=step;
                    cursor.x=Math.max(dp(12),Math.min(view.getWidth()-dp(12),cursor.x));cursor.y=Math.max(dp(12),Math.min(view.getHeight()-dp(12),cursor.y));cursor.invalidate();
                    if(pickingAds)sendAdPoint(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER);else if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)touch(cursor.x,cursor.y);
                }
                return true;
            }
            if(tvMode&&port!=null){if(event.getAction()==KeyEvent.ACTION_DOWN)try{send(new JSONObject().put("keyCode",key));}catch(JSONException ignored){}return true;}
        }
        return super.dispatchKeyEvent(event);
    }
    private void touch(float x,float y){
        if(view==null||!foreground)return;long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        view.dispatchTouchEvent(down);view.dispatchTouchEvent(up);down.recycle();up.recycle();view.requestFocus();
    }
    @Override protected void onResume(){super.onResume();foreground=true;if(officialPlayer!=null)officialPlayer.foreground(true);if(playbackMonitor!=null)playbackMonitor.resume();backgroundPauseConfirmed=false;if(session!=null&&session.isOpen())session.setActive(true);sendEnable();}
    @Override protected void onPause(){if(pickingAds)endAdPicker();foreground=false;if(officialPlayer!=null)officialPlayer.foreground(false);if(playbackMonitor!=null)playbackMonitor.pause();playbackProof.reset();sendCommand("pause");if(mediaSession!=null)mediaSession.pause();if(session!=null&&session.isOpen())session.setActive(false);super.onPause();}
    @Override protected void onDestroy(){
        destroyed=true;if(officialPlayer!=null)officialPlayer.destroy();dropPort();mediaSession=null;if(session!=null){if(extension!=null)session.getWebExtensionController().setMessageDelegate(extension,null,NATIVE_APP);session.setMediaSessionDelegate(null);if(view!=null)view.releaseSession();session.close();session=null;}extension=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onDestroy();
    }
    private float layoutScale(){android.util.DisplayMetrics m=getResources().getDisplayMetrics();return Math.min(1f,Math.min(m.widthPixels/m.density/960f,m.heightPixels/m.density/540f));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density*layoutScale());}
    private void font(TextView target,int size){target.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(size)*Math.min(1.15f,getResources().getConfiguration().fontScale));}
    private TextView addButton(String text,Runnable action){
        TextView button=new TextView(this);button.setText(text);font(button,14);button.setSingleLine(true);button.setTextColor(Color.WHITE);button.setGravity(Gravity.CENTER);button.setPadding(dp(10),dp(8),dp(10),dp(8));button.setFocusable(true);button.setFocusableInTouchMode(true);button.setBackground(background(false));button.setOnFocusChangeListener((v,focus)->v.setBackground(background(focus)));button.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-1);params.setMargins(dp(3),0,dp(3),0);toolbar.addView(button,params);return button;
    }
    private GradientDrawable background(boolean focus){GradientDrawable drawable=new GradientDrawable();drawable.setColor(focus?0xff243b55:0xff1b222b);drawable.setCornerRadius(dp(8));if(focus)drawable.setStroke(dp(2),0xff4c9aff);return drawable;}
    private final class CursorView extends View {
        float x,y;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        CursorView(){super(ModernWebActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);setClickable(false);}
        void initialize(){if(x==0&&getWidth()>0){x=getWidth()/2f;y=getHeight()/2f;}}
        @Override protected void onDraw(Canvas canvas){initialize();paint.setStyle(Paint.Style.FILL);paint.setColor(0xb0101418);canvas.drawCircle(x,y,dp(10),paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(Color.WHITE);canvas.drawCircle(x,y,dp(8),paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,dp(2),paint);}
    }
}
