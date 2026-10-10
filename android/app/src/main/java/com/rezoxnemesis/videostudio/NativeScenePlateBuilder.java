package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Opt-in clean-plate ESTIMATE from the user's explicitly imported image
 * timeline. A per-channel temporal median suppresses changing stickman
 * silhouettes and creates ONE fixed background for the whole animation.
 *
 * This is not learned object removal: dark fixed fighters, drifting camera,
 * inconsistent generated buildings or shared foreground in >50% of frames
 * can leave median artifacts. Never advertise this as perfect inpainting.
 *
 * All reads are limited to the current VideoStudio project assets; no Gallery.
 */
public final class NativeScenePlateBuilder {
    private static final int MAX_SAMPLES=11;
    private NativeScenePlateBuilder(){}

    public static Bitmap fromProject(Context context,ProjectStore.Project source,
                                      int width,int height) throws Exception {
        if(context==null||source==null) throw new IllegalArgumentException("Source project is required");
        if(width<320||height<540||width>720||height>1280)
            throw new IllegalArgumentException("Invalid native background size");
        ArrayList<ProjectStore.Asset> images=new ArrayList<>();
        for(ProjectStore.Clip clip:source.clips){
            ProjectStore.Asset a=source.asset(clip.assetId);
            if(a!=null&&a.mime!=null&&a.mime.startsWith("image/"))images.add(a);
        }
        if(images.size()<5)
            throw new IllegalArgumentException("At least 5 imported images are required for an estimated fixed backdrop");
        int n=Math.min(MAX_SAMPLES,images.size());
        int[][] samples=new int[n][width*height];
        for(int i=0;i<n;i++){
            int sourceIndex=(int)Math.round(i*(images.size()-1.0)/(n-1.0));
            ProjectStore.Asset asset=images.get(sourceIndex);
            Bitmap sourceBitmap=read(context,Uri.parse(asset.uri),width,height);
            try {
                sourceBitmap.getPixels(samples[i],0,width,0,0,width,height);
            }finally{sourceBitmap.recycle();}
        }
        return perChannelMedian(samples,width,height);
    }

    public static Bitmap perChannelMedian(int[][] frames,int width,int height){
        if(frames==null||frames.length<3||frames.length>MAX_SAMPLES
                ||width<1||height<1||width*height>720*1280)
            throw new IllegalArgumentException("Invalid bounded clean-plate inputs");
        int len=width*height,n=frames.length;
        for(int[] f:frames) if(f==null||f.length!=len)
            throw new IllegalArgumentException("Each plate sample must share one exact frame size");
        int[] red=new int[n],green=new int[n],blue=new int[n];
        int[] out=new int[len];
        for(int pos=0;pos<len;pos++){
            for(int i=0;i<n;i++){
                int pixel=frames[i][pos];
                red[i]=pixel>>>16&255;
                green[i]=pixel>>>8&255;
                blue[i]=pixel&255;
            }
            Arrays.sort(red);Arrays.sort(green);Arrays.sort(blue);
            out[pos]=0xff000000|(red[n/2]<<16)|(green[n/2]<<8)|blue[n/2];
        }
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        bitmap.setPixels(out,0,width,0,0,width,height);
        return bitmap;
    }

    private static Bitmap read(Context ctx,Uri uri,int w,int h) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try(InputStream input=ctx.getContentResolver().openInputStream(uri)){
            if(input==null)throw new IllegalArgumentException("Input image is missing");
            BitmapFactory.decodeStream(input,null,bounds);
        }
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>8192
                ||bounds.outHeight>8192)
            throw new IllegalArgumentException("Source frame has unsupported dimensions");
        BitmapFactory.Options decode=new BitmapFactory.Options();
        decode.inPreferredConfig=Bitmap.Config.RGB_565;
        decode.inSampleSize=1;
        while(bounds.outWidth/decode.inSampleSize>w*2 &&
              bounds.outHeight/decode.inSampleSize>h*2)decode.inSampleSize*=2;
        Bitmap raw;
        try(InputStream input=ctx.getContentResolver().openInputStream(uri)){
            if(input==null)throw new IllegalArgumentException("Source frame is unreadable");
            raw=BitmapFactory.decodeStream(input,null,decode);
        }
        if(raw==null)throw new IllegalArgumentException("Source frame failed to decode");
        Bitmap result=Bitmap.createScaledBitmap(raw,w,h,true);
        if(result!=raw)raw.recycle();
        return result;
    }
}
