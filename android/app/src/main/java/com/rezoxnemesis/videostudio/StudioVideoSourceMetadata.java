package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Selected app-owned VIDEO metadata/byte proof. Never decodes a frame or reads Gallery. */
final class StudioVideoSourceMetadata {
    private StudioVideoSourceMetadata(){}

    static final class Proof {
        final String assetId,uri,assetJson,sha256;
        final long bytes,durationMs;
        final int displayWidth,displayHeight,rotation;
        Proof(ProjectStore.Asset asset,String assetJson,String sha256,long bytes,int width,int height,int rotation){
            this.assetId=asset.id;this.uri=asset.uri;this.assetJson=assetJson;this.sha256=sha256;this.bytes=bytes;
            durationMs=asset.durationMs;displayWidth=width;displayHeight=height;this.rotation=rotation;
        }
        double[] fit(double width,double height,int canvasWidth,int canvasHeight){
            if(!Double.isFinite(width)||!Double.isFinite(height)||width<.01||width>2||height<.01||height>2)
                throw new IllegalArgumentException("Use a box width/height in 0.01..2");
            double aspect=displayWidth/(double)displayHeight;
            double fittedWidth=Math.min(width,height*canvasHeight*aspect/canvasWidth);
            double fittedHeight=fittedWidth*canvasWidth/aspect/canvasHeight;
            if(fittedWidth<.01||fittedHeight<.01)throw new IllegalArgumentException("Enlarge the box to fit the complete source within the minimum size");
            return new double[]{fittedWidth,fittedHeight};
        }
    }

    private static ProjectStore.Asset bound(Context context,String projectId,long revision,String assetId,String expected)throws Exception{
        try(ProjectStore store=new ProjectStore(context)){
            ProjectStore.Project project=store.get(projectId);
            if(project==null||project.updatedAt!=revision)throw new IllegalStateException("The source project changed. Reopen the video overlay.");
            ProjectStore.Asset asset=project.asset(assetId);
            if(asset==null||asset.mime==null||!asset.mime.startsWith("video/")||!asset.seekable||asset.durationMs<=0)
                throw new IllegalArgumentException("Choose an owned, seekable video with a known duration");
            if(!expected.equals(SketchDocument.canonical(asset.toJson())))throw new IllegalStateException("The selected video source changed. Reopen its controls.");
            return ProjectStore.Asset.fromJson(new JSONObject(asset.toJson().toString()));
        }
    }

