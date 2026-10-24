package com.rezoxnemesis.videostudio;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Independent fractional-ms sample intervals through the new public helper. Real WAV IO. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeAudioEnvelopePcmClockTest {
    @Test public void fractionalSourceOffsetUsesExactPcmFrameClockAndExcludesNeighboringLoudSamples()throws Exception {
        NativeAudioEnvelopeDispatchTest owner=new NativeAudioEnvelopeDispatchTest();ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Object fixture=invoke(owner,"fixture",new Class<?>[]{short[].class,int.class,long.class},new short[8000],1,1000L);
            // At11025Hz [1,26),[26,51),[51,76),[76,101)ms correspond independently
            // to frame ranges[12,287),[287,563),[563,838),[838,1114).
            short[] pcm=new short[2205];Arrays.fill(pcm,(short)30000);int[] edges={12,287,563,838,1114};short[] levels={0,8192,16384,4096};
            for(int i=0;i<4;i++)Arrays.fill(pcm,edges[i],edges[i+1],levels[i]);
            Method wav=PcmWaveformTest.class.getDeclaredMethod("wav",int.class,int.class,short[].class,boolean.class,boolean.class);wav.setAccessible(true);
            File file=(File)field(fixture,"file");Files.write(file.toPath(),(byte[])wav.invoke(null,11025,1,pcm,false,false));
            JSONObject request=(JSONObject)invoke(fixture,"request");ProjectStore store=(ProjectStore)field(fixture,"store");ProjectStore.Project project=store.get(request.getString("projectId"));ProjectStore.Asset asset=project.asset(request.getString("assetId"));asset.sizeBytes=file.length();asset.durationMs=200;store.save(project);
            request.put("expectedUpdatedAt",project.updatedAt).put("expectedSha256",SketchProjectStore.fileHash(file)).put("sourceMs",1).put("durationMs",100);Context context=(Context)field(fixture,"context");
            String before=(String)invoke(fixture,"inventory");JSONObject result=worker.submit(()->NativeAudioEnvelope.analyse(context,request)).get(10,TimeUnit.SECONDS);
            assertTrue(result.getJSONObject("coverage").getBoolean("complete"));assertEquals(1102,result.getJSONObject("coverage").getLong("decodedFrames"));
            JSONArray buckets=result.getJSONArray("buckets");double[] expected={0,.25,.5,.125};long[] counts={275,276,275,276};
            for(int i=0;i<4;i++){JSONObject bucket=buckets.getJSONObject(i);assertEquals(1000+25000*i,bucket.getLong("sourceStartUs"));assertEquals(26000+25000*i,bucket.getLong("sourceEndUs"));assertEquals(counts[i],bucket.getLong("sampleFrames"));assertEquals(expected[i],bucket.getDouble("rms"),0);assertEquals(expected[i],bucket.getDouble("samplePeak"),0);assertEquals(expected[i]/.5,bucket.getDouble("normalizedRms"),0);}
            assertEquals(before,invoke(fixture,"inventory"));assertEquals(request.getString("expectedSha256"),SketchProjectStore.fileHash(file));
        }finally{worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);owner.close();}
    }
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object invoke(Object object,String name)throws Exception{return invoke(object,name,new Class<?>[0]);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...arguments)throws Exception{try{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,arguments);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}}
}
