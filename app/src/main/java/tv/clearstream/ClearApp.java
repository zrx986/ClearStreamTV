package tv.clearstream;
import android.app.Application;
import android.content.SharedPreferences;
public final class ClearApp extends Application {
    public SourceStore store;
    public NetworkEngine network;
    public SharedPreferences settings;
    public volatile long renderedFirstFrame;
    public volatile int videoWidth;
    @Override public void onCreate() {
        super.onCreate(); settings=getSharedPreferences("settings", MODE_PRIVATE);
        store=new SourceStore(this); network=new NetworkEngine(this, settings);
    }
}
