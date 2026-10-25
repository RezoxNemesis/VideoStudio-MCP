package com.rezoxnemesis.videostudio;

/**
 * A deterministic, independently controllable clock for viewing already
 * accepted native pen operations. Playback speed NEVER changes source drawing,
 * frame timing, project revision, or durable live-session duration.
 *
 * One real stroke is evaluated against monotonic uptime; pausing freezes the
 * prefix and pen tip at the exact last displayed fraction. Rate changes keep
 * fractional progress rather than restarting the stroke or jumping ahead.
 */
public final class SketchLivePlaybackClock {
    public static final float DEFAULT_RATE=1f;
    public static final float[] PRESETS={.1f,.25f,.5f,1f,2f,4f,8f};
    private double accumulatedMs;
    private float rate=DEFAULT_RATE;
    private long anchorMs;
    private boolean paused;

    public SketchLivePlaybackClock(long uptimeMs){
        if(uptimeMs<0)throw new IllegalArgumentException("Monotonic time cannot be negative");
        anchorMs=uptimeMs;
    }

    public static boolean supported(float requested) {
        if(!Float.isFinite(requested))return false;
        for(float preset:PRESETS)if(preset==requested)return true;
        return false;
    }

    public void setRate(float requested,long uptimeMs){
        if(!supported(requested))throw new IllegalArgumentException("Choose a supported replay speed from 0.1x to 8x");
        advance(uptimeMs);rate=requested;
    }

    public void resetOperation(long uptimeMs){
        requireTime(uptimeMs);
        accumulatedMs=0;
        anchorMs=uptimeMs;
    }

    public void pause(long uptimeMs){
        advance(uptimeMs);paused=true;
    }

    public void resume(long uptimeMs){
        requireTime(uptimeMs);
        if(!paused)return;
        paused=false;anchorMs=uptimeMs;
    }

    public boolean paused(){return paused;}
    public float rate(){return rate;}

    public double elapsed(long uptimeMs){
        requireTime(uptimeMs);
        return accumulatedMs+(paused?0:Math.max(0,uptimeMs-anchorMs)*(double)rate);
    }

    public float fraction(long uptimeMs,int durationMs){
        if(durationMs<40||durationMs>15000)
            throw new IllegalArgumentException("Native accepted stroke duration must be 40..15000 ms");
        return (float)Math.max(0,Math.min(1,elapsed(uptimeMs)/durationMs));
    }

    private void advance(long uptimeMs){
        requireTime(uptimeMs);
        if(!paused)accumulatedMs+=Math.max(0,uptimeMs-anchorMs)*(double)rate;
        anchorMs=uptimeMs;
    }

    private static void requireTime(long uptimeMs){
        if(uptimeMs<0)throw new IllegalArgumentException("Monotonic time cannot be negative");
    }
}
