package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Reopens URI streams for bounds and subsampled decode; never reads a source file
 * into a compressed-byte array. Large stills have a fixed decoded pixel budget.
 */
@UnstableApi
final class StreamingBitmapLoader implements BitmapLoader {
    private static final long MAX_DECODE_PIXELS=8_388_608L;
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),runnable->{Thread thread=new Thread(runnable,"VideoStudio-image-decode");thread.setDaemon(true);return thread;},
            new ThreadPoolExecutor.AbortPolicy());
    private final Context context;
    private final int maxEdge;
    StreamingBitmapLoader(Context context,int maxEdge){this.context=context.getApplicationContext();this.maxEdge=Math.max(64,maxEdge);}
    @Override public boolean supportsMimeType(String mime){return Util.isBitmapFactorySupportedMimeType(mime);}
    @Override public ListenableFuture<Bitmap> decodeBitmap(byte[] bytes) {
        if(bytes.length>32*1024*1024)return Futures.immediateFailedFuture(new IllegalArgumentException("Embedded artwork exceeds decode byte limit"));
        return submit(()->{
            checkInterrupted();
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            checkInterrupted();
            Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options(bounds));
            if(bitmap==null)throw new IllegalArgumentException("Could not decode embedded bitmap");
            try{checkInterrupted();return bitmap;}catch(Exception error){bitmap.recycle();throw error;}
        });
    }
    @Override public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
        return submit(()->{
            checkInterrupted();
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            try(InputStream input=context.getContentResolver().openInputStream(uri)){if(input==null)throw new IllegalArgumentException("Image source is unavailable");BitmapFactory.decodeStream(input,null,bounds);}
            checkInterrupted();Bitmap bitmap=null;
            try {
            try(InputStream input=context.getContentResolver().openInputStream(uri)){if(input==null)throw new IllegalArgumentException("Image source is unavailable");bitmap=BitmapFactory.decodeStream(input,null,options(bounds));}
            if(bitmap==null)throw new IllegalArgumentException("Could not decode image");
            checkInterrupted();
            int orientation=ExifInterface.ORIENTATION_NORMAL;
            try(InputStream input=context.getContentResolver().openInputStream(uri)){if(input!=null)orientation=new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}catch(java.io.IOException ignored){}
            checkInterrupted();
            Matrix matrix=new Matrix();
            switch(orientation){
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
                case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
                case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(-90);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(-90);break;
                default:break;
            }
            if(!matrix.isIdentity()){Bitmap oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(oriented!=bitmap)bitmap.recycle();bitmap=oriented;}
            checkInterrupted();return bitmap;
            }catch(Exception|Error error){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();throw error;}
        });
    }
    /** Publish ownership explicitly: a cancelled Future must never discard an
     * allocated bitmap after decode has finished. */
    private static ListenableFuture<Bitmap> submit(Callable<Bitmap> decoding) {
        SettableFuture<Bitmap> result=SettableFuture.create();
        try {
            IO.purge();
            Future<?> work=IO.submit(()->{
                Bitmap bitmap=null;
                try {
                    checkInterrupted();bitmap=decoding.call();checkInterrupted();
                    if(result.set(bitmap))bitmap=null;
                }catch(Exception|Error error){result.setException(error);}
                finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
            });
            result.addListener(()->{if(result.isCancelled()){work.cancel(true);IO.remove((Runnable)work);}},Runnable::run);
        }catch(RejectedExecutionException busy){result.setException(new IllegalStateException("Image decode queue is full",busy));}
        return result;
    }
    private static void checkInterrupted() throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Image decode was cancelled");
    }
    private BitmapFactory.Options options(BitmapFactory.Options bounds) {
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IllegalArgumentException("Unsupported image format");
        BitmapFactory.Options result=new BitmapFactory.Options();result.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/(long)result.inSampleSize>maxEdge
                ||(long)bounds.outWidth*bounds.outHeight/((long)result.inSampleSize*result.inSampleSize)>MAX_DECODE_PIXELS)result.inSampleSize*=2;
        result.inPreferredConfig=Bitmap.Config.ARGB_8888;result.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);return result;
    }
}
