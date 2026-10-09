package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class OwnerToolUiTest {
    @Test public void foregroundBulkPlanCannotReplaceLockedOwnerClips()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,true);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            JSONObject params=new JSONObject().put("projectId",p.id).put("clips",new org.json.JSONArray().put(new JSONObject().put("assetId","image").put("outMs",1000)));
            controller.get().onCommand(new JSONObject().put("id","foreground-plan-001").put("action","apply_edit_plan").put("parameters",params));
            assertEquals("original",store.get(p.id).clips.get(0).id);assertEquals(p.revision,store.get(p.id).revision);
        }
    }
    @Test public void foregroundCompoundEditRollsBackThePlanWhenPresetFails()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            JSONObject params=new JSONObject().put("projectId",p.id).put("preset","invalid-provider").put("clips",new org.json.JSONArray().put(new JSONObject().put("assetId","image").put("outMs",1000)));
            controller.get().onCommand(new JSONObject().put("id","foreground-compound-001").put("action","autonomous_edit").put("parameters",params));
            assertEquals("original",store.get(p.id).clips.get(0).id);assertEquals(p.revision,store.get(p.id).revision);
        }
    }
    @Test public void stoppedForegroundControlDeniesLeasedEditsButOwnerToolsRemainAvailable()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("protocol");field.setAccessible(true);
            ((AppProtocol)field.get(controller.get())).setControlPaused(true);
            JSONObject params=new JSONObject().put("projectId",p.id).put("clipIndex",0).put("tool","title").put("settings",new JSONObject().put("text","Must not edit"));
            controller.get().onCommand(new JSONObject().put("id","stopped-command-001").put("action","apply_tool").put("parameters",params));
            assertEquals("",store.get(p.id).clips.get(0).title);
            apply(controller.get(),store.get(p.id),"Duplicate");
            assertEquals("STOP only pauses remote control",2,store.get(p.id).clips.size());
        }
    }
    @Test public void ownerEffectsClearMigratedBlurAndUndoRestoresIt()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,false);
        p.clips.get(0).effects.put("effectPreset","gaussian_blur");store.save(p);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            apply(controller.get(),p,"Effects");
            android.app.AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);android.widget.ListView choices=dialog.getListView();
            choices.performItemClick(null,0,choices.getAdapter().getItemId(0));
            ProjectStore.Project cleared=store.get(p.id);
            assertEquals("none",cleared.clips.get(0).effects.getString("effectPreset"));
            assertEquals("gaussian_blur",store.undo(p.id,cleared.revision).clips.get(0).effects.getString("effectPreset"));
        }
    }
    private ProjectStore.Project fixture(ProjectStore store,boolean locked){
        ProjectStore.Project p=store.create("Owner tools");
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="image";a.mime="image/png";a.uri="file:///not-loaded.png";p.assets.add(a);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="original";clip.assetId=a.id;clip.outMs=1000;p.clips.add(clip);
        p.tracks.get(0).locked=locked;store.save(p);return p;
    }
    private void apply(MainActivity activity,ProjectStore.Project p,String tool)throws Exception{
        java.lang.reflect.Field selected=MainActivity.class.getDeclaredField("selectedClip");selected.setAccessible(true);selected.set(activity,p.clips.get(0));
        java.lang.reflect.Method apply=MainActivity.class.getDeclaredMethod("applyTool",String.class);apply.setAccessible(true);apply.invoke(activity,tool);
    }
    @Test public void ownerToolDuplicateCannotBypassTrackLock()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,true);long revision=p.revision;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            apply(controller.get(),p,"Duplicate");
            assertEquals("Owner tools must respect the same track lock as MCP",1,store.get(p.id).clips.size());
            assertEquals(revision,store.get(p.id).revision);
        }
    }
    @Test public void ownerToolDuplicatePlacesItsCopyAfterTheSource()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            apply(controller.get(),p,"Duplicate");ProjectStore.Project result=store.get(p.id);
            assertEquals(2,result.clips.size());assertEquals(1000,result.clips.get(1).startMs);
            assertEquals(2000,result.outputDurationMs());
            assertEquals(1,store.undo(p.id,result.revision).clips.size());
        }
    }
    @Test public void foregroundLegacyCommandsCannotBypassTrackLocks()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,true);long revision=p.revision;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            JSONObject parameters=new JSONObject().put("projectId",p.id).put("clipIndex",0).put("tool","volume").put("settings",new JSONObject().put("volume",.4));
            controller.get().onCommand(new JSONObject().put("id","foreground-command-001").put("action","apply_tool").put("parameters",parameters));
            assertEquals(1,store.get(p.id).clips.get(0).volume,0);assertEquals(revision,store.get(p.id).revision);
        }
    }
    @Test public void foregroundLegacyReplayPreservesSubsequentOwnerEdits()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            JSONObject parameters=new JSONObject().put("projectId",p.id).put("clipIndex",0).put("tool","title").put("settings",new JSONObject().put("text","Agent title"));
            JSONObject command=new JSONObject().put("id","foreground-command-002").put("action","apply_tool").put("parameters",parameters);
            controller.get().onCommand(command);ProjectStore.Project edited=store.get(p.id);
            new EditorEngine(store).execute(p.id,edited.revision,"owner","","set_title",new JSONObject().put("clipId","original").put("text","Owner title"));
            long revision=store.get(p.id).revision;controller.get().onCommand(new JSONObject(command.toString()));
            assertEquals("Owner title",store.get(p.id).clips.get(0).title);assertEquals(revision,store.get(p.id).revision);
            java.lang.reflect.Field active=MainActivity.class.getDeclaredField("activeProject");active.setAccessible(true);
            assertEquals("Owner title",((ProjectStore.Project)active.get(controller.get())).clips.get(0).title);
        }
    }
    @Test public void foregroundLegacyEmptyProjectIdStillUsesTheActiveProject()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            JSONObject parameters=new JSONObject().put("projectId","").put("clipIndex",0).put("tool","title").put("settings",new JSONObject().put("text","Active project title"));
            controller.get().onCommand(new JSONObject().put("id","foreground-command-003").put("action","apply_tool").put("parameters",parameters));
            assertEquals("Active project title",store.get(p.id).clips.get(0).title);
        }
    }
}
