package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.StatFs;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * First real source-faithful storyboard shot: 2 illustrated anchor frames,
 * 4 traced actor masks, 16 source landmarks/actor, and 4 original sword tips.
 *
 * A masked two-dimensional mesh deforms the exact original artwork through
 * each timed pose. The ruined city is held steady and reconstructed ONLY
 * from the two original illustrations. This method does NOT use a generic
 * stickman, photo slideshow, full-frame dissolve or invented 3D model.
 *
 * Unseen limbs/new camera angles still require a generative model or new
 * source art; high-risk pairs are rejected rather than faked.
 */
public final class NativeStoryboardArtworkComposer {
    public interface Progress {
        void onFrame(int completed,int total,double worldDifference) throws Exception;
    }
    public static final class Result {
        public final ProjectStore.Project project;
        public final double backgroundDifference,filledFraction;
        public final int generatedFrames;
        public final double actorMotionPixels;
        Result(ProjectStore.Project project,double difference,double fraction,int frames,
               double actorMotionPixels){
            this.project=project;
            backgroundDifference=difference;
            filledFraction=fraction;
            generatedFrames=frames;
            this.actorMotionPixels=actorMotionPixels;
        }
    }

    private NativeStoryboardArtworkComposer(){}

    public static Result compose(Context context,ProjectStore store,
            ProjectStore.Project source,ProjectStore.Project output,
            Rig2DProfile blackFrom,Rig2DProfile whiteFrom,
            Rig2DProfile blackTo,Rig2DProfile whiteTo,
            float[] blackTipFrom,float[] whiteTipFrom,
            float[] blackTipTo,float[] whiteTipTo,
            String action,int width,int height,int fps,
            double seconds,String backgroundAssetId,Progress progress) throws Exception {
        return compose(context,store,source,output,blackFrom,whiteFrom,blackTo,whiteTo,
                blackTipFrom,whiteTipFrom,blackTipTo,whiteTipTo,action,width,height,fps,
                seconds,backgroundAssetId,progress,null);
    }

