package com.rezoxnemesis.videostudio;

import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OMEGA Phase 5 is now usable from revision-bound Sketch Studio edits.
 *
 * Generates genuine object transform keys (not camera-only motion), with
 * authored anticipation, drive, recoil, overlap and recovery. The existing
 * SketchFrameRenderer draws those keys using the same document/human editor.
 * This bridge does not infer body joints or invent backside geometry.
 */
final class SketchNaturalMotionBridge {
    private static final int MAX_NEW_KEYS=192;
    private SketchNaturalMotionBridge(){}

    static void apply(JSONObject doc,JSONObject operation)throws Exception{
        if(doc==null||operation==null)
            throw new IllegalArgumentException("An authored sketch and motion operation are required");
        SketchDocument.fields(operation,"type,objectId,startFrame,x,y,rotation,"
                +"anticipationFrames,driveFrames,recoilFrames,recoveryFrames,"
                +"windup,recoil,sink,arc,lagAngle,lagCycles,lagDamping,"
                +"sampleEvery,replaceExistingKeys,extendExposure");
        if(!"apply_acting_motion".equals(operation.getString("type")))
            throw new IllegalArgumentException("Only explicit subject acting is supported");
        String objectId=SketchDocument.id(operation,"objectId");
        JSONObject layer=SketchDocument.owner(doc,objectId);
        SketchEditOps.requireUnlocked(layer);
        JSONObject item=SketchDocument.object(doc,objectId);
        if(!item.has("transform")||!item.has("keys"))
            throw new IllegalArgumentException("Motion target needs editable transform keys");
        int start=(int)SketchDocument.integer(operation,"startFrame",0,doc.getInt("frameCount")-1);
        int anticipation=(int)SketchDocument.integer(operation,"anticipationFrames",2,300);
        int drive=(int)SketchDocument.integer(operation,"driveFrames",2,300);
        int recoilFrames=(int)SketchDocument.integer(operation,"recoilFrames",2,300);
        int recovery=(int)SketchDocument.integer(operation,"recoveryFrames",2,300);
        int sampleEvery=operation.has("sampleEvery")?
                (int)SketchDocument.integer(operation,"sampleEvery",1,12):2;
        long length=(long)anticipation+drive+recoilFrames+recovery;
        if(length>=doc.getInt("frameCount")||(long)start+length>=doc.getInt("frameCount")
                ||(long)start+length>=1200)
            throw new IllegalArgumentException("Extend the sketch timeline for this bounded acting clip");
        int end=(int)(start+length);
        int originalFrame=item.getInt("frame");
        int originalExposure=item.getInt("exposure");
        if(start<originalFrame)
            throw new IllegalArgumentException("The object is not exposed at the requested start frame");
        boolean extend=flag(operation,"extendExposure",false);
        boolean existingEnd=(long)originalFrame+originalExposure>end;
        if(!existingEnd&&!extend)
            throw new IllegalArgumentException("Extend the object's cel exposure explicitly to animate this interval");
        if(extend&&item.has("masterLink")){
            // Exposure changes are user-opt-in even for linked art, but geometry
            // and original master references remain untouched.
            if(end>=doc.getInt("frameCount"))
                throw new IllegalArgumentException("Animation exposure exceeds the sketch");
        }
        JSONObject from=SketchDocument.transformAt(item,start);
        double x=SketchDocument.number(operation,"x",-4,4);
        double y=SketchDocument.number(operation,"y",-4,4);
        double rotation=SketchDocument.number(operation,"rotation",-720,720);
        OmegaMotionTimeline.Pose initial=new OmegaMotionTimeline.Pose(
                from.getDouble("x"),from.getDouble("y"),from.getDouble("rotation"));
        OmegaMotionTimeline.Pose target=new OmegaMotionTimeline.Pose(x,y,rotation);
        double windup=optional(operation,"windup",.14,0,.5);
        double recoil=optional(operation,"recoil",.10,0,.5);
        double sink=optional(operation,"sink",.03,0,.5);
        double arc=optional(operation,"arc",.04,0,.5);
        double lagAngle=optional(operation,"lagAngle",6,-90,90);
        double lagCycles=optional(operation,"lagCycles",.07,0,.5);
        double lagDamping=optional(operation,"lagDamping",.12,0,2);
        OmegaMotionTimeline.ActingSpec spec=new OmegaMotionTimeline.ActingSpec(
                doc.getInt("fps"),start,anticipation,drive,recoilFrames,recovery,
                initial,target,windup,recoil,sink,arc,lagAngle,lagCycles,lagDamping);
        OmegaMotionTimeline.ActingClip clip=new OmegaMotionTimeline.ActingClip(spec);
        int estimatedKeys=(end-start)/sampleEvery+5;
        if(estimatedKeys>MAX_NEW_KEYS)
            throw new IllegalArgumentException("Motion samples exceed 192 editable keys; use a larger sampleEvery");
        JSONArray existing=item.getJSONArray("keys");
        boolean overwrite=flag(operation,"replaceExistingKeys",false);
        TreeMap<Integer,JSONObject> newKeys=new TreeMap<>();
        for(int i=0;i<existing.length();i++){
            JSONObject key=existing.getJSONObject(i);
            int frame=key.getInt("frame");
            if(frame>=start&&frame<=end){
                if(!overwrite)throw new IllegalStateException(
                        "Existing acting key at frame "+frame+"; explicitly approve replacement");
            }else newKeys.put(frame,SketchDocument.copy(key));
        }
        // Beat boundaries make physical anticipation and impact deterministic.
        java.util.TreeSet<Integer> frames=new java.util.TreeSet<>();
        for(int frame=start;frame<=end;frame+=sampleEvery)frames.add(frame);
        frames.add(start);frames.add(spec.windupEnd);frames.add(spec.impactFrame);
        frames.add(spec.recoilEnd);frames.add(end);
        if(frames.size()>MAX_NEW_KEYS)
            throw new IllegalArgumentException("Too many authored motion samples");
        for(int frame:frames){
            OmegaMotionTimeline.Sample pose=clip.sample(frame);
            JSONObject transform=SketchDocument.copy(from);
            transform.put("x",pose.root.x).put("y",pose.root.y)
                    .put("rotation",pose.root.angle+pose.followThroughAngle);
            SketchDocument.validateTransform(transform);
            JSONObject key=new JSONObject().put("frame",frame)
                    .put("transform",transform).put("interpolation","linear");
            newKeys.put(frame,key);
        }
        if(newKeys.size()>4096)
            throw new IllegalArgumentException("Document transform-key budget exceeded");
        JSONArray committed=new JSONArray();
        for(Map.Entry<Integer,JSONObject> key:newKeys.entrySet())committed.put(key.getValue());
        item.put("keys",committed);
        if(extend&&!existingEnd)item.put("exposure",end-originalFrame+1);
    }

    private static boolean flag(JSONObject op,String field,boolean fallback)throws Exception{
        if(!op.has(field))return fallback;
        Object raw=op.get(field);
        if(!(raw instanceof Boolean))throw new IllegalArgumentException(field+" must be boolean");
        return (Boolean)raw;
    }
    private static double optional(JSONObject op,String field,double fallback,double min,double max)
            throws Exception {
        return op.has(field)?SketchDocument.number(op,field,min,max):fallback;
    }
}
