package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.style.AbsoluteSizeSpan;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import static org.junit.Assert.*;

/**
 * CPU resource-ownership regression at the real Media3 GlEffect allocation boundary.
 * The sole shadow returns an explicit ownership token at the real OverlayEffect
 * allocation boundary. Its drawFrame ALWAYS throws before GL and cannot render a
 * frame. Configure/release exercise the real overlays; native bitmap pixels are real.
 * This tests resource ownership only, never GL/codec/export success.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows=StudioOverlayProgramGenerationTest.AllocationBoundary.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioOverlayProgramGenerationTest {
    private static final Size OUTPUT=new Size(320,180);
    private static final VideoFrameProcessingException BOUNDARY=
            new VideoFrameProcessingException("QA ownership token cannot draw: no GL/codec execution");
    private static final Map<List<TextureOverlay>,AllocationToken> PROGRAMS=new IdentityHashMap<>();

    @Implements(value=OverlayEffect.class,isInAndroidSdk=false)
    public static final class AllocationBoundary {
        @RealObject private OverlayEffect effect;
        @Implementation public BaseGlShaderProgram toGlShaderProgram(Context context,boolean useHdr)
                throws VideoFrameProcessingException {
            try{
                Field field=OverlayEffect.class.getDeclaredField("overlays");field.setAccessible(true);
                @SuppressWarnings("unchecked") List<TextureOverlay> real=(List<TextureOverlay>)field.get(effect);
                return new AllocationToken(real);
            }catch(ReflectiveOperationException error){throw new AssertionError("Pinned Media3 overlay boundary changed",error);}
        }
    }

    /** UNIT ONLY: no GL allocation/texture upload and every draw attempt fails. */
    private static final class AllocationToken extends BaseGlShaderProgram {
        final List<TextureOverlay> overlays;
        private boolean retired;
        AllocationToken(List<TextureOverlay> real){super(false,1);overlays=new ArrayList<>(real);PROGRAMS.put(overlays,this);}
        @Override public Size configure(int width,int height){
            if(retired)throw new IllegalStateException("Unit ownership token retired");
            Size size=new Size(width,height);for(TextureOverlay overlay:overlays)overlay.configure(size);return size;
        }
        @Override public void drawFrame(int textureId,long timeUs)throws VideoFrameProcessingException {throw BOUNDARY;}
        @Override public void release()throws VideoFrameProcessingException {
            if(retired)return;retired=true;
            // The real base texture pool is empty: no input was ever queued or uploaded.
            try{super.release();}finally{for(TextureOverlay overlay:overlays)overlay.release();}
        }
    }

    @Test public void activeImageAndTitleSurviveRetiredFirstProgramAcrossRealNativeVideoCut()throws Exception {
        Fixture f=fixture(0,3000,true);String document=SketchDocument.canonical(f.project.toJson());
        Composition composition=composition(f);GlEffect effect=compositionEffect(composition);
        assertNotEquals("Different native per-clip effects require Media3 intermediate-program reconfiguration",
                composition.sequences.get(0).editedMediaItems.get(0).effects.videoEffects,
                composition.sequences.get(0).editedMediaItems.get(1).effects.videoEffects);
        List<TextureOverlay> first=capture(effect),second=null;Bitmap oldPixels=null;
        try{
            configure(first);BitmapOverlay image=image(first);oldPixels=image.getBitmap(1_500_000);
            pixels(oldPixels);assertEquals(.3,image.getOverlaySettings(1_500_000).getBackgroundFrameAnchor().first,.000001);
            title(first,1_500_000);
            retire(first);assertTrue("Retired generation must release its owned decoded image",oldPixels.isRecycled());
            second=capture(effect);configure(second);
            BitmapOverlay replacement=image(second);Bitmap later=replacement.getBitmap(2_500_000);
            assertFalse(later.isRecycled());pixels(later);
            assertNotSame("New program resources must not reuse the retired decoded bitmap",oldPixels,later);
            assertEquals(.5,replacement.getOverlaySettings(2_500_000).getBackgroundFrameAnchor().first,.000001);
            assertEquals(.75,replacement.getOverlaySettings(2_500_000).getAlphaScale(),0);
            title(second,2_500_000);
            assertArrayEquals(f.original,Files.readAllBytes(f.file.toPath()));
            assertEquals(document,SketchDocument.canonical(f.project.toJson()));assertEquals(4000,f.project.outputDurationMs());
        }finally{retire(second);retire(first);}
    }

    @Test public void inactiveThenLaterActiveImageUsesLiveTransparentAndSourcePixelsAfterProgramRecreation()throws Exception {
        Fixture f=fixture(2500,1000,false);GlEffect effect=compositionEffect(composition(f));
        List<TextureOverlay> first=capture(effect),second=null;Bitmap retiredTransparent=null;
        try{
            configure(first);retiredTransparent=image(first).getBitmap(1_000_000);
            assertEquals(0,retiredTransparent.getPixel(0,0));assertEquals(1,retiredTransparent.getWidth());
            retire(first);assertTrue(retiredTransparent.isRecycled());
            second=capture(effect);configure(second);BitmapOverlay replacement=image(second);
            Bitmap hidden=replacement.getBitmap(2_000_000);assertFalse(hidden.isRecycled());assertEquals(0,hidden.getPixel(0,0));
            assertEquals(0,replacement.getOverlaySettings(2_499_999).getAlphaScale(),0);
            pixels(replacement.getBitmap(2_500_000));assertEquals(.75,replacement.getOverlaySettings(2_500_000).getAlphaScale(),0);
            assertEquals(0,replacement.getOverlaySettings(3_500_000).getAlphaScale(),0);
            assertArrayEquals(f.original,Files.readAllBytes(f.file.toPath()));
        }finally{retire(second);retire(first);}
    }

    @Test public void retiringOneProgramCannotRecycleAnIndependentlyAllocatedProgramImage()throws Exception {
        Fixture f=fixture(0,3000,false);GlEffect effect=compositionEffect(composition(f));
        List<TextureOverlay> first=capture(effect),second=capture(effect);
        try{
            configure(first);configure(second);
            Bitmap a=image(first).getBitmap(1_000_000),b=image(second).getBitmap(1_000_000);pixels(a);pixels(b);
            retire(first);assertTrue("First generation must really release its pixels",a.isRecycled());
            assertFalse("A different shader-program generation must retain its own live source pixels",b.isRecycled());
            pixels(image(second).getBitmap(1_500_000));assertEquals(.3,image(second).getOverlaySettings(1_500_000).getBackgroundFrameAnchor().first,.000001);
            assertArrayEquals(f.original,Files.readAllBytes(f.file.toPath()));
        }finally{retire(second);retire(first);}
    }

    @Test public void standaloneImageReleaseRemainsTerminalAndRecyclesBothOwnedBitmaps()throws Exception {
        Fixture f=fixture(1000,1000,false);StudioTimedImageOverlay overlay=new StudioTimedImageOverlay(
                RuntimeEnvironment.getApplication(),f.asset,f.project.studioLayers.get(0));
        Bitmap transparent=overlay.getBitmap(0),source=overlay.getBitmap(1_500_000);
        pixels(source);overlay.release();assertTrue(source.isRecycled());assertTrue(transparent.isRecycled());
        for(long time:new long[]{0,1_500_000}){
            try{overlay.getBitmap(time);fail("Released standalone overlays must reject both active and inactive reads");}
            catch(VideoFrameProcessingException expected){assertTrue(expected.getMessage().contains("released"));}
        }
        assertArrayEquals(f.original,Files.readAllBytes(f.file.toPath()));
    }

    private static List<TextureOverlay> capture(GlEffect effect)throws Exception {
        GlShaderProgram program=effect.toGlShaderProgram(RuntimeEnvironment.getApplication(),false);
        assertTrue("Actual NRE GlEffect must reach the pinned Media3 unit ownership boundary",program instanceof AllocationToken);
        AllocationToken token=(AllocationToken)program;
        // The token must never masquerade as successful GL rendering, even accidentally.
        try{token.drawFrame(0,0);fail("Unit ownership token cannot render any frame");}
        catch(VideoFrameProcessingException expected){assertSame(BOUNDARY,expected);}
        return token.overlays;
    }
    private static GlEffect compositionEffect(Composition composition){
        assertEquals("Timed image/title composition has one overlay effect",1,composition.effects.videoEffects.size());
        assertTrue(composition.effects.videoEffects.get(0) instanceof GlEffect);
        assertEquals(2,composition.sequences.get(0).editedMediaItems.size());
        return (GlEffect)composition.effects.videoEffects.get(0);
    }
    private static Composition composition(Fixture f)throws Exception {
        return new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(f.project,"9:16","720p");
    }
    private static BitmapOverlay image(List<TextureOverlay> overlays){
        for(TextureOverlay overlay:overlays)if(overlay instanceof StudioTimedImageOverlay)return (BitmapOverlay)overlay;
        throw new AssertionError("Real native timed image overlay missing");
    }
    private static void title(List<TextureOverlay> overlays,long time)throws Exception {
        StudioTimedTitleOverlay title=null;for(TextureOverlay overlay:overlays)if(overlay instanceof StudioTimedTitleOverlay)title=(StudioTimedTitleOverlay)overlay;
        assertNotNull("Real native timed title remains attached",title);assertEquals("Cut-safe title",title.getText(time).toString());
        assertEquals(42,title.getText(time).getSpans(0,14,AbsoluteSizeSpan.class)[0].getSize());
        Bitmap glyphs=title.getBitmap(time);int count=0;for(int y=0;y<glyphs.getHeight();y++)for(int x=0;x<glyphs.getWidth();x++)if((glyphs.getPixel(x,y)>>>24)>0)count++;
        assertTrue("Native TextOverlay Canvas must produce actual visible glyph pixels",count>20);
        OverlaySettings settings=title.getOverlaySettings(time);assertEquals(1,settings.getAlphaScale(),0);
    }
    private static void configure(List<TextureOverlay> overlays){PROGRAMS.get(overlays).configure(OUTPUT.getWidth(),OUTPUT.getHeight());}
    private static void retire(List<TextureOverlay> overlays)throws Exception {if(overlays!=null)PROGRAMS.get(overlays).release();}
    private static void pixels(Bitmap source){
        assertEquals(4,source.getWidth());assertEquals(2,source.getHeight());
        assertEquals(0xffff0000,source.getPixel(0,0));assertEquals(0xff0000ff,source.getPixel(3,0));
        assertEquals(0,source.getPixel(1,1));assertEquals(0xff00ff00,source.getPixel(3,1));
    }
    private static Fixture fixture(long startMs,long durationMs,boolean caption)throws Exception {
        Fixture f=new Fixture();f.file=new File(RuntimeEnvironment.getApplication().getFilesDir(),"program-generation-"+UUID.randomUUID()+".png");
        Bitmap pixels=Bitmap.createBitmap(4,2,Bitmap.Config.ARGB_8888);pixels.eraseColor(0xffffff00);
        pixels.setPixel(0,0,0xffff0000);pixels.setPixel(3,0,0xff0000ff);pixels.setPixel(1,1,0);pixels.setPixel(3,1,0xff00ff00);
        try(FileOutputStream stream=new FileOutputStream(f.file)){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{pixels.recycle();}
        f.original=Files.readAllBytes(f.file.toPath());f.asset=new ProjectStore.Asset();f.asset.id="owned-image";f.asset.mime="image/png";f.asset.uri=Uri.fromFile(f.file).toString();
        f.project=new ProjectStore.Project();f.project.id=UUID.randomUUID().toString();f.project.name="CPU program-generation ownership";f.project.assets.add(f.asset);
        ProjectStore.Asset base=new ProjectStore.Asset();base.id="base";base.mime="video/mp4";base.uri="file:///owned/native-fixture-video.mp4";base.durationMs=4000;f.project.assets.add(base);
        for(int i=0;i<2;i++){ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip-"+i;clip.assetId=base.id;clip.inMs=i*2000;clip.outMs=(i+1)*2000;clip.effects.put("brightness",i==0?0:.12);f.project.clips.add(clip);}
        JSONArray keys=new JSONArray().put(new JSONObject().put("timeMs",0)).put(new JSONObject().put("timeMs",durationMs).put("x",.3));
        f.project.studioLayers.add(StudioLayer.fromJson(new JSONObject().put("id","image").put("type","image").put("assetId",f.asset.id)
                .put("startMs",startMs).put("durationMs",durationMs).put("x",.5).put("y",.5).put("width",.5).put("height",.25).put("opacity",.75).put("motionKeyframes",keys)));
        if(caption)f.project.studioLayers.add(StudioLayer.fromJson(new JSONObject().put("id","title").put("type","title").put("text","Cut-safe title").put("startMs",1000).put("durationMs",2000).put("fontSize",42).put("fontFamily","monospace").put("position",1)));
        return f;
    }
    private static final class Fixture {File file;byte[] original;ProjectStore.Asset asset;ProjectStore.Project project;}
}
