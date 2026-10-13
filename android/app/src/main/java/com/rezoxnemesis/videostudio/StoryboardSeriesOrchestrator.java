package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.List;

/**
 * Runs a whole series of contiguous source art batches without a generic
 * skeleton fallback, without whole-frame dissolves and without touching the
 * owner’s original imported project. The service owns the guarded queue and
 * Media3 final export; this class owns art, time, audio, identity and staging.
 */
public final class StoryboardSeriesOrchestrator {
    public interface Progress {
        void update(int generated,int total,int batch,int shotIndex,
                    String provider,float confidence) throws Exception;
        default void onSaved(ProjectStore.Project output) throws Exception { }
    }
    public static final class Result {
        public final int frames,shots,batches;
        public final double minimumConfidence;
        public final JSONArray reports;
        public final boolean soundtrack,actionAudio;
        public final JSONObject audioEvidence;
        public final String sourceManifestSha256,semanticPlanSha256;
        Result(int frames,int shots,int batches,double confidence,
               JSONArray reports,boolean soundtrack,JSONObject audioEvidence,String sourceSha,String planSha) {
            this.frames=frames;this.shots=shots;this.batches=batches;
            minimumConfidence=confidence;this.reports=reports;
            this.soundtrack=soundtrack;this.audioEvidence=audioEvidence;
            actionAudio=soundtrack&&audioEvidence.optInt("audibleEventCount",0)>0;
            sourceManifestSha256=sourceSha;semanticPlanSha256=planSha;
        }
        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("ok",true)
                    .put("engine","videostudio.storyboard-multigenre-v1")
                    .put("frames",frames)
                    .put("shots",shots)
                    .put("batches",batches)
                    .put("minimumSegmentationConfidence",Double.isFinite(minimumConfidence)?minimumConfidence:JSONObject.NULL)
                    .put("confidenceStatus",Double.isFinite(minimumConfidence)?"MEASURED_SEGMENTATION_HEURISTIC":"UNAVAILABLE")
                    .put("perBatchReports",reports)
                    .put("sourceArtTexturePreserved",true).put("sourceManifestSha256",sourceManifestSha256).put("semanticPlanSha256",semanticPlanSha256)
                    .put("generatedFoley",soundtrack).put("actionAudio",actionAudio).put("foleyTimingEvidence",audioEvidence)
                    .put("decodedExportSynchronizationVerified",false).put("cinematicAcceptance",false)
                    .put("cinematicQaStatus","INCOMPLETE")
                    .put("neuralUnseenAnatomySynthesized",false)
                    .put("galleryAccess",false);
        }
    }
    private StoryboardSeriesOrchestrator(){}

    public static int predictedFrames(StoryboardBatchPlanner.Plan plan,int selected) {
        if(plan==null||selected<-1||selected>=plan.batches.size())
            throw new IllegalArgumentException("Invalid storyboard batch selection");
        int start=selected<0?0:selected;
        int end=selected<0?plan.batches.size():selected+1;
        int total=0;
        for(int i=start;i<end;i++)
            total+=plan.batches.get(i).expectedFrames-(i==start?0:1);
        if(total<2||total>1200)
            throw new IllegalArgumentException(
                    "Reduce per-pair frames or render one batch. Maximum 1200 output frames.");
        return total;
    }

    public static Result run(Context context,ProjectStore store,
                             ProjectStore.Project source,
                             ProjectStore.Project output,
                             StoryboardBatchPlanner.Plan plan,int selected,
                             int width,int height,JSONObject options,
                             Progress progress) throws Exception {
        if(context==null||store==null||source==null||output==null||plan==null
                ||source.id.equals(output.id)||!source.id.equals(plan.projectId))
            throw new IllegalArgumentException("Select a separate source-owned film project");
        if(options==null)options=new JSONObject();
        int expected=predictedFrames(plan,selected);
        int start=selected<0?0:selected;
        int end=selected<0?plan.batches.size():selected+1;
        boolean sound=options.optBoolean("foley",true);
        JSONObject isolated=new JSONObject(options.toString());
        isolated.put("foley",false);
        int produced=0,shots=0;
        double min=Double.NaN;
        java.util.ArrayList<StoryboardFoleySynth.CapturedEvent> captured=new java.util.ArrayList<>();
        JSONArray reports=new JSONArray();
        for(int index=start;index<end;index++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            final int batch=index,offset=produced;
            StoryboardSegmentComposer.Summary part=
                    StoryboardSegmentComposer.compose(context,store,source,
                            output,plan,index,width,height,isolated,
                            index>start,new StoryboardSegmentComposer.Progress(){
                                public void onFrame(int count,int budget,int shotId,String backend,float confidence) throws Exception {
                                    if(progress!=null)progress.update(offset+count,expected,batch,shotId,backend,confidence);
                                }
                                public void onSaved(ProjectStore.Project saved) throws Exception {
                                    isolated.put("_storyboardOutputUpdatedAt",saved.updatedAt);
                                    if(progress!=null)progress.onSaved(saved);
                                }
                            });
            produced+=part.generatedFrames;
            shots+=part.animatedShots;
            if(Double.isFinite(part.minimumConfidence))min=Double.isNaN(min)?part.minimumConfidence:Math.min(min,part.minimumConfidence);
            captured.addAll(part.capturedEvents);
            reports.put(new JSONObject()
                    .put("batchIndex",index)
                    .put("frames",part.generatedFrames)
                    .put("shots",part.animatedShots)
                    .put("providerReports",part.shotReport));
            if(progress!=null)progress.onSaved(output);
        }
        if(produced!=expected||output.clips.size()!=expected)
            throw new IllegalStateException(
                    "The continuous source-frame sequence has missing/duplicated animation frames");
        boolean soundtrack=false;
        JSONObject audioEvidence=new JSONObject().put("status","UNAVAILABLE").put("reason","No captured audible contact event")
                .put("decodedExportSynchronizationVerified",false);
        if(sound){
            File wav=new File(context.getFilesDir(),"creative_workspace/projects/"+output.id+
                    "/generated/storyboard_multigenre/"+java.util.UUID.randomUUID()+"/storyboard_foley.wav");
            float level=(float)Math.min(1,Math.max(.05,options.optDouble("foleyGain",.65)));
            StoryboardFoleySynth.Result audio;
            if(captured.isEmpty())audio=StoryboardFoleySynth.attachAmbience(output,wav,level);
            else {
                List<StoryboardFoleySynth.Cue> cues=StoryboardFoleySynth.cuesFromCapturedEvents(
                        captured,NativeFrameClock.presentationTimeUs(produced,plan.fps));
                audio=StoryboardFoleySynth.attach(output,wav,cues,level);
            }
            soundtrack=true;audioEvidence=audio.timingEvidence();
        }
        store.guardedProject(source.id,source.updatedAt,current->store.guardedProject(output.id,output.updatedAt,owned->{store.save(output);return null;}));
        if(progress!=null)progress.onSaved(output);
        String sourceSha=isolated.has("_storyboardSourceManifest")?isolated.getJSONObject("_storyboardSourceManifest").getString("manifestSha256"):null;
        String planSha=isolated.has("_storyboardAdmission")?isolated.getJSONObject("_storyboardAdmission").getString("planSha256"):null;
        return new Result(produced,shots,end-start,min,reports,soundtrack,audioEvidence,sourceSha,planSha);
    }
}
