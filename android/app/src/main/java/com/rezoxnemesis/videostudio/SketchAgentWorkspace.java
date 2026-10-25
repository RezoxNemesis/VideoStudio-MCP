package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * Native, owner-only human-style controls for Sketch Studio.
 *
 * A native MCP request queues INTENT, not a fake tap or a claim that a sketch
 * has changed. The visible Android activity consumes one bounded command
 * at a time on its UI thread, updates the real canvas/tool/timeline, and ACKs
 * only after the action actually happened. All source editing still goes through
 * ordinary sketch_edit and the real live drawing journal.
 */
public final class SketchAgentWorkspace {
    private static final String PREF="sketch_agent_workspace_v41032";
    private static final String VISIBLE="visible_sketch";
    private static final String VISIBLE_AT="visible_at";
    private static final String PAUSE="owner_paused_";
    private static final String PREFIX="queue_";
    private static final int MAX_ACTIONS=24;
    private static final long TTL_DEFAULT=60*60*1000L;
    private static final Set<String> TOOLS=Set.of(
        "stroke","eraser","line","rect","ellipse","polygon","select",
        "eyedropper","pan","curve","nodes","paint","paint_erase","clone",
        "precision","precision_brush");
    private static final Set<String> TYPES=Set.of(
        "seek_frame","select_layer","select_tool","select_object","focus_object",
        "toggle_onion","set_guides","reset_view","set_brush","set_playback","show_curve");
    private SketchAgentWorkspace(){}

    public static JSONObject capabilities()throws Exception{
        return new JSONObject().put("schema","videostudio.sketch.agent-workspace.v1")
                .put("minimumNativeVersion","4.10.32")
                .put("actions",new JSONArray(Arrays.asList(
                        "seek_frame","select_layer","select_tool","select_object",
                        "focus_object","toggle_onion","set_guides","reset_view",
                        "set_brush","set_playback","show_curve")))
                .put("maximumUiSteps",MAX_ACTIONS)
                .put("maximumQueuedDurationMs",24L*60*60*1000)
                .put("drawAction","sketch_edit")
                .put("authoringUsesActualSketchEditor",true)
                .put("liveDrawingUsesRealStrokePrefixes",true)
                .put("physicalTouchInjection",false)
                .put("stateChangesAcknowledgedByVisibleApp",true)
                .put("previewVerifiedByUiAction",false)
                .put("galleryAccess",false)
                .put("ownerCanPause",true)
                .put("revisionsCheckedOnDelivery",true);
    }

    public static synchronized JSONObject plan(Context context,JSONObject args)throws Exception {
        Validated v=validate(context,args);
        return new JSONObject().put("ok",true).put("dryRun",true)
                .put("sketchId",v.sketchId).put("expectedRevision",v.revision)
                .put("stepCount",v.actions.length()).put("actions",v.actions)
                .put("willChangeDocument",false).put("previewVerified",false)
                .put("galleryAccess",false);
    }

    public static synchronized JSONObject enqueue(Context context,JSONObject args)throws Exception{
        Validated v=validate(context,args);
        String operationId=id(args,"operationId");
        if(!operationId.matches("[A-Za-z0-9_-]{8,64}"))
            throw SketchDocument.bad("A stable 8..64-character operation ID is required");
        long ttl=args.has("ttlMs")?SketchDocument.integer(args,"ttlMs",5000,86_400_000):TTL_DEFAULT;
        JSONObject existing=read(context,v.sketchId);
        String fingerprint=SketchDocument.sha256(SketchDocument.canonical(
                new JSONObject().put("actions",v.actions)
                        .put("expectedRevision",v.revision)
                        .put("sketchId",v.sketchId)).getBytes(StandardCharsets.UTF_8));
        if(existing!=null){
            if(operationId.equals(existing.optString("operationId"))){
                if(!fingerprint.equals(existing.optString("fingerprint")))
                    throw new IllegalStateException("SKETCH_UI_OPERATION_CONFLICT: operationId has a different plan");
                return receipt(context,existing,true);
            }
            if("pending".equals(existing.optString("status"))
                    &&existing.optLong("expiresAtMs",0)>System.currentTimeMillis())
                throw new IllegalStateException(
                        "SKETCH_UI_BUSY: finish or cancel the previous visible UI steps before replacing them");
        }
        long now=System.currentTimeMillis();
        JSONObject record=new JSONObject()
                .put("schema","videostudio.sketch.agent-workspace.v1")
                .put("sketchId",v.sketchId).put("operationId",operationId)
                .put("expectedRevision",v.revision).put("fingerprint",fingerprint)
                .put("actions",v.actions).put("cursor",0).put("status","pending")
                .put("createdAtMs",now).put("expiresAtMs",now+ttl)
                .put("updatedAtMs",now)
                .put("lastApplied",new JSONObject());
        write(context,v.sketchId,record);
        return receipt(context,record,false);
    }

