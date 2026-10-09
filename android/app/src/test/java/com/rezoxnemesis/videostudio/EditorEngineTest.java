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
        assertEquals(2, EditorEngine.valueAt(project.clips.get(1),"scale",0,1),.00001);
        assertEquals(2, EditorEngine.valueAt(project.clips.get(0),"scale",1999,1),.00001);
    }

    @Test public void splitPreservesEverySampleOfAnEasedCurve() throws Exception {
        for (String easing : new String[]{"linear","ease_in","ease_out","ease_in_out","hold"}) {
            setup();
            JSONObject first=args("{\"clipId\":\"clip\",\"property\":\"scale\",\"timeMs\":0,\"value\":1}");
            first.put("easing",easing);edit("set_keyframe",first);
            edit("set_keyframe",args("{\"clipId\":\"clip\",\"property\":\"scale\",\"timeMs\":4000,\"value\":3}"));
            ProjectStore.Clip original=ProjectStore.Clip.fromJson(project.clips.get(0).toJson());
            edit("split_clip",args("{\"clipId\":\"clip\",\"atMs\":1700}"));
            for (long time : new long[]{0,250,1000,1699,1700,2000,3200,3999,4000}) {
                ProjectStore.Clip side=project.clips.get(time<1700?0:1);
                assertEquals(easing+" at "+time,EditorEngine.valueAt(original,"scale",time,1),
                        EditorEngine.valueAt(side,"scale",time-side.startMs,1),.000001);
            }
        }
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

    @Test public void autonomousEditsCannotBypassRevisionChecks() throws Exception {
        try{editor.execute(project.id,-1,"agent","agent-request-001","move_clip",args("{\"clipId\":\"clip\",\"startMs\":2000}"));fail("Agent bypassed owner revision");}
        catch(IllegalArgumentException expected){}
        assertEquals(0,store.get(project.id).clip("clip").startMs);
    }

    @Test public void reusedCommandIdWithDifferentEditIsAConflict() throws Exception {
        editor.execute(project.id,project.revision,"agent","agent-request-001","move_clip",args("{\"clipId\":\"clip\",\"startMs\":2000}"));
        try{editor.execute(project.id,project.revision,"agent","agent-request-001","move_clip",args("{\"clipId\":\"clip\",\"startMs\":4000}"));fail("Different mutation reused receipt");}
        catch(IllegalArgumentException expected){}
        assertEquals(2000,store.get(project.id).clip("clip").startMs);
    }

    @Test public void rollingBoundaryKeepsTheSequenceEndAndBothSourceRangesValid() throws Exception {
        edit("duplicate_clip",args("{\"clipId\":\"clip\"}"));
        long end=project.outputDurationMs();
        edit("roll_clip",args("{\"clipId\":\"clip\",\"deltaMs\":500}"));
        assertEquals(4500,project.clip("clip").outputDurationMs());
        assertEquals(4500,project.clips.get(1).startMs);assertEquals(2000,project.clips.get(1).inMs);
        assertEquals(end,project.outputDurationMs());
    }

    @Test public void slidingMiddleClipPreservesItsSourceAndOuterSequenceEnd()throws Exception{
        edit("duplicate_clip",args("{\"clipId\":\"clip\"}"));
        String middle=project.clips.get(1).id;edit("duplicate_clip",args("{\"clipId\":\""+middle+"\"}"));
        edit("slide_clip",args("{\"clipId\":\""+middle+"\",\"deltaMs\":500}"));
        assertEquals(4500,project.clip(middle).startMs);assertEquals(1000,project.clip(middle).inMs);assertEquals(9000,project.clip(middle).outMs);
        assertEquals(12000,project.outputDurationMs());
    }
    @Test public void audioDspPersistsAndUndoRestoresPreviousSettings()throws Exception{
        edit("set_audio_effects",args("{\"clipId\":\"clip\",\"settings\":{\"lowDb\":6,\"thresholdDb\":-18,\"ratio\":3}}"));
        assertEquals(6,new ProjectStore(RuntimeEnvironment.getApplication()).get(project.id).clip("clip").effects.getJSONObject("audioDsp").getDouble("lowDb"),.00001);
        project=store.undo(project.id,project.revision);assertFalse(project.clip("clip").effects.has("audioDsp"));
        try{edit("set_audio_effects",args("{\"clipId\":\"clip\",\"settings\":{\"delayFeedback\":2}}"));fail("Invalid delay committed");}catch(IllegalArgumentException expected){}
    }

    @Test public void creatorStylesUseDurableEditorHistory() throws Exception {
        try { edit("set_creator_style", args("{\"clipId\":\"clip\",\"settings\":{\"colorPreset\":\"warm_film\",\"motionPreset\":\"push_in\",\"fontFamily\":\"monospace\",\"textAnimation\":\"typewriter\"}}")); }
        catch (IllegalArgumentException missing) { fail("Shared creator style operation is missing: "+missing.getMessage()); }
        ProjectStore.Clip persisted = new ProjectStore(RuntimeEnvironment.getApplication()).get(project.id).clip("clip");
        assertEquals("warm_film", persisted.effects.getString("colorPreset"));
        assertEquals("typewriter", persisted.effects.getString("textAnimation"));
        project = store.undo(project.id, project.revision);
        assertFalse(project.clip("clip").effects.has("colorPreset"));
        assertFalse(project.clip("clip").effects.has("textAnimation"));
    }

    @Test public void creatorStylesCannotCommitUnknownPresetsOrEditLockedTracks() throws Exception {
        long before=project.revision;
        try { edit("set_creator_style", args("{\"clipId\":\"clip\",\"settings\":{\"motionPreset\":\"invented_motion\"}}")); fail("Unknown preset accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(before, store.get(project.id).revision);
        edit("set_track",args("{\"trackId\":\"video-1\",\"locked\":true}"));
        try { edit("set_creator_style",args("{\"clipId\":\"clip\",\"settings\":{\"colorPreset\":\"noir\"}}"));fail("Locked style edit accepted"); }
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("locked"));}
    }

}
