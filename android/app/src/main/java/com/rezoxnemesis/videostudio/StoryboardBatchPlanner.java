package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Production-friendly multi-shot storyboard plan, not a 2-actor fight script.
 *
 * Preserves imported source occurrences and uses only explicitly declared variants.
 * Supports up to 160 source drawings and partitions long storyboards into
 * independently restartable batches with a shared endpoint between batches.
 *
 * This is narrative/timing planning. It does NOT claim to understand visual
 * semantics or run pose diffusion; per-shot image content is checked by the
 * native renderer and must pass explicit motion/matting confidence gates.
 */
public final class StoryboardBatchPlanner {
    public static final int MAX_SOURCE_FRAMES=160;
    public static final int MAX_BATCH_SOURCE_FRAMES=20;
    public static final int MIN_BATCH_SOURCE_FRAMES=2;
    public enum Backend {
        AUTO_LAYERS, SOURCE_RIG, PERSON_SEGMENTATION,
        CAMERA_PARALLAX, MANUAL_MASKS, NEURAL_IF_AVAILABLE
    }
    public static final class Shot {
        public final int fromIndex,toIndex;
        public final String fromId,toId;
        public final StoryboardMotionGrammar.Action action;
        public final StoryboardChoreography.Beat beat;
        public final int frames;
        public final StoryboardMotionGrammar.SceneKind scene;
        public final String sourceDescription;
        Shot(int fromIndex,int toIndex,String a,String b,
             StoryboardMotionGrammar.Action action,int frames,
             StoryboardMotionGrammar.SceneKind scene,String description){
            this(fromIndex,toIndex,a,b,action,frames,scene,description,null);
        }
        Shot(int fromIndex,int toIndex,String a,String b,
             StoryboardMotionGrammar.Action action,int frames,
             StoryboardMotionGrammar.SceneKind scene,String description,StoryboardChoreography.Beat beat){
            this.fromIndex=fromIndex;this.toIndex=toIndex;
            fromId=a;toId=b;this.action=action;this.frames=frames;
            this.scene=scene;sourceDescription=description;
            this.beat=beat;
        }
    }
    public static final class Batch {
        public final int index,start,end,expectedFrames;
        public final List<Shot> shots;
        Batch(int index,int start,int end,List<Shot> shots,int count){
            this.index=index;this.start=start;this.end=end;
            this.shots=Collections.unmodifiableList(new ArrayList<>(shots));expectedFrames=count;
        }
    }
    public static final class Plan {
        public final String projectId;
        public final int fps;
        public final Backend backend;
        public final StoryboardMotionGrammar.SceneKind scene;
        public final List<String> assets;
        public final List<Batch> batches;
        public final int deduplicatedCount;
        public final JSONObject sourceManifest;
        Plan(String projectId,int fps,Backend backend,
             StoryboardMotionGrammar.SceneKind scene,List<String> source,
             List<Batch> batches,int skipped,JSONObject manifest) {
            this.projectId=projectId;this.fps=fps;this.backend=backend;
            this.scene=scene;assets=Collections.unmodifiableList(new ArrayList<>(source));this.batches=Collections.unmodifiableList(new ArrayList<>(batches));
            deduplicatedCount=skipped;
            sourceManifest=manifest;
        }
        public JSONObject toJson() throws Exception {
            JSONObject result=new JSONObject();
            result.put("ok",true);
            result.put("projectId",projectId);
            result.put("fps",fps);
            result.put("preferredBackend",backend.name().toLowerCase(Locale.US));
            result.put("sceneKind",scene.name().toLowerCase(Locale.US));
            result.put("sourceFrameCount",assets.size());
            result.put("sourceDuplicatesFiltered",deduplicatedCount);
            result.put("batchCount",batches.size());
            result.put("sourceProjectUnmodified",true);
            result.put("sourceManifestBound",sourceManifest!=null);
            result.put("ownedBytesRequireVerification",true);
            if(sourceManifest!=null)result.put("sourceManifest",new JSONObject(sourceManifest.toString()));
            result.put("galleryAccess",false);
            result.put("automaticPoseDiffusionAvailable",false);
            result.put("format","videostudio.storyboard-batches.v1");
            JSONArray requested=new JSONArray();
            for(String id:assets)requested.put(id);
            result.put("orderedAssetIds",requested);
            JSONArray groups=new JSONArray();
            for(Batch b:batches){
                JSONObject block=new JSONObject()
                        .put("index",b.index)
                        .put("startIndex",b.start)
                        .put("endIndex",b.end)
                        .put("firstAssetId",assets.get(b.start))
                        .put("lastAssetId",assets.get(b.end))
                        .put("inputFramesIncludingOverlap",b.end-b.start+1)
                        .put("newOriginalFrames",b.end-b.start+(b.index==0?1:0))
                        .put("plannedOutputFrames",b.expectedFrames)
                        .put("sharesFirstAnchorWithPrevious",b.index>0);
                JSONArray shots=new JSONArray();
                for(Shot shot:b.shots)shots.put(new JSONObject()
                        .put("fromIndex",shot.fromIndex)
                        .put("toIndex",shot.toIndex)
                        .put("fromAssetId",shot.fromId)
                        .put("toAssetId",shot.toId)
                        .put("action",shot.action.name())
                        .put("beat",shot.beat==null?JSONObject.NULL:shot.beat.name())
                        .put("scene",shot.scene.name())
                        .put("plannedFrames",shot.frames)
                        .put("motionQualityValidated",false)
                        .put("needsPerShotImageInspection",true));
                block.put("shots",shots);
                groups.put(block);
            }
            result.put("batches",groups);
            return result;
        }
    }
    private StoryboardBatchPlanner(){}

