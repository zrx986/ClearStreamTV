package tv.clearstream;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import tv.clearstream.core.WebsitePolicy;

/** Local, remote-friendly entry to saved films and websites. It never probes film media. */
public final class FavoritesActivity extends Activity {
    private static final int BG=0xff101418,CARD=0xff1b222b,BLUE=0xff4c9aff,MUTED=0xffa9b7c7,PAGE_SIZE=12;
    private ClearApp app;private SiteBookmarks bookmarks;private SiteImages images;private WebsiteHealth health;
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout root,rows,pager;private TextView filmTab,siteTab,status;
    private final List<SiteBookmarks.Entry> films=new ArrayList<>(),sites=new ArrayList<>();
    private final Map<String,List<TextView>> healthViews=new HashMap<>();private final Set<String> checkedRoots=new HashSet<>();
    private final Map<ImageView,SiteBookmarks.Entry> posterEntries=new HashMap<>();private final Map<ImageView,TextView> posterPlaceholders=new HashMap<>();
    private int tab,page;private long imageGeneration;private boolean foreground,destroyed,built;
    private String filmFocus="",siteFocus="",pausedFocus="",signature="";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);app=(ClearApp)getApplication();bookmarks=new SiteBookmarks(app.settings);images=new SiteImages(app,main);
        tab=state==null?app.settings.getInt("favoritesTab",0):state.getInt("tab",0);if(tab!=1)tab=0;
        if(state==null){String requested=getIntent().getStringExtra("tab");if("sites".equals(requested))tab=1;else if("films".equals(requested))tab=0;}
        filmFocus=state==null?app.settings.getString("favoritesFilmFocus",""):state.getString("filmFocus","");siteFocus=state==null?app.settings.getString("favoritesSiteFocus",""):state.getString("siteFocus","");
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(28),dp(18),dp(28),dp(16));setContentView(root);
        LinearLayout header=row();TextView back=button("返回",this::finish);back.setTag("favorites:back");header.addView(back,new LinearLayout.LayoutParams(dp(96),dp(48)));
        TextView title=text("我的收藏",28,Color.WHITE);title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(18),0,dp(12),0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView refresh=button("刷新状态",this::refreshChecks);refresh.setTag("favorites:refresh");header.addView(refresh,new LinearLayout.LayoutParams(dp(125),dp(48)));
        TextView channels=button("频道收藏",()->{setResult(RESULT_OK,new Intent().putExtra("channelFavorites",true));finish();});channels.setTag("favorites:channels");channels.setContentDescription("频道收藏");header.addView(channels,new LinearLayout.LayoutParams(dp(145),dp(48)));root.addView(header);
        LinearLayout tabs=row();filmTab=button("影片",()->switchTab(0));siteTab=button("网站",()->switchTab(1));filmTab.setTag("favorites:tab:film");siteTab.setTag("favorites:tab:site");filmTab.setContentDescription("收藏标签：影片");siteTab.setContentDescription("收藏标签：网站");
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(dp(140),dp(46));tp.setMargins(0,dp(14),dp(10),0);tabs.addView(filmTab,tp);LinearLayout.LayoutParams st=new LinearLayout.LayoutParams(tp);tabs.addView(siteTab,st);root.addView(tabs);
        status=text("",14,MUTED);status.setPadding(dp(2),dp(12),0,dp(10));root.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);rows=column();scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        pager=row();pager.setGravity(Gravity.CENTER);root.addView(pager,new LinearLayout.LayoutParams(-1,dp(42)));
        TextView help=text("方向键选择 · 确定打开 · 取消收藏会再次确认",13,MUTED);help.setGravity(Gravity.CENTER);help.setPadding(0,dp(8),0,0);root.addView(help);
    }
    private void switchTab(int next){rememberFocus();tab=next;page=pageForFocus(currentFocus());render();(tab==0?filmTab:siteTab).requestFocus();saveFocus();}
    private List<SiteBookmarks.Entry> entries(){return tab==0?films:sites;}
    private String currentFocus(){return tab==0?filmFocus:siteFocus;}
    private static String entryKey(boolean film,SiteBookmarks.Entry entry){return (film?"film:":"site:")+entry.root+"\n"+entry.url;}
    private int pageForFocus(String focus){List<SiteBookmarks.Entry> list=entries();for(int i=0;i<list.size();i++){String key=entryKey(tab==0,list.get(i));if(key.equals(focus)||("remove:"+key).equals(focus))return i/PAGE_SIZE;}return 0;}
    private String sourceName(String address){for(SiteBookmarks.Entry entry:sites)if(entry.url.equals(address))return entry.title;for(Website website:app.store.websites())if(website.url.equals(address))return website.name;return WebsitePolicy.nameOf(address);}
    private String loadSaved(){films.clear();films.addAll(bookmarks.films(null));sites.clear();sites.addAll(bookmarks.sites());StringBuilder key=new StringBuilder();for(SiteBookmarks.Entry entry:films)key.append(entry.root).append('\n').append(entry.url).append('\n').append(entry.title).append('\n').append(entry.image).append('\n').append(entry.summary).append('\n').append(entry.referrerPolicy).append('\n');key.append("SITES\n");for(SiteBookmarks.Entry entry:sites)key.append(entry.url).append('\n').append(entry.title).append('\n');return key.toString();}
    private void render(){
        imageGeneration++;long epoch=imageGeneration;rows.removeAllViews();pager.removeAllViews();healthViews.clear();posterEntries.clear();posterPlaceholders.clear();filmTab.setSelected(tab==0);siteTab.setSelected(tab==1);filmTab.setTextColor(tab==0?BLUE:Color.WHITE);siteTab.setTextColor(tab==1?BLUE:Color.WHITE);
        List<SiteBookmarks.Entry> list=entries();int pages=Math.max(1,(list.size()+PAGE_SIZE-1)/PAGE_SIZE);page=Math.max(0,Math.min(page,pages-1));status.setText(tab==0?"已收藏 "+films.size()+" 部影片 · 选中即可进入预览":"已收藏 "+sites.size()+" 个网站 · 选中即可进入");int start=page*PAGE_SIZE,end=Math.min(list.size(),start+PAGE_SIZE);
        if(list.isEmpty()){TextView empty=text(tab==0?"还没有收藏影片\n在影片预览页选择“收藏影片”，以后从这里直接进入":"还没有收藏网站\n在影视网站中选择星标，常用网站就会保留在这里",22,MUTED);empty.setPadding(dp(16),dp(36),dp(16),dp(36));rows.addView(empty);}
        for(int i=start;i<end;){LinearLayout line=row();int columns=tab==0?2:1;
            for(int column=0;column<columns;column++){
                LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(0,dp(tab==0?160:112),1);itemParams.setMargins(dp(4),dp(4),dp(4),dp(8));if(i>=end){line.addView(new View(this),itemParams);continue;}
                SiteBookmarks.Entry entry=list.get(i++);String key=entryKey(tab==0,entry);boolean film=tab==0;LinearLayout tile=row();tile.setBackground(background(false));
                LinearLayout open=row();open.setPadding(dp(12),dp(10),dp(12),dp(10));open.setFocusable(true);open.setFocusableInTouchMode(true);open.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);open.setTag(key);open.setContentDescription((film?"收藏影片：":"收藏网站：")+entry.title);open.setBackground(background(false));open.setOnFocusChangeListener((v,focus)->v.setBackground(background(focus)));open.setOnClickListener(v->open(entry,film,key));
                if(film){FrameLayout cover=new FrameLayout(this);TextView placeholder=text("暂无封面",12,MUTED);placeholder.setGravity(Gravity.CENTER);cover.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.FIT_CENTER);poster.setContentDescription("收藏封面："+entry.title);cover.addView(poster,new FrameLayout.LayoutParams(-1,-1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(82),-1);cp.rightMargin=dp(12);open.addView(cover,cp);posterEntries.put(poster,entry);posterPlaceholders.put(poster,placeholder);images.bind(poster,entry.image,entry.root,null,entry.referrerPolicy,()->foreground&&!destroyed&&imageGeneration==epoch,ok->placeholder.setVisibility(ok?View.GONE:View.VISIBLE));}
                LinearLayout words=column();TextView name=text(entry.title,21,Color.WHITE);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(name,new LinearLayout.LayoutParams(-1,0,1));TextView source=text(film?"来自 "+sourceName(entry.root):WebsitePolicy.nameOf(entry.url),14,MUTED);source.setSingleLine(true);source.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(source);
                TextView checked=text("",12,MUTED);checked.setTag("health:"+entry.root);checked.setMaxLines(2);checked.setEllipsize(android.text.TextUtils.TruncateAt.END);checked.setContentDescription("收藏网站状态："+entry.title);words.addView(checked,new LinearLayout.LayoutParams(-1,dp(34)));open.addView(words,new LinearLayout.LayoutParams(0,-1,1));tile.addView(open,new LinearLayout.LayoutParams(0,-1,1));
                TextView remove=button("取消收藏",()->confirmRemoval(entry,film,key));remove.setTag("remove:"+key);remove.setContentDescription((film?"取消影片收藏：":"取消网站收藏：")+entry.title);remove.setMaxLines(2);remove.setSingleLine(false);tile.addView(remove,new LinearLayout.LayoutParams(dp(96),-1));line.addView(tile,itemParams);
                addHealth(entry.root,checked);
            }
            rows.addView(line);
        }
        if(pages>1){TextView before=button("上一页",()->changePage(-1));before.setEnabled(page>0);pager.addView(before,new LinearLayout.LayoutParams(dp(130),-1));TextView count=text((page+1)+" / "+pages,15,MUTED);count.setGravity(Gravity.CENTER);pager.addView(count,new LinearLayout.LayoutParams(dp(90),-1));TextView after=button("下一页",()->changePage(1));after.setEnabled(page<pages-1);pager.addView(after,new LinearLayout.LayoutParams(dp(130),-1));}
    }
    private void addHealth(String address,TextView target){List<TextView> views=healthViews.get(address);if(views==null){views=new ArrayList<>();healthViews.put(address,views);}views.add(target);if(health==null)return;applyHealth(target,health.status(address));if(checkedRoots.add(address)){WebsiteHealth running=health;health.check(address,false,snapshot->{if(foreground&&!destroyed&&health==running){List<TextView> current=healthViews.get(address);if(current!=null)for(TextView view:current)applyHealth(view,snapshot);}});}}
    private void applyHealth(TextView target,WebsiteHealth.Snapshot state){target.setText(state.checking?"检测网站连接…":state.label+(state.reason.isEmpty()?"":" · "+state.reason));target.setTextColor(state.state==WebsiteHealth.State.UNAVAILABLE||state.state==WebsiteHealth.State.VERIFY_REQUIRED?0xffedb56a:MUTED);}
    private void refreshHealth(){healthViews.clear();checkedRoots.clear();registerHealth(rows);}
    private void refreshChecks(){if(health==null)return;WebsiteHealth running=health;for(String address:new ArrayList<>(healthViews.keySet())){health.check(address,true,snapshot->{if(foreground&&!destroyed&&health==running){List<TextView> current=healthViews.get(address);if(current!=null)for(TextView view:current)applyHealth(view,snapshot);}});List<TextView> current=healthViews.get(address);if(current!=null)for(TextView view:current)applyHealth(view,health.status(address));}}
    private void refreshPosters(){long epoch=imageGeneration;for(Map.Entry<ImageView,SiteBookmarks.Entry> item:posterEntries.entrySet()){ImageView poster=item.getKey();SiteBookmarks.Entry entry=item.getValue();TextView placeholder=posterPlaceholders.get(poster);images.bind(poster,entry.image,entry.root,null,entry.referrerPolicy,()->foreground&&!destroyed&&imageGeneration==epoch,ok->placeholder.setVisibility(ok?View.GONE:View.VISIBLE));}}
    private void registerHealth(View node){Object tag=node.getTag();if(tag instanceof String&&((String)tag).startsWith("health:"))addHealth(((String)tag).substring(7),(TextView)node);if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++)registerHealth(((ViewGroup)node).getChildAt(i));}
    private void changePage(int delta){page+=delta;render();focusFirst();}
    private void open(SiteBookmarks.Entry entry,boolean film,String key){if(tab==0)filmFocus=key;else siteFocus=key;pausedFocus=key;saveFocus();Intent intent=new Intent(this,SiteActivity.class).putExtra("url",entry.root).putExtra("name",film?sourceName(entry.root):entry.title);if(film)intent.putExtra("bookmarkUrl",entry.url);startActivity(intent);}
    private void confirmRemoval(SiteBookmarks.Entry entry,boolean film,String key){
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("取消收藏？").setMessage("取消“"+entry.title+"”的收藏？").setPositiveButton("取消收藏",(d,which)->{if(film)bookmarks.removeFilm(entry.root,entry.url);else bookmarks.removeSite(entry.url);signature=loadSaved();render();if(!restore(key))focusFirst();saveFocus();Toast.makeText(this,"已取消收藏",Toast.LENGTH_SHORT).show();}).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(v->{android.widget.Button cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);cancel.setFocusableInTouchMode(true);cancel.requestFocus();});dialog.show();
    }
    private void rememberFocus(){View focused=getCurrentFocus();if(focused!=null&&focused.getTag() instanceof String){String key=(String)focused.getTag();if(tab==0&&(key.startsWith("film:")||key.startsWith("remove:film:")||key.equals("favorites:tab:film")))filmFocus=key;else if(tab==1&&(key.startsWith("site:")||key.startsWith("remove:site:")||key.equals("favorites:tab:site")))siteFocus=key;pausedFocus=key;}}
    private void saveFocus(){app.settings.edit().putInt("favoritesTab",tab).putString("favoritesFilmFocus",filmFocus).putString("favoritesSiteFocus",siteFocus).apply();}
    private boolean restore(String key){if(key==null||key.isEmpty())return false;View target=root.findViewWithTag(key);return target!=null&&target.requestFocus();}
    private void focusFirst(){List<SiteBookmarks.Entry> list=entries();if(!list.isEmpty()&&restore(entryKey(tab==0,list.get(page*PAGE_SIZE))))return;(tab==0?filmTab:siteTab).requestFocus();}
    @Override protected void onResume(){super.onResume();foreground=true;health=new WebsiteHealth(this,main);checkedRoots.clear();String next=loadSaved();if(!built||!signature.equals(next)){signature=next;page=pageForFocus(currentFocus());render();built=true;}else{refreshHealth();refreshPosters();}if(!restore(pausedFocus)&&!restore(currentFocus()))focusFirst();pausedFocus="";}
    @Override protected void onPause(){rememberFocus();saveFocus();foreground=false;if(health!=null){health.close();health=null;}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){rememberFocus();state.putInt("tab",tab);state.putString("filmFocus",filmFocus);state.putString("siteFocus",siteFocus);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;foreground=false;imageGeneration++;if(health!=null)health.close();if(images!=null)images.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
    private int dp(int value){android.util.DisplayMetrics m=getResources().getDisplayMetrics();float scale=Math.min(1f,Math.min(m.widthPixels/m.density/960f,m.heightPixels/m.density/540f));return Math.round(value*m.density*scale);}
    private void font(TextView view,int size){view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(size)*Math.min(1.15f,getResources().getConfiguration().fontScale));}
    private TextView text(String label,int size,int color){TextView view=new TextView(this);view.setText(label);view.setTextColor(color);font(view,size);return view;}
    private TextView button(String label,Runnable action){TextView view=text(label,17,Color.WHITE);view.setGravity(Gravity.CENTER);view.setPadding(dp(12),dp(8),dp(12),dp(8));view.setSingleLine(true);view.setFocusable(true);view.setFocusableInTouchMode(true);view.setBackground(background(false));view.setOnFocusChangeListener((v,focus)->v.setBackground(background(focus)));view.setOnClickListener(v->action.run());return view;}
    private GradientDrawable background(boolean focused){GradientDrawable shape=new GradientDrawable();shape.setColor(focused?0xff283e57:CARD);shape.setCornerRadius(dp(10));if(focused)shape.setStroke(Math.max(1,dp(2)),BLUE);return shape;}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private LinearLayout row(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.HORIZONTAL);layout.setGravity(Gravity.CENTER_VERTICAL);return layout;}
}
