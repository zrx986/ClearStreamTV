package tv.clearstream;

import android.os.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.*;
import androidx.media3.session.*;
import java.util.Locale;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
    private MediaSession session;
    private ExoPlayer player;
    private ClearApp app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private int reconnects;
    private long recoveryStart;
    private final Runnable networkChanged=()-> {
        if (player!=null&&player.getMediaItemCount()>0&&player.getPlayWhenReady()) {
            if (app.network.active()!=null) {
                long now=SystemClock.elapsedRealtime();
                if (now-recoveryStart>30000) { recoveryStart=now; reconnects=0; }
                if (reconnects++<3) main.postDelayed(()-> {
                    if (player!=null&&player.getMediaItemCount()>0&&player.getPlayWhenReady()) {
                        MediaItem current=player.getCurrentMediaItem();
                        if(current!=null) {
                            long position=player.isCurrentMediaItemLive()?C.TIME_UNSET:player.getCurrentPosition();
                            // prepare() alone is a no-op while READY/BUFFERING. Recreate the source
                            // so new loaders obtain a client bound to the new Android Network.
                            player.setMediaItem(current,position);player.prepare();player.play();
                        }
                    }
                },500);
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate(); app=(ClearApp)getApplication();
        DataSource.Factory http=()->new OkHttpDataSource.Factory(app.network.client("media")).setUserAgent("ClearStreamTV/0.1").createDataSource();
        DefaultDataSource.Factory data=new DefaultDataSource.Factory(this,http);
        int profile=app.settings.getInt("buffer",1);
        int min=profile==0?1000:profile==2?8000:3000;
        int max=profile==0?4000:profile==2?15000:8000;
        DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(min,max,Math.min(1000,min),Math.min(2000,min))
            .setTargetBufferBytes((BuildConfig.LOW_MEMORY?12:32)*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build();
        player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(data))
            .setLoadControl(load).setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            .setHandleAudioBecomingNoisy(true).build();
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onRenderedFirstFrame(EventTime eventTime,Object output,long renderTimeMs) {
                app.renderedFirstFrame=SystemClock.elapsedRealtime(); app.videoWidth=player.getVideoSize().width;
                MediaItem item=player.getCurrentMediaItem();
                if(item!=null) {
                    if(item.localConfiguration!=null)app.settings.edit().putString("lastWatchedUrl",item.localConfiguration.uri.toString()).apply();
                    String id=item.mediaId,family=app.network.mediaFamily,url=item.localConfiguration==null?null:item.localConfiguration.uri.toString();if(url!=null)app.network.io.execute(()->app.store.played(id,url,family));
                }
            }
            @Override public void onBandwidthEstimate(EventTime eventTime,int totalLoadTimeMs,long totalBytesLoaded,long bitrateEstimate) {
                if(totalLoadTimeMs>0) app.network.mediaSpeed=String.format(Locale.ROOT,"%.1f Mbps",totalBytesLoaded*8d/totalLoadTimeMs/1000d);
            }
        });
        session=new MediaSession.Builder(this,player).build(); app.network.observe(networkChanged);
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }
    @Override public void onTaskRemoved(android.content.Intent rootIntent) {
        if (!app.settings.getBoolean("background",false) || !player.getPlayWhenReady()) { player.stop(); stopSelf(); }
    }
    @Override public void onDestroy() {
        app.network.removeObserver(networkChanged); main.removeCallbacksAndMessages(null);
        if (session!=null) session.release(); if (player!=null) player.release(); super.onDestroy();
    }
}
