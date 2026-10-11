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
    }
    public static final class Summary {
        public final ProjectStore.Project output;
        public final int generatedFrames,animatedShots;
        public final double minimumConfidence;
        public final JSONArray shotReport;
        Summary(ProjectStore.Project output,int frames,int shots,
                double confidence,JSONArray reports) {
            this.output=output;generatedFrames=frames;animatedShots=shots;
            minimumConfidence=confidence;shotReport=reports;
        }
    }
    private interface Painter extends AutoCloseable {
        Bitmap frame(float time,int index);
        String backend();
        float confidence();
        default StoryboardLayerIsolation.Result trackedSourceObjects(){return null;}
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
        if(width<256||height<432||width>720||height>1280||
                (width&1)!=0||(height&1)!=0)
            throw new IllegalArgumentException("Use bounded even portrait dimensions");
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

        File outputDir=new File(context.getFilesDir(),
                "creative_workspace/projects/"+target.id+
                "/generated/storyboard_multigenre");
        if(!outputDir.isDirectory()&&!outputDir.mkdirs())
            throw new IllegalStateException("Cannot prepare private multi-shot workspace");
        // Restart only replaces this ONE generated frame pattern in the
        // separate output project; never delete user media or source files.
        if(!append){
            File[] previous=outputDir.listFiles();
            if(previous!=null)for(File f:previous)
                if(f.isFile()&&f.getName().matches("story_[0-9]{5}\\.jpg")
                        &&!f.delete())
                    throw new IllegalStateException("Could not restart incomplete storyboard batch");
            target.assets.clear();target.clips.clear();target.studioLayers.clear();
            target.sourcePrompt="storyboard-multigenre-v1: source-owned layered motion series";
            store.save(target);
        }
        final int priorFrames=target.clips.size();

        Rig2DProjectStore rigs=new Rig2DProjectStore(context,store);
        int complete=0,shotCount=0;
        double minConfidence=1;
        JSONArray report=new JSONArray();
        long durationMs=Math.max(34,Math.round(1000d/plan.fps));
        for(StoryboardBatchPlanner.Shot shot:batch.shots){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            ProjectStore.Asset start=original.asset(shot.fromId);
            ProjectStore.Asset end=original.asset(shot.toId);
            int[] from=read(context,start,width,height);
            int[] to=read(context,end,width,height);
            try(Painter painter=painter(context,original,rigs,plan.backend,
                    start,end,from,to,width,height,shot,settings)){
                int[] middle=null;
                int middleIndex=Math.round(shot.frames*.5f);
                int initial=(shotCount==0&&!append)?0:1;
                for(int step=initial;step<=shot.frames;step++){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    float t=step/(float)shot.frames;
                    Bitmap rendered=painter.frame(t,step);
                    try {
                        if(step==middleIndex){
                            middle=new int[width*height];
                            rendered.getPixels(middle,0,width,0,0,width,height);
                        }
                        add(target,outputDir,rendered,priorFrames+complete,durationMs,plan.fps,
                                start.id,end.id,shot.action,painter.backend(),
                                painter.confidence(),shot.scene);
                    } finally {rendered.recycle();}
                    complete++;
                    if(progress!=null && (complete==1||complete%4==0||complete==expected))
                        progress.onFrame(complete,expected,shot.fromIndex,
                                painter.backend(),painter.confidence());
                }
                boolean graphicOrCameraOnly=painter.backend().contains("graphic")
                        ||painter.backend().contains("environment")
                        ||painter.backend().contains("abstract");
                if(middle==null)throw new IllegalStateException("No genuine midpoint sample");
                StoryboardTemporalCritic.Report inspected=
                        StoryboardTemporalCritic.inspect(from,to,middle,width,height,
                                graphicOrCameraOnly);
                if(!inspected.acceptable)
                    throw new IllegalStateException("Source-artwork animation QA failed: "
                            +inspected.reason+" (source "+start.name+" → "+end.name+")");
                StoryboardLayerIsolation.Result sources=painter.trackedSourceObjects();
                StoryboardSubjectRetentionGate.Report retained=sources==null?null:
                        StoryboardSubjectRetentionGate.inspect(sources,from,to,middle,
                                width,height);
                if(retained!=null&&!retained.accepted)
                    throw new IllegalStateException(
                            "Original subject continuity failed: "+retained.reason+
                            " (source "+start.name+" → "+end.name+")");
                minConfidence=Math.min(minConfidence,painter.confidence());
                report.put(new JSONObject()
                        .put("fromAssetId",start.id)
                        .put("toAssetId",end.id)
                        .put("action",shot.action.name())
                        .put("scene",shot.scene.name())
                        .put("backend",painter.backend())
                        .put("confidence",painter.confidence())
                        .put("motionFrames",shot.frames)
                        .put("actualMidpointSourceDifference",inspected.middleSourceDifference)
                        .put("actualMidpointTargetDifference",inspected.middleTargetDifference)
                        .put("stationaryWorldChange",inspected.unchangedWorldError)
                        .put("qualityGatePassed",inspected.acceptable)
                        .put("objectRetentionChecked",retained!=null)
                        .put("retainedOriginalSubjects",retained==null?0:
                                retained.verifiedObjects)
                        .put("minimumSubjectRetention",retained==null?-1:
                                retained.minimumCoverage));
                shotCount++;
                // Checkpoint a complete shot (not just the next frame); the
                // DB never refers to files that haven't been written yet.
                store.save(target);
            }
        }
        if(complete!=expected)
            throw new IllegalStateException("Storyboard render ended with incorrect frame count");
        if(!append&&settings.optBoolean("foley",true)){
            long clipDuration=complete*durationMs;
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
        store.save(target);
        return new Summary(target,complete,shotCount,minConfidence,report);
    }

    private static Painter painter(Context context,ProjectStore.Project source,
            Rig2DProjectStore rigs,StoryboardBatchPlanner.Backend backend,
            ProjectStore.Asset fromAsset,ProjectStore.Asset toAsset,
            int[] pixelsA,int[] pixelsB,int width,int height,
            StoryboardBatchPlanner.Shot shot,JSONObject options) throws Exception {
        // Explicit source-marked 2D actors take priority when selected, or
        // when AUTO sees a complex cinematic/comic scene and both poses have
        // already been annotated by the user.
        Rig2DProfile[] fromRigs=actorRigs(rigs.list(source.id),fromAsset.id),
                       toRigs=actorRigs(rigs.list(source.id),toAsset.id);
        boolean sourceRigs=fromRigs[0]!=null&&fromRigs[1]!=null
                &&toRigs[0]!=null&&toRigs[1]!=null;
        boolean chooseRig=backend==StoryboardBatchPlanner.Backend.SOURCE_RIG ||
                (sourceRigs&&(shot.scene==StoryboardMotionGrammar.SceneKind.ACTION||
                               shot.scene==StoryboardMotionGrammar.SceneKind.COMIC||
                               shot.scene==StoryboardMotionGrammar.SceneKind.PORTRAIT));
        if(chooseRig){
            if(!sourceRigs)throw new IllegalArgumentException(
                    "This illustrated action shot requires four source-masked actor profiles. "
                  +"Annotate both actors on each of its two imported frames.");
            float[] bkA=fromRigs[0].swordTip,whA=fromRigs[1].swordTip,
                    bkB=toRigs[0].swordTip,whB=toRigs[1].swordTip;
            if(bkA==null||whA==null||bkB==null||whB==null)
                throw new IllegalArgumentException(
                        "Storyboard rig requires original sword-tip endpoints");
            StoryboardPoseMotion.Action act=rigAction(shot.action);
            StoryboardCleanPlate.Result world=StoryboardCleanPlate.restore(
                    pixelsA,pixelsB,width,height,
                    fromRigs[0].outline,fromRigs[1].outline,
                    toRigs[0].outline,toRigs[1].outline);
            NativeSkinnedSprite2D b0=null,w0=null,b1=null,w1=null;
            try {
                b0=new NativeSkinnedSprite2D(context,fromAsset,fromRigs[0],width,height);
                w0=new NativeSkinnedSprite2D(context,fromAsset,fromRigs[1],width,height);
                b1=new NativeSkinnedSprite2D(context,toAsset,toRigs[0],width,height);
                w1=new NativeSkinnedSprite2D(context,toAsset,toRigs[1],width,height);
                final NativeStoryboardArtworkRenderer engine=
                        new NativeStoryboardArtworkRenderer(width,height,world.pixels,
                                fromRigs[0],fromRigs[1],toRigs[0],toRigs[1],
                                b0,w0,b1,w1,bkA,whA,bkB,whB,act);
                return new Painter(){
                    public Bitmap frame(float t,int i){return engine.render(t,i,24);}
                    public String backend(){return "source-artwork-skinned-2d";}
                    public float confidence(){return .93f;}
                    public void close(){engine.close();}
                };
            }catch(Exception error){
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
            public float confidence(){return .80f;}
            public void close(){engine.close();}
        };
    }

    private static Rig2DProfile[] actorRigs(List<Rig2DProfile> available,String id) {
        Rig2DProfile[] selected=new Rig2DProfile[2];
        for(Rig2DProfile rig:available)if(id.equals(rig.sourceAssetId))
            selected[rig.black?0:1]=rig;
        return selected;
    }
    private static StoryboardPoseMotion.Action rigAction(
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

    private static int[] read(Context context,ProjectStore.Asset source,
                              int width,int height) throws Exception {
        if(!StoryboardBatchPlanner.isImage(source))
            throw new IllegalArgumentException("Only imported image assets may be read");
        Uri uri=Uri.parse(source.uri);
        BitmapFactory.Options bound=new BitmapFactory.Options();
        bound.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)) {
            if(in==null)throw new IllegalArgumentException("Source permissions expired");
            BitmapFactory.decodeStream(in,null,bound);
        }
        if(bound.outWidth<32||bound.outHeight<32||
                bound.outWidth>8192||bound.outHeight>8192)
            throw new IllegalArgumentException("Bad imported storyboard dimensions");
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
        int sample=1;
        while(bound.outWidth/sample>width*2 || bound.outHeight/sample>height*2)sample*=2;
        opts.inSampleSize=sample;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Source image unavailable");
            Bitmap decoded=BitmapFactory.decodeStream(in,null,opts);
            if(decoded==null)throw new IllegalArgumentException("Cannot decode original frame");
            Bitmap resized=Bitmap.createScaledBitmap(decoded,width,height,true);
            if(resized!=decoded)decoded.recycle();
            int[] pixels=new int[width*height];
            resized.getPixels(pixels,0,width,0,0,width,height);
            resized.recycle();
            return pixels;
        }
    }
    private static void add(ProjectStore.Project project,File root,Bitmap pixels,
            int index,long durationMs,int fps,String fromId,String toId,
            StoryboardMotionGrammar.Action motion,String backend,
            float confidence,StoryboardMotionGrammar.SceneKind kind) throws Exception {
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
                .put("backend",backend).put("confidence",confidence)
                .put("originalArtwork",true)
                .put("neuralVideoSynthesized",false)
                .put("scene",kind.name())
                .put("action",motion.name())
                .put("frame",index);
        project.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id=UUID.randomUUID().toString();
        clip.assetId=asset.id;
        clip.inMs=0;clip.outMs=durationMs;
        clip.speed=1;clip.volume=0;clip.transition="none";
        clip.effects.put("poseInbetween",true);
        clip.effects.put("poseFps",fps);
        clip.effects.put("storyboardObjectMotion",true);
        project.clips.add(clip);
    }
}
