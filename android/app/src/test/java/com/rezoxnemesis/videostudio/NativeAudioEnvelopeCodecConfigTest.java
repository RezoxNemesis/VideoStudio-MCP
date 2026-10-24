package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.media.MediaCodec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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

/** A codec-config buffer is metadata, never decoded PCM. Synthetic boundary only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows={NativeAudioEnvelopeCodecTest.Extractor.class,NativeAudioEnvelopeCodecConfigTest.ConfigCodec.class})
public class NativeAudioEnvelopeCodecConfigTest {
    private NativeAudioEnvelopeCodecTest fixture;
    @Before public void setup()throws Exception{fixture=new NativeAudioEnvelopeCodecTest();fixture.setup();}
    @After public void close()throws Exception{if(fixture!=null)fixture.close();}
    @Test public void codecConfigurationBytesCannotPoisonTheFirstMeasuredBucketOrMaskItsRealSamples()throws Exception {
        addPacket(0,2000,(short)32767,(short)32767,false); // explicitly CODEC_CONFIG metadata
        for(int i=0;i<4;i++)addPacket(i*250000L,2000,(short)8192,(short)8192,i==3);
        Context context=(Context)field(fixture,"context");JSONObject request=(JSONObject)field(fixture,"request");ExecutorService io=(ExecutorService)field(fixture,"io");
        JSONObject result=io.submit(()->NativeAudioEnvelope.analyse(context,request)).get(10,TimeUnit.SECONDS);
        assertTrue(result.getJSONObject("coverage").getBoolean("complete"));assertEquals(8000,result.getJSONObject("coverage").getLong("decodedFrames"));assertEquals(0,result.getJSONObject("coverage").getLong("duplicateFrames"));
        JSONArray buckets=result.getJSONArray("buckets");for(int i=0;i<4;i++){JSONObject bucket=buckets.getJSONObject(i);assertEquals("Codec initialization bytes must never count as source amplitude",.25,bucket.getDouble("rms"),0);assertEquals(.25,bucket.getDouble("samplePeak"),0);assertEquals(2000,bucket.getLong("sampleFrames"));}
        assertEquals("Every config/media buffer still belongs to the codec until released",5,NativeAudioEnvelopeCodecTest.Codec.outputReleased);
        assertEquals(1,NativeAudioEnvelopeCodecTest.Codec.stopped);assertEquals(1,NativeAudioEnvelopeCodecTest.Codec.released);assertEquals(1,NativeAudioEnvelopeCodecTest.Extractor.released);
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static void addPacket(long time,int frames,short left,short right,boolean eos)throws Exception {
        Method packet=NativeAudioEnvelopeCodecTest.class.getDeclaredMethod("packet",long.class,int.class,short.class,short.class,boolean.class);packet.setAccessible(true);
        ((List)NativeAudioEnvelopeCodecTest.Codec.packets).add(packet.invoke(null,time,frames,left,right,eos));
    }
    @Implements(MediaCodec.class) public static class ConfigCodec extends NativeAudioEnvelopeCodecTest.Codec {
        @Implementation @Override protected int dequeueOutputBuffer(MediaCodec.BufferInfo info,long timeout){int index=super.dequeueOutputBuffer(info,timeout);if(index==0)info.flags|=MediaCodec.BUFFER_FLAG_CODEC_CONFIG;return index;}
    }
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
}
