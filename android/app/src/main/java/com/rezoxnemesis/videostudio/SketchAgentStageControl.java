package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * A closed, native-only, user-visible workspace controller for the private
 * VideoStudio MCP. Changes ONLY camera/pane/tool/frame selection, never source
 * files, pixels, project revisions, or Gallery permissions.
 *
 * The user explicitly enters Sketch Studio's "Watch ChatGPT" mode to consume
 * its small durable stage cue sequence. Execution and acknowledgements remain
 * on the Android UI thread; the remote response means staged, NOT performed.
 */
public final class SketchAgentStageControl {
    private static final String PREFS="videostudio_sketch_agent_stage_v1";
    private static final int MAX_STEPS=16,MAX_JSON_BYTES=8192;
    private static final long VALID_MS=180_000L;
    private static final Set<String> TOOLS=Set.of("stroke","eraser","line","rect",
            "ellipse","polygon","select","eyedropper","pan","curve",
            "nodes","precision","precision_brush");
    private static final Set<String> TYPES=Set.of("tool","frame","layer",
            "object","viewport","onion");
    private SketchAgentStageControl(){}

    public static JSONObject capabilities()throws Exception{
        return new JSONObject().put("schema","videostudio.sketch.agent-stage.v1")
                .put("minimumNativeVersion","4.10.32")
                .put("stageAction","sketch_agent_stage")
                .put("statusAction","sketch_agent_stage_status")
                .put("maxSteps",MAX_STEPS)
                .put("types",new JSONArray().put("tool").put("frame").put("layer")
                        .put("object").put("viewport").put("onion"))
                .put("toolChoices",new JSONArray(TOOLS.toArray(new String[0])))
                .put("cameraZoom",new JSONArray().put(1).put(12))
                .put("requiresVisibleSketchStudioFollowMode",true)
                .put("userCanTakeOverAtAnyTime",true)
                .put("sourceAndGalleryUnchanged",true)
                .put("stageReceiptIsNotExecutionProof",true)
                .put("drawingOperationAction","sketch_edit")
                .put("actualDrawingWatchAction","sketch_live_events");
    }

    public static synchronized JSONObject stage(Context context,JSONObject request)throws Exception{
        if(context==null||request==null)throw SketchDocument.bad("A native sketch stage request is required");
        SketchDocument.fields(request,"sketchId,operationId,expectedRevision,steps");
        String id=SketchDocument.id(request,"sketchId"),operation=SketchDocument.id(request,"operationId");
        long revision=SketchDocument.integer(request,"expectedRevision",1,Long.MAX_VALUE-1);
        JSONArray steps=request.getJSONArray("steps");
        if(steps.length()<1||steps.length()>MAX_STEPS)
            throw SketchDocument.bad("Use 1..16 actual workspace actions");
        JSONObject doc;
        try(SketchProjectStore store=new SketchProjectStore(context)){
            doc=store.get(id);
            if(doc.getLong("revision")!=revision)
                throw new IllegalStateException("SKETCH_SOURCE_CHANGED: use current revision");
        }
        JSONArray validated=new JSONArray();
        for(int i=0;i<steps.length();i++){
            Object raw=steps.opt(i);
            if(!(raw instanceof JSONObject))throw SketchDocument.bad("Each workspace step must be an object");
            validated.put(validate(doc,(JSONObject)raw));
        }
        String canonical=SketchDocument.canonical(validated);
        if(canonical.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES)
            throw SketchDocument.bad("Workspace program exceeds 8 KiB");
        String digest=SketchDocument.sha256((id+":"+revision+":"+canonical).getBytes(StandardCharsets.UTF_8));
        SharedPreferences prefs=prefs(context);
        String oldOperation=prefs.getString("operationId","");
        long oldSeq=prefs.getLong("sequence",0);
        int cursor=prefs.getInt("cursor",0);
        int oldCount=prefs.getInt("count",0);
        boolean recent=fresh(prefs);
        if(id.equals(prefs.getString("sketchId",""))&&operation.equals(oldOperation)){
            if(!digest.equals(prefs.getString("digest","")))
                throw new IllegalStateException("SKETCH_STAGE_OPERATION_CONFLICT");
            return receipt(prefs,true);
        }
        if(recent&&cursor<oldCount)
            throw new IllegalStateException("SKETCH_STAGE_BUSY: finish or expire current visible program");
        long next=oldSeq+1;
        if(next<=0)throw new IllegalStateException("Sketch stage sequence exhausted");
        if(!prefs.edit().putString("sketchId",id)
                .putString("operationId",operation).putString("digest",digest)
                .putLong("expectedRevision",revision).putLong("sequence",next)
                .putLong("issuedAt",System.currentTimeMillis())
                .putString("steps",validated.toString()).putInt("count",validated.length())
                .putInt("cursor",0).commit())
            throw new IllegalStateException("Native Sketch stage journal could not persist");
        return receipt(prefs,false);
    }

