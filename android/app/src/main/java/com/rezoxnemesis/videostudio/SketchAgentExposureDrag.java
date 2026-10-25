package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Original writable Xsheet gesture program for ChatGPT.
 *
 * Plans movement of an actual authored cel's exposed right edge, never a
 * source replacement, screenshot, duplicated cel or inferred in-between.
 * Collision/keyframe protection is delegated to the existing human Xsheet
 * editor's exact SketchXsheetEdits contract.
 */
public final class SketchAgentExposureDrag {
    public static final int MIN_DURATION_MS=180,MAX_DURATION_MS=1400;
    public static final int MAX_SAMPLES=24;
    private SketchAgentExposureDrag(){}

    public static final class Plan {
        public final String sketchId,layerId;
        public final long sourceRevision;
        public final int frameIndex,fromExposure,toExposure,maxSafeExposure,durationMs;
        public final JSONArray authoredObjectIds;
        public final JSONObject editOperation;
        private Plan(JSONObject source,JSONObject inspected,int duration,JSONObject operation)
                throws Exception {
            sketchId=source.getString("id");
            sourceRevision=source.getLong("revision");
            layerId=inspected.getString("layerId");
            frameIndex=inspected.getInt("frameIndex");
            fromExposure=inspected.getInt("maximumCurrentExposure");
            toExposure=inspected.getInt("requestedExposureFrames");
            maxSafeExposure=inspected.getInt("maximumSafeExposureFrames");
            durationMs=duration;
            authoredObjectIds=new JSONArray(inspected.getJSONArray("sourceObjectIds").toString());
            editOperation=SketchDocument.copy(operation);
        }
        public int sample(float position) {
            if(!Float.isFinite(position))
                throw new IllegalArgumentException("A visible native exposure drag needs finite progress");
            double t=Math.max(0,Math.min(1,(double)position));
            t=t*t*(3-2*t);
            return Math.max(1,Math.min(maxSafeExposure,
                    (int)Math.round(fromExposure+(toExposure-(long)fromExposure)*t)));
        }
        public JSONObject receipt(int actualDraws,int displayedExposure)throws Exception {
            if(actualDraws<1||actualDraws>MAX_SAMPLES*20||displayedExposure!=toExposure)
                throw new IllegalStateException("Native Xsheet handle was not visibly moved to the saved target");
            return new JSONObject().put("actualNativeXsheetHandleDraws",actualDraws)
                    .put("sketchId",sketchId).put("layerId",layerId)
                    .put("sourceFrameIndex",frameIndex)
                    .put("sourceRevision",sourceRevision)
                    .put("fromExposureFrames",fromExposure)
                    .put("actualDisplayedExposureFrames",displayedExposure)
                    .put("targetExposureFrames",toExposure)
                    .put("affectedOriginalObjects",authoredObjectIds.length())
                    .put("newCelsGenerated",false)
                    .put("renderedPixelsVerified",false)
                    .put("sourceMediaUnchangedUntilCommit",true);
        }
    }

    public static Plan plan(JSONObject source,JSONObject step)throws Exception {
        if(source==null||step==null)
            throw SketchDocument.bad("Open a real authored sketch and exact Xsheet source");
        SketchDocument.fields(step,"action,layerId,frameIndex,exposureFrames,durationMs");
        if(!"exposure_drag".equals(step.optString("action","")))
            throw SketchDocument.bad("Only source-bound exposure_drag is allowed");
        String level=SketchDocument.id(step,"layerId");
        JSONObject layer=SketchDocument.layer(source,level);
        if(!layer.getBoolean("visible"))
            throw SketchDocument.bad("Reveal the original authored level before changing its exposure");
        SketchEditOps.requireUnlocked(layer);
        int frame=(int)SketchDocument.integer(step,"frameIndex",
                0,source.getInt("frameCount")-1);
        int hold=(int)SketchDocument.integer(step,"exposureFrames",
                1,source.getInt("frameCount")-frame);
        int ms=step.has("durationMs")?(int)SketchDocument.integer(step,
                "durationMs",MIN_DURATION_MS,MAX_DURATION_MS):600;
        JSONObject operation=new JSONObject().put("type","set_exposure_cel")
                .put("layerId",level).put("frameIndex",frame)
                .put("exposureFrames",hold);
        JSONObject inspected=SketchXsheetEdits.plan(source,operation);
        // Preflight the actual author's contract against a detached project.
        JSONObject clone=SketchDocument.copy(source);
        SketchEditOps.apply(clone,operation);
        return new Plan(source,inspected,ms,operation);
    }

    public static JSONObject capabilities()throws Exception {
        return new JSONObject().put("minimumNativeVersion","4.10.46")
                .put("action","exposure_drag")
                .put("stageAction","sketch_stage_execute")
                .put("requiresExistingOriginalCel",true)
                .put("sameContractAsHumanXsheet","set_exposure_cel")
                .put("actualNativeCanvasRuler",true)
                .put("maximumVisibleSamples",MAX_SAMPLES)
                .put("durationMinMs",MIN_DURATION_MS).put("durationMaxMs",MAX_DURATION_MS)
                .put("requiresOpenSketchStudio",true)
                .put("collisionChecked",true).put("authoredKeysNotClipped",true)
                .put("createsIntermediateCels",false)
                .put("oneGuardedNativeEdit",true).put("sourceMediaUntouched",true)
                .put("galleryAccess",false).put("deviceMp4Verified",false);
    }
}
