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
        if(context==null||store==null||source==null||output==null||
                source.id.equals(output.id))
            throw new IllegalArgumentException("Separate imported source and rendered project required");
        if(fps!=24&&fps!=30||width<256||height<432||width>720||height>1280||
                (width&1)!=0||(height&1)!=0)
            throw new IllegalArgumentException("Use even portrait 256..720 wide, 432..1280 high, at 24 or 30fps");
        if(!Double.isFinite(seconds)||seconds<1.5||seconds>5.0)
            throw new IllegalArgumentException("Storyboard shot must be 1.5 to 5 seconds");
        int frames=(int)Math.ceil(fps*seconds);
        if(frames<3||frames>150)throw new IllegalArgumentException("Shot frame budget exceeded");
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
        int[] start=read(context,from,width,height);
        int[] end=read(context,to,width,height);
        StoryboardCleanPlate.Result recovered=null;
        int[] plate;
        if(backgroundAssetId!=null&&!backgroundAssetId.trim().isEmpty()){
            ProjectStore.Asset clean=source.asset(backgroundAssetId);
            if(clean==null||clean.mime==null||!clean.mime.startsWith("image/"))
                throw new IllegalArgumentException("Clean background must be an explicitly imported image");
            plate=read(context,clean,width,height);
        }else{
            recovered=StoryboardCleanPlate.restore(start,end,width,height,
                    blackFrom.outline,whiteFrom.outline,
                    blackTo.outline,whiteTo.outline);
            plate=recovered.pixels;
        }
        double sceneDrift=recovered==null?0:recovered.backgroundDifference;
        double fillFraction=recovered==null?0:recovered.unobservedFraction;

        File outDir=new File(context.getFilesDir(),
                "creative_workspace/projects/"+output.id+"/generated/storyboard_artwork");
        if(!outDir.isDirectory()&&!outDir.mkdirs())
            throw new IllegalStateException("Could not create private shot frame workspace");
        File[] old=outDir.listFiles();
        if(old!=null)for(File file:old)
            if(file.isFile()&&file.getName().matches("story_[0-9]{4}\\.png")&&!file.delete())
                throw new IllegalStateException("Cannot reset previous incomplete shot");
        output.assets.clear();
        output.clips.clear();
        output.sourcePrompt="storyboard-artwork-v1: source traced 2D pixel/mesh animation";
        store.save(output);

        NativeSkinnedSprite2D bkA=null,whA=null,bkB=null,whB=null;
        NativeStoryboardArtworkRenderer renderer=null;
        try {
            bkA=new NativeSkinnedSprite2D(context,from,blackFrom,width,height);
            whA=new NativeSkinnedSprite2D(context,from,whiteFrom,width,height);
            bkB=new NativeSkinnedSprite2D(context,to,blackTo,width,height);
            whB=new NativeSkinnedSprite2D(context,to,whiteTo,width,height);
            renderer=new NativeStoryboardArtworkRenderer(width,height,plate,
                    blackFrom,whiteFrom,blackTo,whiteTo,
                    bkA,whA,bkB,whB,blackTipFrom,whiteTipFrom,blackTipTo,whiteTipTo,beat);
            long frameMs=Math.max(34,Math.round(1000d/fps));
            for(int i=0;i<frames;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                double t=i/(double)(frames-1);
                Bitmap pixels=renderer.render(t,i,frames);
                try{
                    save(output,outDir,pixels,i,frameMs,fps,
                            from.id,to.id,action);
                }finally{pixels.recycle();}
                if(progress!=null)progress.onFrame(i+1,frames,sceneDrift);
            }
            store.save(output);
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

    private static int[] read(Context context,ProjectStore.Asset source,
                              int width,int height) throws Exception {
        if(source==null||source.uri==null||source.mime==null||
                !source.mime.startsWith("image/"))
            throw new IllegalArgumentException("Original storyboard image was not imported");
        Uri uri=Uri.parse(source.uri);
        BitmapFactory.Options limit=new BitmapFactory.Options();
        limit.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Image permission expired");
            BitmapFactory.decodeStream(in,null,limit);
        }
        if(limit.outWidth<64||limit.outHeight<64||
                limit.outWidth>8192||limit.outHeight>8192)
            throw new IllegalArgumentException("Unreasonable source-image dimensions");
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        int sample=1;
        while(limit.outWidth/sample>width*2 || limit.outHeight/sample>height*2)sample*=2;
        options.inSampleSize=sample;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Original image is missing");
            Bitmap full=BitmapFactory.decodeStream(in,null,options);
            if(full==null)throw new IllegalArgumentException("Cannot decode original image");
            Bitmap scaled=Bitmap.createScaledBitmap(full,width,height,true);
            if(scaled!=full)full.recycle();
            int[] pixels=new int[width*height];
            scaled.getPixels(pixels,0,width,0,0,width,height);
            scaled.recycle();
            return pixels;
        }
    }

    private static void save(ProjectStore.Project output,File dir,Bitmap bitmap,
                             int index,long durationMs,int fps,
                             String anchorA,String anchorB,String action) throws Exception {
        String name=String.format(Locale.US,"story_%04d.png",index);
        File destination=new File(dir,name);
        boolean ok;
        try(FileOutputStream out=new FileOutputStream(destination)){
            ok=bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
            out.flush();
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
                .put("fromSourceAssetId",anchorA)
                .put("toSourceAssetId",anchorB)
                .put("notNeuralVideo",true)
                .put("frameIndex",index)
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
        output.clips.add(clip);
    }
}
