package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Actual Media3 admission, before hardware decoding or GPU output. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class NativePreviewAdmissionTest {
    @Test public void knownVideoEntersRealCompositionPlayerSourceAdmission()throws Exception{
        admit(composition(project(),1));
    }

    @Test public void fullSourceDurationIsAppliedBeforeTrimAndSpeed()throws Exception{
        EditedMediaItem item=composition(project(),2).sequences.get(0).editedMediaItems.get(0);
        assertEquals(10_000_000,item.durationUs);
        assertEquals(1_000_000,item.mediaItem.clippingConfiguration.startPositionUs);
        assertEquals(4_000_000,item.mediaItem.clippingConfiguration.endPositionUs);
        assertEquals(1_500_000,item.getPresentationDurationUs());
        assertEquals(C.TIME_UNSET,item.mediaItem.localConfiguration.imageDurationMs);
    }

    @Test public void mixedVideoStillCaptionAndTrimmedAudioHaveAdmissibleClocks()throws Exception{
        ProjectStore.Project p=project();
        ProjectStore.Asset image=asset("still","image/png",0);p.assets.add(image);
        ProjectStore.Clip still=new ProjectStore.Clip();still.id="still-clip";still.assetId=image.id;still.outMs=2000;p.clips.add(still);
        ProjectStore.Asset audio=asset("audio","audio/mp4",6000);p.assets.add(audio);
        StudioLayer music=new StudioLayer();music.id="music";music.type=StudioLayer.AUDIO;music.assetId=audio.id;
        music.sourceInMs=1000;music.durationMs=2000;music.startMs=500;p.studioLayers.add(music);
        StudioLayer title=new StudioLayer();title.id="title";title.type=StudioLayer.TITLE;title.text="Caption";title.durationMs=1000;p.studioLayers.add(title);
        Composition c=composition(p,1);admit(c);
        assertEquals(2,c.sequences.size());
        assertEquals(2_000_000,c.sequences.get(0).editedMediaItems.get(1).getPresentationDurationUs());
        EditedMediaItem soundtrack=c.sequences.get(1).editedMediaItems.get(1);
        assertEquals(6_000_000,soundtrack.durationUs);
        assertEquals(2_000_000,soundtrack.getPresentationDurationUs());
        assertFalse(c.effects.videoEffects.isEmpty());
    }

    @Test public void unknownVideoDurationIsNeverInventedFromTrimOrImageMetadata()throws Exception{
        ProjectStore.Project p=project();p.assets.get(0).durationMs=0;
        EditedMediaItem item=composition(p,1).sequences.get(0).editedMediaItems.get(0);
        assertEquals(C.TIME_UNSET,item.durationUs);
        assertEquals(C.TIME_UNSET,item.mediaItem.localConfiguration.imageDurationMs);
        assertEquals(0,p.assets.get(0).durationMs);
    }

    @Test public void ordinaryStillAdmissionDoesNotNeedVideoMetadata()throws Exception{
        ProjectStore.Project p=project();p.assets.get(0).mime="image/png";p.assets.get(0).uri="file:///not-decoded.png";
        p.assets.get(0).durationMs=0;p.clips.get(0).inMs=0;p.clips.get(0).outMs=3000;
        Composition c=composition(p,1);admit(c);
        assertEquals(3_000_000,c.sequences.get(0).editedMediaItems.get(0).getPresentationDurationUs());
    }

    private static Composition composition(ProjectStore.Project p,float speed)throws Exception{
        p.clips.get(0).speed=speed;
        return new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(p,"9:16","720p");
    }
    static ProjectStore.Project project(){
        ProjectStore.Project p=new ProjectStore.Project();p.id="admission";p.assets.add(asset("video","video/mp4",10000));
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="video-clip";c.assetId="video";c.inMs=1000;c.outMs=4000;p.clips.add(c);return p;
    }
    private static ProjectStore.Asset asset(String id,String mime,long duration){
        ProjectStore.Asset a=new ProjectStore.Asset();a.id=id;a.mime=mime;a.uri="file:///not-decoded-"+id+(mime.startsWith("image/")?".png":".mp4");a.durationMs=duration;return a;
    }
    private static void admit(Composition c)throws Exception{
        Method m=CompositionPlayer.class.getDeclaredMethod("createPrimarySequenceMediaSource",EditedMediaItemSequence.class,MediaSource.Factory.class,boolean.class);m.setAccessible(true);
        for(EditedMediaItemSequence sequence:c.sequences){
            try{assertNotNull(m.invoke(null,sequence,new DefaultMediaSourceFactory(RuntimeEnvironment.getApplication()),false));}
            catch(InvocationTargetException e){throw new AssertionError("Actual CompositionPlayer admission failed",e.getCause());}
        }
    }
}
