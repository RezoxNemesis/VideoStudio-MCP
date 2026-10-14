package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Exercise actual controller admission and restart recovery without running media/network work. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeMediaRecoveryTest {
    private final List<Fixture> fixtures=new ArrayList<>();

    /** JobManager remains real: its submitted worker is held at the executor boundary. */
    private static final class HoldingExecutor extends AbstractExecutorService {
        final List<Runnable> tasks=new ArrayList<>();
        private boolean shutdown;
        @Override public void execute(Runnable task){
            if(shutdown)throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(task);
        }
        @Override public void shutdown(){shutdown=true;}
        @Override public List<Runnable> shutdownNow(){shutdown=true;return new ArrayList<>(tasks);}
        @Override public boolean isShutdown(){return shutdown;}
        @Override public boolean isTerminated(){return shutdown;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return shutdown;}
    }

    private static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();
        final ControlService service=Robolectric.buildService(ControlService.class).get();
        final ProjectStore store=new ProjectStore(context);
        final RecoveryPlanStore plans=new RecoveryPlanStore(context);
        final CommandJournal journal=new CommandJournal(context);
        final AppProtocol protocol=new AppProtocol(context,null);
        final JobManager jobs=new JobManager(context,"native_media_recovery_test");
        final HoldingExecutor transfers=new HoldingExecutor();
        final HoldingExecutor light=new HoldingExecutor();
        final HoldingExecutor watchers=new HoldingExecutor();
        Fixture()throws Exception{
            // Do not invoke Service.onCreate(), AppProtocol.start(), or a held worker.
            context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit()
                    .putBoolean("chatgpt_control_paused",false).commit();
            set(jobs,"transferPool",transfers);
            set(jobs,"lightPool",light);
            set(service,"store",store);
            set(service,"jobs",jobs);
            set(service,"protocol",protocol);
            set(service,"recoveryPlans",plans);
            set(service,"commandJournal",journal);
            set(service,"prefs",context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));
            set(service,"commandCompletionWatchers",watchers);
        }
        void close()throws Exception{
            protocol.stop();
            jobs.shutdown();
            for(String name:new String[]{"commandCompletionWatchers","directorSetupExecutor",
                    "nativeSourceExecutor","cinematic3DExecutor","authoringExecutor","pipelineExecutor"}){
                Field field=ControlService.class.getDeclaredField(name);field.setAccessible(true);
                ((ExecutorService)field.get(service)).shutdownNow();
            }
        }
    }

    private Fixture fixture()throws Exception{Fixture f=new Fixture();fixtures.add(f);return f;}
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object call(ControlService service,String name,Class<?>[] signature,Object...args)throws Exception{
        Method method=ControlService.class.getDeclaredMethod(name,signature);method.setAccessible(true);
        return method.invoke(service,args);
    }
    private static JSONObject command(String projectId)throws Exception{
        return new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",1)
                .put("action","import_mailbox_media").put("parameters",new JSONObject()
                        .put("projectId",projectId).put("attachmentId",UUID.randomUUID().toString())
                        .put("manifestSha256","a".repeat(64)));
    }
    private static JSONObject admit(Fixture f,JSONObject command)throws Exception{
        f.journal.begin(command);
        JSONObject trusted=new JSONObject(command.getJSONObject("parameters").toString())
                .put("_mcpCommandId",command.getString("id"));
        JSONObject queued=(JSONObject)call(f.service,"queueMailboxMediaImport",
                new Class<?>[]{JSONObject.class},trusted);
        call(f.service,"complete",new Class<?>[]{JSONObject.class,JSONObject.class},command,queued);
        return queued;
    }
    @After public void closeFixtures()throws Exception{
        Collections.reverse(fixtures);
        for(Fixture fixture:fixtures)fixture.close();
    }

    @Test public void acceptedImportPersistsBeforeWorkerStartsAndRestartRelinksOriginalCommand()throws Exception{
        Fixture first=fixture();
        String projectId=first.store.create("Original private media").id;
        JSONObject original=command(projectId);
        JSONObject queued=admit(first,original);
        assertEquals(1,first.transfers.tasks.size());
        assertEquals("queued",first.jobs.get(queued.getString("jobId")).getJSONObject("job").getString("state"));
        JSONArray pending=first.plans.pendingForAutoResume();
        assertEquals(1,pending.length());
        JSONObject plan=pending.getJSONObject(0),parameters=plan.getJSONObject("parameters");
        String planId=plan.getString("id"),commandId=original.getString("id"),oldJob=queued.getString("jobId");
        assertEquals("import_mailbox_media",plan.getString("action"));
        assertEquals(oldJob,plan.getString("jobId"));
        assertEquals(1,plan.getInt("attempts"));
        assertEquals(commandId,parameters.getString("_mcpCommandId"));
        assertEquals(original.getJSONObject("parameters").getString("attachmentId"),parameters.getString("attachmentId"));
        assertEquals("a".repeat(64),parameters.getString("manifestSha256"));
        assertFalse(parameters.has("_recoveryPlanId"));

        // A new controller reads only persisted state; the original worker never ran.
        Fixture restarted=fixture();
        assertEquals("checkpointed",restarted.jobs.get(oldJob).getJSONObject("job").getString("state"));
        call(restarted.service,"recoverDurablePlans",new Class<?>[]{});
        assertEquals(1,restarted.transfers.tasks.size());
        JSONObject binding=restarted.journal.inflight(commandId);
        assertNotNull(binding);
        String resumedJob=binding.getString("jobId");
        assertNotEquals(oldJob,resumedJob);
        assertEquals(commandId,binding.getJSONObject("command").getString("id"));
        assertEquals(projectId,binding.getString("projectId"));
        assertEquals(resumedJob,binding.getJSONObject("queuedResult").getString("jobId"));
        JSONObject resumed=restarted.plans.get(planId);
        assertEquals(resumedJob,resumed.getString("jobId"));
        assertEquals(2,resumed.getInt("attempts"));
        assertEquals(1,restarted.plans.recent(24).length());
        assertEquals(1,restarted.watchers.tasks.size());
        assertNull(restarted.journal.terminal(commandId));
    }

    @Test public void cancellingBeforeWorkerStartsPreventsImportFromBeingResubmittedAfterRestart()throws Exception{
        Fixture first=fixture();
        JSONObject original=command(first.store.create("Cancelled import").id);
        JSONObject queued=admit(first,original);
        String jobId=queued.getString("jobId");
        JSONObject plan=first.plans.pendingForAutoResume().getJSONObject(0);
        assertEquals(jobId,plan.getString("jobId"));
        assertTrue(first.jobs.cancel(jobId));
        first.plans.cancelByJob(jobId);

        Fixture restarted=fixture();
        call(restarted.service,"recoverDurablePlans",new Class<?>[]{});
        assertEquals(0,restarted.transfers.tasks.size());
        assertEquals(0,restarted.plans.pendingForAutoResume().length());
        assertEquals("cancelled",restarted.plans.get(plan.getString("id")).getString("state"));
        assertEquals(jobId,restarted.journal.inflight(original.getString("id")).getString("jobId"));
    }

    @Test public void duplicateAttachmentDoesNotSpendRetryAndTerminalPlansCannotBeReattached()throws Exception{
        RecoveryPlanStore plans=new RecoveryPlanStore(RuntimeEnvironment.getApplication());
        String planId=plans.begin("import_mailbox_media",new JSONObject(),"project");
        plans.attachJob(planId,"first-job");
        plans.attachJob(planId,"first-job");
        assertEquals(1,plans.get(planId).getInt("attempts"));
        plans.completeByJob("first-job");
        plans.attachJob(planId,"late-job");
        plans.failByJob("first-job","late transport exception",true);
        plans.checkpointForJob("first-job","late checkpoint",12,"late progress");
        plans.markResuming(planId);
        JSONObject completed=plans.get(planId);
        assertEquals("completed",completed.getString("state"));
        assertEquals("first-job",completed.getString("jobId"));
        assertEquals(1,completed.getInt("attempts"));

        String cancelled=plans.begin("import_mailbox_media",new JSONObject(),"project");
        plans.attachJob(cancelled,"cancelled-job");
        plans.cancelByJob("cancelled-job");
        plans.attachJob(cancelled,"late-cancelled-job");
        plans.failByJob("cancelled-job","late cancellation exception",true);
        plans.checkpointForJob("cancelled-job","late checkpoint",100,"late completion");
        plans.completeByJob("cancelled-job");
        plans.completePlan(cancelled,"late recovered output");
        plans.markResuming(cancelled);
        assertEquals("cancelled",plans.get(cancelled).getString("state"));
        assertEquals("cancelled-job",plans.get(cancelled).getString("jobId"));
        assertEquals(0,plans.pendingForAutoResume().length());
    }

    @Test public void runningWorkerInterruptedIOExceptionCannotResurrectCancelledImport()throws Exception{
        Fixture first=fixture();
        String projectId=first.store.create("Running import cancellation").id;
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        JobManager.Work interruptedTransfer=state->{
            entered.countDown();
            try{release.await();}
            catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Synthetic native media checkpoint cancellation");
            }
        };
        // Exercise the actual guarded worker catch path using LIGHT to avoid device resource gates.
        JobManager.Job job=(JobManager.Job)call(first.service,"submitRecoverableGuarded",
                new Class<?>[]{String.class,JSONObject.class,String.class,String.class,JobManager.Kind.class,JobManager.Work.class},
                "import_mailbox_media",new JSONObject().put("projectId",projectId),projectId,
                "Controlled import",JobManager.Kind.LIGHT,interruptedTransfer);
        String planId=first.plans.pendingForAutoResume().getJSONObject(0).getString("id");
        Thread worker=new Thread(first.light.tasks.get(0),"native-media-cancellation-test");
        try{
            worker.start();
            assertTrue("Guarded import did not start",entered.await(5,TimeUnit.SECONDS));
            // Cancel the durable plan first so the worker's later exception is deliberately last.
            first.plans.cancelByJob(job.id);
            assertTrue(first.jobs.cancel(job.id));
            worker.join(5000);
            assertFalse("Cancelled worker did not exit",worker.isAlive());
            assertEquals("cancelled",first.jobs.get(job.id).getJSONObject("job").getString("state"));
            assertEquals("cancelled",first.plans.get(planId).getString("state"));
            assertEquals(0,first.plans.pendingForAutoResume().length());
            Fixture restarted=fixture();
            call(restarted.service,"recoverDurablePlans",new Class<?>[]{});
            assertEquals(0,restarted.transfers.tasks.size());
            assertEquals("cancelled",restarted.plans.get(planId).getString("state"));
        }finally{
            release.countDown();worker.interrupt();worker.join(5000);
        }
    }
}
