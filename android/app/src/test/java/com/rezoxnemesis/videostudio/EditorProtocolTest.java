package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class EditorProtocolTest {
    @Test public void typedTimelinePlanUsesTheSameAtomicSourceAndHistoryRules()throws Exception{
        JSONObject scene=new JSONObject().put("assetId","source").put("outMs",1000).put("speed",.5)
                .put("effects",new JSONObject().put("effectPreset","gaussian_blur")).put("title","Typed plan");
        JSONObject receipt=protocol.execute("editor_operation",request("typed-plan-command-001").put("operation","replace_timeline")
                .put("args",new JSONObject().put("clips",new JSONArray().put(scene).put(new JSONObject().put("assetId","source").put("outMs",1000)))));
        ProjectStore.Project edited=store.get(project.id);assertEquals(2000,edited.clips.get(1).startMs);assertEquals(3000,edited.outputDurationMs());
        assertEquals("gaussian_blur",edited.clips.get(0).effects.getString("effectPreset"));assertEquals(project.revision+1,receipt.getLong("revision"));
        assertEquals("clip",store.undo(project.id,edited.revision).clips.get(0).id);
    }
    @Test public void bulkPresetCannotModifyAnUnselectedClipOrPartiallyStyleLockedTargets()throws Exception{
        ProjectStore.Asset hidden=new ProjectStore.Asset();hidden.id="private-source";hidden.mime="video/mp4";hidden.durationMs=5000;project.assets.add(hidden);
        ProjectStore.Track track=new ProjectStore.Track();track.id="second-track";track.type="video";track.locked=true;project.tracks.add(track);
        ProjectStore.Clip privateClip=new ProjectStore.Clip();privateClip.id="private-clip";privateClip.assetId=hidden.id;privateClip.trackId=track.id;privateClip.outMs=1000;project.clips.add(privateClip);store.save(project);
        JSONObject args=new JSONObject().put("clipIds",new JSONArray().put("clip").put("private-clip")).put("preset","noir");
        long before=project.revision;
        try{protocol.execute("editor_operation",request("locked-bulk-command-001").put("operation","apply_creator_preset").put("args",args));fail("Locked bulk target must fail");}catch(IllegalArgumentException expected){}
        assertEquals(before,store.get(project.id).revision);assertFalse(store.get(project.id).clips.get(0).effects.has("effectPreset"));
        RuntimeEnvironment.getApplication().getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit()
                .putString("permission_mode","selected_assets").putString("allowed_project_id",project.id).putString("allowed_asset_ids","[\"source\"]").commit();
        try{protocol.execute("editor_operation",request("private-bulk-command-001").put("operation","apply_creator_preset").put("args",args));fail("Unselected bulk target must fail");}catch(SecurityException expected){}
        assertEquals(before,store.get(project.id).revision);
        JSONObject accepted=protocol.execute("editor_operation",request("selected-bulk-command-001").put("operation","apply_creator_preset")
                .put("args",new JSONObject().put("clipIds",new JSONArray().put("clip")).put("preset","noir")));
        assertTrue(accepted.getBoolean("ok"));assertEquals("noir",store.get(project.id).clips.get(0).effects.getString("effectPreset"));
    }
    private ProjectStore store;
    private EditorProtocol protocol;
    private ProjectStore.Project project;
    @Before public void setup(){
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_v3.db");
        context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        store=new ProjectStore(context);protocol=new EditorProtocol(context,store);project=store.create("Owner project");
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source";asset.uri="file:///owned.mp4";asset.name="Source";asset.mime="video/mp4";asset.durationMs=10000;
        project.assets.add(asset);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip";clip.assetId=asset.id;clip.outMs=2000;project.clips.add(clip);store.save(project);
    }
    private JSONObject request(String command)throws Exception{return new JSONObject().put("projectId",project.id).put("expectedRevision",store.get(project.id).revision).put("commandId",command);}
    private JSONObject operation(String name,String args)throws Exception{return new JSONObject().put("operation",name).put("args",new JSONObject(args));}
    @Test public void packagedContractAndGraphExposeTheSameRevision()throws Exception{
        assertEquals(5,protocol.describe().getInt("schemaVersion"));assertTrue(protocol.describe().getJSONObject("operations").has("roll_clip"));
        JSONObject response=protocol.execute("project_query",new JSONObject().put("projectId",project.id));
        assertEquals(project.revision,response.getLong("revision"));assertEquals(project.revision,response.getJSONObject("project").getLong("revision"));
    }
    @Test public void batchCommitsOneRevisionAndOneUndoStep()throws Exception{
        long before=project.revision;
        JSONObject p=request("batch-command-001").put("operations",new JSONArray().put(operation("rename_project","{\"name\":\"Agent edit\"}")).put(operation("move_clip","{\"clipId\":\"clip\",\"startMs\":1000}")));
        JSONObject receipt=protocol.execute("editor_batch",p);assertEquals(before+1,receipt.getLong("revision"));
        ProjectStore.Project changed=store.get(project.id);assertEquals("Agent edit",changed.name);assertEquals(1000,changed.clip("clip").startMs);
        protocol.execute("editor_history",request("undo-command-001").put("operation","undo"));
        ProjectStore.Project undone=store.get(project.id);assertEquals("Owner project",undone.name);assertEquals(0,undone.clip("clip").startMs);
    }
    @Test public void failedBatchRollsBackEarlierEditsAndRevision()throws Exception{
        JSONObject p=request("batch-command-002").put("operations",new JSONArray().put(operation("rename_project","{\"name\":\"Should rollback\"}")).put(operation("move_clip","{\"clipId\":\"missing\",\"startMs\":1000}")));
        try{protocol.execute("editor_batch",p);fail("Invalid batch committed");}catch(IllegalArgumentException expected){}
        assertEquals(project.revision,store.get(project.id).revision);assertEquals("Owner project",store.get(project.id).name);
    }
    @Test public void staleRevisionAndUnknownArgumentsLeaveOwnerGraphUntouched()throws Exception{
        JSONObject stale=request("stale-command-01").put("expectedRevision",project.revision-1).put("operation","move_clip").put("args",new JSONObject().put("clipId","clip").put("startMs",1000));
        try{protocol.execute("editor_operation",stale);fail("Stale command committed");}catch(ProjectStore.RevisionConflict expected){assertEquals(project.revision,expected.actualRevision);}
        JSONObject uri=request("unknown-uri-001").put("operation","add_clip").put("args",new JSONObject().put("assetId","source").put("uri","content://gallery/all"));
        try{protocol.execute("editor_operation",uri);fail("Unknown URI accepted");}catch(IllegalArgumentException expected){}
        JSONObject value=request("invalid-value-01").put("operation","set_property").put("args",new JSONObject().put("clipId","clip").put("property","opacity").put("value",2));
        try{protocol.execute("editor_operation",value);fail("Out of range property accepted");}catch(IllegalArgumentException expected){}
        assertEquals(project.revision,store.get(project.id).revision);assertEquals(0,store.get(project.id).clip("clip").startMs);
    }
    @Test public void undoReplayAfterOwnerEditReturnsOriginalReceiptWithoutUndoingAgain()throws Exception{
        protocol.execute("editor_operation",request("rename-command-01").put("operation","rename_project").put("args",new JSONObject().put("name","Agent name")));
        JSONObject undo=request("history-command-01").put("operation","undo");JSONObject first=protocol.execute("editor_history",undo);
        ProjectStore.Project current=store.get(project.id);new EditorEngine(store).execute(project.id,current.revision,"owner","","rename_project",new JSONObject().put("name","Owner latest"));
        EditorProtocol restarted=new EditorProtocol(RuntimeEnvironment.getApplication(),new ProjectStore(RuntimeEnvironment.getApplication()));
        assertEquals(first.getLong("revision"),restarted.execute("editor_history",undo).getLong("revision"));assertEquals("Owner latest",store.get(project.id).name);
    }
    @Test public void snapshotReplayIsStableAndCannotReuseCommandForDifferentName()throws Exception{
        JSONObject snapshot=request("snapshot-command-1").put("operation","snapshot").put("name","Checkpoint");
        JSONObject first=protocol.execute("editor_history",snapshot);
        new EditorEngine(store).execute(project.id,project.revision,"owner","","rename_project",new JSONObject().put("name","Owner latest"));
        JSONObject replay=protocol.execute("editor_history",snapshot);assertEquals(first.getString("snapshotId"),replay.getString("snapshotId"));assertEquals(first.getLong("revision"),replay.getLong("revision"));assertEquals(1,store.snapshots(project.id).length());
        try{protocol.execute("editor_history",new JSONObject(snapshot.toString()).put("name","Different"));fail("Different snapshot reused a command");}catch(IllegalArgumentException expected){}
    }
}