    public static Plan plan(ProjectStore.Project project,JSONObject parameters)
            throws Exception {
        return plan(project,parameters,null);
    }

    /** Native callers verify this frozen manifest's owned bytes before rendering. */
    public static Plan plan(ProjectStore.Project project,JSONObject parameters,JSONObject acceptedManifest)
            throws Exception {
        if(project==null)throw new IllegalArgumentException("Open an imported VideoStudio project");
        if(parameters==null)parameters=new JSONObject();
        Object requestedFps=parameters.has("fps")?parameters.opt("fps"):Integer.valueOf(24);
        if(!(requestedFps instanceof Number)||!Double.isFinite(((Number)requestedFps).doubleValue())
                ||((Number)requestedFps).doubleValue()!=Math.rint(((Number)requestedFps).doubleValue()))
            throw new IllegalArgumentException("Use an integer 24, 30 or 60 FPS");
        double numericFps=((Number)requestedFps).doubleValue();
        if(numericFps!=24&&numericFps!=30&&numericFps!=60)throw new IllegalArgumentException("Use 24, 30 or 60 FPS");
        int fps=(int)numericFps;
        Backend backend=parseBackend(parameters.optString("backend","AUTO_LAYERS"));
        int maxPerBatch=parameters.optInt("maxFramesPerBatch",MAX_BATCH_SOURCE_FRAMES);
        if(maxPerBatch<MIN_BATCH_SOURCE_FRAMES||maxPerBatch>MAX_BATCH_SOURCE_FRAMES)
            throw new IllegalArgumentException("Batches contain 2-20 original keyframes");
        int framesPerPair=parameters.optInt("framesPerPair",10);
        if(framesPerPair<4||framesPerPair>24)
            throw new IllegalArgumentException("Each pair needs 4-24 real time samples");
        int maxGenerated=parameters.optInt("maxGeneratedFrames",240);
        if(maxGenerated<24||maxGenerated>480)
            throw new IllegalArgumentException("Bound each native batch to 24-480 samples");
        JSONObject manifest=acceptedManifest==null?null:StoryboardSourceManifest.validateForPlanning(project,acceptedManifest);
        if(manifest!=null)StoryboardSourceManifest.validateSelectionAgainstManifest(project,parameters,manifest);
        List<ProjectStore.Asset> images=new ArrayList<>();
        List<String> selected=manifest==null?StoryboardSourceManifest.resolveAssetIds(project,parameters):
                StoryboardSourceManifest.manifestAssetIds(manifest);
        for(String id:selected)images.add(project.asset(id));
        if(images.size()<2)throw new IllegalArgumentException("Need two or more imported frames");
        // A filename, extension or byte size cannot prove visual equivalence.
        // Literal repeat occurrences are beats. Only explicit variant groups
        // select fewer representatives, without deleting any library assets.
        int skipped=StoryboardSourceManifest.declaredVariantCount(project,
                manifest==null?parameters:manifest);
        StoryboardMotionGrammar.SceneKind scene=
                StoryboardMotionGrammar.scene(parameters.optString("genre",
                        project.sourcePrompt+" "+project.name));
        String description=parameters.optString("direction","").trim();
        // Story-wide natural-language direction is editorial context, not
        // permission to replace EVERY distinct source action with one beat.
        StoryboardMotionGrammar.Action override=
                parameters.optBoolean("applyDirectionToAll",false)
                &&!description.isEmpty()
                ?StoryboardMotionGrammar.parseAction(description,null):null;
        List<String> ids=new ArrayList<>();
        for(ProjectStore.Asset a:images)ids.add(a.id);
        if(parameters.has("choreography")){
            JSONArray choreography=parameters.optJSONArray("choreography");
            if(choreography==null||choreography.length()!=ids.size()-1)
                throw new IllegalArgumentException("Choreography must contain exactly one authored beat per ordered source transition");
        }
        ArrayList<Batch> batches=new ArrayList<>();
        int start=0,index=0;
        // Pair count, rather than image count, is the real render budget.
        // Adjacent batches overlap at exactly one immutable source anchor.
        while(start<images.size()-1){
            ArrayList<Shot> shots=new ArrayList<>();
            int end=start,produced=1;
            // Batch zero contributes up to 20 originals. Every later batch
            // contributes another 20 NEW originals plus the preceding
            // batch's last reference keyframe (21 inputs internally).
            while(end+1<images.size() &&
                    end-start<(start==0?maxPerBatch-1:maxPerBatch)) {
                int samples=framesPerPair;
                if(produced+samples>maxGenerated)break;
                ProjectStore.Asset to=images.get(end+1);
                StoryboardMotionGrammar.Action beat=override!=null?override:
                        StoryboardMotionGrammar.parseAction(to.name,
                                scene==StoryboardMotionGrammar.SceneKind.LANDSCAPE
                                        ?StoryboardMotionGrammar.Action.DRIFT
                                        :StoryboardMotionGrammar.Action.TRACK);
                shots.add(new Shot(end,end+1,images.get(end).id,to.id,
                        beat,samples,scene,description,StoryboardChoreography.resolveBeat(parameters,end,images.get(end).id,to.id)));
                produced+=samples;end++;
            }
            if(end==start)throw new IllegalArgumentException(
                    "Render budget is too small for the requested storyboard samples");
            batches.add(new Batch(index++,start,end,shots,produced));
            start=end;
            if(batches.size()>MAX_SOURCE_FRAMES)
                throw new IllegalArgumentException("Excessive batch count");
        }
        return new Plan(project.id,fps,backend,scene,ids,batches,
                skipped,manifest);
    }

    public static Backend parseBackend(String requested) {
        if(requested==null||requested.isEmpty())return Backend.AUTO_LAYERS;
        try {return Backend.valueOf(requested.trim().toUpperCase(Locale.US));}
        catch(Exception bad){throw new IllegalArgumentException(
                "Unsupported storyboard backend "+requested);}
    }
    public static boolean isImage(ProjectStore.Asset asset) {
        return asset!=null&&asset.id!=null&&asset.uri!=null
                &&asset.mime!=null&&asset.mime.startsWith("image/");
    }
    public static String normalizeFrameName(String raw) {
        if(raw==null||raw.isEmpty())return "";
        String name=raw.toLowerCase(Locale.US).trim().replaceAll(
                "\\.(png|jpeg|jpg|webp|bmp|avif)$","");
        name=name.replaceFirst("(?i)[_-](480|720|1080|2160|4k)$","");
        name=name.replaceFirst("^0+([1-9])","$1");
        name=name.replaceFirst("^0+_","0_");
        name=name.replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");
        return name;
    }
}
