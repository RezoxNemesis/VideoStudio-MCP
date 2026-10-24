package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Native editing admission tests. They verify saved Media3-compatible motion
 * layers and source isolation, not actual phone GPU playback or export.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class StudioKineticDirectorTest {
    private static final String OP = "cinematic-case-0001";

    private ProjectStore.Project sample(ProjectStore store) {
        ProjectStore.Project source = store.create("Test subject 2");
        ProjectStore.Asset still = new ProjectStore.Asset();
        still.id = "source-image"; still.name = "Portrait.png"; still.mime = "image/png";
        still.uri = "file:///source/portrait.png"; still.durationMs = 0;
        source.assets.add(still);
        ProjectStore.Asset song = new ProjectStore.Asset();
        song.id = "source-audio"; song.name = "Song.wav"; song.mime = "audio/wav";
        song.uri = "file:///source/song.wav"; song.durationMs = 30000;
        source.assets.add(song);
        for (int i = 0; i < 4; i++) {
            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = "original-clip-" + i; clip.assetId = still.id;
            clip.inMs = 0; clip.outMs = 4500;
            source.clips.add(clip);
        }
        StudioLayer music = new StudioLayer();
        music.type = StudioLayer.AUDIO;
        music.id = "original-song-lane";
        music.assetId = song.id;
        music.startMs = 0; music.durationMs = 18000;
        source.studioLayers.add(music);
        store.save(source);
        return store.get(source.id);
    }

    private JSONObject request(ProjectStore.Project source) throws Exception {
        return new JSONObject().put("projectId", source.id)
                .put("expectedUpdatedAt", source.updatedAt)
                .put("style", "editorial").put("bpm", 120)
                .put("operationId", OP)
                .put("titles", new JSONArray().put("STAY FOCUSED")
                        .put("CREATE MOTION").put("EVERY FRAME COUNTS"));
    }

    @Test public void originalVectorAccentsArePartOfTheSameEditableSourcePreservingEdit() throws Exception {
        try (ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication())) {
            ProjectStore.Project source = sample(store);
            String old = SketchDocument.canonical(source.toJson());
            JSONObject req=request(source).put("shapeAccents",true)
                    .put("operationId","accent-case-20261024");
            JSONObject plan=StudioKineticDirector.plan(source,req);
            assertTrue(plan.getBoolean("shapeAccentsRequested"));
            assertEquals(3,plan.getInt("shapeAccentsCreated"));
            assertEquals(7,plan.getInt("layerCount"));
            JSONObject receipt=StudioKineticDirector.apply(store,req);
            ProjectStore.Project edited=store.get(receipt.getString("projectId"));
            assertEquals(7,edited.studioLayers.size());
            for(int i=1;i<edited.studioLayers.size();i+=2){
                assertEquals("Accent should be below its associated title",
                        StudioLayer.SHAPE,edited.studioLayers.get(i).type);
                assertEquals(StudioLayer.TITLE,edited.studioLayers.get(i+1).type);
                assertEquals(4,edited.studioLayers.get(i).motionKeyframes.length());
            }
            StudioLayerContract.fromJson(edited,StudioLayerContract.toJson(edited.studioLayers));
            assertEquals(old,SketchDocument.canonical(store.get(source.id).toJson()));
            assertEquals(receipt.getString("projectId"),
                    StudioKineticDirector.apply(store,req).getString("projectId"));
        }
    }

    @Test public void dryRunDoesNotTouchTheOriginalProject() throws Exception {
        try (ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication())) {
            ProjectStore.Project source = sample(store);
            String before = SketchDocument.canonical(source.toJson());
            JSONObject planned = StudioKineticDirector.plan(source, request(source));
            assertTrue(planned.getBoolean("ok"));
            assertEquals("manual_bpm_grid", planned.getString("timingProvenance"));
            assertEquals(3, planned.getInt("titleCount"));
            assertEquals(4, planned.getInt("layerCount"));
            assertFalse(planned.getBoolean("rendered"));
            assertFalse(planned.getBoolean("originalProjectModified"));
            assertEquals(before, SketchDocument.canonical(store.get(source.id).toJson()));
        }
    }

    @Test public void appliedKineticEditIsNewEditableProjectAndIdempotent() throws Exception {
        try (ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication())) {
            ProjectStore.Project source = sample(store);
            JSONObject req = request(source);
            String before = SketchDocument.canonical(source.toJson());
            JSONObject applied = StudioKineticDirector.apply(store, req);
            assertFalse(applied.getBoolean("reused"));
            assertFalse(applied.getBoolean("rendered"));
            assertFalse(applied.getBoolean("previewVerified"));
            String id = applied.getString("projectId");
            assertNotEquals(source.id, id);
            ProjectStore.Project output = store.get(id);
            assertNotNull(output);
            assertEquals(source.clips.size(), output.clips.size());
            assertEquals(source.assets.size(), output.assets.size());
            assertEquals(4, output.studioLayers.size());
            assertEquals("original-song-lane", output.studioLayers.get(0).id);
            for (int i = 1; i < output.studioLayers.size(); i++) {
                StudioLayer lane = output.studioLayers.get(i);
                assertEquals(StudioLayer.TITLE, lane.type);
                assertTrue(lane.motionKeyframes.length() >= 4);
                assertTrue(lane.startMs + lane.durationMs <= output.outputDurationMs());
                StudioLayerContract.validate(output, lane);
            }
            assertEquals(before, SketchDocument.canonical(store.get(source.id).toJson()));
            JSONObject repeat = StudioKineticDirector.apply(store, req);
            assertTrue(repeat.getBoolean("reused"));
            assertEquals(id, repeat.getString("projectId"));
            assertEquals(before, SketchDocument.canonical(store.get(source.id).toJson()));
        }
    }

    @Test public void cannotOverwriteOutputByReusingOperationIdWithDifferentCreativeBrief() throws Exception {
        try (ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication())) {
            ProjectStore.Project source = sample(store);
            JSONObject first = request(source);
            StudioKineticDirector.apply(store, first);
            JSONObject different = request(source);
            different.put("titles", new JSONArray().put("DIFFERENT STORY"));
            IllegalStateException problem = assertThrows(IllegalStateException.class,
                    () -> StudioKineticDirector.apply(store, different));
            assertTrue(problem.getMessage().contains("KINETIC_OPERATION_CONFLICT"));
        }
    }

    @Test public void editingAfterTheExpectedRevisionIsRejected() throws Exception {
        try (ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication())) {
            ProjectStore.Project source = sample(store);
            JSONObject old = request(source);
            source.name = "Changed in the editor";
            store.save(source);
            assertThrows(IllegalStateException.class,
                    () -> StudioKineticDirector.apply(store, old));
        }
    }

    @Test public void unverifiedAudioAnalysisIsNeverRelabelledAsRealSync() throws Exception {
        try (ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication())) {
            ProjectStore.Project source = sample(store);
            JSONObject broken = request(source);
            broken.remove("bpm");
            broken.put("audioEnvelope", new JSONObject()
                    .put("schema", "videostudio.audio.envelope.v1")
                    .put("projectId", source.id)
                    .put("sourceProjectUpdatedAt", source.updatedAt)
                    .put("sourceUnchanged", true)
                    .put("coverage", new JSONObject().put("complete", false)));
            assertThrows(IllegalArgumentException.class,
                    () -> StudioKineticDirector.plan(source, broken));
        }
    }

    @Test public void sourceClipBoundariesStayTruthfullyLabelledWhenNoMusicMapExists() throws Exception {
        try (ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication())) {
            ProjectStore.Project source = sample(store);
            JSONObject req = request(source);
            req.remove("bpm");
            JSONObject plan = StudioKineticDirector.plan(source, req);
            assertEquals("clip_boundaries", plan.getString("timingProvenance"));
            assertEquals("none", plan.getString("musicalBeatCertainty"));
        }
    }
}
