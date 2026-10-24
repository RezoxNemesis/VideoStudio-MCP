package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.transformer.Composition;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Real Canvas pixels + native Media3 compositor admission, no claimed phone GPU output. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioShapeCompositorTest {
    private static ProjectStore.Project source() {
        ProjectStore.Project project=new ProjectStore.Project();
        project.id="source-shape-project";
        ProjectStore.Asset original=new ProjectStore.Asset();
        original.id="original-still";original.mime="image/png";
        original.name="User artwork";original.uri="file:///owner/unchanged.png";
        project.assets.add(original);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="original-clip";clip.assetId=original.id;
        clip.inMs=0;clip.outMs=5200;project.clips.add(clip);
        return project;
    }

    @Test public void sixNativeShapesProduceActualNonTransparentPixels() throws Exception {
        for(String kind:new String[]{"grid","ring","ellipse","rect","diamond","line"}) {
            StudioLayer shape=StudioShapePresets.create(kind,"drift",0,3000);
            Bitmap pixels=StudioTimedShapeOverlay.drawOriginalShape(shape);
            try {
                assertEquals(StudioTimedShapeOverlay.RASTER_SIZE,pixels.getWidth());
                assertEquals(StudioTimedShapeOverlay.RASTER_SIZE,pixels.getHeight());
                int nonTransparent=0;
                for(int y=0;y<pixels.getHeight();y+=6)
                    for(int x=0;x<pixels.getWidth();x+=6)
                        if(Color.alpha(pixels.getPixel(x,y))>0)nonTransparent++;
                assertTrue("Actual "+kind+" native geometry must be visible",nonTransparent>0);
            }finally{pixels.recycle();}
        }
    }

    @Test public void originalShapeBitmapIsCachedAndMotionHasAnExactTimeWindow() throws Exception {
        StudioLayer shape=StudioShapePresets.create("grid","drift",300,2000);
        StudioTimedShapeOverlay overlay=new StudioTimedShapeOverlay(shape);
        try {
            overlay.configure(new Size(1080,1920));
            Bitmap a=overlay.getBitmap(300_000);
            Bitmap b=overlay.getBitmap(1_300_000);
            assertSame("Do not allocate a new image on each composition frame",a,b);
            assertEquals(0f,overlay.getOverlaySettings(299_999).getAlphaScale(),.00001f);
            assertTrue(overlay.getOverlaySettings(850_000).getAlphaScale()>.1f);
            assertEquals(0f,overlay.getOverlaySettings(2_300_000).getAlphaScale(),.00001f);
        }finally{overlay.release();}
    }

    @Test public void shapeRecordSurvivesStoreRoundTripWithoutExternalMedia() throws Exception {
        ProjectStore.Project source=source();
        String original=SketchDocument.canonical(source.toJson());
        StudioLayer shape=StudioShapePresets.create("ring","spin",1200,2200);
        assertTrue(shape.assetId.isEmpty());
        assertEquals(4,shape.motionKeyframes.length());
        JSONObject serialized=shape.toJson();
        assertEquals("shape",serialized.getString("type"));
        assertFalse(serialized.has("assetId"));
        assertEquals("ring",StudioLayer.fromJson(serialized).shapeKind);
        source.studioLayers.add(shape);
        ProjectStore.Project recovered=ProjectStore.Project.fromJson(source.toJson());
        assertEquals(1,recovered.studioLayers.size());
        assertEquals(shape.id,recovered.studioLayers.get(0).id);
        StudioLayerContract.fromJson(recovered,StudioLayerContract.toJson(recovered.studioLayers));
        assertEquals("file:///owner/unchanged.png",recovered.assets.get(0).uri);
        assertNotEquals(original,SketchDocument.canonical(recovered.toJson()));
    }

    @Test public void nativeCompositionActuallyIncludesTheAnimatedOriginalShape() throws Exception {
        ProjectStore.Project project=source();
        StudioLayer shape=StudioShapePresets.create("diamond","pulse",500,2200);
        project.studioLayers.add(shape);
        Composition result=new NativeRenderEngine(RuntimeEnvironment.getApplication())
                .buildPreviewComposition(project,"9:16","720p");
        assertEquals(1,result.sequences.size());
        assertEquals(1,result.effects.videoEffects.size());
        assertTrue(result.effects.videoEffects.get(0) instanceof StudioCompositionOverlayEffect);
        StudioCompositionOverlayEffect fx=(StudioCompositionOverlayEffect)result.effects.videoEffects.get(0);
        assertFalse(fx.isEmpty());
        assertEquals(1,fx.createOverlays().size());
        assertTrue(fx.createOverlays().get(0) instanceof StudioTimedShapeOverlay);
    }

    @Test public void unsupportedOrOversizedShapesAreRejectedBeforeProjectMutation() throws Exception {
        ProjectStore.Project project=source();
        ArrayList<StudioLayer> original=new ArrayList<>(project.studioLayers);
        StudioLayer bad=StudioShapePresets.create("grid","pulse",0,2000);
        JSONObject forged=bad.toJson().put("uri","file:///private-source.png");
        assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(forged));
        bad.shapeKind="four-dimensional-lightfield";
        assertThrows(IllegalArgumentException.class,bad::validate);
        for(int i=0;i<13;i++){
            StudioLayer copy=StudioShapePresets.create("grid","hold",0,2000);
            project.studioLayers.add(copy);
        }
        assertThrows(IllegalArgumentException.class,()->
                StudioLayerContract.fromJson(project,StudioLayerContract.toJson(project.studioLayers)));
        assertTrue(original.isEmpty());
    }

    @Test public void legacyImageTitleAndAudioSerializationRemainUnchanged() throws Exception {
        for(String kind:new String[]{StudioLayer.TITLE,StudioLayer.IMAGE,StudioLayer.AUDIO}) {
            StudioLayer old=new StudioLayer();
            old.type=kind;old.assetId="existing-import";
            if(StudioLayer.TITLE.equals(kind))old.text="UNTITLED";
            JSONObject record=old.toJson();
            assertFalse(record.has("shapeKind"));
            assertFalse(record.has("strokeColor"));
            assertFalse(record.has("strokeWidth"));
            assertEquals(record.toString(),StudioLayer.fromJson(record).toJson().toString());
        }
    }

    @Test public void declaredNativeShapeCapabilitiesDoNotPretendToBe3D() {
        JSONObject c=StudioEditingCapabilities.capabilities();
        JSONObject shape=c.optJSONObject("nativeVectorShapes");
        assertNotNull(shape);
        assertEquals(12,shape.optInt("maxCachedShapes"));
        assertEquals("shape",shape.optString("type"));
        assertFalse(shape.optBoolean("true3DWarp",true));
        assertFalse(shape.optBoolean("galleryAccess",true));
    }
}
