package com.rezoxnemesis.videostudio;

import android.graphics.*;
import java.nio.ByteBuffer;

/** Full native device-grid ink with complementary visible-owner pixels.
 * No getPixels/setPixels unpremultiplication, filtering or source-image scaling. */
final class SketchHandPartClip {
    interface Ink {void draw(Canvas canvas)throws Exception;}
    private SketchHandPartClip(){}
    @SuppressWarnings("deprecation")
    static void draw(Canvas target,Path[] owners,float strokeWidth,boolean digits,Ink ink)throws Exception {
        if(target.isHardwareAccelerated())throw SketchDocument.bad("Hand part passes require the native bitmap renderer");
        int w=target.getWidth(),h=target.getHeight();
        if(w<1||h<1||(long)w*h>2048L*2048)throw SketchDocument.bad("Hand part output exceeds2048 squared pixels");
        cancelled();Matrix matrix=new Matrix();target.getMatrix(matrix);
        Bitmap mask=Bitmap.createBitmap(w,h,Bitmap.Config.ALPHA_8),surface=null;
        try {
            int stride=mask.getRowBytes();byte[][] coverage=new byte[2][];
            Canvas classifier=new Canvas(mask);classifier.setMatrix(matrix);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);paint.setStrokeWidth(strokeWidth);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);
            for(int owner=0;owner<2;owner++){
                cancelled();mask.eraseColor(Color.TRANSPARENT);paint.setStyle(Paint.Style.FILL);classifier.drawPath(owners[owner],paint);
                // Assign the original silhouette's outward stroke too. Internal
                // classifier edges create no artwork or new contour strokes.
                paint.setStyle(Paint.Style.STROKE);classifier.drawPath(owners[owner],paint);
                coverage[owner]=new byte[stride*h];mask.copyPixelsToBuffer(ByteBuffer.wrap(coverage[owner]));
            }
            surface=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);surface.setDensity(Bitmap.DENSITY_NONE);Canvas complete=new Canvas(surface);complete.setMatrix(matrix);ink.draw(complete);
            // Clear whole unselected native pixels in-place. Native CLEAR keeps
            // the surviving premultiplied storage unchanged; no color round trip.
            complete.setMatrix(new Matrix());Paint clear=new Paint();clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            for(int y=0;y<h;y++){
                cancelled();int start=-1;
                for(int x=0;x<=w;x++){
                    boolean front=x<w&&(coverage[1][y*stride+x]&255)>(coverage[0][y*stride+x]&255);
                    boolean remove=x<w&&(digits?!front:front);
                    if(remove&&start<0)start=x;
                    if(!remove&&start>=0){complete.drawRect(start,y,x,y+1,clear);start=-1;}
                }
            }
            cancelled();target.setMatrix(new Matrix());
            try{target.drawBitmap(surface,0,0,null);cancelled();}finally{target.setMatrix(matrix);}
        }finally{if(surface!=null)surface.recycle();mask.recycle();}
    }
    private static void cancelled()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Hand part drawing cancelled");}
}
