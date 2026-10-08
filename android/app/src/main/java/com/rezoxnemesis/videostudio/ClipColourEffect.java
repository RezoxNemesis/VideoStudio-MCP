package com.rezoxnemesis.videostudio;

import androidx.media3.effect.RgbMatrix;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;

/** GPU RGB grade with frame-evaluated exposure, contrast, saturation and lightness curves. */
@UnstableApi
public final class ClipColourEffect implements RgbMatrix {
    private final KeyframeCurve brightness,contrast,saturation,lightness;
    public ClipColourEffect(ProjectStore.Clip c){
        JSONObject fx=c.effects,preset=CreatorCatalog.effectPreset(fx.optString("effectPreset",fx.optString("colorPreset","")));
        brightness=new KeyframeCurve(c,"brightness",fx.optDouble("brightness",preset.optDouble("brightness",0)));
        contrast=new KeyframeCurve(c,"contrast",fx.optDouble("contrast",preset.optDouble("contrast",0)));
        saturation=new KeyframeCurve(c,"saturationAdjust",fx.optDouble("saturationAdjust",fx.optDouble("saturation",preset.optDouble("saturationAdjust",0))));
        lightness=new KeyframeCurve(c,"lightnessAdjust",fx.optDouble("lightnessAdjust",preset.optDouble("lightnessAdjust",0)));
    }
    @Override public float[] getMatrix(long timeUs,boolean hdr){
        long time=Math.max(0,timeUs/1000);float s=(float)Math.max(0,1+saturation.valueAt(time)/100);
        float c=(float)Math.pow(2,contrast.valueAt(time));float b=(float)(brightness.valueAt(time)+lightness.valueAt(time)/100+.5*(1-c));
        float r=.2126f*(1-s),g=.7152f*(1-s),bl=.0722f*(1-s);
        return new float[]{c*(r+s),c*r,c*r,0, c*g,c*(g+s),c*g,0, c*bl,c*bl,c*(bl+s),0, b,b,b,1};
    }
}
