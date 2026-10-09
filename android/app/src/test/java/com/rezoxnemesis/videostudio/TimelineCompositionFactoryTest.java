package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import androidx.media3.common.Effect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class TimelineCompositionFactoryTest {
    private ProjectStore.Project videoProject()throws Exception{
        ProjectStore.Project p=new ProjectStore.Project();p.id="duration-proof";p.ensureTimelineDefaults();
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="video";a.mime="video/mp4";a.uri="file:///source.mp4";a.durationMs=4000;
        a.generationMetadata.put("audioMetadataKnown",true).put("hasAudio",false);p.assets.add(a);
        return p;
    }
    private ProjectStore.Clip clip(String id,long start,long in,long out,float speed){
        ProjectStore.Clip c=new ProjectStore.Clip();c.id=id;c.assetId="video";c.trackId="video-1";c.startMs=start;c.inMs=in;c.outMs=out;c.speed=speed;return c;
    }
    @Test public void programItemsDeclareFullSourceDurationBeforeTrimAndSpeed()throws Exception{
        ProjectStore.Project p=videoProject();p.clips.add(clip("first",0,1000,3000,2));
        Composition composition=new TimelineCompositionFactory(RuntimeEnvironment.getApplication()).build(p,"16:9","360p",false);
        EditedMediaItem item=composition.sequences.get(0).editedMediaItems.get(0);
        assertEquals(4000000,item.durationUs);assertEquals(1000,item.mediaItem.clippingConfiguration.startPositionMs);assertEquals(3000,item.mediaItem.clippingConfiguration.endPositionMs);
        assertEquals(1000000,TimelineCompositionFactory.itemOutputUs(p.clips.get(0),p.assets.get(0)));
        for(Effect effect:composition.sequences.get(1).editedMediaItems.get(0).effects.videoEffects)
            if(effect instanceof ClipTransformEffect){float[] matrix=new float[9];((ClipTransformEffect)effect).getMatrix(0).getValues(matrix);assertEquals(0,matrix[Matrix.MTRANS_X],.000001);}
    }
    @Test public void secondRetimedClipStartsItsOwnTransformAndColourAutomation()throws Exception{
        ProjectStore.Project p=videoProject();p.clips.add(clip("first",0,0,1000,3));ProjectStore.Clip second=clip("second",333,1000,2000,1);
        for(String property:new String[]{"x","brightness"}){
            second.keyframes.put(new JSONObject().put("property",property).put("timeMs",0).put("value",0).put("easing","linear"));
            second.keyframes.put(new JSONObject().put("property",property).put("timeMs",1000).put("value",1).put("easing","linear"));
        }
        p.clips.add(second);Composition composition=new TimelineCompositionFactory(RuntimeEnvironment.getApplication()).build(p,"16:9","360p",false);
        EditedMediaItem item=composition.sequences.get(0).editedMediaItems.get(1);boolean transform=false,colour=false;
        for(Effect effect:item.effects.videoEffects){
            if(effect instanceof ClipTransformEffect){transform=true;float[] matrix=new float[9];((ClipTransformEffect)effect).getMatrix(333333).getValues(matrix);assertEquals(0,matrix[Matrix.MTRANS_X],.000001);((ClipTransformEffect)effect).getMatrix(833333).getValues(matrix);assertEquals(.5,matrix[Matrix.MTRANS_X],.000001);}
            if(effect instanceof ClipColourEffect){colour=true;assertEquals(0,((ClipColourEffect)effect).getMatrix(333333,false)[12],.000001);assertEquals(.5,((ClipColourEffect)effect).getMatrix(833333,false)[12],.000001);}
        }
        assertTrue(transform);assertTrue(colour);
    }
    @Test public void manyRetimedClipsUseMedia3MicrosecondDurations()throws Exception{
        ProjectStore.Project p=videoProject();ProjectStore.Clip c=clip("retimed",0,0,1000,3);long cursor=0;
        for(int i=0;i<300;i++)cursor+=TimelineCompositionFactory.itemOutputUs(c,p.assets.get(0));
        assertEquals(99999900,cursor);assertEquals(0,TimelineMath.effectLocalMs(cursor,cursor));
    }
    @Test public void animatedColourWithoutAGradePreservesOriginalRgb()throws Exception{
        for(String preset:new String[]{"", "none", "film_grain"}){
            ProjectStore.Clip c=clip("neutral",0,0,1000,1);
            if(!preset.isEmpty())c.effects.put("effectPreset",preset);
            float[] matrix=new ClipColourEffect(c).getMatrix(500000,false);
            assertArrayEquals("No colour grade was requested: "+preset,
                    new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1},matrix,.000001f);
        }
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test public void titleAnimationDrawsFewerPixelsAtItsOwnClipEntrance()throws Exception{
        ProjectStore.Project p=videoProject();ProjectStore.Clip title=clip("title",1000,0,1500,1);
        title.title="VISIBLE TITLE";title.effects.put("fontFamily","monospace").put("textAnimation","fade");p.clips.add(title);
        Composition composition=new TimelineCompositionFactory(RuntimeEnvironment.getApplication()).build(p,"16:9","360p",false);
        boolean found=false;
        EditedMediaItem titledItem=null;
        for(EditedMediaItem item:composition.sequences.get(0).editedMediaItems)
            if(item.mediaItem.localConfiguration!=null&&"file:///source.mp4".equals(item.mediaItem.localConfiguration.uri.toString()))titledItem=item;
        assertNotNull("Find the titled source item after its leading gap",titledItem);
        for(Effect effect:titledItem.effects.videoEffects){
            if(!(effect instanceof androidx.media3.effect.OverlayEffect))continue;
            java.lang.reflect.Field overlays=androidx.media3.effect.OverlayEffect.class.getDeclaredField("overlays");overlays.setAccessible(true);
            for(androidx.media3.effect.TextureOverlay overlay:(java.util.List<androidx.media3.effect.TextureOverlay>)overlays.get(effect)){
                if(!(overlay instanceof androidx.media3.effect.CanvasOverlay))continue;
                found=true;androidx.media3.effect.CanvasOverlay canvasOverlay=(androidx.media3.effect.CanvasOverlay)overlay;
                android.graphics.Bitmap start=android.graphics.Bitmap.createBitmap(640,360,android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Bitmap middle=android.graphics.Bitmap.createBitmap(640,360,android.graphics.Bitmap.Config.ARGB_8888);
                canvasOverlay.onDraw(new android.graphics.Canvas(start),1000000);
                canvasOverlay.onDraw(new android.graphics.Canvas(middle),1700000);
                assertEquals("The title must start transparently at its clip entrance",0,nonTransparentPixels(start));
                assertTrue("A title must actually draw visible glyphs",nonTransparentPixels(middle)>100);
                start.recycle();middle.recycle();
            }
        }
        assertTrue("Titles need a frame-evaluated overlay rather than a static bitmap",found);
    }
    private int nonTransparentPixels(android.graphics.Bitmap bitmap){
        int count=0;for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if(android.graphics.Color.alpha(bitmap.getPixel(x,y))>0)count++;return count;
    }
    @Test public void silentAudioGapMatchesRetimedVideoDuration()throws Exception{
        ProjectStore.Project p=videoProject();p.assets.get(0).generationMetadata.put("hasAudio",true);
        ProjectStore.Asset silent=new ProjectStore.Asset();silent.id="silent";silent.mime="video/mp4";silent.uri="file:///silent.mp4";silent.durationMs=4000;silent.generationMetadata.put("audioMetadataKnown",true).put("hasAudio",false);p.assets.add(silent);
        p.clips.add(clip("first",0,0,1000,3));ProjectStore.Clip gap=clip("silent-clip",333,0,1000,3);gap.assetId=silent.id;p.clips.add(gap);p.clips.add(clip("last",666,0,1000,1));
        Composition composition=new TimelineCompositionFactory(RuntimeEnvironment.getApplication()).build(p,"16:9","360p",false);
        assertEquals(3,composition.sequences.size());
        EditedMediaItem audioGap=composition.sequences.get(2).editedMediaItems.get(1);assertEquals(333333,audioGap.durationUs);
    }
}
