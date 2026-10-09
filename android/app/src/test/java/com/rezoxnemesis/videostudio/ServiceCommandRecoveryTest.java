package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;

/** Real public service dispatch, with only the external completion transport recorded. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,shadows=ServiceCommandRecoveryTest.RecordingProtocol.class)
public class ServiceCommandRecoveryTest {
    @Test public void foregroundStorageCommandsUseTheDurableAutonomousServiceHandoff()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        try(org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            org.robolectric.shadows.ShadowApplication app=org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication());while(app.getNextStartedService()!=null){}
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("protocol");field.setAccessible(true);RecordingProtocol foreground=Shadow.extract((AppProtocol)field.get(controller.get()));
            JSONObject cmd=new JSONObject().put("id","foreground-storage-001").put("action","vault_replicate").put("parameters",new JSONObject().put("projectId",project.id).put("assetId","owned-video").put("profileIds",new org.json.JSONArray().put("selected-profile")));
            controller.get().onCommand(cmd);assertNull("Storage handoff cannot claim completion",foreground.result);
            android.content.Intent intent=app.getNextStartedService();assertNotNull(intent);assertEquals(ControlService.ACTION_REMOTE_COMMAND,intent.getAction());assertEquals(cmd.getString("id"),intent.getStringExtra("commandId"));
            assertEquals(cmd.toString(),new CommandJournal(context).capturedCommand(cmd.getString("id")).toString());
        }
    }
    @Test public void restoreSubmissionBindsTheManifestWithoutRequiringEvictedLocalCacheAndReusesOneJob()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ProjectStore.Project latest=store.get(project.id);latest.asset("owned-video").generationMetadata.put("vault",new JSONObject().put("complete",true).put("manifestId","b".repeat(64)));store.save(latest);
        JobManager manager=new JobManager(context);RecoveryPlanStore plans=new RecoveryPlanStore(context);field("jobs",manager);field("recoveryPlans",plans);java.lang.reflect.Field lane=JobManager.class.getDeclaredField("heavyLane");lane.setAccessible(true);lane.set(manager,new java.util.concurrent.Semaphore(0));
        java.lang.reflect.Method queue=ControlService.class.getDeclaredMethod("queueVaultRestore",JSONObject.class);queue.setAccessible(true);
        JSONObject args=new JSONObject().put("projectId",project.id).put("assetId","owned-video").put("vaultManifestId","f".repeat(64)).put("_mcpCommandId","restore-bound-command-001");
        try{
            JSONObject first=(JSONObject)queue.invoke(service,args),retry=(JSONObject)queue.invoke(service,new JSONObject(args.toString()));assertTrue(first.getBoolean("durableRecovery"));assertEquals(first.getString("jobId"),retry.getString("jobId"));
            JSONObject saved=plans.recent(24).getJSONObject(0);assertEquals("vault_restore",saved.getString("action"));assertEquals("b".repeat(64),saved.getJSONObject("parameters").getString("vaultManifestId"));assertEquals(latest.asset("owned-video").uri,saved.getJSONObject("parameters").getString("vaultSourceUri"));assertEquals("autonomous",manager.get(first.getString("jobId")).getJSONObject("job").getString("origin"));
        }finally{shutdownJobs(manager);}
    }
    @Test public void storageTransferRejectsUnconnectedProfileIdentifiersBeforeCreatingJobs()throws Exception{
        JSONObject cmd=new JSONObject().put("id","foreign-storage-001").put("action","vault_replicate").put("parameters",new JSONObject().put("projectId",project.id).put("assetId","owned-video").put("profileIds",new org.json.JSONArray().put("content://not-selected/folder")));
        service.onCommand(cmd);assertNotNull(completion.result);assertFalse(completion.result.getBoolean("ok"));assertTrue(completion.result.getString("error").contains("not connected"));assertFalse(completion.result.has("jobId"));
    }
    @Test public void interruptedImplicitProjectEditRemainsBoundAfterOwnerSwitchesProjects()throws Exception{
        JSONObject command=new JSONObject().put("id","implicit-project-edit-001").put("action","creator_preset").put("parameters",new JSONObject().put("preset","noir"));
        service.onCommand(command);assertTrue(completion.result.getBoolean("ok"));
        ProjectStore.Project second=store.create("Owner second project");second.assets.add(ProjectStore.Asset.fromJson(project.assets.get(0).toJson()));second.clips.add(ProjectStore.Clip.fromJson(project.clips.get(0).toJson()));store.save(second);store.setActive(second.id);
        journal.begin(command);completion.result=null;service.onCommand(new JSONObject(command.toString()));
        assertNotNull(completion.result);assertTrue(completion.result.toString(),completion.result.getBoolean("ok"));assertEquals(project.id,completion.result.getString("projectId"));
        assertFalse(store.get(second.id).clips.get(0).effects.has("effectPreset"));
    }
    @Test public void renderOnlyRetryUsesItsOriginalRevisionAndRejectsChangedSettings()throws Exception{
        Context context=RuntimeEnvironment.getApplication();JobManager manager=new JobManager(context);RecoveryPlanStore plans=new RecoveryPlanStore(context);
        field("jobs",manager);field("recoveryPlans",plans);
        java.lang.reflect.Field lane=JobManager.class.getDeclaredField("heavyLane");lane.setAccessible(true);lane.set(manager,new java.util.concurrent.Semaphore(0));
        JSONObject args=new JSONObject().put("projectId",project.id).put("_mcpCommandId","render-only-snapshot-001").put("render",true).put("expectedRevision",project.revision);
        java.lang.reflect.Method method=ControlService.class.getDeclaredMethod("autonomousEdit",JSONObject.class);method.setAccessible(true);
        try{
            JSONObject first=(JSONObject)method.invoke(service,args);
            new EditorEngine(store).execute(project.id,project.revision,"owner","","set_title",new JSONObject().put("clipId","original").put("text","Next draft"));
            JSONObject retry=(JSONObject)method.invoke(service,new JSONObject(args.toString()));
            assertEquals(first.getString("jobId"),retry.getString("jobId"));assertEquals(project.revision,retry.getLong("revision"));
            try{method.invoke(service,new JSONObject(args.toString()).put("quality","720p"));fail("Retry cannot change render settings");}
            catch(java.lang.reflect.InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}
            assertEquals(1,plans.recent(24).length());assertEquals("Next draft",store.get(project.id).clips.get(0).title);
        }finally{shutdownJobs(manager);}
    }
    @Test public void remoteParametersCannotSupplyServiceRecoveryHandles()throws Exception{
        JSONObject command=new JSONObject().put("id","public-internal-keys-001").put("action","creator_preset")
                .put("parameters",new JSONObject().put("projectId",project.id).put("preset","noir")
                        .put("_origin","owner").put("_recoveryPlanId","someone-elses-plan").put("_mcpCommandId","forged-id"));
        service.onCommand(command);
        assertNotNull(completion.result);assertTrue(completion.result.toString(),completion.result.getBoolean("ok"));
        assertEquals(command.getString("id"),completion.result.getString("commandId"));
        assertEquals("noir",store.get(project.id).clips.get(0).effects.getString("effectPreset"));
        assertNull(store.commandReceipt(project.id,"forged-id"));
    }
    private void shutdownJobs(JobManager manager)throws Exception{
        manager.shutdown();
        // Finish checkpoint writes before Robolectric resets Android queued work.
        for(String name:new String[]{"pool","manualPool"}){
            java.lang.reflect.Field executor=JobManager.class.getDeclaredField(name);executor.setAccessible(true);
            assertTrue("Job workers must finish before fixture teardown",((java.util.concurrent.ExecutorService)executor.get(manager)).awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test public void autonomousExportRetryKeepsOneJobAndTheCommittedGraphSnapshot()throws Exception{
        Context context=RuntimeEnvironment.getApplication();JobManager manager=new JobManager(context);RecoveryPlanStore plans=new RecoveryPlanStore(context);
        field("jobs",manager);field("recoveryPlans",plans);
        java.lang.reflect.Field lane=JobManager.class.getDeclaredField("heavyLane");lane.setAccessible(true);lane.set(manager,new java.util.concurrent.Semaphore(0));
        JSONObject args=new JSONObject().put("projectId",project.id).put("_mcpCommandId","snapshot-export-command-001").put("render",true).put("preset","noir");
        java.lang.reflect.Method method=ControlService.class.getDeclaredMethod("autonomousEdit",JSONObject.class);method.setAccessible(true);
        try{
            JSONObject first=(JSONObject)method.invoke(service,args);ProjectStore.Project edited=store.get(project.id);
            new EditorEngine(store).execute(project.id,edited.revision,"owner","","set_title",new JSONObject().put("clipId","original").put("text","New owner draft"));
            JSONObject retry=(JSONObject)method.invoke(service,new JSONObject(args.toString()));
            assertEquals(first.getString("jobId"),retry.getString("jobId"));assertEquals(1,plans.recent(24).length());
            JSONObject saved=plans.recent(24).getJSONObject(0).getJSONObject("parameters").getJSONObject("projectSnapshot");
            assertEquals(edited.revision,saved.getLong("revision"));assertEquals("",saved.getJSONArray("clips").getJSONObject(0).getString("title"));
            assertEquals("New owner draft",store.get(project.id).clips.get(0).title);
        }finally{shutdownJobs(manager);}
    }
    @Test public void retriedSubmissionReusesTheLiveCommandBoundJob()throws Exception{
        Context context=RuntimeEnvironment.getApplication();JobManager manager=new JobManager(context);RecoveryPlanStore plans=new RecoveryPlanStore(context);
        field("jobs",manager);field("recoveryPlans",plans);
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger executions=new java.util.concurrent.atomic.AtomicInteger();
        JobManager.Work work=state->{executions.incrementAndGet();started.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);state.setResult(new JSONObject().put("ok",true));};
        java.lang.reflect.Method method=ControlService.class.getDeclaredMethod("submitRecoverableLight",String.class,JSONObject.class,String.class,String.class,JobManager.Work.class);method.setAccessible(true);
        JSONObject args=new JSONObject().put("projectId",project.id).put("_mcpCommandId","one-job-command-001");
        try{
            JobManager.Job first=(JobManager.Job)method.invoke(service,"export_project",args,project.id,"One job",work);
            assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
            JobManager.Job retry=(JobManager.Job)method.invoke(service,"export_project",new JSONObject(args.toString()),project.id,"Retry",work);
            assertEquals(first.id,retry.id);assertEquals(1,plans.recent(24).length());assertEquals(1,executions.get());
        }finally{release.countDown();shutdownJobs(manager);}
    }
    @Test public void foregroundRemoteRenderHandsOffWithoutClaimingCompletion()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        try(org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            org.robolectric.shadows.ShadowApplication app=org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication());
            while(app.getNextStartedService()!=null){}
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("protocol");field.setAccessible(true);
            RecordingProtocol ownerCompletion=Shadow.extract((AppProtocol)field.get(controller.get()));
            JSONObject cmd=new JSONObject().put("id","foreground-render-001").put("action","autonomous_edit")
                    .put("parameters",new JSONObject().put("projectId",project.id).put("render",true).put("expectedRevision",project.revision));
            controller.get().onCommand(cmd);
            assertNull("Queued foreground export must not be acknowledged as completed",ownerCompletion.result);
            android.content.Intent intent=app.getNextStartedService();assertNotNull("Remote export needs the foreground service",intent);
            assertEquals("com.rezoxnemesis.videostudio.REMOTE_COMMAND",intent.getAction());
            assertNull("Binder carries only the durable reference",intent.getStringExtra("command"));
            JSONObject handed=new CommandJournal(context).capturedCommand(intent.getStringExtra("commandId"));assertEquals(cmd.getString("id"),handed.getString("id"));
            assertEquals(project.id,handed.getJSONObject("parameters").getString("projectId"));
        }
    }
    @Test public void bulkPlanRecoversThroughItsTransactionReceipt()throws Exception{
        JSONObject cmd=new JSONObject().put("id","public-plan-command-001").put("action","apply_edit_plan")
                .put("parameters",new JSONObject().put("projectId",project.id).put("clips",new org.json.JSONArray().put(new JSONObject().put("assetId","owned-video").put("outMs",2000))));
        journal.begin(cmd);service.onCommand(cmd);
        assertNotNull(completion.result);assertTrue(completion.result.toString(),completion.result.getBoolean("ok"));
        ProjectStore.Project edited=store.get(project.id);
        new EditorEngine(store).execute(project.id,edited.revision,"owner","","set_title",new JSONObject().put("clipId",edited.clips.get(0).id).put("text","Owner after plan"));
        long revision=store.get(project.id).revision;
        // Model a lost terminal journal write while preserving the SQLite receipt.
        journal.begin(cmd);completion.result=null;service.onCommand(new JSONObject(cmd.toString()));
        assertNotNull(completion.result);assertTrue(completion.result.getBoolean("ok"));assertEquals(revision,store.get(project.id).revision);
        assertEquals("Owner after plan",store.get(project.id).clips.get(0).title);
    }
    @Test public void stopControlDeniesAnAlreadyLeasedEditBeforeRecovery()throws Exception{
        JSONObject command=command(.4);journal.begin(command);long revision=project.revision;
        protocol.setControlPaused(true);service.onCommand(command);
        assertNotNull(completion.result);assertEquals("denied",completion.status);
        assertFalse(completion.result.getBoolean("ok"));assertEquals(revision,store.get(project.id).revision);
        assertEquals(1,store.get(project.id).clips.get(0).volume,0);
    }
    @Test public void stoppedControlCannotReplayAnOldSuccess()throws Exception{
        service.onCommand(command(.4));long revision=store.get(project.id).revision;
        protocol.setControlPaused(true);completion.result=null;service.onCommand(command(.4));
        assertNotNull(completion.result);assertEquals("denied",completion.status);
        assertFalse(completion.result.getBoolean("ok"));assertEquals(revision,store.get(project.id).revision);
    }
    @Implements(value=AppProtocol.class,isInAndroidSdk=false)
    public static class RecordingProtocol {
        JSONObject result;String status;
        @Implementation public void complete(JSONObject command,JSONObject result,String status){
            this.result=result;this.status=status;
        }
    }
    private ProjectStore store;private ProjectStore.Project project;private ControlService service;
    private CommandJournal journal;private AppProtocol protocol;private RecordingProtocol completion;
    @Before public void setUp()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_v3.db");
        context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        store=new ProjectStore(context);project=store.create("Service recovery");
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-video";asset.mime="video/mp4";asset.durationMs=5000;project.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="original";clip.assetId=asset.id;clip.outMs=1000;project.clips.add(clip);store.save(project);
        service=Robolectric.buildService(ControlService.class).get();
        if(service.getBaseContext()==null)ReflectionHelpers.callInstanceMethod(service,"attachBaseContext",ReflectionHelpers.ClassParameter.from(Context.class,context));
        journal=new CommandJournal(context);protocol=new AppProtocol(context,service);completion=Shadow.extract(protocol);
        field("store",store);field("prefs",context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));field("commandJournal",journal);field("protocol",protocol);
        field("editorProtocol",new EditorProtocol(context,store));
    }
    private void field(String name,Object value)throws Exception{java.lang.reflect.Field field=ControlService.class.getDeclaredField(name);field.setAccessible(true);field.set(service,value);}
    private JSONObject command(double volume)throws Exception{
        return new JSONObject().put("id","service-command-001").put("action","apply_tool")
                .put("parameters",new JSONObject().put("projectId",project.id).put("clipIndex",0).put("tool","volume").put("settings",new JSONObject().put("volume",volume)));
    }
    @After public void tearDown()throws Exception{
        if(protocol!=null)protocol.stop();
        if(service!=null){java.lang.reflect.Field field=ControlService.class.getDeclaredField("commandCompletionWatchers");field.setAccessible(true);((ExecutorService)field.get(service)).shutdownNow();}
    }
    @Test public void publicReplayAfterDeathBeforeTheEditRecoversWithoutWaitingForAJob()throws Exception{
        JSONObject command=command(.4);journal.begin(command);service.onCommand(command);
        assertNotNull("Synchronous edits must not wait for a nonexistent background job",completion.result);
        assertTrue(completion.result.toString(),completion.result.getBoolean("ok"));assertEquals(.4,store.get(project.id).clips.get(0).volume,.0001);
        assertEquals("completed",journal.terminal(command.getString("id")).getString("status"));
    }
    @Test public void publicReplayAfterCommittedEditAndBeforeAcknowledgmentUsesTheDatabaseReceipt()throws Exception{
        JSONObject command=command(.4);journal.begin(command);
        JSONObject parameters=new JSONObject(command.getJSONObject("parameters").toString()).put("_mcpCommandId",command.getString("id"));
        java.lang.reflect.Method apply=ControlService.class.getDeclaredMethod("applyTool",JSONObject.class);apply.setAccessible(true);apply.invoke(service,parameters);
        long revision=store.get(project.id).revision;service.onCommand(command);
        assertNotNull(completion.result);assertTrue(completion.result.toString(),completion.result.getBoolean("ok"));
        assertEquals(revision,store.get(project.id).revision);assertEquals("completed",completion.status);
    }
    @Test public void publicTerminalReplayRejectsDifferentParametersAndPreservesTheOriginalReceipt()throws Exception{
        service.onCommand(command(.4));assertTrue(completion.result.getBoolean("ok"));long revision=store.get(project.id).revision;
        completion.result=null;service.onCommand(command(.7));
        assertNotNull(completion.result);assertFalse("A conflicting request cannot receive the original success",completion.result.getBoolean("ok"));
        assertTrue(completion.result.getString("error").toLowerCase().contains("command"));
        assertEquals(revision,store.get(project.id).revision);assertEquals(.4,store.get(project.id).clips.get(0).volume,.0001);
        assertTrue(journal.terminal("service-command-001").getJSONObject("result").getBoolean("ok"));
    }
    @Test public void sharedEditorReplayAfterDeathUsesItsOwnTransactionReceipt()throws Exception{
        JSONObject command=new JSONObject().put("id","shared-service-command-001").put("action","editor_operation")
                .put("parameters",new JSONObject().put("projectId",project.id).put("expectedRevision",project.revision).put("commandId","shared-edit-command-001")
                        .put("operation","set_property").put("args",new JSONObject().put("clipId","original").put("property","volume").put("value",.4)));
        journal.begin(command);service.onCommand(command);assertNotNull(completion.result);assertTrue(completion.result.toString(),completion.result.getBoolean("ok"));
        long revision=store.get(project.id).revision;service.onCommand(new JSONObject(command.toString()));assertEquals(revision,store.get(project.id).revision);
    }
    @Test public void aRevokedInflightCommandIsDeniedBeforeRecovery()throws Exception{
        JSONObject command=command(.4);journal.begin(command);
        RuntimeEnvironment.getApplication().getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit()
                .putString("permission_mode","project").putString("allowed_project_id","another-project").commit();
        service.onCommand(command);assertNotNull(completion.result);assertFalse(completion.result.getBoolean("ok"));assertEquals("denied",completion.status);
        assertEquals(1,store.get(project.id).clips.get(0).volume,0);
    }
}
