package tv.clearstream;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import tv.clearstream.core.*;

/** One query, a small user-owned website scope, and native remote-friendly results. */
public final class FilmSearchActivity extends Activity {
    private static final int BG=0xff101418,CARD=0xff1b222b,BLUE=0xff4c9aff,MUTED=0xffa9b7c7,PAGE_SIZE=12;
    private static final String SCOPE="filmSearchScope";
    private ClearApp app;private FrameLayout host;private LinearLayout rows;private EditText query;
    private TextView searchButton,rangeButton,summary,rangeSummary;private WebsiteSearch worker;private SiteImages images;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<WebsiteSearch.Target> targets=new ArrayList<>();private final Map<String,Group> groups=new LinkedHashMap<>();
    private boolean foreground,destroyed,centerDown,hasSearch;private String runningQuery="",pausedFocus="";private long generation;
    private static final class Group {
        final WebsiteSearch.Target target;final LinearLayout panel,cards,pager;final TextView status,original;
        List<WebsiteSearch.Item> items=Collections.emptyList();String signature="",searchUrl="";boolean done;int page;long imageGeneration;
        Group(WebsiteSearch.Target target,LinearLayout panel,LinearLayout cards,LinearLayout pager,TextView status,TextView original){this.target=target;this.panel=panel;this.cards=cards;this.pager=pager;this.status=status;this.original=original;}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);app=(ClearApp)getApplication();images=new SiteImages(app,main);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        host=new FrameLayout(this);host.setBackgroundColor(BG);setContentView(host);
        LinearLayout layout=column();layout.setPadding(dp(28),dp(18),dp(28),dp(16));layout.setBackgroundColor(BG);host.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=row();heading.addView(button("返回",this::finish),new LinearLayout.LayoutParams(dp(90),dp(46)));
        TextView title=text("一键搜片",28,Color.WHITE);title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(18),0,0,0);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        rangeButton=button("搜索范围",this::chooseRange);heading.addView(rangeButton,new LinearLayout.LayoutParams(dp(130),dp(46)));
        TextView favorites=button("我的收藏",()->startActivity(new Intent(this,FavoritesActivity.class).putExtra("tab","films")));LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(130),dp(46));fp.leftMargin=dp(10);heading.addView(favorites,fp);layout.addView(heading);
        TextView intro=text("动漫 / 电影 / 电视剧，输入名字就能找",18,MUTED);intro.setPadding(dp(2),dp(12),0,dp(10));layout.addView(intro);
        LinearLayout find=row();query=new EditText(this);query.setId(View.generateViewId());query.setContentDescription("搜索片名");query.setSingleLine(true);query.setHint("输入片名，例如：海贼王");query.setHintTextColor(MUTED);query.setTextColor(Color.WHITE);font(query,22);query.setPadding(dp(12),0,dp(12),0);query.setShowSoftInputOnFocus(false);query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);query.setOnClickListener(v->keyboard());
        query.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_DPAD_CENTER)return false;if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)centerDown=true;if(event.getAction()==KeyEvent.ACTION_UP){boolean show=centerDown&&!event.isCanceled();centerDown=false;if(show)keyboard();}return true;});
        query.setOnEditorActionListener((v,action,event)->{boolean enter=event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER;if(action==EditorInfo.IME_ACTION_SEARCH||enter){if(event==null||event.getAction()==KeyEvent.ACTION_UP)search();return true;}return false;});
        find.addView(query,new LinearLayout.LayoutParams(0,dp(54),1));searchButton=button("一键搜索",this::search);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(140),dp(54));bp.leftMargin=dp(12);find.addView(searchButton,bp);layout.addView(find);
        rangeSummary=text("",14,MUTED);rangeSummary.setPadding(dp(3),dp(10),0,dp(6));rangeSummary.setMaxLines(2);layout.addView(rangeSummary);
        summary=text("输入片名后按“一键搜索”",15,MUTED);summary.setPadding(dp(3),dp(3),0,dp(10));layout.addView(summary);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);rows=column();scroll.addView(rows);layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView help=text("方向键选片 · 确定看预览 · 预览页可播放或收藏",13,MUTED);help.setGravity(Gravity.CENTER);help.setPadding(0,dp(8),0,0);layout.addView(help);
        query.setText(state==null?app.settings.getString("filmSearchLastQuery",""):state.getString("query",""));reloadTargets();query.requestFocus();
    }
    private List<String> savedUrls(){List<String> urls=new ArrayList<>();for(Website site:app.store.websites())urls.add(site.url);return urls;}
    private List<String> availableUrls(){return FilmSearchScope.available(savedUrls());}
    private List<String> chosenUrls(){
        if(!app.settings.contains(SCOPE))return FilmSearchScope.defaults(savedUrls());
        List<String> chosen=new ArrayList<>();try{JSONArray data=new JSONArray(app.settings.getString(SCOPE,"[]"));for(int i=0;i<Math.min(data.length(),100);i++)chosen.add(data.optString(i));}catch(JSONException ignored){}
        return FilmSearchScope.selected(availableUrls(),chosen);
    }
    private String nameOf(String url){for(Website site:app.store.websites())if(site.url.equals(url))return site.name;return WebsitePolicy.nameOf(url);}
    private void reloadTargets(){targets.clear();StringBuilder names=new StringBuilder();for(String url:chosenUrls()){targets.add(new WebsiteSearch.Target(nameOf(url),url));if(names.length()>0)names.append("、");names.append(nameOf(url));}rangeSummary.setText(targets.isEmpty()?"尚未选择网站，点右上角“搜索范围”添加":"搜索 "+targets.size()+" 个网站："+names);}
    private void saveScope(List<String> urls){app.settings.edit().putString(SCOPE,new JSONArray(FilmSearchScope.selected(availableUrls(),urls)).toString()).apply();cancelWorker();hasSearch=false;groups.clear();rows.removeAllViews();reloadTargets();summary.setText("搜索范围已保存，输入片名即可搜索");rangeButton.requestFocus();}
    private void chooseRange(){
        List<String> urls=availableUrls(),chosen=chosenUrls();String[] labels=new String[urls.size()];boolean[] checked=new boolean[urls.size()];
        for(int i=0;i<urls.size();i++){labels[i]=nameOf(urls.get(i))+"  ·  "+android.net.Uri.parse(urls.get(i)).getHost();checked[i]=chosen.contains(urls.get(i));}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("选择搜索网站").setMultiChoiceItems(labels,checked,(d,which,value)->{checked[which]=value;int count=0;for(boolean on:checked)if(on)count++;if(count>6){checked[which]=false;((AlertDialog)d).getListView().setItemChecked(which,false);Toast.makeText(this,"最多同时搜索6个网站，建议选择2–3个",Toast.LENGTH_LONG).show();}})
                .setPositiveButton("保存范围",(d,w)->saveScope(selected(urls,checked))).setNeutralButton("添加网站",(d,w)->addWebsite(selected(urls,checked))).setNegativeButton("取消",null).create();
        dialog.show();dialog.getListView().setContentDescription("搜索网站选择，可多选，最多6个");
    }
    private static List<String> selected(List<String> urls,boolean[] checked){List<String> result=new ArrayList<>();for(int i=0;i<urls.size();i++)if(checked[i])result.add(urls.get(i));return result;}
    private void addWebsite(List<String> draft){
        LinearLayout form=column();form.setPadding(dp(24),dp(12),dp(24),0);TextView hint=text("粘贴具体影视网站网址，添加后会加入搜索范围",16,MUTED);form.addView(hint);
        EditText input=new EditText(this);input.setContentDescription("网站网址");input.setSingleLine(true);input.setTextColor(Color.WHITE);font(input,19);input.setHint("https://example.com");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);form.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加到搜索范围").setView(form).setPositiveButton("添加并选中",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{
            String url=WebsitePolicy.normalize(input.getText().toString());if(url==null){input.setError("请输入完整有效的网站网址");return;}if(FilmSearchScope.available(Collections.singletonList(url)).isEmpty()){input.setError("请添加具体影视站，导航和直播入口不能直接搜片");return;}
            List<String> chosen=new ArrayList<>(draft);String existing=null;for(String old:availableUrls())if(sameWebsite(old,url)){existing=old;break;}String target=existing==null?url:existing;
            if(!chosen.contains(target)&&chosen.size()>=6){input.setError("已选6个网站，请先取消其中一个");return;}
            boolean saved=false;for(Website site:app.store.websites())if(site.url.equals(target))saved=true;
            if(!saved&&app.store.websites().size()>=100){input.setError("网站入口已达100个，请先移除一些");return;}
            if(!saved)app.store.addWebsite(target);if(!chosen.contains(target))chosen.add(target);saveScope(chosen);dialog.dismiss();summary.setText("已添加并选入搜索范围");Toast.makeText(this,"已添加并选入搜索范围",Toast.LENGTH_SHORT).show();
        }));dialog.show();input.requestFocus();
    }
    private boolean sameWebsite(String a,String b){android.net.Uri x=android.net.Uri.parse(a),y=android.net.Uri.parse(b);return x.getPort()==y.getPort()&&x.getHost().replaceFirst("(?i)^www\\.","").equalsIgnoreCase(y.getHost().replaceFirst("(?i)^www\\.",""));}
    private void search(){
        String word=query.getText().toString().trim();if(word.isEmpty()){query.setError("请输入片名");query.requestFocus();return;}if(word.length()>200){query.setError("片名请控制在200字以内");return;}
        hideKeyboard();cancelWorker();reloadTargets();if(targets.isEmpty()){summary.setText("请先选择搜索网站");rangeButton.requestFocus();return;}
        runningQuery=word;app.settings.edit().putString("filmSearchLastQuery",word).apply();hasSearch=true;groups.clear();rows.removeAllViews();for(WebsiteSearch.Target target:targets)addGroup(target);summary.setText("正在搜索，先找到的先显示…");searchButton.requestFocus();startRemaining();
    }
    private void addGroup(WebsiteSearch.Target target){
        LinearLayout panel=column();panel.setPadding(dp(2),dp(4),dp(2),dp(14));LinearLayout header=row();TextView name=text(target.name,21,Color.WHITE);name.setTypeface(null,Typeface.BOLD);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView original=button("打开原网站",()->openOriginal(target.url));header.addView(original,new LinearLayout.LayoutParams(dp(160),dp(42)));panel.addView(header);
        TextView status=text("等待搜索…",14,MUTED);status.setPadding(dp(2),dp(7),0,dp(8));panel.addView(status);LinearLayout cards=column(),pager=row();panel.addView(cards);panel.addView(pager);rows.addView(panel);groups.put(target.url,new Group(target,panel,cards,pager,status,original));
    }
    private void startRemaining(){
        if(!foreground||destroyed||!hasSearch||worker!=null)return;List<WebsiteSearch.Target> pending=new ArrayList<>();for(Group group:groups.values())if(!group.done)pending.add(group.target);if(pending.isEmpty()){updateSummary();return;}
        long epoch=++generation;worker=new WebsiteSearch(this,host,result->{if(!foreground||destroyed||epoch!=generation)return;Group group=groups.get(result.site.url);if(group==null)return;group.status.setText(result.status);group.done=result.done;group.searchUrl=result.searchUrl==null?"":result.searchUrl;group.original.setText(group.searchUrl.isEmpty()?"打开原网站":"原网站搜索");group.original.setOnClickListener(v->openOriginal(group.searchUrl.isEmpty()?group.target.url:group.searchUrl));
            StringBuilder signature=new StringBuilder();for(WebsiteSearch.Item item:result.items)signature.append(item.url).append('\n').append(item.title).append('\n').append(item.image).append('\n').append(item.summary).append('\n').append(item.referrerPolicy).append('\n');
            if((!result.items.isEmpty()||result.done||group.items.isEmpty())&&!group.signature.equals(signature.toString())){group.signature=signature.toString();group.items=result.items;renderGroup(group);}updateSummary();
        });worker.start(pending,runningQuery);
    }
    private void updateSummary(){int count=0,done=0;for(Group group:groups.values()){count+=group.items.size();if(group.done)done++;}summary.setText(done==groups.size()?"搜索完成 · "+count+" 项内容 · "+done+" 个网站":"已找到 "+count+" 项 · 正在搜索 "+done+" / "+groups.size()+" 个网站");}
    private void renderGroup(Group group){
        View focused=getCurrentFocus();String restore=focused!=null&&focused.getTag() instanceof String?(String)focused.getTag():"";long imageEpoch=++group.imageGeneration;group.cards.removeAllViews();group.pager.removeAllViews();group.page=Math.min(group.page,Math.max(0,(group.items.size()-1)/PAGE_SIZE));int start=group.page*PAGE_SIZE,end=Math.min(group.items.size(),start+PAGE_SIZE);
        for(int i=start;i<end;i+=3){LinearLayout line=row();for(int c=0;c<3;c++){int index=i+c;LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(225),1);cp.setMargins(dp(4),dp(4),dp(4),dp(4));if(index>=end){line.addView(new View(this),cp);continue;}
            WebsiteSearch.Item item=group.items.get(index);LinearLayout card=column();card.setPadding(dp(10),dp(10),dp(10),dp(8));card.setFocusable(true);card.setFocusableInTouchMode(true);card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);card.setTag("film:"+item.root+"\n"+item.url);card.setContentDescription("搜索影片："+item.title);card.setBackground(background(false));card.setOnFocusChangeListener((v,on)->v.setBackground(background(on)));card.setOnClickListener(v->openItem(item));
            FrameLayout cover=new FrameLayout(this);TextView placeholder=text("暂无封面",14,MUTED);placeholder.setGravity(Gravity.CENTER);cover.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);cover.addView(image,new FrameLayout.LayoutParams(-1,-1));card.addView(cover,new LinearLayout.LayoutParams(-1,0,1));TextView title=text(item.title,20,Color.WHITE);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(0,dp(8),0,0);card.addView(title,new LinearLayout.LayoutParams(-1,dp(52)));line.addView(card,cp);
            images.bind(image,item.image,group.searchUrl.isEmpty()?item.root:group.searchUrl,null,item.referrerPolicy,()->foreground&&!destroyed&&imageEpoch==group.imageGeneration,ok->placeholder.setVisibility(ok?View.GONE:View.VISIBLE));if(card.getTag().equals(restore))card.requestFocus();
        }group.cards.addView(line);}
        int pages=(group.items.size()+PAGE_SIZE-1)/PAGE_SIZE;if(pages>1){TextView prev=button("上一页",()->{group.page--;renderGroup(group);focusFirst(group);});prev.setEnabled(group.page>0);group.pager.addView(prev,new LinearLayout.LayoutParams(dp(120),dp(42)));TextView label=text((group.page+1)+" / "+pages,16,MUTED);label.setGravity(Gravity.CENTER);group.pager.addView(label,new LinearLayout.LayoutParams(dp(90),dp(42)));TextView next=button("下一页",()->{group.page++;renderGroup(group);focusFirst(group);});next.setEnabled(group.page<pages-1);group.pager.addView(next,new LinearLayout.LayoutParams(dp(120),dp(42)));}
    }
    private void openItem(WebsiteSearch.Item item){startActivity(new Intent(this,SiteActivity.class).putExtra("url",item.root).putExtra("name",item.name).putExtra("searchPreview",true).putExtra("filmUrl",item.url).putExtra("filmTitle",item.title).putExtra("filmImage",item.image).putExtra("filmSummary",item.summary).putExtra("filmPolicy",item.referrerPolicy));}
    private void openOriginal(String url){hideKeyboard();startActivity(WebsitePlayback.intent(this,url,WebsitePolicy.nameOf(url)));}
    private boolean focusFirst(Group group){if(group.cards.getChildCount()>0){ViewGroup line=(ViewGroup)group.cards.getChildAt(0);return line.getChildCount()>0&&line.getChildAt(0).requestFocus();}return false;}
    private void focusResults(){for(Group group:groups.values())if(focusFirst(group))return;for(Group group:groups.values()){group.original.requestFocus();return;}rangeButton.requestFocus();}
    private void keyboard(){query.setShowSoftInputOnFocus(true);query.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);}
    private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);query.setShowSoftInputOnFocus(false);}
    private void cancelWorker(){generation++;if(worker!=null){worker.close();worker=null;}}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN){View focused=getCurrentFocus();if(focused==query&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN){hideKeyboard();searchButton.requestFocus();return true;}if(focused==query&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP){rangeButton.requestFocus();return true;}if(focused==searchButton&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN){focusResults();return true;}}return super.dispatchKeyEvent(event);}
    @Override protected void onResume(){super.onResume();foreground=true;if(hasSearch){startRemaining();if(!pausedFocus.isEmpty())main.post(()->{View old=rows.findViewWithTag(pausedFocus);if(old!=null)old.requestFocus();});}}
    @Override protected void onPause(){View focused=getCurrentFocus();pausedFocus=focused!=null&&focused.getTag() instanceof String?(String)focused.getTag():"";foreground=false;centerDown=false;cancelWorker();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("query",query.getText().toString());super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;cancelWorker();main.removeCallbacksAndMessages(null);if(images!=null)images.close();super.onDestroy();}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private float scale(){android.util.DisplayMetrics m=getResources().getDisplayMetrics();return Math.min(1f,Math.min(m.widthPixels/m.density/960f,m.heightPixels/m.density/540f));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density*scale());}
    private void font(TextView view,int size){view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(size)*Math.min(1.15f,getResources().getConfiguration().fontScale));}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextColor(color);font(view,size);return view;}
    private TextView button(String title,Runnable action){TextView view=text(title,17,Color.WHITE);view.setId(View.generateViewId());view.setGravity(Gravity.CENTER);view.setSingleLine(true);view.setFocusable(true);view.setFocusableInTouchMode(true);view.setPadding(dp(10),dp(6),dp(10),dp(6));view.setBackground(background(false));view.setOnFocusChangeListener((v,on)->v.setBackground(background(on)));view.setOnClickListener(v->action.run());return view;}
    private GradientDrawable background(boolean focused){GradientDrawable background=new GradientDrawable();background.setColor(focused?0xff283e57:CARD);background.setCornerRadius(dp(10));if(focused)background.setStroke(Math.max(1,dp(2)),BLUE);return background;}
}
