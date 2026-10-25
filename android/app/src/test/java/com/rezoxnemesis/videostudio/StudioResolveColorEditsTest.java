package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Atomic user+ChatGPT grade edits over existing media, no Gallery or copy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioResolveColorEditsTest {
    private ProjectStore.Project sample(ProjectStore store)throws Exception{
        ProjectStore.Project p=store.create("Owned grade reference");
        ProjectStore.Asset image=new ProjectStore.Asset();
        image.id="owned-still";image.mime="image/png";
        image.uri="file:///internal/private-owner-panel.png";
        p.assets.add(image);
        ProjectStore.Asset sound=new ProjectStore.Asset();
        sound.id="owned-song";sound.mime="audio/mp4";sound.durationMs=10000;
        sound.uri="file:///internal/private-owner-song.mp4";
        p.assets.add(sound);
        for(int i=0;i<3;i++){
            ProjectStore.Clip c=new ProjectStore.Clip();
            c.id="shot-"+i;c.assetId=image.id;c.inMs=0;c.outMs=2200;
            p.clips.add(c);
        }
        StudioLayer audio=new StudioLayer();audio.id="original-song";
        audio.type=StudioLayer.AUDIO;audio.assetId=sound.id;
        audio.startMs=0;audio.durationMs=6600;p.studioLayers.add(audio);
        store.save(p);
        return store.get(p.id);
    }
    private JSONObject request(ProjectStore.Project p)throws Exception{
        return new JSONObject().put("projectId",p.id)
                .put("expectedUpdatedAt",p.updatedAt)
                .put("clipIds",new JSONArray().put("shot-0").put("shot-1"))
                .put("operation","grade").put("preset","golden_hour");
    }
    @Test public void atomicColorBatchPreservesMediaAndUnselectedShots()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            String before=SketchDocument.canonical(source.toJson());
            JSONObject cmd=request(source);
            JSONObject plan=StudioResolveColorEdits.plan(source,cmd);
            assertTrue(plan.getBoolean("dryRun"));
            assertFalse(plan.getBoolean("saved"));
            assertEquals(before,SketchDocument.canonical(store.get(source.id).toJson()));
            JSONObject ack=StudioResolveColorEdits.apply(store,cmd);
            assertTrue(ack.getBoolean("saved"));
            assertFalse(ack.getBoolean("previewVerified"));
            ProjectStore.Project saved=store.get(source.id);
            assertEquals(2,ack.getInt("affectedClipCount"));
            assertEquals("file:///internal/private-owner-panel.png",saved.asset("owned-still").uri);
            assertEquals("file:///internal/private-owner-song.mp4",saved.asset("owned-song").uri);
            assertEquals("original-song",saved.studioLayers.get(0).id);
            assertEquals(3,saved.clips.size());
            assertNotNull(saved.clips.get(0).effects.optJSONArray("resolveColorNodes"));
            assertNotNull(saved.clips.get(1).effects.optJSONArray("resolveColorNodes"));
            assertFalse(saved.clips.get(2).effects.has("resolveColorNodes"));
            JSONObject old=store.get(source.id).toJson();
            assertThrows(IllegalStateException.class,()->
                    StudioResolveColorEdits.apply(store,cmd));
            assertEquals(SketchDocument.canonical(old),
                    SketchDocument.canonical(store.get(source.id).toJson()));
        }
    }
    @Test public void nativeGradeCanResetWithoutClearingLegacyAdjustments()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project src=sample(store);
            src.clips.get(0).effects.put("brightness",.12);
            store.save(src);src=store.get(src.id);
            StudioResolveColorEdits.apply(store,request(src));
            ProjectStore.Project colored=store.get(src.id);
            JSONObject reset=new JSONObject().put("projectId",src.id)
                    .put("expectedUpdatedAt",colored.updatedAt)
                    .put("operation","reset").put("clipIds",new JSONArray().put("shot-0"));
            StudioResolveColorEdits.apply(store,reset);
            ProjectStore.Project restored=store.get(src.id);
            assertEquals(.12,restored.clips.get(0).effects.getDouble("brightness"),.00001);
            assertFalse(restored.clips.get(0).effects.has("resolveColorNodes"));
            assertTrue(restored.clips.get(1).effects.has("resolveColorNodes"));
        }
    }

    @Test public void unauthorizedFilePathsOrUnknownClipIDsCannotEnterColorProgram()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project src=sample(store);
            JSONObject cmd=request(src);
            cmd.put("clipIds",new JSONArray().put("shot-0").put("unknown-video-id"));
            assertThrows(IllegalArgumentException.class,()->
                    StudioResolveColorEdits.plan(src,cmd));
            assertThrows(IllegalArgumentException.class,()->
                    StudioResolveColorEdits.plan(src,request(src).put("externalLutUri","file:///private")));
            assertThrows(IllegalArgumentException.class,()->
                    StudioResolveColorEdits.plan(src,request(src).put("preset","unsupported")));
        }
    }
    @Test public void nativeColorMcpAliasesRemainClosedAndBackgroundOnly()throws Exception{
        for(String action:new String[]{"resolve_color_capabilities","resolve_grade_plan","resolve_grade_apply"}){
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
        }
        JSONObject cap=NativeWorkbenchBridge.capabilities().getJSONObject("resolveColorPage");
        assertEquals("4.10.34",cap.getString("minimumNativeVersion"));
        assertFalse(cap.getBoolean("galleryAccess"));
    }
}
