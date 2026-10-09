package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class JobRecoveryTest {
    @Test public void fullRecoveryQueueNeverEvictsAnUnfinishedCommand()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        RecoveryPlanStore plans=new RecoveryPlanStore(context);String first="";
        for(int i=0;i<24;i++){String id=plans.begin("export_project",new JSONObject().put("_mcpCommandId","capacity-"+i),"project");if(i==0)first=id;}
        assertEquals("A single restart must resubmit every preserved plan through bounded execution lanes",24,plans.pendingForAutoResume().length());
        try{plans.begin("export_project",new JSONObject().put("_mcpCommandId","overflow"),"project");fail("A full queue must reject new work without losing unfinished intent");}catch(IllegalStateException expected){}
        assertNotNull(plans.get(first));assertEquals(24,plans.recent(24).length());
        plans.completePlan(first,"Completed");
        String replacement=plans.begin("export_project",new JSONObject().put("_mcpCommandId","replacement"),"project");
        assertNotNull(plans.get(replacement));
        for(int i=1;i<24;i++)assertNotNull(plans.findByCommand("export_project","capacity-"+i,"project"));
    }
    @Test public void commandBoundPlanIsCreatedOnlyOnceAndRejectsChangedInputs()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        RecoveryPlanStore plans=new RecoveryPlanStore(context);
        JSONObject p=new JSONObject().put("projectId","project").put("_mcpCommandId","export-command-001").put("quality","720p");
        String first=plans.begin("export_project",p,"project");
        assertEquals("Crash retry must reuse the original durable plan",first,new RecoveryPlanStore(context).begin("export_project",new JSONObject(p.toString()),"project"));
        assertEquals(1,plans.recent(24).length());
        try{plans.begin("export_project",new JSONObject(p.toString()).put("projectId","other"),"other");fail("One command must retain its original project binding");}catch(IllegalArgumentException expected){}
        try{plans.begin("export_project",new JSONObject(p.toString()).put("quality","1080p"),"project");fail("One command cannot silently change export inputs");}catch(IllegalArgumentException expected){}
        assertEquals(1,plans.recent(24).length());
    }
    @Test public void rejectedOutputBindingIsClearedDurablyAndCannotClearANewerOutput()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        RecoveryPlanStore plans=new RecoveryPlanStore(context);String id=plans.begin("export_project",new JSONObject(),"project");plans.attachJob(id,"job");
        plans.markOutputForJob("job","file:///old.mp4","Old.mp4",new JSONObject().put("sha256","a".repeat(64)));
        assertNotNull(plans.outputForJob("job").getJSONObject("verification"));
        assertTrue(plans.invalidateOutput(id,"file:///old.mp4","Checksum mismatch"));
        RecoveryPlanStore restarted=new RecoveryPlanStore(context);assertNull(restarted.outputForJob("job"));
        assertFalse(restarted.get(id).has("outputVerification"));assertEquals("output_invalidated",restarted.get(id).getString("stage"));
        restarted.markOutputForJob("job","file:///new.mp4","New.mp4",new JSONObject().put("sha256","b".repeat(64)));
        assertFalse(restarted.invalidateOutput(id,"file:///old.mp4","Late verification"));
        assertEquals("file:///new.mp4",new RecoveryPlanStore(context).outputForJob("job").getString("uri"));
    }
    @Test public void durableCompletionPrecedesPlanRetirementAndHundredPercentIsNotTerminal()throws Exception{
        Context context=RuntimeEnvironment.getApplication();SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);prefs.edit().clear().commit();
        RecoveryPlanStore plans=new RecoveryPlanStore(context);String planId=plans.begin("generate_voice",new JSONObject(),"project");JobManager manager=new JobManager(context);
        CountDownLatch committed=new CountDownLatch(1),release=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        try{
            JobManager.Job job=manager.submit("Durable completion",JobManager.Kind.LIGHT,state->{
                try{
                    plans.attachJob(planId,state.id);state.setResult(new JSONObject().put("ok",true).put("assetId","registered"));state.checkpoint("ready",100,"Output registered");plans.checkpointForJob(state.id,"ready",100,"Output registered");
                    assertEquals("running",plans.get(planId).getString("state"));assertEquals(1,plans.pendingForAutoResume().length());
                    state.completeDurably();
                    JSONArray saved=new JSONArray(prefs.getString("job_recovery_snapshot","[]"));boolean found=false;
                    for(int i=0;i<saved.length();i++){JSONObject row=saved.getJSONObject(i);if(state.id.equals(row.getString("id"))){found=true;assertEquals("completed",row.getString("state"));assertEquals("registered",row.getJSONObject("result").getString("assetId"));}}
                    assertTrue("Completion must be committed before returning",found);
                }catch(Throwable error){failure.set(error);}finally{committed.countDown();}
                release.await(5,TimeUnit.SECONDS);
            });
            assertTrue(committed.await(5,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());
            assertEquals("completed",manager.get(job.id).getJSONObject("job").getString("state"));assertEquals("running",plans.get(planId).getString("state"));
            plans.completeByJob(job.id);assertEquals("completed",plans.get(planId).getString("state"));assertEquals(0,plans.pendingForAutoResume().length());
            assertFalse("A committed result cannot be cancelled afterwards",manager.cancel(job.id));
        }finally{release.countDown();manager.shutdown();}
    }
    @Test public void cancelledDurablePlanCannotBeResurrectedByLateWorkerCallbacks()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        RecoveryPlanStore plans=new RecoveryPlanStore(context);String id=plans.begin("generate_voice",new JSONObject(),"project");plans.attachJob(id,"cancelled-job");plans.cancelByJob("cancelled-job");
        plans.failByJob("cancelled-job","Worker unwinding",true);plans.checkpointForJob("cancelled-job","late",60,"Late progress");plans.completeByJob("cancelled-job");plans.markOutputForJob("cancelled-job","file:///late.wav","Late");
        assertEquals("cancelled",plans.get(id).getString("state"));assertEquals("",plans.get(id).getString("outputUri"));assertEquals(0,plans.pendingForAutoResume().length());
        try{plans.markResuming(id);fail("Terminal plan resumed");}catch(java.util.concurrent.CancellationException expected){}
        try{plans.attachJob(id,"new-job");fail("Terminal plan attached another job");}catch(java.util.concurrent.CancellationException expected){}
    }
    @Test public void recoveryRetainsGenerationAndCommandIdentityWithoutOtherInternalParameters()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        RecoveryPlanStore plans=new RecoveryPlanStore(context);
        String id=plans.begin("generate_voice",new JSONObject().put("_generationId","stable-generation-123").put("_mcpCommandId","command-123").put("_temporaryToken","discard-me").put("text","A script").put("fileName","voice.wav"),"project");
        JSONObject saved=new RecoveryPlanStore(context).get(id).getJSONObject("parameters");
        assertEquals("stable-generation-123",saved.getString("_generationId"));assertEquals("command-123",saved.getString("_mcpCommandId"));assertFalse(saved.has("_temporaryToken"));assertEquals("voice.wav",saved.getString("fileName"));
        id=plans.begin("generate_voice",new JSONObject().put("_generationId","../unsafe").put("_mcpCommandId","bad/path"),"project");
        saved=plans.get(id).getJSONObject("parameters");assertFalse(saved.has("_generationId"));assertFalse(saved.has("_mcpCommandId"));
    }
    @Test public void cancellationAndRevocationPreventPublication()throws Exception{
        JobManager.Job job=new JobManager.Job("Publication",JobManager.Kind.LIGHT);java.util.concurrent.atomic.AtomicBoolean published=new java.util.concurrent.atomic.AtomicBoolean();
        job.state=JobManager.STATE_CANCELLED;
        try{job.commit(active->published.set(true));fail("Cancelled publication proceeded");}catch(java.util.concurrent.CancellationException expected){}
        try{job.checkpoint("publish",99,"Cancelled");fail("Cancelled work kept progressing");}catch(java.util.concurrent.CancellationException expected){}
        assertFalse(published.get());
        job=new JobManager.Job("Revoked",JobManager.Kind.LIGHT);job.setAuthorizationGuard(()->{throw new java.util.concurrent.CancellationException("Revoked");});
        try{job.commit(active->published.set(true));fail("Revoked publication proceeded");}catch(java.util.concurrent.CancellationException expected){}assertFalse(published.get());
    }
    @Test public void canonicalTransitionsRejectTerminalResurrection() {
        assertTrue(JobManager.canTransition(JobManager.STATE_QUEUED, JobManager.STATE_PREPARING));
        assertTrue(JobManager.canTransition(JobManager.STATE_RUNNING, JobManager.STATE_WAITING_THERMAL));
        assertTrue(JobManager.canTransition(JobManager.STATE_WAITING_THERMAL, JobManager.STATE_RUNNING));
        assertTrue(JobManager.canTransition(JobManager.STATE_RUNNING, JobManager.STATE_CHECKPOINTED));
        assertTrue(JobManager.canTransition(JobManager.STATE_CHECKPOINTED, JobManager.STATE_RUNNING));
        assertTrue(JobManager.canTransition(JobManager.STATE_RUNNING, JobManager.STATE_COMPLETED));

        assertFalse(JobManager.canTransition(JobManager.STATE_COMPLETED, JobManager.STATE_RUNNING));
        assertFalse(JobManager.canTransition(JobManager.STATE_FAILED, JobManager.STATE_RUNNING));
        assertFalse(JobManager.canTransition(JobManager.STATE_CANCELLED, JobManager.STATE_RUNNING));
    }

    @Test public void legacyInterruptedWorkRestoresAsCheckpointedWithoutLosingProgress() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        JSONObject row=new JSONObject()
                .put("id","job-restore")
                .put("name","Heavy render")
                .put("kind","heavy")
                .put("state","running")
                .put("progress",67)
                .put("detail","Encoding")
                .put("stage","encode")
                .put("recoverable",true)
                .put("retryCount",1)
                .put("lastCheckpointAt",now)
                .put("createdAt",now-1000)
                .put("updatedAt",now);
        prefs.edit().putString("job_recovery_snapshot",new JSONArray().put(row).toString()).commit();

        JobManager manager=new JobManager(context);
        try {
            JSONObject state=manager.get("job-restore").getJSONObject("job");
            assertEquals(JobManager.STATE_CHECKPOINTED,state.getString("state"));
            assertEquals("encode",state.getString("stage"));
            assertEquals(67,state.getInt("progress"));
            assertTrue(state.getBoolean("recoverable"));
        } finally {
            manager.shutdown();
            prefs.edit().remove("job_recovery_snapshot").commit();
        }
    }

    @Test public void waitStatesAreNonTerminalAndCancellationIsTerminal() {
        assertFalse(JobManager.isTerminal(JobManager.STATE_WAITING_MEMORY));
        assertFalse(JobManager.isTerminal(JobManager.STATE_WAITING_STORAGE));
        assertFalse(JobManager.isTerminal(JobManager.STATE_WAITING_NETWORK));
        assertFalse(JobManager.isTerminal(JobManager.STATE_WAITING_NATIVE));
        assertTrue(JobManager.isTerminal(JobManager.STATE_CANCELLED));
        assertTrue(JobManager.isTerminal(JobManager.STATE_FAILED));
        assertTrue(JobManager.isTerminal(JobManager.STATE_COMPLETED));
    }

    @Test public void openingAnotherManagerDoesNotCheckpointALiveServiceJob() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().remove("job_recovery_snapshot").commit();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        JobManager service=new JobManager(context);JobManager activity=null;
        try{
            JobManager.Job live=service.submit("Service work",JobManager.Kind.LIGHT,state->{started.countDown();release.await(5,TimeUnit.SECONDS);});
            assertTrue(started.await(5,TimeUnit.SECONDS));activity=new JobManager(context);
            assertEquals("Opening UI is not process death","running",activity.get(live.id).getJSONObject("job").getString("state"));
        }finally{release.countDown();if(activity!=null)activity.shutdown();service.shutdown();}
    }

    @Test public void progressFromOneLaneCannotEraseAnotherManagersJob() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);
        prefs.edit().remove("job_recovery_snapshot").commit();
        CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(2);
        JobManager first=new JobManager(context),second=new JobManager(context);
        try{
            JobManager.Job a=first.submit("A",JobManager.Kind.LIGHT,state->{started.countDown();release.await(5,TimeUnit.SECONDS);});
            JobManager.Job b=second.submit("B",JobManager.Kind.LIGHT,state->{started.countDown();release.await(5,TimeUnit.SECONDS);});
            assertTrue(started.await(5,TimeUnit.SECONDS));a.checkpoint("work",40,"Progress A");
            JSONArray saved=new JSONArray(prefs.getString("job_recovery_snapshot","[]"));
            java.util.HashSet<String> ids=new java.util.HashSet<>();for(int i=0;i<saved.length();i++)ids.add(saved.getJSONObject(i).getString("id"));
            assertTrue(ids.contains(a.id));assertTrue("B must survive A's checkpoint",ids.contains(b.id));
        }finally{release.countDown();first.shutdown();second.shutdown();}
    }
    @Test public void pausingAutonomousWorkKeepsOwnerImportsRunning() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        JobManager service=new JobManager(context);CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);
        try{
            JobManager.Job owner=service.submit("Owner import",JobManager.Kind.LIGHT,JobManager.Origin.OWNER,state->{started.countDown();release.await(5,TimeUnit.SECONDS);});
            JobManager.Job agent=service.submit("Agent work",JobManager.Kind.LIGHT,JobManager.Origin.AUTONOMOUS,state->{started.countDown();release.await(5,TimeUnit.SECONDS);});
            assertTrue(started.await(5,TimeUnit.SECONDS));assertEquals(1,service.cancelAutonomous());
            assertEquals("running",service.get(owner.id).getJSONObject("job").getString("state"));assertEquals("cancelled",service.get(agent.id).getJSONObject("job").getString("state"));
            assertTrue(service.isOwner(owner.id));assertFalse(service.isOwner(agent.id));
        }finally{release.countDown();service.shutdown();}
    }
    @Test public void ownerRecoveryPlanSurvivesPause() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        RecoveryPlanStore plans=new RecoveryPlanStore(context);
        String owner=plans.begin("animate_images",new JSONObject().put("_origin","owner"),"project");String agent=plans.begin("animate_images",new JSONObject(),"project");
        assertEquals(1,plans.cancelAutonomous());assertEquals("queued",plans.get(owner).getString("state"));assertEquals("cancelled",plans.get(agent).getString("state"));
    }

}
