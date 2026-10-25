package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pure, revision-pinned plan for ChatGPT's visibly performed native gestures.
 *
 * A gesture previews through SketchCanvasView's existing exact touch geometry,
 * but only SketchEditOps may persist it under the normal source mutation guard.
 * No injection of fake MotionEvents, Gallery access, screenshots or export.
 */
public final class SketchAgentGestureOps {
    public static final int MIN_DURATION_MS=120,MAX_DURATION_MS=1200;
    private static final String[] TRANSFORM_CHANNELS={
            "x","y","scaleX","scaleY","rotation","anchorX","anchorY","opacity"};
    private SketchAgentGestureOps(){}

    public static final class Plan {
        public final String action,objectId,layerId;
        public final int frameIndex,durationMs;
        public final JSONObject sourceTransform,targetTransform,editOperation,sourceObject;
        public final JSONArray sourceCommands;
        public final int commandIndex,pairIndex;
        public final double startX,startY,targetX,targetY;
        private final JSONObject drawing,owner;

        Plan(JSONObject drawing,JSONObject owner,JSONObject object,JSONObject edit,
             String action,String objectId,int frame,int duration,
             JSONObject before,JSONObject after,JSONArray commands,int commandIndex,int pairIndex,
             double x0,double y0,double x1,double y1)throws Exception{
            this.drawing=SketchDocument.copy(drawing);
            this.owner=SketchDocument.copy(owner);
            sourceObject=SketchDocument.copy(object);
            editOperation=SketchDocument.copy(edit);
            this.action=action;this.objectId=objectId;layerId=owner.getString("id");
            frameIndex=frame;durationMs=duration;
            sourceTransform=before==null?null:SketchDocument.copy(before);
            targetTransform=after==null?null:SketchDocument.copy(after);
            sourceCommands=commands==null?null:new JSONArray(commands.toString());
            this.commandIndex=commandIndex;this.pairIndex=pairIndex;
            startX=x0;startY=y0;targetX=x1;targetY=y1;
        }
        public boolean editingNode(){return "node_drag".equals(action);}
        public JSONObject previewTransform(float fraction)throws Exception{
            if(editingNode())throw SketchDocument.bad("Path knot uses a geometry preview");
            double p=progress(fraction);
            JSONObject merged=new JSONObject();
            for(String channel:TRANSFORM_CHANNELS){
                double a=sourceTransform.getDouble(channel),b=targetTransform.getDouble(channel);
                merged.put(channel,a+(b-a)*p);
            }
            SketchDocument.validateTransform(merged);
            return merged;
        }
        /** Only a detached sample; never replaces the source object. */
        public JSONObject previewNode(float fraction)throws Exception {
            if(!editingNode())throw SketchDocument.bad("Transform gesture has no path node");
            JSONObject object=SketchShapeKeys.sampled(sourceObject,frameIndex);
            JSONArray commands=new JSONArray(sourceCommands.toString());
            JSONArray part=commands.getJSONArray(commandIndex);
            int slot=1+pairIndex*2;
            double p=progress(fraction);
            part.put(slot,startX+(targetX-startX)*p);
            part.put(slot+1,startY+(targetY-startY)*p);
            object.put("commands",commands);
            return object;
        }
        /** True canvas point after layer/object transforms, not guessed UI coordinates. */
        public double[] cursor(float fraction)throws Exception{
            double px,py;
            if(editingNode()){
                double p=progress(fraction);
                px=startX+(targetX-startX)*p;
                py=startY+(targetY-startY)*p;
            }else{
                double[] center=objectCenter(sourceObject,frameIndex);
                px=center[0];py=center[1];
            }
            int width=drawing.getInt("width"),height=drawing.getInt("height");
            JSONObject objectT=editingNode()?SketchDocument.transformAt(sourceObject,frameIndex):
                    previewTransform(fraction);
            JSONObject layerT=SketchDocument.transformAt(owner,frameIndex);
            Matrix matrix=new Matrix();
            matrix.setConcat(SketchFrameRenderer.matrix(layerT,width,height),
                    SketchFrameRenderer.matrix(objectT,width,height));
            float[] point={(float)(px*width),(float)(py*height)};
            matrix.mapPoints(point);
            return new double[]{point[0]/width,point[1]/height};
        }
        private static double progress(float f){
            double t=Math.max(0,Math.min(1,f));
            return t*t*(3-2*t);
        }
    }