    public static Result compose(Context context,ProjectStore store,
            ProjectStore.Project source,ProjectStore.Project output,
            Rig2DProfile blackFrom,Rig2DProfile whiteFrom,Rig2DProfile blackTo,Rig2DProfile whiteTo,
            float[] blackTipFrom,float[] whiteTipFrom,float[] blackTipTo,float[] whiteTipTo,
            String action,int width,int height,int fps,double seconds,String backgroundAssetId,
            Progress progress,JSONObject frozenSourceManifest) throws Exception {
        return compose(context,store,source,output,blackFrom,whiteFrom,blackTo,whiteTo,
                blackTipFrom,whiteTipFrom,blackTipTo,whiteTipTo,action,width,height,fps,
                seconds,backgroundAssetId,progress,frozenSourceManifest,null,true);
    }
    public static Result compose(Context context,ProjectStore store,
            ProjectStore.Project source,ProjectStore.Project output,
            Rig2DProfile blackFrom,Rig2DProfile whiteFrom,Rig2DProfile blackTo,Rig2DProfile whiteTo,
            float[] blackTipFrom,float[] whiteTipFrom,float[] blackTipTo,float[] whiteTipTo,
            String action,int width,int height,int fps,double seconds,String backgroundAssetId,
            Progress progress,JSONObject frozenSourceManifest,StoryboardContactPlan contacts,
            boolean cameraEnabled) throws Exception {
        if(context==null||store==null||source==null||output==null||
                source.id.equals(output.id))
            throw new IllegalArgumentException("Separate imported source and rendered project required");
        if(contacts==null)throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: an explicit source-bound contact plan is required for source-artwork action");
        contacts.requireRigBindings(blackFrom,whiteFrom,blackTo,whiteTo);
        NativeFrameClock.requireFps(fps);
        if(width<256||height<256||width>1280||height>1280||(long)width*height>720L*1280L||
                (width&1)!=0||(height&1)!=0)
            throw new IllegalArgumentException("Use even dimensions 256..1280 with at most 720×1280 pixels, at 24, 30 or 60fps");
        if(!Double.isFinite(seconds)||seconds<1.5||seconds>5.0)
            throw new IllegalArgumentException("Storyboard shot must be 1.5 to 5 seconds");
        int frames=(int)Math.ceil(fps*seconds);
        if(frames<3||frames>300)throw new IllegalArgumentException("Shot frame budget exceeded");
        validate(source,blackFrom,whiteFrom,blackTo,whiteTo,
                blackTipFrom,whiteTipFrom,blackTipTo,whiteTipTo);
        StoryboardPoseMotion.Action beat=StoryboardPoseMotion.action(action);
        StoryboardMotionQualityGate.Verdict quality=
                StoryboardMotionQualityGate.inspect(
                        blackFrom,whiteFrom,blackTo,whiteTo,width,height);
        if(!quality.approved)throw new IllegalArgumentException(quality.reason);
        if(StoryboardPoseMotion.maxActorTravel(blackFrom.joints,blackTo.joints)>.46
                ||StoryboardPoseMotion.maxActorTravel(whiteFrom.joints,whiteTo.joints)>.46)
            throw new IllegalArgumentException(
                    "Actors travel too far for an honest 2D interpolation. Split into more storyboard shots.");
        if(!output.assets.isEmpty()&&
                (output.sourcePrompt==null||!output.sourcePrompt.startsWith("storyboard-artwork-v1")))
            throw new IllegalArgumentException("Output contains unrelated user media");

        ProjectStore.Asset from=source.asset(blackFrom.sourceAssetId);
        ProjectStore.Asset to=source.asset(blackTo.sourceAssetId);
        JSONObject manifest=frozenSourceManifest==null?StoryboardSourceManifest.capture(context,source,new JSONObject())
                :StoryboardSourceManifest.validateForPlanning(source,frozenSourceManifest);
        if(frozenSourceManifest!=null)StoryboardSourceManifest.verifyOwnedSources(context,source,manifest);
        JSONObject fromEntry=entry(manifest,from.id),toEntry=entry(manifest,to.id);
        if(contacts.sourceWidth!=fromEntry.getInt("orientedWidth")||contacts.sourceHeight!=fromEntry.getInt("orientedHeight"))
            throw new IllegalArgumentException("Authored contact geometry differs from the accepted oriented source");
        int[] start=read(context,from,width,height,fromEntry);
        int[] end=read(context,to,width,height,toEntry);
        StoryboardCleanPlate.Result recovered=null;
        int[] plate;
        if(backgroundAssetId!=null&&!backgroundAssetId.trim().isEmpty()){
            ProjectStore.Asset clean=source.asset(backgroundAssetId);
            if(clean==null||clean.mime==null||!clean.mime.startsWith("image/"))
                throw new IllegalArgumentException("Clean background must be an explicitly imported image");
            plate=read(context,clean,width,height,entry(manifest,clean.id));
        }else{
            recovered=StoryboardCleanPlate.restore(start,end,width,height,
                    Arrays.asList(blackFrom.outline,whiteFrom.outline,blackFrom.swordOutline,whiteFrom.swordOutline),
                    Arrays.asList(blackTo.outline,whiteTo.outline,blackTo.swordOutline,whiteTo.swordOutline));
            plate=recovered.pixels;
        }
        double sceneDrift=recovered==null?0:recovered.backgroundDifference;
        double fillFraction=recovered==null?0:recovered.unobservedFraction;

        String generationId=UUID.randomUUID().toString();
        long outputRevision=output.updatedAt,sourceRevision=source.updatedAt;
        ProjectStore.Project prepared=ProjectStore.Project.fromJson(output.toJson());
        prepared.assets.clear();prepared.clips.clear();prepared.studioLayers.clear();
        prepared.latestExportUri="";prepared.latestExportName="";prepared.latestExportAt=0;
        prepared.sourcePrompt="storyboard-artwork-v1: source traced 2D pixel/mesh animation";
        File outDir=new File(context.getFilesDir(),
                "creative_workspace/projects/"+output.id+"/generated/storyboard_artwork/"+generationId);
        if(!outDir.isDirectory()&&!outDir.mkdirs())
            throw new IllegalStateException("Could not create private shot frame workspace");

        NativeSkinnedSprite2D bkA=null,whA=null,bkB=null,whB=null;
        NativeStoryboardArtworkRenderer renderer=null;
        boolean published=false;
        try {
            long required=Math.addExact(Math.multiplyExact((long)width*height*4+128*1024,frames),64L*1024*1024);
            if(new StatFs(outDir.getAbsolutePath()).getAvailableBytes()<required)
                throw new IllegalStateException("Not enough private workspace storage for bounded native frames and source snapshots");
            bkA=new NativeSkinnedSprite2D(context,from,blackFrom,width,height,fromEntry);
            whA=new NativeSkinnedSprite2D(context,from,whiteFrom,width,height,fromEntry);
            bkB=new NativeSkinnedSprite2D(context,to,blackTo,width,height,toEntry);
            whB=new NativeSkinnedSprite2D(context,to,whiteTo,width,height,toEntry);
            renderer=new NativeStoryboardArtworkRenderer(width,height,plate,
                    blackFrom,whiteFrom,blackTo,whiteTo,
                    bkA,whA,bkB,whB,blackTipFrom,whiteTipFrom,blackTipTo,whiteTipTo,beat,
                    contacts.sourceAspect(),contacts,cameraEnabled,true);
            renderer.setFrameRate(fps);
            for(int i=0;i<frames;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                double t=i/(double)(frames-1);
                Bitmap pixels=renderer.render(t,i,frames);
                try{
                    save(prepared,outDir,pixels,i,NativeFrameClock.frameDurationMs(i,fps),fps,
                            from.id,to.id,action,generationId,manifest);
                    prepared.assets.get(prepared.assets.size()-1).generationMetadata
                            .put("contactPlanBinding",contacts.bindingProof())
                            .put("cameraEnabled",cameraEnabled)
                            .put("motionEvidenceOrigin","PREDICTED_NATIVE_EVALUATOR")
                            .put("sourceTexturesPreserved",true)
                            .put("dynamicLimbOcclusionSupported",false)
                            .put("sourceCoverageEvidence",renderer.sourceCoverageEvidence())
                            .put("cinematicAcceptanceVerified",false);
                }finally{pixels.recycle();}
                if(progress!=null)progress.onFrame(i+1,frames,sceneDrift);
            }
            StoryboardSourceManifest.verifyOwnedSources(context,source,manifest);
            synchronized(store){
                ProjectStore.Project currentSource=store.get(source.id),currentOutput=store.get(output.id);
                if(currentSource==null||currentSource.updatedAt!=sourceRevision||currentOutput==null||currentOutput.updatedAt!=outputRevision)
                    throw new IllegalStateException("Source or destination changed during storyboard rendering; previous output retained");
                store.save(prepared);published=true;
            }
            // The legacy service passes this object directly to export after compose returns.
            output.assets.clear();output.assets.addAll(prepared.assets);
            output.clips.clear();output.clips.addAll(prepared.clips);output.studioLayers.clear();
            output.sourcePrompt=prepared.sourcePrompt;output.updatedAt=prepared.updatedAt;
            output.latestExportUri="";output.latestExportName="";output.latestExportAt=0;
            return new Result(output,sceneDrift,fillFraction,frames,
                    quality.actorMotionPixels);
        }finally{
            if(renderer!=null)renderer.close();
            else{
                if(bkA!=null)bkA.close();
                if(whA!=null)whA.close();
                if(bkB!=null)bkB.close();
                if(whB!=null)whB.close();
            }
            if(!published){
                File[] partial=outDir.listFiles();
                if(partial!=null)for(File file:partial)if(file.isFile())file.delete();
                outDir.delete();
            }
        }
    }

