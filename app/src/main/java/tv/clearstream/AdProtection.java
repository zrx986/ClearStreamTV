package tv.clearstream;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import tv.clearstream.core.AdRequestPolicy;
/** Per-site, selectable built-in protection, separate from developer JS plugins. */
final class AdProtection {
    private final Activity owner;private final SharedPreferences prefs;private final String key;
    private final JSONObject rules;private final List<String> domains=new ArrayList<>();
    volatile boolean network,cosmetic,manual,plugins;final AtomicInteger blocked=new AtomicInteger();
    AdProtection(Activity owner,String site){
        this.owner=owner;prefs=((ClearApp)owner.getApplication()).settings;
        String host=Uri.parse(site).getHost();key="adProtection:"+(host==null?"":host.toLowerCase(Locale.ROOT));
        JSONObject data;try(InputStream in=owner.getAssets().open("adblock-rules.json")){data=new JSONObject(new String(NetworkEngine.readBounded(in,128*1024,true),StandardCharsets.UTF_8));}catch(IOException|JSONException error){data=new JSONObject();}
        rules=data;JSONArray list=rules.optJSONArray("domains");if(list!=null)for(int i=0;i<list.length();i++)domains.add(list.optString(i));refresh();
    }
    private void refresh(){network=prefs.getBoolean(key+":network",true);cosmetic=prefs.getBoolean(key+":cosmetic",true);manual=prefs.getBoolean(key+":manual",true);plugins=prefs.getBoolean(key+":plugins",true);}
    boolean shouldBlock(String url,boolean main){if(network&&AdRequestPolicy.shouldBlock(url,main,domains)){blocked.incrementAndGet();return true;}return false;}
    private JSONArray custom(){try{return new JSONArray(prefs.getString(key+":custom","[]"));}catch(JSONException ignored){return new JSONArray();}}
    JSONObject config(){JSONObject out=new JSONObject();try{JSONArray selectors=new JSONArray();JSONArray common=rules.optJSONArray("selectors");if(cosmetic&&common!=null)for(int i=0;i<common.length();i++)selectors.put(common.optString(i));JSONArray mine=custom();if(manual)for(int i=0;i<mine.length();i++)selectors.put(mine.optString(i));out.put("network",network).put("cosmetic",selectors.length()>0).put("plugins",plugins).put("domains",rules.optJSONArray("domains")).put("selectors",selectors);}catch(JSONException ignored){}return out;}
    void saveSelector(String selector){if(selector==null||selector.length()>512||selector.contains("{")||selector.contains("}"))return;JSONArray list=custom();for(int i=0;i<list.length();i++)if(selector.equals(list.optString(i)))return;if(list.length()>=100)return;list.put(selector);prefs.edit().putString(key+":custom",list.toString()).putBoolean(key+":manual",true).apply();refresh();}
    void confirmPick(JSONObject result,Runnable apply){String selector=result.optString("selector"),label=result.optString("label","选中的区域");AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("屏蔽这个区域？").setMessage("选中："+label+"\n\n保存后，之后打开本站会自动隐藏。误选可在广告防护中清除本站规则。").setPositiveButton("保存并屏蔽",(d,w)->{saveSelector(selector);apply.run();}).setNegativeButton("取消",null).create();dialog.setOnShowListener(v->{dialog.getButton(-2).setFocusableInTouchMode(true);dialog.getButton(-2).requestFocus();});dialog.show();}
    void choose(Runnable apply,Runnable pick){new AlertDialog.Builder(owner).setTitle("广告防护").setItems(new String[]{"点选网页广告并隐藏","选择防护开关","清除本站点选规则（"+custom().length()+" 条）"},(d,i)->{if(i==0)pick.run();if(i==1)options(apply);if(i==2)new AlertDialog.Builder(owner).setTitle("清除本站点选规则？").setMessage("之后会恢复显示你手动屏蔽的区域。").setPositiveButton("清除并刷新",(x,w)->{prefs.edit().remove(key+":custom").apply();apply.run();}).setNegativeButton("保留",null).show();}).setNegativeButton("返回",null).show();}
    private void options(Runnable apply){
        boolean[] checked={network,cosmetic,manual,plugins};
        new AlertDialog.Builder(owner).setTitle("选择本站的广告防护")
            .setMultiChoiceItems(new String[]{"基础广告请求拦截","隐藏常见网页广告位","我的点选屏蔽规则","开发者站点增强插件"},checked,(d,i,value)->checked[i]=value)
            .setPositiveButton("应用并刷新",(d,w)->{prefs.edit().putBoolean(key+":network",checked[0]).putBoolean(key+":cosmetic",checked[1]).putBoolean(key+":manual",checked[2]).putBoolean(key+":plugins",checked[3]).apply();refresh();apply.run();})
            .setNeutralButton("本站全部关闭",(d,w)->{prefs.edit().putBoolean(key+":network",false).putBoolean(key+":cosmetic",false).putBoolean(key+":manual",false).putBoolean(key+":plugins",false).apply();refresh();apply.run();})
            .setNegativeButton("取消",null).show();
    }
}
