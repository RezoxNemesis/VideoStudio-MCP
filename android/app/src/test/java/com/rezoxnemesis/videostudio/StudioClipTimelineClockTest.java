package com.rezoxnemesis.videostudio;

import androidx.media3.common.Effect;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.effect.GlMatrixTransformation;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Field;
import java.util.List;
import static org.junit.Assert.*;

/** Renderer callbacks use Media3 composition PTS; saved keys remain clip-local. No codec claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioClipTimelineClockTest {
    @Test public void secondVideoStartsItsOwnZoomAtTheCutWithoutStudioTracks()throws Exception{
        ProjectStore.Project p=project();video(p,"first",2000,6000,1);video(p,"second",82000,86000,1);
        zoom(p.clips.get(0),4000,"ease_in_out");zoom(p.clips.get(1),4000,"ease_in_out");
        Composition c=build(p);List<EditedMediaItem> items=c.sequences.get(0).editedMediaItems;
        assertZoom(items.get(0),0,1);assertZoom(items.get(0),2_000_000,1.02);
        assertZoom(items.get(1),4_000_000,1);assertZoom(items.get(1),6_000_000,1.02);
        assertZoom(items.get(1),7_999_999,1.04);
    }

    @Test public void studioTrackCompositionUsesTheSameSecondItemLocalClock()throws Exception{
        ProjectStore.Project p=project();video(p,"first",0,4000,1);video(p,"second",3000,7000,1);
        zoom(p.clips.get(1),4000,"ease_in_out");caption(p,750,1000);
        Composition c=build(p);EditedMediaItem second=c.sequences.get(0).editedMediaItems.get(1);
        assertZoom(second,4_000_000,1);assertZoom(second,6_000_000,1.02);assertZoom(second,7_999_999,1.04);
    }

    @Test public void thirdItemSubtractsUnequalPrecedingSpeedAdjustedDurations()throws Exception{
        ProjectStore.Project p=project();video(p,"fast",100,3100,1.5f);video(p,"slow",500,3500,.5f);
        video(p,"third",7000,8000,1);zoom(p.clips.get(2),1000,"linear");
        EditedMediaItem third=build(p).sequences.get(0).editedMediaItems.get(2);
        // 3000/1.5 + 3000/.5 = 8000ms of rendered predecessors, not6000ms source.
        assertZoom(third,8_000_000,1);assertZoom(third,8_500_000,1.02);assertZoom(third,8_999_999,1.04);
    }

    @Test public void nonzeroSourceTrimIsNotSubtractedAgainFromTheRendererClock()throws Exception{
        ProjectStore.Project p=project();video(p,"prefix",5000,6500,1);video(p,"trimmed",82000,83000,1);
        zoom(p.clips.get(1),1000,"linear");EditedMediaItem second=build(p).sequences.get(0).editedMediaItems.get(1);
        assertZoom(second,1_500_000,1);assertZoom(second,2_000_000,1.02);assertZoom(second,2_499_999,1.04);
    }

    @Test public void splitRetainedAuthoredOffsetIsAppliedAfterRemovingTheItemStart()throws Exception{
        ProjectStore.Project p=project();video(p,"left",0,500,1);video(p,"right",500,1000,1);
        JSONArray original=new JSONArray().put(new JSONObject().put("timeMs",0).put("scale",1))
                .put(new JSONObject().put("timeMs",1000).put("scale",2));
        for(int i=0;i<2;i++)p.clips.get(i).effects.put("keyframes",new JSONArray(original.toString()))
                .put("studioKeyframeDurationMs",1000).put("studioKeyframeOffsetMs",i*500);
        String before=p.toJson().toString();Composition c=build(p);
        assertZoom(c.sequences.get(0).editedMediaItems.get(0),250_000,1.25);
        EditedMediaItem right=c.sequences.get(0).editedMediaItems.get(1);
        assertZoom(right,500_000,1.5);assertZoom(right,750_000,1.75);assertZoom(right,999_999,1.999999);
        assertEquals(before,p.toJson().toString());
    }

    @Test public void secondItemOpacityUsesItsLocalAgeWhileFramePtsStayGlobal()throws Exception{
        ProjectStore.Project p=project();video(p,"prefix",0,2000,1);video(p,"fading",5000,6000,1);
        p.clips.get(1).effects.put("keyframes",new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("opacity",.2))
                .put(new JSONObject().put("timeMs",1000).put("opacity",.8)));
        StudioPixelEffect pixels=pixels(build(p).sequences.get(0).editedMediaItems.get(1));
        assertEquals(.2,pixels.opacityAtUs(2_000_000),.000001);
        assertEquals(.5,pixels.opacityAtUs(2_500_000),.000001);
        assertEquals(.8,pixels.opacityAtUs(2_999_999),.000001);
    }

    @Test public void laterPresetMotionMatchesTheFirstItemAtCorrespondingLocalAges()throws Exception{
        ProjectStore.Project p=project();video(p,"first",0,1000,1);video(p,"second",10000,11000,1);
        for(ProjectStore.Clip clip:p.clips)clip.effects.put("motionPreset","push_in");
        List<EditedMediaItem> items=build(p).sequences.get(0).editedMediaItems;
        for(long local:new long[]{0,1,250_000,500_000,999_999})
            assertArrayEquals("Same preset, same age at"+local,matrix(items.get(0),local),matrix(items.get(1),1_000_000+local),.000001f);
    }

    @Test public void layeredForegroundAndBackgroundRespectTheirActual700msItemMinimum()throws Exception{
        ProjectStore.Project p=project();image(p,"first",100);image(p,"second",100);
        for(ProjectStore.Clip clip:p.clips)layered(clip,false);
        Composition c=build(p);assertEquals(2,c.sequences.size());
        for(int sequence=0;sequence<2;sequence++){
            List<EditedMediaItem> items=c.sequences.get(sequence).editedMediaItems;
            for(long local:new long[]{0,175_000,350_000,699_999})
                assertArrayEquals("Layer"+sequence+" age"+local,matrix(items.get(0),local),matrix(items.get(1),700_000+local),.000001f);
        }
    }

    @Test public void allFourArticulatedSequencesRestartTheirOwnMotionAtTheNextItem()throws Exception{
        ProjectStore.Project p=project();image(p,"first",1000);image(p,"second",1000);
        for(ProjectStore.Clip clip:p.clips)layered(clip,true);
        Composition c=build(p);assertEquals(4,c.sequences.size());
        for(int sequence=0;sequence<4;sequence++){
            List<EditedMediaItem> items=c.sequences.get(sequence).editedMediaItems;
            for(long local:new long[]{0,250_000,500_000,999_999})
                assertArrayEquals("Articulated sequence"+sequence+" age"+local,matrix(items.get(0),local),matrix(items.get(1),1_000_000+local),.000001f);
        }
    }

    @Test public void compositionTimedCaptionStillUsesGlobalTimeAcrossTheCut()throws Exception{
        ProjectStore.Project p=project();video(p,"first",0,1000,1);video(p,"second",0,1000,1);
        zoom(p.clips.get(1),1000,"linear");caption(p,750,1000);
        Composition c=build(p);StudioCompositionOverlayEffect overlay=(StudioCompositionOverlayEffect)c.effects.videoEffects.stream().filter(e->e instanceof StudioCompositionOverlayEffect).findFirst().get();
        List<?> overlays=overlay.createOverlays();assertEquals(1,overlays.size());TextureOverlay title=(TextureOverlay)overlays.get(0);
        assertEquals(0,title.getOverlaySettings(749_999).getAlphaScale(),0);
        assertEquals(1,title.getOverlaySettings(750_000).getAlphaScale(),0);
        assertEquals(1,title.getOverlaySettings(1_000_000).getAlphaScale(),0);
        assertEquals(1,title.getOverlaySettings(1_749_999).getAlphaScale(),0);
        assertEquals(0,title.getOverlaySettings(1_750_000).getAlphaScale(),0);
        assertZoom(c.sequences.get(0).editedMediaItems.get(1),1_000_000,1);
    }

    @Test public void ordinaryImageMinimumIsIncludedBeforeTheNextVideoClockStarts()throws Exception{
        ProjectStore.Project p=project();image(p,"short-photo",100);video(p,"next",0,1000,1);
        zoom(p.clips.get(1),1000,"linear");Composition c=build(p);
        assertEquals(250_000,c.sequences.get(0).editedMediaItems.get(0).durationUs);
        EditedMediaItem next=c.sequences.get(0).editedMediaItems.get(1);
        assertZoom(next,250_000,1);assertZoom(next,750_000,1.02);assertZoom(next,1_249_999,1.04);
    }

    @Test public void rationalGeneratedFramePrefixRetainsExactMicrosecondsAndSingleClipLegacy()throws Exception{
        ProjectStore.Project single=project();video(single,"only",2000,6000,1);zoom(single.clips.get(0),4000,"ease_in_out");
        String unchanged=single.toJson().toString();EditedMediaItem only=build(single).sequences.get(0).editedMediaItems.get(0);
        assertZoom(only,0,1);assertZoom(only,2_000_000,1.02);assertEquals(unchanged,single.toJson().toString());
        ProjectStore.Project p=project();image(p,"authored-frame",NativeFrameClock.frameDurationMs(0,30));
        ProjectStore.Asset generated=p.assets.get(0);generated.generated=true;generated.role="generated_sketch_frame";
        generated.generationMetadata=new JSONObject().put("frameClock","rational-authored-v1").put("frameIndex",0).put("fps",30);
        p.clips.get(0).effects.put("poseInbetween",true).put("poseFps",30).put("frameClock","rational-authored-v1").put("frameIndex",0);
        video(p,"after-frame",0,1000,1);zoom(p.clips.get(1),1000,"linear");Composition c=build(p);
        assertEquals(33_333,c.sequences.get(0).editedMediaItems.get(0).durationUs);
        EditedMediaItem after=c.sequences.get(0).editedMediaItems.get(1);
        assertZoom(after,33_333,1);assertZoom(after,533_333,1.02);assertZoom(after,1_033_332,1.04);
    }

    private static ProjectStore.Project project(){ProjectStore.Project p=new ProjectStore.Project();p.id="clip-clock-fixture";p.name="Native clip-local clock regression";return p;}
    private static void video(ProjectStore.Project p,String id,long in,long out,float speed){
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=id+"-asset";asset.mime="video/mp4";asset.uri="file:///owned/"+id+".mp4";asset.durationMs=120_000;p.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id=id;clip.assetId=asset.id;clip.inMs=in;clip.outMs=out;clip.speed=speed;p.clips.add(clip);
    }
    private static void image(ProjectStore.Project p,String id,long duration){
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=id+"-asset";asset.mime="image/png";asset.uri="file:///owned/"+id+".png";p.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id=id;clip.assetId=asset.id;clip.outMs=duration;p.clips.add(clip);
    }
    private static void zoom(ProjectStore.Clip clip,long duration,String easing)throws Exception{
        clip.effects.put("keyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("scale",1).put("easing",easing))
                .put(new JSONObject().put("timeMs",duration).put("scale",1.04)));
    }
    private static void caption(ProjectStore.Project p,long start,long duration){StudioLayer title=new StudioLayer();title.id="global-title";title.type=StudioLayer.TITLE;title.text="Global composition caption";title.startMs=start;title.durationMs=duration;p.studioLayers.add(title);}
    private static void layered(ProjectStore.Clip clip,boolean articulated)throws Exception{
        clip.effects.put("animatedScene",true).put("foregroundUri","file:///owned/foreground.png").put("backgroundUri","file:///owned/background.png")
                .put("animationSpec",new JSONObject().put("cameraPreset","push_in").put("organicMotion",0)
                        .put("keyframes",new JSONArray().put(new JSONObject().put("t",0).put("scale",1)).put(new JSONObject().put("t",1).put("scale",1.1))));
        if(articulated)clip.effects.put("headUri","file:///owned/head.png").put("torsoUri","file:///owned/torso.png").put("lowerUri","file:///owned/lower.png");
    }
    private static Composition build(ProjectStore.Project p)throws Exception{return new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(p,"9:16","720p");}
    private static StudioPixelEffect pixels(EditedMediaItem item){return (StudioPixelEffect)item.effects.videoEffects.stream().filter(e->e instanceof StudioPixelEffect).findFirst().get();}
    /** Include every attached matrix transform, regardless of its future adapter class. */
    private static float[] matrix(EditedMediaItem item,long globalUs){
        float[] accumulated=new float[16];android.opengl.Matrix.setIdentityM(accumulated,0);int width=720,height=1280;
        for(Effect effect:item.effects.videoEffects)if(effect instanceof GlMatrixTransformation){
            GlMatrixTransformation transform=(GlMatrixTransformation)effect;Size size=transform.configure(width,height);width=size.getWidth();height=size.getHeight();
            float[] next=new float[16];android.opengl.Matrix.multiplyMM(next,0,transform.getGlMatrixArray(globalUs),0,accumulated,0);accumulated=next;
        }
        return accumulated;
    }
    private static void assertZoom(EditedMediaItem item,long globalUs,double scale){
        float[] actual=matrix(item,globalUs);assertEquals("X scale at global"+globalUs,scale,actual[0],.000002);
        assertEquals("Y scale at global"+globalUs,scale,actual[5],.000002);
    }
}
