package tv.clearstream.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackAttemptTest {
    @Test public void explicitAttemptTimesOutAndEmitsOnlyOnce(){
        PlaybackAttempt watch=new PlaybackAttempt(30000);assertFalse(watch.timedOut(50000));watch.start(50000);
        assertFalse(watch.timedOut(79999));assertTrue(watch.timedOut(80000));assertFalse(watch.timedOut(90000));
    }
    @Test public void mediaErrorFailsEvenBeforeDimensionsExist(){
        PlaybackAttempt watch=new PlaybackAttempt(30000);watch.start(0);
        assertTrue(watch.observe(true,0,0,0,true,0,4,20));assertFalse(watch.timedOut(40000));
    }
    @Test public void advancingPictureExtendsTheDeadlineButStallEventuallyFails(){
        PlaybackAttempt watch=new PlaybackAttempt(30000);watch.start(0);
        assertFalse(watch.observe(true,864,486,1,false,4,0,1000));
        assertFalse(watch.observe(true,864,486,22,false,4,0,22000));
        assertFalse(watch.timedOut(51999));assertTrue(watch.timedOut(52000));
    }
    @Test public void userPauseAndLeavingPageNeverBecomeFailures(){
        PlaybackAttempt watch=new PlaybackAttempt(30000);watch.start(0);
        assertFalse(watch.observe(true,864,486,1,true,4,0,20));assertFalse(watch.timedOut(90000));
        watch.start(90000);watch.cancel();assertFalse(watch.timedOut(150000));
    }
    @Test public void timeSeekDoesNotProveProgressAndANewAttemptCanRecover(){
        PlaybackAttempt watch=new PlaybackAttempt(30000);watch.start(0);
        assertFalse(watch.observe(true,864,486,1,false,4,0,10));
        assertFalse(watch.observe(true,864,486,1000,false,4,0,1000));
        assertTrue(watch.timedOut(30010));watch.start(40000);assertFalse(watch.timedOut(40010));
    }
}
