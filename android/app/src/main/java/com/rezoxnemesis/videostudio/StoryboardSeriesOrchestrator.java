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
    }
    public static final class Result {
        public final int frames,shots,batches;
        public final double minimumConfidence;
        public final JSONArray reports;
        public final boolean soundtrack;
        Result(int frames,int shots,int batches,double confidence,
               JSONArray reports,boolean soundtrack) {
            this.frames=frames;this.shots=shots;this.batches=batches;
            minimumConfidence=confidence;this.reports=reports;
            this.soundtrack=soundtrack;
        }
        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("ok",true)
                    .put("engine","videostudio.storyboard-multigenre-v1")
                    .put("frames",frames)
                    .put("shots",shots)
                    .put("batches",batches)
                    .put("minimumSegmentationConfidence",minimumConfidence)
                    .put("perBatchReports",reports)
                    .put("sourceArtTexturePreserved",true)
                    .put("synchronizedGeneratedFoley",soundtrack)
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
        double min=1;
        JSONArray reports=new JSONArray();
        for(int index=start;index<end;index++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            final int batch=index,offset=produced;
            StoryboardSegmentComposer.Summary part=
                    StoryboardSegmentComposer.compose(context,store,source,
                            output,plan,index,width,height,isolated,
                            index>start,(count,budget,shotId,backend,confidence)->{
                                if(progress!=null)
                                    progress.update(offset+count,expected,batch,
                                            shotId,backend,confidence);
                            });
            produced+=part.generatedFrames;
            shots+=part.animatedShots;
            min=Math.min(min,part.minimumConfidence);
            reports.put(new JSONObject()
                    .put("batchIndex",index)
                    .put("frames",part.generatedFrames)
                    .put("shots",part.animatedShots)
                    .put("providerReports",part.shotReport));
            store.save(output);
        }
        if(produced!=expected||output.clips.size()!=expected)
            throw new IllegalStateException(
                    "The continuous source-frame sequence has missing/duplicated animation frames");
        if(sound){
            List<StoryboardFoleySynth.Cue> cues=selected<0
                    ?StoryboardFoleySynth.cues(plan)
                    :StoryboardFoleySynth.cues(plan.batches.get(start),plan.fps);
            File wav=new File(context.getFilesDir(),
                    "creative_workspace/projects/"+output.id+
                    "/generated/storyboard_multigenre/storyboard_foley.wav");
            float level=(float)Math.min(1,Math.max(.05,
                    options.optDouble("foleyGain",.65)));
            StoryboardFoleySynth.attach(output,wav,cues,level);
        }
        store.save(output);
        return new Result(produced,shots,end-start,min,reports,sound);
    }
}