    private static Proof probe(Context context,String projectId,long revision,String assetId,String expected)throws Exception{
        worker();ProjectStore.Asset asset=bound(context,projectId,revision,assetId,expected);
        File file=ProjectAssetImport.sourceFile(context,asset);long bytes=file.length();String hash=hash(file);
        int width,height,rotation;long duration;
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            retriever.setDataSource(context,Uri.parse(asset.uri));
            width=positiveInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),"width");
            height=positiveInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),"height");
            String rawRotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if(rawRotation==null||!rawRotation.matches("-?[0-9]+"))throw new IllegalArgumentException("Source rotation is unavailable; source proportions cannot be verified");
            rotation=Math.floorMod(Integer.parseInt(rawRotation),360);
            if(rotation%90!=0)throw new IllegalArgumentException("Unsupported source rotation; source proportions cannot be verified");
            String rawDuration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            duration=rawDuration==null?0:Long.parseLong(rawDuration);
            if(duration<=0||duration!=asset.durationMs)throw new IllegalStateException("Native video duration differs from its imported metadata. Re-import the source.");
        }finally{retriever.release();}
        if(file.length()!=bytes||!hash.equals(hash(file)))throw new IllegalStateException("Video source bytes changed while checking metadata");
        bound(context,projectId,revision,assetId,expected);cancelled();
        boolean swapped=rotation==90||rotation==270;
        return new Proof(asset,expected,hash,bytes,swapped?height:width,swapped?width:height,rotation);
    }

    private static void verify(Context context,String projectId,long revision,Proof proof)throws Exception{
        worker();ProjectStore.Asset asset=bound(context,projectId,revision,proof.assetId,proof.assetJson);
        File file=ProjectAssetImport.sourceFile(context,asset);
        if(file.length()!=proof.bytes||!proof.sha256.equals(hash(file)))throw new IllegalStateException("Video source bytes changed. Reopen its controls.");
        bound(context,projectId,revision,proof.assetId,proof.assetJson);cancelled();
    }
    private static int positiveInt(String value,String label){
        try{int number=Integer.parseInt(value);if(number>0)return number;}catch(Exception ignored){}
        throw new IllegalArgumentException("Native video "+label+" is unavailable");
    }
    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];
        try(FileInputStream input=new FileInputStream(file)){for(int n;(n=input.read(buffer))!=-1;){cancelled();if(n>0)digest.update(buffer,0,n);}}
        StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hex.toString();
    }
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Video metadata and hash checks require a worker");}
    private static void cancelled()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Video source check cancelled");}

    /** One bounded worker per open editor; final project/CAS mutation stays in the existing UI save. */
    static final class Session implements AutoCloseable {
        private final Activity activity;
        private final String projectId,assetId,assetJson;
        private final long revision;
        private final BooleanSupplier live,current;
        private final Predicate<StudioLayer> save;
        private final ExecutorService io=Executors.newSingleThreadExecutor();
        private final Handler main=new Handler(Looper.getMainLooper());
        private final AtomicBoolean closed=new AtomicBoolean(),busy=new AtomicBoolean();
        private Proof proof;
        Session(Activity activity,String projectId,long revision,ProjectStore.Asset asset,BooleanSupplier live,
                BooleanSupplier current,Predicate<StudioLayer> save)throws Exception{
            this.activity=activity;this.projectId=projectId;this.revision=revision;assetId=asset.id;assetJson=SketchDocument.canonical(asset.toJson());
            this.live=live;this.current=current;this.save=save;
        }
        void prepare(Consumer<Proof> ready,Consumer<String> failure){
            if(closed.get()||!busy.compareAndSet(false,true))return;
            io.execute(()->{
                try{
                    Proof captured=probe(activity,projectId,revision,assetId,assetJson);
                    post(()->{busy.set(false);if(!current.getAsBoolean()){failure.accept("The editing view changed. Reopen the video overlay.");return;}proof=captured;ready.accept(captured);});
                }catch(Exception error){post(()->{busy.set(false);failure.accept(message(error));});}
            });
        }
        void apply(StudioLayer candidate,BiConsumer<Boolean,String> completed){
            if(closed.get()||!live.getAsBoolean())return;
            if(!current.getAsBoolean()){completed.accept(false,"The editing view changed. Reopen the video overlay.");return;}
            if(candidate==null||!StudioLayer.VIDEO.equals(candidate.type)||!assetId.equals(candidate.assetId)){
                completed.accept(false,"The draft does not use this verified video source");return;
            }
            Proof captured=proof;
            if(captured==null){completed.accept(false,"Wait for the owned source check before applying");return;}
            final StudioLayer draft=candidate.copy();
            if(!busy.compareAndSet(false,true))return;
            io.execute(()->{
                try{
                    verify(activity,projectId,revision,captured);
                    post(()->{
                        busy.set(false);
                        if(!current.getAsBoolean()){completed.accept(false,"The editing view changed. Reopen the video overlay.");return;}
                        try{boolean accepted=save.test(draft);completed.accept(accepted,accepted?"":"Video overlay was not saved; the current project was preserved");}
                        catch(Exception error){completed.accept(false,message(error));}
                    });
                }catch(Exception error){post(()->{busy.set(false);completed.accept(false,message(error));});}
            });
        }
        private void post(Runnable callback){if(!closed.get())main.post(()->{if(!closed.get()&&live.getAsBoolean()&&!activity.isFinishing()&&!activity.isDestroyed())callback.run();});}
        @Override public void close(){if(!closed.compareAndSet(false,true))return;main.removeCallbacksAndMessages(null);io.shutdownNow();}
        private static String message(Exception error){return error.getMessage()==null?"Video source check failed":error.getMessage();}
    }
}
