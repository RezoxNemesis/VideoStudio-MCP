package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.TypefaceSpan;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.transformer.Composition;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual Media3 text Canvas raster/settings and CPU placement, not GL/codec or speech alignment. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioTitleMotionTest {
    private static final Size OUTPUT=new Size(320,180);

    @Test public void omittedMotionRetainsLegacyTitleAnchorsSpansAndHalfOpenClock()throws Exception {
        for(int position=0;position<3;position++){
            StudioLayer layer=StudioLayer.fromJson(title().put("position",position));
            assertFalse(layer.toJson().has("motionKeyframes"));
            StudioTimedTitleOverlay overlay=new StudioTimedTitleOverlay(layer);
            try{
                OverlaySettings visible=overlay.getOverlaySettings(1_250_000);
                assertEquals("androidx.media3.effect.StaticOverlaySettings",visible.getClass().getName());
                assertEquals(0,visible.getBackgroundFrameAnchor().first,0);
                assertEquals(position==0?.72f:position==2?-.71f:0f,visible.getBackgroundFrameAnchor().second,0);
                assertEquals(1,visible.getScale().first,0);assertEquals(1,visible.getScale().second,0);
                assertEquals(0,visible.getRotationDegrees(),0);assertEquals(1,visible.getAlphaScale(),0);
                assertEquals(0,overlay.getOverlaySettings(1_249_999).getAlphaScale(),0);
                assertEquals(1,overlay.getOverlaySettings(2_249_999).getAlphaScale(),0);
                assertEquals(0,overlay.getOverlaySettings(2_250_000).getAlphaScale(),0);
                assertEquals("Why not you?",overlay.getText(1_500_000).toString());
                assertEquals(42,overlay.getText(0).getSpans(0,12,AbsoluteSizeSpan.class)[0].getSize());
                assertEquals("monospace",overlay.getText(0).getSpans(0,12,TypefaceSpan.class)[0].getFamily());
            }finally{overlay.release();}
        }
    }

    @Test public void emptyClearAndIdentityKeysKeepExactStaticSettingsAndNativeGlyphRgba()throws Exception {
        StudioLayer plain=StudioLayer.fromJson(title()),clear=StudioLayer.fromJson(title().put("motionKeyframes",new JSONArray()));
        StudioLayer identity=StudioLayer.fromJson(title().put("motionKeyframes",new JSONArray().put(key(0)).put(key(1000))));
        assertEquals(0,clear.copy().toJson().getJSONArray("motionKeyframes").length());
        StudioTimedTitleOverlay a=new StudioTimedTitleOverlay(plain),b=new StudioTimedTitleOverlay(clear),c=new StudioTimedTitleOverlay(identity);
        Bitmap aa=null,bb=null,cc=null;
        try{
            for(long time:new long[]{1_249_999,1_250_000,1_250_500,1_750_000,2_249_999,2_250_000}){
                exact(a.getOverlaySettings(time),b.getOverlaySettings(time));exact(a.getOverlaySettings(time),c.getOverlaySettings(time));
            }
            assertEquals(a.getOverlaySettings(1_750_000).getClass(),b.getOverlaySettings(1_750_000).getClass());
            aa=a.getBitmap(1_750_000);bb=b.getBitmap(1_750_000);cc=c.getBitmap(1_750_000);
            assertTrue("TextOverlay must really draw native glyph/background pixels",ink(aa)>20);
            pixels(aa,bb);pixels(aa,cc);
        }finally{a.release();b.release();c.release();recycle(aa);recycle(bb);recycle(cc);}
    }

    @Test public void titleChannelsUseLayerLocalSubmillisecondTimeAndPresetBaseAnchor()throws Exception {
        JSONArray keys=new JSONArray().put(key(0)).put(key(1000).put("x",.2).put("y",-.1)
                .put("scaleX",2).put("scaleY",.5).put("rotate",80).put("opacity",.4));
        StudioTimedTitleOverlay overlay=overlay(title().put("position",2).put("motionKeyframes",keys));
        try{
            OverlaySettings middle=overlay.getOverlaySettings(1_750_000);
            assertEquals(.2,middle.getBackgroundFrameAnchor().first,.000001);
            assertEquals(-.71f+.1f,middle.getBackgroundFrameAnchor().second,.000001);
            assertEquals(1.5,middle.getScale().first,0);assertEquals(.75,middle.getScale().second,0);
            assertEquals(40,middle.getRotationDegrees(),0);assertEquals(.7,middle.getAlphaScale(),.000001);
            OverlaySettings sub=overlay.getOverlaySettings(1_250_500);
            assertEquals(.0002,sub.getBackgroundFrameAnchor().first,.00000001);
            assertEquals(.04,sub.getRotationDegrees(),.0000001);
            assertEquals(0,middle.getOverlayFrameAnchor().first,0);assertEquals(0,middle.getOverlayFrameAnchor().second,0);
        }finally{overlay.release();}
    }

    @Test public void outgoingEasingsAndHoldBoundariesFollowTheExistingSharedConvention()throws Exception {
        String[] easings={"linear","ease_in","ease_out","ease_in_out","hold"};double[] progress={.25,.0625,.4375,.15625,0};
        for(int i=0;i<easings.length;i++){
            StudioTimedTitleOverlay overlay=overlay(title().put("motionKeyframes",new JSONArray()
                    .put(key(0).put("easing",easings[i])).put(key(1000).put("x",.4))));
            try{assertEquals(easings[i],.8*progress[i],overlay.getOverlaySettings(1_500_000).getBackgroundFrameAnchor().first,.000001);}
            finally{overlay.release();}
        }
        StudioTimedTitleOverlay hold=overlay(title().put("motionKeyframes",new JSONArray()
                .put(key(0).put("x",.2).put("easing","hold")).put(key(500).put("x",-.2)).put(key(1000))));
        try{assertEquals(.4,hold.getOverlaySettings(1_749_999).getBackgroundFrameAnchor().first,.000001);
            assertEquals(-.4,hold.getOverlaySettings(1_750_000).getBackgroundFrameAnchor().first,.000001);}
        finally{hold.release();}
    }

    @Test public void omittedChannelsResetToIdentityAndUniformScaleRetainsAxisOverride()throws Exception {
        StudioTimedTitleOverlay overlay=overlay(title().put("motionKeyframes",new JSONArray()
                .put(key(0).put("x",.2).put("scale",2).put("scaleY",3).put("opacity",.2))
                .put(key(500)).put(key(1000).put("rotate",90))));
        StudioTimedTitleOverlay plain=overlay(title());
        try{
            assertEquals(2,overlay.getOverlaySettings(1_250_000).getScale().first,0);
            assertEquals(3,overlay.getOverlaySettings(1_250_000).getScale().second,0);
            exact(plain.getOverlaySettings(1_750_000),overlay.getOverlaySettings(1_750_000));
        }finally{overlay.release();plain.release();}
    }

    @Test public void titleMotionRequiresStrictArraysNumbersEasingsAndOnlyDeclaredChannels()throws Exception {
        assertEquals(1,StudioLayer.fromJson(title().put("motionKeyframes",new JSONArray().put(key(0)))).motionKeyframes.length());
        for(Object value:new Object[]{JSONObject.NULL,"[]",new JSONObject(),1,true})
            assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("motionKeyframes",value)));
        for(String field:new String[]{"anchorX","anchorY","width","height","rotation","text","unknown"})
            assertThrows(field,IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("motionKeyframes",new JSONArray().put(key(0).put(field,.5)))));
        for(Object value:new Object[]{JSONObject.NULL,"1",true})
            assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("motionKeyframes",new JSONArray().put(key(0).put("x",value)))));
        for(Object value:new Object[]{JSONObject.NULL,"bezier",1})
            assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("motionKeyframes",new JSONArray().put(key(0).put("easing",value)))));
        assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("type","audio").put("assetId","audio").put("motionKeyframes",new JSONArray())));
    }

    @Test public void keysAreStrictlyOrderedInsideDurationAndBoundedTo64()throws Exception {
        JSONArray allowed=new JSONArray();for(int i=0;i<64;i++)allowed.put(key(i));
        assertEquals(64,StudioLayer.fromJson(title().put("motionKeyframes",allowed)).copy().motionKeyframes.length());
        allowed.put(key(64));assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("motionKeyframes",allowed)));
        for(JSONArray invalid:new JSONArray[]{new JSONArray().put(key(0)).put(key(0)),new JSONArray().put(key(2)).put(key(1)),
                new JSONArray().put(key(-1)),new JSONArray().put(key(1001)),new JSONArray().put(new JSONObject().put("timeMs",.5)),
                new JSONArray().put(new JSONObject().put("x",0)),new JSONArray().put("key"),
                new JSONArray().put(key(0).put("x",2.01)),new JSONArray().put(key(0).put("y",-2.01)),
                new JSONArray().put(key(0).put("scale",.09)),new JSONArray().put(key(0).put("scaleX",8.01)),
                new JSONArray().put(key(0).put("rotate",360.01)),new JSONArray().put(key(0).put("opacity",1.01))})
            assertThrows("Invalid title motion "+invalid,IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("motionKeyframes",invalid)));
    }

    @Test public void parsedCopiedSerializedAndAttachedTitleKeepDetachedMotionAndText()throws Exception {
        JSONObject end=key(1000).put("x",.2);JSONArray keys=new JSONArray().put(key(0)).put(end);
        StudioLayer layer=StudioLayer.fromJson(title().put("motionKeyframes",keys));StudioLayer copy=layer.copy();
        StudioTimedTitleOverlay overlay=new StudioTimedTitleOverlay(layer);
        end.put("x",1);layer.motionKeyframes.getJSONObject(1).put("x",-.8);layer.text="Changed later";layer.position=2;
        JSONObject wire=copy.toJson();wire.getJSONArray("motionKeyframes").getJSONObject(1).put("x",-.9);
        try{
            assertEquals(.2,copy.toJson().getJSONArray("motionKeyframes").getJSONObject(1).getDouble("x"),0);
            assertEquals(.2,overlay.getOverlaySettings(1_750_000).getBackgroundFrameAnchor().first,.000001);
            assertEquals(0,overlay.getOverlaySettings(1_750_000).getBackgroundFrameAnchor().second,0);
            assertEquals("Why not you?",overlay.getText(1_750_000).toString());
        }finally{overlay.release();}
    }

    @Test public void ordinarySetStudioLayersRemainsAtomicRevisionGuardedAndAddsNoAssetsOrTime()throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project project=project();store.save(project);String before=store.get(project.id).toJson().toString();
            JSONObject valid=title().put("motionKeyframes",new JSONArray().put(key(0)).put(key(1000).put("opacity",.5)));
            JSONObject bad=title().put("id","bad").put("motionKeyframes",new JSONArray().put(key(1001)));
            JSONObject request=new JSONObject().put("projectId",project.id).put("expectedUpdatedAt",project.updatedAt).put("studioLayers",new JSONArray().put(valid).put(bad));
            assertThrows(IllegalArgumentException.class,()->StudioLayerContract.apply(store,request));assertEquals(before,store.get(project.id).toJson().toString());
            request.put("studioLayers",new JSONArray().put(valid));StudioLayerContract.apply(store,request);
            ProjectStore.Project edited=store.get(project.id);assertEquals(1,edited.assets.size());assertEquals(2,edited.clips.size());assertEquals(4000,edited.outputDurationMs());
            assertEquals(1,edited.studioLayers.size());assertEquals("",edited.studioLayers.get(0).assetId);
            assertThrows(IllegalStateException.class,()->StudioLayerContract.apply(store,request));
        }
    }

    @Test public void saveReopenProjectRoundtripUndoAndRedoKeepTitleRecipeAndSettings()throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project original=project();store.save(original);StudioEditHistory history=new StudioEditHistory();history.checkpoint(original);
            JSONObject keyed=title().put("motionKeyframes",new JSONArray().put(key(0)).put(key(1000).put("rotate",80).put("opacity",.2)));
            StudioLayerContract.apply(store,new JSONObject().put("projectId",original.id).put("expectedUpdatedAt",original.updatedAt).put("studioLayers",new JSONArray().put(keyed)));
            ProjectStore.Project edited=store.get(original.id),restored=ProjectStore.Project.fromJson(new JSONObject(edited.toJson().toString()));
            assertEquals(2,restored.studioLayers.get(0).motionKeyframes.length());ProjectStore.Project undone=history.undo(edited);assertTrue(undone.studioLayers.isEmpty());store.save(undone);
            ProjectStore.Project redone=history.redo(undone);store.save(redone);ProjectStore.Project reopened=store.get(original.id);
            StudioTimedTitleOverlay a=new StudioTimedTitleOverlay(edited.studioLayers.get(0)),b=new StudioTimedTitleOverlay(reopened.studioLayers.get(0));
            try{for(long time:new long[]{1_250_000,1_250_500,1_500_000,1_750_000,2_249_999})exact(a.getOverlaySettings(time),b.getOverlaySettings(time));}
            finally{a.release();b.release();}
            assertEquals(4000,reopened.outputDurationMs());assertEquals(1,reopened.assets.size());
        }
    }

    @Test public void realCompositionAttachesIndependentGlobalTitlesAcrossAnUnchangedVideoCut()throws Exception {
        ProjectStore.Project project=project();JSONArray keys=new JSONArray().put(key(0)).put(key(1000).put("x",.4));
        project.studioLayers.add(StudioLayer.fromJson(title().put("startMs",1500).put("motionKeyframes",keys)));
        project.studioLayers.add(StudioLayer.fromJson(title().put("id","later").put("startMs",2000).put("motionKeyframes",keys)));
        Composition composition=new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(project,"9:16","720p");
        OverlayEffect effect=(OverlayEffect)composition.effects.videoEffects.stream().filter(e->e instanceof OverlayEffect).findFirst().get();
        Field field=OverlayEffect.class.getDeclaredField("overlays");field.setAccessible(true);List<?> overlays=(List<?>)field.get(effect);
        assertEquals(2,overlays.size());StudioTimedTitleOverlay early=(StudioTimedTitleOverlay)overlays.get(0),later=(StudioTimedTitleOverlay)overlays.get(1);
        try{
            assertEquals(.4,early.getOverlaySettings(2_000_000).getBackgroundFrameAnchor().first,.000001);
            assertEquals(0,later.getOverlaySettings(2_000_000).getBackgroundFrameAnchor().first,0);
            assertEquals(0,early.getOverlaySettings(2_500_000).getAlphaScale(),0);
            assertEquals(.4,later.getOverlaySettings(2_500_000).getBackgroundFrameAnchor().first,.000001);
            assertEquals(2,composition.sequences.get(0).editedMediaItems.size());assertEquals(4000,project.outputDurationMs());assertEquals(1,project.assets.size());
        }finally{early.release();later.release();}
    }

    @Test public void nativeCanvasTextPixelsUseActualMedia3RotatedCornersAndOffscreenPlacement()throws Exception {
        JSONArray keys=new JSONArray().put(key(0).put("x",-2).put("opacity",0))
                .put(key(500).put("x",.125).put("y",-.1).put("scaleX",2).put("scaleY",.5).put("rotate",90).put("opacity",.5))
                .put(key(999).put("x",2)).put(key(1000).put("x",2));
        StudioTimedTitleOverlay overlay=overlay(title().put("text","H").put("backgroundColor",0).put("motionKeyframes",keys));
        Bitmap bitmap=null;
        try{
            overlay.configure(OUTPUT);bitmap=overlay.getBitmap(1_750_000);assertTrue(ink(bitmap)>0);assertTrue(bitmap.getWidth()<160);
            Size source=new Size(bitmap.getWidth(),bitmap.getHeight());float[] matrix=matrix(source,overlay.getOverlaySettings(1_750_000));
            double halfRotatedWidth=bitmap.getHeight()*.25,halfRotatedHeight=bitmap.getWidth();
            point(matrix,0,0,200,72);point(matrix,-1,-1,200+halfRotatedWidth,72+halfRotatedHeight);
            point(matrix,1,1,200-halfRotatedWidth,72-halfRotatedHeight);
            assertEquals(.5,overlay.getOverlaySettings(1_750_000).getAlphaScale(),0);
            float[] left=matrix(source,overlay.getOverlaySettings(1_250_000)),right=matrix(source,overlay.getOverlaySettings(2_249_000));
            point(left,0,0,-480,90);point(right,0,0,800,90);
            assertTrue(pixel(left,1,1)[0]<0);assertTrue(pixel(right,-1,-1)[0]>OUTPUT.getWidth());
            assertSame("Motion reuses the actual styled text raster",bitmap,overlay.getBitmap(2_249_000));
            assertEquals(0,overlay.getOverlaySettings(2_250_000).getAlphaScale(),0);
        }finally{overlay.release();recycle(bitmap);}
    }

    private static JSONObject title()throws Exception {
        return new JSONObject().put("id","title_motion").put("type","title").put("text","Why not you?").put("startMs",1250).put("durationMs",1000).put("position",1)
                .put("fontSize",42).put("fontFamily","monospace").put("fontStyle","bold_italic").put("textAlign","center").put("color",0xffeeddcc).put("backgroundColor",0xff112233);
    }
    private static JSONObject key(long time)throws Exception{return new JSONObject().put("timeMs",time);}
    private static StudioTimedTitleOverlay overlay(JSONObject json){return new StudioTimedTitleOverlay(StudioLayer.fromJson(json));}
    private static ProjectStore.Project project(){
        ProjectStore.Project p=new ProjectStore.Project();p.id=UUID.randomUUID().toString();p.name="Native title motion fixture";
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="base";a.mime="video/mp4";a.uri="file:///owned/test-source.mp4";a.durationMs=10000;p.assets.add(a);
        for(int i=0;i<2;i++){ProjectStore.Clip c=new ProjectStore.Clip();c.id="clip"+i;c.assetId=a.id;c.inMs=i*2000;c.outMs=(i+1)*2000;p.clips.add(c);}return p;
    }
    private static void exact(OverlaySettings a,OverlaySettings b){
        assertEquals(a.getBackgroundFrameAnchor(),b.getBackgroundFrameAnchor());assertEquals(a.getOverlayFrameAnchor(),b.getOverlayFrameAnchor());
        assertEquals(a.getScale(),b.getScale());assertEquals(a.getRotationDegrees(),b.getRotationDegrees(),0);assertEquals(a.getAlphaScale(),b.getAlphaScale(),0);
    }
    private static int ink(Bitmap bitmap){int count=0;for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if((bitmap.getPixel(x,y)>>>24)>0)count++;return count;}
    private static void pixels(Bitmap a,Bitmap b){
        assertEquals(a.getWidth(),b.getWidth());assertEquals(a.getHeight(),b.getHeight());int width=a.getWidth();int[] left=new int[width],right=new int[width];
        for(int y=0;y<a.getHeight();y++){a.getPixels(left,0,width,0,y,width,1);b.getPixels(right,0,width,0,y,width,1);assertArrayEquals("Native text row"+y,left,right);}
    }
    private static void recycle(Bitmap b){if(b!=null&&!b.isRecycled())b.recycle();}
    private static float[] matrix(Size source,OverlaySettings settings)throws Exception {
        Class<?> type=Class.forName("androidx.media3.effect.OverlayMatrixProvider");Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object provider=constructor.newInstance();
        Method configure=type.getDeclaredMethod("configure",Size.class);configure.setAccessible(true);configure.invoke(provider,OUTPUT);
        Method transform=type.getDeclaredMethod("getTransformationMatrix",Size.class,OverlaySettings.class);transform.setAccessible(true);return ((float[])transform.invoke(provider,source,settings)).clone();
    }
    private static double[] pixel(float[] matrix,float x,float y){float[] p=new float[4];android.opengl.Matrix.multiplyMV(p,0,matrix,0,new float[]{x,y,0,1},0);return new double[]{(p[0]/p[3]+1)*OUTPUT.getWidth()/2.0,(1-p[1]/p[3])*OUTPUT.getHeight()/2.0};}
    private static void point(float[] matrix,float x,float y,double expectedX,double expectedY){double[] p=pixel(matrix,x,y);assertEquals(expectedX,p[0],.0002);assertEquals(expectedY,p[1],.0002);}
}
