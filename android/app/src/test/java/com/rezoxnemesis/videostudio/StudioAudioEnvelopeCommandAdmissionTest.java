package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Supplemental MainActivity public-route admission checks. Real PCM fixture and
 * ordinary durable AppProtocol outbox; no decoder/protocol shadow or new product API.
 * Frozen manual4 and native6 sources are reused unchanged. Root owns execution.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class StudioAudioEnvelopeCommandAdmissionTest {
    private final List<Fixture> fixtures=new ArrayList<>();
    @After public void close()throws Exception {for(Fixture fixture:fixtures)fixture.close();}

    @Test public void publicMainRouteRejectsEveryHostUnderscoreRequestKeyRatherThanTreatingItAsTrustedMetadata()throws Exception {
        Fixture f=fixture();String before=(String)invoke(f.shared,"inventory");
        for(String key:new String[]{"_unexpected","_mcpCommandId","_frozenSourceProof"}) {
            JSONObject request=f.request().put(key,"untrusted host request value");JSONObject receipt=f.run(request);
            assertEquals("Host request key "+key+" must fail strict admission: "+receipt,"failed",receipt.getString("status"));
            JSONObject result=receipt.getJSONObject("result");assertFalse(result.getBoolean("ok"));assertEquals("AUDIO_ENVELOPE_INPUT",result.getString("code"));assertFalse(result.has("buckets"));
        }
        assertEquals(before,invoke(f.shared,"inventory"));
    }

    @Test public void ordinaryCommandEnvelopeMetadataStaysOutsideTheValidExactAudioRequest()throws Exception {
        Fixture f=fixture();String before=(String)invoke(f.shared,"inventory");JSONObject receipt=f.run(f.request());
        assertEquals(receipt.toString(),"completed",receipt.getString("status"));JSONObject result=receipt.getJSONObject("result");assertTrue(result.getBoolean("ok"));assertTrue(result.getBoolean("readOnly"));
        assertEquals(f.request().getString("projectId"),result.getString("projectId"));assertEquals(f.request().getString("assetId"),result.getString("assetId"));assertTrue(result.getJSONObject("coverage").getBoolean("complete"));
        assertEquals(before,invoke(f.shared,"inventory"));
    }

    @Test public void queuedCancellationBeforeMainAnalysisLambdaStartsStillPersistsItsNormalProtocolTerminalReceipt()throws Exception {
        Fixture f=fixture();HoldingExecutor held=new HoldingExecutor();set(f.jobs,"lightPool",held);String before=(String)invoke(f.shared,"inventory");
        JSONObject command=f.command(f.request());f.dispatch.submit(()->f.activity.onCommand(command)).get(10,TimeUnit.SECONDS);
        assertEquals("The work must remain queued in this fixture",1,held.tasks.size());assertNull(f.receipt(command.getString("id")));
        assertEquals(1,f.jobs.cancelAll());JSONObject receipt=f.awaitReceipt(command.getString("id"));
        assertEquals(receipt.toString(),"failed",receipt.getString("status"));assertFalse(receipt.getJSONObject("result").getBoolean("ok"));
        assertEquals("AUDIO_ENVELOPE_CANCELLED",receipt.getJSONObject("result").getString("code"));assertFalse(receipt.getJSONObject("result").has("buckets"));
        assertEquals(before,invoke(f.shared,"inventory"));
    }

    private Fixture fixture()throws Exception {Fixture fixture=new Fixture();fixtures.add(fixture);return fixture;}
    private static final class Fixture implements AutoCloseable {
        final NativeAudioEnvelopeDispatchTest owner=new NativeAudioEnvelopeDispatchTest();final Object shared;
        final MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();final JobManager jobs;
        final ExecutorService dispatch,analysis;final AppProtocol protocol;final CommandOutbox outbox;
        Fixture()throws Exception {
            shared=invoke(owner,"fixture",new Class<?>[]{short[].class,int.class,long.class},new short[8000],1,1000L);
            jobs=(JobManager)field(shared,"jobs");dispatch=(ExecutorService)field(shared,"dispatch");analysis=(ExecutorService)field(shared,"analysis");protocol=(AppProtocol)field(shared,"protocol");outbox=(CommandOutbox)field(protocol,"outbox");
            set(activity,"jobs",jobs);set(activity,"protocol",protocol);set(activity,"store",field(shared,"store"));set(activity,"activeProject",field(shared,"source"));
            Context context=RuntimeEnvironment.getApplication();set(activity,"prefs",context.getSharedPreferences("main-audio-command-admission",Context.MODE_PRIVATE));
        }
        JSONObject request()throws Exception {return (JSONObject)invoke(shared,"request");}
        JSONObject command(JSONObject request)throws Exception {return new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",1).put("action","analyse_media").put("parameters",request);}
        JSONObject run(JSONObject request)throws Exception {JSONObject command=command(request);dispatch.submit(()->activity.onCommand(command)).get(10,TimeUnit.SECONDS);analysis.submit(()->{}).get(10,TimeUnit.SECONDS);return awaitReceipt(command.getString("id"));}
        JSONObject receipt(String id)throws Exception {
            JSONObject page;
            try{page=outbox.readResultPage(id,0);}catch(IllegalArgumentException missing){if("Native result not found".equals(missing.getMessage()))return null;throw missing;}
            int pages=page.getInt("pageCount");long bytes=page.getLong("resultBytes");String status=page.getString("status"),hash=page.getString("resultSha256");
            assertTrue("The archived native JSON length must be representable",bytes>=0&&bytes<=Integer.MAX_VALUE);
            assertEquals(Math.max(1,(bytes+RelayTransportPolicy.RESULT_PAGE_BYTES-1)/RelayTransportPolicy.RESULT_PAGE_BYTES),pages);
            ByteArrayOutputStream joined=new ByteArrayOutputStream();
            for(int i=0;i<pages;i++) {
                JSONObject part=i==0?page:outbox.readResultPage(id,i);
                assertTrue(part.getBoolean("ok"));assertEquals("videostudio.native-result-page.v1",part.getString("schema"));assertEquals(id,part.getString("commandId"));
                assertEquals(i,part.getInt("pageIndex"));assertEquals(pages,part.getInt("pageCount"));assertEquals(bytes,part.getLong("resultBytes"));
                assertEquals(status,part.getString("status"));assertEquals(hash,part.getString("resultSha256"));assertEquals("base64",part.getString("encoding"));
                byte[] chunk=Base64.decode(part.getString("encodedChunk"),Base64.DEFAULT);
                assertEquals(Math.min(RelayTransportPolicy.RESULT_PAGE_BYTES,bytes-joined.size()),chunk.length);joined.write(chunk);
            }
            byte[] complete=joined.toByteArray();assertEquals(bytes,complete.length);assertEquals("Ordered pages must reproduce the exact archived result bytes",hash,RelayTransportPolicy.sha256(complete));
            return new JSONObject().put("status",status).put("result",new JSONObject(new String(complete,StandardCharsets.UTF_8)));
        }
        JSONObject awaitReceipt(String id)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JSONObject result;
            do{ShadowLooper.idleMainLooper();result=receipt(id);if(result!=null)return result;Thread.sleep(10);}while(System.nanoTime()<deadline);
            fail("The MainActivity audio command must reach a durable terminal receipt even if cancelled before work starts");return null;
        }
        public void close()throws Exception {Method destroy=MainActivity.class.getDeclaredMethod("onDestroy");destroy.setAccessible(true);destroy.invoke(activity);owner.close();outbox.close();}
    }
    private static final class HoldingExecutor extends AbstractExecutorService {
        final List<Runnable> tasks=new ArrayList<>();boolean stopped;
        public void execute(Runnable task){tasks.add(task);}public void shutdown(){stopped=true;}
        public List<Runnable> shutdownNow(){stopped=true;return new ArrayList<>(tasks);}public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped;}public boolean awaitTermination(long n,TimeUnit unit){return stopped;}
    }
    private static Object field(Object object,String name)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static Object invoke(Object object,String name)throws Exception {return invoke(object,name,new Class<?>[0]);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...values)throws Exception {try{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,values);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}}
}