    public static synchronized JSONObject status(Context context,JSONObject args)throws Exception{
        SketchDocument.fields(args,"sketchId,operationId");
        String id=SketchDocument.id(args,"sketchId");
        JSONObject record=read(context,id);
        if(record==null)return new JSONObject().put("ok",true)
                .put("sketchId",id).put("status","not_queued").put("applied",false);
        if(args.has("operationId")&&!id(args,"operationId").equals(record.optString("operationId")))
            throw new IllegalArgumentException("Requested operationId is not the current workspace command");
        expireIfNeeded(context,id,record);
        return receipt(context,record,true);
    }

    public static synchronized JSONObject cancel(Context context,JSONObject args)throws Exception{
        SketchDocument.fields(args,"sketchId,operationId");
        String id=SketchDocument.id(args,"sketchId"),operation=id(args,"operationId");
        JSONObject record=read(context,id);
        if(record==null||!operation.equals(record.optString("operationId")))
            throw new IllegalArgumentException("Unknown workspace operation");
        if("pending".equals(record.optString("status"))){
            record.put("status","cancelled").put("updatedAtMs",System.currentTimeMillis());
            write(context,id,record);
        }
        return receipt(context,record,true);
    }

    public static synchronized void visible(Context context,String sketchId,boolean shown){
        if(context==null)return;
        SharedPreferences prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        if(shown){
            prefs.edit().putString(VISIBLE,sketchId==null?"":sketchId)
                    .putLong(VISIBLE_AT,System.currentTimeMillis()).apply();
        }else if(sketchId==null||sketchId.equals(prefs.getString(VISIBLE,""))){
            prefs.edit().remove(VISIBLE).remove(VISIBLE_AT).apply();
        }
    }

    public static synchronized void ownerPause(Context context,String sketchId,boolean paused){
        if(context==null||sketchId==null)return;
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .edit().putBoolean(PAUSE+sketchId,paused).apply();
    }
    public static boolean ownerPaused(Context context,String sketchId){
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .getBoolean(PAUSE+sketchId,false);
    }

    /**
     * Called by visible Sketch Studio, NOT by ChatGPT. Never return a step from
     * a changed project revision, an expired queue or a paused human takeover.
     */
    public static synchronized JSONObject nextForUi(Context context,String sketchId,long revision)
            throws Exception {
        if(sketchId==null||ownerPaused(context,sketchId))return null;
        JSONObject record=read(context,sketchId);
        if(record==null||!"pending".equals(record.optString("status")))return null;
        if(expireIfNeeded(context,sketchId,record))return null;
        if(revision!=record.getLong("expectedRevision")){
            record.put("status","blocked_revision").put("updatedAtMs",System.currentTimeMillis());
            write(context,sketchId,record);
            return null;
        }
        int index=record.getInt("cursor");
        JSONArray steps=record.getJSONArray("actions");
        if(index<0||index>=steps.length())throw new IllegalStateException("Corrupted UI action cursor");
        return new JSONObject().put("sketchId",sketchId)
                .put("operationId",record.getString("operationId"))
                .put("stepIndex",index).put("stepCount",steps.length())
                .put("expectedRevision",revision)
                .put("step",new JSONObject(steps.getJSONObject(index).toString()));
    }