    public static JSONObject capabilities()throws Exception{
        return new JSONObject().put("schema","videostudio.sketch-agent-gesture.v1")
                .put("minimumNativeVersion","4.10.43")
                .put("stageAction","sketch_stage_execute")
                .put("steps",new JSONArray().put("object_drag").put("node_drag"))
                .put("terminalStageStepOnly",true)
                .put("preview","Real selected vector + native geometry and tool cursor")
                .put("persistence","Single normal revision-guarded sketch_edit mutation")
                .put("supportsAnimatedObjectFrames",true)
                .put("pathNodeCoordinates","Normalized local path [0,1]")
                .put("transformCoordinates","Original object translation [-4,4] in canvas units")
                .put("durationMinMs",MIN_DURATION_MS).put("durationMaxMs",MAX_DURATION_MS)
                .put("exactOriginalSourcePreserved",true)
                .put("notExternalTouchInjection",true)
                .put("galleryAccess",false);
    }

    /** Plan exactly one final foreground gesture, without modifying artwork. */
    public static Plan plan(JSONObject drawing,JSONObject step)throws Exception {
        if(drawing==null||step==null)
            throw SketchDocument.bad("Native sketch and agent gesture are required");
        String action=step.optString("action","");
        if(!"object_drag".equals(action)&&!"node_drag".equals(action))
            throw SketchDocument.bad("Only object_drag and node_drag can mutate through gesture stage");
        String id=SketchDocument.id(step,"objectId");
        JSONObject object=SketchDocument.object(drawing,id);
        JSONObject owner=SketchDocument.owner(drawing,id);
        SketchEditOps.requireUnlocked(owner);
        if(!owner.getBoolean("visible"))
            throw SketchDocument.bad("Reveal the real source layer before visibly editing it");
        if(object.has("masterLink"))
            throw SketchDocument.bad("Cannot directly alter linked master geometry");
        int frame=(int)SketchDocument.integer(step,"frameIndex",0,drawing.getInt("frameCount")-1);
        if(!SketchFrameRenderer.exposed(object,frame))
            throw SketchDocument.bad("Selected object is not exposed at requested native frame");
        int ms=step.has("durationMs")?(int)SketchDocument.integer(step,"durationMs",
                MIN_DURATION_MS,MAX_DURATION_MS):420;
        if("object_drag".equals(action)){
            SketchDocument.fields(step,
                    "action,objectId,frameIndex,durationMs,x,y,scaleX,scaleY,rotation,anchorX,anchorY,opacity,interpolation,curve");
            JSONObject before=SketchDocument.transformAt(object,frame);
            JSONObject target=SketchDocument.copy(before);
            int edited=0;
            for(String field:TRANSFORM_CHANNELS){
                if(step.has(field)){
                    if(!(step.opt(field) instanceof Number))
                        throw SketchDocument.bad("Transform channel must be numeric: "+field);
                    target.put(field,step.getDouble(field));edited++;
                }
            }
            if(edited==0)throw SketchDocument.bad("Move, rotate, resize or fade at least one channel");
            SketchDocument.validateTransform(target);
            boolean changed=false;
            for(String field:TRANSFORM_CHANNELS)
                if(Math.abs(before.getDouble(field)-target.getDouble(field))>1e-8)changed=true;
            if(!changed)throw SketchDocument.bad("Object already has the requested transform");
            JSONArray sourceKeys=object.getJSONArray("keys");
            boolean keyed=sourceKeys.length()>0||step.has("interpolation")||step.has("curve");
            JSONObject operation=new JSONObject().put("objectId",id)
                    .put("type",keyed?"set_keyframe":"set_transform")
                    .put("transform",SketchDocument.copy(target));
            if(keyed){
                String timing=step.optString("interpolation","linear");
                JSONObject sample=new JSONObject().put("interpolation",timing);
                if(step.has("curve")){
                    if(!(step.opt("curve") instanceof JSONArray))
                        throw SketchDocument.bad("Native animation Bézier curve must be numeric array");
                    sample.put("curve",new JSONArray(step.getJSONArray("curve").toString()));
                }
                SketchEasing.validate(sample);
                operation.put("frame",frame).put("interpolation",timing);
                if(sample.has("curve"))operation.put("curve",sample.getJSONArray("curve"));
            }
            return new Plan(drawing,owner,object,operation,action,id,frame,ms,before,target,
                    null,-1,-1,0,0,0,0);
        }
        SketchDocument.fields(step,
                "action,objectId,frameIndex,durationMs,commandIndex,pairIndex,x,y");
        JSONObject nodeOp=new JSONObject().put("type","move_path_node")
                .put("objectId",id).put("frameIndex",frame)
                .put("commandIndex",SketchDocument.integer(step,"commandIndex",0,SketchDocument.MAX_POINTS))
                .put("pairIndex",SketchDocument.integer(step,"pairIndex",0,2))
                .put("x",SketchDocument.number(step,"x",0,1))
                .put("y",SketchDocument.number(step,"y",0,1));
        JSONObject validated=SketchPathNodeOps.plan(drawing,nodeOp);
        JSONArray commands=SketchShapeKeys.commandsAt(object,frame);
        return new Plan(drawing,owner,object,nodeOp,action,id,frame,ms,
                null,null,commands,validated.getInt("commandIndex"),validated.getInt("pairIndex"),
                validated.getDouble("sourceX"),validated.getDouble("sourceY"),
                validated.getDouble("targetX"),validated.getDouble("targetY"));
    }

