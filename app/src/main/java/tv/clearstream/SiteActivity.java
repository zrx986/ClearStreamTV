package tv.clearstream;

import android.app.*;
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
import org.json.*;
import java.util.*;
import tv.clearstream.core.*;

/** A native TV space for one website. The website still owns login and playback. */
public final class SiteActivity extends Activity {
    private static final int BG=0xff101418,CARD=0xff1b222b,BLUE=0xff4c9aff,MUTED=0xffa9b7c7;
    private static final int PAGE_SIZE=12;
    private ClearApp app;
    private WebView reader;
    private FrameLayout root;
    private LinearLayout grid,pager,browser,previewPanel;
    private FrameLayout body;
    private EditText query;
    private TextView status,searchButton,backButton,previewTitle,previewSummary,previewPlaceholder,previewPlay,previewFavorite;
    private ImageView previewPoster;
    private SiteImages images;
    private Item previewItem;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Item> items=new ArrayList<>();
    private String initialUrl,siteName,currentUrl,requestedReaderUrl,previousReaderUrl,templateKey,detectedTemplate,script,directoryScript,signature="",focusedUrl="";
    private long generation,cardsGeneration,previewGeneration;
    private int page;
    private boolean started,loading,showingSearch,focusResults,detailOpening,resumeInterrupted,queryCenterDown;
    private boolean bookmarkPreview,searchPreview;
    private View bookmarkPausedFocus;

