package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Color;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real GPU alpha/key/mask/bloom controls, shared by preview and export. */
@UnstableApi
public final class StudioPixelEffect implements GlEffect {
    public final boolean chromaKey;
    public final float[] keyColor;
    public final float tolerance,feather,left,top,right,bottom,maskFeather,glowStrength,glowRadiusPx,glowThreshold;
    public final int maskShape; // 0=none, 1=rectangle, 2=ellipse
    private final StudioClipKeyframes animation;
    private final boolean changes;
    private final long presentationStartUs;
    public StudioPixelEffect(JSONObject effects,long durationMs){
        this(effects,durationMs,0);
    }
    public StudioPixelEffect(JSONObject effects,long durationMs,long presentationStartUs){
        if(presentationStartUs<0)throw new IllegalArgumentException("Presentation start must be nonnegative");
        this.presentationStartUs=presentationStartUs;
        if(effects==null)effects=new JSONObject();
        JSONObject fx;
        try{fx=new JSONObject(effects.toString());}catch(Exception error){throw new IllegalArgumentException("Invalid pixel settings",error);}
        animation=StudioClipKeyframes.fromEffects(fx,durationMs);
        if(fx.has("chromaKey")&&!(fx.opt("chromaKey") instanceof Boolean))throw new IllegalArgumentException("chromaKey must be boolean");
        chromaKey=fx.optBoolean("chromaKey",false);
        Object rawColor=fx.opt("chromaColor");String color=rawColor==null?"#00FF00":rawColor instanceof String?(String)rawColor:"";
        if(!color.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("chromaColor must be #RRGGBB");
        int key=Color.parseColor(color);
        keyColor=new float[]{linear(Color.red(key)/255f),linear(Color.green(key)/255f),linear(Color.blue(key)/255f)};
        tolerance=value(fx,"chromaTolerance",.12,0,1);feather=value(fx,"chromaFeather",.05,0,1);
        Object rawShape=fx.opt("maskShape");String shape=rawShape==null?"none":rawShape instanceof String?(String)rawShape:"";
        switch(shape){case "none":maskShape=0;break;case "rectangle":maskShape=1;break;case "ellipse":maskShape=2;break;
            default:throw new IllegalArgumentException("maskShape must be none, rectangle or ellipse");}
        left=value(fx,"maskLeft",0,0,1);top=value(fx,"maskTop",0,0,1);right=value(fx,"maskRight",1,0,1);bottom=value(fx,"maskBottom",1,0,1);
        if(left>=right||top>=bottom)throw new IllegalArgumentException("Mask must have ordered, nonempty bounds");
        maskFeather=value(fx,"maskFeather",.02,0,.5);
        glowStrength=value(fx,"glowStrength",0,0,2);glowRadiusPx=value(fx,"glowRadiusPx",6,0,24);
        glowThreshold=value(fx,"glowThreshold",.7,0,1);
        boolean animatedOpacity=false;JSONArray keys=fx.optJSONArray("keyframes");
        if(keys!=null)for(int i=0;i<keys.length();i++)if(keys.optJSONObject(i)!=null&&keys.optJSONObject(i).has("opacity"))animatedOpacity=true;
        changes=chromaKey||maskShape!=0||glowStrength>0||animation.at(0).opacity!=1||animatedOpacity;
    }
    private static float value(JSONObject fx,String name,double fallback,double low,double high){
        return (float)StudioClipKeyframes.number(fx,name,fallback,low,high);
    }
    // Media3 effects process linear RGB, while owner-authored color selectors are sRGB.
    private static float linear(float value){return value<=.04045f?value/12.92f:(float)Math.pow((value+.055f)/1.055f,2.4);}
    public float opacityAtUs(long presentationTimeUs){
        return (float)animation.atUs(StudioEffectClock.localUs(presentationTimeUs,presentationStartUs)).opacity;
    }
    public boolean hasPixelChanges(){return changes;}
    @Override public boolean isNoOp(int inputWidth,int inputHeight){return !changes;}
    @Override public GlShaderProgram toGlShaderProgram(Context context,boolean useHdr)throws VideoFrameProcessingException{
        return new StudioPixelShaderProgram(this,useHdr);
    }
}
