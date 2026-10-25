package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** One guarded native audio transaction shared by human and ChatGPT control. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioFairlightEditsTest {
    private ProjectStore.Project sample(ProjectStore store)throws Exception{
        ProjectStore.Project p=store.create("Audio grade test");
        ProjectStore.Asset image=new ProjectStore.Asset();
        image.id="still";image.mime="image/png";image.uri="file:///internal/still.png";
        p.assets.add(image);
        ProjectStore.Asset audio=new ProjectStore.Asset();
        audio.id="source-music";audio.mime="audio/mp4";audio.durationMs=12000;
        audio.uri="file:///internal/owner-song.mp4";p.assets.add(audio);
        ProjectStore.Clip c=new ProjectStore.Clip();
        c.id="clip";c.assetId=image.id;c.inMs=0;c.outMs=6500;
        p.clips.add(c);
        for(int i=0;i<2;i++){
            StudioLayer lane=new StudioLayer();lane.id="audio-"+i;
            lane.type=StudioLayer.AUDIO;lane.assetId=audio.id;
            lane.startMs=0;lane.durationMs=6500;
            lane.gain=.6f;
            p.studioLayers.add(lane);
        }
        store.save(p);return store.get(p.id);
    }
    private JSONObject request(ProjectStore.Project p)throws Exception{
        return new JSONObject().put("projectId",p.id)
                .put("expectedUpdatedAt",p.updatedAt)
                .put("operation","apply").put("layerIds",new JSONArray().put("audio-0"))
                .put("preset","dialogue");
    }
    @Test public void ownerPreservingAtomicAudioFxRoundTripsAndCanReset()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=sample(store);
            String before=SketchDocument.canonical(p.toJson());
            JSONObject request=request(p);
            JSONObject plan=StudioFairlightEdits.plan(p,request);
            assertTrue(plan.getBoolean("dryRun"));
            assertEquals(before,SketchDocument.canonical(store.get(p.id).toJson()));
            JSONObject done=StudioFairlightEdits.apply(store,request);
            assertTrue(done.getBoolean("saved"));
            ProjectStore.Project edited=store.get(p.id);
            assertEquals("file:///internal/owner-song.mp4",edited.asset("source-music").uri);
            assertEquals(.6,edited.studioLayers.get(0).gain,.00001);
            assertNotNull(edited.studioLayers.get(0).fairlightFx);
            assertNull(edited.studioLayers.get(1).fairlightFx);
            assertEquals("audio-0",edited.studioLayers.get(0).id);
            JSONObject reset=new JSONObject().put("projectId",p.id)
                    .put("expectedUpdatedAt",edited.updatedAt)
                    .put("operation","reset").put("layerIds",new JSONArray().put("audio-0"));
            StudioFairlightEdits.apply(store,reset);
            ProjectStore.Project clean=store.get(p.id);
            assertNull(clean.studioLayers.get(0).fairlightFx);
            assertEquals("file:///internal/owner-song.mp4",clean.asset("source-music").uri);
        }
    }

    @Test public void malformedAudioEditsAndStaleRevisionsDoNotWrite()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=sample(store);
            JSONObject edit=request(p);
            edit.put("layerIds",new JSONArray().put("clip"));
            assertThrows(IllegalArgumentException.class,()->
                    StudioFairlightEdits.apply(store,edit));
            assertNull(store.get(p.id).studioLayers.get(0).fairlightFx);
            JSONObject ok=request(p);
            StudioFairlightEdits.apply(store,ok);
            assertThrows(IllegalStateException.class,()->
                    StudioFairlightEdits.apply(store,ok));
        }
    }

    @Test public void privateMcpFairlightCallsAreExplicitNativeCommands()throws Exception{
        for(String action:new String[]{
                "fairlight_fx_capabilities","fairlight_fx_plan","fairlight_fx_apply"}){
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
        }
        JSONObject cap=NativeWorkbenchBridge.capabilities().getJSONObject("fairlightAudio");
        assertEquals("4.10.35",cap.getString("minimumNativeVersion"));
        assertFalse(cap.getBoolean("lufsComplianceVerified"));
    }

    @Test public void importedSourceAudioNeverAcquiresFxFromOtherLayerTypes()throws Exception{
        StudioLayer image=new StudioLayer();image.type=StudioLayer.IMAGE;image.assetId="any";
        image.fairlightFx=new JSONObject().put("compressorRatio",3);
        assertThrows(IllegalArgumentException.class,image::validate);
        JSONObject old=new StudioLayer().toJson();
        assertFalse(old.has("fairlightFx"));
    }
}
