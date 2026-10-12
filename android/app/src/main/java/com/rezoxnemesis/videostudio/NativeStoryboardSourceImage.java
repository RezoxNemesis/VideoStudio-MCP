package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import java.io.File;
import org.json.JSONObject;

/** Bounded source read in the oriented UV coordinate system used by authored rigs. */
public final class NativeStoryboardSourceImage {
    private NativeStoryboardSourceImage() {}

    public static Bitmap read(Context context,ProjectStore.Asset asset,int width,int height) throws Exception {
        return ownedRead(callback->StoryboardSourceManifest.withFrozenSource(context,asset,callback),width,height,true);
    }
    public static Bitmap readVerified(Context context,ProjectStore.Asset asset,int width,int height,String expectedSha256) throws Exception {
        if(expectedSha256==null)throw new IllegalArgumentException("A frozen source SHA-256 is required");
        return ownedRead(callback->StoryboardSourceManifest.withVerifiedSource(context,asset,expectedSha256.toLowerCase(java.util.Locale.US),callback),width,height,true);
    }
    public static Bitmap readVerified(Context context,ProjectStore.Asset asset,int width,int height,JSONObject sourceEntry) throws Exception {
        if(asset==null||sourceEntry==null||!asset.id.equals(sourceEntry.optString("assetId")))
            throw new IllegalArgumentException("Frozen manifest must bind this source asset");
        return ownedRead(callback->StoryboardSourceManifest.withVerifiedSource(context,asset,sourceEntry,callback),width,height,true);
    }
    /** Explicit non-rig contain mapping; source-rig callers must use the strict reader. */
    public static Bitmap readVerifiedFit(Context context,ProjectStore.Asset asset,int width,int height,JSONObject entry)throws Exception{
        return ownedRead(callback->StoryboardSourceManifest.withVerifiedSource(context,asset,entry,callback),width,height,false);
    }
    private interface SnapshotRead {Bitmap read(StoryboardSourceManifest.VerifiedSource<Bitmap> callback)throws Exception;}
    private static Bitmap ownedRead(SnapshotRead operation,int width,int height,boolean requireAspect)throws Exception{
        Bitmap[] decoded={null};boolean returned=false;
        try{
            Bitmap result=operation.read((file,entry)->decoded[0]=decodeSnapshot(file,entry,width,height,requireAspect));
            returned=true;return result;
        }finally{if(!returned&&decoded[0]!=null&&!decoded[0].isRecycled())decoded[0].recycle();}
    }
    /** Only call with the immutable encoded file supplied by a verified-source callback. */
    public static Bitmap decodeSnapshot(File encoded,JSONObject sourceEntry,int width,int height)throws Exception{
        return decodeSnapshot(encoded,sourceEntry,width,height,true);
    }
    private static Bitmap decodeSnapshot(File encoded,JSONObject sourceEntry,int width,int height,boolean requireAspect)throws Exception{
        if(encoded==null||!encoded.isFile()||sourceEntry==null||width<128||height<128||width>1280||height>1920)
            throw new IllegalArgumentException("A bounded private source snapshot is required");
        Bitmap owned=null;
        try{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(encoded.getAbsolutePath(),bounds);
        if(bounds.outWidth<16||bounds.outHeight<16||bounds.outWidth>8192||bounds.outHeight>8192)
            throw new IllegalArgumentException("Invalid storyboard source dimensions");
        int orientation=1;
        if(!"image/bmp".equals(bounds.outMimeType)&&!"image/gif".equals(bounds.outMimeType)){
            orientation=new ExifInterface(encoded.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
            if(orientation==0)orientation=1;
            if(orientation<1||orientation>8)throw new IllegalArgumentException("Invalid source EXIF orientation");
        }
        int orientedWidth=orientation>=5?bounds.outHeight:bounds.outWidth;
        int orientedHeight=orientation>=5?bounds.outWidth:bounds.outHeight;
        if(sourceEntry!=null&&(sourceEntry.getInt("codedWidth")!=bounds.outWidth||sourceEntry.getInt("codedHeight")!=bounds.outHeight
                ||sourceEntry.getInt("exifOrientation")!=orientation||sourceEntry.getInt("orientedWidth")!=orientedWidth
                ||sourceEntry.getInt("orientedHeight")!=orientedHeight))
            throw new IllegalArgumentException("Decoded source geometry does not match the frozen manifest");
        // No independent stretching of the city, body, prop or contact coordinate system.
        if(requireAspect&&Math.abs(orientedWidth/(double)orientedHeight-width/(double)height)>0.000001)
            throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: oriented source and output aspect differ; explicit source registration is required");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        options.inSampleSize=1;
        while(orientedWidth/options.inSampleSize>width*2||orientedHeight/options.inSampleSize>height*2)
            options.inSampleSize*=2;
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            owned=BitmapFactory.decodeFile(encoded.getAbsolutePath(),options);
            if(owned==null)throw new IllegalArgumentException("Cannot decode storyboard source");
            Matrix transform=orientationMatrix(orientation);
            if(!transform.isIdentity()){
                Bitmap oriented=Bitmap.createBitmap(owned,0,0,owned.getWidth(),owned.getHeight(),transform,true);
                if(oriented!=owned){owned.recycle();owned=oriented;}
            }
            Bitmap scaled;
            if(requireAspect)scaled=Bitmap.createScaledBitmap(owned,width,height,true);
            else{
                scaled=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                try{
                    double scale=Math.min(width/(double)orientedWidth,height/(double)orientedHeight);
                    float fitWidth=(float)(orientedWidth*scale),fitHeight=(float)(orientedHeight*scale);
                    new Canvas(scaled).drawBitmap(owned,null,new RectF((width-fitWidth)/2,(height-fitHeight)/2,
                            (width+fitWidth)/2,(height+fitHeight)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
                }catch(RuntimeException|Error failure){scaled.recycle();throw failure;}
            }
            if(scaled!=owned){owned.recycle();owned=scaled;}
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            Bitmap result=owned;owned=null;return result;
        }finally{
            if(owned!=null&&!owned.isRecycled())owned.recycle();
        }
    }
    public static JSONObject fitMapping(JSONObject entry,int width,int height)throws Exception{
        int sourceWidth=entry.getInt("orientedWidth"),sourceHeight=entry.getInt("orientedHeight");
        if(sourceWidth<=0||sourceHeight<=0||width<=0||height<=0)throw new IllegalArgumentException("Positive fit dimensions required");
        double scale=Math.min(width/(double)sourceWidth,height/(double)sourceHeight);
        double xScale=sourceWidth*scale/width,yScale=sourceHeight*scale/height;
        return new JSONObject().put("mode","contain").put("coordinateSpace","oriented-source-uv")
                .put("sourceWidth",sourceWidth).put("sourceHeight",sourceHeight).put("outputWidth",width).put("outputHeight",height)
                .put("scaleX",xScale).put("scaleY",yScale).put("offsetX",(1-xScale)/2).put("offsetY",(1-yScale)/2)
                .put("padding","transparent").put("independentStretch",false);
    }
    public static Matrix orientationMatrix(int orientation){
        if(orientation<1||orientation>8)throw new IllegalArgumentException("EXIF orientation must be1..8");
        Matrix matrix=new Matrix();
        switch(orientation){
            case 2:matrix.setScale(-1,1);break;
            case 3:matrix.setRotate(180);break;
            case 4:matrix.setScale(1,-1);break;
            case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;
            case 6:matrix.setRotate(90);break;
            case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;
            case 8:matrix.setRotate(-90);break;
            default:break;
        }
        return matrix;
    }
}