    private static final class Item {
        final String title,url,image,summary,referrerPolicy;
        Item(String title,String url,String image,String summary,String referrerPolicy){this.title=title;this.url=url;this.image=image;this.summary=summary;this.referrerPolicy=referrerPolicy;}
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        initialUrl=WebsitePolicy.normalize(getIntent().getStringExtra("url"));
        if(initialUrl==null){finish();return;}
        app=(ClearApp)getApplication();siteName=getIntent().getStringExtra("name");
        bookmarkPreview=getIntent().hasExtra("bookmarkUrl");
        searchPreview=getIntent().getBooleanExtra("searchPreview",false);
        Item searched=searchPreview?readSearchPreview():null;if(searchPreview&&searched==null){finish();return;}
        SiteBookmarks.Entry bookmarked=bookmarkPreview?new SiteBookmarks(app.settings).film(initialUrl,getIntent().getStringExtra("bookmarkUrl")):null;
        if(siteName==null||siteName.trim().isEmpty())siteName=WebsitePolicy.nameOf(initialUrl);
        currentUrl=searchPreview?searched.url:initialUrl;templateKey="siteSearch:"+initialUrl;images=new SiteImages(app,main);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);
        if(!localPreview())createReader();
        LinearLayout layout=column();layout.setBackgroundColor(BG);layout.setPadding(dp(28),dp(18),dp(28),dp(18));root.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=row();backButton=button("返回网站",this::back);header.addView(backButton,new LinearLayout.LayoutParams(dp(106),dp(45)));
        TextView brand=text(siteName,25,Color.WHITE);brand.setTypeface(null,Typeface.BOLD);brand.setSingleLine(true);brand.setEllipsize(android.text.TextUtils.TruncateAt.END);brand.setPadding(dp(18),0,dp(10),0);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(button("收藏",this::favoriteFilms),new LinearLayout.LayoutParams(dp(85),dp(45)));
        header.addView(button("原网页",()->openOriginal(previewItem==null?currentUrl:previewItem.url)),new LinearLayout.LayoutParams(dp(100),dp(45)));
        TextView more=button("更多",this::more);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(85),dp(45));mp.setMargins(dp(8),0,0,0);header.addView(more,mp);layout.addView(header);
        body=new FrameLayout(this);browser=column();body.addView(browser,new FrameLayout.LayoutParams(-1,-1));layout.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        TextView intro=text("站内搜索 · 遥控器选片",15,MUTED);intro.setPadding(dp(2),dp(12),0,dp(12));browser.addView(intro);
        LinearLayout search=row();query=new EditText(this);query.setId(R.id.site_query);query.setTextColor(Color.WHITE);query.setHintTextColor(MUTED);query.setHint("搜索这个网站的影片或节目");font(query,21);query.setSingleLine(true);query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);query.setInputType(android.text.InputType.TYPE_CLASS_TEXT);query.setShowSoftInputOnFocus(false);query.setOnClickListener(v->showKeyboard());query.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_DPAD_CENTER)return false;if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)queryCenterDown=true;if(event.getAction()==KeyEvent.ACTION_UP){boolean open=queryCenterDown&&!event.isCanceled();queryCenterDown=false;if(open)showKeyboard();}return true;});query.setPadding(dp(12),dp(10),dp(12),dp(10));search.addView(query,new LinearLayout.LayoutParams(0,dp(54),1));
        searchButton=button("搜索",this::search);searchButton.setId(R.id.site_search);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(110),dp(54));sp.setMargins(dp(12),0,0,0);search.addView(searchButton,sp);browser.addView(search);
        query.setOnEditorActionListener((v,action,event)->{
            boolean enter=event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER;
            if(action==EditorInfo.IME_ACTION_SEARCH||enter){if(event==null||event.getAction()==KeyEvent.ACTION_UP)search();return true;}return false;
        });
        query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){focusResults=false;}public void afterTextChanged(android.text.Editable e){}});
        status=text("正在读取网站内容…",15,MUTED);status.setPadding(dp(4),dp(12),dp(4),dp(10));browser.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);grid=column();scroll.addView(grid);browser.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        pager=row();pager.setGravity(Gravity.CENTER);browser.addView(pager,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView help=text("方向键选择 · 确定看预览 · 返回保留上一级  |  内容与播放由网站提供",12,MUTED);help.setGravity(Gravity.CENTER);help.setPadding(0,dp(8),0,0);browser.addView(help);
        if(bookmarkPreview){if(bookmarked==null)showMissingBookmark();else showPreview(new Item(bookmarked.title,bookmarked.url,bookmarked.image,bookmarked.summary,bookmarked.referrerPolicy));}
        else if(searchPreview)showPreview(searched);
        else{if(reader==null)showNoProvider();else load(initialUrl,false);query.requestFocus();}
    }
    private boolean localPreview(){return bookmarkPreview||searchPreview;}
    private String previewBackTitle(){return searchPreview?"返回搜索":bookmarkPreview?"返回收藏":"返回选片";}
    private Item readSearchPreview(){String url=WebsitePolicy.normalize(getIntent().getStringExtra("filmUrl")),title=getIntent().getStringExtra("filmTitle");if(url==null||title==null||title.trim().length()<2||title.trim().length()>100)return null;String image=WebsitePolicy.normalize(getIntent().getStringExtra("filmImage"));if(image==null||image.length()>2048)image="";String summary=getIntent().getStringExtra("filmSummary");if(summary==null)summary="";if(summary.length()>600)summary=summary.substring(0,600);return new Item(title.trim(),url,image,summary,"no-referrer".equals(getIntent().getStringExtra("filmPolicy"))?"no-referrer":"");}
    private void showKeyboard(){query.setShowSoftInputOnFocus(true);query.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);}
    private void createReader() {
        try{reader=new WebView(this);}catch(RuntimeException e){return;}
        // Full measured viewport below an opaque native layout, never focused or clickable.
        reader.setVisibility(View.INVISIBLE);reader.setFocusableInTouchMode(false);reader.setFocusable(false);reader.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);reader.setClickable(false);reader.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.addView(reader,new FrameLayout.LayoutParams(-1,-1));
        WebSettings s=reader.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(true);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);
        if(app.settings.getBoolean("siteDesktop:"+Uri.parse(initialUrl).getHost(),true))s.setUserAgentString(BrowserUserAgent.desktop(s.getUserAgentString()));
        reader.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
    }
    private void installReaderClient(long epoch,boolean handleErrors) {
        reader.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return WebsitePolicy.normalize(url)==null;}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return WebsitePolicy.normalize(request.getUrl().toString())==null;}
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap favicon){if(loading&&epoch==generation&&Objects.equals(url,v.getUrl())&&WebsitePolicy.normalize(url)!=null){boolean changed=!Objects.equals(currentUrl,url);currentUrl=url;if(changed)scheduleProgressiveScans(epoch);}}
            @Override public void onPageFinished(WebView v,String url){
                if(!started||!loading||epoch!=generation||!Objects.equals(url,v.getUrl())||WebsitePolicy.normalize(url)==null)return;
                if(Objects.equals(activeReadUrl(epoch),url))scheduleScans(epoch,url);
            }
            private boolean currentError(WebResourceRequest request){String address=request.getUrl().toString();return handleErrors&&loading&&request.isForMainFrame()&&Objects.equals(address,currentUrl)&&valid(epoch,address);}
            @Override public void onReceivedError(WebView v,WebResourceRequest request,WebResourceError error){if(currentError(request))fail("网站未能加载，可重试或打开原网页");}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest request,WebResourceResponse response){if(currentError(request))fail("网站返回 HTTP "+response.getStatusCode()+"，可打开原网页查看");}
        });
    }
    private void scheduleScans(long epoch,String url){scan(epoch,url,false);main.postDelayed(()->scan(epoch,url,false),700);main.postDelayed(()->scan(epoch,url,false),2000);main.postDelayed(()->scan(epoch,url,true),5000);}
    private void scheduleProgressiveScans(long epoch){for(int delay:new int[]{500,1500,3000,6000,10000,15000,19000})main.postDelayed(()->{String active=activeReadUrl(epoch);if(active!=null)scan(epoch,active,false);},delay);}
    private String activeReadUrl(long epoch){
        if(reader==null||!started||!loading||isFinishing()||isDestroyed()||epoch!=generation)return null;
        String active=WebsitePolicy.normalize(reader.getUrl());if(active==null)return null;
        // A new request can leave getUrl() pointing at the previous document briefly.
        if(Objects.equals(active,previousReaderUrl)&&!Objects.equals(active,requestedReaderUrl)&&!Objects.equals(active,currentUrl))return null;
        currentUrl=active;return active;
    }
    private void showNoProvider() {
        status.setText("设备缺少可用网页组件，无法读取网站内容。可外部打开，或更新系统 Android System WebView。");
        grid.removeAllViews();grid.addView(button("外部打开",this::external),new LinearLayout.LayoutParams(-1,dp(62)));
    }
    private void load(String url,boolean search) {
        if(reader==null){showNoProvider();return;}
        if(WebsitePolicy.normalize(url)==null)return;
        previousReaderUrl=WebsitePolicy.normalize(reader.getUrl());requestedReaderUrl=url;
        generation++;main.removeCallbacksAndMessages(null);reader.stopLoading();currentUrl=url;showingSearch=search;loading=true;page=0;focusedUrl="";signature="";items.clear();renderCards();status.setText(search?"正在搜索这个网站…":"正在读取网站内容…");
        long epoch=generation;
        installReaderClient(epoch,true);
        main.postDelayed(()->{if(epoch==generation&&loading&&started){reader.stopLoading();generation++;loading=false;if(items.isEmpty())catalogUnreadable();status.setText(items.isEmpty()?"读取超时，可在更多中重试或打开原网页":(showingSearch?"找到 ":"首页精选 · ")+items.size()+" 项内容 · 部分资源未完成，可继续选片");}},20000);
        reader.loadUrl(url);
        scheduleProgressiveScans(epoch);
    }
    private void scan(long epoch,String url,boolean last) {
        if(!valid(epoch,url))return;
        try{
            if(directoryScript==null)try(java.io.InputStream in=getAssets().open("directory-reader.js")){directoryScript=new String(NetworkEngine.readBounded(in,32*1024,true),java.nio.charset.StandardCharsets.UTF_8);}
            reader.evaluateJavascript("(function(){var result="+directoryScript+";return {url:location.href,data:result};})()",value->{
                if(!valid(epoch,url))return;
                try{if(value!=null&&value.length()<=192*1024){JSONObject envelope=new JSONObject(value);if(!url.equals(envelope.optString("url")))return;JSONObject directory=envelope.optJSONObject("data");JSONArray websites=directory==null?null:directory.optJSONArray("items");if(!detailOpening&&directory!=null&&directory.optBoolean("directory")&&websites!=null&&websites.length()>=3){detailOpening=true;loading=false;generation++;main.removeCallbacksAndMessages(null);startActivity(WebsitePlayback.directoryIntent(this,url,siteName));finish();return;}}}catch(JSONException ignored){}
                scanFilms(epoch,url,last);
            });
        }catch(Exception unavailable){scanFilms(epoch,url,last);}
    }
    private void scanFilms(long epoch,String url,boolean last) {
        if(!valid(epoch,url))return;
        try {
            if(script==null)try(java.io.InputStream in=getAssets().open("site-reader.js")){script=new String(NetworkEngine.readBounded(in,128*1024,true),java.nio.charset.StandardCharsets.UTF_8);}
            reader.evaluateJavascript(script,value->{
                if(!valid(epoch,url)||value==null||value.length()>128*1024)return;
                try {
                    JSONObject data=new JSONObject(value);if(!url.equals(data.optString("url")))return;
                    JSONObject form=data.optJSONObject("form");
                    if(form!=null) {
                        List<String[]> fixed=new ArrayList<>();JSONArray fields=form.optJSONArray("fixed");
                        if(fields!=null&&fields.length()<=32)for(int i=0;i<fields.length();i++){JSONArray f=fields.optJSONArray(i);if(f!=null&&f.length()==2)fixed.add(new String[]{f.optString(0),f.optString(1)});}
                        detectedTemplate=SiteSearch.fromGetForm(url,form.optString("action"),form.optString("method"),form.optString("query"),fixed);
                    }
                    JSONArray found=data.optJSONArray("items");List<Item> next=new ArrayList<>();Set<String> seen=new HashSet<>();StringBuilder key=new StringBuilder();
                    if(found!=null)for(int i=0;i<Math.min(found.length(),120);i++) {
                        JSONObject item=found.optJSONObject(i);if(item==null)continue;
                        String address=WebsitePolicy.normalize(item.optString("url")),title=item.optString("title").trim();
                        if(address==null||title.length()<2||title.length()>100||!seen.add(address))continue;
                        String image=WebsitePolicy.normalize(item.optString("image"));if(image!=null&&image.length()>2048)image=null;
                        String summary=item.optString("summary");if(summary.length()>600)summary=summary.substring(0,600);
                        String referrerPolicy="no-referrer".equals(item.optString("referrerPolicy"))?"no-referrer":"";
                        next.add(new Item(title,address,image,summary,referrerPolicy));key.append(address).append('\n').append(title).append('\n').append(image).append('\n').append(summary).append('\n').append(referrerPolicy).append('\n');
                    }
                    if(!signature.equals(key.toString())){signature=key.toString();items.clear();items.addAll(next);page=Math.min(page,Math.max(0,(items.size()-1)/PAGE_SIZE));renderCards();if(previewItem!=null)for(Item item:items)if(item.url.equals(previewItem.url)){updatePreview(item);break;}}
                    if(!items.isEmpty()) {
                        WebsiteHealth.clearCatalogIssue(this,initialUrl);
                        status.setText(showingSearch?"找到 "+items.size()+" 项内容":"首页精选 · "+items.size()+" 项内容");
                        if(focusResults){focusResults=false;focusFirstCard();}
                    } else if(last)status.setText(data.optString("notice","").isEmpty()?"这个页面暂时没有可读取的影视卡片，可继续等待或打开原网页":"这个页面有待处理弹窗，请打开原网页操作");
                    else status.setText(data.optString("notice","").isEmpty()?"正在等待网页内容…":"这个页面有待处理弹窗，请打开原网页操作");
                    if(last&&!items.isEmpty())loading=false;
                }catch(JSONException ignored){if(last)status.setText("暂未能读取这个页面，可继续等待或打开原网页");}
            });
        }catch(Exception e){fail("未能读取这个页面，可打开原网页继续");}
    }
    private boolean valid(long epoch,String url){return reader!=null&&started&&!isFinishing()&&!isDestroyed()&&epoch==generation&&Objects.equals(url,currentUrl)&&Objects.equals(url,reader.getUrl());}
    private void catalogUnreadable(){if(!bookmarkPreview&&!detailOpening&&items.isEmpty())WebsiteHealth.recordPlaybackIssue(this,initialUrl,currentUrl,WebsiteHealth.PlaybackIssue.CATALOG_UNREADABLE);}
    private void showMissingBookmark(){browser.removeAllViews();backButton.setText("返回收藏");TextView missing=text("收藏已不存在",24,MUTED);missing.setGravity(Gravity.CENTER);browser.addView(missing,new LinearLayout.LayoutParams(-1,0,1));TextView back=button("返回收藏",this::finish);browser.addView(back,new LinearLayout.LayoutParams(-1,dp(52)));back.requestFocus();}
    private void fail(String message){if(isDestroyed())return;generation++;loading=false;main.removeCallbacksAndMessages(null);if(status!=null)status.setText(message);}
    private String searchTemplate() {
        String custom=app.settings.getString(templateKey,null);
        if(SiteSearch.validTemplate(custom))return custom;
        String known=SiteSearch.builtIn(initialUrl);return known!=null?known:detectedTemplate;
    }
    private void search() {
        if(reader==null){showNoProvider();return;}
        String word=query.getText().toString().trim();if(word.isEmpty()){query.setError("请输入要找的影片或节目");query.requestFocus();return;}
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);
        query.setShowSoftInputOnFocus(false);
        if(word.length()>200){query.setError("搜索词请控制在 200 字以内");return;}
        String address=SiteSearch.apply(searchTemplate(),word);
        if(address==null) {
            AlertDialog d=new AlertDialog.Builder(this).setTitle("这个网站暂未识别搜索入口").setMessage("可以打开原网页搜索；家人也可设置一次这个网站的搜索地址。")
                .setPositiveButton("原网页搜索",(x,w)->openOriginal(currentUrl)).setNeutralButton("家人设置",(x,w)->configureSearch()).setNegativeButton("取消",null).create();
            d.setOnShowListener(v->focus(d,-2));d.show();return;
        }
        searchButton.requestFocus();load(address,true);focusResults=true;
    }
    private void configureSearch() {
        LinearLayout form=column();form.setPadding(dp(24),dp(8),dp(24),0);
        form.addView(text("粘贴网站搜索地址，把关键词替换成 {query}。\n例如 https://example.org/search?wd={query}",15,MUTED));
        EditText input=new EditText(this);input.setTextColor(Color.WHITE);font(input,18);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);String existing=searchTemplate();input.setText(existing==null?"":existing);form.addView(input);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("站内搜索地址").setView(form).setPositiveButton("保存",null).setNeutralButton("恢复自动识别",(x,w)->{app.settings.edit().remove(templateKey).apply();}).setNegativeButton("取消",null).create();
        d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{String value=input.getText().toString().trim();if(!SiteSearch.validTemplate(value)){input.setError("需要完整 HTTP/HTTPS 地址，且只包含一个 {query}");return;}app.settings.edit().putString(templateKey,value).apply();d.dismiss();searchButton.requestFocus();}));d.show();input.requestFocus();
    }
    private void renderCards() {
        if(grid==null)return;
        View focused=getCurrentFocus();String restore=focused!=null&&focused.getTag() instanceof String?(String)focused.getTag():null;
        long imageEpoch=++cardsGeneration;
        grid.removeAllViews();pager.removeAllViews();int start=page*PAGE_SIZE,end=Math.min(items.size(),start+PAGE_SIZE);
        for(int i=start;i<end;i+=3) {
            LinearLayout line=row();
            for(int col=0;col<3;col++) {
                int index=i+col;LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(250),1);p.setMargins(dp(4),dp(4),dp(4),dp(4));
                if(index>=end){line.addView(new View(this),p);continue;}
                Item item=items.get(index);LinearLayout card=column();card.setPadding(dp(10),dp(10),dp(10),dp(10));card.setFocusable(true);card.setFocusableInTouchMode(true);card.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);card.setBackground(background(false));card.setOnFocusChangeListener((v,f)->v.setBackground(background(f)));card.setOnClickListener(v->openItem(item));card.setTag(item.url);card.setContentDescription("网站内容："+item.title);
                FrameLayout cover=new FrameLayout(this);TextView placeholder=text("暂无封面",15,MUTED);placeholder.setGravity(Gravity.CENTER);cover.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.FIT_CENTER);poster.setContentDescription("封面："+item.title);cover.addView(poster,new FrameLayout.LayoutParams(-1,-1));card.addView(cover,new LinearLayout.LayoutParams(-1,0,1));
                TextView label=text(item.title,20,Color.WHITE);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setPadding(dp(8),dp(8),dp(8),0);card.addView(label,new LinearLayout.LayoutParams(-1,dp(56)));line.addView(card,p);
                images.bind(poster,item.image,currentUrl,reader==null?null:reader.getSettings().getUserAgentString(),item.referrerPolicy,()->imageEpoch==cardsGeneration&&!isDestroyed(),ok->placeholder.setVisibility(ok?View.GONE:View.VISIBLE));
                if(item.url.equals(restore))card.requestFocus();
            }
            grid.addView(line);
        }
        int pages=Math.max(1,(items.size()+PAGE_SIZE-1)/PAGE_SIZE);
        if(pages>1) {
            TextView prev=button("上一页",()->{page--;renderCards();focusFirstCard();});prev.setEnabled(page>0);pager.addView(prev,new LinearLayout.LayoutParams(dp(150),dp(42)));
            TextView count=text((page+1)+" / "+pages,16,MUTED);count.setGravity(Gravity.CENTER);pager.addView(count,new LinearLayout.LayoutParams(dp(100),-1));
            TextView next=button("下一页",()->{page++;renderCards();focusFirstCard();});next.setEnabled(page<pages-1);pager.addView(next,new LinearLayout.LayoutParams(dp(150),dp(42)));
        }
    }
    private void focusFirstCard(){if(grid.getChildCount()>0&&grid.getChildAt(0) instanceof LinearLayout){LinearLayout first=(LinearLayout)grid.getChildAt(0);if(first.getChildCount()>0)first.getChildAt(0).requestFocus();}}
    private void openItem(Item item){focusedUrl=item.url;showPreview(item);}
    private void showPreview(Item item) {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);query.setShowSoftInputOnFocus(false);
        if(previewPanel!=null)body.removeView(previewPanel);
        previewItem=item;browser.setVisibility(View.GONE);backButton.setText(previewBackTitle());
        previewPanel=row();previewPanel.setGravity(Gravity.CENTER_VERTICAL);previewPanel.setPadding(dp(16),dp(24),dp(16),dp(24));
        FrameLayout cover=new FrameLayout(this);cover.setBackground(background(false));previewPlaceholder=text("暂无封面",18,MUTED);previewPlaceholder.setId(R.id.site_preview_placeholder);previewPlaceholder.setGravity(Gravity.CENTER);cover.addView(previewPlaceholder,new FrameLayout.LayoutParams(-1,-1));
        previewPoster=new ImageView(this);previewPoster.setId(R.id.site_preview_poster);previewPoster.setContentDescription("预览封面："+item.title);previewPoster.setScaleType(ImageView.ScaleType.FIT_CENTER);cover.addView(previewPoster,new FrameLayout.LayoutParams(-1,-1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-1,2);cp.setMargins(0,0,dp(32),0);previewPanel.addView(cover,cp);
        LinearLayout detail=column();ScrollView information=new ScrollView(this);information.setFocusable(true);information.setContentDescription("影片介绍，可上下滚动");LinearLayout words=column();information.addView(words);detail.addView(information,new LinearLayout.LayoutParams(-1,0,1));words.addView(text("影视预览",16,BLUE));previewTitle=text(item.title,30,Color.WHITE);previewTitle.setTypeface(null,Typeface.BOLD);previewTitle.setMaxLines(2);previewTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);previewTitle.setPadding(0,dp(12),0,dp(12));words.addView(previewTitle);
        TextView source=text("来自 "+siteName,14,MUTED);source.setPadding(0,0,0,dp(16));words.addView(source);
        previewSummary=text("",18,MUTED);words.addView(previewSummary);
        LinearLayout playActions=row();previewPlay=button("进入网站播放",()->openOriginal(previewItem.url));playActions.addView(previewPlay,new LinearLayout.LayoutParams(0,dp(56),1));
        previewFavorite=button(new SiteBookmarks(app.settings).hasFilm(initialUrl,item.url)?"取消收藏":"收藏影片",this::toggleFilmFavorite);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(132),dp(56));fp.leftMargin=dp(8);playActions.addView(previewFavorite,fp);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.setMargins(0,dp(24),0,dp(10));detail.addView(playActions,pp);
        detail.addView(button(previewBackTitle(),localPreview()?this::finish:this::closePreview),new LinearLayout.LayoutParams(-1,dp(48)));previewPanel.addView(detail,new LinearLayout.LayoutParams(0,-1,3));body.addView(previewPanel,new FrameLayout.LayoutParams(-1,-1));
        bindPreview(item);previewPlay.requestFocus();
    }
    private void bindPreview(Item item){previewSummary.setText(item.summary.isEmpty()?"可进入网站查看详情和播放。":item.summary);previewPlaceholder.setVisibility(View.VISIBLE);long epoch=++previewGeneration;images.bind(previewPoster,item.image,currentUrl,reader==null?null:reader.getSettings().getUserAgentString(),item.referrerPolicy,()->previewItem!=null&&epoch==previewGeneration&&!isDestroyed(),ok->previewPlaceholder.setVisibility(ok?View.GONE:View.VISIBLE));}
    private void updatePreview(Item item){boolean changed=!Objects.equals(previewItem.image,item.image)||!Objects.equals(previewItem.referrerPolicy,item.referrerPolicy);previewItem=item;previewTitle.setText(item.title);previewSummary.setText(item.summary.isEmpty()?"可进入网站查看详情和播放。":item.summary);if(changed)bindPreview(item);}
    private void closePreview(){previewGeneration++;previewItem=null;if(previewPanel!=null){body.removeView(previewPanel);previewPanel=null;}browser.setVisibility(View.VISIBLE);backButton.setText("返回网站");main.post(()->{View card=grid.findViewWithTag(focusedUrl);if(card!=null)card.requestFocus();else focusFirstCard();});}
    private void toggleFilmFavorite(){if(previewItem==null)return;Item item=previewItem;SiteBookmarks saved=new SiteBookmarks(app.settings);if(saved.hasFilm(initialUrl,item.url)){AlertDialog dialog=new AlertDialog.Builder(this).setTitle("取消收藏？").setMessage("取消“"+item.title+"”的收藏？").setPositiveButton("取消收藏",(d,which)->{saved.removeFilm(initialUrl,item.url);if(previewItem!=null&&previewItem.url.equals(item.url))previewFavorite.setText("收藏影片");Toast.makeText(this,"已取消影片收藏",Toast.LENGTH_SHORT).show();}).setNegativeButton("取消",null).create();dialog.setOnShowListener(v->focus(dialog,AlertDialog.BUTTON_NEGATIVE));dialog.show();return;}try{saved.toggleFilm(initialUrl,item.url,item.title,item.image,item.summary,item.referrerPolicy);previewFavorite.setText("取消收藏");Toast.makeText(this,"影片已收藏",Toast.LENGTH_SHORT).show();}catch(IllegalArgumentException|IllegalStateException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}
    private void favoriteFilms(){List<SiteBookmarks.Entry> saved=new SiteBookmarks(app.settings).films(initialUrl);if(saved.isEmpty()){Toast.makeText(this,"本站还没有收藏影片，可在预览页选择收藏",Toast.LENGTH_LONG).show();return;}String[] names=new String[saved.size()];for(int i=0;i<names.length;i++)names[i]=saved.get(i).title;new AlertDialog.Builder(this).setTitle("我的影片收藏").setItems(names,(d,i)->{SiteBookmarks.Entry e=saved.get(i);showPreview(new Item(e.title,e.url,e.image,e.summary,e.referrerPolicy));}).setNegativeButton("返回",null).show();}
    private void openOriginal(String url) {
        if(detailOpening||WebsitePolicy.normalize(url)==null)return;
        detailOpening=true;startActivity(WebsitePlayback.intent(this,url,siteName).putExtra("siteRoot",initialUrl));
    }
    private void more(){if(localPreview()){new AlertDialog.Builder(this).setTitle(siteName).setItems(new String[]{"外部打开",previewBackTitle()},(d,i)->{if(i==0)external();else finish();}).setNegativeButton("关闭",null).show();return;}new AlertDialog.Builder(this).setTitle(siteName).setItems(new String[]{"刷新当前内容","搜索设置（家人）","外部打开","返回网站列表"},(d,i)->{if(i==0)load(currentUrl,showingSearch);if(i==1)configureSearch();if(i==2)external();if(i==3)finish();}).setNegativeButton("关闭",null).show();}
    private void external(){try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW,Uri.parse(localPreview()&&previewItem!=null?previewItem.url:currentUrl)),"选择已安装的官方客户端或浏览器"));}catch(ActivityNotFoundException e){Toast.makeText(this,"设备没有可处理此网址的应用",Toast.LENGTH_LONG).show();}}
    private void back(){if(localPreview()){finish();return;}if(previewItem!=null){closePreview();return;}if(showingSearch){query.setText("");load(initialUrl,false);query.requestFocus();}else finish();}
    @Override public void onBackPressed(){back();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getKeyCode()==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())more();return true;}if(getCurrentFocus()==query&&event.getAction()==KeyEvent.ACTION_DOWN){if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);query.setShowSoftInputOnFocus(false);focusResults=items.isEmpty();focusFirstCard();return true;}if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP){backButton.requestFocus();return true;}}return super.dispatchKeyEvent(event);}
    @Override protected void onResume(){super.onResume();started=true;detailOpening=false;if(reader!=null){reader.onResume();if(resumeInterrupted){resumeInterrupted=false;String active=reader.getUrl();if(items.isEmpty()&&previewItem==null)load(currentUrl,showingSearch);else if(WebsitePolicy.normalize(active)!=null&&Objects.equals(active,currentUrl)){loading=true;long epoch=++generation;installReaderClient(epoch,false);scheduleScans(epoch,active);}else load(currentUrl,showingSearch);}}if(previewItem!=null)main.post(()->{if(localPreview()&&bookmarkPausedFocus!=null&&bookmarkPausedFocus.isAttachedToWindow())bookmarkPausedFocus.requestFocus();else previewPlay.requestFocus();});else if(!focusedUrl.isEmpty())main.post(()->{View card=grid.findViewWithTag(focusedUrl);if(card!=null)card.requestFocus();});}
    @Override protected void onPause(){if(localPreview())bookmarkPausedFocus=getCurrentFocus();queryCenterDown=false;resumeInterrupted=!localPreview()&&(loading||items.isEmpty());started=false;generation++;main.removeCallbacksAndMessages(null);loading=false;if(reader!=null){reader.stopLoading();reader.evaluateJavascript("Array.prototype.forEach.call(document.querySelectorAll('video,audio'),function(m){m.pause();})",null);reader.onPause();}super.onPause();}
    @Override protected void onDestroy(){generation++;cardsGeneration++;previewGeneration++;if(images!=null)images.close();main.removeCallbacksAndMessages(null);if(reader!=null){reader.stopLoading();root.removeView(reader);reader.destroy();reader=null;}super.onDestroy();}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private float scale(){android.util.DisplayMetrics m=getResources().getDisplayMetrics();return Math.min(1f,Math.min(m.widthPixels/m.density/960f,m.heightPixels/m.density/540f));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density*scale());}
    private void font(TextView view,int size){view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(size)*Math.min(1.15f,getResources().getConfiguration().fontScale));}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);font(t,size);return t;}
    private TextView button(String title,Runnable action){TextView t=text(title,16,Color.WHITE);t.setGravity(Gravity.CENTER);t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);t.setFocusable(true);t.setFocusableInTouchMode(true);t.setPadding(dp(12),dp(8),dp(12),dp(8));t.setBackground(background(false));t.setOnFocusChangeListener((v,f)->v.setBackground(background(f)));t.setOnClickListener(v->action.run());return t;}
    private GradientDrawable background(boolean focused){GradientDrawable d=new GradientDrawable();d.setColor(focused?0xff283e57:CARD);d.setCornerRadius(dp(10));if(focused)d.setStroke(Math.max(1,dp(2)),BLUE);return d;}
    private void focus(AlertDialog d,int which){android.widget.Button b=d.getButton(which);b.setFocusableInTouchMode(true);b.requestFocus();}
}
