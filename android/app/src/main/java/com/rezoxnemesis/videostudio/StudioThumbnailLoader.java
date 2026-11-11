package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Project-only asynchronous thumbnails for a real, tappable timeline.
 * Only receives media URIs already registered in VideoStudio's ProjectStore;
 * it never queries MediaStore or enumerates the user's Android Gallery.
 */
public final class StudioThumbnailLoader {
    private static final int TARGET_SIDE=160;
    private final Context context;
    private final ExecutorService decoder=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(8*1024*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}
    };
    public StudioThumbnailLoader(Context context){
        this.context=context.getApplicationContext();
    }

    public void bind(ImageView image,ProjectStore.Asset asset){
        if(image==null||asset==null)return;
        String key=asset.id;
        image.setTag(key);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xff142034);
        Bitmap cached=cache.get(key);
        if(cached!=null&&!cached.isRecycled()){
            image.setImageBitmap(cached);
            return;
        }
        image.setImageDrawable(null);
        WeakReference<ImageView> view=new WeakReference<>(image);
        decoder.execute(()->{
            Bitmap bitmap=null;
            try{
                if(asset.mime!=null&&asset.mime.startsWith("image/")){
                    bitmap=decodeImage(Uri.parse(asset.uri));
                }else if(asset.mime!=null&&asset.mime.startsWith("video/")){
                    bitmap=decodeVideo(Uri.parse(asset.uri));
                }
            }catch(Exception ignored){}
            Bitmap result=bitmap;
            if(result==null)return;
            main.post(()->{
                cache.put(key,result);
                ImageView target=view.get();
                if(target!=null&&key.equals(target.getTag()))target.setImageBitmap(result);
            });
        });
    }

    /**
     * Bounded larger preview of a source still, not a full-resolution import
     * on the Android UI thread. Prevents blurry 160px timeline thumbnails.
     */
    public void bindPreview(ImageView image,ProjectStore.Asset asset){
        if(image==null||asset==null)return;
        final String key="preview:"+asset.id;
        image.setTag(key);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xff070b13);
        image.setImageDrawable(null);
        WeakReference<ImageView> target=new WeakReference<>(image);
        decoder.execute(()->{
            Bitmap b=null;
            try{
                if(asset.mime!=null&&asset.mime.startsWith("image/"))
                    b=decodeImage(Uri.parse(asset.uri),900);
                else if(asset.mime!=null&&asset.mime.startsWith("video/"))
                    b=decodeVideo(Uri.parse(asset.uri));
            }catch(Exception ignored){}
            Bitmap bitmap=b;
            if(bitmap==null)return;
            main.post(()->{
                ImageView v=target.get();
                if(v!=null&&key.equals(v.getTag()))v.setImageBitmap(bitmap);
            });
        });
    }

    private Bitmap decodeImage(Uri uri) throws Exception{
        return decodeImage(uri,TARGET_SIDE);
    }
    private Bitmap decodeImage(Uri uri,int targetSide) throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)return null;
            BitmapFactory.decodeStream(in,null,bounds);
        }
        if(bounds.outWidth<=0||bounds.outHeight<=0||
                bounds.outWidth>8192||bounds.outHeight>8192)return null;
        BitmapFactory.Options sample=new BitmapFactory.Options();
        sample.inPreferredConfig=Bitmap.Config.RGB_565;
        sample.inSampleSize=sampleSize(bounds.outWidth,bounds.outHeight,targetSide);
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)return null;
            Bitmap original=BitmapFactory.decodeStream(in,null,sample);
            if(original==null)return null;
            return scaled(original,targetSide);
        }
    }

    private Bitmap decodeVideo(Uri uri){
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            retriever.setDataSource(context,uri);
            Bitmap frame=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return frame==null?null:scaled(frame);
        }finally{
            try{retriever.release();}catch(Exception ignored){}
        }
    }
    private static Bitmap scaled(Bitmap source){return scaled(source,TARGET_SIDE);}
    private static Bitmap scaled(Bitmap source,int maxSide){
        int w=source.getWidth(),h=source.getHeight();
        double s=Math.min(1.0,maxSide/(double)Math.max(w,h));
        int outW=Math.max(1,(int)(w*s)),outH=Math.max(1,(int)(h*s));
        if(w==outW&&h==outH)return source;
        Bitmap result=Bitmap.createScaledBitmap(source,outW,outH,true);
        if(result!=source)source.recycle();
        return result;
    }
    public static int sampleSize(int width,int height,int maxSide){
        if(width<=0||height<=0||maxSide<=0)throw new IllegalArgumentException("Invalid thumbnail size");
        int sample=1;
        while(Math.max(width/sample,height/sample)>maxSide*2)sample*=2;
        return sample;
    }
    public void shutdown(){decoder.shutdownNow();}
}
