package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** Actual public native composition admission. No fixture URI is decoded; no GPU claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioVideoCompositionTest {
    @Test public void independentVideoHasRealVideoClippingAndSilentSourceWithPaddedOutputSpan() throws Exception {
        ProjectStore.Project p=sceneWithVideo();Composition c=composition(p);
        assertEquals(2,c.sequences.size());
        EditedMediaItemSequence overlay=sequenceFor(c,p.asset("overlay-source").uri);
        assertNotSame(c.sequences.get(0),overlay);assertEquals(3,overlay.editedMediaItems.size());
        assertTrue(isGap(overlay.editedMediaItems.get(0)));assertTrue(isGap(overlay.editedMediaItems.get(2)));
        assertEquals(1_750_000,overlay.editedMediaItems.get(0).getPresentationDurationUs());
        assertEquals(3_250_000,overlay.editedMediaItems.get(2).getPresentationDurationUs());
        EditedMediaItem source=overlay.editedMediaItems.get(1);
        assertFalse(isGap(source));assertTrue(source.removeAudio);assertFalse(source.removeVideo);
        assertEquals(C.TIME_UNSET,source.mediaItem.localConfiguration.imageDurationMs);
        assertEquals(C.RATE_UNSET_INT,source.frameRate);
        assertEquals(6_000_000,source.durationUs);
        assertEquals(1_500_000,source.mediaItem.clippingConfiguration.startPositionUs);
        assertEquals(3_500_000,source.mediaItem.clippingConfiguration.endPositionUs);
        assertEquals(2_000_000,source.getPresentationDurationUs());
        assertEquals(7_000_000,durationUs(overlay));
    }

    @Test public void originalBaseRemainsPrimaryUncutAndKeepsSourceAudioAcrossOverlayStartAndEnd() throws Exception {
        ProjectStore.Project p=StudioVideoLayerContractTest.scene();
        Composition before=composition(p);
        p.studioLayers.add(StudioVideoLayerContractTest.parse(p,StudioVideoLayerContractTest.video("insert")));
        Composition after=composition(p);
        EditedMediaItemSequence base=after.sequences.get(0);assertEquals(2,base.editedMediaItems.size());
        for(int i=0;i<2;i++){
            EditedMediaItem a=before.sequences.get(0).editedMediaItems.get(i),b=base.editedMediaItems.get(i);
            assertEquals(p.asset("base").uri,b.mediaItem.localConfiguration.uri.toString());
            assertEquals(a.mediaItem.clippingConfiguration.startPositionUs,b.mediaItem.clippingConfiguration.startPositionUs);
            assertEquals(a.mediaItem.clippingConfiguration.endPositionUs,b.mediaItem.clippingConfiguration.endPositionUs);
            assertEquals(a.durationUs,b.durationUs);assertEquals(a.getPresentationDurationUs(),b.getPresentationDurationUs());
            assertEquals(a.frameRate,b.frameRate);assertFalse(b.removeAudio);assertFalse(b.removeVideo);
        }
        assertEquals(7_000_000,durationUs(base));assertEquals(7000,p.outputDurationMs());
        assertEquals(2,p.clips.size());assertEquals(4,p.assets.size());
    }

    @Test public void twoVideoSourcesKeepIndependentTrimsWithExistingAudioAndFinalTitles() throws Exception {
        ProjectStore.Project p=sceneWithVideo();
        p.studioLayers.add(StudioVideoLayerContractTest.parse(p,StudioVideoLayerContractTest.video("second").put("startMs",4000).put("durationMs",1000).put("sourceInMs",0)));
        StudioLayer audio=new StudioLayer();audio.id="music";audio.type=StudioLayer.AUDIO;audio.assetId="sound";
        audio.startMs=500;audio.sourceInMs=1000;audio.durationMs=1500;audio.gain=.5f;p.studioLayers.add(audio);
        StudioLayer title=new StudioLayer();title.id="caption";title.text="Two independent camera views";title.startMs=2000;title.durationMs=2500;p.studioLayers.add(title);
        Composition c=composition(p);assertEquals(4,c.sequences.size());
        int videos=0,audios=0;
        for(int i=1;i<c.sequences.size();i++){
            EditedMediaItemSequence lane=c.sequences.get(i);assertEquals(7_000_000,durationUs(lane));
            for(EditedMediaItem item:lane.editedMediaItems)if(!isGap(item)){
                assertTrue(item.removeAudio||item.removeVideo);
                if(item.removeAudio){videos++;assertFalse(item.removeVideo);}
                if(item.removeVideo){audios++;assertFalse(item.removeAudio);assertEquals(5_000_000,item.durationUs);}
            }
        }
        assertEquals(2,videos);assertEquals(1,audios);assertFalse(c.effects.videoEffects.isEmpty());
    }

    @Test public void overlayTailUsesActualNativeMicrosecondBaseDurationAfterFractionalSpeed() throws Exception {
        ProjectStore.Project p=sceneWithVideo();p.clips.get(0).speed=1.3f;
        Composition c=composition(p);long baseUs=durationUs(c.sequences.get(0));
        assertNotEquals(p.outputDurationMs()*1000,baseUs);
        EditedMediaItemSequence overlay=sequenceFor(c,p.asset("overlay-source").uri);
        assertEquals(baseUs,durationUs(overlay));
        assertEquals(baseUs-3_750_000,overlay.editedMediaItems.get(2).getPresentationDurationUs());
    }

    @Test public void actualCompositorSettingsGateAtOutputTimeAndSampleMotionOnItsOwnLocalClock() throws Exception {
        ProjectStore.Project p=StudioVideoLayerContractTest.scene();
        p.studioLayers.add(StudioVideoLayerContractTest.parse(p,StudioVideoLayerContractTest.video("insert").put("motionKeyframes",StudioVideoLayerContractTest.keys())));
        Composition c=composition(p);
        c.videoCompositorSettings.getOutputSize(Arrays.asList(new Size(720,1280),new Size(1920,1080)));
        assertEquals(0,c.videoCompositorSettings.getOverlaySettings(1,1_749_999).getAlphaScale(),0);
        OverlaySettings first=c.videoCompositorSettings.getOverlaySettings(1,1_750_000);
        assertEquals(-.5,first.getBackgroundFrameAnchor().first,0);assertEquals(-.5,first.getBackgroundFrameAnchor().second,0);
        assertEquals(12,first.getRotationDegrees(),0);assertEquals(.75,first.getAlphaScale(),0);
        OverlaySettings mid=c.videoCompositorSettings.getOverlaySettings(1,2_750_000);
        assertEquals(-.3,mid.getBackgroundFrameAnchor().first,.000001);assertEquals(-.4,mid.getBackgroundFrameAnchor().second,.000001);
        assertEquals(52,mid.getRotationDegrees(),.000001);assertEquals(.525,mid.getAlphaScale(),.000001);
        OverlaySettings subMillisecond=c.videoCompositorSettings.getOverlaySettings(1,2_750_500);
        assertTrue(subMillisecond.getRotationDegrees()>mid.getRotationDegrees());
        assertTrue(c.videoCompositorSettings.getOverlaySettings(1,3_749_999).getAlphaScale()>0);
        assertEquals(0,c.videoCompositorSettings.getOverlaySettings(1,3_750_000).getAlphaScale(),0);
        assertEquals(0,c.videoCompositorSettings.getOverlaySettings(1,6_999_999).getAlphaScale(),0);
        assertEquals(1,c.videoCompositorSettings.getOverlaySettings(0,2_750_000).getAlphaScale(),0);
    }

    @Test public void gapTexturesCannotResizeCanvasAndTwoVideoLanesHaveIndependentVisibility() throws Exception {
        ProjectStore.Project p=sceneWithVideo();
        p.studioLayers.add(StudioVideoLayerContractTest.parse(p,StudioVideoLayerContractTest.video("second").put("startMs",4000).put("durationMs",1000).put("sourceInMs",0)));
        Composition c=composition(p);
        for(Size second:new Size[]{new Size(16,16),new Size(1920,1080),new Size(1280,720)}){
            Size actual=c.videoCompositorSettings.getOutputSize(Arrays.asList(new Size(720,1280),second,new Size(16,16)));
            assertEquals(720,actual.getWidth());assertEquals(1280,actual.getHeight());
        }
        assertTrue(c.videoCompositorSettings.getOverlaySettings(1,2_000_000).getAlphaScale()>0);
        assertEquals(0,c.videoCompositorSettings.getOverlaySettings(2,2_000_000).getAlphaScale(),0);
        assertEquals(0,c.videoCompositorSettings.getOverlaySettings(1,4_250_000).getAlphaScale(),0);
        assertTrue(c.videoCompositorSettings.getOverlaySettings(2,4_250_000).getAlphaScale()>0);
        assertEquals(0,c.videoCompositorSettings.getOverlaySettings(2,5_000_000).getAlphaScale(),0);
    }

    @Test public void legacyTwoPlateLayeredPathIsPreservedAndExplicitlyRejectsNewVideo() throws Exception {
        ProjectStore.Project p=legacyLayered(false);
        assertEquals(2,composition(p).sequences.size());
        p.studioLayers.add(StudioVideoLayerContractTest.parse(p,StudioVideoLayerContractTest.video("insert")));
        rejectLayeredVideo(p);
    }

    @Test public void legacyFourPlateArticulatedPathIsPreservedAndExplicitlyRejectsNewVideo() throws Exception {
        ProjectStore.Project p=legacyLayered(true);
        assertEquals(4,composition(p).sequences.size());
        p.studioLayers.add(StudioVideoLayerContractTest.parse(p,StudioVideoLayerContractTest.video("insert")));
        rejectLayeredVideo(p);
    }

    private static void rejectLayeredVideo(ProjectStore.Project p)throws Exception{
        try{composition(p);fail("A layered animation must not silently discard an independent VIDEO lane");}
        catch(IllegalArgumentException expected){
            String reason=String.valueOf(expected.getMessage()).toLowerCase(java.util.Locale.ROOT);
            assertTrue(reason.contains("video"));assertTrue(reason.contains("layer")||reason.contains("articulated"));
        }
    }

    private static ProjectStore.Project legacyLayered(boolean articulated)throws Exception{
        ProjectStore.Project p=StudioVideoLayerContractTest.scene();p.asset("base").mime="image/png";
        for(ProjectStore.Clip clip:p.clips){
            clip.effects=new JSONObject().put("animatedScene",true)
                    .put("foregroundUri","file:///not-decoded-foreground.png").put("backgroundUri","file:///not-decoded-background.png")
                    .put("animationSpec",new JSONObject());
            if(articulated)clip.effects.put("headUri","file:///not-decoded-head.png")
                    .put("torsoUri","file:///not-decoded-torso.png").put("lowerUri","file:///not-decoded-lower.png");
        }
        return p;
    }

    private static ProjectStore.Project sceneWithVideo()throws Exception{
        ProjectStore.Project p=StudioVideoLayerContractTest.scene();
        p.studioLayers.add(StudioVideoLayerContractTest.parse(p,StudioVideoLayerContractTest.video("insert")));return p;
    }
    private static Composition composition(ProjectStore.Project p)throws Exception{
        return new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(p,"9:16","720p");
    }
    private static EditedMediaItemSequence sequenceFor(Composition c,String uri){
        for(EditedMediaItemSequence sequence:c.sequences)for(EditedMediaItem item:sequence.editedMediaItems)
            if(item.mediaItem.localConfiguration!=null&&uri.equals(item.mediaItem.localConfiguration.uri.toString()))return sequence;
        throw new AssertionError("Source is absent from the actual Media3 composition: "+uri);
    }
    private static long durationUs(EditedMediaItemSequence sequence){
        long result=0;for(EditedMediaItem item:sequence.editedMediaItems)result=Math.addExact(result,item.getPresentationDurationUs());return result;
    }
    private static boolean isGap(EditedMediaItem item){
        EditedMediaItem publicGap=new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_VIDEO))
                .addGap(1000).build().editedMediaItems.get(0);
        return item.mediaItem.localConfiguration==null&&publicGap.mediaItem.mediaId.equals(item.mediaItem.mediaId);
    }
}
