package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import org.json.JSONObject;

/** Worker-owned paint preparation and independently owned immutable UI publications. */
public final class SketchPaintReplay implements AutoCloseable {
    public static final class Viewport {
        public final int maxDimension;
        public final JSONObject crop;
        public Viewport(int maxDimension, JSONObject crop) {
            if(maxDimension<128||maxDimension>1024)throw new IllegalArgumentException("Paint viewport must be 128..1024 pixels");
            this.maxDimension=maxDimension;
            try{if(crop!=null)SketchDocument.validateBounds(crop);this.crop=crop==null?null:SketchDocument.copy(crop);}
            catch(Exception invalid){throw new IllegalArgumentException("Invalid paint viewport crop",invalid);}
        }
    }
    /** The receiver owns this publication until it is detached from the UI. */
    public static final class Frame implements AutoCloseable {
        public final Bitmap bitmap;
        public final JSONObject crop;
        public final double fraction;
        private boolean closed;
        private Frame(Bitmap bitmap,JSONObject crop,double fraction)throws Exception{
            this.bitmap=bitmap;this.crop=crop==null?null:SketchDocument.copy(crop);this.fraction=fraction;
        }
        /** Internal worker previews transfer their completed local-cel buffer on success. */
        public static Frame owned(Bitmap bitmap,JSONObject crop)throws Exception{
            if(bitmap==null||bitmap.isRecycled()||bitmap.getConfig()!=Bitmap.Config.ARGB_8888||
                    bitmap.getWidth()>1024||bitmap.getHeight()>1024)throw SketchDocument.bad("Owned paint publication requires a bounded ARGB bitmap");
            if(crop!=null)SketchDocument.validateBounds(crop);return new Frame(bitmap,crop,1);
        }
        @Override public synchronized void close(){if(!closed){closed=true;bitmap.recycle();}}
    }
    private final SketchPaintStore store;
    private final JSONObject operation;
    private final Object lock=new Object();
    private boolean closed,storeClosed;
    private int rendering;
    public SketchPaintReplay(Context context,String sketchId,JSONObject operation)throws Exception{
        if(!java.util.Arrays.asList("paint_stroke","paint_fill").contains(operation.optString("type")))throw SketchDocument.bad("Paint replay requires a native stroke event");
        this.operation=SketchDocument.copy(operation);store=new SketchPaintStore(context,sketchId);
    }
    public Frame render(double fraction,Viewport viewport)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Paint replay requires a background worker");
        if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw SketchDocument.bad("Paint replay fraction outside 0..1");
        synchronized(lock){if(closed)throw new InterruptedException("Paint replay closed");rendering++;}
        Bitmap bitmap=null;
        try{
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Paint replay cancelled");
            bitmap=store.renderPrefix(operation,fraction,viewport.maxDimension,viewport.crop);
            synchronized(lock){if(closed)throw new InterruptedException("Paint replay closed");}
            JSONObject descriptor=operation.getJSONObject("beforePaint");
            Frame frame=new Frame(bitmap,pixelCoveredCrop(viewport.crop,descriptor.getInt("width"),descriptor.getInt("height")),fraction);bitmap=null;return frame;
        }finally{
            if(bitmap!=null)bitmap.recycle();
            synchronized(lock){rendering--;if(closed&&rendering==0)closeStore();}
        }
    }
    /** Store crops include every intersecting native pixel; publish that exact image extent. */
    public static JSONObject pixelCoveredCrop(JSONObject crop,int w,int h)throws Exception{
        if(crop==null)return null;SketchDocument.validateBounds(crop);if(w<1||h<1||w>2048||h>2048)throw SketchDocument.bad("Paint crop dimensions outside native bounds");
        int left=Math.min(w-1,(int)Math.floor(crop.getDouble("x")*w)),top=Math.min(h-1,(int)Math.floor(crop.getDouble("y")*h));
        int right=Math.min(w,Math.max(left+1,(int)Math.ceil((crop.getDouble("x")+crop.getDouble("width"))*w))),bottom=Math.min(h,Math.max(top+1,(int)Math.ceil((crop.getDouble("y")+crop.getDouble("height"))*h)));
        return new JSONObject().put("x",left/(double)w).put("y",top/(double)h).put("width",(right-left)/(double)w).put("height",(bottom-top)/(double)h);
    }
    private void closeStore(){if(!storeClosed){storeClosed=true;store.close();}}
    @Override public void close(){synchronized(lock){closed=true;if(rendering==0)closeStore();}}
}
