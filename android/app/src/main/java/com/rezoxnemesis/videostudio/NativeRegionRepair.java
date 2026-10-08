package com.rezoxnemesis.videostudio;
import android.graphics.*;

/** Explicit rectangular neural replacement. Pixels outside the requested region are preserved. */
public final class NativeRegionRepair {
    public static Bitmap compose(Bitmap reference,Bitmap generated,double left,double top,double right,double bottom) {
        if(!Double.isFinite(left+top+right+bottom)||left<0||top<0||right>1||bottom>1||right<=left||bottom<=top) throw new IllegalArgumentException("Repair region requires ordered normalized bounds within 0..1");
        Rect scope=new Rect((int)Math.floor(left*reference.getWidth()),(int)Math.floor(top*reference.getHeight()),(int)Math.ceil(right*reference.getWidth()),(int)Math.ceil(bottom*reference.getHeight()));
        Bitmap result=reference.copy(Bitmap.Config.ARGB_8888,true);Canvas canvas=new Canvas(result);canvas.save();canvas.clipRect(scope);
        canvas.drawBitmap(generated,null,scope,new Paint(Paint.FILTER_BITMAP_FLAG));canvas.restore();return result;
    }
}
