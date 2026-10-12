package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.UUID;
import static com.rezoxnemesis.videostudio.Cinematic3DTimeline.*;
import static com.rezoxnemesis.videostudio.NativeCinematicBundleStore.*;

/** Independent source-bound 3D programs with immutable revisions and a CAS current pointer. */
public final class Cinematic3DProgramStore {
    private static final int MAX_PROGRAMS=64,MAX_REVISIONS=128;
    private static final long MAX_STORAGE=64L*1024*1024;
    private static final String SCHEMA="videostudio.cinematic3d.program.v1";
    private Cinematic3DProgramStore(){}

    public static JSONObject save(Context context,ProjectStore projects,JSONObject request)throws Exception {
        fields(request,"projectId","sourceProjectUpdatedAt","programId","expectedProgramSha256","bundleId","timeline");String projectId=id(text(request,"projectId",96)),bundleId=id(text(request,"bundleId",96));long revision=integer(request,"sourceProjectUpdatedAt",0,Long.MAX_VALUE);
        String programId=request.has("programId")?id(text(request,"programId",96)):UUID.randomUUID().toString();String expected=optionalDigest(request,"expectedProgramSha256");JSONObject timeline=validate(object(request,"timeline"));ProjectStore.Project source=projects.get(projectId);
        if(source==null)throw new IllegalArgumentException("Source project unavailable");if(source.updatedAt!=revision)throw new IllegalStateException("SOURCE_PROJECT_CHANGED: source revision differs");
        JSONObject bundle=NativeCinematicBundleStore.get(context,projects,projectId,bundleId);bindScenes(bundle,timeline);SourceProof proof=measureSources(context,source,timeline);
        JSONObject payload=new JSONObject().put("schema",SCHEMA).put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("programId",programId).put("bundleId",bundleId)
            .put("bundleSha256",bundle.getString("sha256")).put("bundleReceiptSha256",bundle.getString("receiptSha256")).put("timeline",timeline).put("sourceEntries",proof.entries).put("sourceRegistrationSha256",proof.registrations);
        String digest=sha(payload);byte[] bytes=canonical(payload).getBytes(StandardCharsets.UTF_8);
        return projects.guardedProject(projectId,revision,current->{synchronized(FILE_LOCK){assertRegistration(current,proof);JSONObject liveBundle=cheapPayload(context,projectId,bundleId);if(!bundle.getString("receiptSha256").equals(liveBundle.getString("receiptSha256")))throw new IllegalStateException("SOURCE_BUNDLE_CHANGED: bundle changed before program publication");
            File root=directory(context,projectId,"programs",true),dir=new File(root,programId);plain(dir);boolean exists=dir.exists();if(exists&&!dir.isDirectory())throw new IllegalArgumentException("Program revision path is not a directory");JSONObject prior=exists&&new File(dir,"_current.json").exists()?readCurrent(context,projectId,programId):null;String actual=prior==null?"":prior.getString("sha256");
            if(!expected.isEmpty()&&!expected.equals(actual))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: current program SHA differs");
            if(prior!=null&&digest.equals(actual))return receipt(payload,digest,bundle);
            if(prior!=null&&expected.isEmpty())throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: replacing a program requires its current SHA");
            if(!exists){File[] programs=root.listFiles(File::isDirectory);if(programs!=null&&programs.length>=MAX_PROGRAMS)throw new IllegalArgumentException("Cinematic program limit is 64");if(!dir.mkdir())throw new IllegalStateException("Cannot create program revision directory");}
            File target=new File(dir,digest+".json");plain(target);if(!target.exists()){File[] versions=dir.listFiles((folder,name)->name.matches("[0-9a-f]{64}\\.json"));if(versions!=null&&versions.length>=MAX_REVISIONS)throw new IllegalArgumentException("Cinematic program revision limit is 128");if(storage(root)+bytes.length>MAX_STORAGE)throw new IllegalArgumentException("Cinematic program storage exceeds 64 MiB");writeAtomic(target,bytes);}else if(!digest.equals(sha(readJson(target))))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: immutable program revision corrupted");
            JSONObject pointer=new JSONObject().put("schema","videostudio.cinematic3d.program-pointer.v1").put("programId",programId).put("sha256",digest);writeAtomic(new File(dir,"_current.json"),canonical(pointer).getBytes(StandardCharsets.UTF_8));return receipt(payload,digest,bundle);
        }});
    }

