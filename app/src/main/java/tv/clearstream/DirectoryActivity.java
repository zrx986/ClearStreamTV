package tv.clearstream;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import tv.clearstream.core.WebsitePolicy;

/** Reads explicitly marked directory cards; never runs their onclick handlers. */
public final class DirectoryActivity extends Activity {
    private static final int BG=0xff101418,CARD=0xff1b222b,BLUE=0xff4c9aff,MUTED=0xffa9b7c7;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Entry> entries=new ArrayList<>();
    private WebsiteHealth health;
    private final Map<String,TextView> healthLabels=new HashMap<>();
    private int readCount;
    private ClearApp app;
    private WebView reader;
    private FrameLayout root;
    private LinearLayout rows,categories;
    private EditText query;
    private TextView status,refresh;
    private String initialUrl,script,category="全部",focusedUrl="";
    private long generation;
    private boolean loading,destroyed,foreground,reloadOnResume;
    private View pausedFocus;
    private String pausedFocusTag;

    static final class Entry {
        final String name,url,category,summary;
        Entry(String name,String url,String category,String summary){this.name=name;this.url=url;this.category=category;this.summary=summary;}
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);app=(ClearApp)getApplication();
        String requested=getIntent().getStringExtra("url");initialUrl=WebsitePolicy.normalize(requested);
        if(initialUrl==null){finish();return;}
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);
        createReader();LinearLayout layout=column();layout.setPadding(dp(30),dp(22),dp(30),dp(16));root.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=row();header.addView(button("返回",this::finish),new LinearLayout.LayoutParams(dp(90),dp(48)));
        TextView title=text("发现影视网站",28,Color.WHITE);title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(20),0,0,0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(button("原网页",()->startActivity(WebsitePlayback.intent(this,initialUrl,WebsitePolicy.nameOf(initialUrl)))),new LinearLayout.LayoutParams(dp(110),dp(48)));
        refresh=button("更新清单",this::load);header.addView(refresh,new LinearLayout.LayoutParams(dp(120),dp(48)));layout.addView(header);
        TextView intro=text("只读取你添加的导航网站 · 自动检查网站连接",16,MUTED);intro.setPadding(0,dp(15),0,dp(14));layout.addView(intro);
        LinearLayout search=row();query=new EditText(this);query.setSingleLine(true);query.setHint("查找网站名称或域名");query.setHintTextColor(MUTED);query.setTextColor(Color.WHITE);font(query,20);query.setPadding(dp(12),0,dp(12),0);query.setShowSoftInputOnFocus(false);query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnClickListener(v->showKeyboard());query.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_DPAD_CENTER)return false;if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())showKeyboard();return true;});
        query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){renderRows(null);}public void afterTextChanged(android.text.Editable e){}});
        query.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);query.setShowSoftInputOnFocus(false);focusFirst();return true;}return false;});
        search.addView(query,new LinearLayout.LayoutParams(0,dp(50),1));TextView clear=button("清空搜索",()->{query.setText("");query.requestFocus();});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(120),dp(50));cp.leftMargin=dp(10);search.addView(clear,cp);layout.addView(search);
        HorizontalScrollView categoryScroll=new HorizontalScrollView(this);categoryScroll.setHorizontalScrollBarEnabled(false);categoryScroll.setFillViewport(false);categories=row();categoryScroll.addView(categories);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(56));gp.topMargin=dp(10);layout.addView(categoryScroll,gp);
        status=text("正在读取导航清单…",15,MUTED);status.setPadding(dp(2),dp(8),0,dp(10));layout.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);rows=column();scroll.addView(rows);layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView help=text("方向键选择 · 确定进入网站 / 添加 · 已添加的网站在“影视网站”中保留",13,MUTED);help.setGravity(Gravity.CENTER);help.setPadding(0,dp(10),0,0);layout.addView(help);
        renderCategories();if(reader==null)status.setText("设备缺少网页组件，暂时无法读取导航清单");else reloadOnResume=true;refresh.requestFocus();
    }
    private void createReader() {
        try{reader=new WebView(this);}catch(RuntimeException unavailable){return;}
        reader.setVisibility(View.INVISIBLE);reader.setFocusable(false);reader.setFocusableInTouchMode(false);reader.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);reader.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);root.addView(reader,new FrameLayout.LayoutParams(-1,-1));
        WebSettings settings=reader.getSettings();settings.setJavaScriptEnabled(false);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setMediaPlaybackRequiresUserGesture(true);
        reader.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
    }
    private void load() {
        if(!foreground){reloadOnResume=true;return;}
        if(health!=null){health.close();health=null;}
        if(reader==null||destroyed)return;long epoch=++generation;loading=true;main.removeCallbacksAndMessages(null);reader.stopLoading();reader.getSettings().setJavaScriptEnabled(false);status.setText(entries.isEmpty()?"正在读取导航清单…":"正在更新，当前清单仍可使用…");
        reader.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !sameDirectory(url);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !sameDirectory(request.getUrl().toString());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return request.isForMainFrame()?null:new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
            @Override public void onPageFinished(WebView view,String url){if(valid(epoch)&&sameDirectory(url))read(epoch);}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()&&valid(epoch))fail("导航网站暂时无法连接");}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame()&&valid(epoch))fail("导航网站返回 HTTP "+response.getStatusCode());}
        });reader.loadUrl(initialUrl);main.postDelayed(()->{if(valid(epoch)){reader.stopLoading();fail("读取超时，可选择更新清单重试");}},20000);
    }
    private boolean sameDirectory(String url){String normalized=WebsitePolicy.normalize(url);return normalized!=null&&host(normalized).equals(host(initialUrl));}
    private boolean valid(long epoch){return foreground&&!destroyed&&loading&&epoch==generation;}
    private void read(long epoch) {
        try{if(script==null)try(InputStream in=getAssets().open("directory-reader.js")){script=new String(NetworkEngine.readBounded(in,32*1024,true),StandardCharsets.UTF_8);}
            // Page scripts were disabled during parsing; all subresource downloads stay blocked.
            reader.getSettings().setJavaScriptEnabled(true);reader.evaluateJavascript(script,result->{
                if(!valid(epoch))return;reader.getSettings().setJavaScriptEnabled(false);
                try{List<Entry> parsed=parseEntries(result,initialUrl);if(parsed.isEmpty()){fail("没有识别到网站卡片，导航结构可能已改变");return;}entries.clear();readCount=parsed.size();entries.addAll(parsed);loading=false;renderCategories();renderRows(null);if(!restorePausedFocus())focusFirst();}
                catch(JSONException bad){fail("导航清单格式无法读取，可更新重试");}
            });
        }catch(IOException error){fail("读取组件未能加载");}
    }
    static List<Entry> parseEntries(String json,String directoryUrl)throws JSONException {
        if(json==null||json.length()>192*1024)throw new JSONException("Directory size");JSONArray array=new JSONObject(json).optJSONArray("items");List<Entry> result=new ArrayList<>();Set<String> seen=new HashSet<>();if(array==null)return result;
        for(int i=0;i<array.length()&&i<240;i++){JSONObject item=array.optJSONObject(i);if(item==null)continue;String url=WebsitePolicy.normalize(item.optString("url")),name=clean(item.optString("name"),80),group=clean(item.optString("category"),40);if(url==null||url.length()>2048||name.isEmpty()||host(url).equals(host(directoryUrl))||group.matches(".*(友情链接|广告|推广|赞助).*")||Uri.parse(url).getPath().toLowerCase(Locale.ROOT).matches(".*\\.(apk|exe|msi|dmg|zip|rar|7z)$"))continue;String key=key(url);if(!seen.add(key))continue;result.add(new Entry(name,url,group.isEmpty()?"网站":group,clean(item.optString("summary"),220)));}return result;
    }
    private static String clean(String value,int max){String s=value.replaceAll("[\\p{Cntrl}]"," ").trim();return s.length()>max?s.substring(0,max):s;}
    private static String host(String url){String h=Uri.parse(url).getHost();return h==null?"":h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");}
    private static String key(String url){Uri u=Uri.parse(url);String path=u.getEncodedPath();return u.getScheme().toLowerCase(Locale.ROOT)+"://"+u.getHost().toLowerCase(Locale.ROOT)+(u.getPort()==-1?"":":"+u.getPort())+(path==null||path.equals("/")?"":path)+(u.getEncodedQuery()==null?"":"?"+u.getEncodedQuery())+(u.getEncodedFragment()==null||u.getEncodedFragment().isEmpty()?"":"#"+u.getEncodedFragment());}
    private void fail(String message){loading=false;if(reader!=null)reader.getSettings().setJavaScriptEnabled(false);status.setText(message+(entries.isEmpty()?"":" · 已保留上次清单"));}
    private void renderCategories(){categories.removeAllViews();LinkedHashSet<String> all=new LinkedHashSet<>();all.add("全部");for(Entry e:entries)all.add(e.category);if(!all.contains(category))category="全部";for(String name:all){TextView b=button(name,()->{category=name;renderCategories();renderRows(null);focusFirst();});b.setSelected(category.equals(name));if(b.isSelected())b.setTextColor(BLUE);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(45));p.setMargins(0,0,dp(8),0);categories.addView(b,p);}}
    private void renderRows(String focusUrl){if(rows==null||!foreground||destroyed)return;rows.removeAllViews();healthLabels.clear();if(health==null)health=new WebsiteHealth(this,main);String filter=query==null?"":query.getText().toString().trim().toLowerCase(Locale.ROOT);Set<String> added=new HashSet<>();for(Website site:app.store.websites())added.add(key(site.url));int count=0;
        for(Entry e:entries){if(!category.equals("全部")&&!category.equals(e.category))continue;if(!filter.isEmpty()&&!(e.name+" "+e.url).toLowerCase(Locale.ROOT).contains(filter))continue;count++;
            LinearLayout line=row(),words=column();TextView enter=button(e.name+"\n"+host(e.url),()->{focusedUrl=e.url;startActivity(WebsitePlayback.portalIntent(this,e.url,e.name));});enter.setSingleLine(false);enter.setMaxLines(2);enter.setGravity(Gravity.CENTER_VERTICAL);enter.setContentDescription("导航网站："+e.name);enter.setTag(e.url);words.addView(enter,new LinearLayout.LayoutParams(-1,dp(64)));
            TextView checked=text("检测中…",14,MUTED);checked.setPadding(dp(14),dp(2),0,0);checked.setContentDescription("网站检测："+e.name);words.addView(checked,new LinearLayout.LayoutParams(-1,dp(24)));healthLabels.put(e.url,checked);line.addView(words,new LinearLayout.LayoutParams(0,dp(88),1));
            boolean saved=added.contains(key(e.url));TextView add=button(saved?"已添加":"＋ 添加到我的网站",()->add(e));add.setContentDescription("添加网站："+e.name);add.setTag("directory-add:"+e.url);if(saved)add.setTextColor(MUTED);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(190),dp(88));ap.leftMargin=dp(12);line.addView(add,ap);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(88));lp.bottomMargin=dp(10);rows.addView(line,lp);
            WebsiteHealth running=health;health.check(e.url,false,s->{if(!destroyed&&health==running&&healthLabels.get(e.url)==checked)applyHealth(checked,s);});applyHealth(checked,health.status(e.url));
            if(focusUrl!=null&&e.url.equals(focusUrl))add.requestFocus();else if(focusUrl==null&&e.url.equals(focusedUrl))enter.requestFocus();
        }
        if(!loading)status.setText("显示 "+count+" 个网站 · 导航读取 "+readCount+" 个 · 网页可访问仍需播放验证");if(count==0&&!entries.isEmpty()){TextView empty=text("没有匹配的网站，试试其他分类或清空搜索",20,MUTED);empty.setPadding(dp(10),dp(30),dp(10),dp(30));rows.addView(empty);}
    }
    private void applyHealth(TextView view,WebsiteHealth.Snapshot s){String label=s.checking?"检测中…":s.label+(s.reason.isEmpty()?"":" · "+s.reason);if(s.playbackTimestamp>0&&!label.contains("实播"))label+=" · 有实播记录";view.setSingleLine(true);view.setEllipsize(android.text.TextUtils.TruncateAt.END);view.setText(label);view.setTextColor(s.checking?MUTED:s.playbackIssueTimestamp>0||s.state==WebsiteHealth.State.UNAVAILABLE||s.state==WebsiteHealth.State.VERIFY_REQUIRED?0xffedb56a:s.state==WebsiteHealth.State.PAGE_OK?0xff79c6a9:MUTED);}
    private void add(Entry entry){String message; synchronized(app.store){List<Website> saved=app.store.websites();for(Website site:saved)if(key(site.url).equals(key(entry.url))){app.settings.edit().putString("directoryReturnUrl",site.url).apply();Toast.makeText(this,"已在我的网站中，按返回即可进入",Toast.LENGTH_SHORT).show();return;}if(saved.size()>=100){Toast.makeText(this,"已保存100个网站，请先到管理模式移除不需要的网站",Toast.LENGTH_LONG).show();return;}
            if(app.store.addWebsite(entry.url)){ContentValues title=new ContentValues();title.put("name",entry.name);app.store.getWritableDatabase().update("websites",title,"url=?",new String[]{entry.url});message="已添加，可返回影视网站进入";}else message="这个网站已经添加";
        }app.settings.edit().putString("directoryReturnUrl",entry.url).apply();renderRows(entry.url);Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void focusFirst(){if(rows!=null&&rows.getChildCount()>0&&rows.getChildAt(0) instanceof LinearLayout)((LinearLayout)rows.getChildAt(0)).getChildAt(0).requestFocus();}
    private void showKeyboard(){query.setShowSoftInputOnFocus(true);query.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);}
    private int dp(int n){android.util.DisplayMetrics m=getResources().getDisplayMetrics();float scale=Math.min(1f,Math.min(m.widthPixels/m.density/960f,m.heightPixels/m.density/540f));return Math.round(n*m.density*scale);}
    private void font(TextView view,int size){view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(size)*Math.min(1.15f,getResources().getConfiguration().fontScale));}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextColor(color);font(view,size);return view;}
    private TextView button(String name,Runnable action){TextView b=text(name,19,Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setSingleLine(true);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setFocusable(true);b.setFocusableInTouchMode(true);b.setBackground(background(false));b.setOnFocusChangeListener((v,f)->v.setBackground(background(f)));b.setOnClickListener(v->action.run());return b;}
    private GradientDrawable background(boolean focus){GradientDrawable d=new GradientDrawable();d.setColor(focus?0xff243b55:CARD);d.setCornerRadius(dp(10));if(focus)d.setStroke(dp(2),BLUE);return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private boolean restorePausedFocus(){View target=pausedFocusTag==null?pausedFocus:root.findViewWithTag(pausedFocusTag);pausedFocus=null;pausedFocusTag=null;return foreground&&target!=null&&target.isAttachedToWindow()&&target.requestFocus();}
    @Override protected void onResume(){super.onResume();foreground=true;if(reader!=null)reader.onResume();if(reader!=null&&(reloadOnResume||entries.isEmpty())){reloadOnResume=false;load();}else if(rows!=null&&!entries.isEmpty()){renderRows(null);restorePausedFocus();}}
    @Override protected void onPause(){foreground=false;pausedFocus=getCurrentFocus();pausedFocusTag=pausedFocus!=null&&pausedFocus.getTag() instanceof String?(String)pausedFocus.getTag():null;reloadOnResume=reloadOnResume||loading;generation++;loading=false;main.removeCallbacksAndMessages(null);if(health!=null){health.close();health=null;}if(reader!=null){reader.stopLoading();reader.getSettings().setJavaScriptEnabled(false);reader.onPause();}super.onPause();}
    @Override protected void onDestroy(){destroyed=true;generation++;if(health!=null){health.close();health=null;}main.removeCallbacksAndMessages(null);if(reader!=null){reader.stopLoading();root.removeView(reader);reader.destroy();reader=null;}super.onDestroy();}
}
