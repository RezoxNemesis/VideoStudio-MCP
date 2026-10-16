package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Prevent starting several ML Kit models and creating 5+ full-size portrait
 * planes when Android is already close to killing the app for memory pressure.
 * No source image is deleted, downsampled on disk or changed in ProjectStore.
 */
final class PortraitMemoryAdmission {
    private static final long MIB=1024L*1024L;
    private PortraitMemoryAdmission(){}

    static int safeAnalysisEdge(long javaHeapLimitBytes,long availableSystemBytes,
                                boolean lowSystemMemory){
        if(lowSystemMemory||availableSystemBytes<160*MIB)
            throw new IllegalStateException("PORTRAIT_LOW_MEMORY: close other apps or wait for more available RAM before generating native layers");
        if(javaHeapLimitBytes<112*MIB)
            throw new IllegalStateException("PORTRAIT_LOW_HEAP: source preserved; this device cannot safely run bundled portrait analysis now");
        if(javaHeapLimitBytes<256*MIB||availableSystemBytes<512*MIB)return 768;
        if(javaHeapLimitBytes<384*MIB||availableSystemBytes<900*MIB)return 960;
        return 1200;
    }

    static int edgeFor(Context context){
        ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if(am==null)return 960;
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return safeAnalysisEdge(Runtime.getRuntime().maxMemory(),info.availMem,info.lowMemory);
    }

    static void recycle(android.graphics.Bitmap... bitmaps){
        if(bitmaps==null)return;
        for(android.graphics.Bitmap bitmap:bitmaps){
            if(bitmap!=null&&!bitmap.isRecycled()){
                try{bitmap.recycle();}catch(RuntimeException ignored){}
            }
        }
    }
}
