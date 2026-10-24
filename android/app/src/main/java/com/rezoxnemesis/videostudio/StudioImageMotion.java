package com.rezoxnemesis.videostudio;

/** Shared layer-local motion sampling; saved image geometry remains the static base. */
public final class StudioImageMotion {
    private final StudioLayerMotion animation;

    private StudioImageMotion(StudioLayerMotion animation){
        this.animation=animation;
    }

    public static StudioImageMotion fromLayer(StudioLayer layer){
        if(layer==null||!StudioLayer.IMAGE.equals(layer.type))
            throw new IllegalArgumentException("Image motion requires an image layer");
        return new StudioImageMotion(StudioLayerMotion.fromLayer(layer));
    }

    public boolean hasKeyframes(){return animation.hasKeyframes();}

    /** Relative canvas deltas, scale/alpha multipliers and additive rotation. */
    public StudioClipKeyframes.Values atUs(long localTimeUs){return animation.atUs(localTimeUs);}
}
