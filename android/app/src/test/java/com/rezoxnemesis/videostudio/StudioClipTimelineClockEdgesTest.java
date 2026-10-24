package com.rezoxnemesis.videostudio;

import androidx.media3.common.Effect;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.Size;
import androidx.media3.effect.GlMatrixTransformation;
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
import java.util.List;
import static org.junit.Assert.*;

/** Independent edge cases against existing523 public callbacks; not codec execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioClipTimelineClockEdgesTest {
    @Test public void fractionalPredecessorUsesMedia3FloorInMicrosecondsNotRoundedMilliseconds()throws Exception{
        ProjectStore.Project p=project();video(p,"fraction",0,1001,3);video(p,"next",0,1000,1);wideZoom(p.clips.get(1));
        EditedMediaItem next=items(build(p)).get(1);
        // Media3 floors1001000/3 to333666us. The next microsecond must advance motion.
        scale(next,333_666,1);scale(next,333_667,1.000007);scale(next,833_666,4.5);
    }

    @Test public void eachFractionalPredecessorIsFlooredBeforeAccumulatingTheThirdStart()throws Exception{
        ProjectStore.Project p=project();video(p,"a",0,1001,3);video(p,"b",0,1001,3);video(p,"next",0,1000,1);wideZoom(p.clips.get(2));
        EditedMediaItem next=items(build(p)).get(2);
        // Two independently presented333666us items: notfloor(2002000/3)=667333.
        scale(next,667_332,1);scale(next,667_333,1.000007);scale(next,1_167_332,4.5);
    }

    @Test public void unknownVideoMetadataRetainsAnExplicitAuthoredTrimClock()throws Exception{
        ProjectStore.Project p=project();video(p,"unknown",250,1251,3);p.assets.get(0).durationMs=0;
        video(p,"next",4000,5000,1);wideZoom(p.clips.get(1));Composition c=build(p);
        assertEquals("Do not invent a full-source duration",androidx.media3.common.C.TIME_UNSET,items(c).get(0).durationUs);
        scale(items(c).get(1),333_666,1);scale(items(c).get(1),833_666,4.5);
    }

    @Test public void legacyNearUnitSpeedDeadbandUsesTheActualBuiltDefaultProvider()throws Exception{
        ProjectStore.Project p=project();video(p,"near-unit",0,1000,1.005f);video(p,"next",0,1000,1);wideZoom(p.clips.get(1));
        List<EditedMediaItem> items=items(build(p));assertSame(SpeedProvider.DEFAULT,items.get(0).speedProvider);
        scale(items.get(1),1_000_000,1);scale(items.get(1),1_500_000,4.5);
    }

    @Test public void editedImageDurationIsNotDividedByItsSpeedASecondTime()throws Exception{
        ProjectStore.Project p=project();image(p,"photo",1000);p.clips.get(0).speed=2;
        video(p,"next",0,1000,1);wideZoom(p.clips.get(1));List<EditedMediaItem> items=items(build(p));
        assertEquals(500_000,items.get(0).durationUs);assertSame(SpeedProvider.DEFAULT,items.get(0).speedProvider);
        scale(items.get(1),500_000,1);scale(items.get(1),1_000_000,4.5);
    }

    @Test public void speedAdjustedCurrentItemCallbacksAreNotDividedBySpeedAgain()throws Exception{
        ProjectStore.Project p=project();video(p,"prefix",0,1000,1);video(p,"fast-animated",5000,7000,2);wideZoom(p.clips.get(1));
        EditedMediaItem fast=items(build(p)).get(1);
        scale(fast,1_000_000,1);scale(fast,1_500_000,4.5);scale(fast,1_999_999,7.999993);
    }

    @Test public void singleFinalShortVideoKeepsItsLegacyCompositionAdmission()throws Exception{
        ProjectStore.Project p=project();video(p,"short-source",0,50,1);p.assets.get(0).durationMs=50;
        // Existing renderer applies a100ms video trim minimum. No later item needs
        // a computed duration, so do not introduce eager metadata rejection here.
        List<EditedMediaItem> items=items(build(p));assertEquals(1,items.size());
        assertEquals(100_000,items.get(0).mediaItem.clippingConfiguration.endPositionUs);scale(items.get(0),0,1);
    }

    @Test public void unusedFinalDurationDoesNotRejectAnOtherwiseAdmittedMultiItemComposition()throws Exception{
        ProjectStore.Project p=project();video(p,"prefix",0,1000,1);video(p,"last-short",0,50,1);p.assets.get(1).durationMs=50;
        List<EditedMediaItem> items=items(build(p));assertEquals(2,items.size());
        assertEquals(100_000,items.get(1).mediaItem.clippingConfiguration.endPositionUs);scale(items.get(1),1_000_000,1);
    }

    @Test public void originZeroKeepsRawLocalSamplerAndPixelApisUnchanged()throws Exception{
        ProjectStore.Project p=project();video(p,"single",4000,5000,1);wideZoom(p.clips.get(0));
        JSONObject effects=p.clips.get(0).effects;JSONArray keys=effects.getJSONArray("keyframes");
        keys.getJSONObject(0).put("opacity",.2);keys.getJSONObject(1).put("opacity",.8);
        String before=p.toJson().toString();EditedMediaItem single=items(build(p)).get(0);
        StudioClipKeyframes local=StudioClipKeyframes.fromEffects(effects,1000);StudioPixelEffect raw=new StudioPixelEffect(effects,1000);
        StudioPixelEffect attached=pixels(single);
        for(long at:new long[]{0,1,250_000,500_000,999_999}){
            assertEquals(local.atUs(at).scaleX,matrix(single,at)[0],.000001);
            assertEquals(raw.opacityAtUs(at),attached.opacityAtUs(at),0);
        }
        StudioLayer image=StudioLayer.fromJson(new JSONObject().put("type","image").put("assetId","own-image").put("durationMs",1000)
                .put("motionKeyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("x",0))
                        .put(new JSONObject().put("timeMs",1000).put("x",.4))));
        assertEquals(.2,StudioImageMotion.fromLayer(image).atUs(500_000).x,0);
        assertEquals(before,p.toJson().toString());
    }

    @Test public void layeredPixelEffectsUseTheSameActual700msOriginAsTheirMotion()throws Exception{
        ProjectStore.Project p=project();image(p,"a",100);image(p,"b",100);
        for(ProjectStore.Clip clip:p.clips)clip.effects.put("animatedScene",true)
                .put("foregroundUri","file:///owned/front.png").put("backgroundUri","file:///owned/back.png")
                .put("animationSpec",new JSONObject().put("cameraPreset","none"))
                .put("keyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("opacity",.2))
                        .put(new JSONObject().put("timeMs",100).put("opacity",.8)));
        Composition c=build(p);assertEquals(2,c.sequences.size());
        for(int sequence=0;sequence<2;sequence++){
            StudioPixelEffect pixel=pixels(c.sequences.get(sequence).editedMediaItems.get(1));
            assertEquals(.2,pixel.opacityAtUs(700_000),.000001);assertEquals(.5,pixel.opacityAtUs(750_000),.000001);
            assertEquals(.8,pixel.opacityAtUs(799_999),.00001);
        }
    }

    private static ProjectStore.Project project(){ProjectStore.Project p=new ProjectStore.Project();p.id="clock-edge-fixture";p.name="Native renderer clock boundaries";return p;}
    private static void video(ProjectStore.Project p,String id,long in,long out,float speed){
        ProjectStore.Asset a=new ProjectStore.Asset();a.id=id+"-asset";a.mime="video/mp4";a.uri="file:///owned/"+id+".mp4";a.durationMs=120_000;p.assets.add(a);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id=id;c.assetId=a.id;c.inMs=in;c.outMs=out;c.speed=speed;p.clips.add(c);
    }
    private static void image(ProjectStore.Project p,String id,long duration){
        ProjectStore.Asset a=new ProjectStore.Asset();a.id=id+"-asset";a.mime="image/png";a.uri="file:///owned/"+id+".png";p.assets.add(a);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id=id;c.assetId=a.id;c.outMs=duration;p.clips.add(c);
    }
    private static void wideZoom(ProjectStore.Clip c)throws Exception{
        c.effects.put("keyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("scale",1))
                .put(new JSONObject().put("timeMs",1000).put("scale",8)));
    }
    private static Composition build(ProjectStore.Project p)throws Exception{return new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(p,"9:16","720p");}
    private static List<EditedMediaItem> items(Composition c){return c.sequences.get(0).editedMediaItems;}
    private static StudioPixelEffect pixels(EditedMediaItem item){return (StudioPixelEffect)item.effects.videoEffects.stream().filter(e->e instanceof StudioPixelEffect).findFirst().get();}
    private static float[] matrix(EditedMediaItem item,long globalUs){
        float[] result=new float[16];android.opengl.Matrix.setIdentityM(result,0);int width=720,height=1280;
        for(Effect effect:item.effects.videoEffects)if(effect instanceof GlMatrixTransformation){
            GlMatrixTransformation t=(GlMatrixTransformation)effect;Size size=t.configure(width,height);width=size.getWidth();height=size.getHeight();
            float[] next=new float[16];android.opengl.Matrix.multiplyMM(next,0,t.getGlMatrixArray(globalUs),0,result,0);result=next;
        }
        return result;
    }
    private static void scale(EditedMediaItem item,long globalUs,double expected){
        float[] value=matrix(item,globalUs);assertEquals("Xscale at global"+globalUs,expected,value[0],.0000002);
        assertEquals("Yscale at global"+globalUs,expected,value[5],.0000002);
    }
}
