package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** One-command native corner pin program keeps original owner files and project history. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StudioCornerPinOpsTest {
    private ProjectStore.Project sample(ProjectStore store){
        ProjectStore.Project p=store.create("Original photo with song");
        ProjectStore.Asset image=new ProjectStore.Asset();
        image.id="owned-image";image.name="Photo.png";
        image.mime="image/png";image.uri="file:///private/Photo.png";
        p.assets.add(image);
        ProjectStore.Asset audio=new ProjectStore.Asset();
        audio.id="owned-song";audio.name="Sound.mp3";
        audio.mime="audio/mpeg";audio.uri="file:///private/Sound.mp3";audio.durationMs=4500;
        p.assets.add(audio);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="original-clip";clip.assetId=image.id;clip.outMs=4000;
        p.clips.add(clip);
        StudioLayer music=new StudioLayer();
        music.id="original-audio";music.type=StudioLayer.AUDIO;
        music.assetId=audio.id;music.durationMs=4000;p.studioLayers.add(music);
        StudioLayer overlay=new StudioLayer();
        overlay.id="original-overlay";overlay.type=StudioLayer.IMAGE;
        overlay.assetId=image.id;overlay.startMs=0;overlay.durationMs=4000;
        p.studioLayers.add(overlay);store.save(p);return store.get(p.id);
    }
    private JSONObject command(ProjectStore.Project p,String action)throws Exception{
        return new JSONObject().put("projectId",p.id)
            .put("expectedUpdatedAt",p.updatedAt).put("layerId","original-overlay")
            .put("operation",action);
    }

    @Test public void planIsReadOnlyAndApplyAndClearRetainSameImportedMedia()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project initial=sample(store);
            String before=SketchDocument.canonical(initial.toJson());
            JSONArray coordinates=StudioCornerPinDialog.parse("0.15,0.1,0.95,0.2,0.82,0.9,0.2,0.8");
            JSONObject request=command(initial,"apply").put("corners",coordinates);
            JSONObject plan=StudioCornerPinOps.plan(initial,request);
            assertTrue(plan.getBoolean("dryRun"));
            assertEquals(before,SketchDocument.canonical(store.get(initial.id).toJson()));
            JSONObject receipt=StudioCornerPinOps.apply(store,request);
            assertTrue(receipt.getBoolean("saved"));
            assertTrue(receipt.getBoolean("hasCornerPin"));
            assertFalse(receipt.getBoolean("previewVerified"));
            ProjectStore.Project changed=store.get(initial.id);
            assertEquals("file:///private/Photo.png",changed.asset("owned-image").uri);
            assertEquals("file:///private/Sound.mp3",changed.asset("owned-song").uri);
            assertEquals(1,changed.clips.size());assertEquals(2,changed.studioLayers.size());
            assertEquals(8,changed.studioLayers.get(1).cornerPin.length());
            assertThrows(IllegalStateException.class,()->StudioCornerPinOps.apply(store,request));
            JSONObject clear=command(changed,"clear");
            assertTrue(StudioCornerPinOps.apply(store,clear).getBoolean("saved"));
            ProjectStore.Project restored=store.get(initial.id);
            assertNull(restored.studioLayers.get(1).cornerPin);
            assertEquals(before,SketchDocument.canonical(restored.toJson()));
        }
    }

    @Test public void malformedUnownedOrUnauthorizedCornersAreNeverSaved()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            String original=SketchDocument.canonical(source.toJson());
            JSONObject wrong=command(source,"apply").put("corners",
                    new JSONArray().put(0).put(0).put(1).put(0).put(0).put(1).put(1).put(1));
            assertThrows(IllegalArgumentException.class,()->
                    StudioCornerPinOps.apply(store,wrong));
            JSONObject external=command(source,"apply").put("corners",StudioCornerPinRasterizer.identity())
                    .put("url","file:///outside-project");
            assertThrows(IllegalArgumentException.class,()->
                    StudioCornerPinOps.apply(store,external));
            JSONObject unrelated=command(source,"apply").put("corners",StudioCornerPinRasterizer.identity())
                    .put("layerId","other-project-image");
            assertThrows(IllegalArgumentException.class,()->
                    StudioCornerPinOps.apply(store,unrelated));
            assertEquals(original,SketchDocument.canonical(store.get(source.id).toJson()));
        }
    }

    @Test public void exactClosedNativeMcpActionsHaveNoGalleryAccess()throws Exception{
        for(String action:new String[]{"corner_pin_capabilities","corner_pin_plan","corner_pin_edit"}){
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
        }
        assertEquals(101,NativeWorkbenchBridge.capabilities().getJSONArray("canonicalActions").length());
        JSONObject obj=NativeWorkbenchBridge.capabilities().getJSONObject("cornerPin");
        assertTrue(obj.getBoolean("projective2DHomography"));
        assertFalse(obj.getBoolean("galleryAccess"));
        assertFalse(obj.getBoolean("independent3DCamera"));
    }
}
