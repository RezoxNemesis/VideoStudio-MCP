package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Genuine source-sampled audio waveforms, decoded from explicitly imported
 * VideoStudio audio assets only. Never scans the Gallery or runs on the UI
 * thread. Work is bounded to at most 30 seconds of the selected source.
 */
public final class StudioAudioWaveforms {
    public interface Callback {void ready(String key,float[] peaks);}
    public static final int MAX_BUCKETS=96;
    private static final long MAX_WINDOW_US=30_000_000L;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService thread=Executors.newSingleThreadExecutor();
    private final Map<String,float[]> cache=
            new LinkedHashMap<String,float[]>(24,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,float[]> value){
            return size()>20;
        }
    };
    public StudioAudioWaveforms(Context context){
        this.context=context.getApplicationContext();
    }
    public static String key(ProjectStore.Asset asset,StudioLayer layer){
        return asset.id+":"+layer.sourceInMs+":"+layer.durationMs;
    }
    public void request(ProjectStore.Asset asset,StudioLayer layer,Callback callback){
        if(asset==null||layer==null||callback==null||
                asset.mime==null||!asset.mime.startsWith("audio/"))return;
        final String key=key(asset,layer);
        synchronized(cache){
            float[] result=cache.get(key);
            if(result!=null){callback.ready(key,result.clone());return;}
        }
        thread.execute(()->{
            float[] waveform=new float[MAX_BUCKETS];
            try{
                waveform=decode(Uri.parse(asset.uri),layer.sourceInMs,layer.durationMs,
                        MAX_BUCKETS);
            }catch(Exception ignored){
                // A decoder failure must never stop playback or editing.
            }
            final float[] result=waveform;
            synchronized(cache){cache.put(key,result);}
            main.post(()->callback.ready(key,result.clone()));
        });
    }

    static float[] normalize(double[] sum,int[] samples) {
        if(sum==null||samples==null||sum.length!=samples.length)
            throw new IllegalArgumentException("Invalid waveform buckets");
        float[] out=new float[sum.length];
        float maximum=0;
        for(int i=0;i<out.length;i++){
            out[i]=samples[i]<=0?0:
                    (float)Math.sqrt(sum[i]/Math.max(1,samples[i]));
            maximum=Math.max(maximum,out[i]);
        }
        if(maximum>0)for(int i=0;i<out.length;i++)
            out[i]=Math.min(1,out[i]/maximum);
        return out;
    }

    private float[] decode(Uri uri,long sourceMs,long durationMs,int buckets) throws Exception{
        if(uri==null||sourceMs<0||durationMs<100||buckets<1||buckets>MAX_BUCKETS)
            throw new IllegalArgumentException("Invalid audio waveform source");
        long from=sourceMs*1000L;
        long visible=Math.min(durationMs*1000L,MAX_WINDOW_US);
        long end=from+visible;
        double[] power=new double[buckets];
        int[] counts=new int[buckets];
        MediaExtractor extractor=new MediaExtractor();
        MediaCodec codec=null;
        try{
            extractor.setDataSource(context,uri,null);
            int track=-1;
            MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat next=extractor.getTrackFormat(i);
                String type=next.getString(MediaFormat.KEY_MIME);
                if(type!=null&&type.startsWith("audio/")){
                    track=i;format=next;break;
                }
            }
            if(track<0||format==null)return new float[buckets];
            String type=format.getString(MediaFormat.KEY_MIME);
            if(type==null)return new float[buckets];
            extractor.selectTrack(track);
            extractor.seekTo(from,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec=MediaCodec.createDecoderByType(type);
            codec.configure(format,null,null,0);
            codec.start();
            int sampleRate=format.containsKey(MediaFormat.KEY_SAMPLE_RATE)?
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int channels=format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
            sampleRate=Math.max(1,sampleRate);
            channels=Math.max(1,channels);
            int pcmType=AudioFormat.ENCODING_PCM_16BIT;
            boolean inputDone=false,outputDone=false;
            int quietCycles=0;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!outputDone&&quietCycles<2200&&!Thread.currentThread().isInterrupted()){
                if(!inputDone){
                    int slot=codec.dequeueInputBuffer(2000);
                    if(slot>=0){
                        ByteBuffer input=codec.getInputBuffer(slot);
                        if(input==null)throw new IllegalStateException("Audio decoder input missing");
                        input.clear();
                        int size=extractor.readSampleData(input,0);
                        long time=extractor.getSampleTime();
                        if(size<0||time>end+250_000L){
                            codec.queueInputBuffer(slot,0,0,
                                    Math.max(0,time),MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone=true;
                        }else{
                            codec.queueInputBuffer(slot,0,size,time,0);
                            extractor.advance();
                        }
                    }
                }
                int index=codec.dequeueOutputBuffer(info,2000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat decoded=codec.getOutputFormat();
                    if(decoded.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        sampleRate=Math.max(1,decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                    if(decoded.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels=Math.max(1,decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    if(decoded.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        pcmType=decoded.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    quietCycles=0;
                }else if(index>=0){
                    quietCycles=0;
                    ByteBuffer data=codec.getOutputBuffer(index);
                    if(data!=null&&info.size>0&&
                            (pcmType==AudioFormat.ENCODING_PCM_16BIT||
                             pcmType==AudioFormat.ENCODING_PCM_FLOAT)){
                        data.order(ByteOrder.LITTLE_ENDIAN);
                        int bytes=pcmType==AudioFormat.ENCODING_PCM_FLOAT?4:2;
                        int frames=info.size/Math.max(1,bytes*channels);
                        int stride=Math.max(1,frames/4096);
                        int startOffset=info.offset;
                        for(int frame=0;frame<frames;frame+=stride){
                            long timestamp=info.presentationTimeUs+
                                    frame*1_000_000L/sampleRate;
                            if(timestamp<from||timestamp>=end)continue;
                            long position=timestamp-from;
                            int bucket=(int)Math.min(buckets-1,
                                    position*buckets/Math.max(1,visible));
                            int readAt=startOffset+frame*channels*bytes;
                            if(readAt+bytes>data.limit())break;
                            float sample;
                            if(bytes==4)sample=data.getFloat(readAt);
                            else sample=data.getShort(readAt)/32768f;
                            if(!Float.isFinite(sample))sample=0;
                            sample=Math.max(-1,Math.min(1,sample));
                            power[bucket]+=sample*sample;
                            counts[bucket]++;
                        }
                    }
                    outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0||
                            info.presentationTimeUs>=end;
                    codec.releaseOutputBuffer(index,false);
                }else{
                    quietCycles++;
                }
            }
        }finally{
            if(codec!=null){
                try{codec.stop();}catch(Exception ignored){}
                try{codec.release();}catch(Exception ignored){}
            }
            try{extractor.release();}catch(Exception ignored){}
        }
        return normalize(power,counts);
    }
    public void shutdown(){thread.shutdownNow();}
}
