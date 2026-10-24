package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioPixelAndImageLayerTest {
    @Test public void animatedOpacityUsesExactClipClockAndDefaultsPreserveOpaquePixels() throws Exception {
        StudioPixelEffect plain=new StudioPixelEffect(new JSONObject(),1000);
        assertEquals(1,plain.opacityAtUs(750_000),0);
        JSONObject fx=new JSONObject().put("keyframes",new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("opacity",1))
                .put(new JSONObject().put("timeMs",1000).put("opacity",0)));
        assertEquals(.25,new StudioPixelEffect(fx,1000).opacityAtUs(750_000),.00001);
        assertFalse(plain.hasPixelChanges());
        assertTrue(new StudioPixelEffect(fx,1000).hasPixelChanges());
    }

    @Test public void rejectsInvalidShaderParametersBeforeNativeRendering() throws Exception {
        for(JSONObject invalid:new JSONObject[]{new JSONObject().put("chromaKey","true"),
                new JSONObject().put("chromaColor","green"),new JSONObject().put("chromaTolerance",1.1),
                new JSONObject().put("maskShape","magic"),new JSONObject().put("maskLeft",.8).put("maskRight",.2),
                new JSONObject().put("glowStrength",3),new JSONObject().put("opacity",-.1)}){
            try{new StudioPixelEffect(invalid,1000);fail("Invalid shader parameters accepted: "+invalid);}
            catch(IllegalArgumentException expected){}
        }
    }

    @Test public void imageLaneMustBindAnImageInTheSameProject() throws Exception {
        ProjectStore.Project p=project();StudioLayer lane=image();lane.assetId="foreign-image";
        try{StudioLayerContract.fromJson(p,new JSONArray().put(lane.toJson()));fail("Foreign overlay accepted");}
        catch(IllegalArgumentException expected){}
        lane.assetId=p.assets.get(0).id;
        StudioLayer saved=StudioLayerContract.fromJson(p,new JSONArray().put(lane.toJson())).get(0);
        assertEquals(StudioLayer.IMAGE,saved.type);assertEquals(.75,saved.x,.00001);
        assertEquals(.35,saved.width,.00001);assertEquals(.6,saved.opacity,.00001);
        saved.startMs=950;saved.durationMs=100;
        try{StudioLayerContract.validate(p,saved);fail("Overlay outside timeline accepted");}
        catch(IllegalArgumentException expected){}
    }

    @Test public void overlayLoadsOwnPixelsOnlyDuringAuthoredSpanAndReleasesBitmap() throws Exception {
        File file=new File(RuntimeEnvironment.getApplication().getCacheDir(),UUID.randomUUID()+".png");
        Bitmap original=Bitmap.createBitmap(2,1,Bitmap.Config.ARGB_8888);
        original.setPixel(0,0,0xffff0000);original.setPixel(1,0,0xff0000ff);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,out));}
        ProjectStore.Project p=project();p.assets.get(0).uri=android.net.Uri.fromFile(file).toString();
        StudioLayer lane=image();lane.assetId=p.assets.get(0).id;lane.startMs=100;lane.durationMs=200;
        StudioTimedImageOverlay overlay=new StudioTimedImageOverlay(RuntimeEnvironment.getApplication(),p.assets.get(0),lane);
        overlay.configure(new androidx.media3.common.util.Size(100,100));
        assertEquals(0,overlay.getOverlaySettings(99_999).getAlphaScale(),0);
        assertEquals(.6,overlay.getOverlaySettings(100_000).getAlphaScale(),.00001);
        Bitmap rendered=overlay.getBitmap(150_000);
        assertEquals(0xffff0000,rendered.getPixel(0,0));assertEquals(0xff0000ff,rendered.getPixel(1,0));
        assertEquals(0,overlay.getOverlaySettings(300_000).getAlphaScale(),0);
        overlay.release();assertTrue(rendered.isRecycled());
        original.recycle();assertTrue(file.delete());
    }

    @Test public void imageOverlayIsARealCompositionEffectAlongsideTimedTitles() throws Exception {
        ProjectStore.Project p=project();StudioLayer lane=image();lane.assetId=p.assets.get(0).id;p.studioLayers.add(lane);
        androidx.media3.transformer.Composition composition=new NativeRenderEngine(RuntimeEnvironment.getApplication())
                .buildPreviewComposition(p,"9:16","720p");
        assertTrue(composition.effects.videoEffects.stream().anyMatch(e->e instanceof StudioCompositionOverlayEffect));
    }

    @Test public void allPixelControlsAreBoundToTheSharedNativeEffectBuilder() throws Exception {
        ProjectStore.Project p=project();p.clips.get(0).effects.put("chromaKey",true).put("chromaColor","#00FF00")
                .put("maskShape","ellipse").put("glowStrength",.5).put("opacity",.75);
        java.util.List<androidx.media3.common.Effect> effects=new NativeRenderEngine(RuntimeEnvironment.getApplication())
                .buildPreviewEffects(p.clips.get(0),"9:16","720p");
        StudioPixelEffect actual=(StudioPixelEffect)effects.stream().filter(e->e instanceof StudioPixelEffect).findFirst().get();
        assertTrue(actual.chromaKey);assertEquals(2,actual.maskShape);assertEquals(.75,actual.opacityAtUs(0),0);
        assertEquals(.5,actual.glowStrength,0);assertArrayEquals(new float[]{0,1,0},actual.keyColor,0);
    }

    @Test public void invalidImageLayerBatchDoesNotReplaceExistingTracks() throws Exception {
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project();store.save(p);
        long revision=p.updatedAt;StudioLayer valid=image();valid.assetId=p.assets.get(0).id;
        StudioLayer foreign=image();foreign.assetId="foreign";
        JSONObject request=new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",revision)
                .put("studioLayers",new JSONArray().put(valid.toJson()).put(foreign.toJson()));
        try{StudioLayerContract.apply(store,request);fail("Foreign image accepted");}catch(IllegalArgumentException expected){}
        assertEquals(revision,store.get(p.id).updatedAt);assertTrue(store.get(p.id).studioLayers.isEmpty());
        request.put("studioLayers",new JSONArray().put(valid.toJson()));
        JSONObject saved=StudioLayerContract.apply(store,request);
        assertEquals(valid.id,saved.getJSONArray("layerIds").getString(0));assertFalse(saved.has("project"));
        try{StudioLayerContract.apply(store,request);fail("Stale lane save accepted");}catch(IllegalStateException expected){}
    }

    private static StudioLayer image(){StudioLayer lane=new StudioLayer();lane.type=StudioLayer.IMAGE;
        lane.startMs=0;lane.durationMs=500;lane.x=.75f;lane.opacity=.6f;return lane;}
    private static ProjectStore.Project project(){
        ProjectStore.Project p=new ProjectStore.Project();p.id=UUID.randomUUID().toString();p.name="Image layers";
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="owned-image";a.uri="file:///unused.png";a.mime="image/png";p.assets.add(a);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="clip";c.assetId=a.id;c.inMs=0;c.outMs=1000;p.clips.add(c);return p;
    }
}
