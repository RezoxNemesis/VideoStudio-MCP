package com.rezoxnemesis.videostudio;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.transformer.*;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

final class ReliableDecoderFactory implements Codec.DecoderFactory {
    interface Provider {Codec.DecoderFactory create(Format format,MediaCodecSelector selector,DefaultDecoderFactory.Listener listener);}
    private final Provider provider;
    private final Context context;
    private final RenderRetryController.Route route;
    private final int fps;
    private final java.util.concurrent.ConcurrentLinkedQueue<String> names=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Map<String,Format> formats=new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> errors=java.util.concurrent.ConcurrentHashMap.newKeySet();
    ReliableDecoderFactory(Context context,RenderRetryController.Route route,int fps){this(context,route,fps,(format,selector,listener)->new DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).setShouldConfigureOperatingRate(route==RenderRetryController.Route.DEFAULT).setMediaCodecSelector(selector).setListener(listener).build());}
    ReliableDecoderFactory(Context context,RenderRetryController.Route route,int fps,Provider provider){this.context=context.getApplicationContext();this.route=route;this.fps=fps;this.provider=provider;}
    private int fps(Format format){return format.frameRate>0?Math.max(1,Math.round(format.frameRate)):fps;}
    private String profile(Format format){return "source:"+(format.codecs==null?format.sampleMimeType:format.codecs);}
    private Codec.DecoderFactory configured(Format format){
        MediaCodecSelector selector=(mime,secure,tunneled)->{
            List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> codecs=new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneled));
            if(NativeCodecPolicy.softwareDecoder(route,mime))codecs.removeIf(codec->!codec.softwareOnly);
            try(CodecReliabilityStore history=new CodecReliabilityStore(context)){codecs.sort(java.util.Comparator.comparingInt(codec->history.penalty(codec.name,Math.max(1,format.width),Math.max(1,format.height),profile(format),fps(format))));}
            return codecs;
        };
        return provider.create(format,selector,(name,failures)->{names.add(name);formats.put(name,format);for(ExportException failure:failures)record(failure,format);});
    }
    public Codec createForAudioDecoding(Format format,android.media.metrics.LogSessionId session)throws ExportException{try{return configured(format).createForAudioDecoding(format,session);}catch(ExportException error){record(error,format);throw error;}}
    public Codec createForVideoDecoding(Format format,android.view.Surface surface,boolean toneMap,android.media.metrics.LogSessionId session)throws ExportException{try{return configured(format).createForVideoDecoding(format,surface,toneMap,session);}catch(ExportException error){record(error,format);throw error;}}
    private void record(ExportException error,Format format){
        if(error.codecInfo==null||!RenderRetryController.codecFailure(error.errorCode)||!errors.add(error.timestampMs+":"+error.codecInfo.name+":"+error.errorCode))return;
        if(error.codecInfo.name!=null)formats.put(error.codecInfo.name,format);
        try(CodecReliabilityStore history=new CodecReliabilityStore(context)){history.failure(error.codecInfo.name,Math.max(1,format.width),Math.max(1,format.height),profile(format),fps(format),error.getErrorCodeName());}
        catch(Exception unavailable){android.util.Log.w("VideoStudioRender","Could not record input codec failure",unavailable);}
    }
    void record(ExportException error){if(error.codecInfo!=null&&error.codecInfo.name!=null){Format input=formats.get(error.codecInfo.name);if(input!=null)record(error,input);}}
    JSONArray names(){return new JSONArray(names);}
}
