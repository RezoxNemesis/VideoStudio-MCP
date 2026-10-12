package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Local source-artwork, multi-shot animation composer.
 *
 * Produces genuinely new per-object image samples for the existing Media3
 * exporter. Multiple original layers move independently at 24/30 FPS.
 * The authored scene, colors and artist's source frames remain unchanged.
 *
 * Supports AUTO_LAYERS, MANUAL_MASKS and SOURCE_RIG. Other backends must
 * opt in explicitly; missing models NEVER masquerade as complete AI video.
 * Unsupported shots fail with a useful message instead of using a slideshow.
 */
public final class StoryboardSegmentComposer {
    public interface Progress {
        void onFrame(int completed,int total,int shotIndex,
                     String backend,float confidence) throws Exception;
        default void onSaved(ProjectStore.Project output) throws Exception { }
    }
    public static final class Summary {
        public final ProjectStore.Project output;
        public final int generatedFrames,animatedShots;
        public final double minimumConfidence;
        public final JSONArray shotReport;
        public final List<StoryboardFoleySynth.CapturedEvent> capturedEvents;
        Summary(ProjectStore.Project output,int frames,int shots,
                double confidence,JSONArray reports,List<StoryboardFoleySynth.CapturedEvent> events) {
            this.output=output;generatedFrames=frames;animatedShots=shots;
            minimumConfidence=confidence;shotReport=reports;capturedEvents=java.util.Collections.unmodifiableList(new ArrayList<>(events));
        }
    }
    private interface Painter extends AutoCloseable {
        Bitmap frame(float time,int index);
        String backend();
        float confidence();
        default StoryboardLayerIsolation.Result trackedSourceObjects(){return null;}
        default boolean confidenceMeasured(){return true;}
        default StoryboardPoseMotion.Frame motion(float time){return null;}
        default boolean collisionIntent(){return false;}
        default JSONObject sourceCoverageEvidence() throws Exception {return new JSONObject().put("status","UNAVAILABLE").put("hiddenSourceMaterialSynthesized",false);}
        @Override void close();
    }
    private StoryboardSegmentComposer(){}

    public static Summary compose(Context context,ProjectStore store,
            ProjectStore.Project original,ProjectStore.Project target,
            StoryboardBatchPlanner.Plan plan,int batchIndex,int width,int height,
            JSONObject settings,Progress progress) throws Exception {
        return compose(context,store,original,target,plan,batchIndex,
                width,height,settings,false,progress);
    }

