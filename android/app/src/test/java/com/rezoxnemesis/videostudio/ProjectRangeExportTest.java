package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Focused future range-export regressions; not run during product implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class ProjectRangeExportTest {
    @Test public void cropPreservesLinkedProgramSpansAndIndependentSourceSpeeds() {
        ProjectStore.Project project = project();
        ProjectStore.Track audio = track(project, "audio", "audio");
        ProjectStore.Clip video = clip(project, "video", project.tracks.get(0), asset(project, "v", false), 1000, 2000, 12000, 2f);
        ProjectStore.Clip sound = clip(project, "sound", audio, asset(project, "a", true), 1000, 0, 5000, 1f);
        video.linkGroupId = sound.linkGroupId = "linked-av";
        ProjectRangeExport.Prepared prepared = ProjectRangeExport.prepare(project, 7, 2000, 5000);
        ProjectStore.Project cropped = prepared.project();
        assertEquals(0L, cropped.clip("video").startMs);
        assertEquals(3000L, cropped.clip("video").endMs());
        assertEquals(3000L, cropped.clip("sound").endMs());
        assertEquals(4000L, cropped.clip("video").inMs);
        assertEquals(10000L, cropped.clip("video").outMs);
        assertEquals(1000L, cropped.clip("sound").inMs);
        assertEquals(4000L, cropped.clip("sound").outMs);
        assertEquals("linked-av", cropped.clip("video").linkGroupId);
        assertEquals("linked-av", cropped.clip("sound").linkGroupId);
        assertEquals(1000L, video.startMs);
        assertEquals(12000L, video.outMs);
    }

    @Test public void cropRetainsSignedMotionAndAudioAuthoredClockRatherThanRestartingEffects() throws Exception {
        ProjectStore.Project project = project();
        ProjectStore.Clip before = clip(project, "visual", project.tracks.get(0), asset(project, "media", false), 1000, 0, 6000, 1f);
        before.transition = "fade";
        before.effects.put("animationDurationMs", 6000L).put("animationOffsetMs", -500L)
                .put("transitionDurationMs", 1000L)
                .put("keyframes", new JSONArray().put(new JSONObject().put("timeMs", 0).put("x", -.6).put("volume", .2))
                        .put(new JSONObject().put("timeMs", 6000).put("x", .7).put("volume", 1.7)))
                .put("stack", new JSONArray().put(new JSONObject().put("settings", new JSONObject().put("animationOffsetMs", -100L))));
        ProjectRangeExport.Prepared prepared = ProjectRangeExport.prepare(project, 7, 2250, 5000);
        ProjectStore.Clip after = prepared.project().clip("visual");
        assertEquals(750L, after.effects.getLong("animationOffsetMs"));
        assertEquals(6000L, after.effects.getLong("animationDurationMs"));
        assertEquals(1150L, after.effects.getJSONArray("stack").getJSONObject(0).getJSONObject("settings").getLong("animationOffsetMs"));
        assertEquals(before.effects.getJSONArray("keyframes").toString(), after.effects.getJSONArray("keyframes").toString());
        for (long local : new long[]{0, 300, 1200, 2700}) {
            MotionTimeline.Sample oldMotion = MotionTimeline.evaluate(before, 1250 + local);
            MotionTimeline.Sample newMotion = MotionTimeline.evaluate(after, local);
            assertEquals(oldMotion.x, newMotion.x, .00001f);
            assertEquals(oldMotion.opacity, newMotion.opacity, .00001f);
            assertEquals(AudioGainEnvelope.evaluate(before, 1250 + local), AudioGainEnvelope.evaluate(after, local), .00001f);
        }
    }

    @Test public void terminalGapIsAnExplicitRendererDurationRatherThanInventedMedia() {
        ProjectStore.Project project = project();
        clip(project, "first", project.tracks.get(0), asset(project, "one", false), 0, 0, 1000, 1f);
        clip(project, "later", project.tracks.get(0), asset(project, "two", false), 5000, 0, 1000, 1f);
        ProjectRangeExport.Prepared prepared = ProjectRangeExport.prepare(project, 7, 500, 3000);
        assertEquals(2500L, prepared.durationMs);
        assertEquals(500L, prepared.project().outputDurationMs());
        assertTrue(prepared.requiresTerminalGap);
        assertEquals(1, prepared.project().clips.size());
        assertEquals(2, prepared.project().assets.size());
        assertEquals(6000L, project.outputDurationMs());
    }

    @Test public void audioOnlyRangePreservesMutedHiddenFlagsAndOriginalAssetReferences() {
        ProjectStore.Project project = project();
        ProjectStore.Track hidden = project.tracks.get(0); hidden.visible = false;
        clip(project, "hidden-picture", hidden, asset(project, "picture", false), 0, 0, 5000, 1f);
        ProjectStore.Track audio = track(project, "dialogue", "audio");
        clip(project, "dialogue", audio, asset(project, "dialogue", true), 0, 0, 5000, 1f);
        ProjectRangeExport.Prepared prepared = ProjectRangeExport.prepare(project, 7, 1000, 3000);
        assertTrue(prepared.audioOnly);
        assertFalse(prepared.project().track(hidden.id).visible);
        assertEquals("content://fixture/dialogue", prepared.project().asset("dialogue").uri);
        assertEquals(2, prepared.project().clips.size());
    }

    @Test public void acceptedGraphIsDetachedAndOwnerAnnotationsStayOnOriginal() throws Exception {
        ProjectStore.Project project = project();
        clip(project, "clip", project.tracks.get(0), asset(project, "source", false), 0, 0, 4000, 1f);
        ProjectMarkers.add(project, new JSONObject().put("atMs", 500L).put("markerId", "owner-marker"));
        ProjectMarkers.setRange(project, new JSONObject().put("inMs", 1000L).put("outMs", 2000L));
        project.latestExportUri = "content://fixture/prior-export";
        String original = project.toJson().toString();
        ProjectRangeExport.Prepared prepared = ProjectRangeExport.prepare(project, 7, 1000, 2000);
        ProjectStore.Project external = prepared.project();
        assertEquals(0, external.markers.length());
        assertEquals(0, external.editorRange.length());
        assertEquals("", external.latestExportUri);
        external.clips.get(0).outMs = 1500L;
        assertEquals(2000L, prepared.project().clips.get(0).outMs);
        assertEquals(original, project.toJson().toString());
        assertFalse(prepared.metadata().toString().contains("content://"));
    }

    @Test public void staleRevisionAndUnrepresentableOrAllGapRangesReject() {
        ProjectStore.Project project = project();
        clip(project, "early", project.tracks.get(0), asset(project, "early", false), 0, 0, 1000, 1f);
        clip(project, "late", project.tracks.get(0), asset(project, "late", false), 5000, 0, 1000, 1f);
        try { ProjectRangeExport.prepare(project, 6, 0, 1000); fail("Expected stale revision"); }
        catch (ProjectStore.RevisionConflictException expected) { assertEquals(7L, expected.actualRevision); }
        reject(project, 2000, 3000);
        reject(project, -1, 1000);
        reject(project, 1000, 1000);
        reject(project, 0, 6001);
        ProjectStore.Project slow = project();
        clip(slow, "slow", slow.tracks.get(0), asset(slow, "slow-source", false), 0, 0, 10, .1f);
        reject(slow, 1, 2);
    }

    private static void reject(ProjectStore.Project project, long in, long out) {
        try { ProjectRangeExport.prepare(project, project.revision, in, out); fail("Expected unsupported range"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    private static ProjectStore.Project project() {
        ProjectStore.Project project = new ProjectStore.Project();
        project.id = "range-project"; project.name = "Range fixture"; project.revision = 7;
        track(project, "video", "video");
        return project;
    }

    private static ProjectStore.Track track(ProjectStore.Project project, String id, String type) {
        ProjectStore.Track track = new ProjectStore.Track();
        track.id = id; track.name = id; track.type = type; track.order = project.tracks.size();
        project.tracks.add(track); return track;
    }

    private static ProjectStore.Asset asset(ProjectStore.Project project, String id, boolean audio) {
        ProjectStore.Asset asset = new ProjectStore.Asset();
        asset.id = id; asset.uri = "content://fixture/" + id; asset.name = id;
        asset.mime = audio ? "audio/mp4" : "video/mp4"; asset.durationMs = 20000L; asset.hasAudio = audio;
        project.assets.add(asset); return asset;
    }

    private static ProjectStore.Clip clip(ProjectStore.Project project, String id, ProjectStore.Track track,
                                          ProjectStore.Asset asset, long start, long in, long out, float speed) {
        ProjectStore.Clip clip = new ProjectStore.Clip();
        clip.id = id; clip.trackId = track.id; clip.assetId = asset.id;
        clip.startMs = start; clip.inMs = in; clip.outMs = out; clip.speed = speed;
        project.clips.add(clip); return clip;
    }
}
