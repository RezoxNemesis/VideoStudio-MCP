package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit native filtered clone sampling; omitted requests retain the legacy sampler. */
final class SketchCloneSourceSampling {
    static final int MAX_TARGET_PIXELS=1048576;
    private SketchCloneSourceSampling() {}

    static String choice(JSONObject source)throws Exception {
        Object value=source.get("sampling");
        if(!(value instanceof String)||!("nearest".equals(value)||"bilinear".equals(value)))
            throw SketchDocument.bad("Clone sampling must be nearest or bilinear");
        return (String)value;
    }
    static boolean filtered(JSONObject source)throws Exception {
        return source.has("sampling")&&"bilinear".equals(choice(source));
    }
    static void requireTarget(int width,int height)throws Exception {
        if(width<1||height<1||(long)width*height>MAX_TARGET_PIXELS)
            throw SketchDocument.bad("Filtered clone sampling requires at most1048576 native target pixels; no downsampling");
    }
    static Rect sourceRect(JSONObject guide,int width,int height)throws Exception {
        return guide.has("referenceCrop")?SketchFrameRenderer.referencePixels(guide.getJSONObject("referenceCrop"),width,height):new Rect(0,0,width,height);
    }
    static JSONArray declaration(Rect rect){return new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom);}
    static Rect validatedRect(JSONObject pin)throws Exception {
        JSONArray a=pin.getJSONArray("sourceRect");if(a.length()!=4)throw SketchDocument.bad("Filtered clone sourceRect requires four integers");
        int w=pin.getInt("width"),h=pin.getInt("height");
        int l=(int)SketchDocument.scalar(a.get(0),0,w-1,"sourceRect left"),t=(int)SketchDocument.scalar(a.get(1),0,h-1,"sourceRect top");
        int r=(int)SketchDocument.scalar(a.get(2),1,w,"sourceRect right"),b=(int)SketchDocument.scalar(a.get(3),1,h,"sourceRect bottom");
        if(((Number)a.get(0)).doubleValue()!=l||((Number)a.get(1)).doubleValue()!=t||((Number)a.get(2)).doubleValue()!=r||((Number)a.get(3)).doubleValue()!=b||r<=l||b<=t)
            throw SketchDocument.bad("Filtered clone sourceRect must be a nonempty integer rectangle");
        return new Rect(l,t,r,b);
    }
    static int[] raster(Bitmap source,JSONObject pin,int width,int height)throws Exception {
        requireTarget(width,height);cancelled();Rect crop=validatedRect(pin);
        JSONArray a=pin.getJSONArray("mapping");double sw=source.getWidth(),sh=source.getHeight();
        Matrix paintToSource=new Matrix();paintToSource.setValues(new float[]{
            (float)(sw*a.getDouble(0)/width),(float)(sw*a.getDouble(2)/height),(float)(sw*a.getDouble(4)),
            (float)(sh*a.getDouble(1)/width),(float)(sh*a.getDouble(3)/height),(float)(sh*a.getDouble(5)),0,0,1});
        Matrix sourceToPaint=new Matrix();if(!paintToSource.invert(sourceToPaint))throw SketchDocument.bad("Filtered clone mapping cannot be inverted");
        Bitmap rendered=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas=new Canvas(rendered);canvas.concat(sourceToPaint);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(source,crop,new RectF(crop),paint);cancelled();
            int[] pixels=new int[width*height];
            for(int row=0;row<height;row+=256){cancelled();int count=Math.min(256,height-row);rendered.getPixels(pixels,row*width,width,0,row,width,count);}
            return pixels;
        } finally {rendered.recycle();}
    }
    private static void cancelled()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Paint cancelled");}
    static JSONObject capabilities()throws Exception {
        return new JSONObject().put("minimumNativeVersion","4.10.14").put("operation","paint_stroke").put("field","cloneSource.sampling")
            .put("choices",new JSONArray(new String[]{"nearest","bilinear"})).put("omittedBehavior","nearest").put("explicitNearestRequiresUpgrade",true)
            .put("sourceMaxAxis",2048).put("targetMaxPixels",MAX_TARGET_PIXELS).put("manualControls","Sketch Studio > Paint studio > Paint settings > Clone sampling");
    }
}
