package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/**
 * Source-free, bounded pixel checks for one *actual temporal ONNX output*.
 * Reports crude motion evidence only; never claims actor identity, physics,
 * visual fidelity, or that unrelated images form continuous video.
 */
public final class NativeOfflineVideoMotionAudit {
    private NativeOfflineVideoMotionAudit(){}
    static final float MIN_CHANGED_INTERVAL_DELTA=0.0025f;
    public static JSONObject inspect(float[] rgb,int frames,int width,int height)throws Exception {
        if(frames<3||frames>32||width<32||height<32||width>1024||height>1024)
            throw new IllegalArgumentException("Bounded decoded temporal frame dimensions required");
        int pixels=Math.multiplyExact(width,height);
        int frameSize=Math.multiplyExact(pixels,3);
        if(rgb==null||rgb.length!=Math.multiplyExact(frameSize,frames))
            throw new IllegalArgumentException("Temporal frame tensor size differs from model ABI");
        double sum=0,max=0;
        int moving=0,blank=0,thresholdSamples=0;
        // Sample evenly across the frame to avoid allocating bitmaps for
        // temporal validation. No source pixels, paths or prompt is retained.
        int stride=Math.max(1,pixels/256);
        for(int frame=0;frame<frames;frame++){
            int offset=frame*frameSize;
            double totalLuma=0;int lumaSamples=0;
            for(int i=0;i<pixels;i+=stride){
                for(int c=0;c<3;c++){
                    float value=rgb[offset+c*pixels+i];
                    if(!Float.isFinite(value)||value<-1.25f||value>1.25f)
                        throw new IllegalArgumentException("Invalid model video tensor value");
                    totalLuma+=value;
                    lumaSamples++;
                }
            }
            if(Math.abs(totalLuma/lumaSamples+1)<.001)blank++;
            if(frame==0)continue;
            double difference=0;int samples=0;
            int previous=(frame-1)*frameSize;
            for(int i=0;i<pixels;i+=stride){
                for(int c=0;c<3;c++){
                    difference+=Math.abs(rgb[offset+c*pixels+i]-rgb[previous+c*pixels+i]);
                    samples++;
                }
            }
            double delta=difference/samples;
            if(delta>=MIN_CHANGED_INTERVAL_DELTA)moving++;
            sum+=delta;
            max=Math.max(max,delta);
            thresholdSamples++;
        }
        JSONObject report=new JSONObject()
                .put("schema","videostudio.offline-video.motion-evidence.v1")
                .put("decodedFrameCount",frames)
                .put("differentFrameIntervals",moving)
                .put("intervalCount",frames-1)
                .put("meanChannelDelta",sum/(frames-1))
                .put("maximumChannelDelta",max)
                .put("allBlackFrames",blank)
                .put("motionEvidenceOnly",true)
                .put("characterContinuityVerified",false)
                .put("cinematicQualityVerified",false);
        if(moving<2)throw new IllegalStateException(
                "LOCAL_VIDEO_STATIC_OUTPUT: temporal model produced no measurable frame-to-frame motion");
        if(blank>frames/2)throw new IllegalStateException(
                "LOCAL_VIDEO_BLANK_OUTPUT: temporal model produced blank frames");
        return report;
    }
}
