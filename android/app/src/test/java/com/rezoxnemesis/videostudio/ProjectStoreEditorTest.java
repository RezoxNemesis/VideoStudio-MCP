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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class ProjectStoreEditorTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("videostudio_v3.db");
        context.getSharedPreferences("videostudio_native_v1", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void durationIncludesGapsAndOverlappingTracks() throws Exception {
        ProjectStore.Project p = ProjectStore.Project.fromJson(new JSONObject("{\"id\":\"p\",\"tracks\":[{\"id\":\"v1\",\"type\":\"video\"},{\"id\":\"v2\",\"type\":\"image\"}],\"clips\":[{\"id\":\"a\",\"assetId\":\"source\",\"trackId\":\"v1\",\"startMs\":2000,\"inMs\":0,\"outMs\":1000},{\"id\":\"b\",\"assetId\":\"overlay\",\"trackId\":\"v2\",\"startMs\":5000,\"inMs\":0,\"outMs\":1000}]}"));
        assertEquals("Timeline duration is the latest clip end, including gaps", 6000L, p.outputDurationMs());
    }

    @Test public void staleWriterCannotSilentlyOverwriteOwnerEdit() {
        ProjectStore owner = new ProjectStore(context);
        ProjectStore agent = new ProjectStore(context);
        ProjectStore.Project created = owner.create("Original");
        ProjectStore.Project stale = agent.get(created.id);
        created.name = "Owner edit";
        owner.save(created);
        stale.name = "Stale agent edit";
        boolean rejected = false;
        try { agent.save(stale); } catch (IllegalStateException expected) { rejected = true; }
        assertTrue("Stale saves must report a revision conflict", rejected);
        assertEquals("Owner edit", owner.get(created.id).name);
    }

    @Test public void revisionIsDurableAcrossStoreInstances() {
        ProjectStore store = new ProjectStore(context);
        ProjectStore.Project p = store.create("Revision");
        long first = p.toJson().optLong("revision", 0);
        assertTrue("Created projects have a persisted revision", first > 0);
        p.name = "Saved";
        store.save(p);
        assertEquals(first + 1, new ProjectStore(context).get(p.id).toJson().optLong("revision", 0));
    }

    @Test public void legacySequentialClipsKeepTheirOriginalTiming() throws Exception {
        ProjectStore.Project p = ProjectStore.Project.fromJson(new JSONObject("{\"id\":\"legacy\",\"clips\":[{\"id\":\"a\",\"inMs\":0,\"outMs\":3000},{\"id\":\"b\",\"inMs\":0,\"outMs\":2000,\"speed\":2}]}"));
        assertEquals(4000L, p.outputDurationMs());
    }

    @Test public void backgroundGeneratedMediaMergesIntoLatestOwnerRevision() {
        ProjectStore owner=new ProjectStore(context), worker=new ProjectStore(context);
        ProjectStore.Project project=owner.create("Before");
        ProjectStore.Project stale=worker.get(project.id);
        project.name="Owner renamed during render";owner.save(project);
        ProjectStore.Asset result=worker.registerGeneratedAsset(stale,Uri.parse("file:///owned/result.mp4"),"result.mp4","final_render",false);
        ProjectStore.Project latest=owner.get(project.id);
        assertEquals("Owner renamed during render",latest.name);
        assertNotNull(latest.asset(result.id));assertEquals(1,latest.assets.size());
        worker.registerGeneratedAsset(stale,Uri.parse("file:///owned/result.mp4"),"result.mp4","final_render",false);
        assertEquals("Publication replay does not duplicate media",1,owner.get(project.id).assets.size());
    }

    @Test public void appendingOwnedMediaDoesNotOverwriteConcurrentOwnerName() {
        ProjectStore owner=new ProjectStore(context),worker=new ProjectStore(context);
        ProjectStore.Project project=owner.create("Before");
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="image";asset.mime="image/png";asset.uri="file:///owned/image.png";project.assets.add(asset);owner.save(project);
        ProjectStore.Project stale=worker.get(project.id);
        project.name="Owner changed";owner.save(project);
        assertTrue(worker.appendAssetToTimeline(stale,asset.id));
        assertEquals("Owner changed",owner.get(project.id).name);
        assertEquals(1,owner.get(project.id).clips.size());
    }
}
