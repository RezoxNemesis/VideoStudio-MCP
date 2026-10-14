package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Builds the Volume dialog's explicit edits without reauthoring unchanged split fades. */
public final class StudioVolumeEdits {
    private StudioVolumeEdits(){}
    public static JSONObject settings(int volumePercent,long durationMs,long originalInMs,long originalOutMs,long inMs,long outMs)throws Exception{
        if(volumePercent<0||volumePercent>200||inMs<0||outMs<0)throw new IllegalArgumentException("Invalid clip volume or fade");
        boolean changedIn=inMs!=originalInMs,changedOut=outMs!=originalOutMs;
        // Changing a fade explicitly starts a new local envelope; volume alone preserves its clock.
        if((changedIn||changedOut)&&(inMs>durationMs||outMs>durationMs))throw new IllegalArgumentException("When editing fades, each fade must fit inside this clip's duration");
        JSONObject set=new JSONObject().put("volume",volumePercent/100.0);
        if(changedIn)set.put("audioFadeInMs",inMs);
        if(changedOut)set.put("audioFadeOutMs",outMs);
        return set;
    }
    public static JSONObject settings(int volumePercent,long durationMs,long originalInMs,long originalOutMs,long inMs,long outMs,int panPercent,boolean mono)throws Exception{
        if(panPercent< -100||panPercent>100)throw new IllegalArgumentException("Stereo balance must be -100..100 percent");
        return settings(volumePercent,durationMs,originalInMs,originalOutMs,inMs,outMs)
                .put("audioPan",panPercent/100.0).put("audioMono",mono);
    }
}
