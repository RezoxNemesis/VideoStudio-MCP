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
 * Bounded Android generator of an actual 2D articulated fight, rendered at
 * each encoded timestamp into private app-owned image samples and then exported
 * using the existing Media3 pipeline. The camera/background remain locked.
 */
public final class NativeCombatRigComposer {
    public interface Progress {
        void onFrame(int completed,int total,double seconds,double contactStrength) throws Exception;
    }

    private NativeCombatRigComposer(){}

    public static ProjectStore.Project compose(Context context,ProjectStore store,
              ProjectStore.Project output,int width,int height,int fps,
              double durationSeconds,Progress progress) throws Exception {
        return compose(context,store,output,width,height,fps,durationSeconds,null,progress);
    }

    public static ProjectStore.Project compose(Context context,ProjectStore store,
              ProjectStore.Project output,int width,int height,int fps,
              double durationSeconds,Bitmap sourceBackdrop,Progress progress) throws Exception {
        if(context==null||store==null||output==null)
            throw new IllegalArgumentException("A native output project is required");
        if(fps!=24&&fps!=30)
            throw new IllegalArgumentException("Combat rig supports exactly 24 or 30 fps");
        if(durationSeconds<2.0||durationSeconds>5.0||!Double.isFinite(durationSeconds))
            throw new IllegalArgumentException("Combat benchmark must be 2-5 seconds");
        if(width<320||height<540||width>720||height>1280
                ||width%2!=0||height%2!=0)
            throw new IllegalArgumentException("Invalid bounded combat canvas");
        int total=Math.max(2,(int)Math.round(durationSeconds*fps));
        if(total>150)throw new IllegalArgumentException("Combat benchmark exceeds 150 frames");
        if(!output.assets.isEmpty() && !output.sourcePrompt.startsWith("combat-rig:"))
            throw new IllegalArgumentException("Existing non-combat project cannot be overwritten");
        String base="combat_rig/"+output.id+"/frames";
        File frames=new File(context.getFilesDir(),base);
        if(!frames.exists()&&!frames.mkdirs())
            throw new IllegalStateException("Cannot prepare combat rig frame storage");

        // Retry only clears frame files from this generated benchmark output.
        File[] existing=frames.listFiles();
        if(existing!=null)for(File f:existing)
            if(f.isFile() && f.getName().matches("rig_[0-9]{4}\\.jpg") && !f.delete())
                throw new IllegalStateException("Could not remove stale generated combat frame");
        output.assets.clear();
        output.clips.clear();
        output.sourcePrompt="combat-rig: fully procedural articulated motion with static background";
        store.save(output);
        NativeCombatRigRenderer renderer=new NativeCombatRigRenderer(width,height,sourceBackdrop);
        long frameMs=Math.max(34,Math.round(1000.0/fps));
        try {
            for(int i=0;i<total;i++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                double seconds=i*durationSeconds/Math.max(1,total-1);
                CombatRigSolver.Frame movement=CombatRigSolver.at(seconds,durationSeconds);
                Bitmap result=renderer.render(movement,seconds,durationSeconds);
                String name=String.format(Locale.US,"rig_%04d.jpg",i);
                File dest=new File(frames,name);
                boolean encoded;
                try(FileOutputStream out=new FileOutputStream(dest)){
                    encoded=result.compress(Bitmap.CompressFormat.JPEG,91,out);
                    out.flush();
                } finally {
                    result.recycle();
                }
                if(!encoded||!dest.exists()||dest.length()<100)
                    throw new IllegalStateException("Could not encode articulated frame "+i);
                ProjectStore.Asset asset=new ProjectStore.Asset();
                asset.id=UUID.randomUUID().toString();
                asset.uri=Uri.fromFile(dest).toString();
                asset.mime="image/jpeg";
                asset.name=name;
                asset.sizeBytes=dest.length();
                asset.seekable=true;
                asset.persistedReadAccess=true;
                asset.role="generated_combat_rig";
                asset.generated=true;
                asset.generationMetadata=new JSONObject();
                asset.generationMetadata.put("engine","builtin.native-combat-rig-v1");
                asset.generationMetadata.put("movingCharacterRig",true);
                asset.generationMetadata.put("fixedEnvironment",true);
                asset.generationMetadata.put("backgroundMode",sourceBackdrop==null?"procedural":"source_median");
                asset.generationMetadata.put("referenceArtReconstruction",false);
                asset.generationMetadata.put("frameIndex",i);
                asset.generationMetadata.put("sceneTime",movement.sceneTime);
                asset.generationMetadata.put("impact",movement.impactIntensity);
                output.assets.add(asset);
                ProjectStore.Clip clip=new ProjectStore.Clip();
                clip.id=UUID.randomUUID().toString();
                clip.assetId=asset.id;
                clip.inMs=0;
                clip.outMs=frameMs;
                clip.speed=1f;
                clip.volume=0;
                clip.transition="none";
                clip.title="";
                clip.effects=new JSONObject();
                clip.effects.put("directorSequenceFrame",true);
                clip.effects.put("poseFps",fps);
                output.clips.add(clip);
                if(progress!=null) progress.onFrame(i+1,total,seconds,movement.impactIntensity);
            }
            store.save(output);
            return output;
        }finally {
            renderer.recycle();
        }
    }
}
