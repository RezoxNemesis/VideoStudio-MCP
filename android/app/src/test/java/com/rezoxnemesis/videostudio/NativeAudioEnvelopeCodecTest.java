package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;
import java.io.File;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Synthetic decoded-PCM boundary checks. These test native app analysis logic,
 * not MP3 decoding, codec performance, playback, audition or editing quality.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows={NativeAudioEnvelopeCodecTest.Extractor.class,NativeAudioEnvelopeCodecTest.Codec.class})
public class NativeAudioEnvelopeCodecTest {
    private NativeAudioEnvelopeDispatchTest owner;private Object fixture;private Context context;private File file;private JSONObject request;
    private ExecutorService io;private final AtomicBoolean finalInterrupt=new AtomicBoolean();
    @Before public void setup()throws Exception {
        Codec.reset();Extractor.opened=Extractor.released=0;owner=new NativeAudioEnvelopeDispatchTest();
        fixture=invoke(owner,"fixture",new Class<?>[]{short[].class,int.class,long.class},new short[8000],1,1000L);context=(Context)field(fixture,"context");file=(File)field(fixture,"file");
        Files.write(file.toPath(),new byte[]{73,68,51,1,2,3,5,8});ProjectStore store=(ProjectStore)field(fixture,"store");JSONObject original=(JSONObject)invoke(fixture,"request");
        ProjectStore.Project project=store.get(original.getString("projectId"));ProjectStore.Asset asset=project.asset(original.getString("assetId"));asset.mime="audio/mpeg";asset.sizeBytes=file.length();store.save(project);
        request=original.put("expectedUpdatedAt",project.updatedAt).put("expectedSha256",SketchProjectStore.fileHash(file));io=Executors.newSingleThreadExecutor();
    }
    @After public void close()throws Exception {if(io!=null){io.shutdownNow();io.awaitTermination(5,TimeUnit.SECONDS);}if(owner!=null)owner.close();}

