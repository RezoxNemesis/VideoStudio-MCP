package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.File;
import java.util.UUID;

/** Select only already-imported, hash-bound VideoStudio dialogue, never device Gallery. */
final class SketchSpeechAssets {
    private SketchSpeechAssets(){}
    static JSONArray choices(Context context)throws Exception {
        OpenPipelineStore store=new OpenPipelineStore(context);JSONArray records=store.list(),choices=new JSONArray();
        ProjectStore projects=new ProjectStore(context);
        for(int i=0;i<records.length();i++){
            JSONObject receipt=records.getJSONObject(i);
            if(!"kokoro".equals(receipt.optString("backend"))||!receipt.optBoolean("importReady"))continue;
            JSONObject record=store.get(receipt.getString("requestId"));ProjectStore.Project owner=projects.get(record.optString("outputProjectId"));
            if(owner==null)continue;
            JSONArray lines=record.getJSONObject("request").getJSONObject("controls").getJSONArray("lines");
            for(int n=0;n<lines.length();n++)choices.put(new JSONObject().put("requestId",record.getString("requestId")).put("lineIndex",n)
                    .put("label",owner.name+" · "+lines.getJSONObject(n).getString("text")));
        }
        return choices;
    }
    static JSONObject load(Context context,String requestId,int lineIndex,double durationMs)throws Exception {
        OpenPipelineStore store=new OpenPipelineStore(context);JSONObject record=store.get(requestId);
        if(!"kokoro".equals(record.optString("backend"))||!record.optBoolean("importReady"))throw SketchDocument.bad("Import the completed generated dialogue into VideoStudio first");
        ProjectStore.Project owner=new ProjectStore(context).get(record.getString("outputProjectId"));
        if(owner==null)throw SketchDocument.bad("The imported dialogue project no longer exists");
        File archive=new File(store.directory(requestId),requestId+".vsresult");
        if(!archive.isFile()||archive.length()!=record.getLong("resultBytes")||!record.getString("resultSha256").equals(OpenPipelineArtifacts.hash(archive)))throw SketchDocument.bad("Generated dialogue result archive changed");
        File stage=new File(store.directory(requestId),".speech_verify_"+UUID.randomUUID());JSONObject manifest;
        try{
            manifest=OpenPipelineArtifacts.verifyResult(archive,stage,record.getJSONObject("request"),record.getString("requestSha256"));
            if(!"completed".equals(manifest.getString("state"))||!record.getString("jobId").equals(manifest.getString("jobId")))throw SketchDocument.bad("Generated dialogue job identity changed");
        }finally{OpenPipelineArtifacts.deleteTree(stage);}
        File root=new File(new File(new CreativeWorkspace(context).projectRoot(owner.id),"exports"),"openpipeline_"+record.getString("resultSha256"));
        JSONArray files=manifest.getJSONArray("files");DialogueOutput.verify(root,record.getJSONObject("request"),files);
        JSONObject timing=null,master=null;
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i);
            if("dialogue_timing".equals(file.getString("role")))timing=OpenPipelineArtifacts.json(OpenPipelineArtifacts.target(root,file.getString("path")));
            if("audio".equals(file.getString("role")))master=file;
        }
        if(timing==null||master==null)throw SketchDocument.bad("Generated dialogue timing or master is missing");
        File audio=OpenPipelineArtifacts.target(root,master.getString("path"));ProjectStore.Asset asset=null;
        for(ProjectStore.Asset candidate:owner.assets){
            JSONObject meta=candidate.generationMetadata;
            if(candidate.generated&&"audio".equals(candidate.role)&&"audio/wav".equals(candidate.mime)
                    &&requestId.equals(meta.optString("requestId"))&&record.getString("resultSha256").equals(meta.optString("resultSha256"))
                    &&record.getString("requestSha256").equals(meta.optString("requestSha256"))&&master.getString("sha256").equals(meta.optString("artifactSha256"))){
                Uri uri=Uri.parse(candidate.uri);
                if("file".equals(uri.getScheme())&&audio.getCanonicalPath().equals(new File(uri.getPath()).getCanonicalPath()))asset=candidate;
            }
        }
        if(asset==null)throw SketchDocument.bad("The generated speech master is no longer owned by its imported VideoStudio project");
        JSONObject speech=SketchSpeechTiming.resolve(SketchSpeechTiming.fromDialogue(timing,lineIndex),durationMs);
        return new JSONObject().put("speech",speech).put("projectId",owner.id).put("assetId",asset.id).put("audioSha256",master.getString("sha256"))
                .put("text",timing.getJSONArray("lines").getJSONObject(lineIndex).getString("text"))
                .put("timingMethod","model predicted phoneme durations; review and edit cues against the original audio")
                .put("forcedAlignmentVerified",false).put("audioMixedIntoSketch",false);
    }
    static JSONObject expand(Context context,JSONObject doc,JSONObject operation)throws Exception {
        JSONObject speech=operation.optJSONObject("speech");
        if(speech==null||!speech.has("generated"))return operation;
        SketchDocument.fields(speech,"generated,transitionMs");JSONObject ref=speech.getJSONObject("generated");SketchDocument.fields(ref,"requestId,lineIndex");
        JSONObject loaded=load(context,SketchDocument.id(ref,"requestId"),(int)SketchDocument.integer(ref,"lineIndex",0,31),doc.getInt("frameCount")*1000d/doc.getInt("fps"));
        JSONObject resolved=loaded.getJSONObject("speech");if(speech.has("transitionMs"))resolved.put("transitionMs",speech.get("transitionMs"));
        SketchSpeechTiming.validate(resolved,doc.getInt("frameCount")*1000d/doc.getInt("fps"));
        return SketchDocument.copy(operation).put("speech",resolved);
    }
}