    /**
     * Append adjacent batch N to an existing output N-1. The first anchor
     * of N equals the last frame of N-1 and is intentionally not encoded
     * twice; timestamps, clips and original media remain contiguous.
     */
    public static Summary compose(Context context,ProjectStore store,
            ProjectStore.Project original,ProjectStore.Project target,
            StoryboardBatchPlanner.Plan plan,int batchIndex,int width,int height,
            JSONObject settings,boolean append,Progress progress) throws Exception {
        if(context==null||store==null||original==null||target==null||
                original.id.equals(target.id)||plan==null||
                !original.id.equals(plan.projectId))
            throw new IllegalArgumentException("Source and separate native output projects required");
        if(batchIndex<0||batchIndex>=plan.batches.size())
            throw new IllegalArgumentException("Storyboard batch index not available");
        StoryboardMotionAdmission.dimensions(width,height);
        if(settings==null)settings=new JSONObject();
        StoryboardBatchPlanner.Batch batch=plan.batches.get(batchIndex);
        int expected=batch.expectedFrames-(append?1:0);
        if(append&&(batchIndex==0||target.clips.isEmpty()
                ||!target.studioLayers.isEmpty()))
            throw new IllegalArgumentException("Append requires a completed preceding storyboard batch");
        if(expected>480||expected<2)
            throw new IllegalArgumentException("Bound source motion batch to 480 frames");
        if(!target.assets.isEmpty()&&(target.sourcePrompt==null||
                !target.sourcePrompt.startsWith("storyboard-multigenre-v1")))
            throw new IllegalArgumentException("Output holds unrelated user content");

        // Resolve/verify accepted sources before touching even a partial output graph.
        if(!settings.has("_storyboardAdmission")) {
            JSONObject manifest=StoryboardSourceManifest.capture(context,original,settings);
            StoryboardBatchPlanner.Plan bound=StoryboardBatchPlanner.plan(original,settings,manifest);
            settings=new JSONObject(settings.toString()).put("batchIndex",batchIndex).put("width",width).put("height",height)
                    .put("motionIntent",StoryboardMotionAdmission.intent(settings)).put("_storyboardSourceManifest",manifest)
                    .put("_storyboardAdmission",StoryboardMotionAdmission.freeze(context,store,original,bound,batchIndex,width,height,settings,manifest));
            plan=bound;batch=plan.batches.get(batchIndex);
        }
        StoryboardMotionAdmission.verify(context,store,store.get(original.id),plan,settings);
        if(settings.has("_storyboardOutputUpdatedAt")&&target.updatedAt!=settings.getLong("_storyboardOutputUpdatedAt"))
            throw new IllegalStateException("STORYBOARD_OUTPUT_CHANGED: partial target was edited; previous generation retained");
        JSONObject manifest=settings.getJSONObject("_storyboardSourceManifest");
        File outputDir=new File(context.getFilesDir(),
                "creative_workspace/projects/"+target.id+"/generated/storyboard_multigenre/"+UUID.randomUUID());
        if(!outputDir.mkdirs())throw new IllegalStateException("Cannot prepare immutable private animation generation");
        String sidecarSha=writeAdmissionProof(outputDir,manifest,settings.getJSONObject("_storyboardAdmission"));
        // Previous generation files remain intact for saved graphs/history and audit.
        if(!append){
            target.assets.clear();target.clips.clear();target.studioLayers.clear();
            target.sourcePrompt="storyboard-multigenre-v1: source-owned layered motion series";
        }
        final int priorFrames=target.clips.size();

        boolean generationPublished=false;
        try {
        Rig2DProjectStore rigs=new Rig2DProjectStore(context,store);
        int complete=0,shotCount=0;
        double minConfidence=Double.NaN;
        JSONArray report=new JSONArray();
        NativeFrameClock.requireFps(plan.fps);
        settings.put("fps",plan.fps);
        List<StoryboardFoleySynth.CapturedEvent> captured=new ArrayList<>();
        for(StoryboardBatchPlanner.Shot shot:batch.shots){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            ProjectStore.Asset start=original.asset(shot.fromId);
            ProjectStore.Asset end=original.asset(shot.toId);
            boolean registered=StoryboardMotionAdmission.CINEMATIC_ACTION.equals(StoryboardMotionAdmission.intent(settings))
                    ||plan.backend==StoryboardBatchPlanner.Backend.SOURCE_RIG;
            int[] from=read(context,start,width,height,StoryboardMotionAdmission.library(manifest,start.id),registered);
            int[] to=read(context,end,width,height,StoryboardMotionAdmission.library(manifest,end.id),registered);
            try(Painter painter=painter(context,store,original,rigs,plan.backend,
                    start,end,from,to,width,height,shot,settings)){
                JSONObject provenance=new JSONObject().put("sourceManifestSha256",manifest.getString("manifestSha256"))
                        .put("fromSourceSha256",StoryboardMotionAdmission.library(manifest,start.id).getString("sourceSha256"))
                        .put("toSourceSha256",StoryboardMotionAdmission.library(manifest,end.id).getString("sourceSha256"))
                        .put("shotIndex",shot.fromIndex).put("choreographyBeat",shot.beat==null?JSONObject.NULL:shot.beat.name()).put("admissionProofFile","admission.json").put("admissionProofSha256",sidecarSha);
                int[] quarter=null,middle=null,threeQuarter=null;
                int quarterIndex=Math.max(1,Math.round(shot.frames*.25f));
                int middleIndex=Math.round(shot.frames*.5f);
                int threeQuarterIndex=Math.min(shot.frames-1,
                        Math.round(shot.frames*.75f));
                int initial=(shotCount==0&&!append)?0:1;
                boolean capturedContact=false;
                for(int step=initial;step<=shot.frames;step++){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    float t=step/(float)shot.frames;
                    StoryboardPoseMotion.Frame motion=painter.motion(t);
                    if(!capturedContact&&painter.collisionIntent()&&motion!=null&&motion.contact>.05) {
                        captured.add(new StoryboardFoleySynth.CapturedEvent("blade-contact:"+shot.fromIndex,
                                StoryboardMotionGrammar.Action.IMPACT,shot.scene,
                                NativeFrameClock.presentationTimeUs(priorFrames+complete,plan.fps),
                                (float)Math.min(1,motion.contact),StoryboardMotionEvidence.Origin.PREDICTED_NATIVE_EVALUATOR));
                        capturedContact=true;
                    }
                    Bitmap rendered=painter.frame(t,step);
                    try {
                        if(step==quarterIndex||step==middleIndex
                                ||step==threeQuarterIndex){
                            int[] snapshot=new int[width*height];
                            rendered.getPixels(snapshot,0,width,0,0,width,height);
                            if(step==quarterIndex)quarter=snapshot;
                            if(step==middleIndex)middle=snapshot;
                            if(step==threeQuarterIndex)threeQuarter=snapshot;
                        }
                        add(target,outputDir,rendered,priorFrames+complete,NativeFrameClock.frameDurationMs(priorFrames+complete,plan.fps),plan.fps,
                                start.id,end.id,shot.action,painter.backend(),
                                painter.confidenceMeasured()?painter.confidence():Float.NaN,shot.scene,provenance);
                    } finally {rendered.recycle();}
                    complete++;
                    if(progress!=null && (complete==1||complete%4==0||complete==expected))
                        progress.onFrame(complete,expected,shot.fromIndex,
                                painter.backend(),painter.confidence());
                }
                boolean intentionalHold=shot.fromId.equals(shot.toId)&&shot.beat==StoryboardChoreography.Beat.STANDOFF;
                boolean graphicOrCameraOnly=painter.backend().contains("graphic")
                        ||painter.backend().contains("environment")
                        ||painter.backend().contains("abstract");
                if(quarter==null||middle==null||threeQuarter==null)
                    throw new IllegalStateException(
                            "Missing early, middle or late source-motion sample");
                StoryboardTemporalCritic.Report inspected=
                        StoryboardTemporalCritic.inspect(from,to,middle,width,height,
                                graphicOrCameraOnly||intentionalHold);
                if(!inspected.acceptable)
                    throw new IllegalStateException("Source-artwork animation QA failed: "
                            +inspected.reason+" (source "+start.name+" → "+end.name+")");
                StoryboardTemporalCritic.TrajectoryReport trajectory=
                        StoryboardTemporalCritic.inspectTrajectory(from,to,
                                quarter,middle,threeQuarter,width,height,
                                graphicOrCameraOnly||intentionalHold||shot.action==StoryboardMotionGrammar.Action.HOLD
                                  ||shot.action==StoryboardMotionGrammar.Action.CUT);
                if(!trajectory.acceptable)
                    throw new IllegalStateException(
                            "Source-artwork motion stalled: "+trajectory.reason+
                            " (source "+start.name+" → "+end.name+")");
                StoryboardLayerIsolation.Result sources=painter.trackedSourceObjects();
                StoryboardSubjectRetentionGate.Report retained=null;
                double smallestCoverage=1;
                if(sources!=null){
                    int sampleNumber=0;
                    for(int[] sample:new int[][]{quarter,middle,threeQuarter}){
                        int step=sampleNumber++==0?quarterIndex:
                                sampleNumber==2?middleIndex:threeQuarterIndex;
                        float phase=StoryboardMotionGrammar.phase(
                                shot.action,step/(float)shot.frames);
                        StoryboardSubjectRetentionGate.Report current=
                                StoryboardSubjectRetentionGate.inspect(
                                        sources,from,to,sample,width,height,phase);
                        if(!current.accepted)
                            throw new IllegalStateException(
                                    "Original subject continuity failed: "+current.reason+
                                    " (source "+start.name+" → "+end.name+")");
                        smallestCoverage=Math.min(smallestCoverage,
                                current.minimumCoverage);
                        retained=current;
                    }
                }
                if(painter.confidenceMeasured())minConfidence=Double.isNaN(minConfidence)?painter.confidence():Math.min(minConfidence,painter.confidence());
                report.put(new JSONObject()
                        .put("fromAssetId",start.id)
                        .put("toAssetId",end.id)
                        .put("action",shot.action.name()).put("actionOrigin","SOURCE_GRAMMAR_CLASSIFICATION")
                        .put("choreographyBeat",shot.beat==null?JSONObject.NULL:shot.beat.name())
                        .put("scene",shot.scene.name())
                        .put("backend",painter.backend())
                        .put("confidence",painter.confidenceMeasured()?painter.confidence():JSONObject.NULL)
                        .put("confidenceStatus",painter.confidenceMeasured()?"MEASURED_SEGMENTATION_HEURISTIC":"UNAVAILABLE")
                        .put("motionFrames",shot.frames)
                        .put("sourceManifestSha256",manifest.getString("manifestSha256"))
                        .put("semanticPlanSha256",settings.getJSONObject("_storyboardAdmission").getString("planSha256"))
                        .put("admissionProofSha256",sidecarSha).put("sourceCoverage",painter.sourceCoverageEvidence())
                        .put("actualMidpointSourceDifference",inspected.middleSourceDifference)
                        .put("actualMidpointTargetDifference",inspected.middleTargetDifference)
                        .put("stationaryWorldChange",inspected.unchangedWorldError)
                        .put("pixelTemporalGatePassed",intentionalHold?JSONObject.NULL:inspected.acceptable&&trajectory.acceptable)
                        .put("intentionalHold",intentionalHold).put("motionRequirement",intentionalHold?"INTENTIONAL_STANDOFF_HOLD":"SOURCE_MOTION_REQUIRED")
                        .put("cinematicAcceptance",false)
                        .put("cinematicQA",StoryboardMotionEvidence.unavailable(
                                StoryboardMotionAdmission.CINEMATIC_ACTION.equals(StoryboardMotionAdmission.intent(settings)),
                                painter.backend(),"Decoded physical action metrics have not been measured").toJson())
                        .put("sourceFit",NativeStoryboardSourceImage.fitMapping(StoryboardMotionAdmission.library(manifest,start.id),width,height))
                        .put("fourIntervalMotionChecked",!intentionalHold)
                        .put("minimumAdjacentMotion",trajectory.minAdjacentMotion)
                        .put("maximumAdjacentMotion",trajectory.maxAdjacentMotion)
                        .put("objectRetentionChecked",retained!=null)
                        .put("retentionDiagnosticScope",retained==null?JSONObject.NULL:retained.diagnosticScope)
                        .put("anatomicalCoverageCertified",false)
                        .put("retentionSamplesChecked",retained==null?0:3)
                        .put("retainedOriginalSubjects",retained==null?0:
                                retained.verifiedObjects)
                        .put("minimumSubjectRetention",retained==null?-1:
                                smallestCoverage)
                        .put("sourceAnnotationProviderBound",registered));
                shotCount++;
                // Checkpoint a complete shot (not just the next frame); the
                // DB never refers to files that haven't been written yet.
                StoryboardMotionAdmission.verify(context,store,store.get(original.id),plan,settings);
                saveOutput(store,original,target);
                generationPublished=true;
                settings.put("_storyboardOutputUpdatedAt",target.updatedAt);
                if(progress!=null)progress.onSaved(target);
            }
        }
        if(complete!=expected)
            throw new IllegalStateException("Storyboard render ended with incorrect frame count");
        if(!append&&settings.optBoolean("foley",true)&&!StoryboardMotionAdmission.CINEMATIC_ACTION.equals(StoryboardMotionAdmission.intent(settings))
                &&batch.shots.stream().noneMatch(v->v.action==StoryboardMotionGrammar.Action.IMPACT)
                &&!StoryboardFoleySynth.cues(batch,plan.fps).isEmpty()){
            long clipDuration=NativeFrameClock.durationMs(complete,plan.fps);
            File audioFile=new File(outputDir,"shot_foley.wav");
            StoryboardFoleySynth.Result audio=StoryboardFoleySynth.render(
                    audioFile,clipDuration,
                    StoryboardFoleySynth.cues(batch,plan.fps));
            ProjectStore.Asset sound=new ProjectStore.Asset();
            sound.id=UUID.randomUUID().toString();
            sound.uri=Uri.fromFile(audioFile).toString();
            sound.name=audioFile.getName();
            sound.mime="audio/wav";
            sound.role="storyboard_generated_foley";
            sound.durationMs=audio.durationMs;
            sound.sizeBytes=audioFile.length();
            sound.generated=true;
            sound.seekable=true;
            sound.persistedReadAccess=true;
            sound.generationMetadata=new JSONObject()
                    .put("provider","videostudio.local-foley-v1")
                    .put("eventCount",audio.cues)
                    .put("noPaidApi",true)
                    .put("neuralMusicGeneration",false);
            target.assets.add(sound);
            StudioLayer audioLane=new StudioLayer();
            audioLane.type=StudioLayer.AUDIO;
            audioLane.assetId=sound.id;
            audioLane.startMs=0;
            audioLane.durationMs=clipDuration;
            audioLane.sourceInMs=0;
            audioLane.gain=Math.min(1f,Math.max(.01f,
                    (float)settings.optDouble("foleyGain",.65)));
            StudioLayerContract.validate(target,audioLane);
            target.studioLayers.add(audioLane);
        }
        saveOutput(store,original,target);
        generationPublished=true;
        settings.put("_storyboardOutputUpdatedAt",target.updatedAt);
        if(progress!=null)progress.onSaved(target);
        return new Summary(target,complete,shotCount,minConfidence,report,captured);
        } catch(Exception|Error failure) {
            // A published generation may be referenced by another owner graph; never prune it here.
            if(!generationPublished) {
                File[] unpublished=outputDir.listFiles();
                if(unpublished!=null)for(File file:unpublished)if(file.isFile())file.delete();
                outputDir.delete();
            }
            throw failure;
        }
    }

