package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
        int frames=Math.round((float)(fps*durationSeconds));
        if(frames<2||frames>180)throw new IllegalArgumentException("Too many rigged frames");
        if(!output.assets.isEmpty()&&
                (output.sourcePrompt==null||!output.sourcePrompt.startsWith("rigged2d:")))
            throw new IllegalArgumentException("Destination contains unrelated user content");

        ProjectStore.Asset blackAsset=source.asset(blackProfile.sourceAssetId);
        ProjectStore.Asset whiteAsset=source.asset(whiteProfile.sourceAssetId);
        if(blackAsset==null||whiteAsset==null)
            throw new IllegalArgumentException("Actor source image no longer exists");

        File framesDir=new File(context.getFilesDir(),
                "creative_workspace/projects/"+output.id+"/generated/skinned_2d");
        if(!framesDir.exists()&&!framesDir.mkdirs())
            throw new IllegalStateException("Could not prepare private rig output");
        File[] old=framesDir.listFiles();
        if(old!=null)for(File f:old) {
            if(f.isFile()&&f.getName().matches("skin_[0-9]{4}\\.jpg")&&!f.delete())
                throw new IllegalStateException("Could not reset partial rig frame");
        }
        output.assets.clear();
        output.clips.clear();
        output.sourcePrompt="rigged2d: two source-masked actor meshes animated by continuous IK";
        store.save(output);

        NativeSkinnedSprite2D black=new NativeSkinnedSprite2D(
                context,blackAsset,blackProfile,width,height);
        NativeSkinnedSprite2D white=null;
        NativeRiggedCombatRenderer renderer=null;
        try{
            white=new NativeSkinnedSprite2D(context,whiteAsset,whiteProfile,width,height);
            renderer=new NativeRiggedCombatRenderer(width,height,cleanPlate,black,white);
            long frameMs=Math.max(34,Math.round(1000.0/fps));
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
                    stream.flush();
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
                asset.generationMetadata.put("poseTimeSeconds",seconds);
                asset.generationMetadata.put("impact",motion.impactIntensity);
                asset.generationMetadata.put("choreographyBeat",direction.beat.name());
                asset.generationMetadata.put("choreographyCue",director.cueAt(seconds,durationSeconds));
                asset.generationMetadata.put("sourceActorTexturesPreserved",true);
                asset.generationMetadata.put("neuralReconstruction",false);
                asset.generationMetadata.put("full3DMesh",false);
                output.assets.add(asset);
                ProjectStore.Clip clip=new ProjectStore.Clip();
                clip.id=UUID.randomUUID().toString();
                clip.assetId=asset.id;
                clip.inMs=0;
                clip.outMs=frameMs;
                clip.speed=1f;
                clip.volume=0;
                clip.title="";
                clip.transition="none";
                clip.effects=new JSONObject();
                clip.effects.put("directorSequenceFrame",true);
                clip.effects.put("poseFps",fps);
                output.clips.add(clip);
                if(progress!=null)progress.onFrame(i+1,frames,seconds,motion.impactIntensity);
            }
            store.save(output);
            return output;
        }finally{
            if(renderer!=null)renderer.close();
            else{black.close();if(white!=null)white.close();}
        }
    }

    /** Read ONLY one explicitly named VideoStudio asset, not the phone library. */
    public static Bitmap cleanPlate(Context ctx,ProjectStore.Asset asset,int w,int h)
            throws Exception{
        if(ctx==null||asset==null||asset.mime==null||
                !asset.mime.startsWith("image/"))
            throw new IllegalArgumentException("A VideoStudio-owned clean scene image is required");
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        Uri uri=Uri.parse(asset.uri);
        try(InputStream in=ctx.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Clean plate unreadable");
            BitmapFactory.decodeStream(in,null,bounds);
        }
        if(bounds.outWidth<1||bounds.outHeight<1||
                bounds.outWidth>8192||bounds.outHeight>8192)
            throw new IllegalArgumentException("Clean plate image dimensions are unsupported");
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
        opts.inSampleSize=1;
        while(bounds.outWidth/opts.inSampleSize>w*2&&
                bounds.outHeight/opts.inSampleSize>h*2)opts.inSampleSize*=2;
        Bitmap raw;
        try(InputStream in=ctx.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Clean plate missing");
            raw=BitmapFactory.decodeStream(in,null,opts);
        }
        if(raw==null)throw new IllegalArgumentException("Could not decode clean plate");
        Bitmap scaled=Bitmap.createScaledBitmap(raw,w,h,true);
        if(scaled!=raw)raw.recycle();
        return scaled;
    }
}
