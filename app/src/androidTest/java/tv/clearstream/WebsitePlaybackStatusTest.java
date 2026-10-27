package tv.clearstream;
import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.UUID;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WebsitePlaybackStatusTest {
    private Context isolated;
    @Before public void setup(){Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();String prefix="play-status-"+UUID.randomUUID();isolated=new ContextWrapper(base){@Override public Context getApplicationContext(){return this;}@Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+name,mode);}};}
    @Test public void playbackFailureSurvivesReopeningAndAConnectionCheckCannotEraseIt(){
        String root="https://example.org/",page=root+"play/one";
        WebsiteHealth.recordPlaybackIssue(isolated,root,page,WebsiteHealth.PlaybackIssue.MEDIA_ERROR);
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){WebsiteHealth.Snapshot s=health.status(root);assertTrue(s.playbackIssueTimestamp>0);assertTrue(s.label.contains("播放未成功"));assertTrue(s.reason.contains("视频线路报错"));assertEquals(0,s.playbackTimestamp);}
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){assertTrue(health.status(root).playbackIssueTimestamp>0);}
    }
    @Test public void actualPlaybackClearsLatestFailureButKeepsEvidence(){
        String root="https://example.org/",page=root+"play/one";
        WebsiteHealth.recordPlaybackIssue(isolated,root,page,WebsiteHealth.PlaybackIssue.START_TIMEOUT);
        WebsiteHealth.recordPlayback(isolated,root,page,864,486,8);
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){WebsiteHealth.Snapshot s=health.status(root);assertEquals(0,s.playbackIssueTimestamp);assertTrue(s.playbackTimestamp>0);assertEquals(864,s.playbackWidth);}
    }
    @Test public void failedLaterAttemptIsVisibleEvenWhenTheSitePlayedAnEarlierFilm(){
        String root="https://example.org/",page=root+"play/one";
        WebsiteHealth.recordPlayback(isolated,root,page,864,486,8);
        WebsiteHealth.recordPlaybackIssue(isolated,root,root+"play/two",WebsiteHealth.PlaybackIssue.START_TIMEOUT);
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){WebsiteHealth.Snapshot s=health.status(root);assertTrue(s.playbackTimestamp>0);assertTrue(s.playbackIssueTimestamp>0);assertTrue(s.label.contains("播放未成功"));assertTrue(s.reason.contains("等待超时"));}
    }
    @Test public void invalidAddressesNeverCreateStatusAndNoVideoExplainsHowToContinue(){
        WebsiteHealth.recordPlaybackIssue(isolated,"javascript:bad","https://example.org/",WebsiteHealth.PlaybackIssue.MEDIA_ERROR);
        String root="https://example.org/";WebsiteHealth.recordPlaybackIssue(isolated,root,root+"detail/one",WebsiteHealth.PlaybackIssue.NO_VIDEO);
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){assertEquals(0,health.status("javascript:bad").playbackIssueTimestamp);assertTrue(health.status(root).reason.contains("选择剧集"));}
    }
    @Test public void foregroundTimeoutIsAutomaticAndLeavingBeforeDeadlineDoesNotMarkFailure()throws Exception{
        String root="https://example.org/",page=root+"play/one";
        WebsitePlaybackMonitor monitor=new WebsitePlaybackMonitor(isolated,root,page,new Handler(Looper.getMainLooper()),120);
        monitor.resume();monitor.request(page);SystemClock.sleep(400);
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){assertTrue(health.status(root).playbackIssueTimestamp>0);assertTrue(health.status(root).reason.contains("等待超时"));}
        monitor.pause();WebsiteHealth.recordPlayback(isolated,root,page,864,486,8);
        monitor.resume();monitor.request(page);monitor.pause();SystemClock.sleep(400);
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){assertEquals(0,health.status(root).playbackIssueTimestamp);}
    }
    @Test public void observedMediaErrorIsMarkedWithoutAnHttpFailure(){
        String root="https://example.org/",page=root+"play/one";
        WebsitePlaybackMonitor monitor=new WebsitePlaybackMonitor(isolated,root,page,new Handler(Looper.getMainLooper()),30000);
        monitor.resume();monitor.sample(page,true,0,0,0,true,0,4);monitor.pause();
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){assertTrue(health.status(root).reason.contains("视频线路报错"));}
    }
    @Test public void refreshingTheSameAddressCancelsThePreviousDocumentsDeadline(){
        String root="https://example.org/",page=root+"play/one";WebsitePlaybackMonitor monitor=new WebsitePlaybackMonitor(isolated,root,page,new Handler(Looper.getMainLooper()),120);
        monitor.resume();monitor.request(page);monitor.newDocument(page);SystemClock.sleep(400);monitor.pause();
        try(WebsiteHealth health=new WebsiteHealth(isolated,new Handler(Looper.getMainLooper()))){assertEquals(0,health.status(root).playbackIssueTimestamp);}
    }
}
