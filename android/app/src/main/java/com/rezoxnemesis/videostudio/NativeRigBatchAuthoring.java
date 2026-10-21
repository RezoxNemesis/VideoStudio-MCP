package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded native source annotations. Structural preparation never certifies anatomy or a film. */
public final class NativeRigBatchAuthoring {
    private static final Object BATCH_LOCK=new Object();
    private static final ThreadLocal<Boolean> ACTIVE_BATCH=new ThreadLocal<>();
    private NativeRigBatchAuthoring() {}

    /**
     * Bounded service/phone progress checkpoint, delivered with SQLite/profile locks released.
     * Batch orchestration remains serialized: observers must not initiate another batch or wait
     * synchronously for another batch on any thread. They may record progress or stop this batch.
     */
    public interface PublicationObserver {
        void published(int index,JSONObject receipt) throws Exception;
    }

    public static JSONObject save(Context context,ProjectStore projects,JSONObject request) throws Exception {
        return save(context,projects,request,null);
    }

    /**
     * Validates the entire request before effects. Every subsequent profile has its own source/CAS
     * guard and atomic file replacement. A stopped batch retains exact receipts, never rolls files
     * back, and returns PARTIAL_APPLIED. Reusing batchId requires identical accepted annotations.
     */
    public static JSONObject save(Context context,ProjectStore projects,JSONObject request,
                                  PublicationObserver observer) throws Exception {
        if(context==null||projects==null)throw new IllegalArgumentException("Native source stores are required");
        if(Boolean.TRUE.equals(ACTIVE_BATCH.get()))throw new IllegalStateException("Nested batch publication is not supported");
        Prepared prepared=prepare(projects,request);
        synchronized(BATCH_LOCK) {
            ACTIVE_BATCH.set(true);
            try{return apply(context,projects,prepared,observer);}
            finally{ACTIVE_BATCH.remove();}
        }
    }

    private static final class Prepared {
        String projectId,batchId,requestSha256;long revision;
        Rig2DProfile[] rigs;String[] expected;
    }
    private static Prepared prepare(ProjectStore projects,JSONObject request) throws Exception {
        fields(request,"projectId","sourceProjectUpdatedAt","batchId","profiles");
        Prepared out=new Prepared();out.projectId=id(request,"projectId");out.batchId=id(request,"batchId");
        out.revision=integer(request,"sourceProjectUpdatedAt",0,Long.MAX_VALUE);
        ProjectStore.Project project=projects.get(out.projectId);
        if(project==null)throw new IllegalArgumentException("Source project was not found");
        if(project.updatedAt!=out.revision)throw new IllegalStateException("SOURCE_PROJECT_CHANGED: reopen the source before saving annotations");
        JSONArray profiles=request.optJSONArray("profiles");
        if(profiles==null||profiles.length()<1||profiles.length()>8)throw new IllegalArgumentException("Provide 1..8 source rig profiles");
        out.rigs=new Rig2DProfile[profiles.length()];out.expected=new String[profiles.length()];
        Set<String> ids=new HashSet<>(),actors=new HashSet<>();JSONArray accepted=new JSONArray();
        for(int index=0;index<profiles.length();index++) {
            JSONObject given=profiles.optJSONObject(index);
            fields(given,"sourceAssetId","rigId","black","transparentSource","joints","outline",
                    "swordHilt","swordTip","swordOutline","headOutline","sourceSha256","expectedRigSha256");
            JSONObject frozen=new JSONObject(given.toString());String assetId=id(frozen,"sourceAssetId");
            ProjectStore.Asset asset=project.asset(assetId);
            if(!registered(asset))throw new IllegalArgumentException("Source must be an existing project-owned image");
            digest(frozen,"sourceSha256",false);
            if(!frozen.has("rigId")||"".equals(frozen.opt("rigId"))) {
                String identity=out.projectId+"\n"+out.batchId+"\n"+index+"\n"+assetId;
                frozen.put("rigId",UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString());
            }
            Rig2DProfile rig=NativeRigAuthoring.parse(project.id,asset.id,frozen);
            if(!ids.add(rig.id)||!actors.add(asset.id+":"+rig.black))throw new IllegalArgumentException("Duplicate rig or source actor in batch");
            String expected=digest(frozen,"expectedRigSha256",true);
            out.rigs[index]=rig;out.expected[index]=expected;
            accepted.put(new JSONObject().put("rig",rig.toJson()).put("expectedRigSha256",expected));
        }
        // Rig serialization normalizes numeric geometry. No newly allocated timestamp enters replay proof.
        out.requestSha256=Rig2DProjectStore.sha256Json(new JSONObject().put("projectId",out.projectId)
                .put("sourceProjectUpdatedAt",out.revision).put("batchId",out.batchId).put("profiles",accepted));
        return out;
    }

