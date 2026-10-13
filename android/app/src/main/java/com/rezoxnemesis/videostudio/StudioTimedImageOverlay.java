package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.StaticOverlaySettings;
import java.io.InputStream;

/** Project-owned image pixels at global composition times, with bounded decode and explicit cleanup. */
public final class StudioTimedImageOverlay extends BitmapOverlay {
    private final Context context;
    private final String uri;
    private final StudioLayer layer;
    private final Bitmap transparent=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
    private Bitmap source;
    private int outputWidth=1,outputHeight=1;
    private boolean released;
    public StudioTimedImageOverlay(Context context,ProjectStore.Asset asset,StudioLayer layer){
        if(asset==null||asset.mime==null||!asset.mime.startsWith("image/")||asset.uri==null||asset.uri.isEmpty()
                ||layer==null||!StudioLayer.IMAGE.equals(layer.type)||!asset.id.equals(layer.assetId))
            throw new IllegalArgumentException("Project-owned image layer required");
        layer.validate();this.context=context.getApplicationContext();uri=asset.uri;this.layer=layer.copy();
    }
    @Override public void configure(Size size){super.configure(size);outputWidth=size.getWidth();outputHeight=size.getHeight();}
    public boolean activeAt(long timeUs){return timeUs>=layer.startMs*1000L&&timeUs<(layer.startMs+layer.durationMs)*1000L;}
    @Override public Bitmap getBitmap(long presentationTimeUs)throws VideoFrameProcessingException{
        if(released)throw new VideoFrameProcessingException("Image overlay has been released");
        return activeAt(presentationTimeUs)?load():transparent;
    }
    private Bitmap load()throws VideoFrameProcessingException{
        if(released)throw new VideoFrameProcessingException("Image overlay has been released");
        if(source!=null)return source;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(uri))){
                if(input==null)throw new IllegalStateException("Owned image is unreadable");BitmapFactory.decodeStream(input,null,bounds);
            }
            if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>32768||bounds.outHeight>32768)
                throw new IllegalArgumentException("Image dimensions are unavailable or exceed 32768 pixels");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inSampleSize=1;
            while(Math.max(bounds.outWidth/options.inSampleSize,bounds.outHeight/options.inSampleSize)>1024
                    ||(long)(bounds.outWidth/options.inSampleSize)*(bounds.outHeight/options.inSampleSize)>1_048_576L)options.inSampleSize*=2;
            try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(uri))){
                if(input==null)throw new IllegalStateException("Owned image is unreadable");source=BitmapFactory.decodeStream(input,null,options);
            }
            if(source==null)throw new IllegalArgumentException("Could not decode imported image pixels");return source;
        }catch(Exception error){throw new VideoFrameProcessingException("Could not load timed image overlay",error);}
    }
    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs){
        boolean active=activeAt(presentationTimeUs);
        int width=1,height=1;
        if(active)try{Bitmap bitmap=load();width=bitmap.getWidth();height=bitmap.getHeight();}
        catch(VideoFrameProcessingException error){throw new IllegalStateException(error);}
        return new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(2*layer.x-1,1-2*layer.y).setOverlayFrameAnchor(0,0)
                .setScale(outputWidth*layer.width/width,outputHeight*layer.height/height)
                .setRotationDegrees(layer.rotation).setAlphaScale(active?layer.opacity:0).build();
    }
    @Override public void release()throws VideoFrameProcessingException{
        try{super.release();}finally{released=true;if(source!=null&&!source.isRecycled())source.recycle();
            if(!transparent.isRecycled())transparent.recycle();source=null;}
    }
}
