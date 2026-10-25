package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Plans one actual, visible ChatGPT Bézier timing-handle drag.
 *
 * Only an existing, authored Bézier key may be manipulated. The exact key
 * transform or path geometry stays byte-equivalent; only its selected timing
 * handle changes. The project source is never mutated during preview.
 */
public final class SketchAgentCurveDrag {
    public static final int MIN_DURATION_MS=180,MAX_DURATION_MS=1200;
    public static final int MAX_VISUAL_SAMPLES=24;
    private SketchAgentCurveDrag(){}

    public static final class Plan {
        public final String sketchId,mode,ownerId,targetId;
        public final long sourceRevision;
        public final int frameIndex,handleIndex,durationMs;
        public final JSONArray sourceCurve,targetCurve;
        public final JSONObject editOperation;
        private Plan(JSONObject doc,String mode,String id,String layerId,int frame,
                     int handle,int duration,JSONArray source,JSONArray target,JSONObject operation)
                     throws Exception {
            sketchId=doc.getString("id");
            sourceRevision=doc.getLong("revision");
            this.mode=mode;targetId=id;ownerId=layerId;
            frameIndex=frame;handleIndex=handle;durationMs=duration;
            sourceCurve=new JSONArray(source.toString());
            targetCurve=new JSONArray(target.toString());
            editOperation=SketchDocument.copy(operation);
        }
        public JSONArray sample(float progress)throws Exception {
            if(!Float.isFinite(progress))
                throw SketchDocument.bad("Agent timing drag fraction must be finite");
            double t=Math.max(0,Math.min(1,progress));
            t=t*t*(3-2*t);
            JSONArray result=new JSONArray();
            for(int i=0;i<4;i++){
                double a=sourceCurve.getDouble(i),b=targetCurve.getDouble(i);
                result.put((Object)Double.valueOf(a+(b-a)*t));
            }
            return result;
        }
        public boolean matchesTarget(JSONArray displayed)throws Exception {
            if(displayed==null||displayed.length()!=4)return false;
            for(int i=0;i<4;i++)
                if(Math.abs(displayed.getDouble(i)-targetCurve.getDouble(i))>0.0002)return false;
            return true;
        }
        /** Evidence after foreground draw, never proof that GPU export succeeded. */
        public JSONObject visualReceipt(int actualDraws,JSONArray displayed)throws Exception {
            if(actualDraws<1||actualDraws>MAX_VISUAL_SAMPLES+10||!matchesTarget(displayed))
                throw new IllegalStateException("Curve handles were not actually displayed at the requested target");
            return new JSONObject().put("visibleCurveCanvasDraws",actualDraws)
                    .put("timingHandle",handleIndex)
                    .put("targetFrame",frameIndex)
                    .put("sourceRevision",sourceRevision)
                    .put("targetId",targetId)
                    .put("curveMode",mode)
                    .put("displayedCurve",new JSONArray(displayed.toString()))
                    .put("renderedMp4Verified",false)
                    .put("sourcePixelsUnchangedUntilCommit",true);
        }
    }

