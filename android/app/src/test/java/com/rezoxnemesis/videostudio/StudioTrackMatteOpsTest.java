package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Human and ChatGPT both use one transaction and the same project-owned layers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StudioTrackMatteOpsTest {
    private ProjectStore.Project project(ProjectStore store){
        ProjectStore.Project p=store.create("Existing owner image and soundtrack");
        ProjectStore.Asset art=new ProjectStore.Asset();
        art.id="owned-still";art.name="Original.png";
        art.uri="file:///owner/original.png";art.mime="image/png";
        p.assets.add(art);
        ProjectStore.Asset song=new ProjectStore.Asset();
        song.id="owned-song";song.name="Audio.mp4";
        song.uri="file:///owner/song.mp4";song.mime="audio/mp4";song.durationMs=5000;
        p.assets.add(song);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="source-clip";clip.assetId=art.id;clip.outMs=4000;
        p.clips.add(clip);
        StudioLayer music=new StudioLayer();
        music.id="music";music.type=StudioLayer.AUDIO;music.assetId=song.id;
        music.durationMs=4000;p.studioLayers.add(music);
        StudioLayer shape=new StudioLayer();
        shape.id="existing-vector";shape.type=StudioLayer.SHAPE;
        shape.startMs=0;shape.durationMs=4000;shape.shapeKind="ellipse";
        shape.color=0xffffffff;p.studioLayers.add(shape);
        StudioLayer overlay=new StudioLayer();
        overlay.id="existing-overlay";overlay.type=StudioLayer.IMAGE;
        overlay.assetId=art.id;overlay.startMs=0;overlay.durationMs=4000;
        p.studioLayers.add(overlay);
        store.save(p);return store.get(p.id);
    }

    private JSONObject command(ProjectStore.Project p,String op)throws Exception{
        return new JSONObject().put("projectId",p.id)
                .put("expectedUpdatedAt",p.updatedAt)
                .put("operation",op).put("targetLayerId","existing-overlay");
    }

    @Test public void linkingAndUnlinkingAreRealRevisionGuardedSourcePreservingEdits()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project original=project(store);
            String originalAudio=original.asset("owned-song").uri;
            String originalImage=original.asset("owned-still").uri;
            String initial=SketchDocument.canonical(original.toJson());
            JSONObject link=command(original,"link").put("matteLayerId","existing-vector")
                    .put("matteMode","alpha_inverted");
            JSONObject planned=StudioTrackMatteOps.plan(original,link);
            assertTrue(planned.getBoolean("dryRun"));
            assertEquals(initial,SketchDocument.canonical(store.get(original.id).toJson()));
            JSONObject result=StudioTrackMatteOps.apply(store,link);
            assertTrue(result.getBoolean("saved"));
            assertFalse(result.getBoolean("previewVerified"));
            ProjectStore.Project edited=store.get(original.id);
            assertEquals(3,edited.studioLayers.size());
            assertEquals(originalAudio,edited.asset("owned-song").uri);
            assertEquals(originalImage,edited.asset("owned-still").uri);
            assertEquals("source-clip",edited.clips.get(0).id);
            StudioLayer matte=edited.studioLayers.get(1),overlay=edited.studioLayers.get(2);
            assertTrue(matte.matteOnly);
            assertEquals(matte.id,overlay.matteLayerId);
            assertEquals("alpha_inverted",overlay.matteMode);
            assertThrows(IllegalStateException.class,()->
                    StudioTrackMatteOps.apply(store,link));
            JSONObject unlink=command(edited,"unlink");
            JSONObject removed=StudioTrackMatteOps.apply(store,unlink);
            assertEquals("none",removed.getString("matteMode"));
            ProjectStore.Project after=store.get(original.id);
            assertFalse(after.studioLayers.get(1).matteOnly);
            assertTrue(after.studioLayers.get(2).matteLayerId.isEmpty());
            assertEquals(originalAudio,after.asset("owned-song").uri);
        }
    }

    @Test public void staticSafetyRejectsMovingSourcesAndUnownedMaskFields()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project project=project(store);
            JSONObject link=command(project,"link").put("matteLayerId","other-project-mask");
            String first=SketchDocument.canonical(project.toJson());
            assertThrows(IllegalArgumentException.class,()->
                    StudioTrackMatteOps.apply(store,link));
            assertEquals(first,SketchDocument.canonical(store.get(project.id).toJson()));
            JSONObject unsupported=command(project,"link").put("matteLayerId","existing-vector")
                    .put("matteMode","multiply_rgb");
            assertThrows(IllegalArgumentException.class,()->
                    StudioTrackMatteOps.plan(project,unsupported));
            JSONObject illegal=command(project,"unlink").put("matteMode","alpha");
            assertThrows(IllegalArgumentException.class,()->
                    StudioTrackMatteOps.plan(project,illegal));
            JSONObject unknown=command(project,"link")
                    .put("matteLayerId","existing-vector").put("filesystem","/mnt/storage");
            assertThrows(IllegalArgumentException.class,()->
                    StudioTrackMatteOps.plan(project,unknown));
        }
    }

    @Test public void literalAssetUriNeverAppearsInMcpReceipts()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=project(store);
            JSONObject action=command(source,"link").put("matteLayerId","existing-vector");
            String receipt=StudioTrackMatteOps.apply(store,action).toString();
            assertFalse(receipt.contains("file:///owner"));
            assertFalse(receipt.contains("Audio.mp4"));
            assertFalse(receipt.contains("Original.png"));
            assertTrue(receipt.contains("\\"galleryAccess\\":false"));
            assertEquals("4.10.33",StudioTrackMatteOps.capabilities()
                    .getString("minimumNativeVersion"));
        }
    }
}
