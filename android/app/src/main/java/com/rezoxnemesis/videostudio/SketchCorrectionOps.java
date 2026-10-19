package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicitly operated review -> native proposal -> preview -> atomic apply coordinator.
 * Review decisions are authored observations; this class does not infer artistic quality. */
final class SketchCorrectionOps {
    static final Set<String> SUPPORTED=Set.of("add_layer","update_layer","delete_layer","reorder_layer","add_object","update_object","delete_object","set_transform","set_keyframe","set_shape_key","set_exposure","append_points","add_human_animation","add_hand_animation","add_eye_animation","add_mouth_animation","add_face_animation","add_character_animation","add_motion_study","add_solid","add_solid_collision","add_story_stage","add_actor_prop","add_grip_link","symmetry_stroke");
    private SketchCorrectionOps(){}
    static synchronized JSONObject execute(Context context,String action,JSONObject args)throws Exception{
        if(args==null||args.toString().getBytes(StandardCharsets.UTF_8).length>512*1024)throw SketchDocument.bad("Correction request exceeds512KiB");
        String sketch=SketchDocument.id(args,"sketchId");
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Correction operation cancelled");
        try(SketchCorrectionStore store=new SketchCorrectionStore(context)){
            if(action.equals("sketch_correction_list")){SketchDocument.fields(args,"sketchId,offset,limit");try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.get(sketch);}return store.list(sketch,args.has("offset")?(int)SketchDocument.integer(args,"offset",0,1000000):0,args.has("limit")?(int)SketchDocument.integer(args,"limit",1,16):16);}
            String review=SketchDocument.id(args,"reviewId");
            if(action.equals("sketch_correction_start"))return start(context,store,sketch,review,args);
            JSONObject state=store.state(sketch,review);
            switch(action){
                case "sketch_correction_get":SketchDocument.fields(args,"sketchId,reviewId");return response(context,state);
                case "sketch_correction_options":SketchDocument.fields(args,"sketchId,reviewId,objectId");return SketchCorrectionRecipes.options(context,store.source(sketch,review),SketchDocument.id(args,"objectId")).put("reviewId",review).put("sourceRevision",state.getLong("sourceRevision")).put("sourceDocumentSha256",state.getString("sourceDocumentSha256")).put("sourceModified",false).put("ok",true);
                case "sketch_correction_note":return note(context,store,state,args);
                case "sketch_correction_propose":return propose(context,store,state,args);
                case "sketch_correction_preview":return preview(context,store,state,args);
                case "sketch_correction_apply":return apply(context,store,state,args);
                default:throw SketchDocument.bad("Unknown correction action");
            }
        }
    }
    private static JSONObject start(Context c,SketchCorrectionStore store,String sketch,String review,JSONObject a)throws Exception{
        SketchDocument.fields(a,"sketchId,reviewId,expectedRevision,target,frameOffset,frameLimit,frameStep,motionRequired");SketchDocument.text(a,"target",500);long revision=SketchDocument.integer(a,"expectedRevision",1,Long.MAX_VALUE-2);String hash=hash(a);
        if(store.exists(review)){if(!store.startHash(sketch,review).equals(hash))throw SketchCorrectionStore.conflict("Review identity reused with another request");return response(c,store.state(sketch,review));}
        JSONObject source;try(SketchProjectStore sketches=new SketchProjectStore(c)){source=sketches.get(sketch);SketchProjectStore.requireRevision(source,revision);}
        JSONObject auditArgs=new JSONObject().put("sketchId",sketch).put("expectedRevision",revision);for(String k:new String[]{"frameOffset","frameLimit","frameStep","motionRequired"})if(a.has(k))auditArgs.put(k,a.get(k));
        JSONObject audit=SketchQualityAudit.inspect(c,source,auditArgs);
        JSONObject state=new JSONObject().put("reviewId",review).put("sketchId",sketch).put("revision",1).put("sourceRevision",revision).put("sourceDocumentSha256",hash(source)).put("target",a.getString("target")).put("status","OPEN").put("auditRequest",auditArgs).put("audit",audit).put("notes",new JSONArray()).put("proposals",new JSONArray()).put("visualAcceptanceVerified",false);
        store.create(source,state,hash);return response(c,state);
    }
    private static JSONObject response(Context c,JSONObject state)throws Exception{
        long live;try(SketchProjectStore sketches=new SketchProjectStore(c)){live=sketches.get(state.getString("sketchId")).getLong("revision");}
        return new JSONObject().put("ok",true).put("review",SketchDocument.copy(state)).put("currentSourceRevision",live).put("sourceMatches",live==state.getLong("sourceRevision")).put("sourceModified",false);
    }
    private static void open(JSONObject s)throws Exception{if(!s.getString("status").equals("OPEN"))throw SketchCorrectionStore.conflict("This review already applied a correction; start another review for the new drawing");}
    private static JSONObject current(Context c,JSONObject s)throws Exception{
        try(SketchProjectStore sketches=new SketchProjectStore(c)){JSONObject source=sketches.get(s.getString("sketchId"));SketchProjectStore.requireRevision(source,s.getLong("sourceRevision"));if(!hash(source).equals(s.getString("sourceDocumentSha256")))throw new IllegalStateException("SKETCH_REVISION_CONFLICT: source hash changed");return source;}
    }
    private static long revision(JSONObject a)throws Exception{return SketchDocument.integer(a,"expectedReviewRevision",1,Long.MAX_VALUE-2);}
    private static JSONObject note(Context c,SketchCorrectionStore store,JSONObject state,JSONObject a)throws Exception{
        SketchDocument.fields(a,"sketchId,reviewId,expectedReviewRevision,noteId,outcome,note,frameStart,frameEnd,objectId,bounds,proposalId");String id=SketchDocument.id(a,"noteId");long expected=revision(a);SketchDocument.text(a,"note",1000);
        if(!(a.get("outcome") instanceof String)||!Set.of("ACCEPTABLE","NEEDS_REWORK","UNREVIEWED").contains(a.getString("outcome")))throw SketchDocument.bad("Unknown review outcome");
        JSONObject entry=SketchDocument.copy(a);entry.remove("sketchId");entry.remove("reviewId");entry.remove("expectedReviewRevision");String requestHash=hash(entry);JSONArray notes=state.getJSONArray("notes");
        for(int i=0;i<notes.length();i++)if(notes.getJSONObject(i).getString("noteId").equals(id)){if(!notes.getJSONObject(i).getString("requestHash").equals(requestHash))throw SketchCorrectionStore.conflict("Note identity reused");return response(c,state);}
        SketchCorrectionStore.requireRevision(state,expected);JSONObject source=a.has("proposalId")?store.proposal(state.getString("sketchId"),state.getString("reviewId"),SketchDocument.id(a,"proposalId")).getJSONObject("document"):store.source(state.getString("sketchId"),state.getString("reviewId"));
        int start=(int)SketchDocument.integer(a,"frameStart",0,source.getInt("frameCount")-1),end=(int)SketchDocument.integer(a,"frameEnd",start,source.getInt("frameCount")-1);
        if(a.has("objectId"))SketchDocument.object(source,SketchDocument.id(a,"objectId"));if(a.has("bounds"))SketchDocument.validateBounds(a.getJSONObject("bounds"));
        if(notes.length()>=SketchCorrectionStore.MAX_NOTES)throw new IllegalStateException("CORRECTION_NOTE_BUDGET: at most32 notes per review");notes.put(entry.put("requestHash",requestHash));store.write(state.getString("sketchId"),state.getString("reviewId"),expected,state);return response(c,state);
    }
    private static JSONObject propose(Context c,SketchCorrectionStore store,JSONObject state,JSONObject a)throws Exception{
        SketchDocument.fields(a,"sketchId,reviewId,expectedReviewRevision,proposalId,summary,noteIds,operations,recipeId,objectId");String id=SketchDocument.id(a,"proposalId");long expected=revision(a);SketchDocument.text(a,"summary",500);String requestHash=hash(a),sketch=state.getString("sketchId"),review=state.getString("reviewId");
        if(store.hasProposal(review,id)){if(!store.proposal(sketch,review,id).getString("requestHash").equals(requestHash))throw SketchCorrectionStore.conflict("Proposal identity reused");return response(c,state);}
        open(state);SketchCorrectionStore.requireRevision(state,expected);current(c,state);
        JSONArray noteIds=a.getJSONArray("noteIds");if(noteIds.length()<1||noteIds.length()>16)throw SketchDocument.bad("Choose1..16 existing review notes");Set<String> selected=new HashSet<>();JSONArray notes=state.getJSONArray("notes");
        for(int i=0;i<noteIds.length();i++){JSONObject input=new JSONObject().put("id",noteIds.get(i));String note=SketchDocument.id(input,"id");if(!selected.add(note))throw SketchDocument.bad("Duplicate correction note");boolean found=false;for(int j=0;j<notes.length();j++)found|=notes.getJSONObject(j).getString("noteId").equals(note);if(!found)throw SketchDocument.bad("Unknown correction note");}
        boolean recipe=a.has("recipeId")||a.has("objectId");if(a.has("operations")==recipe)throw SketchDocument.bad("Choose correction operations or recipeId with objectId");
        JSONObject candidate=store.source(sketch,review);JSONArray operations=recipe?SketchCorrectionRecipes.operations(c,candidate,SketchDocument.id(a,"recipeId"),SketchDocument.id(a,"objectId")):new JSONArray(a.getJSONArray("operations").toString());
        if(operations.length()<1||operations.length()>64)throw SketchDocument.bad("Use1..64 correction operations");SketchPaintOps.validatePublicOperations(operations);JSONArray resolved=new JSONArray();
        try(SketchProjectStore sketches=new SketchProjectStore(c)){SketchDrawingPolicy.requireOperations(sketches.activeDrawingSession(sketch),operations);}
        for(int i=0;i<operations.length();i++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Correction preparation cancelled");JSONObject op=operations.getJSONObject(i);if(!SUPPORTED.contains(op.getString("type")))throw SketchDocument.bad("Unsupported correction operation; use native vector, pose or placement edits");if(op.getString("type").equals("add_object")&&op.getJSONObject("object").optString("kind").equals("image"))throw SketchDocument.bad("Correction candidates do not import reference images");
            if(op.getString("type").equals("add_layer")){JSONArray objects=op.getJSONObject("layer").optJSONArray("objects");if(objects!=null)for(int j=0;j<objects.length();j++)if(objects.getJSONObject(j).optString("kind").equals("image"))throw SketchDocument.bad("Correction candidates do not import reference images");}
            JSONObject expanded=SketchSpeechAssets.expand(c,candidate,op);SketchEditOps.apply(candidate,expanded);resolved.put(expanded);}
        SketchDocument.validate(candidate);JSONObject source=store.source(sketch,review);
        try(SketchProjectStore sketches=new SketchProjectStore(c)){SketchDrawingPolicy.requireDocument(sketches.activeDrawingSession(sketch),source,candidate);sketches.verifyReferences(candidate);}
        for(String k:new String[]{"width","height","fps","frameCount"})if(!source.get(k).equals(candidate.get(k)))throw SketchDocument.bad("Correction must preserve canvas and animation timing");if(hash(candidate).equals(hash(source)))throw SketchDocument.bad("Correction makes no change");
        JSONObject metadata=new JSONObject().put("proposalId",id).put("summary",a.getString("summary")).put("noteIds",noteIds).put("operationCount",resolved.length()).put("documentSha256",hash(candidate)).put("visualAcceptanceVerified",false);
        JSONObject auditRequest=state.has("auditRequest")?SketchDocument.copy(state.getJSONObject("auditRequest")):new JSONObject().put("sketchId",sketch).put("expectedRevision",state.getLong("sourceRevision")).put("frameOffset",state.getJSONObject("audit").getInt("frameOffset")).put("frameLimit",state.getJSONObject("audit").getInt("sampleCount")).put("frameStep",state.getJSONObject("audit").getInt("frameStep"));
        metadata.put("candidateAudit",SketchQualityAudit.inspect(c,candidate,auditRequest)).put("nativeChecks",new JSONObject().put("documentValid",true).put("canvasPreserved",true).put("clockPreserved",true).put("sourceUnmodified",true).put("artisticQualityVerified",false));
        if(recipe){JSONObject library=SketchCorrectionRecipes.options(c,source,a.getString("objectId"));metadata.put("recipeId",a.getString("recipeId")).put("objectId",a.getString("objectId")).put("libraryVersion",library.getInt("libraryVersion")).put("librarySha256",library.getString("librarySha256"));}
        store.saveProposal(sketch,review,expected,requestHash,candidate,resolved,metadata);return response(c,store.state(sketch,review));
    }
    private static JSONObject preview(Context c,SketchCorrectionStore store,JSONObject state,JSONObject a)throws Exception{
        SketchDocument.fields(a,"sketchId,reviewId,side,frameIndex,proposalId,crop");if(!(a.get("side") instanceof String)||!Set.of("before","after").contains(a.getString("side")))throw SketchDocument.bad("Choose before or after");
        String sketch=state.getString("sketchId"),review=state.getString("reviewId");boolean after=a.getString("side").equals("after");if(after&&!a.has("proposalId"))throw SketchDocument.bad("After preview requires a correction proposalId");if(!after&&a.has("proposalId"))throw SketchDocument.bad("Before preview uses the captured source");
        JSONObject source=after?store.proposal(sketch,review,SketchDocument.id(a,"proposalId")).getJSONObject("document"):store.source(sketch,review);
        int frame=(int)SketchDocument.integer(a,"frameIndex",0,source.getInt("frameCount")-1);JSONObject request=new JSONObject().put("sketchId",sketch).put("expectedRevision",source.getLong("revision")).put("frameIndex",frame).put("detail","high").put("focus","correction "+a.getString("side"));if(a.has("crop"))request.put("crop",a.getJSONObject("crop"));
        JSONObject result=SketchVisualReview.inspect(c,source,request);result.put("reviewId",review).put("side",a.getString("side")).put("snapshotDocumentSha256",hash(source)).put("canvasWidth",source.getInt("width")).put("canvasHeight",source.getInt("height")).put("frameCount",source.getInt("frameCount")).put("fps",source.getInt("fps")).put("sourceModified",false);if(after)result.put("proposalId",a.getString("proposalId"));return result;
    }
    private static JSONObject apply(Context c,SketchCorrectionStore store,JSONObject state,JSONObject a)throws Exception{
        SketchDocument.fields(a,"sketchId,reviewId,expectedReviewRevision,proposalId,expectedRevision,operationId");long expected=revision(a),sourceRevision=SketchDocument.integer(a,"expectedRevision",1,Long.MAX_VALUE-2);String proposalId=SketchDocument.id(a,"proposalId"),operation=SketchDocument.id(a,"operationId"),sketch=state.getString("sketchId"),review=state.getString("reviewId");
        JSONObject proposal=store.proposal(sketch,review,proposalId);String fingerprint=hash(new JSONObject().put("action","sketch_correction_apply").put("arguments",a));JSONObject edit;
        try(SketchProjectStore sketches=new SketchProjectStore(c)){
            edit=sketches.operationReceipt(sketch,operation,fingerprint);
            if(edit==null){open(state);SketchCorrectionStore.requireRevision(state,expected);if(sourceRevision!=state.getLong("sourceRevision"))throw new IllegalStateException("SKETCH_REVISION_CONFLICT: correction source differs");
                JSONArray operations=proposal.getJSONArray("operations");edit=sketches.mutate(sketch,operation,sourceRevision,fingerprint,live->{
                    if(!hash(live).equals(state.getString("sourceDocumentSha256")))throw new IllegalStateException("SKETCH_REVISION_CONFLICT: correction source hash changed");SketchDrawingPolicy.requireOperations(sketches.activeDrawingSession(sketch),operations);return SketchDocument.copy(proposal.getJSONObject("document"));
                },operations);
            }
        }
        if(state.getString("status").equals("APPLIED")){
            if(!state.getString("appliedOperationId").equals(operation)||!state.getString("appliedProposalId").equals(proposalId))throw SketchCorrectionStore.conflict("Another proposal was applied");
        }else{
            // A retry can arrive after the source transaction committed and before coordinator
            // state persisted. The exact ordinary operation receipt is authoritative recovery.
            long actual=state.getLong("revision");state.put("status","APPLIED").put("appliedOperationId",operation).put("appliedProposalId",proposalId).put("appliedRevision",edit.getLong("revision"));store.write(sketch,review,actual,state);
        }
        SketchEditOps.signalChanged(c,"sketch_correction_apply",edit);return new JSONObject().put("ok",true).put("review",state).put("edit",edit).put("sourceModified",true).put("visualAcceptanceVerified",false).put("undo",new JSONObject().put("action","sketch_undo").put("sketchId",sketch).put("expectedRevision",edit.getLong("revision")));
    }
    static JSONArray relaxedArms(JSONObject document,String objectId)throws Exception{
        JSONObject object=SketchDocument.object(document,objectId);if(!object.getString("kind").equals("human"))throw SketchDocument.bad("Choose a retained human actor");JSONObject human=SketchDocument.copy(object.getJSONObject("human"));JSONArray poses=human.getJSONArray("poses");for(int i=0;i<poses.length();i++)poses.getJSONObject(i).put("nearElbowSwivel",90).put("farElbowSwivel",-90).put("nearWrist",-6).put("farWrist",6);
        return new JSONArray().put(new JSONObject().put("type","update_object").put("objectId",objectId).put("patch",new JSONObject().put("human",human)));
    }
    static String hash(Object value)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(value).getBytes(StandardCharsets.UTF_8));}
}
