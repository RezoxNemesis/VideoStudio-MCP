package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.net.Uri;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.effect.OverlayEffect;
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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual native image pixels/settings, not an assertion of hardware GPU or codec execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioImageMotionTest {
    @Test public void omittedAndIdentityMotionRetainExactLegacyGeometryAndSourcePixels() throws Exception {
        Fixture f=fixture();
        StudioLayer plain=StudioLayer.fromJson(layer());
        StudioLayer identity=StudioLayer.fromJson(layer().put("motionKeyframes",new JSONArray()
                .put(key(0)).put(key(1000).put("scale",1).put("opacity",1))));
        StudioTimedImageOverlay old=overlay(f,plain),keyed=overlay(f,identity);
        assertFalse(plain.toJson().has("motionKeyframes"));
        for(long t:new long[]{250_000,250_001,750_500,1_249_999})
            assertSettingsExact(old.getOverlaySettings(t),keyed.getOverlaySettings(t));
        Bitmap actual=keyed.getBitmap(750_000);
        assertEquals(4,actual.getWidth());assertEquals(2,actual.getHeight());
        assertEquals(0xffff0000,actual.getPixel(0,0));assertEquals(0xff0000ff,actual.getPixel(3,0));
        assertEquals(0,actual.getPixel(1,1));assertEquals(0xff00ff00,actual.getPixel(3,1));
        assertArrayEquals(f.originalBytes,Files.readAllBytes(f.file.toPath()));
        old.release();keyed.release();assertTrue(actual.isRecycled());
    }

    @Test public void explicitEmptyMotionSurvivesAsClearAndUsesExactStaticSettings() throws Exception {
        Fixture f=fixture();StudioLayer plain=StudioLayer.fromJson(layer());
        StudioLayer cleared=StudioLayer.fromJson(layer().put("motionKeyframes",new JSONArray()));
        assertTrue(cleared.toJson().has("motionKeyframes"));
        assertEquals(0,cleared.copy().toJson().getJSONArray("motionKeyframes").length());
        StudioTimedImageOverlay a=overlay(f,plain),b=overlay(f,cleared);
        assertEquals(a.getOverlaySettings(750_000).getClass(),b.getOverlaySettings(750_000).getClass());
        assertSettingsExact(a.getOverlaySettings(750_000),b.getOverlaySettings(750_000));
        a.release();b.release();
    }

    @Test public void relativeChannelsComposeAtLocalSubmillisecondClockOnNonSquareRaster() throws Exception {
        Fixture f=fixture();
        JSONArray motion=new JSONArray().put(key(0)).put(key(1000).put("x",.2).put("y",-.1)
                .put("scaleX",2).put("scaleY",.5).put("rotate",80).put("opacity",.4));
        StudioTimedImageOverlay overlay=overlay(f,StudioLayer.fromJson(layer().put("motionKeyframes",motion)));
        OverlaySettings mid=overlay.getOverlaySettings(750_000);
        assertEquals(.2,mid.getBackgroundFrameAnchor().first,.000001);
        assertEquals(.1,mid.getBackgroundFrameAnchor().second,.000001);
        assertEquals(37.5,mid.getScale().first,.000001);
        assertEquals(9.375,mid.getScale().second,.000001);
        assertEquals(50,mid.getRotationDegrees(),0);
        assertEquals(.35,mid.getAlphaScale(),.000001);
        OverlaySettings halfMillisecond=overlay.getOverlaySettings(250_500);
        assertEquals(.0002,halfMillisecond.getBackgroundFrameAnchor().first,.00000001);
        assertEquals(.0001,halfMillisecond.getBackgroundFrameAnchor().second,.00000001);
        assertEquals(10.04,halfMillisecond.getRotationDegrees(),.000001);
        assertEquals(0,mid.getOverlayFrameAnchor().first,0);assertEquals(0,mid.getOverlayFrameAnchor().second,0);
        overlay.release();
    }

    @Test public void allOutgoingEasingsAndExactKeyBoundariesUseTheSharedClipConvention() throws Exception {
        Fixture f=fixture();String[] easing={"linear","ease_in","ease_out","ease_in_out","hold"};
        double[] progress={.25,.0625,.4375,.15625,0};
        for(int i=0;i<easing.length;i++){
            JSONArray keys=new JSONArray().put(key(0).put("easing",easing[i])).put(key(1000).put("x",.4));
            StudioTimedImageOverlay overlay=overlay(f,StudioLayer.fromJson(layer().put("motionKeyframes",keys)));
            assertEquals(easing[i],.8*progress[i],overlay.getOverlaySettings(500_000).getBackgroundFrameAnchor().first,.000001);
            assertEquals(easing[i],"hold".equals(easing[i])?0:.8,
                    overlay.getOverlaySettings(1_249_999).getBackgroundFrameAnchor().first,.00001);
            overlay.release();
        }
        // A key exactly at a visible interior boundary takes its own value even after HOLD.
        JSONArray keys=new JSONArray().put(key(0).put("x",.2).put("easing","hold"))
                .put(key(500).put("x",-.2)).put(key(1000));
        StudioTimedImageOverlay overlay=overlay(f,StudioLayer.fromJson(layer().put("motionKeyframes",keys)));
        assertEquals(.4,overlay.getOverlaySettings(749_999).getBackgroundFrameAnchor().first,.000001);
        assertEquals(-.4,overlay.getOverlaySettings(750_000).getBackgroundFrameAnchor().first,.000001);
        overlay.release();
    }

    @Test public void omittedKeyChannelsReturnToIdentityInsteadOfCarryingPriorValues() throws Exception {
        Fixture f=fixture();JSONArray keys=new JSONArray().put(key(0).put("x",.2).put("scale",2).put("opacity",.2))
                .put(key(500)).put(key(1000).put("rotate",90));
        StudioTimedImageOverlay keyed=overlay(f,StudioLayer.fromJson(layer().put("motionKeyframes",keys)));
        StudioTimedImageOverlay plain=overlay(f,StudioLayer.fromJson(layer()));
        assertSettingsExact(plain.getOverlaySettings(750_000),keyed.getOverlaySettings(750_000));
        keyed.release();plain.release();
    }

    @Test public void uniformScaleKeepsAxisOverridesAndSmallStaticImagesDoNotBecomeInvalidScales() throws Exception {
        Fixture f=fixture();JSONArray keys=new JSONArray().put(key(0).put("scale",2).put("scaleY",3));
        StudioTimedImageOverlay axes=overlay(f,StudioLayer.fromJson(layer().put("motionKeyframes",keys)));
        assertEquals(50,axes.getOverlaySettings(250_000).getScale().first,0);
        assertEquals(37.5,axes.getOverlaySettings(250_000).getScale().second,0);axes.release();
        StudioTimedImageOverlay tiny=overlay(f,StudioLayer.fromJson(layer().put("width",.01).put("height",.01)
                .put("motionKeyframes",new JSONArray().put(key(0).put("scale",.1)))));
        assertEquals(.05,tiny.getOverlaySettings(250_000).getScale().first,.000001);
        assertEquals(.05,tiny.getOverlaySettings(250_000).getScale().second,.000001);tiny.release();
    }

    @Test public void offscreenPanelsRemainOffscreenWithoutClampingOrRejectedBuilderAnchors() throws Exception {
        Fixture f=fixture();JSONArray keys=new JSONArray().put(key(0).put("x",-2).put("y",2))
                .put(key(1000).put("x",2).put("y",-2));
        StudioTimedImageOverlay overlay=overlay(f,StudioLayer.fromJson(layer().put("motionKeyframes",keys)));
        OverlaySettings first=overlay.getOverlaySettings(250_000);
        assertEquals(-4,first.getBackgroundFrameAnchor().first,0);assertEquals(-4,first.getBackgroundFrameAnchor().second,0);
        OverlaySettings halfway=overlay.getOverlaySettings(750_000);
        assertEquals(0,halfway.getBackgroundFrameAnchor().first,0);assertEquals(0,halfway.getBackgroundFrameAnchor().second,0);
        overlay.release();
    }

    @Test public void inactiveHalfOpenSpansNeverDecodeSourceAndStaggeredLayersHaveIndependentLocalClocks() throws Exception {
        Fixture missing=fixture();assertTrue(missing.file.delete());
        StudioTimedImageOverlay inactive=overlay(missing,StudioLayer.fromJson(layer().put("motionKeyframes",new JSONArray().put(key(0).put("x",2)))));
        assertEquals(0,inactive.getOverlaySettings(249_999).getAlphaScale(),0);
        assertEquals(0,inactive.getOverlaySettings(1_250_000).getAlphaScale(),0);
        assertEquals(0,inactive.getBitmap(249_999).getPixel(0,0));inactive.release();
        Fixture f=fixture();JSONArray keys=new JSONArray().put(key(0)).put(key(1000).put("x",.4));
        StudioTimedImageOverlay early=overlay(f,StudioLayer.fromJson(layer().put("motionKeyframes",keys)));
        StudioTimedImageOverlay later=overlay(f,StudioLayer.fromJson(layer().put("id","later").put("startMs",750).put("motionKeyframes",keys)));
        assertEquals(.4,early.getOverlaySettings(750_000).getBackgroundFrameAnchor().first,.000001);
        assertEquals(0,later.getOverlaySettings(750_000).getBackgroundFrameAnchor().first,0);
        early.release();later.release();
    }

    @Test public void motionRecipeIsDetachedThroughParsingCopyAndSerialization() throws Exception {
        JSONObject end=key(1000).put("x",.2);JSONArray keys=new JSONArray().put(key(0)).put(end);
        StudioLayer parsed=StudioLayer.fromJson(layer().put("motionKeyframes",keys));
        end.put("x",1);assertEquals(.2,parsed.toJson().getJSONArray("motionKeyframes").getJSONObject(1).getDouble("x"),0);
        StudioLayer copy=parsed.copy();JSONObject serialized=parsed.toJson();
        serialized.getJSONArray("motionKeyframes").getJSONObject(1).put("x",-1);
        assertEquals(.2,parsed.toJson().getJSONArray("motionKeyframes").getJSONObject(1).getDouble("x"),0);
        assertEquals(.2,copy.toJson().getJSONArray("motionKeyframes").getJSONObject(1).getDouble("x"),0);
    }

    @Test public void malformedMotionIsStrictAndCannotCrossIntoTitlesOrAudio() throws Exception {
        for(Object raw:new Object[]{JSONObject.NULL,"[]",new JSONObject(),1,true})
            assertThrows("motionKeyframes type "+raw,IllegalArgumentException.class,()->StudioLayer.fromJson(layer().put("motionKeyframes",raw)));
        for(String type:new String[]{"title","audio"})
            assertThrows("Non-image explicit keys",IllegalArgumentException.class,()->StudioLayer.fromJson(layer().put("type",type).put("text","Hello").put("motionKeyframes",new JSONArray())));
        for(String field:new String[]{"anchorX","anchorY","width","rotation","perspective","unknown"})
            assertThrows(field,IllegalArgumentException.class,()->StudioLayer.fromJson(layer().put("motionKeyframes",new JSONArray().put(key(0).put(field,.5)))));
        for(Object raw:new Object[]{JSONObject.NULL,"1",true})
            assertThrows("Motion numeric type",IllegalArgumentException.class,()->StudioLayer.fromJson(layer().put("motionKeyframes",new JSONArray().put(key(0).put("x",raw)))));
        for(Object raw:new Object[]{JSONObject.NULL,"bezier",1})
            assertThrows("Motion easing",IllegalArgumentException.class,()->StudioLayer.fromJson(layer().put("motionKeyframes",new JSONArray().put(key(0).put("easing",raw)))));
    }

    @Test public void rejectsUnorderedOutOfClockOrOutOfRangeKeysAndAcceptsAll64BoundaryKeys() throws Exception {
        for(JSONArray invalid:new JSONArray[]{new JSONArray().put(key(0)).put(key(0)),new JSONArray().put(key(2)).put(key(1)),
                new JSONArray().put(key(-1)),new JSONArray().put(key(1001)),new JSONArray().put(new JSONObject().put("timeMs",.5)),
                new JSONArray().put(new JSONObject().put("x",0)),new JSONArray().put("key"),
                new JSONArray().put(key(0).put("x",2.01)),new JSONArray().put(key(0).put("y",-2.01)),
                new JSONArray().put(key(0).put("scale",.09)),new JSONArray().put(key(0).put("scaleX",8.01)),
                new JSONArray().put(key(0).put("rotate",360.01)),new JSONArray().put(key(0).put("opacity",1.01))})
            assertThrows("Invalid motion "+invalid,IllegalArgumentException.class,()->StudioLayer.fromJson(layer().put("motionKeyframes",invalid)));
        JSONArray allowed=new JSONArray();for(int i=0;i<64;i++)allowed.put(key(i));
        assertEquals(64,StudioLayer.fromJson(layer().put("motionKeyframes",allowed)).toJson().getJSONArray("motionKeyframes").length());
        allowed.put(key(64));assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(layer().put("motionKeyframes",allowed)));
    }

    @Test public void atomicLaneReplacementRejectsMalformedOrStaleMotionAndRetainsSourceOwnership() throws Exception {
        Fixture f=fixture();ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project project=project(f);store.save(project);
        JSONObject before=store.get(project.id).toJson();long revision=project.updatedAt;
        JSONObject valid=layer().put("motionKeyframes",new JSONArray().put(key(0)).put(key(1000).put("x",.2)));
        JSONObject bad=layer().put("id","bad").put("motionKeyframes",new JSONArray().put(key(1001)));
        JSONObject request=new JSONObject().put("projectId",project.id).put("expectedUpdatedAt",revision)
                .put("studioLayers",new JSONArray().put(valid).put(bad));
        assertThrows(IllegalArgumentException.class,()->StudioLayerContract.apply(store,request));
        assertEquals(before.toString(),store.get(project.id).toJson().toString());
        JSONObject foreign=layer().put("assetId","foreign").put("motionKeyframes",new JSONArray().put(key(0)));
        request.put("studioLayers",new JSONArray().put(foreign));
        assertThrows(IllegalArgumentException.class,()->StudioLayerContract.apply(store,request));
        assertEquals(revision,store.get(project.id).updatedAt);
        request.put("studioLayers",new JSONArray().put(valid));JSONObject receipt=StudioLayerContract.apply(store,request);
        assertTrue(receipt.getLong("updatedAt")>revision);
        assertEquals(2,store.get(project.id).studioLayers.get(0).toJson().getJSONArray("motionKeyframes").length());
        assertThrows(IllegalStateException.class,()->StudioLayerContract.apply(store,request));
        assertArrayEquals(f.originalBytes,Files.readAllBytes(f.file.toPath()));
    }

    @Test public void saveReopenAndOrdinaryUndoRedoRetainRecipeAndEverySampledSetting() throws Exception {
        Fixture f=fixture();ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project original=project(f);store.save(original);
        StudioEditHistory history=new StudioEditHistory();history.checkpoint(original);
        JSONObject keyed=layer().put("motionKeyframes",new JSONArray().put(key(0)).put(key(1000).put("rotate",80).put("opacity",.2)));
        StudioLayerContract.apply(store,new JSONObject().put("projectId",original.id).put("expectedUpdatedAt",original.updatedAt).put("studioLayers",new JSONArray().put(keyed)));
        ProjectStore.Project edited=new ProjectStore(RuntimeEnvironment.getApplication()).get(original.id);
        assertEquals(2,edited.studioLayers.get(0).toJson().getJSONArray("motionKeyframes").length());
        ProjectStore.Project undone=history.undo(edited);assertTrue(undone.studioLayers.isEmpty());
        store.save(undone);ProjectStore.Project redone=history.redo(undone);store.save(redone);
        StudioTimedImageOverlay a=overlay(f,edited.studioLayers.get(0)),b=overlay(f,new ProjectStore(RuntimeEnvironment.getApplication()).get(original.id).studioLayers.get(0));
        for(long time:new long[]{250_000,250_500,500_000,750_000,1_249_999})assertSettingsExact(a.getOverlaySettings(time),b.getOverlaySettings(time));
        assertEquals(2000,redone.outputDurationMs());assertEquals(f.asset.uri,redone.assets.get(0).uri);
        a.release();b.release();
    }

    @Test public void sharedNativeCompositionAttachesTheKeyedOverlayAlongsideTitles() throws Exception {
        Fixture f=fixture();ProjectStore.Project project=project(f);
        StudioLayer motion=StudioLayer.fromJson(layer().put("motionKeyframes",new JSONArray().put(key(0)).put(key(1000).put("x",.2))));
        project.studioLayers.add(motion);StudioLayer caption=new StudioLayer();caption.type=StudioLayer.TITLE;caption.text="A real caption";caption.durationMs=1000;project.studioLayers.add(caption);
        androidx.media3.transformer.Composition composition=new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(project,"1:1","720p");
        OverlayEffect effect=(OverlayEffect)composition.effects.videoEffects.stream().filter(e->e instanceof OverlayEffect).findFirst().get();
        // Inspect actual attached TextureOverlay instances; no replacement compositor or test renderer.
        Field field=OverlayEffect.class.getDeclaredField("overlays");field.setAccessible(true);
        List<?> overlays=(List<?>)field.get(effect);assertEquals(2,overlays.size());
        StudioTimedImageOverlay attached=(StudioTimedImageOverlay)overlays.stream().filter(o->o instanceof StudioTimedImageOverlay).findFirst().get();
        attached.configure(new Size(200,100));assertEquals(.2,attached.getOverlaySettings(750_000).getBackgroundFrameAnchor().first,.000001);
        assertTrue(overlays.stream().anyMatch(o->o instanceof StudioTimedTitleOverlay));attached.release();
    }

    private static JSONObject key(long time)throws Exception{return new JSONObject().put("timeMs",time);}
    private static JSONObject layer()throws Exception{return new JSONObject().put("id","panel").put("type","image").put("assetId","owned-image")
            .put("startMs",250).put("durationMs",1000).put("x",.5).put("y",.5).put("width",.5).put("height",.25).put("rotation",10).put("opacity",.5);}
    private static StudioTimedImageOverlay overlay(Fixture f,StudioLayer lane){
        StudioTimedImageOverlay overlay=new StudioTimedImageOverlay(RuntimeEnvironment.getApplication(),f.asset,lane);overlay.configure(new Size(200,100));return overlay;
    }
    private static final class Fixture{File file;ProjectStore.Asset asset;byte[] originalBytes;}
    private static Fixture fixture()throws Exception{
        Fixture f=new Fixture();f.file=new File(RuntimeEnvironment.getApplication().getCacheDir(),"native-image-motion-"+UUID.randomUUID()+".png");
        Bitmap source=Bitmap.createBitmap(4,2,Bitmap.Config.ARGB_8888);source.eraseColor(0xffffff00);
        source.setPixel(0,0,0xffff0000);source.setPixel(3,0,0xff0000ff);source.setPixel(1,1,0);source.setPixel(3,1,0xff00ff00);
        try(FileOutputStream out=new FileOutputStream(f.file)){assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,out));}source.recycle();
        f.originalBytes=Files.readAllBytes(f.file.toPath());f.asset=new ProjectStore.Asset();f.asset.id="owned-image";f.asset.mime="image/png";f.asset.uri=Uri.fromFile(f.file).toString();return f;
    }
    private static ProjectStore.Project project(Fixture f){
        ProjectStore.Project p=new ProjectStore.Project();p.id=UUID.randomUUID().toString();p.name="Native image motion";p.assets.add(f.asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="base";clip.assetId=f.asset.id;clip.inMs=0;clip.outMs=2000;p.clips.add(clip);return p;
    }
    private static void assertSettingsExact(OverlaySettings a,OverlaySettings b){
        assertEquals(a.getBackgroundFrameAnchor(),b.getBackgroundFrameAnchor());assertEquals(a.getOverlayFrameAnchor(),b.getOverlayFrameAnchor());
        assertEquals(a.getScale(),b.getScale());assertEquals(Float.floatToIntBits(a.getRotationDegrees()),Float.floatToIntBits(b.getRotationDegrees()));
        assertEquals(Float.floatToIntBits(a.getAlphaScale()),Float.floatToIntBits(b.getAlphaScale()));
        assertEquals(Float.floatToIntBits(a.getHdrLuminanceMultiplier()),Float.floatToIntBits(b.getHdrLuminanceMultiplier()));
    }
}