    /**
     * ACK from actual main-thread canvas update. It describes state changes,
     * never rendered pixels, a drawn stroke or a successful export.
     */
    public static synchronized JSONObject acknowledged(Context context,String sketchId,
                    String operationId,int stepIndex,JSONObject displayed)throws Exception {
        JSONObject record=read(context,sketchId);
        if(record==null||!"pending".equals(record.optString("status"))
                ||!operationId.equals(record.optString("operationId")))
            throw new IllegalStateException("Sketch UI command is no longer pending");
        if(record.getInt("cursor")!=stepIndex)
            throw new IllegalStateException("Out-of-order visible editor acknowledgement");
        if(displayed==null||displayed.toString().length()>1024)
            throw new IllegalArgumentException("Bounded real UI selection required");
        int cursor=stepIndex+1;
        record.put("cursor",cursor)
                .put("status",cursor>=record.getJSONArray("actions").length()?"applied":"pending")
                .put("updatedAtMs",System.currentTimeMillis())
                .put("lastApplied",new JSONObject(displayed.toString()));
        write(context,sketchId,record);
        return receipt(context,record,false);
    }

    public static synchronized void failed(Context context,String sketchId,String operationId,
                                           String safeError)throws Exception{
        JSONObject record=read(context,sketchId);
        if(record==null||!operationId.equals(record.optString("operationId")))return;
        if(!"pending".equals(record.optString("status")))return;
        record.put("status","failed").put("updatedAtMs",System.currentTimeMillis())
                .put("error",safeError==null?"UI action was not applied":
                        safeError.substring(0,Math.min(180,safeError.length())));
        write(context,sketchId,record);
    }

    private static boolean expireIfNeeded(Context c,String id,JSONObject record)throws Exception{
        if("pending".equals(record.optString("status"))
                &&System.currentTimeMillis()>record.optLong("expiresAtMs",0)){
            record.put("status","expired").put("updatedAtMs",System.currentTimeMillis());
            write(c,id,record);
            return true;
        }
        return false;
    }

    private static JSONObject receipt(Context c,JSONObject r,boolean repeated)throws Exception{
        int done=r.optInt("cursor",0),count=r.getJSONArray("actions").length();
        SharedPreferences prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        boolean visible=r.optString("sketchId").equals(prefs.getString(VISIBLE,""))
                &&System.currentTimeMillis()-prefs.getLong(VISIBLE_AT,0)<4000;
        return new JSONObject().put("ok",true)
                .put("schema","videostudio.sketch.agent-workspace.receipt.v1")
                .put("sketchId",r.getString("sketchId"))
                .put("operationId",r.getString("operationId"))
                .put("expectedRevision",r.getLong("expectedRevision"))
                .put("status",r.getString("status"))
                .put("queuedSteps",count).put("uiAppliedSteps",done)
                .put("visibleSketchStudio",visible)
                .put("pausedByOwner",ownerPaused(c,r.getString("sketchId")))
                .put("applied","applied".equals(r.getString("status")))
                .put("sourceDrawingUnchanged",true)
                .put("previewVerified",false)
                .put("lastApplied",r.optJSONObject("lastApplied"))
                .put("error",r.optString("error",""))
                .put("operationReplayed",repeated)
                .put("actualToolAndFrameUpdatesOnlyAfterUiAck",true)
                .put("galleryAccess",false);
    }

    private static final class Validated{
        final String sketchId;final long revision;final JSONArray actions;
        Validated(String sketchId,long revision,JSONArray actions){
            this.sketchId=sketchId;this.revision=revision;this.actions=actions;
        }
    }

