package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import java.io.File;
import java.util.Locale;

/** Each returned bitmap belongs to the caller and must be recycled immediately. */
interface SketchExportFrameSource extends AutoCloseable {
    Bitmap render(int frame, int width, int height) throws Exception;
    void close();
    interface Factory { SketchExportFrameSource open() throws Exception; }

    /** Consumes the input bitmap; returns a caller-owned, fitted output. */
    static Bitmap fit(Bitmap bitmap,int width,int height) {
        if(width<1||height<1||width>2048||height>2048){bitmap.recycle();throw new IllegalArgumentException("Invalid frame output size");}
        if(bitmap.getWidth()==width&&bitmap.getHeight()==height)return bitmap;
        Bitmap output=null;try{
            output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(output);canvas.drawColor(android.graphics.Color.BLACK);
            float scale=Math.min(width/(float)bitmap.getWidth(),height/(float)bitmap.getHeight()),w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
            canvas.drawBitmap(bitmap,null,new android.graphics.RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));return output;
        }catch(RuntimeException error){if(output!=null)output.recycle();throw error;}finally{bitmap.recycle();}
    }
    static SketchExportFrameSource pngs(File directory, int count) {
        return new SketchExportFrameSource() {
            public Bitmap render(int frame, int width, int height) throws Exception {
                if(frame<0||frame>=count)throw new IllegalArgumentException("Frame outside sequence");
                File png=new File(directory,String.format(Locale.ROOT,"frame_%04d.png",frame));
                if(!png.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())||!png.isFile())
                    throw new IllegalStateException("SKETCH_CODEC_FRAME_MISSING: frame "+frame);
                return SketchFrameStreamEncoder.decodeFrame(png,width,height);
            }
            public void close() {}
        };
    }
}
