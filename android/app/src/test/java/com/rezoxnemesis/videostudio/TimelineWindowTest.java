package com.rezoxnemesis.videostudio;

import androidx.media3.common.Effect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class TimelineWindowTest {
    private final TimelineCompositionFactory factory=new TimelineCompositionFactory(RuntimeEnvironment.getApplication());
    private ProjectStore.Project project(String mime)throws Exception{
        ProjectStore.Project p=new ProjectStore.Project();p.id="window-proof";p.ensureTimelineDefaults();
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="source";a.mime=mime;a.uri="file:///original";a.durationMs=20000;a.generationMetadata.put("audioMetadataKnown",true).put("hasAudio",true);p.assets.add(a);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="clip";c.assetId=a.id;c.trackId=p.tracks.get(0).id;c.outMs=12000;p.clips.add(c);return p;
    }
    private EditedMediaItem source(Composition composition,String uri){
        for(var sequence:composition.sequences)for(var item:sequence.editedMediaItems)
            if(item.mediaItem.localConfiguration!=null&&uri.equals(item.mediaItem.localConfiguration.uri.toString()))return item;
        throw new AssertionError("Source not found: "+uri);
    }
    private <T> T effect(EditedMediaItem item,Class<T> type){for(Effect effect:item.effects.videoEffects)if(type.isInstance(effect))return type.cast(effect);throw new AssertionError("Effect not found "+type);}
    private float[] matrix(android.graphics.Matrix m){float[] values=new float[9];m.getValues(values);return values;}
    @Test public void midClipWindowPreservesSourceSpeedAndOriginalAutomation()throws Exception{
        ProjectStore.Project p=project("video/mp4");var clip=p.clips.get(0);clip.startMs=2000;clip.inMs=1000;clip.outMs=17000;clip.speed=2;
        for(String property:new String[]{"x","brightness"})for(int i=0;i<2;i++)clip.keyframes.put(new JSONObject().put("property",property).put("timeMs",i*8000).put("value",i));
        String before=p.toJson().toString();Composition whole=factory.build(p,"16:9","720p",false);
        Composition window=factory.buildVideoWindow(p,"16:9","720p",new TimelineWindow(5_000_000,7_000_000));EditedMediaItem item=source(window,"file:///original");
        assertEquals(7_000_000,item.mediaItem.clippingConfiguration.startPositionUs);assertEquals(11_000_000,item.mediaItem.clippingConfiguration.endPositionUs);assertEquals(2,item.speedProvider.getSpeed(0),0);assertTrue(item.removeAudio);assertTrue(item.effects.audioProcessors.isEmpty());
        EditedMediaItem original=source(whole,"file:///original");
        assertArrayEquals(matrix(effect(original,ClipTransformEffect.class).getMatrix(5_400_000)),matrix(effect(item,ClipTransformEffect.class).getMatrix(400_000)),.000001f);
        assertArrayEquals(effect(original,ClipColourEffect.class).getMatrix(5_400_000,false),effect(item,ClipColourEffect.class).getMatrix(400_000,false),.000001f);
        assertEquals(before,p.toJson().toString());
    }
    @Test public void windowRetainsLeadingGapAndOpaqueBaseAndExcludesHiddenTracks()throws Exception{
        ProjectStore.Project p=project("image/png");p.clips.get(0).startMs=6000;p.clips.get(0).outMs=2000;
        ProjectStore.Asset audio=new ProjectStore.Asset();audio.id="audio";audio.uri="file:///sound";audio.mime="audio/wav";audio.durationMs=10000;p.assets.add(audio);
        ProjectStore.Track sound=new ProjectStore.Track();sound.id="audio-track";sound.type="audio_music";sound.order=1;p.tracks.add(sound);
        ProjectStore.Clip soundClip=new ProjectStore.Clip();soundClip.id="sound";soundClip.assetId=audio.id;soundClip.trackId=sound.id;soundClip.outMs=10000;p.clips.add(soundClip);
        Composition window=factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(5_000_000,10_000_000));assertEquals(2,window.sequences.size());
        assertEquals(1_000_000,window.sequences.get(0).editedMediaItems.get(0).durationUs);assertEquals(2_000_000,source(window,"file:///original").mediaItem.localConfiguration.imageDurationMs*1000L);
        assertEquals(5_000_000,window.sequences.get(1).editedMediaItems.get(0).mediaItem.localConfiguration.imageDurationMs*1000L);
        p.tracks.get(0).visible=false;assertEquals(1,factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(5_000_000,10_000_000)).sequences.size());
    }
    @Test public void windowHonoursSoloAndTopTrackOrderWithoutReadingProxies()throws Exception{
        ProjectStore.Project p=project("image/png");ProjectStore.Asset top=new ProjectStore.Asset();top.id="top";top.uri="file:///top-original";top.mime="image/png";top.generationMetadata.put("proxyUri","file:///preview-proxy");p.assets.add(top);
        ProjectStore.Track track=new ProjectStore.Track();track.id="top-track";track.order=3;track.type="video";p.tracks.add(track);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="top-clip";clip.assetId=top.id;clip.trackId=track.id;clip.outMs=12000;p.clips.add(clip);
        Composition both=factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(5_000_000,7_000_000));assertEquals("file:///top-original",both.sequences.get(0).editedMediaItems.get(0).mediaItem.localConfiguration.uri.toString());assertEquals(3,both.sequences.size());
        track.solo=true;track.muted=true;Composition solo=factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(5_000_000,7_000_000));assertEquals(2,solo.sequences.size());assertEquals("file:///top-original",solo.sequences.get(0).editedMediaItems.get(0).mediaItem.localConfiguration.uri.toString());
    }
    @Test public void generatedLayersUseWindowDurationAndOriginalMotionClock()throws Exception{
        ProjectStore.Project p=project("image/png");var clip=p.clips.get(0);clip.effects.put("animatedScene",true).put("foregroundUri","file:///plate").put("backgroundUri","file:///background").put("animationSpec",new JSONObject().put("cameraPreset","push_in"));
        Composition whole=factory.build(p,"16:9","360p",false),window=factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(5_000_000,7_000_000));
        EditedMediaItem plate=source(window,"file:///plate");assertEquals(2_000_000,plate.mediaItem.localConfiguration.imageDurationMs*1000L);assertTrue(plate.removeAudio);
        assertArrayEquals(matrix(effect(source(whole,"file:///plate"),MotionMatrixEffect.class).getMatrix(5_250_000)),matrix(effect(plate,MotionMatrixEffect.class).getMatrix(250_000)),.000001f);
        assertEquals(2_000_000,source(window,"file:///background").mediaItem.localConfiguration.imageDurationMs*1000L);
    }
    @Test public void tinyVideoRemainderUsesMicrosecondsWithoutMinimumEditorTrim()throws Exception{
        ProjectStore.Project p=project("video/mp4");EditedMediaItem item=source(factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(11_998_999,11_999_999)),"file:///original");
        assertEquals(11_998_999,item.mediaItem.clippingConfiguration.startPositionUs);assertEquals(11_999_999,item.mediaItem.clippingConfiguration.endPositionUs);
    }
    @Test public void threeTimesSpeedUsesExactSourceMicrosecondSeek()throws Exception{
        ProjectStore.Project p=project("video/mp4");var clip=p.clips.get(0);clip.inMs=1000;clip.outMs=4000;clip.speed=3;
        EditedMediaItem item=source(factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(100_001,200_001)),"file:///original");
        assertEquals(1_300_003,item.mediaItem.clippingConfiguration.startPositionUs);assertEquals(1_600_003,item.mediaItem.clippingConfiguration.endPositionUs);
    }
    @Test public void signedEffectOriginRetainsElapsedClipClock(){assertEquals(5300,TimelineMath.effectLocalMs(300_000,-5_000_000));assertEquals(0,TimelineMath.effectLocalMs(300_000,400_000));}
    @Test public void finalWindowIncludesAccumulatedFractionalSpeedRemainder()throws Exception{
        ProjectStore.Project p=project("video/mp4");p.clips.clear();
        for(int i=0;i<300;i++){ProjectStore.Clip c=new ProjectStore.Clip();c.id="c"+i;c.assetId="source";c.trackId=p.tracks.get(0).id;c.startMs=i*333;c.outMs=1000;c.speed=3;p.clips.add(c);}
        Composition window=factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(99_999_000,99_999_900));assertNotNull(source(window,"file:///original"));assertEquals(900,window.sequences.get(window.sequences.size()-1).editedMediaItems.get(0).durationUs);
    }
    @Test public void imageAndBaseRetainExactMicrosecondDuration()throws Exception{
        ProjectStore.Project p=project("image/png");Composition window=factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(0,1001));
        assertEquals(1001,source(window,"file:///original").durationUs);assertEquals(1001,window.sequences.get(1).editedMediaItems.get(0).durationUs);
        p.clips.get(0).effects.put("animatedScene",true).put("foregroundUri","file:///plate").put("backgroundUri","file:///background");Composition layers=factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(0,1001));assertEquals(1001,source(layers,"file:///plate").durationUs);assertEquals(1001,source(layers,"file:///background").durationUs);
    }
    @Test public void imageAfterFractionalSpeedUsesExactSliceDuration()throws Exception{
        ProjectStore.Project p=project("video/mp4");p.clips.get(0).outMs=1000;p.clips.get(0).speed=3;
        ProjectStore.Asset image=new ProjectStore.Asset();image.id="image";image.uri="file:///image.png";image.mime="image/png";p.assets.add(image);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="image-clip";c.assetId=image.id;c.trackId=p.tracks.get(0).id;c.startMs=333;c.outMs=1000;p.clips.add(c);
        assertEquals(666667,source(factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(0,1_000_000)),"file:///image.png").durationUs);
    }
    @Test public void capturingUnnormalizedOwnerProjectOnlyNormalizesTheCopy()throws Exception{
        ProjectStore.Project p=project("image/png");p.tracks.clear();var clip=p.clips.get(0);clip.trackId="";clip.startMs=-1;
        factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(0,1_000_000));assertTrue(p.tracks.isEmpty());assertEquals("",clip.trackId);assertEquals(-1,clip.startMs);
        factory.build(p,"16:9","360p",false);assertTrue(p.tracks.isEmpty());assertEquals("",clip.trackId);assertEquals(-1,clip.startMs);
    }
    @Test public void soloSelectionDoesNotValidateAnExcludedMissingSource()throws Exception{
        ProjectStore.Project p=project("image/png");p.tracks.get(0).solo=true;ProjectStore.Track excluded=new ProjectStore.Track();excluded.id="excluded";p.tracks.add(excluded);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="excluded-clip";clip.assetId="absent";clip.trackId=excluded.id;clip.outMs=12000;p.clips.add(clip);
        assertNotNull(factory.build(p,"16:9","360p",false));assertNotNull(factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(0,5_000_000)));
    }
    @Test public void soloSelectionDoesNotExtendProgramWithExcludedFractionalClock()throws Exception{
        ProjectStore.Project p=project("image/png");p.tracks.get(0).solo=true;p.clips.get(0).outMs=99900;ProjectStore.Track excluded=new ProjectStore.Track();excluded.id="excluded";p.tracks.add(excluded);
        ProjectStore.Asset video=new ProjectStore.Asset();video.id="excluded-video";video.mime="video/mp4";video.uri="file:///excluded.mp4";p.assets.add(video);
        for(int i=0;i<300;i++){ProjectStore.Clip c=new ProjectStore.Clip();c.id="c"+i;c.assetId=video.id;c.trackId=excluded.id;c.startMs=i*333;c.outMs=1000;c.speed=3;p.clips.add(c);}
        assertEquals(99_900_000,TimelineCompositionFactory.programDurationUs(p));
    }
    @Test public void invalidWindowAndOutOfProgramWindowFail()throws Exception{
        for(long[] range:new long[][]{{-1,1},{0,0},{5,4},{0,10_000_001}})try{new TimelineWindow(range[0],range[1]);fail("Invalid interval accepted");}catch(IllegalArgumentException expected){}
        try{factory.buildVideoWindow(project("image/png"),"16:9","360p",new TimelineWindow(12_000_000,13_000_000));fail("Past program accepted");}catch(IllegalArgumentException expected){}
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test public void shortWindowDoesNotShortenOriginalTitleEntrance()throws Exception{
        ProjectStore.Project p=project("image/png");p.clips.get(0).title="ORIGINAL TITLE";p.clips.get(0).effects.put("textAnimation","fade");
        EditedMediaItem whole=source(factory.build(p,"16:9","360p",false),"file:///original"),window=source(factory.buildVideoWindow(p,"16:9","360p",new TimelineWindow(100_000,300_000)),"file:///original");
        Bitmap a=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);
        try{title(whole).onDraw(new Canvas(a),200_000);title(window).onDraw(new Canvas(b),100_000);int[] av=new int[640*360],bv=new int[av.length];a.getPixels(av,0,640,0,0,640,360);b.getPixels(bv,0,640,0,0,640,360);assertArrayEquals(av,bv);assertTrue(java.util.Arrays.stream(av).anyMatch(v->v!=0));}finally{a.recycle();b.recycle();}
    }
    @SuppressWarnings("unchecked") private ClipTitleOverlay title(EditedMediaItem item)throws Exception{
        var field=androidx.media3.effect.OverlayEffect.class.getDeclaredField("overlays");field.setAccessible(true);
        for(Effect e:item.effects.videoEffects)if(e instanceof androidx.media3.effect.OverlayEffect)for(var overlay:(java.util.List<androidx.media3.effect.TextureOverlay>)field.get(e))if(overlay instanceof ClipTitleOverlay)return (ClipTitleOverlay)overlay;
        throw new AssertionError("Missing title");
    }
}
