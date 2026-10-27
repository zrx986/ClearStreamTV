package tv.clearstream;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Remembers explicit link selections; rereads the root page on every update. */
public final class SourceSubscriptions {
    private static final String PREFIX="sourceRoute:";
    public static final class Session {
        public final String root;
        private final Set<String> visited=new HashSet<>();
        private final List<SubscriptionImporter.Option> chosen=new ArrayList<>(),saved=new ArrayList<>();
        private boolean bound;
        public Session(String root,SharedPreferences prefs,boolean forget) {
            this.root=root;bound=!forget&&root!=null&&prefs.contains(PREFIX+root);
            if(bound)try {
                JSONArray route=new JSONArray(prefs.getString(PREFIX+root,"[]"));
                for(int i=0;i<Math.min(route.length(),3);i++){JSONObject item=route.getJSONObject(i);saved.add(new SubscriptionImporter.Option(item.getString("name"),item.getString("url")));}
            }catch(JSONException e){bound=false;saved.clear();}
        }
        public void choose(SubscriptionImporter.Option option){chosen.add(option);}
        public void chooseManually(SubscriptionImporter.Option option){saved.clear();bound=false;choose(option);}
        public SubscriptionImporter.Option remembered(List<SubscriptionImporter.Option> options) {
            if(chosen.size()>=saved.size())return null;
            SubscriptionImporter.Option old=saved.get(chosen.size()),match=null;
            for(SubscriptionImporter.Option option:options)if(old.url.equals(option.url))return option;
            for(SubscriptionImporter.Option option:options)if(old.name.equals(option.name)){if(match!=null)return null;match=option;}
            return match;
        }
        public boolean inlineSelected(){return bound&&chosen.size()>=saved.size();}
        public void save(SharedPreferences prefs) {
            if(root==null)return;
            JSONArray route=new JSONArray();
            try{for(SubscriptionImporter.Option option:chosen){JSONObject item=new JSONObject();item.put("name",option.name);item.put("url",option.url);route.put(item);}}
            catch(JSONException e){throw new IllegalStateException(e);}
            Set<String> subs=new HashSet<>(prefs.getStringSet("subscriptions",Collections.emptySet()));subs.add(root);
            prefs.edit().putStringSet("subscriptions",subs).putString(PREFIX+root,route.toString()).apply();
        }
        private void enter(String url) throws IOException {
            if(visited.size()>=4||!visited.add(url))throw new IOException("订阅入口循环或超过 4 层，请直接提供直播表地址");
        }
    }
    public static final class Loaded {
        public final SubscriptionImporter.Result result;
        public final Session session;
        public final boolean inline;
        Loaded(SubscriptionImporter.Result result,Session session,boolean inline){this.result=result;this.session=session;this.inline=inline;}
    }
    public static Loaded load(NetworkEngine network,String url,Session session) throws IOException {
        for(;;) {
            session.enter(url);
            NetworkEngine.TextResponse document=network.fetchDocument(url);
            SubscriptionImporter.Result result=SubscriptionImporter.parse(document.text,document.url);
            if(result.options.isEmpty())return new Loaded(result,session,true);
            SubscriptionImporter.Option remembered=session.remembered(result.options);
            if(remembered==null)return new Loaded(result,session,session.inlineSelected()&&!result.playlist.entries.isEmpty());
            session.choose(remembered);url=remembered.url;
        }
    }
}