    private static JSONObject apply(Context context,ProjectStore projects,Prepared p,
                                    PublicationObserver observer) throws Exception {
        Rig2DProjectStore store=new Rig2DProjectStore(context,projects);
        JSONObject journal=store.readBatchRecord(p.projectId,p.batchId);
        boolean replay=journal!=null;int priorCount=0,recoveredIndex=-1;
        boolean[] allowed=new boolean[p.rigs.length],required=new boolean[p.rigs.length];
        if(replay) {
            verifyJournal(journal,p);priorCount=journal.getJSONArray("receipts").length();Arrays.fill(allowed,true);
            for(int index=0;index<priorCount;index++)required[index]=true;
            int inFlight=(int)integer(journal,"inFlightIndex",-1,p.rigs.length-1);
            if(inFlight>=0) {
                // A crash may occur after the profile move but before the receipt move. Recover only
                // the exact accepted image/geometry, never overwrite an ambiguous old/concurrent draft.
                try {
                    JSONObject actual=store.captureBinding(p.projectId,p.rigs[inFlight].id);
                    if(!Rig2DProjectStore.sha256(p.rigs[inFlight]).equals(actual.getString("sha256")))throw new IllegalStateException();
                } catch(Exception uncertain) {
                    throw new IllegalStateException("BATCH_APPLICATION_UNCERTAIN: inspect the in-flight profile and submit a fresh reviewed batch");
                }
                required[inFlight]=true;
                if(inFlight==priorCount)recoveredIndex=inFlight;
            }
        }
        store.prevalidateBatch(p.rigs,p.expected,allowed,required,p.revision,p.batchId);
        if(journal==null)journal=new JSONObject().put("version",1).put("projectId",p.projectId).put("batchId",p.batchId)
                .put("sourceProjectUpdatedAt",p.revision).put("requestSha256",p.requestSha256)
                .put("requestedCount",p.rigs.length).put("receipts",new JSONArray()).put("inFlightIndex",-1);
        // These describe total accepted publication, not just progress through this invocation.
        // Prevalidation has checked every durable receipt against its exact current profile.
        JSONArray receipts=new JSONArray();int newlyApplied=0;
        if(replay) {
            for(int index=0;index<priorCount;index++)receipts.put(new JSONObject(journal.getJSONArray("receipts").getJSONObject(index).toString()).put("replayed",true));
            if(recoveredIndex>=0)receipts.put(Rig2DProjectStore.binding(p.rigs[recoveredIndex]).put("index",recoveredIndex)
                    .put("sourceSha256",p.rigs[recoveredIndex].sourceSha256).put("replayed",true));
        }
        try {
            if(!replay)checkpoint(store,p,journal);
            for(int index=0;index<p.rigs.length;index++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Batch interrupted before the next profile");
                journal.put("inFlightIndex",index);checkpoint(store,p,journal);
                JSONObject binding=store.saveBatchWithBinding(p.rigs[index],p.revision,p.expected[index],replay,required[index]);
                boolean reused=binding.optBoolean("replayed",false);
                JSONObject receipt=new JSONObject(binding.toString()).put("index",index)
                        .put("sourceSha256",p.rigs[index].sourceSha256).put("replayed",reused);
                receipts.put(index,receipt);if(!reused)newlyApplied++;
                JSONArray durable=journal.getJSONArray("receipts");
                if(index<durable.length())durable.put(index,new JSONObject(receipt.toString()));else durable.put(new JSONObject(receipt.toString()));
                journal.put("inFlightIndex",-1);checkpoint(store,p,journal);
                if(observer!=null)observer.published(index,new JSONObject(receipt.toString()));
            }
            return result(p,newlyApplied==0?"REPLAYED":"APPLIED",receipts,newlyApplied,true,null);
        } catch(Exception stopped) {
            return result(p,"PARTIAL_APPLIED",receipts,newlyApplied,false,stopped);
        }
    }

