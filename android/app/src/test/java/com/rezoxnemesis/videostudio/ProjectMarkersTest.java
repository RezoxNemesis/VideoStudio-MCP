package com.rezoxnemesis.videostudio;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Future source regressions. Not executed during the owner's implementation phase. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class ProjectMarkersTest {
    private ProjectStore store;
    private ProjectStore.Project baseline;

    @Before public void projectWithProgram() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("videostudio_v3.db");
        store = new ProjectStore(context);
        ProjectStore.Project empty = store.create("Marker fixture");
        baseline = store.edit(empty.id, empty.revision, "Source fixture", project -> {
            ProjectStore.Asset asset = new ProjectStore.Asset();
            asset.id = "asset-video"; asset.uri = "content://fixture/video";
            asset.name = "Source video"; asset.mime = "video/mp4"; asset.durationMs = 10000L;
            project.assets.add(asset);
            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = "clip-video"; clip.assetId = asset.id; clip.trackId = project.tracks.get(0).id;
            clip.startMs = 0L; clip.inMs = 0L; clip.outMs = 10000L;
            project.clips.add(clip);
        });
    }

    @Test public void persistedMarkerAndRangeHaveSharedUndoRedo() throws Exception {
        ProjectStore.Project edited = store.edit(baseline.id, baseline.revision, "Owner range and marker", project -> {
            ProjectMarkers.add(project, new JSONObject().put("markerId", "beat-one").put("atMs", 1250L)
                    .put("endMs", 2250L).put("name", "First beat").put("note", "Match the cut"));
            ProjectMarkers.setRange(project, new JSONObject().put("inMs", 1000L).put("outMs", 5000L));
        });
        assertEquals(1250L, store.get(edited.id).markers.getJSONObject(0).getLong("atMs"));
        assertTrue(ProjectMarkers.selection(store.get(edited.id)).enabled);
        ProjectStore.Project undone = store.undo(edited.id, edited.revision);
        assertEquals(0, undone.markers.length());
        assertFalse(ProjectMarkers.selection(undone).defined);
        ProjectStore.Project redone = store.redo(undone.id, undone.revision);
        assertEquals("beat-one", redone.markers.getJSONObject(0).getString("id"));
        assertEquals(5000L, ProjectMarkers.selection(redone).outMs);
    }

    @Test public void shorteningDisablesRangeAndPreservesOffProgramMarkersUntilUndo() throws Exception {
        ProjectStore.Project annotated = store.edit(baseline.id, baseline.revision, "Mark program", project -> {
            ProjectMarkers.add(project, new JSONObject().put("markerId", "late").put("atMs", 8000L));
            ProjectMarkers.setRange(project, new JSONObject().put("inMs", 1000L).put("outMs", 9000L));
        });
        ProjectStore.Project shortened = store.edit(annotated.id, annotated.revision, "Trim tail", project -> project.clip("clip-video").outMs = 4000L);
        ProjectMarkers.Range disabled = ProjectMarkers.selection(shortened);
        assertFalse(disabled.enabled);
        assertFalse(disabled.valid);
        assertEquals(9000L, disabled.outMs);
        assertTrue(disabled.reason.contains("duration"));
        assertEquals(8000L, shortened.markers.getJSONObject(0).getLong("atMs"));
        assertFalse(ProjectMarkers.list(shortened).getJSONObject(0).getBoolean("inProgram"));
        ProjectStore.Project restored = store.undo(shortened.id, shortened.revision);
        assertTrue(ProjectMarkers.selection(restored).enabled);
        assertTrue(ProjectMarkers.list(restored).getJSONObject(0).getBoolean("inProgram"));
    }

    @Test public void growthNeverSilentlyReenablesAnInvalidatedSelection() throws Exception {
        ProjectStore.Project selection = store.edit(baseline.id, baseline.revision, "Range", project ->
                ProjectMarkers.setRange(project, new JSONObject().put("inMs", 1000L).put("outMs", 9000L)));
        ProjectStore.Project shorter = store.edit(selection.id, selection.revision, "Shorten", project -> project.clip("clip-video").outMs = 4000L);
        ProjectStore.Project longer = store.edit(shorter.id, shorter.revision, "Extend", project -> project.clip("clip-video").outMs = 10000L);
        ProjectMarkers.Range range = ProjectMarkers.selection(longer);
        assertTrue(range.valid);
        assertFalse(range.enabled);
        assertTrue(longer.editorRange.has("invalidatedReason"));
    }

    @Test public void staleMcpMarkerCannotOverwriteOwnerProject() throws Exception {
        ProjectStore.Project owner = store.edit(baseline.id, baseline.revision, "Owner marker", project ->
                ProjectMarkers.add(project, new JSONObject().put("markerId", "owner").put("atMs", 1000L)));
        try {
            store.edit(owner.id, baseline.revision, "Stale MCP marker", project ->
                    ProjectMarkers.add(project, new JSONObject().put("markerId", "mcp").put("atMs", 2000L)));
            fail("Expected revision conflict");
        } catch (ProjectStore.RevisionConflictException expected) {
            assertEquals(owner.revision, expected.actualRevision);
        }
        assertEquals(1, store.get(owner.id).markers.length());
        assertEquals("owner", store.get(owner.id).markers.getJSONObject(0).getString("id"));
    }

    @Test public void markerTimingRequiresExactIntegerAndMetadataRejectsPrivateLocators() throws Exception {
        assertRejected(new JSONObject().put("atMs", 123.5d));
        assertRejected(new JSONObject().put("atMs", "123"));
        assertRejected(new JSONObject().put("atMs", 10001L));
        assertRejected(new JSONObject().put("atMs", 100L).put("sourceUrl", "https://private.invalid/media"));
        assertEquals(0, store.get(baseline.id).markers.length());
    }

    @Test public void metadataBudgetRejectsNewMarkerWithoutMutatingEarlierAnnotations() throws Exception {
        ProjectStore.Project project = ProjectStore.copy(baseline);
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < ProjectMarkers.MAX_NOTE_CHARACTERS; i++) note.append('x');
        int accepted = 0;
        for (int i = 0; i < ProjectMarkers.MAX_MARKERS; i++) {
            int before = project.markers.length();
            try {
                ProjectMarkers.add(project, new JSONObject().put("markerId", "marker-" + i).put("atMs", i)
                        .put("note", note.toString()));
                accepted++;
            } catch (IllegalArgumentException expected) {
                assertEquals(before, project.markers.length());
                assertTrue(expected.getMessage().contains("128 KiB"));
                break;
            }
        }
        assertTrue(accepted > 0 && accepted < ProjectMarkers.MAX_MARKERS);
    }

    private void assertRejected(JSONObject settings) {
        try {
            store.edit(baseline.id, baseline.revision, "Rejected marker", project -> ProjectMarkers.add(project, settings));
            fail("Expected invalid marker settings");
        } catch (IllegalArgumentException expected) {
            assertEquals(baseline.revision, store.get(baseline.id).revision);
        }
    }
}
