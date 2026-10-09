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
