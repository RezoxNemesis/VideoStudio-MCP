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
@Config(sdk = 33, manifest = Config.NONE)
public class EditorEngineTest {
    private ProjectStore store;
    private EditorEngine editor;
    private ProjectStore.Project project;

    @Before public void setup() {
        Context c = RuntimeEnvironment.getApplication();
        c.deleteDatabase("videostudio_v3.db");
        c.getSharedPreferences("videostudio_native_v1", Context.MODE_PRIVATE).edit().clear().commit();
        store = new ProjectStore(c); editor = new EditorEngine(store);
        project = store.create("Edit test");
        ProjectStore.Asset a = new ProjectStore.Asset();
        a.id = "media"; a.uri = "file:///owned.mp4"; a.mime = "video/mp4"; a.name = "Media"; a.durationMs = 10000;
        project.assets.add(a);
        ProjectStore.Clip clip = new ProjectStore.Clip();
        clip.id = "clip"; clip.assetId = a.id; clip.inMs = 1000; clip.outMs = 9000; clip.speed = 2;
        project.clips.add(clip); store.save(project);
    }
    private ProjectStore.Project edit(String op, JSONObject args) {
        project = editor.execute(project.id, project.revision, "owner", "", op, args);
        return project;
    }
    private JSONObject args(String json) throws Exception { return new JSONObject(json); }

    @Test public void splitUsesPlayheadAndSourceSpeedAndPreservesKeyframes() throws Exception {
        edit("set_keyframe", args("{\"clipId\":\"clip\",\"property\":\"scale\",\"timeMs\":3000,\"value\":2}"));
        edit("split_clip", args("{\"clipId\":\"clip\",\"atMs\":2000}"));
        assertEquals(2, project.clips.size());
        assertEquals(5000, project.clips.get(0).outMs);
        assertEquals(5000, project.clips.get(1).inMs);
        assertEquals(2000, project.clips.get(1).startMs);
        assertEquals(4000, project.outputDurationMs());
        assertEquals(1000, project.clips.get(1).keyframes.getJSONObject(0).getLong("timeMs"));
    }

    @Test public void rippleDeleteClosesOnlyTheChosenTrack() throws Exception {
        edit("duplicate_clip", args("{\"clipId\":\"clip\"}"));
        String second = project.clips.get(1).id;
        edit("remove_clip", args("{\"clipId\":\"clip\",\"ripple\":true}"));
        assertEquals(1, project.clips.size()); assertEquals(second, project.clips.get(0).id);
        assertEquals(0, project.clips.get(0).startMs);
    }

    @Test public void lockedTrackRejectsOwnerAndAgentTimelineMutations() throws Exception {
        edit("set_track", args("{\"trackId\":\"video-1\",\"locked\":true}"));
        try { edit("move_clip", args("{\"clipId\":\"clip\",\"startMs\":500}")); fail("Locked move accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("locked")); }
        assertEquals(0, store.get(project.id).clips.get(0).startMs);
    }

    @Test public void trimCannotReadPastVideoSourceEnd() throws Exception {
        try { edit("trim_clip", args("{\"clipId\":\"clip\",\"inMs\":1000,\"outMs\":12000}")); fail("Out-of-source trim accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(9000, store.get(project.id).clips.get(0).outMs);
    }

    @Test public void repeatedCommandDoesNotDuplicateClipAfterStoreRestart() throws Exception {
        long revision = project.revision;
        ProjectStore.Project first = editor.execute(project.id, revision, "agent", "command-1", "duplicate_clip", args("{\"clipId\":\"clip\"}"));
        EditorEngine restarted = new EditorEngine(new ProjectStore(RuntimeEnvironment.getApplication()));
        ProjectStore.Project replay = restarted.execute(project.id, revision, "agent", "command-1", "duplicate_clip", args("{\"clipId\":\"clip\"}"));
        assertEquals(first.revision, replay.revision); assertEquals(2, store.get(project.id).clips.size());
    }

    @Test public void undoRedoAreDurableNewRevisions() throws Exception {
        edit("rename_project", args("{\"name\":\"Renamed\"}"));
        long revision = project.revision;
        project = store.undo(project.id, revision);
        assertEquals("Edit test", project.name); assertEquals(revision + 1, project.revision);
        project = new ProjectStore(RuntimeEnvironment.getApplication()).redo(project.id, project.revision);
        assertEquals("Renamed", project.name); assertEquals(revision + 2, project.revision);
    }

    @Test public void namedSnapshotRestoreRetainsLaterImportedMedia() throws Exception {
        String snapshot = store.snapshot(project.id, "Before edits");
        ProjectStore.Asset later = new ProjectStore.Asset();
        later.id = "later"; later.name = "Later"; later.uri = "file:///later.png"; later.mime = "image/png";
        project.assets.add(later); store.save(project);
        edit("rename_project", args("{\"name\":\"Changed\"}"));
        project = store.restore(project.id, project.revision, snapshot);
        assertEquals("Edit test", project.name); assertNotNull(project.asset("later"));
    }

    @Test public void keyframesAreSortedAndSameTimeReplacesValue() throws Exception {
        edit("set_keyframe", args("{\"clipId\":\"clip\",\"property\":\"scale\",\"timeMs\":2000,\"value\":3}"));
        edit("set_keyframe", args("{\"clipId\":\"clip\",\"property\":\"scale\",\"timeMs\":0,\"value\":1}"));
        edit("set_keyframe", args("{\"clipId\":\"clip\",\"property\":\"scale\",\"timeMs\":2000,\"value\":2}"));
        assertEquals(2, project.clips.get(0).keyframes.length());
        assertEquals(0, project.clips.get(0).keyframes.getJSONObject(0).getLong("timeMs"));
        assertEquals(1.5, EditorEngine.valueAt(project.clips.get(0), "scale", 1000, 1), .00001);
    }

    @Test public void duplicateShiftsLaterClipsToAvoidSilentOverlap() throws Exception {
        edit("duplicate_clip", args("{\"clipId\":\"clip\"}"));
        edit("duplicate_clip", args("{\"clipId\":\"clip\"}"));
        java.util.List<Long> starts = new java.util.ArrayList<>();
        for (ProjectStore.Clip c : project.clips) starts.add(c.startMs);
        java.util.Collections.sort(starts);
        assertEquals(java.util.Arrays.asList(0L, 4000L, 8000L), starts);
    }

    @Test public void unknownAssetNeverBecomesAClip() throws Exception {
        try { edit("add_clip", args("{\"assetId\":\"gallery-unknown\"}")); fail("Unknown asset accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(1, store.get(project.id).clips.size());
    }

    @Test public void nonFinitePropertyDoesNotCommit() throws Exception {
        JSONObject a = args("{\"clipId\":\"clip\",\"property\":\"scale\"}");
        a.put("value", "NaN"); long revision = project.revision;
        try { edit("set_property", a); fail("Nonfinite property accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(revision, store.get(project.id).revision);
    }
}
