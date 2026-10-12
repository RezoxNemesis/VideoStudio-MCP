package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The single native operation surface used by both the phone studio and MCP service. */
public final class OpenPipelineOperations {
    private OpenPipelineOperations() {}
    public static JSONObject execute(Context context,ProjectStore projects,String action,JSONObject parameters,DriveWorkspaceProvider.Progress progress)throws Exception {
        JSONObject p=parameters==null?new JSONObject():parameters;
        DriveWorkspaceProvider.Progress report=progress==null?(n,d)->{}:progress;
        OpenPipelineStore store=new OpenPipelineStore(context);
        switch(action){
            case "open_pipeline_configure":OpenPipelineArtifacts.fields(p,"endpoint","bearer");return store.configure(p.getString("endpoint"),p.getString("bearer"));
            case "open_pipeline_list":OpenPipelineArtifacts.fields(p);return new JSONObject().put("requests",store.list()).put("configuration",store.configuration());
            case "open_pipeline_capabilities":{
                OpenPipelineArtifacts.fields(p);JSONObject out=store.configuration();out.put("drive",new DriveWorkspaceProvider(context).status()).put("executionSurface","self-hosted-open-source-runner").put("paidInferenceRequired",false).put("nativeRendered",false);
                if(out.getBoolean("configured")){try{JSONObject runtime=new OpenPipelineClient(store).capabilities();out.put("reachable",true).put("runner",runtime);}catch(Exception unavailable){out.put("reachable",false).put("code","RUNNER_UNAVAILABLE");}}
                else out.put("reachable",false).put("code","RUNNER_NOT_CONFIGURED");return out;
            }
            case "open_pipeline_export_request":return export(context,projects,store,p,report);
            case "open_pipeline_submit":case "open_pipeline_status":case "open_pipeline_cancel":case "open_pipeline_download_result":case "open_pipeline_import_result":case "open_pipeline_archive_artifact":case "open_pipeline_restore_artifact":{
                if(action.equals("open_pipeline_archive_artifact"))OpenPipelineArtifacts.fields(p,"requestId","kind");
                else if(action.equals("open_pipeline_restore_artifact"))OpenPipelineArtifacts.fields(p,"requestId","name","sha256");
                else OpenPipelineArtifacts.fields(p,"requestId");
                String id=OpenPipelineArtifacts.id(p.getString("requestId"));
                synchronized(OpenPipelineStore.LOCK){JSONObject record=store.get(id);
                    if(action.equals("open_pipeline_import_result"))return importResult(context,projects,store,record,report);
                    if(action.equals("open_pipeline_archive_artifact"))return archive(context,store,record,p.getString("kind"),report);
                    if(action.equals("open_pipeline_restore_artifact"))return restore(context,store,record,p,report);
                    OpenPipelineClient client=new OpenPipelineClient(store);endpointFence(record,client.endpoint());
                    if(action.equals("open_pipeline_submit")){requireSources(context,projects,record,true);report.onProgress(5,"Submitting immutable request to your inference computer");JSONObject remote=client.submit(new File(store.directory(id),id+".vsjob"),id,record.getString("archiveSha256"));acceptRemote(record,remote);record.put("runnerEndpoint",client.endpoint());store.save(record);return OpenPipelineStore.receipt(record);}
                    String job=OpenPipelineArtifacts.id(record.getString("jobId"));
                    if(action.equals("open_pipeline_download_result"))return download(context,projects,store,record,client,report);
                    JSONObject remote=action.equals("open_pipeline_cancel")?client.cancel(job):client.status(job);acceptRemote(record,remote);store.save(record);return OpenPipelineStore.receipt(record);
                }
            }
            default:throw new IllegalArgumentException("Unsupported open pipeline action");
        }
    }
    static JSONObject validateControls(String backend,JSONObject input)throws Exception {
        JSONObject c=new JSONObject(input.toString());OpenPipelineArtifacts.validateJson(c,0,new int[]{0});
        switch(backend){
            case "cogvideox":
                OpenPipelineArtifacts.fields(c,"prompt","negativePrompt","width","height","numFrames","fps","seed","numInferenceSteps","guidanceScale","mode","strength");
                text(c,"prompt",10000,true);if(c.has("negativePrompt"))text(c,"negativePrompt",10000,false);
                OpenPipelineArtifacts.integer(c,"width",16,2048);OpenPipelineArtifacts.integer(c,"height",16,2048);OpenPipelineArtifacts.integer(c,"numFrames",1,241);OpenPipelineArtifacts.integer(c,"fps",1,60);OpenPipelineArtifacts.integer(c,"seed",0,2147483647);OpenPipelineArtifacts.integer(c,"numInferenceSteps",1,100);if(c.getLong("width")%16!=0||c.getLong("height")%16!=0||(c.getLong("numFrames")-1)%8!=0)throw new IllegalArgumentException("CogVideoX requires dimensions divisible by 16 and frame count 8k+1");
                if(!(c.get("guidanceScale") instanceof Number)||!Double.isFinite(c.getDouble("guidanceScale"))||c.getDouble("guidanceScale")<0||c.getDouble("guidanceScale")>30)throw new IllegalArgumentException("Invalid guidanceScale");String mode=c.optString("mode","text_to_video");if(c.has("mode")&&!(c.get("mode") instanceof String)||!Arrays.asList("text_to_video","video_to_video").contains(mode))throw new IllegalArgumentException("Unsupported CogVideoX conditioning mode");if(mode.equals("video_to_video")){if(!c.has("strength")||!(c.get("strength") instanceof Number)||!Double.isFinite(c.getDouble("strength"))||c.getDouble("strength")<.05||c.getDouble("strength")>1)throw new IllegalArgumentException("Video conditioning requires strength from 0.05 to 1.0");if(Math.floor(c.getDouble("strength")*c.getLong("numInferenceSteps"))<1)throw new IllegalArgumentException("V2V_ZERO_DENOISING_STEPS: increase strength or inference steps");}else if(c.has("strength"))throw new IllegalArgumentException("Strength applies only to video-to-video conditioning");break;
            case "blender":
                OpenPipelineArtifacts.fields(c,"timeline","conditioningFrames","renderFrames");c.put("timeline",Cinematic3DTimeline.validate(c.getJSONObject("timeline")));long totalFrames=0;JSONArray shots=c.getJSONObject("timeline").getJSONArray("shots");for(int i=0;i<shots.length();i++)totalFrames+=shots.getJSONObject(i).getLong("durationFrames");if(c.has("renderFrames"))OpenPipelineArtifacts.integer(c,"renderFrames",0,Math.min(totalFrames,12000));
                if(c.has("conditioningFrames")){JSONArray frames=c.getJSONArray("conditioningFrames");if(frames.length()>Math.min(totalFrames,512))throw new IllegalArgumentException("Too many conditioning frames");for(int i=0;i<frames.length();i++){Object f=frames.get(i);if(!(f instanceof Integer||f instanceof Long)||((Number)f).longValue()!=i)throw new IllegalArgumentException("Conditioning frames must be the contiguous prefix [0,1,...]");}}break;
            case "frame_sequence":OpenPipelineArtifacts.fields(c,"fps","width","height");OpenPipelineArtifacts.integer(c,"fps",1,60);OpenPipelineArtifacts.integer(c,"width",16,4096);OpenPipelineArtifacts.integer(c,"height",16,4096);if(c.getLong("width")%2!=0||c.getLong("height")%2!=0)throw new IllegalArgumentException("Video dimensions must be even");break;
            case "interpolation":
                OpenPipelineArtifacts.fields(c,"inputFps","outputFps","cuts","hitStopRanges");OpenPipelineArtifacts.integer(c,"inputFps",1,60);OpenPipelineArtifacts.integer(c,"outputFps",1,120);if(c.getLong("outputFps")<=c.getLong("inputFps"))throw new IllegalArgumentException("Interpolation outputFps must increase");
                if(c.has("cuts")){JSONArray cuts=c.getJSONArray("cuts");if(cuts.length()>80)throw new IllegalArgumentException("Too many cut markers");long last=-1;for(int i=0;i<cuts.length();i++){Object n=cuts.get(i);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<0||((Number)n).longValue()<=last||((Number)n).longValue()>11999)throw new IllegalArgumentException("Cuts must be ordered frame indices");last=((Number)n).longValue();}}
                if(c.has("hitStopRanges")){JSONArray holds=c.getJSONArray("hitStopRanges");if(holds.length()>80)throw new IllegalArgumentException("Too many hold ranges");for(int i=0;i<holds.length();i++){JSONArray range=holds.getJSONArray(i);if(range.length()!=2||!(range.get(0) instanceof Integer||range.get(0) instanceof Long)||!(range.get(1) instanceof Integer||range.get(1) instanceof Long)||range.getLong(0)<0||range.getLong(1)<range.getLong(0)||range.getLong(1)>11999)throw new IllegalArgumentException("Invalid hit-stop range");}}break;
            default:throw new IllegalArgumentException("Use an installed open-source backend");
        }
        if(c.toString().getBytes(StandardCharsets.UTF_8).length>OpenPipelineArtifacts.MAX_JSON)throw new IllegalArgumentException("Controls exceed metadata limit");return c;
    }
    static void text(JSONObject value,String key,int limit,boolean nonempty)throws Exception {Object raw=value.get(key);if(!(raw instanceof String)||((String)raw).length()>limit||((String)raw).indexOf('\0')>=0||(nonempty&&((String)raw).trim().isEmpty()))throw new IllegalArgumentException("Invalid text: "+key);}
    static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();Iterator<String> it=o.keys();while(it.hasNext())keys.add(it.next());StringBuilder b=new StringBuilder("{");for(String key:keys){if(b.length()>1)b.append(',');b.append(JSONObject.quote(key)).append(':').append(canonical(o.get(key)));}return b.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)b.append(',');b.append(canonical(a.get(i)));}return b.append(']').toString();}String scalar=new JSONArray().put(value).toString();return scalar.substring(1,scalar.length()-1);
    }
    private static JSONObject export(Context context,ProjectStore projects,OpenPipelineStore store,JSONObject p,DriveWorkspaceProvider.Progress progress)throws Exception {
        OpenPipelineArtifacts.fields(p,"projectId","sourceProjectUpdatedAt","requestId","backend","controls","assetIds");String id=OpenPipelineArtifacts.id(p.getString("requestId")),projectId=OpenPipelineArtifacts.id(p.getString("projectId"));long revision=OpenPipelineArtifacts.integer(p,"sourceProjectUpdatedAt",0,Long.MAX_VALUE);String backend=p.getString("backend");JSONObject controls=validateControls(backend,p.getJSONObject("controls"));JSONArray ids=p.getJSONArray("assetIds");if(ids.length()>64||(backend.equals("frame_sequence")&&ids.length()<1)||(backend.equals("interpolation")&&ids.length()<2))throw new IllegalArgumentException("Backend source count unavailable");
        ProjectStore.Project source=projects.get(projectId);if(source==null||source.updatedAt!=revision)throw new IllegalStateException("SOURCE_PROJECT_CHANGED: reopen this project");if(backend.equals("cogvideox"))validateCogSources(controls,ids,source);if(backend.equals("frame_sequence")||backend.equals("interpolation")){for(int i=0;i<ids.length();i++){ProjectStore.Asset asset=source.asset(OpenPipelineArtifacts.id(ids.getString(i)));if(asset==null||!"image/png".equals(asset.mime))throw new IllegalArgumentException("This backend requires an ordered selection of project-owned PNG frames");}if(backend.equals("interpolation")){JSONArray cuts=controls.optJSONArray("cuts");if(cuts!=null)for(int i=0;i<cuts.length();i++)if(cuts.getLong(i)<=0||cuts.getLong(i)>=ids.length())throw new IllegalArgumentException("Cuts must be interior source-frame boundaries");JSONArray holds=controls.optJSONArray("hitStopRanges");long previous=-1;if(holds!=null)for(int i=0;i<holds.length();i++){JSONArray range=holds.getJSONArray(i);if(range.getLong(0)<previous||range.getLong(0)>=range.getLong(1)||range.getLong(1)>ids.length())throw new IllegalArgumentException("Hold ranges must be nonoverlapping, ordered and end-exclusive source-frame ranges");previous=range.getLong(1);}}}String admission=OpenPipelineArtifacts.hash(canonical(new JSONObject().put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("backend",backend).put("controls",controls).put("assetIds",ids)).getBytes(StandardCharsets.UTF_8));
        synchronized(OpenPipelineStore.LOCK){File destination=store.directory(id);if(destination.exists()){JSONObject existing=store.get(id);if(!admission.equals(existing.getString("admissionSha256")))throw new IllegalStateException("IDEMPOTENCY_CONFLICT: requestId already has different controls or sources");requireSources(context,projects,existing,true);return OpenPipelineStore.receipt(existing);}
            File[] retained=store.root().listFiles(File::isDirectory);if(retained!=null&&retained.length>=64)throw new IllegalStateException("Pipeline request limit reached; preserve/archive finished jobs before adding requests");if(store.retainedBytes()>2L*1024*1024*1024-2*OpenPipelineArtifacts.MAX_ARCHIVE)throw new IllegalStateException("Pipeline journal exceeds its 2 GiB storage budget");if(store.root().getUsableSpace()<2*OpenPipelineArtifacts.MAX_ARCHIVE+ResumableTransferManager.STORAGE_RESERVE_BYTES)throw new IllegalStateException("Insufficient private storage for source snapshots, job archive and reserve");
            File stage=new File(store.root(),".stage_"+UUID.randomUUID());if(!stage.mkdirs())throw new IOException("Cannot stage request");boolean accepted=false;
            try{JSONArray sources=new JSONArray();JSONObject registrations=new JSONObject();Set<String> seen=new HashSet<>();long total=0;
                for(int i=0;i<ids.length();i++){String assetId=OpenPipelineArtifacts.id(ids.getString(i));if(!seen.add(assetId))throw new IllegalArgumentException("Duplicate source asset");ProjectStore.Asset asset=source.asset(assetId);if(asset==null)throw new IllegalArgumentException("Source asset must belong to this project");String relative="inputs/"+i+extension(asset.mime);File file=OpenPipelineArtifacts.target(stage,relative);if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException("Cannot stage source");long size;
                    try(InputStream in=ownedInput(context,source,asset);FileOutputStream out=new FileOutputStream(file)){size=OpenPipelineArtifacts.copy(in,out,OpenPipelineArtifacts.MAX_ARCHIVE-total);out.getFD().sync();}
                    if(size<1||(asset.sizeBytes>0&&size!=asset.sizeBytes))throw new IllegalArgumentException("Source snapshot incomplete");total+=size;sources.put(new JSONObject().put("assetId",assetId).put("path",relative).put("bytes",size).put("sha256",OpenPipelineArtifacts.hash(file)).put("mime",asset.mime));registrations.put(assetId,OpenPipelineArtifacts.hash(canonical(asset.toJson()).getBytes(StandardCharsets.UTF_8)));progress.onProgress(5+(int)(55d*(i+1)/Math.max(1,ids.length())),"Snapshotting explicitly selected source "+(i+1)+"/"+ids.length());
                }
                if("blender".equals(backend))OpenPipelineRequestTemplate.verifySourceBindings(controls,sources);
                JSONObject request=new JSONObject().put("schema",OpenPipelineArtifacts.REQUEST_SCHEMA).put("requestId",id).put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("backend",backend).put("controls",controls).put("sources",sources);byte[] raw=request.toString().getBytes(StandardCharsets.UTF_8);OpenPipelineArtifacts.writeAtomic(new File(stage,"request.json"),raw);File archive=new File(stage,id+".vsjob");OpenPipelineArtifacts.packageRequest(stage,request,archive);
                JSONObject record=new JSONObject().put("requestId",id).put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("backend",backend).put("request",request).put("sourceRegistrations",registrations).put("admissionSha256",admission).put("requestSha256",OpenPipelineArtifacts.hash(raw)).put("archiveSha256",OpenPipelineArtifacts.hash(archive)).put("archiveBytes",archive.length()).put("state","exported").put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis());requireSources(context,projects,record,true);
                OpenPipelineArtifacts.writeAtomic(new File(stage,"record.json"),record.toString().getBytes(StandardCharsets.UTF_8),OpenPipelineStore.MAX_RECORD_BYTES);if(!stage.renameTo(destination))throw new IOException("Cannot publish immutable request");accepted=true;progress.onProgress(100,"Hash-bound request ready for your runner or linked Drive folder");return OpenPipelineStore.receipt(record);
            }finally{if(!accepted)OpenPipelineArtifacts.deleteTree(stage);}
        }
    }
    static void validateCogSources(JSONObject controls,JSONArray ids,ProjectStore.Project project)throws Exception {
        String mode=controls.optString("mode","text_to_video");
        if(mode.equals("text_to_video")){if(ids.length()!=0)throw new IllegalArgumentException("Text-to-video accepts no reference sources; select video-to-video for a motion reference");return;}
        if(!mode.equals("video_to_video")||ids.length()!=1)throw new IllegalArgumentException("Video-to-video requires exactly one project-owned MP4 motion reference");
        ProjectStore.Asset asset=project.asset(OpenPipelineArtifacts.id(ids.getString(0)));
        if(asset==null||!"video/mp4".equals(asset.mime)||asset.durationMs<0||asset.durationMs>600000)throw new IllegalArgumentException("Video-to-video reference must be a bounded MP4 registered in this source project");
    }
    private static String extension(String mime){if("image/png".equals(mime))return ".png";if("image/jpeg".equals(mime))return ".jpg";if("image/webp".equals(mime))return ".webp";if("video/mp4".equals(mime))return ".mp4";if("application/json".equals(mime))return ".json";if("model/gltf-binary".equals(mime))return ".glb";if("audio/wav".equals(mime))return ".wav";return ".bin";}
    private static InputStream ownedInput(Context context,ProjectStore.Project project,ProjectStore.Asset asset)throws Exception {
        Uri uri=Uri.parse(asset.uri);if("file".equals(uri.getScheme())){File file=new File(uri.getPath());if(!file.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator)||java.nio.file.Files.isSymbolicLink(file.toPath()))throw new IllegalArgumentException("Source file must be explicitly imported into VideoStudio storage");}
        else if("content".equals(uri.getScheme())){if(!asset.persistedReadAccess&&!(asset.generated&&DriveWorkspaceProvider.isProjectPublishedMp4(project)&&asset.uri.equals(project.latestExportUri)))throw new IllegalArgumentException("Source document requires its persisted explicit picker permission");}
        else throw new IllegalArgumentException("Source must be an owned file or explicitly selected document");InputStream in=context.getContentResolver().openInputStream(uri);if(in==null)throw new IllegalArgumentException("Selected source unavailable");return in;
    }
    private static void requireSources(Context context,ProjectStore projects,JSONObject record,boolean rehash)throws Exception {
        JSONObject request=record.getJSONObject("request");ProjectStore.Project project=projects.get(record.getString("projectId"));if(project==null||project.updatedAt!=record.getLong("sourceProjectUpdatedAt"))throw new IllegalStateException("SOURCE_PROJECT_CHANGED: pending generation uses another revision");JSONArray sources=request.getJSONArray("sources");JSONObject registrations=record.getJSONObject("sourceRegistrations");
        for(int i=0;i<sources.length();i++){JSONObject source=sources.getJSONObject(i);String id=source.getString("assetId");ProjectStore.Asset asset=project.asset(id);if(asset==null||!registrations.getString(id).equals(OpenPipelineArtifacts.hash(canonical(asset.toJson()).getBytes(StandardCharsets.UTF_8))))throw new IllegalStateException("SOURCE_ASSET_CHANGED: pending source registration differs");if(rehash){java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long size=0;try(InputStream in=ownedInput(context,project,asset)){byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){OpenPipelineArtifacts.interrupted();size+=n;if(size>source.getLong("bytes"))throw new IllegalStateException("SOURCE_BYTES_CHANGED");digest.update(b,0,n);}}if(size!=source.getLong("bytes")||!source.getString("sha256").equals(OpenPipelineArtifacts.hex(digest.digest())))throw new IllegalStateException("SOURCE_BYTES_CHANGED: original differs from generation request");}}
    }
    private static void endpointFence(JSONObject record,String endpoint)throws Exception {if(record.has("runnerEndpoint")&&!endpoint.equals(record.getString("runnerEndpoint")))throw new IllegalStateException("RUNNER_CHANGED: this job belongs to the previously configured runner");}
    private static void acceptRemote(JSONObject record,JSONObject remote)throws Exception {
        if(!record.getString("requestId").equals(remote.getString("requestId"))||!record.getString("requestSha256").equals(remote.getString("requestSha256")))throw new IllegalStateException("RUNNER_RECEIPT_MISMATCH: request identity differs");String job=OpenPipelineArtifacts.id(remote.getString("jobId"));if(record.has("jobId")&&!job.equals(record.getString("jobId")))throw new IllegalStateException("RUNNER_JOB_CHANGED: idempotent job identity differs");String state=remote.getString("state");if(!Arrays.asList("queued","running","checkpointed","waiting_compute","waiting_model","completed","failed","cancelled","expired","cancelling").contains(state))throw new IllegalStateException("Unknown runner job state");String previous=record.optString("state");if(Arrays.asList("completed","failed","cancelled","expired","imported").contains(previous)&&!previous.equals(state)&&!previous.equals("imported"))throw new IllegalStateException("RUNNER_TERMINAL_CONFLICT");record.put("jobId",job).put("state",previous.equals("imported")?"imported":state).put("updatedAt",System.currentTimeMillis());
        for(String key:new String[]{"stage","code"})if(remote.has(key)&&!remote.isNull(key)){String value=remote.getString(key);if(value.length()>200)throw new IllegalArgumentException("Runner status text exceeds limit");record.put(key,value);}for(String key:new String[]{"heartbeatAt","deadlineAt"})if(remote.has(key))record.put(key,OpenPipelineArtifacts.integer(remote,key,0,Long.MAX_VALUE));if(remote.has("progress")){Object value=remote.get("progress");OpenPipelineArtifacts.validateJson(value,0,new int[]{0});record.put("progress",value);}if(remote.has("resultSha256")){String digest=OpenPipelineArtifacts.sha(remote.getString("resultSha256"));if(record.has("resultSha256")&&!digest.equals(record.getString("resultSha256")))throw new IllegalStateException("IMMUTABLE_RESULT_CHANGED");record.put("resultSha256",digest).put("resultBytes",OpenPipelineArtifacts.integer(remote,"resultBytes",1,OpenPipelineArtifacts.MAX_ARCHIVE));}
    }
    private static JSONObject download(Context context,ProjectStore projects,OpenPipelineStore store,JSONObject record,OpenPipelineClient client,DriveWorkspaceProvider.Progress progress)throws Exception {
        requireSources(context,projects,record,true);JSONObject status=client.status(record.getString("jobId"));acceptRemote(record,status);if(!Arrays.asList("completed","imported").contains(record.getString("state")))throw new IllegalStateException("RESULT_NOT_READY: inference must finish before import");JSONObject descriptor=client.resultDescriptor(record.getString("jobId"));if(record.has("resultSha256")&&!record.getString("resultSha256").equals(descriptor.getString("sha256")))throw new IllegalStateException("IMMUTABLE_RESULT_CHANGED");record.put("resultSha256",descriptor.getString("sha256")).put("resultBytes",descriptor.getLong("bytes"));store.save(record);File target=new File(store.directory(record.getString("requestId")),record.getString("requestId")+".vsresult");
        if(!target.isFile()){try{new ResumableTransferManager(new TransferJournal(context)).download(new ResumableTransferManager.Request("open_"+record.getString("requestId"),client.resultUrl(record.getString("jobId")),target,descriptor.getLong("bytes"),descriptor.getString("sha256")),(url,offset,etag,modified)->client.resultConnection(record.getString("jobId"),offset,etag,modified),(done,expected)->reportDownloadProgress(progress,(int)Math.min(95,100d*done/Math.max(1,expected))));}catch(ProgressFailure failed){throw failed.failure;}}
        if(target.length()!=record.getLong("resultBytes")||!record.getString("resultSha256").equals(OpenPipelineArtifacts.hash(target)))throw new IllegalStateException("RESULT_ARCHIVE_CHANGED");verifyPendingResult(store,record);store.save(record);progress.onProgress(100,"Result downloaded and hash verified; import is a separate native operation");return OpenPipelineStore.receipt(record).put("resultVerified",true);
    }
    private static final class ProgressFailure extends RuntimeException {final Exception failure;ProgressFailure(Exception failure){super(failure);this.failure=failure;}}
    private static void reportDownloadProgress(DriveWorkspaceProvider.Progress progress,int value){try{progress.onProgress(value,"Downloading immutable generation result");}catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new ProgressFailure(failure);}}
    private static JSONObject verifyPendingResult(OpenPipelineStore store,JSONObject record)throws Exception {File directory=store.directory(record.getString("requestId")),archive=new File(directory,record.getString("requestId")+".vsresult"),stage=new File(directory,".verify_"+UUID.randomUUID());if(!record.getString("resultSha256").equals(OpenPipelineArtifacts.hash(archive)))throw new IllegalStateException("RESULT_ARCHIVE_CHANGED");try{JSONObject manifest=OpenPipelineArtifacts.verifyResult(archive,stage,record.getJSONObject("request"),record.getString("requestSha256"));if(!"completed".equals(manifest.getString("state"))||!record.getString("jobId").equals(manifest.getString("jobId")))throw new IllegalStateException("Result is not the completed pending job");return manifest;}finally{OpenPipelineArtifacts.deleteTree(stage);}}
    private static JSONObject importResult(Context context,ProjectStore projects,OpenPipelineStore store,JSONObject record,DriveWorkspaceProvider.Progress progress)throws Exception {
        requireSources(context,projects,record,true);
        if(!Arrays.asList("completed","imported").contains(record.getString("state")))throw new IllegalStateException("RESULT_NOT_READY");
        JSONObject manifest=verifyPendingResult(store,record);
        String outputId=UUID.nameUUIDFromBytes(("openpipeline:"+record.getString("requestId")+":"+record.getString("resultSha256")).getBytes(StandardCharsets.UTF_8)).toString();
        ProjectStore.Project existing=projects.get(outputId);
        // The remote job may be complete while native installation is unfinished.
        // Persist that distinction before inspecting/reconciling any local output.
        record.remove("nativeInstallations");record.put("state","completed").put("importReady",false).put("outputProjectId",outputId);
        store.save(record);
        if(existing!=null){
            projects.guardedProject(record.getString("projectId"),record.getLong("sourceProjectUpdatedAt"),source->
                projects.guardedProject(existing.id,existing.updatedAt,current->{
                    requireSources(context,projects,record,true);
                    reconcileNativeArtifacts(context,projects,store,current,record,manifest);
                    return true;
                }));
            return completeNativeImport(store,record,progress);
        }
        File parent=new File(new CreativeWorkspace(context).projectRoot(outputId),"exports");
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create output storage");
        File outputFiles=new File(parent,"openpipeline_"+record.getString("resultSha256")),stage=new File(store.root(),".import_"+UUID.randomUUID());
        File archive=new File(store.directory(record.getString("requestId")),record.getString("requestId")+".vsresult");boolean published=false;
        try{
            OpenPipelineArtifacts.verifyResult(archive,stage,record.getJSONObject("request"),record.getString("requestSha256"));
            JSONArray files=manifest.getJSONArray("files");int usable=0,bundles=0;
            for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if(primary(file)){usable++;if("video".equals(file.getString("role")))probeVideo(new File(stage,file.getString("path")));else bundles++;}}
            if(usable<1||bundles>8)throw new IllegalArgumentException("Result requires usable MP4 outputs and at most eight cinematic bundles");
            requireSources(context,projects,record,true);
            if(outputFiles.exists()){
                OpenPipelineArtifacts.deleteTree(stage);
                for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);File actual=OpenPipelineArtifacts.target(outputFiles,file.getString("path"));if(!actual.isFile()||actual.length()!=file.getLong("bytes")||!file.getString("sha256").equals(OpenPipelineArtifacts.hash(actual)))throw new IllegalStateException("Existing immutable output changed");}
            }else if(!stage.renameTo(outputFiles))throw new IOException("Cannot publish output files");
            published=true;
            projects.guardedProject(record.getString("projectId"),record.getLong("sourceProjectUpdatedAt"),source->{
                requireSources(context,projects,record,true);
                ProjectStore.Project output=new ProjectStore.Project();output.id=outputId;output.name="Open Pipeline • "+source.name;
                output.sourcePrompt=record.getJSONObject("request").getJSONObject("controls").optString("prompt","");
                boolean bundle=false;for(int i=0;i<files.length();i++)if("cinematic_bundle".equals(files.getJSONObject(i).optString("role")))bundle=true;
                if(bundle)for(ProjectStore.Asset original:source.assets)output.assets.add(ProjectStore.Asset.fromJson(original.toJson()));
                projects.save(output);
                // Register every artifact before installing bundles, so all native
                // installation receipts bind the final stable asset registrations.
                for(int i=0;i<files.length();i++){
                    JSONObject file=files.getJSONObject(i);if(!primary(file))continue;String name=file.getString("path");
                    ProjectStore.Asset asset=projects.registerGeneratedAsset(output,Uri.fromFile(new File(outputFiles,name)),new File(name).getName(),file.getString("role"),"video".equals(file.getString("role")));
                    String registrationId=asset.id;asset.id=UUID.nameUUIDFromBytes((record.getString("requestId")+":"+name+":"+file.getString("sha256")).getBytes(StandardCharsets.UTF_8)).toString();
                    // registerGeneratedAsset created a clip using its initial ID.
                    for(ProjectStore.Clip clip:output.clips)if(registrationId.equals(clip.assetId))clip.assetId=asset.id;
                    asset.createdAt=record.getLong("createdAt");asset.mime=file.getString("mime");asset.sizeBytes=file.getLong("bytes");
                    asset.generationMetadata=new JSONObject().put("provider","self-hosted-open-source:"+record.getString("backend")).put("requestId",record.getString("requestId")).put("requestSha256",record.getString("requestSha256")).put("resultSha256",record.getString("resultSha256")).put("artifactSha256",file.getString("sha256")).put("nativeRendered",false).put("cinematicAcceptance",false).put("originalImageReconstruction",false).put("producerEvidenceVerified",false);
                    if("cogvideox".equals(record.getString("backend")))asset.generationMetadata.put("conditioningMode",record.getJSONObject("request").getJSONObject("controls").optString("mode","text_to_video"));
                    projects.save(output);
                }
                reconcileNativeArtifacts(context,projects,store,output,record,manifest);
                return true;
            });
            // Publish imported only after the project transaction committed and
            // every required native artifact has a freshly verified receipt.
            return completeNativeImport(store,record,progress);
        }finally{if(!published)OpenPipelineArtifacts.deleteTree(stage);}
    }
    private static JSONObject completeNativeImport(OpenPipelineStore store,JSONObject record,DriveWorkspaceProvider.Progress progress)throws Exception {
        record.put("state","imported").put("importReady",true).put("updatedAt",System.currentTimeMillis());store.save(record);
        progress.onProgress(100,"Verified cloud outputs imported into a separate native project");
        return OpenPipelineStore.receipt(record).put("imported",true).put("mediaContainerVerified",true).put("decodedVideoVerified",false);
    }
    private static void reconcileNativeArtifacts(Context context,ProjectStore projects,OpenPipelineStore store,ProjectStore.Project output,JSONObject record,JSONObject manifest)throws Exception {
        JSONObject receipts=new JSONObject();JSONArray files=manifest.getJSONArray("files");int usable=0;
        for(int i=0;i<files.length();i++){
            JSONObject descriptor=files.getJSONObject(i);if(!primary(descriptor))continue;usable++;
            File expectedFile=OpenPipelineArtifacts.target(new File(new CreativeWorkspace(context).projectRoot(output.id),"exports/openpipeline_"+record.getString("resultSha256")),descriptor.getString("path"));
            ProjectStore.Asset matching=null;
            for(ProjectStore.Asset asset:output.assets)if(asset.generated&&record.getString("requestId").equals(asset.generationMetadata.optString("requestId"))&&record.getString("resultSha256").equals(asset.generationMetadata.optString("resultSha256"))&&descriptor.getString("sha256").equals(asset.generationMetadata.optString("artifactSha256"))&&descriptor.getString("role").equals(asset.role)&&descriptor.getString("mime").equals(asset.mime)&&"file".equals(Uri.parse(asset.uri).getScheme())&&expectedFile.getCanonicalPath().equals(new File(Uri.parse(asset.uri).getPath()).getCanonicalPath())){matching=asset;break;}
            if(matching==null||!"file".equals(Uri.parse(matching.uri).getScheme()))throw new IllegalStateException("Imported output registration changed; preserve the user's current project");
            File file=new File(Uri.parse(matching.uri).getPath());
            if(!file.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator)||file.length()!=descriptor.getLong("bytes")||!descriptor.getString("sha256").equals(OpenPipelineArtifacts.hash(file)))throw new IllegalStateException("Imported artifact bytes changed");
            JSONObject receipt=new JSONObject().put("assetId",matching.id).put("artifactSha256",descriptor.getString("sha256")).put("role",descriptor.getString("role"));
            if("cinematic_bundle".equals(descriptor.getString("role"))){
                // install() reconciles missing installations; get() rechecks the
                // activated archive, scenes and bound original source bytes.
                JSONObject installed=NativeCinematicBundleStore.install(context,projects,new JSONObject().put("projectId",output.id).put("sourceProjectUpdatedAt",output.updatedAt).put("bundleAssetId",matching.id));
                JSONObject verified=NativeCinematicBundleStore.get(context,projects,output.id,installed.getString("bundleId"));
                if(!descriptor.getString("sha256").equals(verified.getString("sha256"))||!verified.getBoolean("artifactIntegrityVerified"))throw new IllegalStateException("Native bundle installation differs from the artifact");
                receipt.put("bundleId",verified.getString("bundleId")).put("receiptSha256",verified.getString("receiptSha256")).put("nativeInstallationVerified",true);
            }else {probeVideo(file);receipt.put("mediaContainerVerified",true).put("decodedVideoVerified",false);}
            receipts.put(descriptor.getString("path"),receipt);
            record.put("nativeInstallations",receipts);store.save(record);
        }
        if(usable==0)throw new IllegalStateException("Output has no required native artifacts");
    }
    static boolean primary(JSONObject file)throws Exception {String role=file.getString("role"),path=file.getString("path"),mime=file.getString("mime");if(role.equals("video")){if(!path.endsWith(".mp4")||!mime.equals("video/mp4"))throw new IllegalArgumentException("Video output descriptor differs");return true;}if(role.equals("cinematic_bundle")){if(!path.endsWith(".vscinematic")||!mime.equals("application/vnd.videostudio.cinematic3d"))throw new IllegalArgumentException("Cinematic output descriptor differs");return true;}return false;}
    private static void probeVideo(File file)throws Exception {MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(file.getAbsolutePath());String has=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);int width=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),height=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));long duration=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));if(!"yes".equals(has)||width<1||height<1||width>4096||height>4096||duration<1||duration>600000)throw new IllegalArgumentException("MP4 container lacks bounded usable video");}finally{retriever.release();}}
    private static JSONObject archive(Context context,OpenPipelineStore store,JSONObject record,String kind,DriveWorkspaceProvider.Progress progress)throws Exception {
        if(!Arrays.asList("request","result").contains(kind))throw new IllegalArgumentException("Archive kind must be request or result");String id=record.getString("requestId");if(kind.equals("result"))verifyPendingResult(store,record);String extension=kind.equals("request")?".vsjob":".vsresult",hash=record.getString(kind.equals("request")?"archiveSha256":"resultSha256");JSONObject receipt=new DriveWorkspaceProvider(context).archiveArtifact(record.getString("projectId"),kind.equals("request")?"Requests":"Results",new File(store.directory(id),id+extension),hash,progress);if(!receipt.optBoolean("verified"))throw new IllegalStateException("Drive artifact verification incomplete");record.put(kind.equals("request")?"driveRequestArchived":"driveResultArchived",true);store.save(record);return OpenPipelineStore.receipt(record).put("archive",receipt);
    }
    private static JSONObject restore(Context context,OpenPipelineStore store,JSONObject record,JSONObject p,DriveWorkspaceProvider.Progress progress)throws Exception {
        String id=record.getString("requestId"),name=p.getString("name");if(!name.equals(id+".vsresult"))throw new IllegalArgumentException("Restore only the pending request's immutable result");String sha=OpenPipelineArtifacts.sha(p.getString("sha256"));if(record.has("resultSha256")&&!sha.equals(record.getString("resultSha256")))throw new IllegalStateException("Result checksum conflicts with retained receipt");File target=new File(store.directory(id),name);JSONObject archived=new DriveWorkspaceProvider(context).restoreArtifact(record.getString("projectId"),"Results",name,target,sha,progress);record.put("resultSha256",sha).put("resultBytes",target.length());File stage=new File(store.directory(id),".verify_"+UUID.randomUUID());JSONObject manifest;try{manifest=OpenPipelineArtifacts.verifyResult(target,stage,record.getJSONObject("request"),record.getString("requestSha256"));if(!"completed".equals(manifest.getString("state")))throw new IllegalArgumentException("Drive result is not completed");acceptRemote(record,manifest);}finally{OpenPipelineArtifacts.deleteTree(stage);}store.save(record);return OpenPipelineStore.receipt(record).put("resultVerified",true).put("archive",archived);
    }
}
