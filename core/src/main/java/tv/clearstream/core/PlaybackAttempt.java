package tv.clearstream.core;

/** Tracks one explicit foreground play attempt, without treating pause as failure. */
public final class PlaybackAttempt {
    private final long timeout;
    private boolean active;
    private long lastProgress,lastSample=-1;
    private double lastTime;
    public PlaybackAttempt(long timeoutMillis) {if(timeoutMillis<=0)throw new IllegalArgumentException("Positive timeout required");timeout=timeoutMillis;}
    public void start(long now) {active=true;lastProgress=now;lastSample=-1;}
    public void cancel() {active=false;lastSample=-1;}
    public boolean observe(boolean video,int width,int height,double time,boolean paused,int ready,int error,long now){
        if(!active)return false;
        if(error>0){cancel();return true;}
        if(video&&width>0&&height>0&&ready>=2&&paused){cancel();return false;}
        if(video&&width>0&&height>0&&ready>=2&&!paused&&!Double.isNaN(time)&&!Double.isInfinite(time)&&time>=0){
            long elapsed=now-lastSample;double delta=time-lastTime;
            if(lastSample<0||elapsed>=0&&delta>.01&&delta<=Math.max(1,elapsed/1000.0*3+1))lastProgress=now;
            lastSample=now;lastTime=time;
        }
        return timedOut(now);
    }
    public boolean timedOut(long now){if(!active)return false;if(now<lastProgress){cancel();return false;}if(now-lastProgress>=timeout){cancel();return true;}return false;}
}