    private static void checkpoint(Rig2DProjectStore store,Prepared p,JSONObject journal) throws Exception {
        journal.remove("sha256");journal.put("sha256",Rig2DProjectStore.sha256Json(journal));
        store.writeBatchRecord(p.projectId,p.revision,p.batchId,journal);
    }
    private static void verifyJournal(JSONObject journal,Prepared p) throws Exception {
        fields(journal,"version","projectId","batchId","sourceProjectUpdatedAt","requestSha256","requestedCount","receipts","inFlightIndex","sha256");
        JSONObject content=new JSONObject(journal.toString());String sha=digest(content,"sha256",false);content.remove("sha256");
        if(!sha.equals(Rig2DProjectStore.sha256Json(content)))throw new IllegalStateException("BATCH_RECORD_INVALID: saved checkpoint digest differs");
        if(integer(journal,"version",1,1)!=1||!p.projectId.equals(id(journal,"projectId"))||!p.batchId.equals(id(journal,"batchId"))
                ||p.revision!=integer(journal,"sourceProjectUpdatedAt",0,Long.MAX_VALUE)
                ||!p.requestSha256.equals(digest(journal,"requestSha256",false))
                ||p.rigs.length!=integer(journal,"requestedCount",1,8))
            throw new IllegalStateException("BATCH_REPLAY_CONFLICT: batchId already binds different accepted annotations");
        JSONArray receipts=journal.optJSONArray("receipts");
        if(receipts==null||receipts.length()>p.rigs.length)throw new IllegalStateException("BATCH_RECORD_INVALID: checkpoint receipts are outside bounds");
        for(int index=0;index<receipts.length();index++) {
            JSONObject receipt=receipts.getJSONObject(index);Rig2DProfile rig=p.rigs[index];
            fields(receipt,"index","rigId","projectId","sourceAssetId","black","sha256","sourceSha256","replayed");
            if(integer(receipt,"index",0,7)!=index||!rig.id.equals(id(receipt,"rigId"))
                    ||!rig.projectId.equals(id(receipt,"projectId"))||!rig.sourceAssetId.equals(id(receipt,"sourceAssetId"))
                    ||!(receipt.opt("black") instanceof Boolean)||rig.black!=receipt.getBoolean("black")
                    ||!Rig2DProjectStore.sha256(rig).equals(digest(receipt,"sha256",false))
                    ||!rig.sourceSha256.equals(digest(receipt,"sourceSha256",false))
                    ||!(receipt.opt("replayed") instanceof Boolean))
                throw new IllegalStateException("BATCH_RECORD_INVALID: checkpoint receipt differs from the accepted plan");
        }
        int inFlight=(int)integer(journal,"inFlightIndex",-1,p.rigs.length-1);
        // During replay, an existing receipt can be rechecked; a new entry must extend the prefix.
        if(inFlight>receipts.length())throw new IllegalStateException("BATCH_RECORD_INVALID: checkpoint has skipped pending profiles");
    }
    private static JSONObject result(Prepared p,String state,JSONArray receipts,int newlyApplied,
                                     boolean sourcesVerified,Exception stopped) throws Exception {
        JSONArray pending=new JSONArray();for(int index=receipts.length();index<p.rigs.length;index++)pending.put(index);
        JSONObject out=new JSONObject().put("ok",stopped==null).put("state",state).put("projectId",p.projectId)
                .put("sourceProjectUpdatedAt",p.revision).put("batchId",p.batchId).put("requestedCount",p.rigs.length)
                .put("appliedCount",receipts.length()).put("newlyAppliedCount",newlyApplied)
                .put("replayedCount",receipts.length()-newlyApplied).put("receipts",receipts).put("pendingIndices",pending)
                .put("allOrNothing",false).put("sourceBytesVerified",sourcesVerified).put("cinematicApproved",false);
        if(stopped!=null) {
            String code=stopped instanceof InterruptedException?"INTERRUPTED":"BATCH_SAVE_FAILED";
            String message=stopped.getMessage();
            if(message!=null&&message.matches("[A-Z_]+:.*"))code=message.substring(0,message.indexOf(':'));
            out.put("error",new JSONObject().put("code",code).put("message","Batch stopped; published receipts are retained. Inspect current sources/profiles before retrying."));
        }
        return out;
    }