    private static String writeAdmissionProof(File generation,JSONObject manifest,JSONObject admission) throws Exception {
        byte[] bytes=new JSONObject().put("sourceManifest",manifest).put("admission",admission).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>512*1024){generation.delete();throw new IllegalArgumentException("Immutable storyboard admission proof exceeds 512 KiB");}
        File pending=new File(generation,"admission.json.part"),saved=new File(generation,"admission.json");
        boolean written=false;
        try {
            try(FileOutputStream out=new FileOutputStream(pending)){out.write(bytes);out.flush();out.getFD().sync();}
            if(pending.length()!=bytes.length||!pending.renameTo(saved))throw new IllegalStateException("Could not publish immutable storyboard proof");
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder sha=new StringBuilder(64);
            for(byte value:hash)sha.append(String.format(Locale.US,"%02x",value&255));written=true;return sha.toString();
        } finally {if(!written){pending.delete();saved.delete();generation.delete();}}
    }
    private static void saveOutput(ProjectStore store,ProjectStore.Project source,ProjectStore.Project target) throws Exception {
        store.guardedProject(source.id,source.updatedAt,current->store.guardedProject(target.id,target.updatedAt,owned->{
            store.save(target);return null;
        }));
    }
    private static Painter painter(Context context,ProjectStore store,ProjectStore.Project source,
            Rig2DProjectStore rigs,StoryboardBatchPlanner.Backend backend,
            ProjectStore.Asset fromAsset,ProjectStore.Asset toAsset,
            int[] pixelsA,int[] pixelsB,int width,int height,
            StoryboardBatchPlanner.Shot shot,JSONObject options) throws Exception {
        StoryboardMotionAdmission.FrozenShot frozen=StoryboardMotionAdmission.frozenShot(context,
                store,source,options,shot);
        boolean cinematic=StoryboardMotionAdmission.CINEMATIC_ACTION.equals(StoryboardMotionAdmission.intent(options));
        boolean chooseRig=cinematic||backend==StoryboardBatchPlanner.Backend.SOURCE_RIG;
        if(chooseRig){
            if(frozen==null)throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: frozen source rigs are missing");
            Rig2DProfile[] fromRigs=frozen.from,toRigs=frozen.to;
            float[] bkA=fromRigs[0].swordTip,whA=fromRigs[1].swordTip,
                    bkB=toRigs[0].swordTip,whB=toRigs[1].swordTip;
            if(bkA==null||whA==null||bkB==null||whB==null)
                throw new IllegalArgumentException(
                        "Storyboard rig requires original sword-tip endpoints");
            StoryboardPoseMotion.Action act=rigAction(shot.action);
            StoryboardCleanPlate.Result world=StoryboardCleanPlate.restore(
                    pixelsA,pixelsB,width,height,
                    java.util.Arrays.asList(fromRigs[0].outline,fromRigs[1].outline,fromRigs[0].swordOutline,fromRigs[1].swordOutline),
                    java.util.Arrays.asList(toRigs[0].outline,toRigs[1].outline,toRigs[0].swordOutline,toRigs[1].swordOutline));
            NativeSkinnedSprite2D b0=null,w0=null,b1=null,w1=null;
            try {
                b0=new NativeSkinnedSprite2D(context,fromAsset,fromRigs[0],width,height,StoryboardMotionAdmission.library(options.getJSONObject("_storyboardSourceManifest"),fromAsset.id));
                w0=new NativeSkinnedSprite2D(context,fromAsset,fromRigs[1],width,height,StoryboardMotionAdmission.library(options.getJSONObject("_storyboardSourceManifest"),fromAsset.id));
                b1=new NativeSkinnedSprite2D(context,toAsset,toRigs[0],width,height,StoryboardMotionAdmission.library(options.getJSONObject("_storyboardSourceManifest"),toAsset.id));
                w1=new NativeSkinnedSprite2D(context,toAsset,toRigs[1],width,height,StoryboardMotionAdmission.library(options.getJSONObject("_storyboardSourceManifest"),toAsset.id));
                final NativeStoryboardArtworkRenderer engine=cinematic
                        ?new NativeStoryboardArtworkRenderer(width,height,world.pixels,fromRigs[0],fromRigs[1],toRigs[0],toRigs[1],
                                b0,w0,b1,w1,bkA,whA,bkB,whB,frozen.beat,frozen.aspect,frozen.contacts,options.optBoolean("cameraEnabled",true),true)
                        :new NativeStoryboardArtworkRenderer(width,height,world.pixels,fromRigs[0],fromRigs[1],toRigs[0],toRigs[1],
                                b0,w0,b1,w1,bkA,whA,bkB,whB,act,frozen.aspect,frozen.contacts,options.optBoolean("cameraEnabled",true),false);
                engine.setFrameRate(NativeFrameClock.requireFps(options.optInt("fps",24)));
                return new Painter(){
                    public JSONObject sourceCoverageEvidence() throws Exception{return engine.sourceCoverageEvidence();}
                    public Bitmap frame(float t,int i){return engine.render(t,i,NativeFrameClock.requireFps(options.optInt("fps",24)));}
                    public String backend(){return "source-artwork-skinned-2d";}
                    public StoryboardPoseMotion.Frame motion(float t){return engine.sampleMotion(t);}
                    public boolean collisionIntent(){
                        StoryboardPoseMotion.Action black=cinematic?StoryboardChoreography.actionFor(frozen.beat,true):act,
                                white=cinematic?StoryboardChoreography.actionFor(frozen.beat,false):act;
                        return black==StoryboardPoseMotion.Action.IMPACT||black==StoryboardPoseMotion.Action.PARRY
                                ||white==StoryboardPoseMotion.Action.IMPACT||white==StoryboardPoseMotion.Action.PARRY;
                    }
                    public float confidence(){return Float.NaN;}
                    public boolean confidenceMeasured(){return false;}
                    public void close(){engine.close();}
                };
            }catch(Exception|Error error){
                if(b0!=null)b0.close();if(w0!=null)w0.close();
                if(b1!=null)b1.close();if(w1!=null)w1.close();
                throw error;
            }
        }
        if(backend==StoryboardBatchPlanner.Backend.NEURAL_IF_AVAILABLE)
            throw new IllegalStateException(
                    "No audited on-device image-to-video model adapter installed. "
                  +"Install a compatible motion-synthesis model pack first.");
        if(backend==StoryboardBatchPlanner.Backend.SOURCE_RIG)
            throw new IllegalArgumentException("Source-rig annotations incomplete");
        if(backend==StoryboardBatchPlanner.Backend.CAMERA_PARALLAX
                && shot.scene!=StoryboardMotionGrammar.SceneKind.ACTION
                && shot.scene!=StoryboardMotionGrammar.SceneKind.PORTRAIT)
            return graphics(pixelsA,pixelsB,width,height,shot,
                    StoryboardGraphicSceneRenderer.Mode.ENVIRONMENT);

        StoryboardLayerIsolation.Result layers=null;
        String provenance="auto-motion-foreground-2d";
        if(backend==StoryboardBatchPlanner.Backend.MANUAL_MASKS) {
            JSONObject overrides=options.optJSONObject("sourceMasks");
            String key=shot.fromId+"__"+shot.toId;
            JSONObject entry=overrides==null?null:overrides.optJSONObject(key);
            if(entry==null)throw new IllegalArgumentException(
                    "Supply explicit per-object polygons for this pair: "+key);
            layers=manual(pixelsA,pixelsB,width,height,entry);
            provenance="authored-multilayer-2d";
        }else if(backend==StoryboardBatchPlanner.Backend.PERSON_SEGMENTATION||
                  (backend==StoryboardBatchPlanner.Backend.AUTO_LAYERS&&
                   shot.scene==StoryboardMotionGrammar.SceneKind.PORTRAIT)) {
            try {
                StoryboardPersonMaskProvider.Result person=
                        StoryboardPersonMaskProvider.isolate(
                                pixelsA,pixelsB,width,height);
                layers=person.layers;
                provenance=person.provider;
            }catch(Exception cannotSeePerson) {
                if(backend==StoryboardBatchPlanner.Backend.PERSON_SEGMENTATION)
                    throw cannotSeePerson;
            }
        }
        if(layers==null)
            layers=StoryboardLayerIsolation.analyse(pixelsA,pixelsB,width,height);

        if(!layers.autoRenderable()){
            // A progressive diagram/title is not a jumping fighter: a
            // controlled source-stroke reveal is the CORRECT animation,
            // while a still landscape can be animated atmospherically.
            if(shot.scene==StoryboardMotionGrammar.SceneKind.EXPLAINER||
                    shot.scene==StoryboardMotionGrammar.SceneKind.TYPOGRAPHY||
                    shot.action==StoryboardMotionGrammar.Action.REVEAL)
                return graphics(pixelsA,pixelsB,width,height,shot,
                        StoryboardGraphicSceneRenderer.Mode.DRAW_REVEAL);
            if(shot.scene==StoryboardMotionGrammar.SceneKind.LANDSCAPE
                    ||shot.scene==StoryboardMotionGrammar.SceneKind.ABSTRACT)
                return graphics(pixelsA,pixelsB,width,height,shot,
                        shot.scene==StoryboardMotionGrammar.SceneKind.ABSTRACT
                             ?StoryboardGraphicSceneRenderer.Mode.ABSTRACT_FLOW
                             :StoryboardGraphicSceneRenderer.Mode.ENVIRONMENT);
            throw new IllegalArgumentException(
                    "Cannot animate this source shot faithfully: "+layers.reason+
                    " (scene="+shot.scene+", source="+fromAsset.name+
                    ", destination="+toAsset.name+"). "
                    +"Use traced masks, a suitable segmentation model, "+
                     "or split the camera shot. Never substitute a slideshow.");
        }
        final StoryboardLayerRenderer renderer=new StoryboardLayerRenderer(
                pixelsA,pixelsB,width,height,layers,shot.scene,shot.action);
        final float quality=averageConfidence(layers);
        final String provider=provenance;
        final StoryboardLayerIsolation.Result trackedLayers=layers;
        return new Painter(){
            public Bitmap frame(float t,int i){return renderer.render(t,i);}
            public String backend(){return provider;}
            public float confidence(){return quality;}
            @Override public StoryboardLayerIsolation.Result trackedSourceObjects(){
                return trackedLayers;
            }
            public void close(){renderer.close();}
        };
    }

    private static Painter graphics(int[] first,int[] last,int width,int height,
                                    StoryboardBatchPlanner.Shot shot,
                                    StoryboardGraphicSceneRenderer.Mode mode){
        final StoryboardGraphicSceneRenderer engine=
                new StoryboardGraphicSceneRenderer(first,last,width,height,mode);
        return new Painter(){
            public Bitmap frame(float t,int index){return engine.render(t);}
            public String backend(){return engine.backend();}
            public float confidence(){return Float.NaN;}
            public boolean confidenceMeasured(){return false;}
            public void close(){engine.close();}
        };
    }

    static StoryboardPoseMotion.Action rigAction(
            StoryboardMotionGrammar.Action motion) {
        switch(motion){
            case STRIKE:return StoryboardPoseMotion.Action.STRIKE;
            case IMPACT:return StoryboardPoseMotion.Action.IMPACT;
            case LEAP:return StoryboardPoseMotion.Action.LEAP;
            case FALL:case RECOIL:return StoryboardPoseMotion.Action.KNOCKBACK;
            case DODGE:return StoryboardPoseMotion.Action.PARRY;
            default:return StoryboardPoseMotion.Action.ADVANCE;
        }
    }
    private static float averageConfidence(StoryboardLayerIsolation.Result layers) {
        float score=0;for(StoryboardLayerIsolation.Track t:layers.tracks)
            score+=t.confidence;
        return score/Math.max(1,layers.tracks.size());
    }

    /** Explicit source polygons for arbitrary entities, no human-only rig. */
    private static StoryboardLayerIsolation.Result manual(int[] a,int[] b,
            int width,int height,JSONObject masks) throws Exception {
        JSONArray objects=masks.optJSONArray("objects");
        if(objects==null||objects.length()<1||objects.length()>8)
            throw new IllegalArgumentException("Specify one to eight approved moving objects");
        ArrayList<float[][]> from=new ArrayList<>(),to=new ArrayList<>();
        for(int i=0;i<objects.length();i++){
            JSONObject item=objects.getJSONObject(i);
            from.add(polygon(item.optJSONArray("from")));
            to.add(polygon(item.optJSONArray("to")));
        }
        return StoryboardLayerIsolation.annotated(a,b,width,height,from,to);
    }
    private static float[][] polygon(JSONArray points) throws Exception {
        if(points==null||points.length()<3||points.length()>64)
            throw new IllegalArgumentException("Each moving object needs a 3-64-point polygon");
        float[][] out=new float[points.length()][2];
        for(int i=0;i<points.length();i++){
            JSONArray xy=points.getJSONArray(i);
            if(xy.length()!=2)throw new IllegalArgumentException("Each polygon point needs x,y");
            out[i][0]=(float)xy.getDouble(0);
            out[i][1]=(float)xy.getDouble(1);
        }
        return out;
    }

    private static int[] read(Context context,ProjectStore.Asset source,int width,int height,
                              JSONObject library,boolean registered) throws Exception {
        Bitmap image=registered?NativeStoryboardSourceImage.readVerified(context,source,width,height,library)
                :NativeStoryboardSourceImage.readVerifiedFit(context,source,width,height,library);
        try {int[] pixels=new int[width*height];image.getPixels(pixels,0,width,0,0,width,height);return pixels;}
        finally {image.recycle();}
    }
    private static void add(ProjectStore.Project project,File root,Bitmap pixels,
            int index,long durationMs,int fps,String fromId,String toId,
            StoryboardMotionGrammar.Action motion,String backend,
            float confidence,StoryboardMotionGrammar.SceneKind kind,JSONObject provenance) throws Exception {
        String name=String.format(Locale.US,"story_%05d.jpg",index);
        File dest=new File(root,name);
        File pending=new File(root,name+".part");
        try(FileOutputStream out=new FileOutputStream(pending)){
            if(!pixels.compress(Bitmap.CompressFormat.JPEG,90,out))
                throw new IllegalStateException("Could not encode source animation");
            out.flush();
        }
        if(pending.length()<64||!pending.renameTo(dest)){
            pending.delete();
            throw new IllegalStateException("Could not atomically save animated storyboard frame");
        }
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=UUID.randomUUID().toString();
        asset.uri=Uri.fromFile(dest).toString();
        asset.name=name;
        asset.mime="image/jpeg";
        asset.role="multigenre_storyboard_motion";
        asset.generated=true;
        asset.sizeBytes=dest.length();
        asset.seekable=true;
        asset.persistedReadAccess=true;
        asset.generationMetadata=new JSONObject()
                .put("provider","videostudio.storyboard-multigenre-v1")
                .put("fromAssetId",fromId).put("toAssetId",toId)
                .put("backend",backend).put("confidence",Float.isFinite(confidence)?confidence:JSONObject.NULL)
                .put("confidenceStatus",Float.isFinite(confidence)?"MEASURED_SEGMENTATION_HEURISTIC":"UNAVAILABLE")
                .put("originalArtwork",true)
                .put("neuralVideoSynthesized",false)
                .put("scene",kind.name())
                .put("action",motion.name())
                .put("frame",index).put("frameClock","rational-authored-v1").put("frameIndex",index).put("fps",fps);
        java.util.Iterator<String> proofKeys=provenance.keys();while(proofKeys.hasNext()){String key=proofKeys.next();asset.generationMetadata.put(key,provenance.get(key));}
        project.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id=UUID.randomUUID().toString();
        clip.assetId=asset.id;
        clip.inMs=0;clip.outMs=durationMs;
        clip.speed=1;clip.volume=0;clip.transition="none";
        clip.effects.put("poseInbetween",true);
        clip.effects.put("poseFps",fps);
        clip.effects.put("frameClock","rational-authored-v1");
        clip.effects.put("frameIndex",index);
        clip.effects.put("storyboardObjectMotion",true);
        project.clips.add(clip);
    }
}
