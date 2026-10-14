package com.rezoxnemesis.videostudio;

/** Main-thread clip clock. Pauses and Activity stops never count as animation time. */
final class Preview3DPlayback {
    private double seconds,rate=1;
    private long previousNanos;
    private boolean advancing;
    double position(){return seconds;}
    double rate(){return rate;}
    void suspend(){previousNanos=0;advancing=false;}
    void seek(double position,double duration){
        if(!Double.isFinite(position)||position<0)throw new IllegalArgumentException("Clip time must be finite and nonnegative");
        seconds=Math.min(position,validDuration(duration));suspend();
    }
    void rate(double value){
        if(!Double.isFinite(value)||value<.25||value>4)throw new IllegalArgumentException("Playback rate must be between 0.25 and 4");
        rate=value;suspend();
    }
    double frame(long nanos,boolean playing,double duration){
        double limit=validDuration(duration);
        if(limit==0){seconds=0;suspend();return 0;}
        seconds=Math.min(seconds,limit);
        if(playing&&advancing&&previousNanos>0&&nanos>previousNanos){
            seconds=(seconds+(nanos-previousNanos)/1_000_000_000d*rate)%limit;
        }
        previousNanos=nanos;advancing=playing;return seconds;
    }
    private static double validDuration(double value){return Double.isFinite(value)&&value>0?value:0;}
}
