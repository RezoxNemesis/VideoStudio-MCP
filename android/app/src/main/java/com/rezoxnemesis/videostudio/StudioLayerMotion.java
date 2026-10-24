package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/** Shared image/video layer-local motion; the saved placement remains the static base. */
public final class StudioLayerMotion {
    private static final Set<String> FIELDS=Set.of("timeMs","x","y","scale","scaleX","scaleY","rotate","opacity","easing");
    private final StudioClipKeyframes animation;
    private final boolean keyed;

    private StudioLayerMotion(StudioClipKeyframes animation,boolean keyed){
        this.animation=animation;this.keyed=keyed;
    }

    public static StudioLayerMotion fromLayer(StudioLayer layer){
        if(layer==null||(!StudioLayer.IMAGE.equals(layer.type)&&!StudioLayer.VIDEO.equals(layer.type)))
            throw new IllegalArgumentException("Layer motion requires an image or video layer");
        String kind=layer.type;
        JSONArray keys=layer.motionKeyframes;
        if(keys!=null){
            if(keys.length()>StudioClipKeyframes.MAX_KEYS)
                throw new IllegalArgumentException("Maximum 64 "+kind+" motion keyframes");
            for(int i=0;i<keys.length();i++){
                JSONObject key=keys.optJSONObject(i);
                if(key==null)throw new IllegalArgumentException("Each "+kind+" motion keyframe must be an object");
                java.util.Iterator<String> fields=key.keys();
                while(fields.hasNext())if(!FIELDS.contains(fields.next()))
                    throw new IllegalArgumentException("Unsupported "+kind+" motion keyframe field");
            }
        }
        // Parsing detaches every numeric value. Empty/omitted channels have the
        // identity base, not the prior key or the saved layer's width/position.
        return new StudioLayerMotion(StudioClipKeyframes.parse(keys,layer.durationMs),keys!=null&&keys.length()>0);
    }

    public boolean hasKeyframes(){return keyed;}

    /** Relative canvas deltas, scale/alpha multipliers and additive rotation. */
    public StudioClipKeyframes.Values atUs(long localTimeUs){return animation.atUs(localTimeUs);}
}
