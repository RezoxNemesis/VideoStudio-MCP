package com.rezoxnemesis.videostudio;

import androidx.media3.transformer.VideoEncoderSettings;
import android.media.MediaCodecInfo;

final class NativeCodecPolicy {
    static VideoEncoderSettings settings(RenderRetryController.Route route,int bitrate,int fps){
        return settings(route,bitrate,fps,1920,1080);
    }
    static VideoEncoderSettings settings(RenderRetryController.Route route,int bitrate,int fps,int width,int height){
        VideoEncoderSettings.Builder settings=new VideoEncoderSettings.Builder().setBitrate(Math.max(500_000,Math.min(50_000_000,bitrate)));
        if(route!=RenderRetryController.Route.DEFAULT)settings.setMaxBFrames(0).setEncoderPerformanceParameters(Math.max(1,fps),1).setEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,avcLevel(width,height,fps,Math.max(500_000,Math.min(50_000_000,bitrate))));
        return settings.build();
    }
    private static int avcLevel(int width,int height,int fps,int bitrate){
        if(width<1||height<1||fps<1)throw new IllegalArgumentException("Invalid final codec dimensions or frame rate");
        long blocks=Math.multiplyExact((width+15L)/16,(height+15L)/16),rate=Math.multiplyExact(blocks,fps);
        // H.264 Annex A baseline limits: MaxFS, MaxMBPS, MaxBR.
        long[][] limits={{3600,108000,14000000,MediaCodecInfo.CodecProfileLevel.AVCLevel31},{5120,216000,20000000,MediaCodecInfo.CodecProfileLevel.AVCLevel32},{8192,245760,20000000,MediaCodecInfo.CodecProfileLevel.AVCLevel4},{8192,245760,50000000,MediaCodecInfo.CodecProfileLevel.AVCLevel41},{8704,522240,50000000,MediaCodecInfo.CodecProfileLevel.AVCLevel42},{22080,589824,135000000,MediaCodecInfo.CodecProfileLevel.AVCLevel5},{36864,983040,240000000,MediaCodecInfo.CodecProfileLevel.AVCLevel51},{36864,2073600,240000000,MediaCodecInfo.CodecProfileLevel.AVCLevel52}};
        for(long[] limit:limits)if(blocks<=limit[0]&&rate<=limit[1]&&bitrate<=limit[2])return (int)limit[3];
        throw new IllegalArgumentException("Requested final format exceeds supported AVC level policy");
    }
    static int[] dimensions(String aspect,String quality){
        int height="720p".equalsIgnoreCase(quality)?("16:9".equals(aspect)?720:1280):("16:9".equals(aspect)?1080:1920);
        if("540p".equalsIgnoreCase(quality))height=540;if("360p".equalsIgnoreCase(quality))height=360;
        float ratio="16:9".equals(aspect)?16f/9f:"1:1".equals(aspect)?1f:"4:5".equals(aspect)?4f/5f:9f/16f;
        return new int[]{Math.round(height*ratio)/2*2,height};
    }
    static boolean softwareDecoder(RenderRetryController.Route route,String mime){return route.ordinal()>=RenderRetryController.Route.SOFTWARE_DECODER.ordinal()&&mime.startsWith("video/");}
    /** Media3's DefaultEncoderFactory uses 30 fps when the composition leaves this unset. */
    static androidx.media3.common.Format encoderRequest(androidx.media3.common.Format format){
        return format.frameRate==androidx.media3.common.Format.NO_VALUE?format.buildUpon().setFrameRate(30).build():format;
    }
    static boolean matchesConfiguredFormat(androidx.media3.common.Format requested,androidx.media3.common.Format actual,int bitrate){
        androidx.media3.common.Format normalized=encoderRequest(requested);
        return actual.width==normalized.width&&actual.height==normalized.height&&Math.round(actual.frameRate)==Math.round(normalized.frameRate)&&actual.bitrate==bitrate;
    }
    static <T> java.util.List<T> selectEncoders(java.util.List<T> codecs,java.util.function.Function<T,android.util.Range<Integer>> range,java.util.function.ToIntFunction<T> penalty,int bitrate){
        java.util.ArrayList<T> selected=new java.util.ArrayList<>();for(T codec:codecs)try{android.util.Range<Integer> supported=range.apply(codec);if(supported!=null&&supported.contains(bitrate))selected.add(codec);}catch(RuntimeException unavailable){}
        selected.sort(java.util.Comparator.comparingInt(penalty));return selected;
    }
}
