package com.rezoxnemesis.videostudio;
import org.json.*;import java.util.*;

/** Bounded motion intent. Unsupported semantic actions remain explicit requirements. */
public final class ImageMotionPlan {
    public static JSONObject compile(JSONObject parameters) throws Exception {
        String prompt=parameters.optString("prompt","").trim();if(prompt.length()>4000)throw new IllegalArgumentException("Motion prompt is too long");
        int fps=parameters.optInt("fps",24);if(fps!=24&&fps!=30)throw new IllegalArgumentException("Animation supports 24 or 30 fps");
        double duration=parameters.optDouble("durationSeconds",4),strength=parameters.optDouble("styleStrength",.5);
        if(!Double.isFinite(duration)||duration<1||duration>8||!Double.isFinite(strength)||strength<0||strength>1)throw new IllegalArgumentException("Duration must be 1–8 seconds and strength 0–1");
        String mode=parameters.optString("engine","layered");if(!mode.equals("layered")&&!mode.equals("learned"))throw new IllegalArgumentException("Select layered or learned animation");
        String lower=prompt.toLowerCase(Locale.ROOT);JSONArray actions=new JSONArray(),requirements=new JSONArray();
        String[] keywords={"blink","mouth","head","recoil","limb","cloth","smoke","flame","shake","parallax"};
        JSONObject controls=new JSONObject();for(String keyword:keywords){boolean requested=lower.contains(keyword)||(keyword.equals("flame")&&lower.contains("fire"));controls.put(keyword,requested?strength:0);if(requested)actions.put(keyword);}
        if(lower.contains("react")||lower.contains("recoil"))controls.put("recoil",strength);
        if(parameters.optString("camera","locked").equals("parallax"))controls.put("parallax",strength);
        if(parameters.optString("camera","locked").equals("shake"))controls.put("shake",strength);
        if(lower.contains("walk")||lower.contains("arm")||lower.contains("leg"))controls.put("limb",strength);
        if(!mode.equals("learned"))for(String keyword:new String[]{"blink","mouth","limb","recoil"})if(controls.optDouble(keyword)>0)requirements.put("learned_subject_"+keyword);
        if(mode.equals("learned"))requirements.put("verified_image_conditioned_temporal_model");
        JSONArray strokes=parameters.optJSONArray("motionStrokes");if(strokes==null)strokes=new JSONArray();if(strokes.length()>128)throw new IllegalArgumentException("Motion brush has too many strokes");
        for(int i=0;i<strokes.length();i++){JSONObject stroke=strokes.getJSONObject(i);for(String key:new String[]{"x","y","radius"}){double n=stroke.optDouble(key,Double.NaN);if(!Double.isFinite(n)||n<0||n>1)throw new IllegalArgumentException("Invalid motion brush coordinate");}}
        return new JSONObject().put("version",1).put("assetId",parameters.optString("assetId")).put("prompt",prompt).put("engine",mode).put("fps",fps).put("durationSeconds",duration).put("frameCount",(int)Math.round(duration*fps)).put("subjectLock",parameters.optBoolean("subjectLock",true)).put("styleStrength",strength).put("camera",parameters.optString("camera","locked")).put("actions",actions).put("controls",controls).put("motionStrokes",strokes).put("requirements",requirements).put("trueFrameSynthesis",mode.equals("learned")).put("semanticQualityVerified",false);
    }
    static float[] controls(JSONObject plan,int frame){JSONObject c=plan.optJSONObject("controls");float[] result=new float[12];result[0]=frame/(float)Math.max(1,plan.optInt("frameCount")-1);result[1]=1f/plan.optInt("fps",24);String[] keys={"blink","mouth","head","recoil","limb","cloth","smoke","flame","shake","parallax"};for(int i=0;i<keys.length;i++)result[i+2]=(float)c.optDouble(keys[i]);return result;}
}
