package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import androidx.media3.effect.TextureOverlay;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Native Skia and actual Media3 Overlay admission, without pretending that
 * pixel tests prove decoder/GPU hardware parity on the user's phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StudioTrackMatteTest {

    private static StudioLayer shape(String id,float width,float height) {
        StudioLayer shape=new StudioLayer();
        shape.id=id;shape.type=StudioLayer.SHAPE;shape.shapeKind="rect";
        shape.x=.5f;shape.y=.5f;shape.width=width;shape.height=height;
        shape.color=Color.WHITE;shape.strokeColor=Color.WHITE;
        shape.strokeWidth=0;shape.startMs=0;shape.durationMs=4000;
        shape.matteOnly=true;
        return shape;
    }
    private static StudioLayer image(){
        StudioLayer target=new StudioLayer();
        target.id="owner-image";target.type=StudioLayer.IMAGE;
        target.assetId="original-image";target.startMs=0;target.durationMs=4000;
        target.width=1f;target.height=1f;
        target.matteLayerId="original-matte";target.matteMode="alpha";
        return target;
    }
    private static Bitmap opaque(int width,int height){
        Bitmap pixels=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        pixels.eraseColor(Color.RED);return pixels;
    }

    @Test public void originalShapeActuallyCropsDecodedImageAlpha() {
        StudioLayer matte=shape("original-matte",.5f,.5f);
        StudioLayer child=image();
        Bitmap input=opaque(100,100);
        try{
            assertSame(input,StudioTrackMatteRasterizer.apply(input,child,matte));
            assertEquals(255,Color.alpha(input.getPixel(50,50)));
            assertEquals(0,Color.alpha(input.getPixel(0,0)));
            assertEquals(0,Color.alpha(input.getPixel(99,99)));
            assertEquals(255,Color.red(input.getPixel(50,50)));
        }finally{input.recycle();}
    }

    @Test public void invertedAlphaModeUsesSameSourceMatteAndNoExternalAssets() {
        StudioLayer matte=shape("original-matte",.5f,.5f);
        StudioLayer child=image();child.matteMode="alpha_inverted";
        Bitmap pixels=opaque(100,100);
        try{
            StudioTrackMatteRasterizer.apply(pixels,child,matte);
            assertEquals(0,Color.alpha(pixels.getPixel(50,50)));
            assertEquals(255,Color.alpha(pixels.getPixel(2,2)));
        }finally{pixels.recycle();}
    }

    @Test public void hiddenMatteIsNotExportedAsOwnGraphicOverlay()throws Exception {
        File original=new File(RuntimeEnvironment.getApplication().getFilesDir(),
                "track_matte_source_"+System.nanoTime()+".png");
        Bitmap image=opaque(64,64);
        try(FileOutputStream output=new FileOutputStream(original)){
            assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));
        }finally{image.recycle();}
        try{
            ProjectStore.Project project=new ProjectStore.Project();
            project.id="matte-composition";
            ProjectStore.Asset asset=new ProjectStore.Asset();
            asset.id="original-image";asset.mime="image/png";
            asset.uri=Uri.fromFile(original).toString();
            asset.name="Owned image";
            project.assets.add(asset);
            ProjectStore.Clip base=new ProjectStore.Clip();
            base.id="original-clip";base.assetId=asset.id;base.outMs=4000;
            project.clips.add(base);
            StudioLayer matte=shape("original-matte",.5f,.5f);
            project.studioLayers.add(matte);
            project.studioLayers.add(image());
            StudioLayerContract.fromJson(project,StudioLayerContract.toJson(project.studioLayers));
            StudioCompositionOverlayEffect effects=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),project);
            List<TextureOverlay> overlays=effects.createOverlays();
            try{
                assertEquals("Only the target is drawn; the matte itself must be hidden",1,overlays.size());
                assertTrue(overlays.get(0) instanceof StudioTimedImageOverlay);
                StudioTimedImageOverlay edited=(StudioTimedImageOverlay)overlays.get(0);
                Bitmap decoded=edited.getBitmap(1_000_000);
                assertEquals(255,Color.alpha(decoded.getPixel(32,32)));
                assertEquals(0,Color.alpha(decoded.getPixel(0,0)));
            }finally{
                for(TextureOverlay overlay:overlays)overlay.release();
            }
        }finally{assertTrue(original.delete());}
    }

    @Test public void originalLegacyMediaRecordsDoNotContainAnyMatteKeys(){
        StudioLayer still=new StudioLayer();still.type=StudioLayer.IMAGE;still.assetId="old-asset";
        JSONObject record=still.toJson();
        assertFalse(record.has("matteLayerId"));
        assertFalse(record.has("matteMode"));
        assertFalse(record.has("matteOnly"));
        assertEquals(record.toString(),StudioLayer.fromJson(record).toJson().toString());
        StudioLayer ring=shape("legacy-vector",.2f,.2f);ring.matteOnly=false;
        JSONObject ringRecord=ring.toJson();
        assertFalse(ringRecord.has("matteOnly"));
        assertEquals(ringRecord.toString(),StudioLayer.fromJson(ringRecord).toJson().toString());
    }

    @Test public void dynamicAndUnrelatedMatteSourcesAreRejectedWithoutPublishing()throws Exception{
        StudioLayer matte=shape("original-matte",.5f,.5f);
        StudioLayer child=image();
        matte.motionKeyframes=new JSONArray().put(
                new JSONObject().put("timeMs",0).put("x",.2));
        assertThrows(IllegalArgumentException.class,()->
                StudioTrackMatteRasterizer.validatePair(child,matte));
        matte.motionKeyframes=null;
        matte.parentId="moving-group";
        assertThrows(IllegalArgumentException.class,()->
                StudioTrackMatteRasterizer.validatePair(child,matte));
        matte.parentId="";
        child.motionKeyframes=new JSONArray().put(
                new JSONObject().put("timeMs",0).put("x",.2));
        assertThrows(IllegalArgumentException.class,()->
                StudioTrackMatteRasterizer.validatePair(child,matte));
        child.motionKeyframes=null;
        child.matteLayerId="another-missing-matte";
        assertThrows(IllegalArgumentException.class,()->
                StudioTrackMatteRasterizer.validatePair(child,matte));
        child.matteLayerId=matte.id;child.durationMs=5000;
        assertThrows(IllegalArgumentException.class,()->
                StudioTrackMatteRasterizer.validatePair(child,matte));
    }

    @Test public void malformedReferencesAndUnsupportedVideoMattesFail() throws Exception {
        StudioLayer image=image();
        image.matteLayerId=image.id;
        assertThrows(IllegalArgumentException.class,image::validate);
        assertThrows(IllegalArgumentException.class,()->
                StudioLayer.fromJson(new JSONObject().put("type","image")
                        .put("assetId","owned").put("matteLayerId",12)));
        assertThrows(IllegalArgumentException.class,()->
                StudioLayer.fromJson(new JSONObject().put("type","video")
                        .put("audioMode","silent").put("assetId","owned")
                        .put("matteLayerId","shape")));
        StudioLayer source=shape("matte",.5f,.5f);
        source.matteLayerId="another";
        assertThrows(IllegalArgumentException.class,source::validate);
    }
}
