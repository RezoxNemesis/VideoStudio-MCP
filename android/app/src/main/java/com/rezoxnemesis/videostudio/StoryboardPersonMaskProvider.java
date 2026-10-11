package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Real bundled on-device ML Kit foreground segmentation for PERSON shots.
 *
 * Unlike generic motion-difference masks, this can isolate stationary
 * clothing/body pixels and preserve figure texture while a human moves.
 * The bundled selfie model is trained for photographs, so it can fail on
 * comic/anime/stickman drawings. Failure is reported; a dubious matte is
 * NEVER treated as a successful source-artwork animation.
 */
public final class StoryboardPersonMaskProvider {
    public static final class Result {
        public final StoryboardLayerIsolation.Result layers;
        public final float personConfidence;
        public final String provider;
        Result(StoryboardLayerIsolation.Result layers,float confidence){
            this.layers=layers;personConfidence=confidence;
            provider="bundled-mlkit-person-segmentation";
        }
    }
    private static final int MAX_PIXELS=720*1280;
    private StoryboardPersonMaskProvider(){}

    public static Result isolate(int[] a,int[] b,int width,int height)
            throws Exception {
        if(a==null||b==null||width<64||height<64
                ||(long)width*height>MAX_PIXELS||
                a.length!=width*height||b.length!=a.length)
            throw new IllegalArgumentException("Two bounded imported portraits required");
        Bitmap first=Bitmap.createBitmap(a,width,height,Bitmap.Config.ARGB_8888);
        Bitmap last=Bitmap.createBitmap(b,width,height,Bitmap.Config.ARGB_8888);
        Segmenter segmenter=Segmentation.getClient(
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build());
        try {
            Mask before=segment(segmenter,first,width,height);
            Mask after=segment(segmenter,last,width,height);
            float confidence=Math.min(before.confidence,after.confidence);
            if(confidence<.57f)
                throw new IllegalArgumentException(
                        "Bundled person model cannot see this illustrated figure reliably. "
                       +"Use manual masks or an appropriate optional model pack.");
            StoryboardLayerIsolation.Result scene=StoryboardLayerIsolation.fromMasks(
                    a,b,width,height,Collections.singletonList(before.pixels),
                    Collections.singletonList(after.pixels),
                    Math.max(.42f,Math.min(.96f,confidence)));
            if(!scene.autoRenderable())
                throw new IllegalArgumentException(
                        "Person segmentation cannot reconstruct this shot: "+scene.reason);
            return new Result(scene,confidence);
        }finally {
            try{segmenter.close();}catch(Exception ignored){}
            first.recycle();last.recycle();
        }
    }
    private static final class Mask {
        final boolean[] pixels;
        final float confidence;
        Mask(boolean[] pixels,float confidence){
            this.pixels=pixels;this.confidence=confidence;
        }
    }
    private static Mask segment(Segmenter model,Bitmap source,int width,int height)
            throws Exception {
        SegmentationMask predicted=Tasks.await(
                model.process(InputImage.fromBitmap(source,0)),
                25,TimeUnit.SECONDS);
        int mw=predicted.getWidth(),mh=predicted.getHeight();
        if(mw<8||mh<8||mw*mh>MAX_PIXELS)
            throw new IllegalStateException("Person model returned invalid matte");
        ByteBuffer confidence=predicted.getBuffer().duplicate()
                .order(ByteOrder.nativeOrder());
        confidence.rewind();
        if(confidence.remaining()<mw*mh*4)
            throw new IllegalStateException("Truncated person segmentation output");
        float[] probabilities=new float[mw*mh];
        for(int i=0;i<probabilities.length;i++){
            float v=confidence.getFloat();
            probabilities[i]=Float.isFinite(v)?Math.max(0,Math.min(1,v)):0;
        }
        boolean[] opaque=new boolean[width*height];
        float support=0;int area=0;
        for(int y=0;y<height;y++){
            int my=Math.min(mh-1,(int)((y+.5f)*mh/height));
            for(int x=0;x<width;x++){
                int mx=Math.min(mw-1,(int)((x+.5f)*mw/width));
                float value=probabilities[my*mw+mx];
                int index=y*width+x;
                if(value>.62f) {
                    opaque[index]=true;
                    area++;
                    support+=value;
                }
            }
        }
        float fraction=area/(float)opaque.length;
        if(fraction<.012f||fraction>.69f)
            throw new IllegalArgumentException(
                    "Detected human mask has implausible coverage for this storyboard");
        // Small matte defects get filled without inventing new outside art.
        boolean[] filled=opaque.clone();
        for(int y=1;y<height-1;y++)for(int x=1;x<width-1;x++){
            int i=y*width+x;
            if(opaque[i])continue;
            int near=0;
            for(int yy=-1;yy<=1;yy++)for(int xx=-1;xx<=1;xx++)
                if(opaque[i+yy*width+xx])near++;
            if(near>=7)filled[i]=true;
        }
        float average=support/Math.max(1,area);
        float coveragePenalty=Math.min(1f,fraction/.045f)
                *Math.min(1f,(1-fraction)/.40f);
        return new Mask(filled,Math.min(.96f,average*coveragePenalty));
    }
}
