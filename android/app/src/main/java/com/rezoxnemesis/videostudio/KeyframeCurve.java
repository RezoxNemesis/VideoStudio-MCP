package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;

/** Compiled immutable curve; evaluation allocates nothing in the audio/frame loop. */
public final class KeyframeCurve {
    private final long[] times;
    private final double[] values;
    private final String[] easing;
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
        for(int i=0;i<frames.size();i++){
            times[i]=frames.get(i).optLong("timeMs");values[i]=frames.get(i).optDouble("value",fallback);easing[i]=frames.get(i).optString("easing","linear");
            if(times[i]<0 || !Double.isFinite(values[i]) || (i>0 && times[i]<=times[i-1]))throw new IllegalArgumentException("Invalid keyframe curve");
        }
    }
    public double valueAt(long time){
        if(times.length==0)return fallback;
        if(time<=times[0])return values[0];int last=times.length-1;
        if(time>=times[last])return values[last];
        int left=0,right=last;while(right-left>1){int mid=(left+right)>>>1;if(times[mid]<=time)left=mid;else right=mid;}
        double t=(time-times[left])/(double)(times[right]-times[left]);
        String ease=easing[left];if("hold".equals(ease))t=0;else if("ease_in".equals(ease))t*=t;
        else if("ease_out".equals(ease))t=1-(1-t)*(1-t);else if("ease_in_out".equals(ease))t=t*t*(3-2*t);
        return values[left]+(values[right]-values[left])*t;
    }
}
