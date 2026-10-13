package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Matrix;
import org.robolectric.RuntimeEnvironment;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioPrecisionEditsTest {
    @Test public void exactKeysInterpolateWithoutOrganicMotionAndHoldAtEndpoints() throws Exception {
        JSONArray keys=new JSONArray().put(new JSONObject().put("timeMs",100).put("x",0).put("scale",1).put("easing","linear"))
                .put(new JSONObject().put("timeMs",900).put("x",.4).put("scale",2));
        StudioClipKeyframes effect=StudioClipKeyframes.parse(keys,1000);
        assertEquals(0,effect.at(0).x,.00001);
        assertEquals(.2,effect.at(500).x,.00001);
        assertEquals(1.5,effect.at(500).scaleX,.00001);
        assertEquals(.4,effect.at(1000).x,.00001);
        float[] a=new float[9],b=new float[9];
        effect.getMatrix(500_000).getValues(a);effect.getMatrix(500_000).getValues(b);
        assertArrayEquals(a,b,0f);
        assertEquals(.4,a[Matrix.MTRANS_X],.00001);
    }

    @Test public void holdAndEaseAreExplicit() throws Exception {
        JSONArray keys=new JSONArray().put(new JSONObject().put("timeMs",0).put("x",0).put("easing","hold"))
                .put(new JSONObject().put("timeMs",1000).put("x",1));
        assertEquals(0,StudioClipKeyframes.parse(keys,1000).at(999).x,0);
        assertEquals(1,StudioClipKeyframes.parse(keys,1000).at(1000).x,0);
        keys.getJSONObject(0).put("easing","ease_in");
        assertEquals(.25,StudioClipKeyframes.parse(keys,1000).at(500).x,.00001);
    }

    @Test public void malformedKeysRejectRatherThanSilentlyClamp() throws Exception {
        for(JSONArray bad:new JSONArray[]{
                new JSONArray().put(new JSONObject().put("timeMs",0)).put(new JSONObject().put("timeMs",0)),
                new JSONArray().put(new JSONObject().put("timeMs",1001)),
                new JSONArray().put(new JSONObject().put("timeMs","0")),
                new JSONArray().put(new JSONObject().put("timeMs",0).put("x","0.2")),
                new JSONArray().put(new JSONObject().put("timeMs",0).put("scale",0)),
                new JSONArray().put(new JSONObject().put("timeMs",0).put("easing","magic")),
                new JSONArray().put(new JSONObject().put("timeMs",0).put("unsupported",1))}){
            try{StudioClipKeyframes.parse(bad,1000);fail("Accepted malformed keyframes: "+bad);}
            catch(IllegalArgumentException expected){}
        }
    }

    @Test public void batchRejectsLateInvalidOperationWithoutSavingEarlierEdits() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);
        ProjectStore.Project p=project(store);long revision=p.updatedAt;
        JSONObject request=new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",revision)
                .put("operations",new JSONArray().put(op(p.clips.get(0).id,new JSONObject().put("brightness",.5)))
                        .put(op(p.clips.get(0).id,new JSONObject().put("scale",0))));
        try{StudioPrecisionEdits.apply(context,store,request);fail("Invalid batch accepted");}catch(IllegalArgumentException expected){}
        assertEquals(revision,store.get(p.id).updatedAt);
        assertFalse(store.get(p.id).clips.get(0).effects.has("brightness"));
    }

    @Test public void stableIdsAndRevisionProtectConcurrentPhoneAndMcpEdits() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);
        ProjectStore.Project p=project(store);long revision=p.updatedAt;
        JSONObject request=new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",revision)
                .put("operations",new JSONArray().put(op(p.clips.get(0).id,new JSONObject().put("x",.25).put("volume",1.5))));
        JSONObject result=StudioPrecisionEdits.apply(context,store,request);
        assertTrue(result.getLong("updatedAt")>revision);
        assertFalse("Durable ACK must not include the unbounded project",result.has("project"));
        assertTrue(result.toString().length()<16_384);
        assertEquals(.25,store.get(p.id).clips.get(0).effects.getDouble("x"),0);
        try{StudioPrecisionEdits.apply(context,store,request);fail("Stale batch accepted");}catch(IllegalStateException expected){}
        assertEquals(result.getLong("updatedAt"),store.get(p.id).updatedAt);
    }

    @Test public void renderAndPreviewBuildSameEffectsAndConsumeFlatKeys() throws Exception {
        Context context=RuntimeEnvironment.getApplication();NativeRenderEngine engine=new NativeRenderEngine(context);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.inMs=0;clip.outMs=1000;
        clip.effects.put("brightness",.2).put("blur",2).put("keyframes",new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("x",0))
                .put(new JSONObject().put("timeMs",1000).put("x",.4)));
        java.util.List<androidx.media3.common.Effect> effects=engine.buildPreviewEffects(clip,"9:16","720p");
        assertTrue(effects.stream().anyMatch(e->e instanceof StudioClipKeyframes));
        assertTrue(effects.stream().anyMatch(e->e instanceof androidx.media3.effect.Brightness));
        assertTrue(effects.stream().anyMatch(e->e instanceof androidx.media3.effect.GaussianBlur));
        assertFalse(effects.stream().anyMatch(e->e instanceof MotionMatrixEffect));
        ProjectStore store=new ProjectStore(context);ProjectStore.Project p=project(store);
        p.clips.get(0).effects=clip.effects;
        java.util.List<androidx.media3.common.Effect> composed=engine.buildPreviewComposition(p,"9:16","720p")
                .sequences.get(0).editedMediaItems.get(0).effects.videoEffects;
        assertEquals(effects.size(),composed.size());
        for(int i=0;i<effects.size();i++)assertEquals(effects.get(i).getClass(),composed.get(i).getClass());
    }

    @Test public void unsupportedToolsCannotPretendToBeRendered(){
        assertFalse(StudioToolCatalog.isSupported("Reverse"));
        assertTrue(StudioToolCatalog.isSupported("Green Screen"));
        assertTrue(StudioToolCatalog.isSupported("Keyframes"));
        assertTrue(StudioToolCatalog.group(2).stream().anyMatch(t->"Transform".equals(t.label)&&t.supported&&!t.previewRequiresExport));
    }

    @Test public void anImageInMixedTimelineDoesNotDropVideoSourceAudio() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);
        ProjectStore.Project p=project(store);
        ProjectStore.Asset video=new ProjectStore.Asset();video.id="video-source";video.uri="file:///unused.mp4";
        video.mime="video/mp4";video.durationMs=2000;p.assets.add(video);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="video-clip";clip.assetId=video.id;clip.inMs=0;clip.outMs=2000;p.clips.add(clip);
        androidx.media3.transformer.EditedMediaItemSequence sequence=new NativeRenderEngine(context)
                .buildPreviewComposition(p,"9:16","720p").sequences.get(0);
        assertTrue(sequence.trackTypes.contains(androidx.media3.common.C.TRACK_TYPE_AUDIO));
        assertTrue(sequence.forceAudioTrack);
        assertTrue(sequence.editedMediaItems.get(0).removeAudio);
        assertFalse(sequence.editedMediaItems.get(1).removeAudio);
    }

    @Test public void explicitColorPresetCombinesWithBlurEffectsInsteadOfBeingShadowed() throws Exception {
        NativeRenderEngine engine=new NativeRenderEngine(RuntimeEnvironment.getApplication());
        for(String blur:new String[]{"dream","gaussian_blur"}){
            ProjectStore.Clip clip=new ProjectStore.Clip();clip.inMs=0;clip.outMs=1000;
            clip.effects.put("colorPreset","warm_film").put("effectPreset",blur);
            java.util.List<androidx.media3.common.Effect> effects=engine.buildPreviewEffects(clip,"9:16","720p");
            assertTrue("Explicit color grade must still render with "+blur,effects.stream().anyMatch(e->e instanceof androidx.media3.effect.Brightness));
            assertTrue(effects.stream().anyMatch(e->e instanceof androidx.media3.effect.HslAdjustment));
            assertTrue(effects.stream().anyMatch(e->e instanceof androidx.media3.effect.GaussianBlur));
            clip.effects.put("colorPreset","none");
            assertFalse("Explicit none preserves neutral color",engine.buildPreviewEffects(clip,"9:16","720p")
                    .stream().anyMatch(e->e instanceof androidx.media3.effect.Brightness));
        }
    }

    @Test public void oldNormalizedKeysRenderWithoutChangingStoredDataButCanonicalWritesStayStrict() throws Exception {
        JSONArray old=new JSONArray().put(new JSONObject().put("t",0).put("x",0).put("rotation",0))
                .put(new JSONObject().put("t",1).put("x",.4).put("rotation",20));
        JSONObject effects=new JSONObject().put("keyframes",old);String before=effects.toString();
        StudioClipKeyframes compatible=StudioClipKeyframes.fromEffects(effects,1000);
        assertEquals(.2,compatible.at(500).x,.00001);assertEquals(10,compatible.at(500).rotate,.00001);
        assertEquals(before,effects.toString());
        try{StudioClipKeyframes.parse(old,1000);fail("Canonical edits accepted old normalized timestamps");}
        catch(IllegalArgumentException expected){}
        for(JSONArray invalid:new JSONArray[]{
                new JSONArray().put(new JSONObject().put("t",.2)).put(new JSONObject().put("t",.1)),
                new JSONArray().put(new JSONObject().put("t",0)).put(new JSONObject().put("t",1.01)),
                new JSONArray().put(new JSONObject().put("t","0")),
                new JSONArray().put(new JSONObject().put("t",.0001)).put(new JSONObject().put("t",.0002))}){
            try{StudioClipKeyframes.fromEffects(new JSONObject().put("keyframes",invalid),1000);fail("Malformed legacy keys accepted");}
            catch(IllegalArgumentException expected){}
        }
    }

    @Test public void timelineButtonsUseStableIdsAndRejectStaleProjectWithoutOverwritingMcp() throws Exception {
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);
        JSONObject duplicate=new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt)
                .put("action","duplicate").put("clipId",p.clips.get(0).id);
        JSONObject saved=StudioPrecisionEdits.applyTimeline(store,duplicate);
        assertEquals(2,store.get(p.id).clips.size());assertNotEquals("stable-clip",saved.getString("selectedClipId"));
        try{StudioPrecisionEdits.applyTimeline(store,duplicate);fail("Stale duplicate accepted");}catch(IllegalStateException expected){}
        assertEquals(2,store.get(p.id).clips.size());assertEquals(saved.getLong("updatedAt"),store.get(p.id).updatedAt);
    }

    @Test public void splitPreservesAnExactEasedKeyframeClockAcrossBothNewClips() throws Exception {
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);
        p.clips.get(0).effects.put("keyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("x",0).put("easing","ease_in"))
                .put(new JSONObject().put("timeMs",1000).put("x",1)));store.save(p);
        StudioPrecisionEdits.applyTimeline(store,new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt)
                .put("action","split").put("clipId","stable-clip").put("sourceTimeMs",500));
        ProjectStore.Project split=store.get(p.id);assertEquals(2,split.clips.size());
        assertEquals(.0625,StudioClipKeyframes.fromEffects(split.clips.get(0).effects,500).at(250).x,.00001);
        assertEquals(.5625,StudioClipKeyframes.fromEffects(split.clips.get(1).effects,500).at(250).x,.00001);
        assertEquals("owned-image",split.clips.get(0).assetId);assertEquals("owned-image",split.clips.get(1).assetId);
        assertEquals(1000,split.outputDurationMs());
    }

    private static JSONObject op(String clipId,JSONObject values)throws Exception{return new JSONObject().put("clipId",clipId).put("set",values);}
    private static ProjectStore.Project project(ProjectStore store){
        ProjectStore.Project p=store.create("Precision test");ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id="owned-image";asset.uri="file:///unused.png";asset.mime="image/png";asset.durationMs=3000;p.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="stable-clip";clip.assetId=asset.id;clip.inMs=0;clip.outMs=1000;p.clips.add(clip);store.save(p);return p;
    }
}
