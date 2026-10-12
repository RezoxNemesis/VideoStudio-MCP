package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * On-device rigged source-art swordfight sampler. Outputs genuine independent
 * 2D skinned-pose frames for Media3 video encoding. No Gallery, network model,
 * still-image fades or procedural black/white stickman overlays.
 */
public final class NativeRiggedCombatComposer {
    public interface Progress {
        void onFrame(int completed,int total,double seconds,double impact) throws Exception;
    }
    private NativeRiggedCombatComposer(){}

    public static ProjectStore.Project compose(Context context,ProjectStore store,
            ProjectStore.Project source,ProjectStore.Project output,
            Rig2DProfile blackProfile,Rig2DProfile whiteProfile,
            int width,int height,int fps,double durationSeconds,
            boolean storyboardMotion,Bitmap cleanPlate,Progress progress) throws Exception {
        return compose(context,store,source,output,blackProfile,whiteProfile,
                width,height,fps,durationSeconds,storyboardMotion,cleanPlate,
                new CombatActionPlan(),progress);
    }

    public static ProjectStore.Project compose(Context context,ProjectStore store,
            ProjectStore.Project source,ProjectStore.Project output,
            Rig2DProfile blackProfile,Rig2DProfile whiteProfile,
            int width,int height,int fps,double durationSeconds,
            boolean storyboardMotion,Bitmap cleanPlate,
            CombatActionPlan director,Progress progress) throws Exception {
        if(director==null)throw new IllegalArgumentException("Combat director required");
        if(context==null||store==null||source==null||output==null||
                blackProfile==null||whiteProfile==null||source.id.equals(output.id))
            throw new IllegalArgumentException("Two source rigs and a separate output project are required");
        if(!blackProfile.black||whiteProfile.black)
            throw new IllegalArgumentException("Black and white profiles must be bound to their own actors");
        if(!source.id.equals(blackProfile.projectId)||
                !source.id.equals(whiteProfile.projectId)||
                blackProfile.id.equals(whiteProfile.id))
            throw new IllegalArgumentException("Actor rigs must belong to the selected image project");
        if(fps!=24&&fps!=30)throw new IllegalArgumentException("Use 24 or 30 FPS");
        if(!Double.isFinite(durationSeconds)||durationSeconds<2||durationSeconds>6)
            throw new IllegalArgumentException("Rigged swordfight duration must be 2-6 seconds");
        if(width<320||height<540||width>720||height>1280||
                (width&1)!=0||(height&1)!=0)
            throw new IllegalArgumentException("Invalid bounded portrait render");
        if(cleanPlate!=null&&(cleanPlate.isRecycled()
                ||Math.abs(cleanPlate.getWidth()/(double)cleanPlate.getHeight()-width/(double)height)>.000001))
            throw new IllegalArgumentException("Clean scene needs matching oriented aspect or explicit source registration");
        int frames=Math.round((float)(fps*durationSeconds));
        if(frames<2||frames>180)throw new IllegalArgumentException("Too many rigged frames");
        if(!output.assets.isEmpty()&&
                (output.sourcePrompt==null||!output.sourcePrompt.startsWith("rigged2d:")))
            throw new IllegalArgumentException("Destination contains unrelated user content");

        ProjectStore.Asset blackAsset=source.asset(blackProfile.sourceAssetId);
        ProjectStore.Asset whiteAsset=source.asset(whiteProfile.sourceAssetId);
        if(blackAsset==null||whiteAsset==null)
            throw new IllegalArgumentException("Actor source image no longer exists");
        final long sourceRevision=source.updatedAt,outputRevision=output.updatedAt;
        final Rig2DProfile frozenBlack=Rig2DProfile.fromJson(blackProfile.toJson()),frozenWhite=Rig2DProfile.fromJson(whiteProfile.toJson());
        final ProjectStore.Asset frozenBlackAsset=ProjectStore.Asset.fromJson(blackAsset.toJson()),frozenWhiteAsset=ProjectStore.Asset.fromJson(whiteAsset.toJson());
        JSONObject blackSource=StoryboardSourceManifest.captureSource(context,frozenBlackAsset),whiteSource=StoryboardSourceManifest.captureSource(context,frozenWhiteAsset);
        if(frozenBlack.sourceSha256!=null&&!frozenBlack.sourceSha256.equals(blackSource.getString("sourceSha256"))
                ||frozenWhite.sourceSha256!=null&&!frozenWhite.sourceSha256.equals(whiteSource.getString("sourceSha256")))
            throw new IllegalArgumentException("SOURCE_IMAGE_CHANGED: source actor marks belong to different image bytes");
        ProjectStore.Project prepared=ProjectStore.Project.fromJson(output.toJson());
        prepared.assets.clear();prepared.clips.clear();prepared.studioLayers.clear();
        prepared.sourcePrompt="rigged2d: two source-masked actor meshes animated by continuous IK";
        prepared.latestExportUri="";prepared.latestExportName="";prepared.latestExportAt=0;
        String generationId=UUID.randomUUID().toString();File framesDir=null;
        NativeSkinnedSprite2D black=null,white=null;
        NativeRiggedCombatRenderer renderer=null;
        boolean published=false;final boolean[] publicationAttempted={false};
        try{
            // Predictable decode, registration and pose failures precede any destination change.
            black=new NativeSkinnedSprite2D(context,frozenBlackAsset,frozenBlack,width,height,blackSource);
            white=new NativeSkinnedSprite2D(context,frozenWhiteAsset,frozenWhite,width,height,whiteSource);
            renderer=new NativeRiggedCombatRenderer(width,height,cleanPlate,black,white);
            for(int i=0;i<frames;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                double seconds=i*durationSeconds/Math.max(1,frames-1);
                CombatActionPlan.Frame direction=director.at(seconds,durationSeconds);
                CombatRigSolver.Frame motion=CombatRigSolver.at(direction.solverSeconds,durationSeconds,storyboardMotion);
                NativeSkinnedSprite2D.requireRenderablePose(frozenBlack,motion.black,width,height);
                NativeSkinnedSprite2D.requireRenderablePose(frozenWhite,motion.white,width,height);
            }
            framesDir=new File(context.getFilesDir(),"creative_workspace/projects/"+output.id+"/generated/skinned_2d/"+generationId);
            if(!framesDir.mkdirs())throw new IllegalStateException("Could not prepare immutable private rig output");
            long required=Math.addExact(Math.multiplyExact((long)width*height*4+128*1024,frames),64L*1024*1024);
            if(new android.os.StatFs(framesDir.getAbsolutePath()).getAvailableBytes()<required)
                throw new IllegalStateException("Not enough private storage for bounded native rig frames");
            for(int i=0;i<frames;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                double seconds=i*durationSeconds/Math.max(1,frames-1);
                CombatActionPlan.Frame direction=director.at(seconds,durationSeconds);
                CombatRigSolver.Frame motion=CombatRigSolver.at(
                        direction.solverSeconds,durationSeconds,storyboardMotion);
                Bitmap image=renderer.render(motion,i,direction);
                File frame=new File(framesDir,String.format(Locale.US,"skin_%04d.jpg",i));
                boolean encoded=false;
                try(FileOutputStream stream=new FileOutputStream(frame)){
                    encoded=image.compress(Bitmap.CompressFormat.JPEG,91,stream);
                    stream.flush();stream.getFD().sync();
                }finally{image.recycle();}
                if(!encoded||frame.length()<100)
                    throw new IllegalStateException("Could not encode rigged frame "+i);
                ProjectStore.Asset asset=new ProjectStore.Asset();
                asset.id=UUID.randomUUID().toString();
                asset.uri=Uri.fromFile(frame).toString();
                asset.name=frame.getName();
                asset.mime="image/jpeg";
                asset.sizeBytes=frame.length();
                asset.seekable=true;
                asset.persistedReadAccess=true;
                asset.generated=true;
                asset.role="generated_skinned_2d";
                asset.generationMetadata=new JSONObject();
                asset.generationMetadata.put("engine","builtin.mesh-skinned-2d-v1");
                asset.generationMetadata.put("blackRigId",blackProfile.id);
                asset.generationMetadata.put("whiteRigId",whiteProfile.id);
                asset.generationMetadata.put("sourceProjectId",source.id);
                asset.generationMetadata.put("frame",i);
                asset.generationMetadata.put("generationId",generationId);
                asset.generationMetadata.put("frameIndex",i);
                asset.generationMetadata.put("frameClock","rational-authored-v1");
                asset.generationMetadata.put("fps",fps);
                asset.generationMetadata.put("blackSourceSha256",blackSource.getString("sourceSha256"));
                asset.generationMetadata.put("whiteSourceSha256",whiteSource.getString("sourceSha256"));
                asset.generationMetadata.put("motionIntent","source_deformation");
                asset.generationMetadata.put("cinematicAcceptanceVerified",false);
                asset.generationMetadata.put("decodedFrameCadenceVerified",false);
                asset.generationMetadata.put("poseTimeSeconds",seconds);
                asset.generationMetadata.put("impact",motion.impactIntensity);
                asset.generationMetadata.put("choreographyBeat",direction.beat.name());
                asset.generationMetadata.put("choreographyCue",director.cueAt(seconds,durationSeconds));
                asset.generationMetadata.put("sourceActorTexturesPreserved",true);
                asset.generationMetadata.put("neuralReconstruction",false);
                asset.generationMetadata.put("full3DMesh",false);
                prepared.assets.add(asset);
                ProjectStore.Clip clip=new ProjectStore.Clip();
                clip.id=UUID.randomUUID().toString();
                clip.assetId=asset.id;
                clip.inMs=0;
                clip.outMs=NativeFrameClock.frameDurationMs(i,fps);
                clip.speed=1f;
                clip.volume=0;
                clip.title="";
                clip.transition="none";
                clip.effects=new JSONObject();
                clip.effects.put("directorSequenceFrame",true);
                clip.effects.put("poseFps",fps);
                clip.effects.put("frameClock","rational-authored-v1");clip.effects.put("frameIndex",i);
                prepared.clips.add(clip);
                if(progress!=null)progress.onFrame(i+1,frames,seconds,motion.impactIntensity);
            }
            StoryboardSourceManifest.withVerifiedSource(context,frozenBlackAsset,blackSource,(file,entry)->null);
            StoryboardSourceManifest.withVerifiedSource(context,frozenWhiteAsset,whiteSource,(file,entry)->null);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            // Reserve refresh capacity before the durable commit; the final object update allocates nothing.
            output.assets.ensureCapacity(prepared.assets.size());output.clips.ensureCapacity(prepared.clips.size());
            store.guardedProject(source.id,sourceRevision,current->store.guardedProject(output.id,outputRevision,owned->{
                publicationAttempted[0]=true;store.save(prepared);return null;
            }));
            published=true;
            output.assets.clear();for(int i=0;i<prepared.assets.size();i++)output.assets.add(prepared.assets.get(i));
            output.clips.clear();for(int i=0;i<prepared.clips.size();i++)output.clips.add(prepared.clips.get(i));
            output.studioLayers.clear();output.sourcePrompt=prepared.sourcePrompt;output.updatedAt=prepared.updatedAt;
            output.latestExportUri="";output.latestExportName="";output.latestExportAt=0;
            return output;
        }finally{
            if(renderer!=null)renderer.close();
            else{if(black!=null)black.close();if(white!=null)white.close();}
            // An uncertain SQLite commit can already reference this generation; retain it for recovery.
            if(!published&&!publicationAttempted[0]&&framesDir!=null){
                File[] partial=framesDir.listFiles();if(partial!=null)for(File file:partial)if(file.isFile())file.delete();
                framesDir.delete();
            }
        }
    }

    /** Read ONLY one explicitly named VideoStudio asset, not the phone library. */
    public static Bitmap cleanPlate(Context ctx,ProjectStore.Asset asset,int w,int h)
            throws Exception{
        if(ctx==null||asset==null||asset.mime==null||
                !asset.mime.startsWith("image/"))
            throw new IllegalArgumentException("A VideoStudio-owned clean scene image is required");
        return NativeStoryboardSourceImage.read(ctx,asset,w,h);
    }
}
