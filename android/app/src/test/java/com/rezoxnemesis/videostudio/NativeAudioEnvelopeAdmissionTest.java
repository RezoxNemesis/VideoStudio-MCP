package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Public command admission/lifecycle regressions. Reuses the frozen6 test's real
 * WAV fixture via reflection, preserving its byte-identical source/assertions.
 * No native/codec shadow and no new production symbols are required.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeAudioEnvelopeAdmissionTest {
    private final List<NativeAudioEnvelopeDispatchTest> owners=new ArrayList<>();
    @After public void close()throws Exception{for(NativeAudioEnvelopeDispatchTest owner:owners)owner.close();}

    @Test public void hostUnderscoreFieldsAreRejectedRatherThanSilentlySanitizedInStrictAudioMode()throws Exception {
        Object fixture=fixture();String before=(String)invoke(fixture,"inventory");
        for(String hidden:new String[]{"_unexpected","_mcpCommandId","_frozenSourceProof"}){
            JSONObject request=(JSONObject)invoke(fixture,"request");request.put(hidden,"untrusted host value");
            JSONObject terminal=(JSONObject)invoke(fixture,"run",new Class<?>[]{JSONObject.class},request);
            assertEquals("Strict native audio mode must reject host field "+hidden+": "+terminal,"failed",terminal.getString("status"));
            assertEquals("AUDIO_ENVELOPE_INPUT",terminal.getJSONObject("result").getString("code"));assertFalse(terminal.getJSONObject("result").has("buckets"));
        }
        assertEquals(before,invoke(fixture,"inventory"));
    }

    @Test public void cancelledBeforeWorkerStartHasADurableJobBindingAndTerminalReceiptWithoutDecoding()throws Exception {
        Object fixture=fixture();JobManager jobs=(JobManager)field(fixture,"jobs");HoldingExecutor held=new HoldingExecutor();set(jobs,"lightPool",held);
        ControlService service=(ControlService)field(fixture,"service");CommandJournal journal=(CommandJournal)field(fixture,"journal");ExecutorService dispatch=(ExecutorService)field(fixture,"dispatch");
        String before=(String)invoke(fixture,"inventory");JSONObject request=(JSONObject)invoke(fixture,"request");String id=UUID.randomUUID().toString();
        JSONObject command=new JSONObject().put("id",id).put("seq",1).put("action","analyse_media").put("parameters",request);
        dispatch.submit(()->service.onCommand(command)).get(10,TimeUnit.SECONDS);
        JSONObject inflight=journal.inflight(id);assertNotNull("Queued audio analysis must retain its actual job ID before execution",inflight);
        String jobId=inflight.getString("jobId");assertFalse(jobId.isEmpty());assertFalse(held.tasks.isEmpty());assertNull(journal.terminal(id));
        jobs.cancelAll();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JSONObject terminal;
        while((terminal=journal.terminal(id))==null&&System.nanoTime()<until)Thread.sleep(10);
        assertNotNull("Cancellation before lambda execution must not strand the command forever",terminal);assertEquals("failed",terminal.getString("status"));
        assertFalse(terminal.getJSONObject("result").getBoolean("ok"));assertEquals("cancelled",jobs.get(jobId).getJSONObject("job").getString("state"));
        assertEquals(before,invoke(fixture,"inventory"));
    }

    private Object fixture()throws Exception {
        NativeAudioEnvelopeDispatchTest owner=new NativeAudioEnvelopeDispatchTest();owners.add(owner);
        return invoke(owner,"fixture",new Class<?>[]{short[].class,int.class,long.class},new short[8000],1,1000L);
    }
    private static final class HoldingExecutor extends AbstractExecutorService {
        final List<Runnable> tasks=new ArrayList<>();boolean stopped;
        public void execute(Runnable task){tasks.add(task);}public void shutdown(){stopped=true;}
        public List<Runnable> shutdownNow(){stopped=true;return new ArrayList<>(tasks);}public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped;}public boolean awaitTermination(long n,TimeUnit unit){return stopped;}
    }
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static Object invoke(Object object,String name)throws Exception{return invoke(object,name,new Class<?>[0]);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...arguments)throws Exception{
        try{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,arguments);}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}
    }
}