    @Test public void codecUsesAllChannelsAndBufferInfoOffsetWithDistinctCompleteCoverage()throws Exception {
        for(int i=0;i<4;i++)Codec.packets.add(packet(i*250000L,2000,(short)0,(short)16384,i==3));
        String before=(String)invoke(fixture,"inventory");JSONObject result=analyse();assertTrue(result.getJSONObject("coverage").getBoolean("complete"));
        assertEquals("android_media_codec",result.getString("decoderRoute"));assertEquals("codec_pts_nearest_source_sample_frame_v1",result.getString("timestampMapping"));assertEquals("synthetic.fixture.pcm16",result.getString("codecName"));
        JSONArray buckets=result.getJSONArray("buckets");for(int i=0;i<4;i++){JSONObject bucket=buckets.getJSONObject(i);assertEquals(2000,bucket.getLong("sampleFrames"));assertEquals(4000,bucket.getLong("channelSamples"));assertEquals(Math.sqrt(.125),bucket.getDouble("rms"),1e-8);assertEquals(.5,bucket.getDouble("samplePeak"),0);}
        assertEquals(AudioFormat.ENCODING_PCM_16BIT,Codec.requestedEncoding);assertEquals(4,Codec.outputReleased);closed();assertEquals(before,invoke(fixture,"inventory"));
    }
    @Test public void earlyEosKeepsMeasuredBucketsButMissingBucketsAreNullAndIncomplete()throws Exception {
        Codec.packets.add(packet(0,2000,(short)0,(short)16384,false));Codec.packets.add(packet(500000,2000,(short)8192,(short)8192,true));
        JSONObject result=analyse();assertEquals("partial",result.getString("status"));JSONObject coverage=result.getJSONObject("coverage");assertFalse(coverage.getBoolean("complete"));assertEquals(4000,coverage.getLong("decodedFrames"));assertEquals(2,coverage.getInt("missingBuckets"));
        JSONArray buckets=result.getJSONArray("buckets");for(int i:new int[]{1,3}){JSONObject missing=buckets.getJSONObject(i);assertEquals(0,missing.getLong("sampleFrames"));assertTrue(missing.isNull("rms"));assertTrue(missing.isNull("samplePeak"));assertTrue(missing.isNull("normalizedRms"));assertFalse(missing.getJSONObject("coverage").getBoolean("complete"));}
        assertEquals(2,Codec.outputReleased);closed();
    }
    @Test public void duplicateDecodedFramesCannotHideAGapOrInflateEnergyCounts()throws Exception {
        Codec.packets.add(packet(0,2000,(short)1000,(short)1000,false));Codec.packets.add(packet(0,2000,(short)1000,(short)1000,false));
        Codec.packets.add(packet(500000,2000,(short)1000,(short)1000,false));Codec.packets.add(packet(750000,2000,(short)1000,(short)1000,true));
        JSONObject result=analyse();JSONObject coverage=result.getJSONObject("coverage");assertFalse(coverage.getBoolean("complete"));assertEquals(6000,coverage.getLong("decodedFrames"));assertEquals(2000,coverage.getLong("duplicateFrames"));assertEquals(2000,coverage.getLong("missingFrames"));assertTrue(result.getJSONArray("buckets").getJSONObject(1).isNull("rms"));closed();
    }
    @Test public void unsupportedOutputEncodingFailsExplicitlyAndClosesEveryDecoderHandle()throws Exception {
        Codec.encoding=AudioFormat.ENCODING_PCM_FLOAT;Codec.packets.add(packet(0,8000,(short)0,(short)0,true));Throwable failure=failed();
        assertEquals("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT",NativeAudioEnvelope.code(failure));assertEquals(0,Codec.outputReleased);closed();
    }
    @Test public void invalidOutputBoundsReleaseTheCurrentBufferAndFailInsteadOfSilence()throws Exception {
        Packet bad=packet(0,2000,(short)2000,(short)2000,true);bad.declaredSize=bad.bytes.capacity()+8;Codec.packets.add(bad);
        assertEquals("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT",NativeAudioEnvelope.code(failed()));assertEquals(1,Codec.outputReleased);closed();
    }
    @Test public void interruptAfterReturningCurrentBufferRetainsFlagAndReleasesIt()throws Exception {
        Codec.packets.add(packet(0,2000,(short)2000,(short)2000,true));Codec.interruptOnBuffer=true;
        assertTrue(failed() instanceof InterruptedException);assertTrue(finalInterrupt.get());assertEquals(1,Codec.outputReleased);closed();
    }
    @Test public void sourceChangedDuringDecodeIsRejectedAndNeverRepairedOverThoseBytes()throws Exception {
        Codec.packets.add(packet(0,8000,(short)2000,(short)2000,true));byte[] changed={9,7,5,3,1};Codec.afterRelease=()->{try{Files.write(file.toPath(),changed);}catch(Exception error){throw new RuntimeException(error);}};
        assertEquals("AUDIO_ENVELOPE_SOURCE_CHANGED",NativeAudioEnvelope.code(failed()));assertArrayEquals(changed,Files.readAllBytes(file.toPath()));closed();
    }
    @Test public void alreadyInterruptedWorkerOpensNoExtractorOrCodec()throws Exception {
        ExecutionException failure=assertThrows(ExecutionException.class,()->io.submit(()->{Thread.currentThread().interrupt();try{return NativeAudioEnvelope.analyse(context,request);}finally{finalInterrupt.set(Thread.currentThread().isInterrupted());}}).get(10,TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof InterruptedException);assertTrue(finalInterrupt.get());assertEquals(0,Extractor.opened);assertEquals(0,Codec.opened);
    }

    private JSONObject analyse()throws Exception{return io.submit(()->{try{return NativeAudioEnvelope.analyse(context,request);}finally{finalInterrupt.set(Thread.currentThread().isInterrupted());}}).get(10,TimeUnit.SECONDS);}
    private Throwable failed()throws Exception{return assertThrows(ExecutionException.class,this::analyse).getCause();}
    private static void closed(){assertEquals(1,Extractor.opened);assertEquals(1,Extractor.released);assertEquals(1,Codec.opened);assertEquals(1,Codec.stopped);assertEquals(1,Codec.released);}
    private static Packet packet(long time,int frames,short left,short right,boolean eos){ByteBuffer data=ByteBuffer.allocate(12+frames*4).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<6;i++)data.putShort((short)32767);for(int i=0;i<frames;i++)data.putShort(left).putShort(right);return new Packet(time,data,12,frames*4,eos);}
    private static final class Packet {final long time;final ByteBuffer bytes;final int offset;int declaredSize;final boolean eos;Packet(long time,ByteBuffer bytes,int offset,int size,boolean eos){this.time=time;this.bytes=bytes;this.offset=offset;declaredSize=size;this.eos=eos;}}
    private static MediaFormat format(int encoding){MediaFormat format=MediaFormat.createAudioFormat("audio/raw",8000,2);format.setInteger(MediaFormat.KEY_PCM_ENCODING,encoding);return format;}

