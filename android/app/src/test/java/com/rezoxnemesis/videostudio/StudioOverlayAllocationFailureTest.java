package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
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
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
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

/** CPU-only allocation ownership; actual app decoding/settings/release, never successful GL or codec output. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows=StudioOverlayAllocationFailureTest.AllocationBoundary.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioOverlayAllocationFailureTest {
    private static final VideoFrameProcessingException FAILURE=new VideoFrameProcessingException("Exact unit allocation failure after native source decode");
    private static final VideoFrameProcessingException NO_DRAW=new VideoFrameProcessingException("Unit ownership token cannot draw GL frames");
    private static boolean failNext;
    private static List<TextureOverlay> failedOverlays;
    private static final List<Bitmap> failedSource=new ArrayList<>(),failedTransparent=new ArrayList<>();

    @Before public void resetBoundary(){failNext=false;failedOverlays=null;failedSource.clear();failedTransparent.clear();}

    @Implements(value=OverlayEffect.class,isInAndroidSdk=false)
    public static final class AllocationBoundary {
        @RealObject private OverlayEffect effect;
        @Implementation public BaseGlShaderProgram toGlShaderProgram(Context context,boolean hdr)throws VideoFrameProcessingException {
            final List<TextureOverlay> overlays;
            try{
                Field field=OverlayEffect.class.getDeclaredField("overlays");field.setAccessible(true);
                @SuppressWarnings("unchecked") List<TextureOverlay> actual=(List<TextureOverlay>)field.get(effect);
                overlays=new ArrayList<>(actual);
            }catch(ReflectiveOperationException error){throw new AssertionError("Pinned Media3 ownership boundary changed",error);}
            if(failNext){
                failNext=false;failedOverlays=overlays;
                for(TextureOverlay overlay:overlays){
                    assertTrue("This allocation case owns only actual timed IMAGE resources",overlay instanceof StudioTimedImageOverlay);
                    BitmapOverlay image=(BitmapOverlay)overlay;
                    failedTransparent.add(image.getBitmap(0));failedSource.add(image.getBitmap(1_000_000));
                }
                throw FAILURE;
            }
            return new Token(overlays);
        }
    }
    private static final class Token extends BaseGlShaderProgram {
        final List<TextureOverlay> overlays;
        boolean released;
        Token(List<TextureOverlay> overlays){super(false,1);this.overlays=overlays;}
        @Override public Size configure(int width,int height){Size size=new Size(width,height);for(TextureOverlay overlay:overlays)overlay.configure(size);return size;}
        @Override public void drawFrame(int id,long time)throws VideoFrameProcessingException {throw NO_DRAW;}
        @Override public void release()throws VideoFrameProcessingException {if(released)return;released=true;try{super.release();}finally{for(TextureOverlay overlay:overlays)overlay.release();}}
    }

    @Test public void allocationFailureKeepsPrimaryReleasesEveryNewImageAndPreservesPreviousGeneration()throws Exception {
        Fixture f=fixture();GlEffect effect=effect(f.project);Token previous=allocate(effect);
        Bitmap a=image(previous,0).getBitmap(1_000_000),b=image(previous,1).getBitmap(1_000_000);
        try{
            assertEquals(0xffff0000,a.getPixel(0,0));assertEquals(0xff0000ff,b.getPixel(0,0));
            failNext=true;
            try{effect.toGlShaderProgram(RuntimeEnvironment.getApplication(),false);fail("The exact injected allocation failure must propagate");}
            catch(VideoFrameProcessingException error){assertSame("The primary shader allocation exception must remain exact",FAILURE,error);}
            assertEquals(2,failedOverlays.size());assertEquals(2,failedSource.size());assertEquals(2,failedTransparent.size());
            for(int i=0;i<2;i++){
                assertTrue("Every failed generation source bitmap must be retired",failedSource.get(i).isRecycled());
                assertTrue("Every failed generation transparent bitmap must be retired",failedTransparent.get(i).isRecycled());
                for(long time:new long[]{0,1_000_000}){
                    try{((BitmapOverlay)failedOverlays.get(i)).getBitmap(time);fail("Failed-generation overlay must remain terminal after cleanup");}
                    catch(VideoFrameProcessingException expected){assertTrue(expected.getMessage().contains("released"));}
                }
            }
            assertFalse("Failure of a new generation must not retire the previous source",a.isRecycled());
            assertFalse("All previous-generation images retain their own resources",b.isRecycled());
            assertEquals(0xffff0000,image(previous,0).getBitmap(2_000_000).getPixel(0,0));
            assertEquals(0xff0000ff,image(previous,1).getBitmap(2_000_000).getPixel(0,0));
            assertArrayEquals(f.aBytes,Files.readAllBytes(f.a.toPath()));assertArrayEquals(f.bBytes,Files.readAllBytes(f.b.toPath()));
        }finally{previous.release();if(failedOverlays!=null)for(TextureOverlay overlay:failedOverlays)overlay.release();}
    }

    @Test public void callerAssetLayerAndKeyMutationsCannotAlterPreparedCompositionRecipe()throws Exception {
        Fixture f=fixture();String before=SketchDocument.canonical(f.project.toJson());GlEffect effect=effect(f.project);
        StudioLayer caller=f.project.studioLayers.get(0);caller.x=.9f;caller.opacity=.1f;
        caller.motionKeyframes.getJSONObject(1).put("x",-1);
        ProjectStore.Asset asset=f.project.assets.get(1);asset.uri=Uri.fromFile(f.b).toString();asset.id="mutated-owner";asset.mime="video/mp4";asset.generationMetadata.put("mutated",true);
        assertNotEquals(before,SketchDocument.canonical(f.project.toJson()));
        Token first=allocate(effect),second=allocate(effect);
        try{
            for(Token generation:new Token[]{first,second}){
                BitmapOverlay original=image(generation,0);Bitmap bitmap=original.getBitmap(2_000_000);
                assertEquals("The frozen original source must not switch to the caller's blue URI",0xffff0000,bitmap.getPixel(0,0));
                assertEquals(0,bitmap.getPixel(1,1));
                assertEquals("Frozen static center and original local key must remain",-.2,original.getOverlaySettings(2_000_000).getBackgroundFrameAnchor().first,.000001);
                assertEquals(.75,original.getOverlaySettings(2_000_000).getAlphaScale(),0);
                assertEquals(0xff0000ff,image(generation,1).getBitmap(2_000_000).getPixel(0,0));
            }
            assertArrayEquals(f.aBytes,Files.readAllBytes(f.a.toPath()));assertArrayEquals(f.bBytes,Files.readAllBytes(f.b.toPath()));
            assertEquals(4000,f.project.outputDurationMs());
        }finally{first.release();second.release();}
    }

    private static Token allocate(GlEffect effect)throws Exception {
        GlShaderProgram program=effect.toGlShaderProgram(RuntimeEnvironment.getApplication(),false);
        assertTrue("The actual composition factory must reach the unit ownership boundary",program instanceof Token);
        Token token=(Token)program;token.configure(320,180);
        try{token.drawFrame(0,0);fail("Unit token must never pretend to draw a frame");}catch(VideoFrameProcessingException error){assertSame(NO_DRAW,error);}
        return token;
    }
    private static BitmapOverlay image(Token token,int index){return (BitmapOverlay)token.overlays.get(index);}
    private static GlEffect effect(ProjectStore.Project project)throws Exception {
        Composition composition=new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(project,"9:16","720p");
        assertEquals(1,composition.effects.videoEffects.size());return (GlEffect)composition.effects.videoEffects.get(0);
    }
    private static Fixture fixture()throws Exception {
        Fixture f=new Fixture();f.a=source(0xffff0000);f.b=source(0xff0000ff);f.aBytes=Files.readAllBytes(f.a.toPath());f.bBytes=Files.readAllBytes(f.b.toPath());
        f.project=new ProjectStore.Project();f.project.id=UUID.randomUUID().toString();f.project.name="Unit overlay allocation failure and snapshots";
        ProjectStore.Asset base=new ProjectStore.Asset();base.id="base";base.mime="video/mp4";base.uri="file:///owned/native-fixture.mp4";base.durationMs=4000;f.project.assets.add(base);
        for(int i=0;i<2;i++){
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="image"+i;asset.mime="image/png";asset.uri=Uri.fromFile(i==0?f.a:f.b).toString();f.project.assets.add(asset);
            JSONArray keys=new JSONArray().put(new JSONObject().put("timeMs",0)).put(new JSONObject().put("timeMs",3000).put("x",.3));
            f.project.studioLayers.add(StudioLayer.fromJson(new JSONObject().put("id","panel"+i).put("type","image").put("assetId",asset.id).put("startMs",500).put("durationMs",3000)
                    .put("x",i==0?.25:.75).put("y",.5).put("width",.5).put("height",.25).put("opacity",.75).put("motionKeyframes",keys)));
            ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip"+i;clip.assetId=base.id;clip.inMs=i*2000;clip.outMs=(i+1)*2000;f.project.clips.add(clip);
        }
        return f;
    }
    private static File source(int color)throws Exception {
        File file=new File(RuntimeEnvironment.getApplication().getFilesDir(),"unit-overlay-"+UUID.randomUUID()+".png");Bitmap bitmap=Bitmap.createBitmap(4,2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);bitmap.setPixel(1,1,0);
        try(FileOutputStream output=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}return file;
    }
    private static final class Fixture {File a,b;byte[] aBytes,bBytes;ProjectStore.Project project;}
}