    public static Plan plan(JSONObject document,JSONObject step)throws Exception {
        if(document==null||step==null)
            throw SketchDocument.bad("A real native Sketch drawing and curve-handle request are required");
        SketchDocument.fields(step,
                "action,mode,objectId,layerId,frameIndex,handleIndex,x,y,durationMs");
        if(!"curve_handle_drag".equals(step.optString("action","")))
            throw SketchDocument.bad("Only exact native curve_handle_drag is supported");
        String mode=step.optString("mode","");
        if(!"transform".equals(mode)&&!"shape".equals(mode))
            throw SketchDocument.bad("Choose transform or shape animation timing");
        boolean hasObject=step.has("objectId"),hasLayer=step.has("layerId");
        if(hasObject==hasLayer||("shape".equals(mode)&&hasLayer))
            throw SketchDocument.bad("Choose exactly one editable object or layer; shape curves require a path object");
        String id=SketchDocument.id(step,hasObject?"objectId":"layerId");
        JSONObject target,owner;
        if(hasObject){
            target=SketchDocument.object(document,id);
            owner=SketchDocument.owner(document,id);
            if(target.has("masterLink"))
                throw SketchDocument.bad("Cannot change linked master animation outside master editing");
        }else{
            target=SketchDocument.layer(document,id);
            owner=target;
        }
        SketchEditOps.requireUnlocked(owner);
        if(!owner.getBoolean("visible"))
            throw SketchDocument.bad("The authored layer must be visible for its timing graph");
        int frame=(int)SketchDocument.integer(step,"frameIndex",0,
                document.getInt("frameCount")-1);
        if(hasObject&&!SketchFrameRenderer.exposed(target,frame))
            throw SketchDocument.bad("Selected object is not exposed at its timing key");
        int index=(int)SketchDocument.integer(step,"handleIndex",0,1);
        double x=SketchDocument.number(step,"x",0,1);
        double y=SketchDocument.number(step,"y",0,1);
        int ms=step.has("durationMs")?(int)SketchDocument.integer(step,"durationMs",
                MIN_DURATION_MS,MAX_DURATION_MS):600;
        if("shape".equals(mode)&&!"path".equals(target.optString("kind","")))
            throw SketchDocument.bad("Shape timing handles require an editable Bézier path");
        JSONArray keys=target.optJSONArray("shape".equals(mode)?"shapeKeys":"keys");
        if(keys==null)
            throw SketchDocument.bad("There are no existing authored animation keys");
        JSONObject authored=null;
        for(int i=0;i<keys.length();i++){
            JSONObject candidate=keys.getJSONObject(i);
            if(candidate.getInt("frame")==frame){authored=candidate;break;}
        }
        if(authored==null||!"bezier".equals(authored.optString("interpolation",""))
                ||!(authored.opt("curve") instanceof JSONArray))
            throw SketchDocument.bad("Select a real saved Bézier key before dragging its timing handle");
        SketchEasing.validate(authored);
        JSONArray previous=authored.getJSONArray("curve");
        JSONArray next=new JSONArray(previous.toString());
        next.put(index*2,(Object)Double.valueOf(x));
        next.put(index*2+1,(Object)Double.valueOf(y));
        SketchEasing.validate(new JSONObject().put("interpolation","bezier").put("curve",next));
        if(Math.abs(previous.getDouble(index*2)-x)<0.000001&&
                Math.abs(previous.getDouble(index*2+1)-y)<0.000001)
            throw SketchDocument.bad("The Bézier handle is already in the requested position");

        JSONObject operation=new JSONObject().put("frame",frame)
                .put("interpolation","bezier").put("curve",new JSONArray(next.toString()));
        if("shape".equals(mode)){
            operation.put("type","set_shape_key").put("objectId",id)
                    .put("commands",new JSONArray(authored.getJSONArray("commands").toString()));
        }else{
            operation.put("type","set_keyframe")
                    .put(hasObject?"objectId":"layerId",id)
                    .put("transform",SketchDocument.copy(authored.getJSONObject("transform")));
        }
        // Admission proves the identical ordinary editor command actually passes
        // authoritative document validation, without touching the original.
        JSONObject candidate=SketchDocument.copy(document);
        SketchEditOps.apply(candidate,operation);
        return new Plan(document,mode,id,owner.getString("id"),frame,index,ms,
                previous,next,operation);
    }

    public static JSONObject capabilities()throws Exception {
        return new JSONObject().put("minimumNativeVersion","4.10.45")
                .put("action","curve_handle_drag")
                .put("stageAction","sketch_stage_execute")
                .put("types",new JSONArray().put("transform").put("shape"))
                .put("requiresRealExistingBezierKey",true)
                .put("handleIndexRange",new JSONArray().put(0).put(1))
                .put("handleCoordinates","normalized 0..1 original authored timing curve")
                .put("durationMinMs",MIN_DURATION_MS).put("durationMaxMs",MAX_DURATION_MS)
                .put("maximumSamples",MAX_VISUAL_SAMPLES)
                .put("singleRevisionGuardedEdit",true)
                .put("terminalStageStepOnly",true)
                .put("previewNotSavedUntilComplete",true)
                .put("requiresForegroundSketchStudio",true)
                .put("notScreenshotSubstitution",true)
                .put("galleryAccess",false);
    }
}