    @Implements(MediaExtractor.class)
    public static class Extractor {
        static int opened,released;private boolean advanced;
        @Implementation protected void __constructor__(){opened++;}
        @Implementation protected void setDataSource(FileDescriptor file,long offset,long length){}
        @Implementation protected int getTrackCount(){return 1;}
        @Implementation protected MediaFormat getTrackFormat(int index){return MediaFormat.createAudioFormat("audio/mpeg",8000,2);}
        @Implementation protected void selectTrack(int index){}
        @Implementation protected void seekTo(long time,int mode){}
        @Implementation protected long getSampleTime(){return advanced?-1:0;}
        @Implementation protected int readSampleData(ByteBuffer input,int offset){input.putInt(offset,1);return 4;}
        @Implementation protected boolean advance(){advanced=true;return false;}
        @Implementation protected void release(){released++;}
    }
    @Implements(MediaCodec.class)
    public static class Codec {
        static final List<Packet> packets=new ArrayList<>();static int encoding,opened,stopped,released,outputReleased,requestedEncoding;static boolean interruptOnBuffer;static Runnable afterRelease;
        private boolean formatted;private int cursor;
        static void reset(){packets.clear();encoding=AudioFormat.ENCODING_PCM_16BIT;opened=stopped=released=outputReleased=requestedEncoding=0;interruptOnBuffer=false;afterRelease=null;}
        @Implementation protected void __constructor__(String name,boolean nameIsType,boolean encoder){opened++;}
        @Implementation protected void configure(MediaFormat format,Surface surface,MediaCrypto crypto,int flags){requestedEncoding=format.getInteger(MediaFormat.KEY_PCM_ENCODING);}
        @Implementation protected void start(){}
        @Implementation protected String getName(){return "synthetic.fixture.pcm16";}
        @Implementation protected int dequeueInputBuffer(long timeout){return 0;}
        @Implementation protected ByteBuffer getInputBuffer(int index){return ByteBuffer.allocate(256);}
        @Implementation protected void queueInputBuffer(int index,int offset,int size,long time,int flags){}
        @Implementation protected int dequeueOutputBuffer(MediaCodec.BufferInfo info,long timeout){if(!formatted){formatted=true;return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;}if(cursor>=packets.size())return MediaCodec.INFO_TRY_AGAIN_LATER;Packet next=packets.get(cursor);info.set(next.offset,next.declaredSize,next.time,next.eos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);return cursor++;}
        @Implementation protected MediaFormat getOutputFormat(){return format(encoding);}
        @Implementation protected ByteBuffer getOutputBuffer(int index){if(interruptOnBuffer)Thread.currentThread().interrupt();ByteBuffer output=packets.get(index).bytes.duplicate();output.clear();return output;}
        @Implementation protected void releaseOutputBuffer(int index,boolean render){outputReleased++;if(afterRelease!=null){Runnable action=afterRelease;afterRelease=null;action.run();}}
        @Implementation protected void stop(){stopped++;}
        @Implementation protected void release(){released++;}
    }
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object invoke(Object object,String name)throws Exception{return invoke(object,name,new Class<?>[0]);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...arguments)throws Exception {try{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,arguments);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}}
}
