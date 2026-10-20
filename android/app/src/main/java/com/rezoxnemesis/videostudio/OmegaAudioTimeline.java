package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OMEGA Phase 7 PCM waveform frame envelope. This is track timing data
 * for an eventual visible Sketch/Editor audio lane, not audible MP4 proof.
 */
final class OmegaAudioTimeline {
    private OmegaAudioTimeline() {}
    static final int MAX_SAMPLES=10_000_000;
    static final class Envelope {
        final int frame,startSample,endSample;
        final double rms,peak;
        Envelope(int frame,int startSample,int endSample,double rms,double peak) {
            this.frame=frame;this.startSample=startSample;this.endSample=endSample;
            this.rms=rms;this.peak=peak;
        }
        boolean silent(double threshold) {
            if(!Double.isFinite(threshold)||threshold<0||threshold>1)
                throw new IllegalArgumentException("Silence threshold must be 0..1");
            return peak<=threshold;
        }
    }
    static List<Envelope> waveform(short[] pcm,int sampleRate,int fps,int frameCount) {
        if(pcm==null||pcm.length>MAX_SAMPLES||sampleRate<8000||sampleRate>192000||
           (fps!=24&&fps!=30&&fps!=60)||frameCount<1||frameCount>1200)
            throw new IllegalArgumentException("Invalid waveform/sample clock");
        List<Envelope> result=new ArrayList<>(frameCount);
        for(int frame=0;frame<frameCount;frame++) {
            int start=(int)Math.min(pcm.length,(long)frame*sampleRate/fps);
            int end=(int)Math.min(pcm.length,(long)(frame+1)*sampleRate/fps);
            double sumSq=0,peak=0;
            for(int i=start;i<end;i++){
                double amp=Math.abs(pcm[i]/32768.0);
                sumSq+=amp*amp;peak=Math.max(peak,amp);
            }
            double rms=end>start?Math.sqrt(sumSq/(end-start)):0;
            result.add(new Envelope(frame,start,end,rms,peak));
        }
        return Collections.unmodifiableList(result);
    }
    static int frameForSample(long sampleIndex,int sampleRate,int fps,int frameCount){
        if(sampleIndex<0||sampleRate<8000||sampleRate>192000||
           (fps!=24&&fps!=30&&fps!=60)||frameCount<1||frameCount>1200)
            throw new IllegalArgumentException("Invalid clock");
        return (int)Math.min(frameCount-1,(sampleIndex*fps)/sampleRate);
    }
}