    private static Validated validate(Context context,JSONObject request)throws Exception{
        if(context==null||request==null)
            throw new IllegalArgumentException("Sketch workspace request required");
        SketchDocument.fields(request,"sketchId,operationId,expectedRevision,actions,ttlMs");
        String id=SketchDocument.id(request,"sketchId");
        long rev=SketchDocument.integer(request,"expectedRevision",1,Long.MAX_VALUE-1);
        try(SketchProjectStore store=new SketchProjectStore(context)){
            JSONObject doc=store.get(id);
            SketchProjectStore.requireRevision(doc,rev);
            JSONArray original=request.optJSONArray("actions");
            if(original==null||original.length()<1||original.length()>MAX_ACTIONS)
                throw SketchDocument.bad("Queue 1..24 actual human-workspace actions");
            JSONArray validated=new JSONArray();
            for(int i=0;i<original.length();i++){
                JSONObject step=original.optJSONObject(i);
                if(step==null)throw SketchDocument.bad("Every UI step must be an object");
                String type=step.optString("type","");
                if(!TYPES.contains(type))throw SketchDocument.bad("Unsupported native UI step: "+type);
                switch(type){
                    case "seek_frame":
                        SketchDocument.fields(step,"type,frameIndex");
                        SketchDocument.integer(step,"frameIndex",0,doc.getInt("frameCount")-1);break;
                    case "select_layer":
                        SketchDocument.fields(step,"type,layerId");
                        SketchDocument.layer(doc,SketchDocument.id(step,"layerId"));break;
                    case "select_tool":
                        SketchDocument.fields(step,"type,tool");
                        if(!TOOLS.contains(step.optString("tool","")))
                            throw SketchDocument.bad("Tool is not an installed Sketch Studio tool");
                        break;
                    case "select_object":case "focus_object":
                        SketchDocument.fields(step,"type,objectId");
                        SketchDocument.object(doc,SketchDocument.id(step,"objectId"));break;
                    case "toggle_onion":
                        SketchDocument.fields(step,"type,enabled,range,alpha");
                        if(!(step.opt("enabled") instanceof Boolean))
                            throw SketchDocument.bad("Onion enabled must be true or false");
                        if(step.has("range"))SketchDocument.integer(step,"range",1,3);
                        if(step.has("alpha"))SketchDocument.integer(step,"alpha",0,128);
                        break;
                    case "set_guides":
                        SketchDocument.fields(step,"type,grid,symmetry");
                        if(step.has("grid")&&!(step.opt("grid") instanceof Boolean)
                                ||step.has("symmetry")&&!(step.opt("symmetry") instanceof Boolean)
                                ||!step.has("grid")&&!step.has("symmetry"))
                            throw SketchDocument.bad("Guides need grid or symmetry boolean");
                        break;
                    case "set_brush":
                        SketchDocument.fields(step,"type,color,width");
                        if(!step.has("color")&&!step.has("width"))
                            throw SketchDocument.bad("Brush change needs a color or width");
                        if(step.has("color")&&!step.optString("color","").matches("#[0-9A-Fa-f]{8}"))
                            throw SketchDocument.bad("Use exact ARGB #AARRGGBB");
                        if(step.has("width"))SketchDocument.number(step,"width",.0005,.25);
                        break;
                    case "set_playback":
                        SketchDocument.fields(step,"type,playing");
                        if(!(step.opt("playing") instanceof Boolean))
                            throw SketchDocument.bad("Playback needs an explicit boolean state");
                        break;
                    case "show_curve":
                        SketchDocument.fields(step,"type,frameIndex,objectId,layerId");
                        if(step.has("frameIndex"))
                            SketchDocument.integer(step,"frameIndex",0,doc.getInt("frameCount")-1);
                        if(step.has("objectId"))
                            SketchDocument.object(doc,SketchDocument.id(step,"objectId"));
                        if(step.has("layerId"))
                            SketchDocument.layer(doc,SketchDocument.id(step,"layerId"));
                        if(i!=original.length()-1)
                            throw SketchDocument.bad("Open a curve inspector as the final UI step");
                        break;
                    case "reset_view":
                        SketchDocument.fields(step,"type");break;
                    default:throw SketchDocument.bad("Unsupported Sketch tool");
                }
                validated.put(new JSONObject(step.toString()));
            }
            return new Validated(id,rev,validated);
        }
    }

    private static String id(JSONObject args,String key)throws Exception {
        String id=SketchDocument.id(args,key);
        if(id.length()>128)throw SketchDocument.bad("Invalid operation identity");
        return id;
    }

    private static JSONObject read(Context context,String id)throws Exception{
        String raw=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .getString(PREFIX+id,"");
        return raw.isEmpty()?null:new JSONObject(raw);
    }
    private static void write(Context context,String id,JSONObject state){
        if(!context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
                .putString(PREFIX+id,state.toString()).commit())
            throw new IllegalStateException("Could not durably store the native agent workspace state");
    }
}