    public static String deterministicEditId(JSONObject stageClaim)throws Exception {
        String stage=SketchDocument.id(stageClaim,"operationId");
        String id=SketchDocument.id(stageClaim,"sketchId");
        int index=(int)SketchDocument.integer(stageClaim,"stepIndex",0,
                SketchAgentStageCommands.MAX_STEPS-1);
        return UUID.nameUUIDFromBytes(("sketch-actual-gesture/"+id+"/"+stage+"/"+index)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static double[] objectCenter(JSONObject source,int frame)throws Exception {
        JSONObject sampled=SketchShapeKeys.sampled(source,frame);
        if(sampled.has("bounds")){
            JSONObject bounds=sampled.getJSONObject("bounds");
            return new double[]{bounds.getDouble("x")+bounds.getDouble("width")*.5,
                    bounds.getDouble("y")+bounds.getDouble("height")*.5};
        }
        JSONArray geometry=sampled.optJSONArray("commands");
        boolean commands=geometry!=null;
        if(!commands)geometry=sampled.optJSONArray("points");
        if(geometry==null||geometry.length()==0)return new double[]{.5,.5};
        double lx=Double.POSITIVE_INFINITY,ly=Double.POSITIVE_INFINITY;
        double rx=Double.NEGATIVE_INFINITY,ry=Double.NEGATIVE_INFINITY;
        for(int i=0;i<geometry.length();i++){
            JSONArray item=geometry.getJSONArray(i);
            int start=commands?1:0;
            for(int p=start;p+1<item.length();p+=2){
                if(!(item.opt(p) instanceof Number)||!(item.opt(p+1) instanceof Number))continue;
                double x=item.getDouble(p),y=item.getDouble(p+1);
                lx=Math.min(lx,x);rx=Math.max(rx,x);ly=Math.min(ly,y);ry=Math.max(ry,y);
            }
        }
        if(!Double.isFinite(lx))return new double[]{.5,.5};
        return new double[]{(lx+rx)*.5,(ly+ry)*.5};
    }
}
