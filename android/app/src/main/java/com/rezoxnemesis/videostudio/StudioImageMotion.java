package com.rezoxnemesis.videostudio;

/** Compatibility facade for image-only callers of the shared layer-local sampler. */
public final class StudioImageMotion {
    private final StudioLayerMotion motion;

    private StudioImageMotion(StudioLayerMotion motion){this.motion=motion;}

    public static StudioImageMotion fromLayer(StudioLayer layer){
        if(layer==null||!StudioLayer.IMAGE.equals(layer.type))
            throw new IllegalArgumentException("Image motion requires an image layer");
        return new StudioImageMotion(StudioLayerMotion.fromLayer(layer));
    }

    public boolean hasKeyframes(){return motion.hasKeyframes();}

    /** Relative canvas deltas, scale/alpha multipliers and additive rotation. */
    public StudioClipKeyframes.Values atUs(long localTimeUs){return motion.atUs(localTimeUs);}
}