    private static void validate(ProjectStore.Project source,
            Rig2DProfile blackFrom,Rig2DProfile whiteFrom,
            Rig2DProfile blackTo,Rig2DProfile whiteTo,
            float[] bkA,float[] whA,float[] bkB,float[] whB) {
        if(blackFrom==null||blackTo==null||whiteFrom==null||whiteTo==null||
                !blackFrom.black||!blackTo.black||whiteFrom.black||whiteTo.black)
            throw new IllegalArgumentException("Both actors must be traced in both original keyframes");
        Rig2DProfile[] rigs={blackFrom,whiteFrom,blackTo,whiteTo};
        for(Rig2DProfile rig:rigs){
            if(!source.id.equals(rig.projectId)||source.asset(rig.sourceAssetId)==null)
                throw new IllegalArgumentException("Rig belongs to another project or lost source");
            if(rig.swordTip==null||rig.swordHilt==null||rig.swordOutline.length<3)
                throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: annotate both original sword polygons, hilts and tips in both anchors");
        }
        if(!blackFrom.sourceAssetId.equals(whiteFrom.sourceAssetId)
                ||!blackTo.sourceAssetId.equals(whiteTo.sourceAssetId)
                ||blackFrom.sourceAssetId.equals(blackTo.sourceAssetId))
            throw new IllegalArgumentException(
                    "Source keyframe A and B must each trace both fighters in different imported images");
        StoryboardPoseMotion.blade(blackFrom.joints,blackTo.joints,bkA,bkB,0.5,
                StoryboardPoseMotion.Action.ADVANCE);
        StoryboardPoseMotion.blade(whiteFrom.joints,whiteTo.joints,whA,whB,0.5,
                StoryboardPoseMotion.Action.ADVANCE);
    }