    public static synchronized JSONObject status(Context context,JSONObject request)throws Exception{
        SketchDocument.fields(request,"sketchId");
        String id=SketchDocument.id(request,"sketchId");
        SharedPreferences p=prefs(context);
        if(!id.equals(p.getString("sketchId","")))
            return new JSONObject().put("ok",true).put("sketchId",id)
                    .put("status","no_staged_control").put("executedSteps",0);
        return receipt(p,true);
    }

    /** Returns one pending step; called only by the open visible Sketch editor. */
    public static synchronized JSONObject nextVisibleStep(Context context,String sketchId,
                                                           long documentRevision)throws Exception{
        SharedPreferences p=prefs(context);
        if(!fresh(p)||!sketchId.equals(p.getString("sketchId",""))
                ||documentRevision!=p.getLong("expectedRevision",-1))return null;
        int cursor=p.getInt("cursor",0),count=p.getInt("count",0);
        if(cursor<0||cursor>=count)return null;
        JSONArray steps=new JSONArray(p.getString("steps","[]"));
        return new JSONObject().put("sequence",p.getLong("sequence",0))
                .put("index",cursor).put("operationId",p.getString("operationId",""))
                .put("step",steps.getJSONObject(cursor))
                .put("total",count);
    }

    /** Only after a real UI control accepted the step, not after queue delivery. */
    public static synchronized void acknowledge(Context context,String sketchId,long revision,
                                                long sequence,int index)throws Exception {
        SharedPreferences p=prefs(context);
        if(!fresh(p)||!sketchId.equals(p.getString("sketchId",""))
                ||p.getLong("expectedRevision",-1)!=revision
                ||p.getLong("sequence",-1)!=sequence
                ||p.getInt("cursor",-1)!=index)
            throw new IllegalStateException("SKETCH_STAGE_CURSOR_CHANGED");
        if(!p.edit().putInt("cursor",index+1).commit())
            throw new IllegalStateException("Could not record applied Sketch control");
    }

    private static JSONObject validate(JSONObject doc,JSONObject step)throws Exception{
        String type=step.optString("type","");
        if(!TYPES.contains(type))throw SketchDocument.bad("Unknown native Sketch viewport tool");
        JSONObject clean=new JSONObject().put("type",type);
        switch(type){
            case "tool":{
                SketchDocument.fields(step,"type,tool");
                String tool=step.getString("tool");
                if(!TOOLS.contains(tool))throw SketchDocument.bad("Use an implemented native Sketch drawing tool");
                clean.put("tool",tool);break;
            }
            case "frame":{
                SketchDocument.fields(step,"type,frame");
                clean.put("frame",SketchDocument.integer(step,"frame",0,doc.getInt("frameCount")-1));
                break;
            }
            case "layer":{
                SketchDocument.fields(step,"type,layerId");
                String layer=SketchDocument.id(step,"layerId");
                SketchDocument.layer(doc,layer);clean.put("layerId",layer);break;
            }
            case "object":{
                SketchDocument.fields(step,"type,objectId");
                String object=SketchDocument.id(step,"objectId");
                SketchDocument.object(doc,object);clean.put("objectId",object);break;
            }
            case "viewport":{
                SketchDocument.fields(step,"type,x,y,zoom");
                clean.put("x",SketchDocument.number(step,"x",0,1))
                    .put("y",SketchDocument.number(step,"y",0,1))
                    .put("zoom",SketchDocument.number(step,"zoom",1,12));break;
            }
            case "onion":{
                SketchDocument.fields(step,"type,enabled,range,alpha");
                SketchDocument.bool(step,"enabled");
                clean.put("enabled",step.getBoolean("enabled"));
                clean.put("range",step.has("range")
                        ?SketchDocument.integer(step,"range",1,3):1);
                clean.put("alpha",step.has("alpha")
                        ?SketchDocument.integer(step,"alpha",0,128):48);
                break;
            }
            default:throw SketchDocument.bad("Unsupported native Sketch control");
        }
        return clean;
    }

    private static JSONObject receipt(SharedPreferences prefs,boolean existing)throws Exception{
        int count=prefs.getInt("count",0),cursor=prefs.getInt("cursor",0);
        String state=!fresh(prefs)?"expired":cursor>=count?"applied_by_visible_editor":"queued_for_visible_editor";
        return new JSONObject().put("ok",true)
                .put("schema","videostudio.sketch.agent-stage-receipt.v1")
                .put("sketchId",prefs.getString("sketchId",""))
                .put("operationId",prefs.getString("operationId",""))
                .put("sequence",prefs.getLong("sequence",0))
                .put("expectedRevision",prefs.getLong("expectedRevision",0))
                .put("status",state).put("stepCount",count).put("executedSteps",cursor)
                .put("reused",existing).put("documentModified",false)
                .put("galleryAccess",false).put("rendered",false)
                .put("requiresVisibleSketchStudio",true);
    }

    private static SharedPreferences prefs(Context context){
        return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    private static boolean fresh(SharedPreferences p){
        long issued=p.getLong("issuedAt",0),now=System.currentTimeMillis();
        return issued>0&&now>=issued&&now-issued<=VALID_MS;
    }
}
