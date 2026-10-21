package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Shared raster brush semantics; never a claim of skin-tone matching or healing. */
public final class SketchPaintCoverageCapabilities {
    private SketchPaintCoverageCapabilities() {}
    public static JSONObject get()throws Exception {return new JSONObject("{\"minimumNativeVersion\":\"4.10.11\",\"operation\":\"paint_stroke\",\"field\":\"brush.coverage\",\"choices\":[\"maximum\",\"accumulate\"],\"omittedBehavior\":\"accumulate\",\"modes\":[\"paint\",\"erase\",\"clone\"],\"maximumSemantics\":\"Per-pixel maximum of native dab coverage multiplied by flow; whole-stroke opacity applies once against the pre-stroke pigment. Separate strokes still composite normally.\",\"legacySemantics\":\"Accumulate dab flow within one stroke, with the existing whole-stroke opacity cap.\",\"vectorPressureBrushesSupported\":false,\"automaticToneMatching\":false,\"healsSeams\":false,\"sourceProvenanceUnchanged\":true,\"manualControls\":\"Sketch Studio > Paint studio > Paint settings > Stroke coverage\",\"limitsUnchanged\":\"Existing8192dab,32000000pixelvisit,2048point,clip,cancellation and tile limits apply; grain remains deterministic per dab.\"}");}
}
