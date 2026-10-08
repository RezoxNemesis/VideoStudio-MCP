package com.rezoxnemesis.videostudio;
import android.graphics.*;import androidx.media3.effect.CanvasOverlay;import androidx.media3.common.VideoFrameProcessingException;import java.io.*;
/** Rasterize generated frames at their native timestamps with one cached decoded frame. */
final class GeneratedFrameOverlay extends CanvasOverlay {
    private final File folder;private final int count,fps;private Bitmap image;private int current=-1;
    GeneratedFrameOverlay(File folder,int count,int fps){super(true);this.folder=folder;this.count=count;this.fps=fps;}
    @Override public void onDraw(Canvas canvas,long us){int index=Math.min(count-1,Math.max(0,(int)(us*fps/1000000)));if(index!=current){Bitmap next=BitmapFactory.decodeFile(new File(folder,String.format(java.util.Locale.US,"frame_%05d.png",index)).getAbsolutePath());if(next==null)throw new IllegalStateException("Generated frame missing: "+index);if(image!=null)image.recycle();image=next;current=index;}canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);canvas.drawBitmap(image,null,new Rect(0,0,canvas.getWidth(),canvas.getHeight()),null);}
    @Override public void release()throws VideoFrameProcessingException{if(image!=null){image.recycle();image=null;}super.release();}
}