    public static JSONObject get(Context context,ProjectStore projects,JSONObject request)throws Exception {
        fields(request,"projectId","programId","expectedProgramSha256","editOnly");if(request.has("editOnly")&&bool(request,"editOnly"))throw new IllegalArgumentException("Use the edit-only helper to reopen an unverified draft");String projectId=id(text(request,"projectId",96)),programId=id(text(request,"programId",96)),expected=optionalDigest(request,"expectedProgramSha256");final JSONObject saved;
        synchronized(FILE_LOCK){saved=readCurrent(context,projectId,programId);}String digest=saved.getString("sha256");if(!expected.isEmpty()&&!expected.equals(digest))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: selected program revision changed");JSONObject payload=withoutDigest(saved);validatePayload(payload,projectId,programId);
        ProjectStore.Project source=projects.get(projectId);if(source==null)throw new IllegalArgumentException("Source project unavailable");if(source.updatedAt!=payload.getLong("sourceProjectUpdatedAt"))throw new IllegalStateException("SOURCE_PROJECT_CHANGED: saved program source revision differs");
        JSONObject bundle=NativeCinematicBundleStore.get(context,projects,projectId,payload.getString("bundleId"));if(!payload.getString("bundleSha256").equals(bundle.getString("sha256"))||!payload.getString("bundleReceiptSha256").equals(bundle.getString("receiptSha256")))throw new IllegalStateException("SOURCE_BUNDLE_CHANGED: saved bundle proof differs");bindScenes(bundle,payload.getJSONObject("timeline"));
        SourceProof proof=measureSources(context,source,payload.getJSONObject("timeline"));requireSourceProof(payload,proof);
        return projects.guardedProject(projectId,source.updatedAt,current->{synchronized(FILE_LOCK){assertRegistration(current,proof);if(!digest.equals(readCurrent(context,projectId,programId).getString("sha256")))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: program changed during source verification");if(!payload.getString("bundleReceiptSha256").equals(cheapPayload(context,projectId,payload.getString("bundleId")).getString("receiptSha256")))throw new IllegalStateException("SOURCE_BUNDLE_CHANGED: bundle changed during verification");return receipt(payload,digest,bundle);}});
    }

    /** Reopen immutable authoring data, including stale sources, without authorizing execution. */
    public static JSONObject readForEdit(Context context,ProjectStore projects,JSONObject request)throws Exception {
        fields(request,"projectId","programId","expectedProgramSha256","editOnly");if(request.has("editOnly"))bool(request,"editOnly");String projectId=id(text(request,"projectId",96)),programId=id(text(request,"programId",96)),expected=optionalDigest(request,"expectedProgramSha256");ProjectStore.Project current=projects.get(projectId);if(current==null)throw new IllegalArgumentException("Source project unavailable");
        synchronized(FILE_LOCK){JSONObject saved=readCurrent(context,projectId,programId);String digest=saved.getString("sha256");if(!expected.isEmpty()&&!expected.equals(digest))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: selected editor revision differs");JSONObject payload=withoutDigest(saved),bundle=cheapPayload(context,projectId,payload.getString("bundleId"));
            if(!payload.getString("bundleSha256").equals(bundle.getString("sha256"))||!payload.getString("bundleReceiptSha256").equals(bundle.getString("receiptSha256")))throw new IllegalStateException("SOURCE_BUNDLE_CHANGED: saved editor metadata proof differs");
            return copy(payload).put("ok",true).put("sha256",digest).put("editorBundle",editorBundle(bundle)).put("editOnly",true).put("renderReady",false).put("sourceBytesVerified",false).put("artifactIntegrityVerified",false).put("nativeExecutionVerified",false).put("cinematicApproved",false).put("originalImageReconstruction",false)
                .put("currentSourceProjectUpdatedAt",current.updatedAt).put("sourceRevisionChanged",current.updatedAt!=payload.getLong("sourceProjectUpdatedAt")).put("requiresSourceRebinding",true).put("bakedPoseChangesRequireRebuild",true);
        }
    }

    /** Metadata only; listings do not authorize GPU work or imply source byte verification. */
    public static JSONObject list(Context context,ProjectStore projects,JSONObject request)throws Exception {
        fields(request,"projectId");String projectId=id(text(request,"projectId",96));ProjectStore.Project source=projects.get(projectId);if(source==null)throw new IllegalArgumentException("Source project unavailable");JSONArray items=new JSONArray();synchronized(FILE_LOCK){File[] dirs=directory(context,projectId,"programs",false).listFiles(File::isDirectory);if(dirs!=null){Arrays.sort(dirs,java.util.Comparator.comparing(File::getName));for(File dir:dirs){if(items.length()>=MAX_PROGRAMS)break;try{JSONObject item=readCurrent(context,projectId,id(dir.getName()));JSONObject timeline=item.getJSONObject("timeline");items.put(new JSONObject().put("programId",item.getString("programId")).put("sha256",item.getString("sha256")).put("bundleId",item.getString("bundleId")).put("name",timeline.getString("name")).put("shotCount",timeline.getJSONArray("shots").length()).put("sourceProjectUpdatedAt",item.getLong("sourceProjectUpdatedAt")).put("stale",item.getLong("sourceProjectUpdatedAt")!=source.updatedAt));}catch(Exception invalid){/* Malformed files never become admission proofs. */}}}}
        return new JSONObject().put("ok",true).put("projectId",projectId).put("programs",items).put("sourceBytesVerified",false).put("nativeExecutionVerified",false);
    }

    public static JSONObject plan(Context context,ProjectStore projects,JSONObject request)throws Exception {
        JSONObject out=get(context,projects,request),compiled=compile(out.getJSONObject("timeline"));return out.put("fps",compiled.getInt("fps")).put("totalFrames",compiled.getInt("totalFrames")).put("frameCount",compiled.getInt("totalFrames")).put("durationSeconds",compiled.getDouble("durationSeconds")).put("shots",compiled.getJSONArray("shots")).put("requiresNativeSceneValidation",true);
    }

    /** A portable inert producer request. No external code, model or Blender constraint runs here. */
    public static JSONObject exportRequest(Context context,ProjectStore projects,JSONObject request)throws Exception {
        JSONObject accepted=get(context,projects,request);JSONObject producer=new JSONObject().put("schema","videostudio.cinematic3d.export-request.v1").put("timeline",accepted.getJSONObject("timeline")).put("sourceProjectId",accepted.getString("projectId")).put("sourceProjectUpdatedAt",accepted.getLong("sourceProjectUpdatedAt")).put("sourceEntries",accepted.getJSONObject("sourceEntries")).put("bundleSha256",accepted.getString("bundleSha256")).put("programSha256",accepted.getString("sha256"));
        return accepted.put("exportRequest",producer).put("execution","request_only").put("bakedPoseChangesRequireRebuild",true);
    }

    public static void requireUnchanged(Context context,ProjectStore projects,JSONObject suppliedProgram,JSONObject suppliedBundle)throws Exception {
        JSONObject fresh=get(context,projects,new JSONObject().put("projectId",suppliedProgram.getString("projectId")).put("programId",suppliedProgram.getString("programId")).put("expectedProgramSha256",suppliedProgram.getString("sha256")));
        for(String key:new String[]{"projectId","programId","sha256","sourceProjectUpdatedAt","bundleId","bundleSha256","bundleReceiptSha256","timeline","sourceEntries","sourceRegistrationSha256","scenes"})if(!canonical(fresh.get(key)).equals(canonical(suppliedProgram.get(key))))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: supplied program receipt differs");
        JSONObject bundle=NativeCinematicBundleStore.get(context,projects,fresh.getString("projectId"),fresh.getString("bundleId"));for(String key:new String[]{"projectId","bundleId","sha256","receiptSha256","manifest","timeline","sourceEntries","sourceRegistrationSha256"})if(!canonical(bundle.get(key)).equals(canonical(suppliedBundle.get(key))))throw new IllegalStateException("SOURCE_BUNDLE_CHANGED: supplied bundle receipt differs");
    }

    /** Hash providers before the writer. The writer only rechecks exact registration and file receipts. */
    public static ProjectStore.Project publishRendered(Context context,ProjectStore projects,ProjectStore.Project source,ProjectStore.Project stagedOutput,JSONObject suppliedProgram,JSONObject suppliedBundle)throws Exception {
        if(source==null||stagedOutput==null||source.id.equals(stagedOutput.id)||source.updatedAt!=suppliedProgram.getLong("sourceProjectUpdatedAt"))throw new IllegalArgumentException("Fresh source-bound output project required");requireUnchanged(context,projects,suppliedProgram,suppliedBundle);
        SourceProof proof=measureSources(context,source,suppliedProgram.getJSONObject("timeline"));requireSourceProof(suppliedProgram,proof);
        return projects.guardedProject(source.id,source.updatedAt,current->{synchronized(FILE_LOCK){assertRegistration(current,proof);String programId=suppliedProgram.getString("programId");if(!suppliedProgram.getString("sha256").equals(readCurrent(context,source.id,programId).getString("sha256")))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: program changed before frame publication");if(!suppliedProgram.getString("bundleReceiptSha256").equals(cheapPayload(context,source.id,suppliedProgram.getString("bundleId")).getString("receiptSha256")))throw new IllegalStateException("SOURCE_BUNDLE_CHANGED: bundle changed before frame publication");
            return projects.guardedProject(stagedOutput.id,stagedOutput.updatedAt,output->{if(!output.assets.isEmpty()||!output.clips.isEmpty()||!output.studioLayers.isEmpty())throw new IllegalStateException("OUTPUT_PROJECT_CHANGED: cinematic output already contains a generation");interrupted();projects.save(stagedOutput);return stagedOutput;});
        }});
    }

    /** Resolve all clips once, keeping cuts and the exact literal shot order. */
    private static JSONArray bindScenes(JSONObject bundle,JSONObject timeline)throws Exception {
        JSONArray declared=bundle.getJSONObject("manifest").getJSONArray("scenes"),base=bundle.getJSONObject("timeline").getJSONArray("shots"),shots=timeline.getJSONArray("shots"),bound=new JSONArray();boolean library=bundle.getJSONObject("manifest").getJSONObject("generator").optBoolean("actionLibrary",false);
        if(!timeline.getString("motionProvider").equals(bundle.getJSONObject("timeline").getString("motionProvider")))throw new IllegalArgumentException("BAKED_POSE_REBUILD_REQUIRED: motion provider differs from bundle");
        Object actors=timeline.has("actors")?timeline.get("actors"):new JSONObject(),bakedActors=bundle.getJSONObject("timeline").has("actors")?bundle.getJSONObject("timeline").get("actors"):new JSONObject();
        if(!canonical(actors).equals(canonical(bakedActors)))throw new IllegalArgumentException("BAKED_POSE_REBUILD_REQUIRED: actor morphology/material changes need a rebuilt bundle");
        for(int i=0;i<shots.length();i++){JSONObject shot=shots.getJSONObject(i),scene=null,original=null;String id=shot.getString("id"),action=shot.getString("action");for(int j=0;j<declared.length();j++){JSONObject candidate=declared.getJSONObject(j);if(id.equals(candidate.getString("shotId"))&&action.equals(candidate.getString("action"))){scene=candidate;break;}}
            if(scene==null&&library)for(int j=0;j<declared.length();j++){JSONObject candidate=declared.getJSONObject(j);if(action.equals(candidate.getString("action"))){scene=candidate;break;}}
            if(scene==null)throw new IllegalArgumentException("BAKED_POSE_REBUILD_REQUIRED: no exact declared scene/action");for(int j=0;j<base.length();j++)if(scene.getString("shotId").equals(base.getJSONObject(j).getString("id")))original=base.getJSONObject(j);
            if(original==null)throw new IllegalArgumentException("Bundle baked shot missing");for(String key:new String[]{"sourceAnchors","poseKeyframes","footContacts"}){Object actual=shot.has(key)?shot.get(key):new JSONArray(),expected=original.has(key)?original.get(key):new JSONArray();if(!canonical(actual).equals(canonical(expected)))throw new IllegalArgumentException("BAKED_POSE_REBUILD_REQUIRED: source/pose/contact edits need a rebuilt bundle");}
            Object style=shot.has("motionStyle")?shot.get("motionStyle"):new JSONObject(),bakedStyle=original.has("motionStyle")?original.get("motionStyle"):new JSONObject();
            if(!canonical(style).equals(canonical(bakedStyle)))throw new IllegalArgumentException("BAKED_POSE_REBUILD_REQUIRED: motion-style changes need a rebuilt bundle");
            bound.put(copy(scene).put("shotId",id));
        }return bound;
    }
    private static JSONObject receipt(JSONObject payload,String digest,JSONObject bundle)throws Exception {return copy(payload).put("ok",true).put("sha256",digest).put("scenes",bindScenes(bundle,payload.getJSONObject("timeline"))).put("editorBundle",editorBundle(bundle)).put("sourceBytesVerified",true).put("artifactIntegrityVerified",true).put("nativeExecutionVerified",false).put("cinematicApproved",false).put("originalImageReconstruction",false).put("bakedPoseChangesRequireRebuild",true);}
    private static JSONObject editorBundle(JSONObject bundle)throws Exception {JSONObject manifest=bundle.getJSONObject("manifest"),projection=new JSONObject().put("generator",copy(manifest.getJSONObject("generator"))).put("scenes",new JSONArray(canonical(manifest.getJSONArray("scenes"))));return new JSONObject().put("manifest",projection).put("timeline",validate(bundle.getJSONObject("timeline")));}
    private static JSONObject readCurrent(Context context,String projectId,String programId)throws Exception {
        File dir=new File(directory(context,projectId,"programs",false),programId);plain(dir);JSONObject pointer=readJson(new File(dir,"_current.json"));fields(pointer,"schema","programId","sha256");String digest=digest(text(pointer,"sha256",64),false);
        if(!"videostudio.cinematic3d.program-pointer.v1".equals(pointer.getString("schema"))||!programId.equals(pointer.getString("programId")))throw new IllegalArgumentException("Program pointer identity differs");JSONObject payload=readJson(new File(dir,digest+".json"));validatePayload(payload,projectId,programId);if(!digest.equals(sha(payload)))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: immutable program bytes differ");return payload.put("sha256",digest);
    }
    private static void validatePayload(JSONObject p,String projectId,String programId)throws Exception {fields(p,"schema","projectId","sourceProjectUpdatedAt","programId","bundleId","bundleSha256","bundleReceiptSha256","timeline","sourceEntries","sourceRegistrationSha256");if(!SCHEMA.equals(p.getString("schema"))||!projectId.equals(p.getString("projectId"))||!programId.equals(p.getString("programId")))throw new IllegalArgumentException("Program identity/schema differs");id(p.getString("bundleId"));digest(p.getString("bundleSha256"),false);digest(p.getString("bundleReceiptSha256"),false);integer(p,"sourceProjectUpdatedAt",0,Long.MAX_VALUE);validate(p.getJSONObject("timeline"));object(p,"sourceEntries");object(p,"sourceRegistrationSha256");}
    private static JSONObject withoutDigest(JSONObject saved)throws Exception {JSONObject out=copy(saved);out.remove("sha256");return out;}
    private static String optionalDigest(JSONObject request,String key)throws Exception {if(!request.has(key))return "";Object raw=request.get(key);if(!(raw instanceof String))throw new IllegalArgumentException("Expected program digest must be a string");return digest((String)raw,true);}
    private static long storage(File root)throws Exception {plain(root);if(root.isFile())return root.length();long total=0;File[] children=root.listFiles();if(children==null)throw new IllegalStateException("Cannot measure program storage");for(File child:children)total=Math.addExact(total,storage(child));return total;}
}
