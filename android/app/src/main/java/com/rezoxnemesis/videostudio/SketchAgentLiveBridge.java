package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turns private MCP native sketch_edit batches into visible, editable
 * native stroke journals. It NEVER imports images or invents animation.
 *
 * Explicit sketch_live_begin sessions keep their original admission policy.
 * A standalone native edit gets a short, source-assisted, silent session so
 * the owner's "Follow current work" canvas can play the actual operations.
 */
final class SketchAgentLiveBridge {
    private SketchAgentLiveBridge() {}

    static JSONObject edit(Context context,JSONObject input)throws Exception {
        if(context==null || input==null)throw SketchDocument.bad("Native drawing edit is required");
        String id=SketchDocument.id(input,"sketchId");
        String operationId=SketchDocument.id(input,"operationId");
        long expected=SketchDocument.integer(input,"expectedRevision",1,Long.MAX_VALUE-1);
        JSONArray edits=input.getJSONArray("operations");
        if(edits.length()==0)return SketchEditOps.execute(context,"sketch_edit",input);

        boolean explicitSession=false;
        boolean freshRevision,layered;
        try(SketchProjectStore store=new SketchProjectStore(context)){
            explicitSession=store.activeDrawingSession(id)!=null;
            freshRevision=store.revision(id)==expected;layered=store.get(id).has("layeredDrawing");
        }
        // Existing live stream, duplicate queued command, or edit conflict.
        // The normal idempotent receipt/revision logic remains authoritative.
        if(explicitSession || !freshRevision)
            return SketchEditOps.execute(context,"sketch_edit",input);

        String journalId=UUID.nameUUIDFromBytes(("native-chatgpt-sketch-edit:"
                +id+"/"+operationId).getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject begin=new JSONObject().put("sketchId",id).put("expectedRevision",expected)
                .put("operationId",journalId).put("strokeDurationMs",80)
                .put("adaptiveNativePacing",true)
                .put("drawingMethod",layered?SketchDrawingPolicy.ORIGINAL:SketchDrawingPolicy.ASSISTED);
        SketchLiveDrawing.beginQuietly(context,begin);
        JSONObject edited=null;
        Exception failure=null;
        try{
            edited=SketchEditOps.execute(context,"sketch_edit",input);
        }catch(Exception original){failure=original;}
        try{
            long current;
            try(SketchProjectStore store=new SketchProjectStore(context)){
                current=store.revision(id);
            }
            SketchEditOps.execute(context,"sketch_live_end",
                    new JSONObject().put("sketchId",id).put("sessionId",journalId)
                            .put("expectedRevision",current));
        }catch(Exception finalisation){
            if(failure==null)failure=new IllegalStateException(
                    "Native drawing saved but watch journal could not close",finalisation);
            else failure.addSuppressed(finalisation);
        }
        if(failure!=null)throw failure;
        return edited.put("watchableNativeOperations",edits.length())
                .put("watchSessionId",journalId)
                .put("autoLiveMethod",layered?SketchDrawingPolicy.ORIGINAL:SketchDrawingPolicy.ASSISTED)
                .put("deviceExecuted",true);
    }
}