    private static JSONObject entry(JSONObject manifest,String assetId)throws Exception {
        org.json.JSONArray library=manifest.getJSONArray("library");
        for(int i=0;i<library.length();i++)if(assetId.equals(library.getJSONObject(i).getString("assetId")))
            return library.getJSONObject(i);
        throw new IllegalArgumentException("Frozen source manifest does not contain the requested image");
    }
    private static int[] read(Context context,ProjectStore.Asset source,int width,int height,JSONObject entry)throws Exception {
        Bitmap pixels=NativeStoryboardSourceImage.readVerified(context,source,width,height,entry);
        try{int[] result=new int[width*height];pixels.getPixels(result,0,width,0,0,width,height);return result;}
        finally{pixels.recycle();}
    }

    private static void save(ProjectStore.Project output,File dir,Bitmap bitmap,
                             int index,long durationMs,int fps,
                             String anchorA,String anchorB,String action,String generationId,JSONObject manifest) throws Exception {
        String name=String.format(Locale.US,"story_%04d.png",index);
        File destination=new File(dir,name);
        boolean ok;
        try(FileOutputStream out=new FileOutputStream(destination)){
            ok=bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
            out.flush();out.getFD().sync();
        }
        if(!ok||destination.length()==0){
            destination.delete();
            throw new IllegalStateException("Storyboard frame encoding failed");
        }
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=UUID.randomUUID().toString();
        asset.uri=Uri.fromFile(destination).toString();
        asset.name=name;
        asset.mime="image/png";
        asset.generated=true;
        asset.role="storyboard_artwork_frame";
        asset.seekable=true;
        asset.persistedReadAccess=true;
        asset.sizeBytes=destination.length();
        asset.generationMetadata=new JSONObject()
                .put("provider","videostudio.storyboard-artwork-mesh-v1")
                .put("generationId",generationId)
                .put("sourceManifestSha256",manifest.getString("manifestSha256"))
                .put("sourceProjectId",manifest.getString("sourceProjectId"))
                .put("fromSourceSha256",entry(manifest,anchorA).getString("sourceSha256"))
                .put("toSourceSha256",entry(manifest,anchorB).getString("sourceSha256"))
                .put("sourceCoordinateSpace","oriented-source-uv")
                .put("fromSourceAssetId",anchorA)
                .put("toSourceAssetId",anchorB)
                .put("notNeuralVideo",true)
                .put("frameIndex",index)
                .put("frameClock","rational-authored-v1")
                .put("fps",fps)
                .put("authoredFrameRate",fps)
                .put("authoredPresentationTimeUs",NativeFrameClock.presentationTimeUs(index,fps))
                .put("authoredFrameHoldMs",durationMs)
                .put("decodedFrameCadenceVerified",false)
                .put("action",action);
        output.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id=UUID.randomUUID().toString();
        clip.assetId=asset.id;
        clip.inMs=0;
        clip.outMs=durationMs;
        clip.speed=1;
        clip.transition="none";
        clip.effects.put("poseInbetween",true);
        clip.effects.put("poseFps",fps);
        clip.effects.put("frameClock","rational-authored-v1");
        clip.effects.put("frameIndex",index);
        output.clips.add(clip);
    }
}
