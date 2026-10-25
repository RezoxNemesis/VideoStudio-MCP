package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Human/ChatGPT authored Xsheet holds on one exact source-cel start.
 *
 * Applies to the original editable objects through the ordinary sketch_edit
 * transaction and source-revision guard. Never rasterizes, copies artwork,
 * invents in-betweens, touches Gallery or edits immutable paint/reference bytes.
 */
final class SketchXsheetEdits {
    static final int MAX_OBJECTS_PER_HOLD=256;
    private SketchXsheetEdits() {}

    static JSONObject capabilities() throws Exception {
        return new JSONObject().put("minimumNativeVersion","4.10.38")
                .put("action","sketch_edit")
                .put("operationType","set_exposure_cel")
                .put("maximumObjectsPerEdit",MAX_OBJECTS_PER_HOLD)
                .put("sourceCelStartRequired",true)
                .put("collisionChecked",true)
                .put("keyframePreserving",true)
                .put("sourceGeometryUnchanged",true)
                .put("previewAndExportUseSameExposure",true)
                .put("galleryAccess",false)
                .put("renderVerifiedOnPhone",false);
    }

    /** Pure information about the source drawing. No document mutation. */
    static JSONObject inspect(JSONObject document,String levelId,int firstFrame) throws Exception {
        if(document==null)throw SketchDocument.bad("Open an owned sketch first");
        int count=document.getInt("frameCount");
        if(firstFrame<0||firstFrame>=count)
            throw SketchDocument.bad("Exposure start must be an existing 0-based frame");
        JSONObject layer=SketchDocument.layer(document,levelId);
        if(layer.getBoolean("locked"))
            throw new IllegalStateException("SKETCH_LAYER_LOCKED: protected level cannot be retimed");
        JSONArray objects=layer.getJSONArray("objects"),ids=new JSONArray();
        int next=count,earliest=Integer.MAX_VALUE,latest=0,selected=0;
        for(int i=0;i<objects.length();i++){
            JSONObject object=objects.getJSONObject(i);
            int start=object.getInt("frame");
            if(start>firstFrame)next=Math.min(next,start);
            if(start!=firstFrame)continue;
            ids.put(object.getString("id"));selected++;
            earliest=Math.min(earliest,object.getInt("exposure"));
            latest=Math.max(latest,object.getInt("exposure"));
            if(selected>MAX_OBJECTS_PER_HOLD)
                throw SketchDocument.bad("More than 256 marks begin here; split this complex cel before changing its hold");
        }
        if(selected==0)
            throw SketchDocument.bad("This cell is a hold/blank, not a new drawing. Choose its original drawing-start cell");
        return new JSONObject().put("schema","videostudio.sketch-xsheet-hold-inspection.v1")
                .put("sketchId",document.getString("id"))
                .put("sourceRevision",document.getLong("revision"))
                .put("layerId",levelId)
                .put("frameIndex",firstFrame)
                .put("sourceObjectIds",ids)
                .put("sourceObjectCount",selected)
                .put("minimumCurrentExposure",earliest)
                .put("maximumCurrentExposure",latest)
                .put("nextDrawingFrame",next<count?next:JSONObject.NULL)
                .put("maximumSafeExposureFrames",next-firstFrame)
                .put("readOnly",true);
    }

    static JSONObject plan(JSONObject document,JSONObject request) throws Exception {
        if(request==null)throw SketchDocument.bad("An explicit native Xsheet operation is required");
        SketchDocument.fields(request,"type,layerId,frameIndex,exposureFrames");
        if(!"set_exposure_cel".equals(request.optString("type")))
            throw SketchDocument.bad("Use set_exposure_cel for a writable exposure");
        String levelId=SketchDocument.id(request,"layerId");
        int first=(int)SketchDocument.integer(request,"frameIndex",0,document.getInt("frameCount")-1);
        int hold=(int)SketchDocument.integer(request,"exposureFrames",1,document.getInt("frameCount")-first);
        JSONObject info=inspect(document,levelId,first);
        int safe=info.getInt("maximumSafeExposureFrames");
        if(hold>safe)
            throw SketchDocument.bad("Hold crosses the next drawing on this level. Maximum without overlap is "+safe+" frame(s)");
        JSONArray layerObjects=SketchDocument.layer(document,levelId).getJSONArray("objects");
        int changes=0;
        for(int i=0;i<layerObjects.length();i++){
            JSONObject object=layerObjects.getJSONObject(i);
            if(object.getInt("frame")!=first)continue;
            int before=object.getInt("exposure");
            if(before==hold)continue;
            if(hold<before)ensureMotionSurvives(object,first+hold,first+before);
            changes++;
        }
        if(changes==0)throw SketchDocument.bad("Exposure already equals the requested hold; nothing was changed");
        return info.put("requestedExposureFrames",hold)
                .put("changedObjectCount",changes)
                .put("readOnly",true)
                .put("sourcePixelsCopied",false)
                .put("createdIntermediateDrawings",false);
    }

    /** Called only from a revision-bound sketch_edit native transaction. */
    static void apply(JSONObject document,JSONObject request) throws Exception {
        JSONObject plan=plan(document,request);
        JSONObject layer=SketchDocument.layer(document,plan.getString("layerId"));
        SketchEditOps.requireUnlocked(layer);
        JSONArray objects=layer.getJSONArray("objects");
        int frame=plan.getInt("frameIndex"),hold=plan.getInt("requestedExposureFrames");
        for(int i=0;i<objects.length();i++){
            JSONObject object=objects.getJSONObject(i);
            if(object.getInt("frame")==frame)
                object.put("exposure",hold);
        }
    }

    /** Don't silently clip authored acting, shape deformation or painted rigs. */
    private static void ensureMotionSurvives(JSONObject object,int newEnd,int previousEnd)
            throws Exception {
        if(object.has("hand")||object.has("human")||object.has("paintMesh"))
            throw SketchDocument.bad("Shortening an articulated/paint-mesh cel requires explicit pose review");
        for(String key:new String[]{"keys","shapeKeys"}){
            JSONArray keys=object.optJSONArray(key);
            if(keys==null)continue;
            for(int i=0;i<keys.length();i++){
                int at=keys.getJSONObject(i).getInt("frame");
                if(at>=newEnd && at<previousEnd)
                    throw SketchDocument.bad("Shortening hides a "+key+" at frame "+(at+1)+". Retiming keys requires a separate review");
            }
        }
    }
}
