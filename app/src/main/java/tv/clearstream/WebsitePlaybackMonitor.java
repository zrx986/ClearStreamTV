package tv.clearstream;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.net.Uri;
import tv.clearstream.core.PlaybackAttempt;
import tv.clearstream.core.PlaybackProof;
import tv.clearstream.core.WebsitePolicy;

/** Foreground automatic outcome recording for one selected website film. */
final class WebsitePlaybackMonitor {
    private final Context context;private final String root,selected;private final Handler handler;private final PlaybackAttempt watch;private final long interval;
    private boolean foreground,armed,observedUnpaused;private String current,lastIssue="";
    private final Runnable tick=new Runnable(){@Override public void run(){synchronized(WebsitePlaybackMonitor.this){if(!foreground||!armed)return;if(watch.timedOut(SystemClock.elapsedRealtime())){armed=false;failed(current,WebsiteHealth.PlaybackIssue.START_TIMEOUT);}else handler.postDelayed(this,interval);}}};
    WebsitePlaybackMonitor(Context context,String root,String selected,Handler handler,long timeout) {this.context=context.getApplicationContext();this.root=root;this.selected=selected;this.current=selected;this.handler=handler;watch=new PlaybackAttempt(timeout);interval=Math.min(750,timeout);}
    synchronized void resume() {foreground=true;}
    synchronized void pause() {foreground=false;cancelAttempt();}
    synchronized void cancelAttempt(){armed=false;observedUnpaused=false;watch.cancel();handler.removeCallbacks(tick);}
    synchronized void navigate(String page) {
        if(!java.util.Objects.equals(current,page)){cancelAttempt();current=page;lastIssue="";}
        if(!foreground||WebsitePolicy.normalize(page)==null||PlaybackProof.canAssociate(root,selected,page))return;
        String host=Uri.parse(page).getHost();if(host==null)return;
        for(String search:new String[]{"baidu.com","bing.com","google.com"})if(host.equals(search)||host.endsWith("."+search)){failed(page,WebsiteHealth.PlaybackIssue.LEFT_SITE);break;}
    }
    synchronized void newDocument(String page){cancelAttempt();current=page;lastIssue="";navigate(page);}
    synchronized void request(String page) {if(!foreground||!PlaybackProof.canAssociate(root,selected,page))return;current=page;lastIssue="";armed=true;watch.start(SystemClock.elapsedRealtime());handler.removeCallbacks(tick);handler.postDelayed(tick,interval);}
    synchronized void noVideo(String page) {if(armed)failed(page,WebsiteHealth.PlaybackIssue.NO_VIDEO);cancelAttempt();}
    synchronized void failed(String page,WebsiteHealth.PlaybackIssue issue) {
        if(!foreground||WebsitePolicy.normalize(page)==null||issue!=WebsiteHealth.PlaybackIssue.LEFT_SITE&&!PlaybackProof.canAssociate(root,selected,page))return;
        String key=page+"|"+issue.name();if(!key.equals(lastIssue)){WebsiteHealth.recordPlaybackIssue(context,root,page,issue);lastIssue=key;}cancelAttempt();
    }
    synchronized void sample(String page,boolean video,int width,int height,double time,boolean paused,int ready,int error) {
        if(!foreground||!PlaybackProof.canAssociate(root,selected,page))return;
        if(video&&error>0){failed(page,WebsiteHealth.PlaybackIssue.MEDIA_ERROR);return;}
        if(video&&paused&&observedUnpaused){cancelAttempt();return;}
        if(video&&!paused&&!armed)request(page);
        if(video&&!paused)observedUnpaused=true;
        if(video&&ready>=2&&width>0&&height>0&&!paused)lastIssue="";
        if(watch.observe(video,width,height,time,paused,ready,error,SystemClock.elapsedRealtime()))failed(page,error>0?WebsiteHealth.PlaybackIssue.MEDIA_ERROR:WebsiteHealth.PlaybackIssue.START_TIMEOUT);
        if(video&&paused&&ready>=2&&width>0&&height>0)cancelAttempt();
    }
}
