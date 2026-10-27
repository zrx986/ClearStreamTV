package tv.clearstream;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import androidx.media3.ui.*;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import tv.clearstream.core.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final int BG=0xff101418, PANEL=0xff1b222b, BLUE=0xff4c9aff, TEXT=0xfff5f7fa, MUTED=0xff9eabba;
    private ClearApp app;
    private final AtomicBoolean subscriptionUpdating=new AtomicBoolean(false);
    private LinearLayout root,content,primaryNav;
    private FrameLayout frame;
    private TextView headerStatus, playerStatus, subtitle,simplePlaybackHint;
    private String page="home",filter="全部";
    private int homePage;
    private long lastFocusedChannel;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private PlayerView playerView;
    private View playerControls;
    private Channel playing,pendingChannel;
    private boolean resumeAfterStop;
    private AlertDialog playbackError;
    private AlertDialog channelPicker;
    private int popupKey;
    private boolean cctvOnly;
    private List<ChannelGuide.SearchItem> catalogIndex;
    private Set<String> catalogFavorites=new HashSet<>();
    private Set<String> renderedWebsiteUrls=Collections.emptySet();
    private WebsiteHealth websiteHealth;
    private final Map<String,List<TextView>> websiteHealthLabels=new HashMap<>();
    private SourceManager sourceManager;
    private String catalogCategory="全部",searchBackPage="home",catalogFocusKey="";
    private int catalogPage,catalogGeneration;
    private boolean started,resumed,checksRunning,singleCheckRunning;
    private final AtomicBoolean cancelChecks=new AtomicBoolean(false);
    private boolean simpleMode() {return app.settings.getBoolean("simpleMode",true);}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(headerStatus!=null) headerStatus.setText(app.network.localSummary()+"  |  "+new java.text.SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date()));
            if(playerStatus!=null) playerStatus.setText("最近媒体连接 "+app.network.mediaFamily+"  ·  下载 "+app.network.mediaSpeed);
            main.postDelayed(this,1500);
        }
    };
    private final Runnable hideControls=()-> { if(playerControls!=null && controller!=null && controller.isPlaying()) playerControls.setVisibility(View.GONE); };
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState); app=(ClearApp)getApplication();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        controllerFuture=new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PlaybackService.class))).buildAsync();
        controllerFuture.addListener(()-> {
            try {
                if(isDestroyed())return;
                controller=controllerFuture.get();
                controller.addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException error) {
                        if("player".equals(page)) runOnUiThread(()->showPlaybackError(error));
                    }
                    @Override public void onIsPlayingChanged(boolean isPlaying) {
                        if(!"player".equals(page))return;
                        if(simpleMode()&&simplePlaybackHint!=null) {
                            boolean paused=!controller.getPlayWhenReady();
                            simplePlaybackHint.setText(paused?"已暂停 · 按菜单选择“继续播放”":"上下换台   确定选台   返回回首页   菜单：暂停 / 收藏");
                            if(paused&&playerControls!=null)playerControls.setVisibility(View.VISIBLE);
                        }
                        if(isPlaying)scheduleHide();
                    }
                });
                playPending();
            } catch (Exception e) { pendingChannel=null;toast("播放器初始化失败，请重新打开应用"); }
        },command->main.post(command));
        shell(); home();
        if(Intent.ACTION_VIEW.equals(getIntent().getAction())&&getIntent().getData()!=null)readImportUri(getIntent().getData());
    }
    @Override public void onStart() {
        super.onStart();started=true;main.removeCallbacks(tick);main.post(tick);
        if(resumeAfterStop&&controller!=null&&page.equals("player"))controller.play();
        resumeAfterStop=false;playPending();
    }
    @Override public void onResume() {
        super.onResume();resumed=true;
        if(page.equals("sources")&&sourceManager!=null){sourceManager.refresh();return;}
        if(!page.equals("websites"))return;
        List<Website> saved=app.store.websites();Set<String> current=new HashSet<>();String added=null;
        for(Website site:saved){current.add(site.url);if(!renderedWebsiteUrls.contains(site.url))added=site.url;}
        String selected=app.settings.getString("directoryReturnUrl",added);app.settings.edit().remove("directoryReturnUrl").apply();
        if(!current.equals(renderedWebsiteUrls))websites();else startWebsiteChecks();
        if(selected!=null){View target=content.findViewWithTag("website:"+selected);if(target!=null)target.requestFocus();}
    }
    @Override public void onPause(){resumed=false;if(sourceManager!=null)sourceManager.suspend();stopWebsiteChecks();super.onPause();}
    @Override public void onStop() {
        started=false;popupKey=0; main.removeCallbacks(tick); main.removeCallbacks(hideControls);
        if(controller!=null&&!app.settings.getBoolean("background",false)) {
            resumeAfterStop=page.equals("player")&&controller.getPlayWhenReady();controller.pause();
        }
        super.onStop();
    }
    @Override public void onDestroy() {
        if(sourceManager!=null)sourceManager.close();
        if(channelPicker!=null){channelPicker.dismiss();channelPicker=null;}
        pendingChannel=null;cancelChecks.set(true); main.removeCallbacksAndMessages(null);
        if(playerView!=null) playerView.setPlayer(null);
        if(controllerFuture!=null) MediaController.releaseFuture(controllerFuture);
        super.onDestroy();
    }
    private float layoutScale() {
        android.util.DisplayMetrics m=getResources().getDisplayMetrics();
        return Math.min(1f,Math.min(m.widthPixels/m.density/960f,m.heightPixels/m.density/540f));
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density*layoutScale()); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value,float size,int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(size)*Math.min(1.15f,getResources().getConfiguration().fontScale)); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t;
    }
    private GradientDrawable surface(int color,int stroke) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(10)); if(stroke!=0)d.setStroke(dp(2),stroke); return d;
    }
    private void focusable(View v) {
        v.setFocusable(true); v.setFocusableInTouchMode(true);
        v.setBackground(surface(PANEL,0));
        v.setOnFocusChangeListener((view,has)-> {
            view.setBackground(surface(has?0xff243b55:PANEL,has?BLUE:0));
            view.setElevation(has?dp(6):0);
        });
    }
    private TextView button(String label,Runnable action) {
        TextView t=text(label,16,TEXT); t.setGravity(Gravity.CENTER); t.setPadding(dp(15),dp(11),dp(15),dp(11));
        t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);t.setIncludeFontPadding(false);
        focusable(t); t.setOnClickListener(v->action.run()); return t;
    }
    private void addButton(LinearLayout parent,String label,Runnable action) {
        TextView b=button(label,action); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1); p.setMargins(dp(4),dp(4),dp(4),dp(4)); parent.addView(b,p);
    }
    private void shell() {
        if(playerView!=null) { playerView.setPlayer(null); playerView=null; }
        playerControls=null; playerStatus=null;
        frame=new FrameLayout(this); frame.setBackgroundColor(BG); setContentView(frame);
        root=column(); root.setPadding(dp(28),dp(14),dp(28),dp(12)); frame.addView(root,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=row();
        TextView brand=text("澄流 TV",20,TEXT); brand.setTypeface(null,Typeface.BOLD); top.addView(brand,new LinearLayout.LayoutParams(0,dp(36),1));
        headerStatus=text("网络检测中",11,MUTED);headerStatus.setSingleLine(true);headerStatus.setEllipsize(android.text.TextUtils.TruncateAt.END); top.addView(headerStatus); root.addView(top);
        content=column(); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=row();primaryNav=nav; nav.setPadding(0,dp(10),0,0);
        addButton(nav,simpleMode()?"首页":"直播",()-> { filter="全部"; homePage=0; home(); });
        addButton(nav,"央视直播",this::openCctv);
        addButton(nav,"搜台",this::searchChannels);
        addButton(nav,"影视网站",this::websites);
        addButton(nav,"收藏",()->startActivityForResult(new Intent(this,FavoritesActivity.class),775));
        if(!simpleMode())addButton(nav,"设置",this::settings);
        root.addView(nav);
    }
    private void reset(String newPage,String title,String description) {
        if(!newPage.equals("sources")&&sourceManager!=null)sourceManager.suspend();
        if(!newPage.equals("websites")){stopWebsiteChecks();websiteHealthLabels.clear();}
        if(!newPage.equals("opening"))pendingChannel=null;
        page=newPage;
        if(content==null || root.getParent()==null || playerControls!=null) shell();
        content.removeAllViews();
        LinearLayout heading=row();
        TextView h=text(title,25,TEXT); h.setTypeface(null,Typeface.BOLD); h.setPadding(0,dp(10),0,dp(5));heading.addView(h,new LinearLayout.LayoutParams(0,-2,1));content.addView(heading);
        if(newPage.equals("home")&&!simpleMode()) {
            TextView add=button("一键导入",simpleMode()?this::quickImport:this::importDialog);heading.addView(add,new LinearLayout.LayoutParams(dp(110),dp(40)));
            TextView manage=button(simpleMode()?"管理模式":"简易模式",simpleMode()?this::enterAdmin:this::leaveAdmin);
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(110),dp(40));mp.setMargins(dp(8),0,0,0);heading.addView(manage,mp);
        }
        subtitle=text(description,13,MUTED); subtitle.setPadding(0,0,0,dp(12)); content.addView(subtitle);
        if(primaryNav!=null)for(int i=0;i<primaryNav.getChildCount();i++) {
            TextView tab=(TextView)primaryNav.getChildAt(i);
            boolean selected=i==0&&(newPage.equals("home")&&!filter.equals("收藏")||newPage.equals("sources"))
                ||i==1&&newPage.equals("cctv")||i==2&&newPage.equals("search")||i==3&&newPage.equals("websites")||i==4&&newPage.equals("home")&&filter.equals("收藏")||i==5&&newPage.equals("settings");
            tab.setTextColor(selected?BLUE:MUTED);tab.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);tab.setSelected(selected);
        }
    }
    private void home() {
        cctvOnly=false;
        if("player".equals(page)&&controller!=null) { controller.stop(); controller.clearMediaItems(); }
        if(simpleMode()&&filter.equals("全部")){simpleHome();return;}
        reset("home",filter.equals("收藏")?"我的收藏":"直播频道","选择频道，按确定键播放");
        List<Channel> all=app.store.all();
        subtitle.setText(all.size()+" 个频道  ·  "+(simpleMode()?"简易模式":"管理模式")+"  ·  按确定键播放");
        LinearLayout filters=row(); HorizontalScrollView hs=new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinkedHashSet<String> groups=new LinkedHashSet<>(simpleMode()?Arrays.asList("全部","收藏"):Arrays.asList("全部","IPv6","收藏"));
        for(Channel c:all)groups.add(c.group);
        for(String group:groups) {
            TextView b=button(group.equals(filter)?"• "+group:group,()-> { filter=group; homePage=0; home(); });
            b.setMaxWidth(dp(180));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(43)); p.setMargins(0,0,dp(8),0); filters.addView(b,p);
        }
        hs.addView(filters); content.addView(hs);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout grid=column(); grid.setPadding(0,dp(14),0,dp(10)); scroll.addView(grid);
        List<Channel> visible=new ArrayList<>();
        for(Channel c:all) {
            if(filter.equals("全部")||(filter.equals("收藏")&&c.favorite)||(filter.equals("IPv6")&&c.isV6())||filter.equals(c.group))visible.add(c);
        }
        visible=ChannelGuide.programs(visible,app.settings.getString("lastWatchedUrl",""));
        int totalVisible=visible.size();
        homePage=Math.min(homePage,Math.max(0,(totalVisible-1)/24));
        visible=new ArrayList<>(visible.subList(Math.min(homePage*24,totalVisible),Math.min(homePage*24+24,totalVisible)));
        if(visible.isEmpty()) {
            LinearLayout empty=column(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20),dp(18),dp(20),dp(18));
            empty.setBackground(surface(PANEL,0));
            TextView symbol=text("6",54,BLUE); symbol.setTypeface(null,Typeface.BOLD); empty.addView(symbol);
            empty.addView(text(all.isEmpty()?"把你的直播，带到大屏":"这里还没有频道",23,TEXT));
            TextView hint=text(simpleMode()?"选择朋友准备好的频道表，即可开始观看":"添加 IPv6 / IPv4 链接，或导入 M3U、TXT 频道表",13,MUTED); hint.setPadding(0,dp(10),0,dp(14)); empty.addView(hint);
            TextView importButton=button(simpleMode()?"＋  一键导入频道":"＋  导入直播源",simpleMode()?this::quickImport:this::importDialog); empty.addView(importButton,new LinearLayout.LayoutParams(dp(220),dp(48))); grid.addView(empty);
            importButton.requestFocus();
        } else {
            LinearLayout current=null; View first=null,restore=null;
            for(int i=0;i<visible.size();i++) {
                if(i%3==0) { current=row(); current.setGravity(Gravity.TOP); grid.addView(current); }
                Channel c=visible.get(i); LinearLayout card=column(); focusable(card); card.setPadding(dp(18),dp(14),dp(18),dp(12));
                TextView index=text(String.format(Locale.ROOT,"直播  %02d",homePage*24+i+1),14,BLUE); card.addView(index);
                TextView name=text((c.favorite?"☆  ":"")+ChannelNames.display(c.name),19,TEXT); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(name);
                TextView meta=text(simpleMode()?"按确定观看":c.group+"   ·   "+c.hint(),11,MUTED); meta.setSingleLine(true);meta.setEllipsize(android.text.TextUtils.TruncateAt.END);meta.setPadding(0,dp(8),0,0); card.addView(meta);
                card.setOnClickListener(v->{lastFocusedChannel=c.id;play(c);}); card.setOnLongClickListener(v-> { if(!simpleMode())channelMenu(c); return true; });
                card.setContentDescription(c.name+"，"+c.group+"，按确定播放"+(simpleMode()?"":"，长按管理"));
                card.setMinimumHeight(dp(124));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); p.setMargins(dp(4),dp(4),dp(4),dp(4)); current.addView(card,p);
                if(first==null)first=card;
                if(c.id==lastFocusedChannel)restore=card;
                if(i==visible.size()-1) for(int j=(i%3)+1;j<3;j++)current.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
            }
            if(restore!=null)restore.requestFocus();else if(first!=null)first.requestFocus();
        }
        LinearLayout actions=row();
        if(!simpleMode()) {addButton(actions,"管理源 · "+all.size(),()->sources(false));content.addView(actions);}
        if(totalVisible>24) {
            if(simpleMode())content.addView(actions);
            addButton(actions,"上一页",()-> {homePage=Math.max(0,homePage-1);home();});
            addButton(actions,(homePage+1)+" / "+((totalVisible+23)/24)+" 下一页",()-> {homePage=Math.min((totalVisible-1)/24,homePage+1);home();});
        }
    }
    private void simpleHome() {
        List<Channel> all=app.store.all();Channel target=ChannelGuide.recent(all,app.settings.getString("lastWatchedUrl",""));
        reset("home","看电视",all.isEmpty()?"尚未添加频道，请先导入":"按确定选择已添加的频道");
        ScrollView scroll=new ScrollView(this);LinearLayout body=column();body.setPadding(0,dp(8),0,dp(12));scroll.addView(body);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView watch=button("看电视",()->{if(app.store.all().isEmpty())quickImport();else{catalogCategory="全部";catalogPage=0;catalogFocusKey="";openCatalog();}});watch.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(42));watch.setMinimumHeight(dp(136));
        watch.setContentDescription(all.isEmpty()?"看电视，请先导入频道":"看电视，按确定选台");
        LinearLayout watchActions=row();LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,dp(136),1);wp.rightMargin=dp(12);watchActions.addView(watch,wp);
        TextView films=button("看影视",this::websites);films.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(42));films.setContentDescription("看影视，选择网站或添加网站，按确定进入");watchActions.addView(films,new LinearLayout.LayoutParams(0,dp(136),1));body.addView(watchActions);
        TextView current=text(all.isEmpty()?"无预置频道和网站，请家人先帮忙添加":"已添加 "+ChannelGuide.programs(all,null).size()+" 个频道，按确定选台",22,TEXT);current.setSingleLine(true);current.setEllipsize(android.text.TextUtils.TruncateAt.END);current.setPadding(dp(16),dp(14),dp(16),dp(20));body.addView(current);
        TextView filmSearch=button("一键搜片",()->startActivity(new Intent(this,FilmSearchActivity.class)));filmSearch.setContentDescription("一键搜片，搜索动漫电影电视剧");body.addView(filmSearch,new LinearLayout.LayoutParams(-1,dp(54)));
        if(target!=null){
            LinearLayout choices=row();addButton(choices,"选频道",this::openCatalog);addButton(choices,"我的收藏",()->{catalogCategory="收藏";catalogPage=0;openCatalog();});body.addView(choices);
            TextView guide=text("看电视时：上下换台 · 确定选台 · 返回回首页",18,MUTED);guide.setPadding(dp(12),dp(20),0,dp(14));body.addView(guide);
        }
        TextView family=button("家人设置",this::familySettings);body.addView(family,new LinearLayout.LayoutParams(-1,dp(46)));watch.requestFocus();
    }
    private void openCctv() {
        openLocalCctv();
    }
    private void openLocalCctv() {
        List<Channel> stations=ChannelGuide.cctv(app.store.all(),app.settings.getString("lastWatchedUrl",""));
        cctvOnly=false;
        if(!stations.isEmpty()){cctvOnly=true;play(ChannelGuide.recent(stations,app.settings.getString("lastWatchedUrl","")));return;}
        reset("cctv","央视直播","还没有央视频道，请家人添加包含央视的频道表");
        TextView add=button("请家人添加频道",this::quickImport);content.addView(add,new LinearLayout.LayoutParams(-1,dp(86)));add.requestFocus();
        content.addView(text("添加后再点底部“央视直播”，即可直接播放。",20,MUTED));
    }
    private void searchChannels() {
        searchBackPage=page.equals("catalog")?"catalog":"home";
        reset("search","搜台","输入频道名或台号，例如 CCTV1、央视一套、湖南");
        List<Channel> all=app.store.all();List<ChannelGuide.SearchItem> index=new ArrayList<>();boolean[] ready={false};
        LinearLayout actions=row();EditText query=input("输入频道名 / 台号","",false);query.setSingleLine(true);query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        actions.addView(query,new LinearLayout.LayoutParams(0,dp(52),1));TextView search=button("搜索",()->{});actions.addView(search,new LinearLayout.LayoutParams(dp(110),dp(52)));content.addView(actions);
        TextView count=text("",16,MUTED);count.setPadding(dp(6),dp(12),0,dp(10));content.addView(count);
        ScrollView scroll=new ScrollView(this);LinearLayout results=column();scroll.addView(results);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Runnable render=()-> {
            results.removeAllViews();int total=0,shown=0;String term=query.getText().toString().trim();
            ChannelNames.Query matcher=new ChannelNames.Query(term);
            for(ChannelGuide.SearchItem entry:index) {
                if(!matcher.matches(entry.key,entry.text))continue;total++;Channel channel=entry.channel;
                if(shown++>=60)continue;
                int routes=entry.routes;TextView item=button(entry.title+"    ·    "+routes+" 条线路",()->{cctvOnly=false;play(channel);});
                item.setContentDescription(ChannelNames.display(channel.name)+"，"+routes+" 条线路，按确定播放");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(62));lp.setMargins(0,0,0,dp(6));results.addView(item,lp);
            }
            count.setText(!ready[0]?"正在整理频道…":total==0?"没有找到频道，可换个名字或请家人添加频道":"找到 "+total+" 个频道"+(total>60?"，显示前 60 个，请输入更多字缩小范围":""));
        };
        render.run();app.network.io.execute(()->{List<ChannelGuide.SearchItem> loaded=ChannelGuide.searchIndex(all,app.settings.getString("lastWatchedUrl",""));main.post(()->{if(isDestroyed()||!page.equals("search")||!query.isAttachedToWindow())return;index.addAll(loaded);ready[0]=true;render.run();});});
        query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){render.run();}public void afterTextChanged(android.text.Editable e){}});
        Runnable searchAction=()-> {
            render.run();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);
            if(results.getChildCount()>0)results.getChildAt(0).requestFocus();else search.requestFocus();
        };
        search.setOnClickListener(v->searchAction.run());query.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){searchAction.run();return true;}if(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER){if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())searchAction.run();return true;}return false;});query.requestFocus();
    }
    private void openCatalog() {
        if(page.equals("player")&&controller!=null){controller.stop();controller.clearMediaItems();}
        cctvOnly=false;catalogIndex=null;int generation=++catalogGeneration;
        reset("catalog","分类选台","正在整理频道…");
        app.network.io.execute(()-> {
            List<Channel> all=app.store.all();List<ChannelGuide.SearchItem> loaded=ChannelGuide.searchIndex(all,app.settings.getString("lastWatchedUrl",""));Set<String> favorites=new HashSet<>();
            for(Channel channel:all)if(channel.favorite)favorites.add(ChannelNames.key(channel.name,channel.group,channel.url));
            main.post(()->{if(isDestroyed()||generation!=catalogGeneration)return;catalogIndex=loaded;catalogFavorites=favorites;if(page.equals("catalog"))renderCatalog();});
        });
    }
    private void renderCatalog() {
        reset("catalog","分类选台","同一频道已合并，多个来源收在备用线路里");
        LinearLayout tabs=row();content.addView(tabs);
        for(String category:new String[]{"全部","央视","卫视","地方/其他","收藏"})addButton(tabs,(category.equals(catalogCategory)?"● ":"")+category,()->{catalogCategory=category;catalogPage=0;catalogFocusKey="";renderCatalog();});
        if(catalogIndex==null){content.addView(text("正在整理频道…",22,MUTED));return;}
        List<ChannelGuide.SearchItem> shown=new ArrayList<>();
        for(ChannelGuide.SearchItem entry:catalogIndex) {
            boolean cctv=entry.key.startsWith("cctv:"),satellite=!cctv&&entry.title.contains("卫视");
            if(catalogCategory.equals("全部")||catalogCategory.equals("央视")&&cctv||catalogCategory.equals("卫视")&&satellite||catalogCategory.equals("地方/其他")&&!cctv&&!satellite||catalogCategory.equals("收藏")&&catalogFavorites.contains(entry.key))shown.add(entry);
        }
        subtitle.setText(catalogCategory+" · "+shown.size()+" 个频道");
        ScrollView scroll=new ScrollView(this);LinearLayout grid=column();scroll.addView(grid);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        catalogPage=Math.min(catalogPage,Math.max(0,(shown.size()-1)/24));int start=catalogPage*24,end=Math.min(start+24,shown.size());LinearLayout line=null;View first=null,restore=null;
        for(int i=start;i<end;i++) {
            if((i-start)%3==0){line=row();grid.addView(line);}
            ChannelGuide.SearchItem entry=shown.get(i);LinearLayout card=column();focusable(card);card.setPadding(dp(18),dp(18),dp(18),dp(18));card.setMinimumHeight(dp(104));
            TextView name=text((catalogFavorites.contains(entry.key)?"☆ ":"")+entry.title,22,TEXT);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
            card.addView(text(entry.routes+" 条线路 · 按确定观看",15,MUTED));card.setContentDescription(entry.title+"，"+entry.routes+" 条线路，按确定播放");
            card.setOnClickListener(v->{catalogFocusKey=entry.key;cctvOnly=catalogCategory.equals("央视");play(entry.channel);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(4),dp(4),dp(4),dp(4));line.addView(card,lp);
            if(first==null)first=card;if(entry.key.equals(catalogFocusKey))restore=card;
            if(i==end-1)for(int j=((i-start)%3)+1;j<3;j++)line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        }
        if(shown.isEmpty()){TextView empty=text(catalogCategory.equals("收藏")?"还没有收藏，播放时按菜单可收藏频道":"没有找到这类频道，请家人添加频道表",22,MUTED);empty.setPadding(dp(15),dp(30),dp(15),dp(30));grid.addView(empty);}
        LinearLayout actions=row();addButton(actions,"搜台",this::searchChannels);addButton(actions,"回首页",()->{filter="全部";home();});
        if(shown.size()>24){addButton(actions,"上一页",()->{catalogPage=Math.max(0,catalogPage-1);renderCatalog();});addButton(actions,(catalogPage+1)+" / "+((shown.size()+23)/24)+" 下一页",()->{catalogPage++;renderCatalog();});}content.addView(actions);
        if(restore!=null)restore.requestFocus();else if(first!=null)first.requestFocus();else tabs.getChildAt(0).requestFocus();
    }
    private void familySettings() {
        new AlertDialog.Builder(this).setTitle("请家人帮忙设置").setItems(new String[]{"添加 / 导入频道","管理直播源","更新已保存的频道订阅","网站与源网页","更多设置（管理模式）"},(d,i)-> {
            if(i==0)quickImport();if(i==1)sources(false);if(i==2)refreshSubscriptions();if(i==3)websites();if(i==4)enterAdmin();
        }).setNegativeButton("回去看电视",null).show();
    }
    private void sources(boolean speedPage) {
        reset("sources",speedPage?"源测速与检测":"我的源","网络可连接不等于媒体可播放  ·  检测不会删除你的源");
        manager().render(content);
    }
    private SourceManager manager(){
        if(sourceManager==null)sourceManager=new SourceManager(this,app,main,new SourceManager.Actions(){
            public void importSources(){importDialog();}public void refreshSubscriptions(){MainActivity.this.refreshSubscriptions();}
            public void inspect(List<Channel> targets){batchProbe(targets);}public void openChannel(Channel c){channelMenu(c);}
            public boolean checking(){return checksRunning||singleCheckRunning;}public boolean busy(){return checksRunning||singleCheckRunning||subscriptionUpdating.get();}
            public boolean active(){return resumed&&!isDestroyed()&&page.equals("sources");}
            public void cancelChecks(){cancelChecks.set(true);app.network.cancelChecks();}
            public void libraryChanged(){catalogIndex=null;catalogGeneration++;List<Channel> all=app.store.all();if(controller!=null){MediaItem item=controller.getCurrentMediaItem();if(item!=null){boolean found=false;for(Channel c:all)if(Long.toString(c.id).equals(item.mediaId)&&item.localConfiguration!=null&&c.url.equals(item.localConfiguration.uri.toString())){found=true;break;}if(!found){controller.stop();controller.clearMediaItems();playing=null;pendingChannel=null;}}}if(page.equals("home")&&resumed)home();}
        });return sourceManager;
    }
    private void websites() {
        stopWebsiteChecks();websiteHealthLabels.clear();
        reset("websites","影视网站","选择网站 · 搜索影片 · 按确定播放");
        LinearLayout actions=row();addButton(actions,"一键搜片",()->startActivity(new Intent(this,FilmSearchActivity.class)));TextView addSite=button("＋ 添加网站",this::addWebsiteDialog);LinearLayout.LayoutParams addSiteParams=new LinearLayout.LayoutParams(0,dp(48),1);addSiteParams.setMargins(dp(4),dp(4),dp(4),dp(4));actions.addView(addSite,addSiteParams);addButton(actions,"网站收藏",this::favoriteWebsites);
        if(!simpleMode())addButton(actions,"直播源工具",()->new AlertDialog.Builder(this).setTitle("直播源工具").setItems(new String[]{"从网址导入直播源","更新已保存订阅"},(d,i)->{if(i==0)subscriptionWizard();else refreshSubscriptions();}).setNegativeButton("返回",null).show());content.addView(actions);
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();scroll.addView(list);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView heading=text("选择网站 · 按确定进入",21,TEXT);heading.setPadding(dp(8),dp(14),dp(8),dp(6));list.addView(heading);
        List<Website> sites=app.store.websites();
        renderedWebsiteUrls=new HashSet<>();for(Website site:sites)renderedWebsiteUrls.add(site.url);
        if(sites.isEmpty()) {
            TextView note=text("还没有网站，请选择“添加网站”保存你自己的网址。",16,MUTED);note.setPadding(dp(12),dp(18),dp(12),dp(12));list.addView(note);addSite.requestFocus();
        }
        else {TextView mine=text("我添加的网站",21,TEXT);mine.setPadding(dp(8),dp(16),dp(8),dp(8));list.addView(mine);}
        for(Website site:sites) {
            LinearLayout line=row();
            LinearLayout info=column();focusable(info);info.setTag("website:"+site.url);info.setContentDescription("网站入口："+site.name);info.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);info.setOnClickListener(v->openWebsite(site.url,site.name));
            TextView card=text("▣  "+site.name+"\n"+Uri.parse(site.url).getHost(),16,TEXT);card.setGravity(Gravity.CENTER);card.setSingleLine(false);card.setMaxLines(2);card.setPadding(dp(14),dp(8),dp(14),0);
            info.addView(card,new LinearLayout.LayoutParams(-1,0,1));TextView check=text("检测中…",13,MUTED);check.setPadding(dp(14),dp(2),dp(10),dp(4));info.addView(check,new LinearLayout.LayoutParams(-1,dp(28)));trackHealthLabel(site.url,check);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(88),1);p.setMargins(0,dp(6),dp(8),dp(6));line.addView(info,p);
            line.addView(button(new SiteBookmarks(app.settings).hasSite(site.url)?"★ 已收藏":"☆ 收藏",()->toggleWebsiteFavorite(site.url,site.name)));
            line.addView(button("更多",()->new AlertDialog.Builder(this).setTitle(site.name).setItems(simpleMode()?new String[]{"读取/更新源"}:new String[]{"读取/更新源","移除网站"},(d,i)->{
                if(i==0){if(app.settings.getStringSet("subscriptions",Collections.emptySet()).contains(site.url))refreshSubscriptions(Collections.singleton(site.url));else fetchSubscription(site.url,"默认");}
                else {AlertDialog confirm=new AlertDialog.Builder(this).setTitle("移除此网站入口？").setPositiveButton("移除",(x,w)->{app.store.deleteWebsite(site);websites();}).setNegativeButton("保留",null).create();confirm.setOnShowListener(v->focusDialogButton(confirm,-2));confirm.show();}
            }).setNegativeButton("返回",null).show()));
            list.addView(line);if(site==sites.get(0))info.requestFocus();
        }
        startWebsiteChecks();
    }
    private void openWebsite(String url,String name){startActivity(WebsitePlayback.portalIntent(this,url,name));}
    private void stopWebsiteChecks(){if(websiteHealth!=null){websiteHealth.close();websiteHealth=null;}}
    private void trackHealthLabel(String url,TextView label){List<TextView> labels=websiteHealthLabels.get(url);if(labels==null){labels=new ArrayList<>();websiteHealthLabels.put(url,labels);}labels.add(label);}
    private void startWebsiteChecks(){if(!resumed||!page.equals("websites"))return;if(websiteHealth==null)websiteHealth=new WebsiteHealth(this,main);WebsiteHealth active=websiteHealth;for(Map.Entry<String,List<TextView>> entry:websiteHealthLabels.entrySet()){String url=entry.getKey();List<TextView> labels=entry.getValue();active.check(url,false,s->{if(websiteHealth==active&&websiteHealthLabels.get(url)==labels)for(TextView label:labels)applyWebsiteCheck(label,s);});for(TextView label:labels)applyWebsiteCheck(label,active.status(url));}}
    private void applyWebsiteCheck(TextView view,WebsiteHealth.Snapshot s){String message=s.checking?"检测中…":s.label+(s.reason.isEmpty()?"":" · "+s.reason);if(s.playbackTimestamp>0&&!message.contains("实播"))message+=" · 有实播记录";view.setSingleLine(true);view.setEllipsize(android.text.TextUtils.TruncateAt.END);view.setText(message);view.setTextColor(s.playbackIssueTimestamp>0||s.state==WebsiteHealth.State.VERIFY_REQUIRED||s.state==WebsiteHealth.State.UNAVAILABLE?0xffedb56a:s.state==WebsiteHealth.State.PAGE_OK?0xff79c6a9:MUTED);}
    private void toggleWebsiteFavorite(String url,String name){try{boolean saved=new SiteBookmarks(app.settings).toggleSite(url,name);websites();toast(saved?"网站已收藏":"已取消网站收藏");}catch(IllegalArgumentException|IllegalStateException e){toast(e.getMessage());}}
    private void favoriteWebsites(){startActivityForResult(new Intent(this,FavoritesActivity.class).putExtra("tab","sites"),775);}
    private void addWebsiteDialog() {
        LinearLayout l=form();EditText url=input("输入你的网站完整网址","",false);l.addView(url);
        l.addView(text("影视网站可直接打开；挂载源的网站保存后可选“读取/更新源”。",13,MUTED));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("添加影视网址").setView(l).setPositiveButton("保存入口",null).setNegativeButton("取消",null).create();
        d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{String value=WebsitePolicy.normalize(url.getText().toString());if(value==null){url.setError("请输入有效网页地址");return;}
            if(app.store.websites().size()>=100){toast("最多保存 100 个网站入口");return;}
            boolean added=app.store.addWebsite(value);d.dismiss();websites();toast(added?"已加入影视网站，选卡片即可打开":"该网址已保存");}));d.show();url.requestFocus();
    }
    private AlertDialog dialog(String title,View view,String yes,Runnable action) {
        AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(view).setPositiveButton(yes,(v,w)->action.run()).setNegativeButton("取消",null).create();
        d.setOnShowListener(v-> { if(d.getWindow()!=null)d.getWindow().setBackgroundDrawable(surface(PANEL,0)); }); d.show(); return d;
    }
    private EditText input(String hint,String value,boolean multi) {
        EditText e=new EditText(this); e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(16)*Math.min(1.15f,getResources().getConfiguration().fontScale)); e.setHint(hint); e.setText(value);
        e.setSingleLine(!multi); e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (multi?android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        if(multi){e.setMinLines(3);e.setMaxLines(5);} return e;
    }
    private LinearLayout form() { LinearLayout l=column(); l.setPadding(dp(24),dp(8),dp(24),dp(8)); return l; }
    private void importDialog() {
        new AlertDialog.Builder(this).setTitle("导入直播源").setItems(new String[]{"添加单条 IPv6 / IPv4 地址","粘贴 M3U / TXT 频道表","从文件导入","添加网络订阅"},(d,i)-> {
            if(i==2) {
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(intent,101); } catch(ActivityNotFoundException e) { toast("此设备没有文件选择器，请使用粘贴或订阅导入"); }
                return;
            }
            LinearLayout l=form(); EditText name=input("频道名（单条源）","",false),group=input("分组","默认",false);
            EditText value=input(i==3?"https://example.org/list.m3u":i==0?"http://[IPv6地址]/live.m3u8":"粘贴频道表内容","",i==1);
            if(i==0)l.addView(name); l.addView(group); l.addView(value);
            TextView note=text(i==3?"仅访问你提供的地址；更新保留已有源和收藏。":"支持 HTTP / HTTPS；IPv6 字面量用 [] 包围。",12,MUTED); l.addView(note);
            AlertDialog formDialog=new AlertDialog.Builder(this).setTitle(i==3?"添加订阅":"添加直播源").setView(l).setPositiveButton(i==3?"获取并预览":"预览导入",null).setNegativeButton("取消",null).create();
            formDialog.setOnShowListener(v->formDialog.getButton(-1).setOnClickListener(b-> {
                String raw=value.getText().toString().trim(),g=group.getText().toString().trim();
                if(i==0) {
                    if(!UrlPolicy.isSupported(raw)) { value.setError("请输入合法 HTTP/HTTPS 源，IPv6 地址需带 []"); return; }
                    if(PlaylistParser.isSubscriptionUrl(raw)){formDialog.dismiss();fetchSubscription(raw,g);return;}
                    String title=name.getText().toString().trim(); if(title.isEmpty())title="直播流";
                    ImportResult result=new ImportResult(); result.entries.add(new SourceEntry(title,raw,g.isEmpty()?"默认":g)); formDialog.dismiss(); preview(result,null);
                } else if(i==3) {
                    if(!UrlPolicy.isSupported(raw)){value.setError("订阅地址无效");return;}
                    formDialog.dismiss(); fetchSubscription(raw,g);
                } else {
                    formDialog.dismiss();
                    if(UrlPolicy.isSupported(raw)&&PlaylistParser.isSubscriptionUrl(raw))fetchSubscription(raw,g);
                    else showImportResult(SubscriptionImporter.parse(raw,null),null,g,new HashSet<>(),0);
                }
            })); formDialog.show(); value.requestFocus();
        }).setNegativeButton("返回",null).show();
    }
    private ImportResult withGroup(ImportResult result,String group) {
        if(group==null||group.trim().isEmpty()||group.equals("默认"))return result;
        for(int i=0;i<result.entries.size();i++) { SourceEntry e=result.entries.get(i); if(e.group.equals("默认"))result.entries.set(i,new SourceEntry(e.name,e.url,group)); } return result;
    }
    private void fetchSubscription(String url,String group) {
        fetchSubscription(url,group,new SourceSubscriptions.Session(url,app.settings,false));
    }
    private void fetchSubscription(String url,String group,SourceSubscriptions.Session session) {
        toast("正在读取源网站 / 订阅…");app.network.io.execute(()-> {
            try {
                SourceSubscriptions.Loaded loaded=SourceSubscriptions.load(app.network,url,session);
                main.post(()-> {if(!isFinishing()&&!isDestroyed())showImportResult(loaded.result,group,loaded.session,loaded.inline);});
            } catch(Exception e){main.post(()->importFailure(NetworkEngine.error(e)));}
        });
    }
    private void importFailure(String reason) {
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle("订阅未导入").setMessage(reason+"\n\n现有频道和收藏未改动。\nTLS / DNS / 超时属于下载问题；取得频道表也不代表其中每条频道都能播放。可让朋友下载 M3U/TXT 文件后使用“一键导入”。")
            .setPositiveButton("知道了",null).show();
    }
    private void showImportResult(SubscriptionImporter.Result result,String subscription,String group,Set<String> visited,int depth) {
        showImportResult(result,group,new SourceSubscriptions.Session(subscription,app.settings,true),false);
    }
    private void showImportResult(SubscriptionImporter.Result result,String group,SourceSubscriptions.Session session,boolean inline) {
        if(result.options.isEmpty()||inline){preview(withGroup(result.playlist,group),session.root,session);return;}
        List<String> choices=new ArrayList<>();
        if(!result.playlist.entries.isEmpty())choices.add("导入配置内的 "+result.playlist.entries.size()+" 条静态直播");
        for(SubscriptionImporter.Option option:result.options)choices.add(option.name);
        String title=result.type.equals("源网页")?"发现网页源链接 · 选择一次后记住":"识别为 TVBox 配置 · 选择入口";
        new AlertDialog.Builder(this).setTitle(title).setItems(choices.toArray(new String[0]),(d,index)-> {
            if(!result.playlist.entries.isEmpty()&&index==0){preview(withGroup(result.playlist,group),session.root,session);return;}
            SubscriptionImporter.Option option=result.options.get(index-(result.playlist.entries.isEmpty()?0:1));
            if(session.root==null)fetchSubscription(option.url,group);
            else {session.chooseManually(option);fetchSubscription(option.url,group,session);}
        }).setNegativeButton("取消",null).show();
    }
    private void enterAdmin() {
        AlertDialog d=new AlertDialog.Builder(this).setTitle("进入管理模式？").setMessage("这里可以修改频道、订阅和网络设置。日常看电视无需进入。")
            .setPositiveButton("进入管理",(x,w)->{app.settings.edit().putBoolean("simpleMode",false).apply();shell();settings();})
            .setNegativeButton("继续看电视",null).create();
        d.setOnShowListener(v->focusDialogButton(d,-2));d.show();
    }
    private void leaveAdmin() {app.settings.edit().putBoolean("simpleMode",true).apply();filter="全部";shell();home();toast("已切换简易模式");}
    private void quickImport() {
        new AlertDialog.Builder(this).setTitle("一键导入频道").setMessage("自动识别频道表；已有频道和收藏会保留。")
            .setPositiveButton("选择源文件",(d,w)-> {
                try {startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),101);}
                catch(ActivityNotFoundException e){toast("盒子没有文件选择器，可使用订阅链接导入");subscriptionWizard();}
            }).setNeutralButton("使用订阅链接",(d,w)->subscriptionWizard()).setNegativeButton("返回",null).show();
    }
    private void subscriptionWizard() {
        LinearLayout l=form();EditText url=input("粘贴朋友提供的完整订阅链接","",false);l.addView(url);
        l.addView(text("可填源网站首页、M3U/TXT 订阅或 TVBox JSON；选择一次，以后点更新。",13,MUTED));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("导入朋友准备的频道").setView(l).setPositiveButton("识别频道",null).setNegativeButton("返回",null).create();
        d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{String value=url.getText().toString().trim();if(!UrlPolicy.isSupported(value)){url.setError("链接不完整，请粘贴完整 HTTP/HTTPS 地址");return;}d.dismiss();fetchSubscription(value,"默认");}));d.show();
    }
    private void restoreImport() {
        ImportBackup backup=new ImportBackup(this);
        if(!backup.exists()){toast("还没有可恢复的导入记录");return;}
        try {
            List<Channel> previous=backup.load();
            AlertDialog d=new AlertDialog.Builder(this).setTitle("撤销上次导入？").setMessage("当前 "+app.store.all().size()+" 个频道，将恢复为导入前的 "+previous.size()+" 个频道。收藏与分组一并恢复。")
                .setPositiveButton("确认恢复",(x,w)->{try{List<Channel> current=app.store.all();backup.save(current);app.store.replaceAll(previous);filter="全部";home();toast("已恢复，仍可再次撤销");}catch(Exception e){toast("恢复失败，保留现有频道");}})
                .setNegativeButton("保留当前频道",null).create();d.setOnShowListener(v->focusDialogButton(d,-2));d.show();
        }catch(Exception e){toast("备份不可读取，保留当前频道");}
    }
    private void preview(ImportResult result,String subscription) {preview(result,subscription,null);}
    private void preview(ImportResult result,String subscription,SourceSubscriptions.Session session) {
        StringBuilder detail=new StringBuilder("识别 ").append(result.entries.size()).append(" 条，表内重复 ").append(result.duplicates).append(" 条\n\n");
        for(int i=0;i<Math.min(5,result.entries.size());i++)detail.append(result.entries.get(i).group).append(" / ").append(result.entries.get(i).name).append("\n");
        if(!result.errors.isEmpty()) { detail.append("\n注意：\n"); for(int i=0;i<Math.min(4,result.errors.size());i++)detail.append(result.errors.get(i)).append("\n"); }
        detail.append("\n添加后仍需实际播放验证；相同 URL 不会覆盖已有名称和收藏。");
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("导入预览").setMessage(detail.toString()).setNegativeButton("取消",null);
        if(!result.entries.isEmpty())b.setPositiveButton("确认导入",(d,w)-> {
            try {new ImportBackup(this).save(app.store.all());}catch(Exception e){toast("无法保存恢复点，本次未导入");return;}
            int n=app.store.add(result.entries);
            if(session!=null)session.save(app.settings);
            else if(subscription!=null) {
                Set<String> set=new HashSet<>(app.settings.getStringSet("subscriptions",Collections.emptySet())); set.add(subscription); app.settings.edit().putStringSet("subscriptions",set).apply();
            }
            filter="全部";home();
            if(simpleMode())showImportComplete(result,n);
            else toast("已添加 "+n+" 条；重复或超过一万条上限的源未加入");
        }); b.show();
    }
    private void showImportComplete(ImportResult result,int added) {
        List<Channel> channels=app.store.all();Channel first=null;
        Set<String> imported=new HashSet<>();for(SourceEntry entry:result.entries)imported.add(entry.url);
        for(Channel channel:channels)if(imported.contains(channel.url)){first=channel;break;}
        if(first==null)return;Channel target=first;
        AlertDialog complete=new AlertDialog.Builder(this).setTitle("频道已准备好")
            .setMessage("新增 "+added+" 条，已有频道已保留。\n现在就可以看电视了。")
            .setPositiveButton("开始看电视",(d,w)->play(target)).setNegativeButton("稍后观看",null).create();
        complete.setOnShowListener(v->focusDialogButton(complete,-1));complete.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==775&&result==RESULT_OK&&data!=null&&data.getBooleanExtra("channelFavorites",false)){catalogCategory="收藏";catalogPage=0;openCatalog();return;}
        if(request==101&&result==RESULT_OK&&data!=null&&data.getData()!=null) {
            readImportUri(data.getData());
        }
    }
    private void readImportUri(Uri uri) {
        if(!"content".equals(uri.getScheme())&&!"file".equals(uri.getScheme())){toast("请通过文件管理器选择源文件");return;}
        app.network.io.execute(()-> {
                try(InputStream in=getContentResolver().openInputStream(uri)) {
                    if(in==null)throw new IOException("无法读取文件");
                    String raw=new String(NetworkEngine.readBounded(in,2*1024*1024,true),StandardCharsets.UTF_8);
                    SubscriptionImporter.Result parsed=SubscriptionImporter.parse(raw,null); main.post(()-> { if(!isDestroyed())showImportResult(parsed,null,"默认",new HashSet<>(),0); });
                } catch(Exception e) { main.post(()->toast("读取失败：文件应为 UTF-8 且不超过 2MB")); }
        });
    }
    private void refreshSubscriptions() {
        refreshSubscriptions(new HashSet<>(app.settings.getStringSet("subscriptions",Collections.emptySet())));
    }
    private void refreshSubscriptions(Set<String> subs) {
        if(subs.isEmpty()){toast("先添加源网站或订阅，再更新");return;}
        if(!subscriptionUpdating.compareAndSet(false,true)){toast("正在更新，请稍候");return;}
        toast("正在更新 "+subs.size()+" 个源网站 / 订阅");
        app.network.io.execute(()-> {
            int added=0,failed=0,read=0;boolean backedUp=false;StringBuilder failures=new StringBuilder();
            for(String url:subs) {
                try {
                    SourceSubscriptions.Loaded loaded=SourceSubscriptions.load(app.network,url,new SourceSubscriptions.Session(url,app.settings,false));
                    if(!loaded.inline)throw new IOException("订阅入口发生变化，请重新选择源入口");
                    ImportResult r=loaded.result.playlist;
                    if(r.entries.isEmpty())throw new IOException("订阅入口未返回可用频道表，请重新识别");
                    if(!backedUp){new ImportBackup(this).save(app.store.all());backedUp=true;}
                    added+=app.store.add(r.entries);read+=r.entries.size();loaded.session.save(app.settings);
                }catch(Exception e){failed++;if(failed<=4)failures.append("\n").append(Uri.parse(url).getHost()).append("：").append(NetworkEngine.error(e));}
            }
            String message="读取 "+read+" 条，新增 "+added+" 条，失败 "+failed+" 个订阅。\n相同 URL 去重，已有名称和收藏保留；不自动删除旧频道。"+failures;
            boolean anyFailed=failed>0;
            main.post(()-> {
                subscriptionUpdating.set(false);if(isDestroyed()||isFinishing())return;
                if(page.equals("websites"))websites();else if(simpleMode())home();else sources(false);
                AlertDialog.Builder report=new AlertDialog.Builder(this).setTitle("更新结果").setMessage(message).setPositiveButton("知道了",null);
                if(anyFailed&&subs.size()==1){String root=subs.iterator().next();report.setNeutralButton("重新选择入口",(d,w)->fetchSubscription(root,"默认",new SourceSubscriptions.Session(root,app.settings,true)));}
                AlertDialog result=report.create();result.setOnShowListener(v->focusDialogButton(result,-1));result.show();
            });
        });
    }
    private void channelMenu(Channel c) {
        new AlertDialog.Builder(this).setTitle(c.name).setItems(new String[]{"播放",c.favorite?"取消收藏":"加入收藏","检测源","修改名称 / 分组","删除源"},(d,i)-> {
            if(i==0)play(c);
            if(i==1) {app.store.favorite(c);if(page.equals("home"))home();else sources(false);}
            if(i==2)probeSource(c);
            if(i==3) {
                LinearLayout l=form(); EditText name=input("名称",c.name,false),group=input("分组",c.group,false); l.addView(name);l.addView(group);
                dialog("修改源",l,"保存",()-> {String n=name.getText().toString().trim(),g=group.getText().toString().trim();if(!n.isEmpty())app.store.rename(c,n,g.isEmpty()?"默认":g);sources(false);});
            }
            if(i==4)manager().removeOne(c);
        }).setNegativeButton("返回",null).show();
    }
    private void batchProbe() {
        batchProbe(app.store.all());
    }
    private void probeSource(Channel c){
        if(checksRunning||singleCheckRunning||subscriptionUpdating.get()){toast("已有检测或更新，请等待完成或先取消");return;}
        if(controller!=null&&controller.isPlaying()){toast("请先停止播放，再检测源");return;}
        singleCheckRunning=true;cancelChecks.set(false);if(page.equals("sources"))sources(false);toast("正在复检此源…");
        app.network.io.execute(()->{String status=app.network.probe(c.url);boolean cancelled=cancelChecks.get();if(!cancelled)app.store.status(c,status);main.post(()->{singleCheckRunning=false;if(resumed&&!isDestroyed()){if(page.equals("sources"))sources(false);toast(cancelled?"已取消检测":status);}});});
    }
    private void batchProbe(List<Channel> targets) {
        if(controller!=null&&controller.isPlaying()){toast("请先停止播放，再执行批量检测");return;}
        if(singleCheckRunning||subscriptionUpdating.get()){toast("已有检测或更新，请等待完成或先取消");return;}
        if(checksRunning)return;
        List<Channel> channels=new ArrayList<>(targets); if(channels.isEmpty()){toast("没有可检测的源");return;}
        checksRunning=true;cancelChecks.set(false);sources(true);
        app.network.io.execute(()-> {
            int done=0;
            for(Channel c:channels) {
                if(cancelChecks.get())break;
                String status=app.network.probe(c.url);
                if(cancelChecks.get())break;app.store.status(c,status);done++;
                final int count=done;main.post(()-> {if(page.equals("sources")&&subtitle!=null)subtitle.setText("已检测 "+count+" / "+channels.size()+" · 可取消");});
            }
            final int count=done;main.post(()-> {checksRunning=false;if(!isDestroyed()&&page.equals("sources"))sources(true);toast("检测结束，共 "+count+" 条");});
        });
    }
    private void settings() {
        reset("settings","网络与播放","连接状态，一目了然  ·  诊断按需运行");
        LinearLayout panels=row();panels.setGravity(Gravity.TOP);content.addView(panels,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout left=column(),right=column();left.setPadding(dp(18),dp(14),dp(18),dp(14));right.setPadding(dp(18),dp(14),dp(18),dp(14));
        left.setBackground(surface(PANEL,0));right.setBackground(surface(PANEL,0));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(0,0,dp(14),0);panels.addView(left,p);
        ScrollView rightScroll=new ScrollView(this);rightScroll.addView(right);panels.addView(rightScroll,new LinearLayout.LayoutParams(0,-1,1));
        left.addView(text("连接检测",20,TEXT));
        ScrollView scroll=new ScrollView(this);TextView report=text(app.network.lastProbe,13,MUTED);report.setLineSpacing(dp(3),1);scroll.addView(report);left.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView detect=button("重新检测",()-> {
            report.setText("检测中…\n目标：www.cloudflare.com\n每种地址族最多等待 6 秒");
            app.network.io.execute(()-> {String value=app.network.diagnose();main.post(()-> {if(!isDestroyed())report.setText(value);});});
        });left.addView(detect,new LinearLayout.LayoutParams(-1,dp(45)));
        right.addView(text("连接偏好",20,TEXT));
        String[] names={"自动 · 优先 IPv6","仅 IPv6 · 诊断用","仅 IPv4 · 诊断用"};AddressPolicy.Mode[] modes=AddressPolicy.Mode.values();
        for(int i=0;i<names.length;i++) {
            AddressPolicy.Mode m=modes[i];TextView b=button((app.network.mode()==m?"◉ ":"○ ")+names[i],()-> {app.settings.edit().putString("networkMode",m.name()).apply();app.network.refreshPolicy();settings();});
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(42));bp.setMargins(0,dp(5),0,0);right.addView(b,bp);
        }
        String[] buffers={"低延迟","标准","稳定"}; int selected=app.settings.getInt("buffer",1);
        TextView buffer=button("缓冲策略 · "+buffers[selected],()->new AlertDialog.Builder(this).setTitle("缓冲策略（下次启动播放器生效）").setSingleChoiceItems(buffers,selected,(d,i)-> {app.settings.edit().putInt("buffer",i).apply();d.dismiss();settings();}).setNegativeButton("返回",null).show());
        right.addView(buffer,new LinearLayout.LayoutParams(-1,dp(45)));
        boolean bg=app.settings.getBoolean("background",false);
        TextView background=button("后台音频 · "+(bg?"开启":"关闭"),()-> {app.settings.edit().putBoolean("background",!bg).apply();settings();});right.addView(background,new LinearLayout.LayoutParams(-1,dp(45)));
        LinearLayout tools=row();addButton(tools,"管理频道",()->sources(false));addButton(tools,"撤销上次导入",this::restoreImport);addButton(tools,"返回简易模式",this::leaveAdmin);content.addView(tools);
        detect.requestFocus();
    }
    private void play(Channel channel) {
        if(controller==null){
            pendingChannel=channel;reset("opening","正在打开电视…","正在准备播放器，请稍等");
            TextView cancel=button("返回首页",()->{pendingChannel=null;filter="全部";home();});content.addView(cancel);cancel.requestFocus();return;
        }
        popupKey=0;pendingChannel=null;resumeAfterStop=false;main.removeCallbacks(hideControls);
        if(playbackError!=null){playbackError.dismiss();playbackError=null;}
        lastFocusedChannel=channel.id;
        if(checksRunning||singleCheckRunning){cancelChecks.set(true);app.network.cancelChecks();}
        if(!ChannelNames.isCctv(channel.name))cctvOnly=false;
        playing=channel;page="player";headerStatus=null;
        if(playerView!=null)playerView.setPlayer(null);
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);setContentView(frame);
        playerView=new PlayerView(this);playerView.setUseController(false);playerView.setPlayer(controller);playerView.setFocusable(false);
        frame.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout overlay=column();overlay.setPadding(dp(32),dp(18),dp(32),dp(18));overlay.setBackgroundColor(0xe6101418);
        overlay.addView(text(simpleMode()?ChannelNames.display(channel.name):channel.name,simpleMode()?28:23,TEXT));
        playerStatus=simpleMode()?null:text("正在连接 · "+channel.hint(),13,MUTED);if(playerStatus!=null)overlay.addView(playerStatus);
        LinearLayout controls=row();if(!simpleMode())addButton(controls,"频道",this::chooseChannel);
        if(!simpleMode()) {
            addButton(controls,"线路",this::chooseLine);addButton(controls,"清晰度",()->chooseTrack(C.TRACK_TYPE_VIDEO));addButton(controls,"音轨",()->chooseTrack(C.TRACK_TYPE_AUDIO));addButton(controls,"画面比例",this::aspect);
        }
        if(!simpleMode()) {
            addButton(controls,channel.favorite?"已收藏":"收藏",()-> {app.store.favorite(channel);toast(channel.favorite?"已收藏":"已取消收藏");});
            addButton(controls,"暂停 / 播放",()-> {if(controller.isPlaying())controller.pause();else controller.play();});overlay.addView(controls);
        }
        TextView tip=text(simpleMode()?"上下换台   确定选台   返回回首页   菜单：暂停 / 收藏":"确定：打开控制栏   返回：关闭控制栏 / 返回首页",simpleMode()?19:11,MUTED);tip.setPadding(dp(4),dp(8),0,0);overlay.addView(tip);simplePlaybackHint=simpleMode()?tip:null;
        FrameLayout.LayoutParams op=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);frame.addView(overlay,op);playerControls=overlay;
        controller.setMediaItem(new MediaItem.Builder().setUri(channel.url).setMediaId(Long.toString(channel.id))
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(channel.name).setArtist("澄流 TV").build()).build());
        controller.prepare();controller.play();if(controls.getChildCount()>0)controls.getChildAt(0).requestFocus();scheduleHide();
    }
    private void scheduleHide() {main.removeCallbacks(hideControls);main.postDelayed(hideControls,5000);}
    private void playPending() {
        if(!page.equals("opening")){pendingChannel=null;return;}
        if(started&&controller!=null&&pendingChannel!=null){Channel channel=pendingChannel;pendingChannel=null;play(channel);}
    }
    private boolean navigationKey(int key) {
        return key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();
        if(page.equals("player")) {
            if(simpleMode()&&(navigationKey(key)||key==KeyEvent.KEYCODE_CHANNEL_UP||key==KeyEvent.KEYCODE_CHANNEL_DOWN||key==KeyEvent.KEYCODE_MENU)) {
                boolean popup=key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER||key==KeyEvent.KEYCODE_MENU;
                if(popup) {
                    // Open the new window after release so the same key-up cannot activate it.
                    if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)popupKey=key;
                    if(event.getAction()==KeyEvent.ACTION_UP&&popupKey==key) {
                        popupKey=0;if(!event.isCanceled()){if(key==KeyEvent.KEYCODE_MENU)simplePlaybackMenu();else chooseChannel();}
                    }
                    return true;
                }
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0) {
                    if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_CHANNEL_DOWN)stepChannel(-1);
                    else if(key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_CHANNEL_UP)stepChannel(1);
                }
                return true;
            }
            if(!simpleMode()&&navigationKey(key)&&event.getAction()==KeyEvent.ACTION_DOWN) {
                if(playerControls!=null&&playerControls.getVisibility()!=View.VISIBLE) {
                    playerControls.setVisibility(View.VISIBLE);View first=firstFocusable(playerControls);if(first!=null)first.requestFocus();scheduleHide();return true;
                }
                scheduleHide();
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private void backToTelevisionHome() {
        if(channelPicker!=null){channelPicker.dismiss();channelPicker=null;}
        popupKey=0;pendingChannel=null;resumeAfterStop=false;main.removeCallbacks(hideControls);
        if(controller!=null){controller.stop();controller.clearMediaItems();}playing=null;filter="全部";shell();home();
    }
    @Override public void onBackPressed() {
        if(page.equals("search")&&searchBackPage.equals("catalog")){renderCatalog();return;}
        if(page.equals("opening")){pendingChannel=null;filter="全部";home();return;}
        if(page.equals("player")) {
            if(simpleMode()){backToTelevisionHome();return;}
            if(playerControls!=null&&playerControls.getVisibility()==View.VISIBLE) {playerControls.setVisibility(View.GONE);main.removeCallbacks(hideControls);return;}
            backToTelevisionHome();return;
        }
        if(!page.equals("home")||!filter.equals("全部")){filter="全部";home();return;}
        AlertDialog exit=new AlertDialog.Builder(this).setTitle("退出澄流 TV？").setPositiveButton("退出",(d,w)-> {if(controller!=null){controller.stop();controller.clearMediaItems();}finish();}).setNegativeButton("继续观看",null).create();
        exit.setOnShowListener(v->focusDialogButton(exit,-2));exit.show();
    }
    private void chooseChannel(){chooseChannel(true);}
    private void chooseChannel(boolean fromPlayer) {
        if(channelPicker!=null&&channelPicker.isShowing())return;
        List<Channel> items=cctvOnly?ChannelGuide.cctv(app.store.all(),playing==null?null:playing.url):ChannelGuide.programs(app.store.all(),playing==null?app.settings.getString("lastWatchedUrl",""):playing.url);
        if(items.isEmpty()){toast("还没有频道，请家人添加");return;}
        String[] names=new String[items.size()];for(int i=0;i<items.size();i++)names[i]=simpleMode()?ChannelNames.display(items.get(i).name):items.get(i).name;
        int selected=ChannelGuide.indexOf(items,fromPlayer?playing:ChannelGuide.recent(items,app.settings.getString("lastWatchedUrl","")));
        AlertDialog picker=new AlertDialog.Builder(this).setTitle(cctvOnly?"央视频道 · 共 "+items.size()+" 个":simpleMode()?"选择频道 · 共 "+items.size()+" 个":"选择频道")
            .setSingleChoiceItems(names,selected,(d,i)->{d.dismiss();play(items.get(i));}).setNegativeButton("返回",null).create();
        channelPicker=picker;picker.setOnDismissListener(d->{if(channelPicker==picker)channelPicker=null;});
        picker.setOnKeyListener((d,key,event)-> {
            if(key!=KeyEvent.KEYCODE_BACK)return false;
            if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())d.dismiss();return true;
        });picker.show();
    }
    private void stepChannel(int direction) {
        List<Channel> channels=cctvOnly?ChannelGuide.cctv(app.store.all(),playing==null?null:playing.url):ChannelGuide.programs(app.store.all(),playing==null?null:playing.url);
        if(channels.size()<2){toast("现在只有一个频道");return;}
        int index=ChannelGuide.indexOf(channels,playing);play(channels.get((index+direction+channels.size())%channels.size()));
    }
    private void simplePlaybackMenu() {
        if(controller==null||playing==null)return;
        boolean paused=!controller.getPlayWhenReady();
        new AlertDialog.Builder(this).setTitle(playing.name).setItems(new String[]{"选频道",paused?"继续播放":"暂停",playing.favorite?"取消收藏":"收藏这个频道","更多播放设置","回到首页"},(d,i)-> {
            if(i==0)chooseChannel();if(i==1){if(paused)controller.play();else controller.pause();toast(paused?"已继续播放":"已暂停，按菜单可继续播放");}
            if(i==2){app.store.favorite(playing);toast(playing.favorite?"已收藏":"已取消收藏");}if(i==3)morePlaybackSettings();if(i==4)backToTelevisionHome();
        }).setNegativeButton("关闭菜单",null).show();
    }
    private void morePlaybackSettings() {
        new AlertDialog.Builder(this).setTitle("播放设置").setItems(new String[]{"切换线路","清晰度","音轨","画面比例"},(d,i)-> {
            if(i==0)chooseLine();if(i==1)chooseTrack(C.TRACK_TYPE_VIDEO);if(i==2)chooseTrack(C.TRACK_TYPE_AUDIO);if(i==3)aspect();
        }).setNegativeButton("返回",null).show();
    }
    private void chooseLine() {
        if(playing==null)return;
        List<Channel> lines=ChannelGuide.lines(app.store.all(),playing);
        if(lines.size()<2){toast("当前频道只有一条线路；同一频道的不同 URL 作为备用线路");return;}
        String[] names=new String[lines.size()];for(int i=0;i<lines.size();i++)names[i]="线路 "+(i+1)+" · "+lines.get(i).hint();
        new AlertDialog.Builder(this).setTitle("切换线路").setItems(names,(d,i)->play(lines.get(i))).setNegativeButton("取消",null).show();
    }
    private void chooseTrack(int type) {
        if(controller==null)return;
        List<String> labels=new ArrayList<>();List<TrackSelectionOverride> overrides=new ArrayList<>();labels.add("自动");overrides.add(null);
        for(Tracks.Group group:controller.getCurrentTracks().getGroups()) {
            if(group.getType()!=type)continue;
            for(int i=0;i<group.length;i++)if(group.isTrackSupported(i)) {
                Format f=group.getTrackFormat(i);
                labels.add(type==C.TRACK_TYPE_VIDEO?(f.height>0?f.height+"p":"视频轨道")+(f.bitrate>0?String.format(Locale.ROOT," · %.1f Mbps",f.bitrate/1e6):""):(f.language==null?"音轨":f.language)+(f.label==null?"":" · "+f.label));
                overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(),i));
            }
        }
        if(labels.size()<2){toast("当前源尚未提供可选轨道");return;}
        new AlertDialog.Builder(this).setTitle(type==C.TRACK_TYPE_VIDEO?"源提供的清晰度":"源提供的音轨").setItems(labels.toArray(new String[0]),(d,i)-> {
            TrackSelectionParameters.Builder b=controller.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
            if(overrides.get(i)!=null)b.setOverrideForType(overrides.get(i));controller.setTrackSelectionParameters(b.build());
        }).setNegativeButton("返回",null).show();
    }
    private void aspect() {
        new AlertDialog.Builder(this).setTitle("画面比例").setItems(new String[]{"适应屏幕 · 保持比例","填充屏幕 · 裁剪","拉伸铺满"},(d,i)-> {
            playerView.setResizeMode(i==0?AspectRatioFrameLayout.RESIZE_MODE_FIT:i==1?AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FILL);
        }).setNegativeButton("返回",null).show();
    }
    private void showPlaybackError(PlaybackException error) {
        if(isDestroyed()||!started)return;
        if(playerControls!=null)playerControls.setVisibility(View.VISIBLE);
        String reason=error.errorCode==PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS?"服务器拒绝请求、鉴权过期或源已失效":error.errorCode==PlaybackException.ERROR_CODE_DECODING_FAILED?"设备无法解码该媒体，请尝试已有的其他线路或清晰度":"请检查网络、源地址及设备支持的格式";
        if(playbackError!=null&&playbackError.isShowing())return;
        AlertDialog.Builder message=new AlertDialog.Builder(this).setTitle(simpleMode()?"这个频道暂时看不了":"暂时无法播放");
        boolean hasAlternates=playing!=null&&ChannelGuide.lines(app.store.all(),playing).size()>1;
        if(simpleMode())message.setMessage("可以换个频道试试，或请家人检查频道和网络。")
            .setPositiveButton("换个频道",(d,w)->chooseChannel()).setNeutralButton(hasAlternates?"换条线路":"再试一次",(d,w)->{if(hasAlternates)chooseLine();else{controller.prepare();controller.play();}}).setNegativeButton("回到首页",(d,w)->backToTelevisionHome());
        else message.setMessage(reason+"\n错误："+error.getErrorCodeName()).setPositiveButton("重试",(d,w)->{controller.prepare();controller.play();}).setNeutralButton("其他线路",(d,w)->chooseLine()).setNegativeButton("返回源库",(d,w)->backToTelevisionHome());
        playbackError=message.create();playbackError.setOnShowListener(v->focusDialogButton(playbackError,-1));playbackError.show();
    }
    private void focusDialogButton(AlertDialog dialog,int which) {
        dialog.getButton(which).setFocusableInTouchMode(true);dialog.getButton(which).requestFocus();
    }
    private void toast(String message) { if(!isDestroyed())Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private View firstFocusable(View view) {
        if(view.isFocusable()&&view.isEnabled()&&view.getVisibility()==View.VISIBLE)return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View child=firstFocusable(((ViewGroup)view).getChildAt(i));if(child!=null)return child;}
        return null;
    }
}
