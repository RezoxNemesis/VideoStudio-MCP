package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Parameter contracts matching NativeRenderEngine's actual Media3 effects. */
public final class StudioClipAdjustments {
    private StudioClipAdjustments(){}
    public static final String[] COLOR_KEYS={
        "brightness","contrast","saturationAdjust","lightnessAdjust"
    };
    public static final String[] COLOR_NAMES={
        "Brightness","Contrast","Saturation","Lightness"
    };
    public static final String[] TRANSFORM_KEYS={"scale","rotate"};
    public static final String[] TRANSFORM_NAMES={"Zoom","Rotation"};
    public static final int[] COLOR_DEFAULTS={100,100,100,100};
    public static final int[] TRANSFORM_DEFAULTS={100,45};

    public static int sliderFor(JSONObject effects,String key) {
        if(effects==null)effects=new JSONObject();
        switch(key){
            case "brightness":
            case "contrast":
                return bound((int)Math.round(effects.optDouble(key,0)*100)+100,0,200);
            case "saturationAdjust":
            case "lightnessAdjust":
                return bound((int)Math.round(effects.optDouble(key,0))+100,0,200);
            case "scale":
                return bound((int)Math.round(effects.optDouble(key,1)*100),50,250);
            case "rotate":
                return bound((int)Math.round(effects.optDouble(key,0))+45,0,90);
            default: throw new IllegalArgumentException("Unsupported native adjust key");
        }
    }
    public static String labelFor(String key,int slider){
        switch(key){
            case "brightness":
            case "contrast":
                return (slider-100)+"%";
            case "saturationAdjust":
            case "lightnessAdjust":
                return (slider-100)+" pts";
            case "scale":
                return slider+"%";
            case "rotate":
                return (slider-45)+"°";
            default:throw new IllegalArgumentException("Unsupported adjust key");
        }
    }
    public static void apply(JSONObject effects,String key,int slider){
        if(effects==null)throw new IllegalArgumentException("Clip effects missing");
        try{
            switch(key){
                case "brightness":
                case "contrast":
                    effects.put(key,(bound(slider,0,200)-100)/100.0);return;
                case "saturationAdjust":
                case "lightnessAdjust":
                    effects.put(key,bound(slider,0,200)-100);return;
                case "scale":
                    effects.put(key,bound(slider,50,250)/100.0);return;
                case "rotate":
                    effects.put(key,bound(slider,0,90)-45);return;
                default:throw new IllegalArgumentException("Unsupported adjust key");
            }
        }catch(org.json.JSONException error){
            throw new IllegalArgumentException("Could not save clip adjustment",error);
        }
    }
    public static void reset(JSONObject effects,boolean transform){
        if(effects==null)return;
        for(String key:transform?TRANSFORM_KEYS:COLOR_KEYS)effects.remove(key);
    }
    private static int bound(int value,int min,int max){
        return Math.max(min,Math.min(max,value));
    }
}
