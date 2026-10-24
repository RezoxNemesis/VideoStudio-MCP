package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/** Shared layer-local motion sampling; saved image geometry remains the static base. */
public final class StudioImageMotion {
    private static final Set<String> FIELDS=Set.of("timeMs","x","y","scale","scaleX","scaleY","rotate","opacity","easing");
    private final StudioClipKeyframes animation;
    private final boolean keyed;

    private StudioImageMotion(StudioClipKeyframes animation,boolean keyed){
        this.animation=animation;this.keyed=keyed;
    }

    public static StudioImageMotion fromLayer(StudioLayer layer){
        if(layer==null||!StudioLayer.IMAGE.equals(layer.type))
            throw new IllegalArgumentException("Image motion requires an image layer");
        JSONArray keys=layer.motionKeyframes;
        if(keys!=null){
            if(keys.length()>StudioClipKeyframes.MAX_KEYS)
                throw new IllegalArgumentException("Maximum 64 image motion keyframes");
            for(int i=0;i<keys.length();i++){
                JSONObject key=keys.optJSONObject(i);
                if(key==null)throw new IllegalArgumentException("Each image motion keyframe must be an object");
                java.util.Iterator<String> fields=key.keys();
                while(fields.hasNext())if(!FIELDS.contains(fields.next()))
                    throw new IllegalArgumentException("Unsupported image motion keyframe field");
            }
        }
        // The sampler detaches all numeric values. Empty/omitted channels have the
        // identity base, not the previous key or the image's static width/position.
        return new StudioImageMotion(StudioClipKeyframes.parse(keys,layer.durationMs),keys!=null&&keys.length()>0);
    }

    public boolean hasKeyframes(){return keyed;}

    /** Relative canvas deltas, scale/alpha multipliers and additive rotation. */
    public StudioClipKeyframes.Values atUs(long localTimeUs){return animation.atUs(localTimeUs);}
}
