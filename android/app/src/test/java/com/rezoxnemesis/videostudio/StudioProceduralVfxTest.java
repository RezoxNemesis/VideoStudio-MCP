package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Native Canvas evidence, not MP4/GPU or reference-quality acceptance.
 * Every effect must produce moving source-free pixels on explicit timestamps.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class StudioProceduralVfxTest {
    private StudioLayer emitter(String kind){
        StudioLayer fx=new StudioLayer();
        fx.type=StudioLayer.VFX;fx.id="vfx-"+kind;fx.vfxKind=kind;
        fx.startMs=1200;fx.durationMs=4400;
        fx.width=1;fx.height=1;fx.x=.5f;fx.y=.5f;
        fx.particleCount=48;fx.particleSeed=5788;
        fx.particleSpeed=1.4f;fx.particleSize=.016f;
        fx.color=0xffffd8a4;fx.validate();
        return fx;
    }
    private int[] pixels(StudioProceduralVfxOverlay fx,long timestampUs){
        Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        try{
            fx.onDraw(new Canvas(bitmap),timestampUs);
            int[] pixels=new int[256*256];
            bitmap.getPixels(pixels,0,256,0,0,256,256);
            return pixels;
        }finally{bitmap.recycle();}
    }
    private int occupied(int[] a){int count=0;for(int v:a)if((v>>>24)!=0)count++;return count;}
    private int changed(int[] a,int[] b){int diff=0;for(int i=0;i<a.length;i++)if(a[i]!=b[i])diff++;return diff;}

    @Test public void allNativeEffectsProduceActualMovingPixelsAcrossFrames(){
        for(String kind:new String[]{"rain","sparks","energy","embers","snow","confetti"}){
            StudioProceduralVfxOverlay fx=new StudioProceduralVfxOverlay(emitter(kind));
            int[] atA=pixels(fx,1_350_000L),atB=pixels(fx,1_870_000L);
            assertTrue(kind+" pixels should exist",occupied(atA)>18);
            assertTrue(kind+" must actually move",changed(atA,atB)>25);
            assertArrayEquals(kind+" must be deterministic at same timestamp",atA,pixels(fx,1_350_000L));
            assertEquals(kind+" should be hidden before lane starts",0,occupied(pixels(fx,1_199_000L)));
            assertEquals(kind+" should be hidden after lane ends",0,occupied(pixels(fx,5_601_000L)));
        }
    }

    @Test public void effectStateSurvivesNativeProjectSerializationWithoutChangingOldLayers(){
        StudioLayer fx=emitter("energy");
        fx.motionKeyframes=new JSONArray().put(new JSONObject().put("timeMs",0)
                .put("x",-.1).put("opacity",0).put("easing","ease_out"))
                .put(new JSONObject().put("timeMs",380)
                        .put("x",0).put("opacity",1).put("easing","linear"));
        fx.validate();
        StudioLayer restored=StudioLayer.fromJson(fx.toJson());
        assertEquals(StudioLayer.VFX,restored.type);
        assertEquals("energy",restored.vfxKind);
        assertEquals(48,restored.particleCount);
        assertEquals(5788,restored.particleSeed);
        assertEquals(1.4,restored.particleSpeed,.001);
        assertEquals(2,restored.motionKeyframes.length());
        StudioLayer existing=new StudioLayer();existing.type=StudioLayer.TITLE;existing.text="ORIGINAL";
        assertFalse(existing.toJson().has("vfxKind"));
        assertFalse(existing.toJson().has("particleCount"));
    }

    @Test public void malformedEmitterAndSourceImportsAreRefused(){
        assertThrows(IllegalArgumentException.class,()->{StudioLayer a=emitter("snow");a.particleCount=200;a.validate();});
        assertThrows(IllegalArgumentException.class,()->{StudioLayer a=emitter("sparks");a.particleSpeed=Float.NaN;a.validate();});
        assertThrows(IllegalArgumentException.class,()->{StudioLayer a=emitter("rain");a.assetId="gallery-video";a.validate();});
        assertThrows(IllegalArgumentException.class,()->{StudioLayer a=emitter("liquid_simulation");a.validate();});
        assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(
                new JSONObject().put("type","vfx").put("vfxKind","rain").put("galleryUrl","private")));
    }

    @Test public void nativeMedia3ComposerCreatesExactVfxEmitter() throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project project=store.create("Source-safe VFX");
            project.studioLayers.add(emitter("rain"));
            StudioCompositionOverlayEffect compositor=
                    new StudioCompositionOverlayEffect(RuntimeEnvironment.getApplication(),project);
            assertEquals(1,compositor.createOverlays().size());
            assertTrue(compositor.createOverlays().get(0) instanceof StudioProceduralVfxOverlay);
        }
    }

    @Test public void boundedVfxLanesPreventExcessiveRendererMemory() throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project project=store.create("Particle budget");
            ProjectStore.Asset image=new ProjectStore.Asset();
            image.id="original-image";image.mime="image/png";image.uri="file:///owned/image.png";
            project.assets.add(image);
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.assetId=image.id;clip.inMs=0;clip.outMs=6000;
            project.clips.add(clip);
            for(int i=0;i<5;i++){
                StudioLayer fx=emitter("embers");fx.id="fx-"+i;project.studioLayers.add(fx);
            }
            assertThrows(IllegalArgumentException.class,()->StudioLayerContract.fromJson(
                    project,StudioLayerContract.toJson(project.studioLayers)));
        }
    }

    @Test public void fusionVfxProgramMakesEditableProjectWithoutChangingOriginal() throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=store.create("No destructive edits");
            ProjectStore.Asset image=new ProjectStore.Asset();
            image.id="source.png";image.mime="image/png";image.uri="file:///owned/source.png";
            source.assets.add(image);
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.id="source-video-clip";clip.assetId=image.id;clip.inMs=0;clip.outMs=8000;
            source.clips.add(clip);
            store.save(source);source=store.get(source.id);
            String before=SketchDocument.canonical(source.toJson());
            JSONObject fxNode=new JSONObject().put("id","rainScene")
                    .put("type","vfx").put("kind","rain").put("startMs",0)
                    .put("durationMs",6500).put("x",.5).put("y",.5)
                    .put("width",1).put("height",1)
                    .put("particleCount",64).put("seed",921).put("speed",1.4)
                    .put("particleSize",.01).put("color",0xff87b9d4L);
            JSONObject request=new JSONObject().put("projectId",source.id)
                    .put("expectedUpdatedAt",source.updatedAt)
                    .put("operationId","native-particle-fusion-case-01")
                    .put("name","Rain motion pass").put("nodes",new JSONArray().put(fxNode));
            JSONObject plan=StudioFusionSceneCompiler.plan(source,request);
            assertEquals(1,plan.getInt("newVisualLayers"));
            assertTrue(plan.getBoolean("dryRun"));
            JSONObject applied=StudioFusionSceneCompiler.apply(store,request);
            ProjectStore.Project generated=store.get(applied.getString("outputProjectId"));
            assertEquals(1,generated.studioLayers.size());
            assertEquals("rain",generated.studioLayers.get(0).vfxKind);
            assertEquals(64,generated.studioLayers.get(0).particleCount);
            assertEquals(before,SketchDocument.canonical(store.get(source.id).toJson()));
        }
    }
}
