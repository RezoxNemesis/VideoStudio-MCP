package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;

/** Compiled immutable curve; evaluation allocates nothing in the audio/frame loop. */
public final class KeyframeCurve {
    private final long[] times;
    private final double[] values;
    private final String[] easing;
    private final double[] easingStart,easingEnd;
    private final double fallback;
    public KeyframeCurve(ProjectStore.Clip clip,String property,double fallback){
        this.fallback=fallback;
        ArrayList<JSONObject> frames=new ArrayList<>();
        for(int i=0;i<clip.keyframes.length();i++){
            JSONObject f=clip.keyframes.optJSONObject(i);
            if(f!=null && property.equals(f.optString("property")))frames.add(f);
        }
        frames.sort(Comparator.comparingLong(f->f.optLong("timeMs")));
        times=new long[frames.size()];values=new double[frames.size()];easing=new String[frames.size()];
        easingStart=new double[frames.size()];easingEnd=new double[frames.size()];
        for(int i=0;i<frames.size();i++){
            times[i]=frames.get(i).optLong("timeMs");values[i]=frames.get(i).optDouble("value",fallback);easing[i]=frames.get(i).optString("easing","linear");
            easingStart[i]=frames.get(i).optDouble("easingStart",0);easingEnd[i]=frames.get(i).optDouble("easingEnd",1);
            if(times[i]<0 || !Double.isFinite(values[i]) || (i>0 && times[i]<=times[i-1]))throw new IllegalArgumentException("Invalid keyframe curve");
            if(!Double.isFinite(easingStart[i])||!Double.isFinite(easingEnd[i])||easingStart[i]<0||easingEnd[i]>1||easingStart[i]>=easingEnd[i])throw new IllegalArgumentException("Invalid sliced easing interval");
        }
    }
    public double valueAt(long time){
        if(times.length==0)return fallback;
        if(time<=times[0])return values[0];int last=times.length-1;
        if(time>=times[last])return values[last];
        int left=0,right=last;while(right-left>1){int mid=(left+right)>>>1;if(times[mid]<=time)left=mid;else right=mid;}
        double t=(time-times[left])/(double)(times[right]-times[left]);
        double start=ease(easing[left],easingStart[left]),end=ease(easing[left],easingEnd[left]);
        t=end==start?0:(ease(easing[left],easingStart[left]+t*(easingEnd[left]-easingStart[left]))-start)/(end-start);
        return values[left]+(values[right]-values[left])*t;
    }
    private static double ease(String name,double t){
        if("hold".equals(name))return 0;if("ease_in".equals(name))return t*t;
        if("ease_out".equals(name))return 1-(1-t)*(1-t);
        if("ease_in_out".equals(name))return t*t*(3-2*t);return t;
    }
}