    /** Ordered, read-only preparation checklist. Original bytes and correct anatomy are unmeasured. */
    public static JSONObject status(Context context,ProjectStore projects,JSONObject request) throws Exception {
        if(context==null||projects==null)throw new IllegalArgumentException("Native source stores are required");
        fields(request,"projectId","assetIds","programId","expectedProgramSha256");String projectId=id(request,"projectId");
        ProjectStore.Project project=projects.get(projectId);if(project==null)throw new IllegalArgumentException("Source project was not found");
        if(request.has("assetIds")&&request.has("programId"))throw new IllegalArgumentException("Select assetIds or a saved program identity");
        if(request.has("expectedProgramSha256")&&!request.has("programId"))throw new IllegalArgumentException("A program identity is required for its digest");
        JSONArray order=new JSONArray();JSONObject program=null;String origin;
        if(request.has("programId")) {
            program=new StoryboardProgramStore(context,projects).readForEdit(projectId,id(request,"programId"));
            if(request.has("expectedProgramSha256")&&!digest(request,"expectedProgramSha256",false).equals(program.getString("sha256")))
                throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: reopen the saved program before checking preparation");
            order=new JSONArray(program.getJSONArray("sourceAssetIds").toString());origin="saved_program";
        } else if(request.has("assetIds")) {
            JSONArray given=request.optJSONArray("assetIds");if(given==null)throw new IllegalArgumentException("assetIds must be an array");
            for(int index=0;index<given.length();index++) {String assetId=id(given.opt(index));if(!registered(project.asset(assetId)))throw new IllegalArgumentException("Selected source must be a project-owned image");order.put(assetId);}
            origin="explicit_asset_ids";
        } else {
            for(ProjectStore.Asset asset:project.assets)if(registered(asset))order.put(asset.id);origin="project_image_order";
        }
        if(order.length()<1||order.length()>160)throw new IllegalArgumentException("Select 1..160 ordered source occurrences");
        Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);List<Rig2DProfile> all=rigs.list(projectId);
        JSONArray occurrences=new JSONArray();JSONObject next=null;boolean complete=true;
        java.util.Map<String,JSONObject> cache=new java.util.HashMap<>();
        for(int index=0;index<order.length();index++) {
            String assetId=id(order.opt(index));boolean present=registered(project.asset(assetId));
            JSONObject row=new JSONObject().put("index",index).put("assetId",assetId).put("sourceRegistered",present);
            for(boolean black:new boolean[]{true,false}) {
                String actor=black?"black":"white";String pinnedId=null,pinnedSha=null;
                if(program!=null) {JSONObject binding=program.getJSONObject("sourceRigBindings").getJSONObject(assetId);pinnedId=binding.getString(actor+"RigId");pinnedSha=binding.getString(actor+"RigSha256");}
                String key=assetId+":"+actor;JSONObject preparation=cache.get(key);
                if(preparation==null){preparation=actorStatus(all,assetId,black,present,pinnedId,pinnedSha);cache.put(key,preparation);}
                row.put(actor,preparation);
                if(!preparation.getBoolean("annotationPresent")) {
                    complete=false;if(next==null)next=new JSONObject().put("index",index).put("assetId",assetId).put("actor",actor).put("missing",preparation.getJSONArray("missing"));
                }
            }
            occurrences.put(row);
        }
        JSONObject out=new JSONObject().put("ok",true).put("projectId",projectId).put("sourceProjectUpdatedAt",project.updatedAt)
                .put("orderOrigin",origin).put("assetIds",order).put("occurrences",occurrences).put("preparationComplete",complete)
                .put("preparationScope","annotation-fields-present-only").put("sourceBytesVerified",false).put("inspectionRequired",true)
                .put("identityStatus","UNAVAILABLE").put("anatomyStatus","UNAVAILABLE").put("cinematicStatus","UNAVAILABLE").put("cinematicApproved",false);
        if(next!=null)out.put("nextMissing",next);
        if(program!=null)out.put("programId",program.getString("programId")).put("programSha256",program.getString("sha256"))
                .put("programStale",program.getBoolean("stale")).put("staleWarnings",program.getJSONArray("staleWarnings"));
        return out;
    }

    private static JSONObject actorStatus(List<Rig2DProfile> all,String assetId,boolean black,boolean sourcePresent,
                                          String selectedId,String selectedSha) throws Exception {
        JSONArray profiles=new JSONArray(),missing=new JSONArray();JSONObject only=null;int total=0;
        for(Rig2DProfile rig:all) {
            if(!assetId.equals(rig.sourceAssetId)||rig.black!=black||selectedId!=null&&!selectedId.equals(rig.id))continue;
            total++;if(profiles.length()>=8)continue;
            JSONArray absent=new JSONArray();
            if(!sourcePresent)absent.put("sourceAsset");if(rig.sourceSha256==null)absent.put("sourceSha256");
            if(rig.joints.length!=16)absent.put("joints");if(rig.outline.length<3)absent.put("outline");
            if(rig.swordHilt==null)absent.put("swordHilt");if(rig.swordTip==null)absent.put("swordTip");if(rig.swordOutline.length<3)absent.put("swordOutline");
            String sha=Rig2DProjectStore.sha256(rig);if(selectedSha!=null&&!selectedSha.equals(sha))absent.put("sourceRigBinding");
            JSONObject item=new JSONObject().put("rigId",rig.id).put("sha256",sha).put("missing",absent).put("annotationPresent",absent.length()==0);
            if(rig.sourceSha256!=null)item.put("sourceSha256",rig.sourceSha256);profiles.put(item);only=item;
        }
        if(profiles.length()==0) {
            if(!sourcePresent)missing.put("sourceAsset");
            for(String field:new String[]{"rigProfile","sourceSha256","joints","outline","swordHilt","swordTip","swordOutline"})missing.put(field);
            if(selectedId!=null)missing.put("sourceRigBinding");
        } else if(total>1)missing.put("rigSelection");
        else missing=new JSONArray(only.getJSONArray("missing").toString());
        return new JSONObject().put("annotationPresent",missing.length()==0).put("selectionRequired",total>1)
                .put("totalProfileCount",total).put("profilesTruncated",total>profiles.length()).put("profiles",profiles).put("missing",missing);
    }
    private static boolean registered(ProjectStore.Asset asset) {
        return asset!=null&&asset.mime!=null&&asset.mime.startsWith("image/")&&asset.uri!=null&&!asset.uri.isEmpty();
    }
    private static void fields(JSONObject object,String... allowed) {
        if(object==null)throw new IllegalArgumentException("A bounded source annotation object is required");
        Set<String> keys=new HashSet<>(Arrays.asList(allowed));for(Iterator<String> i=object.keys();i.hasNext();)if(!keys.contains(i.next()))throw new IllegalArgumentException("Unknown source annotation field");
    }
    private static String id(JSONObject object,String field){return id(object.opt(field));}
    private static String id(Object value) {
        if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9_-]{8,128}"))throw new IllegalArgumentException("Invalid source annotation identity");return(String)value;
    }
    private static String digest(JSONObject object,String field,boolean optional) {
        if(optional&&!object.has(field))return "";Object raw=object.opt(field);
        if(!(raw instanceof String)||!((String)raw).matches(optional?"(?:[0-9a-f]{64})?":"[0-9a-f]{64}"))throw new IllegalArgumentException(field+" must be a lowercase SHA-256 string");return(String)raw;
    }
    private static long integer(JSONObject object,String field,long min,long max) {
        Object raw=object.opt(field);if(!(raw instanceof Number))throw new IllegalArgumentException(field+" must be an integer");
        double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value!=Math.rint(value)||value<min||value>max)
            throw new IllegalArgumentException(field+" is outside integer bounds");return((Number)raw).longValue();
    }
}
