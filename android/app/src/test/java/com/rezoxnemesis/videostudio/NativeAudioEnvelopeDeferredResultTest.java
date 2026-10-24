package com.rezoxnemesis.videostudio;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Persisted job receipt/source binding at the existing deferred completion path.
 * Real native PCM analysis supplies the valid receipt; no media playback claim.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeAudioEnvelopeDeferredResultTest {
    private NativeAudioEnvelopeDispatchTest owner;private Object fixture;private ControlService service;
    private JSONObject request,result,queued;private ExecutorService worker;
    @Before public void setup()throws Exception {
        owner=new NativeAudioEnvelopeDispatchTest();fixture=invoke(owner,"fixture",new Class<?>[]{short[].class,int.class,long.class},new short[8000],1,1000L);
        service=(ControlService)field(fixture,"service");Context context=(Context)field(fixture,"context");request=(JSONObject)invoke(fixture,"request");worker=Executors.newSingleThreadExecutor();
        result=worker.submit(()->NativeAudioEnvelope.analyse(context,request)).get(10,TimeUnit.SECONDS);
        queued=new JSONObject().put("ok",true).put("queued",true).put("jobId","original-audio-job").put("projectId",request.getString("projectId")).put("mode","audio_envelope").put("readOnly",true).put("audioRequest",request);
    }
    @After public void close()throws Exception {if(worker!=null){worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}if(owner!=null)owner.close();}
    @Test public void restoredCompletedAudioJobDeliversTheOriginalBoundFlatReceiptWithoutDuplicatingItsBuckets()throws Exception {
        String before=(String)invoke(fixture,"inventory");JSONObject completed=delivered(result);
        assertTrue(completed.toString(),completed.getBoolean("ok"));assertTrue(completed.getBoolean("completed"));assertFalse(completed.getBoolean("queued"));
        assertEquals("original-audio-job",completed.getString("jobId"));assertEquals("videostudio.audio.envelope.v1",completed.getString("schema"));
        assertEquals(result.getJSONArray("buckets").toString(),completed.getJSONArray("buckets").toString());assertEquals(result.getJSONObject("coverage").toString(),completed.getJSONObject("coverage").toString());
        assertFalse("Do not duplicate a potentially720-bucket payload",completed.has("job"));assertFalse(completed.has("jobResult"));assertEquals(before,invoke(fixture,"inventory"));
    }
    @Test public void persistedReceiptFromAnotherAssetHashRevisionOrWindowCannotBeReturnedAsThisJobsSuccess()throws Exception {
        String before=(String)invoke(fixture,"inventory");String[] fields={"assetId","projectId","sourceSha256","sourceProjectUpdatedAt","sourceMs","durationMs","bucketCount"};
        for(String field:fields){JSONObject changed=new JSONObject(result.toString());Object value=result.get(field);changed.put(field,value instanceof Number?((Number)value).longValue()+1:"different-source");JSONObject rejected=delivered(changed);
            assertFalse(field+": "+rejected,rejected.getBoolean("ok"));assertEquals("AUDIO_ENVELOPE_DECODE_FAILED",rejected.getString("code"));assertFalse(rejected.has("buckets"));}
        assertEquals(before,invoke(fixture,"inventory"));
    }
    private JSONObject delivered(JSONObject payload)throws Exception {return (JSONObject)invoke(service,"buildVerifiedDeferredResult",new Class<?>[]{String.class,JSONObject.class,JSONObject.class,String.class,String.class},"analyse_media",queued,new JSONObject().put("state","completed").put("result",payload),"original-audio-job",request.getString("projectId"));}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object invoke(Object object,String name)throws Exception{return invoke(object,name,new Class<?>[0]);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...arguments)throws Exception {try{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,arguments);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}}
}
