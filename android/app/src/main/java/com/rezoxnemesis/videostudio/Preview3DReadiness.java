package com.rezoxnemesis.videostudio;

/** Main-thread resource/submission gate. Submission is not proof of visible pixels. */
final class Preview3DReadiness {
    private static final long TIMEOUT_MS=30_000;
    private boolean foreground,monitoring,ready,failed;
    private long previousMillis,pendingMillis;

    void start(long now){monitoring=true;ready=false;failed=false;pendingMillis=0;previousMillis=now;}
    void resume(long now){previousMillis=now;foreground=true;}
    void pause(long now){tick(now);foreground=false;ready=false;}
    void fail(){failed=true;ready=false;}
    boolean monitoring(){return monitoring;}
    boolean ready(){return ready;}
    boolean failed(){return failed;}
    void observe(long now,float progress,boolean submitted){
        tick(now);
        if(!monitoring||!foreground||failed)return;
        ready=Float.isFinite(progress)&&progress>=1f&&submitted;
        if(ready)pendingMillis=0;
        else if(pendingMillis>=TIMEOUT_MS)fail();
    }
    private void tick(long now){
        if(foreground&&monitoring&&!ready&&!failed&&now>previousMillis)
            pendingMillis=Math.min(TIMEOUT_MS,pendingMillis+Math.min(TIMEOUT_MS,now-previousMillis));
        previousMillis=now;
    }
}
