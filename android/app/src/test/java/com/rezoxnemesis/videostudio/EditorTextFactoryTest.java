package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.*;

/** Future regressions for title ownership and replay; no network or export is involved. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class EditorTextFactoryTest {
    private Context context;
    private ProjectStore store;
    private ProjectStore.Project baseline;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("videostudio_v3.db");
        context.getSharedPreferences("videostudio_native_v1", Context.MODE_PRIVATE).edit().clear().commit();
        store = new ProjectStore(context);
        baseline = store.create("Title project");
    }

    @Test public void replayKeepsOwnerEditsWithoutAddingAssetOrRevision() throws Exception {
        EditorTextFactory.Result first = create(baseline.revision, "Original", "command-a");
        ProjectStore.Project edited = store.edit(baseline.id, first.project.revision, "Owner text", p -> {
            p.clip(first.clipId).title = "Owner changed this";
            p.clip(first.clipId).effects.put("textColor", "#F0A040");
        });
        EditorTextFactory.Result replay = create(baseline.revision, "Original", "command-a");
        assertTrue(replay.reused);
        assertEquals(first.clipId, replay.clipId);
        assertEquals(edited.revision, replay.project.revision);
        assertEquals(1, replay.project.assets.size());
        assertEquals(1, replay.project.clips.size());
        assertEquals("Owner changed this", replay.project.clip(first.clipId).title);
        assertEquals("#F0A040", replay.project.clip(first.clipId).effects.getString("textColor"));
    }

    @Test public void staleNewRequestRollsBackItsCanvasAndGraph() throws Exception {
        ProjectStore.Project current = store.edit(baseline.id, baseline.revision, "Rename", p -> p.name = "New name");
        try { create(baseline.revision, "New title", "stale-command"); fail("Stale revisions must refuse a new title"); }
        catch (ProjectStore.RevisionConflictException expected) { }
        ProjectStore.Project fresh = store.get(baseline.id);
        assertEquals(current.revision, fresh.revision);
        assertTrue(fresh.assets.isEmpty());
        assertTrue(fresh.clips.isEmpty());
        File directory = new File(new CreativeWorkspace(context).projectRoot(baseline.id), "generated/title_canvases");
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(0, files.length);
    }

    @Test public void removedTitleCannotBeRecreatedByAnOldCommand() throws Exception {
        EditorTextFactory.Result first = create(baseline.revision, "Original", "command-a");
        ProjectStore.Project removed = store.timelineEdit(baseline.id, first.project.revision, "delete", new JSONObject().put("clipId", first.clipId));
        try { create(baseline.revision, "Original", "command-a"); fail("Replay must preserve the owner deletion"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("already applied")); }
        assertEquals(removed.revision, store.get(baseline.id).revision);
        assertTrue(store.get(baseline.id).clips.isEmpty());
    }

    @Test public void longNativeTitleRemainsOwnedAndMirrorRejectsItsSmallerLimit() throws Exception {
        String title = new String(new char[1500]).replace('\0', 'T');
        EditorTextFactory.Result result = create(baseline.revision, title, "long-title");
        ProjectStore.Clip clip = result.project.clip(result.clipId);
        assertEquals(title, clip.title);
        assertTrue(clip.effects.getBoolean("titleOnly"));
        File canvas = new File(Uri.parse(result.project.asset(clip.assetId).uri).getPath());
        assertTrue(canvas.isFile());
        assertTrue(canvas.getCanonicalPath().startsWith(new CreativeWorkspace(context).projectRoot(baseline.id).getCanonicalPath() + File.separator));
        try { NativeMetadataMirror.metadataGraph(result.project); fail("The mirror must explicitly reject titles above its 1000-character limit"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }

    private EditorTextFactory.Result create(long revision, String text, String key) throws Exception {
        return EditorTextFactory.create(context, store, baseline.id, revision, 0, 3000, text,
                new JSONObject().put("fontFamily", "sans-serif-medium").put("textColor", "#FFFFFF"), key);
    }
}
