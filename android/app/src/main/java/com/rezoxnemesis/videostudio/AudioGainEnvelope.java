package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;

/** Clip-output-clock gain automation and actual sample-based audio edge fades. */
public final class AudioGainEnvelope {
    private static final class Key {
        final long timeUs;final float gain;final String easing;final CubicBezierEasing cubic;final int order;
        Key(long timeUs,float gain,String easing,CubicBezierEasing cubic,int order){this.timeUs=timeUs;this.gain=gain;this.easing=easing;this.cubic=cubic;this.order=order;}
    }
    private final ArrayList<Key> keys=new ArrayList<>();
    private final float base;
    private final boolean detached;
    private final long durationUs,offsetUs,fadeUs;
    public AudioGainEnvelope(ProjectStore.Clip clip) {
        JSONObject fx=clip.effects==null?new JSONObject():clip.effects;
        detached=fx.optBoolean("audioDetached",false);
        base=clip.volume;durationUs=AnimationClock.microseconds(Math.max(1,fx.optLong("animationDurationMs",clip.outputDurationMs())));
        offsetUs=AnimationClock.microseconds(fx.optLong("animationOffsetMs",0));
        fadeUs=java.util.Arrays.asList("fade","dip_black","dip_white").contains(clip.transition)
                ?Math.min(durationUs/2,AnimationClock.microseconds(Math.max(0,fx.optLong("transitionDurationMs",280)))):0;
        String transformEasing=fx.optString("ease","linear"),audioEasing=fx.optString("audioEasing","linear");
        add(fx.optJSONArray("keyframes"),transformEasing,CubicBezierEasing.compileDefault(fx,"ease","bezier",transformEasing));
        add(fx.optJSONArray("audioKeyframes"),audioEasing,CubicBezierEasing.compileDefault(fx,"audioEasing","audioBezier",audioEasing));
        keys.sort(Comparator.comparingLong((Key key)->key.timeUs).thenComparingInt(key->key.order));
        for(int i=keys.size()-1;i>0;i--)if(keys.get(i).timeUs==keys.get(i-1).timeUs)keys.remove(i-1);
    }
    boolean isConstantUnity(){return !detached&&base==1&&keys.isEmpty()&&fadeUs==0;}
    public float at(long localUs) {
        if(detached)return 0;
        long timeUs=AnimationClock.authoredTimeUs(localUs,offsetUs,durationUs);float gain=automationAt(localUs);
        if(fadeUs>0)gain*=MotionTimeline.ease(Math.min(1,Math.min(timeUs,durationUs-timeUs)/(float)fadeUs),"smooth");
        return Math.max(0,Math.min(2,gain));
    }
    /** Authored gain only; keyframe editors must not bake clip-edge fades into keys. */
    public float automationAt(long localUs) {
        long timeUs=AnimationClock.authoredTimeUs(localUs,offsetUs,durationUs);float gain=base;
        if(!keys.isEmpty()) {
            gain=keys.get(keys.size()-1).gain;
            if(timeUs<=keys.get(0).timeUs)gain=keys.get(0).gain;
            else for(int i=1;i<keys.size();i++) {
                Key right=keys.get(i),left=keys.get(i-1);
                if(timeUs<=right.timeUs){float p=(timeUs-left.timeUs)/(float)(right.timeUs-left.timeUs);float e=left.cubic==null?MotionTimeline.ease(p,left.easing):left.cubic.at(p);gain=left.gain+(right.gain-left.gain)*e;break;}
            }
        }
        return Math.max(0,Math.min(2,gain));
    }
    public static float evaluate(ProjectStore.Clip clip,long outputTimeMs){return new AudioGainEnvelope(clip).at(AnimationClock.microseconds(Math.max(0,outputTimeMs)));}
    private void add(JSONArray frames,String easing,CubicBezierEasing defaultCubic) {
        if(frames==null)return;
        for(int i=0;i<frames.length();i++) {
            JSONObject frame=frames.optJSONObject(i);if(frame==null||!frame.has("volume"))continue;
            double gain=frame.optDouble("volume",Double.NaN);
            double time=frame.has("timeMs")?frame.optDouble("timeMs",0)*1000:frame.has("timeUs")?frame.optDouble("timeUs",0):frame.optDouble("t",i/(double)Math.max(1,frames.length()-1))*durationUs;
            if(!Double.isFinite(gain)||gain<0||gain>2||!Double.isFinite(time))throw new IllegalArgumentException("Invalid volume automation keyframe");
            keys.add(new Key(Math.max(0,Math.min(durationUs,(long)time)),(float)gain,frame.optString("easing",easing),
                    CubicBezierEasing.forKeyframe(frame,"easing",easing,defaultCubic),keys.size()));
        }
    }
}
