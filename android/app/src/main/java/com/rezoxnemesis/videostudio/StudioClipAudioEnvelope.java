package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Clip stereo controls and linear fades in output time, after Media3's speed processing. */
public final class StudioClipAudioEnvelope {
    private static final long MAX_DURATION_MS=86_400_000L;
    private final long durationUs,offsetUs,fadeInUs,fadeOutUs;
    private final float volume;
    private final float leftBalance,rightBalance;
    private final boolean mono;

    private StudioClipAudioEnvelope(long durationMs,long offsetMs,long fadeInMs,long fadeOutMs,float volume,float pan,boolean mono){
        durationUs=durationMs*1000;offsetUs=offsetMs*1000;
        fadeInUs=fadeInMs*1000;fadeOutUs=fadeOutMs*1000;this.volume=volume;
        leftBalance=Math.min(1f,1f-pan);rightBalance=Math.min(1f,1f+pan);this.mono=mono;
    }

    public static StudioClipAudioEnvelope fromEffects(JSONObject effects,long outputDurationMs,float speed,float volume){
        if(outputDurationMs<1||outputDurationMs>MAX_DURATION_MS)
            throw new IllegalArgumentException("Audio clip duration must be 1..86400000 ms");
        if(!Float.isFinite(speed)||speed<.25f||speed>4f)
            throw new IllegalArgumentException("Audio clip speed must be finite in 0.25..4");
        if(!Float.isFinite(volume)||volume<0||volume>2)
            throw new IllegalArgumentException("Audio clip volume must be finite in 0..2");
        if(effects==null)effects=new JSONObject();
        long span=outputDurationMs,offset=0;
        if(effects.has("studioAudioDurationMs")||effects.has("studioAudioOffsetMs")){
            span=StudioClipKeyframes.integer(effects,"studioAudioDurationMs",1,MAX_DURATION_MS);
            offset=StudioClipKeyframes.integer(effects,"studioAudioOffsetMs",0,span);
            // Independent rounding of split output durations can differ by one millisecond.
            if(outputDurationMs>span-offset+1)
                throw new IllegalArgumentException("Split audio clock exceeds its authored duration; replace fades before extending this clip");
        }
        long in=effects.has("audioFadeInMs")?StudioClipKeyframes.integer(effects,"audioFadeInMs",0,span):0;
        long out=effects.has("audioFadeOutMs")?StudioClipKeyframes.integer(effects,"audioFadeOutMs",0,span):0;
        float pan=(float)StudioClipKeyframes.number(effects,"audioPan",0,-1,1);
        if(effects.has("audioMono")&&!(effects.opt("audioMono") instanceof Boolean))
            throw new IllegalArgumentException("audioMono must be boolean");
        return new StudioClipAudioEnvelope(span,offset,in,out,volume,pan,effects.optBoolean("audioMono",false));
    }

    /** Split calls preserve one continuous envelope rather than adding a dip at the cut. */
    public static void preserveSplit(JSONObject firstEffects,JSONObject secondEffects,
                                     long originalDurationMs,long firstDurationMs){
        if(firstEffects==null||secondEffects==null)throw new IllegalArgumentException("Split clip effects required");
        if(firstDurationMs<1||firstDurationMs>=originalDurationMs)
            throw new IllegalArgumentException("Audio split must fall inside the clip");
        StudioClipAudioEnvelope envelope=fromEffects(firstEffects,originalDurationMs,1,1);
        if(envelope.fadeInUs==0&&envelope.fadeOutUs==0)return;
        try{
            long span=envelope.durationUs/1000,offset=envelope.offsetUs/1000;
            firstEffects.put("studioAudioDurationMs",span).put("studioAudioOffsetMs",offset);
            secondEffects.put("studioAudioDurationMs",span).put("studioAudioOffsetMs",offset+firstDurationMs);
        }catch(org.json.JSONException error){throw new IllegalArgumentException("Could not preserve split audio clock",error);}
    }

    public boolean changesAudio(){return volume!=1f||fadeInUs>0||fadeOutUs>0||leftBalance!=1f||rightBalance!=1f||mono;}

    boolean mixesStereoToMono(){return mono;}
    float leftBalance(){return leftBalance;}
    float rightBalance(){return rightBalance;}

    /** Receives the edited item's local output clock, independent of its source trim. */
    public float gainAtOutputTimeUs(double outputTimeUs){
        double time=Math.max(0,outputTimeUs)+offsetUs;
        double gain=volume;
        if(fadeInUs>0)gain*=Math.max(0,Math.min(1,time/fadeInUs));
        if(fadeOutUs>0)gain*=Math.max(0,Math.min(1,(durationUs-time)/fadeOutUs));
        return (float)gain;
    }
}
