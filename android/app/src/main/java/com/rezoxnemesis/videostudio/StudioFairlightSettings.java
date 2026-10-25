package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/**
 * Native, bounded Fairlight-inspired audio FX settings for project-owned AUDIO
 * tracks. All inputs are scalar and validated; there are no network/file paths,
 * plugins, shell commands, arbitrary scripts, or DAW project-file imports.
 */
public final class StudioFairlightSettings {
    private static final Set<String> FIELDS=Set.of(
            "eqLowDb","eqMidDb","eqHighDb","compressorThresholdDb",
            "compressorRatio","attackMs","releaseMs","makeupDb",
            "limiterCeilingDb","outputDb","highpassHz","lowpassHz",
            "noiseGateThresholdDb","bypass");
    private StudioFairlightSettings(){}

    public static JSONObject capabilities()throws Exception{
        return new JSONObject().put("schema","videostudio.fairlight-fx.v1")
                .put("minimumNativeVersion","4.10.35")
                .put("supportedTrackTypes",new JSONArray().put("audio"))
                .put("effects",new JSONArray().put("three_band_eq")
                        .put("compressor").put("sample_peak_limiter")
                        .put("noise_gate").put("highpass").put("lowpass"))
                .put("pcmEncoding","PCM_16BIT").put("maxChannels",8)
                .put("sampleAccurateStreaming",true)
                .put("samplePeakNotTruePeak",true)
                .put("lufsComplianceVerified",false)
                .put("multichannelSurroundMastering",false)
                .put("sourceMediaUnchanged",true)
                .put("nativeActions",new JSONArray().put("fairlight_fx_capabilities")
                        .put("fairlight_fx_plan").put("fairlight_fx_apply"))
                .put("galleryAccess",false);
    }

    public static JSONObject validate(JSONObject raw)throws Exception{
        if(raw==null)throw new IllegalArgumentException("Fairlight audio effect object required");
        java.util.Iterator<String> keys=raw.keys();
        while(keys.hasNext())if(!FIELDS.contains(keys.next()))
            throw new IllegalArgumentException("Unknown Fairlight audio effect field");
        JSONObject spec=new JSONObject();
        for(String key:new String[]{"eqLowDb","eqMidDb","eqHighDb","makeupDb","outputDb"}){
            double min="outputDb".equals(key)?-24:-12;
            double max="makeupDb".equals(key)?24:12;
            spec.put(key,num(raw,key,0,min,max));
        }
        spec.put("compressorThresholdDb",num(raw,"compressorThresholdDb",-18,-60,0));
        spec.put("compressorRatio",num(raw,"compressorRatio",1,1,20));
        spec.put("attackMs",num(raw,"attackMs",12,1,150));
        spec.put("releaseMs",num(raw,"releaseMs",180,10,1500));
        spec.put("limiterCeilingDb",num(raw,"limiterCeilingDb",0,-18,0));
        spec.put("highpassHz",num(raw,"highpassHz",0,0,400));
        double lowpass=num(raw,"lowpassHz",0,0,20000);
        if(lowpass>0&&lowpass<1000)
            throw new IllegalArgumentException("Lowpass cutoff must be 0 (off) or 1000..20000 Hz");
        spec.put("lowpassHz",lowpass);
        spec.put("noiseGateThresholdDb",num(raw,"noiseGateThresholdDb",-80,-80,0));
        if(raw.has("bypass")&&!(raw.opt("bypass") instanceof Boolean))
            throw new IllegalArgumentException("Bypass must be boolean");
        spec.put("bypass",raw.optBoolean("bypass",false));
        return spec;
    }

    private static double num(JSONObject obj,String key,double fallback,double min,double max){
        Object v=obj.opt(key);
        if(v==null)return fallback;
        if(!(v instanceof Number))
            throw new IllegalArgumentException(key+" requires a numeric value");
        double d=((Number)v).doubleValue();
        if(!Double.isFinite(d)||d<min||d>max)
            throw new IllegalArgumentException(key+" outside allowed range ["+min+","+max+"]");
        return d;
    }

    /** Null denotes no processor, which preserves all legacy saved AUDIO bytes. */
    public static boolean enabled(JSONObject normalized) {
        return normalized!=null&&!normalized.optBoolean("bypass",false);
    }
}
