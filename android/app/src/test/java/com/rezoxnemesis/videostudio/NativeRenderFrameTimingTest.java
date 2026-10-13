package com.rezoxnemesis.videostudio;

import androidx.media3.transformer.EditedMediaItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Tests the real Media3 item boundary; final decoded MP4 timing needs a device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeRenderFrameTimingTest {
    @Test public void sixtyFpsRequestReachesTheNativeEncoderItem() throws Exception {
        EditedMediaItem item = item(60,17);
        assertEquals(60, item.frameRate);
        assertEquals(17, item.mediaItem.localConfiguration.imageDurationMs);
    }
    @Test public void cumulativeThirtyFpsHoldCanBeThirtyThreeMilliseconds() throws Exception {
        EditedMediaItem item = item(30,33);
        assertEquals(30, item.frameRate);
        assertEquals("A 34ms minimum accumulates a false slow timeline",33,
                item.mediaItem.localConfiguration.imageDurationMs);
    }
    @Test public void existingTwelveFpsPoseSequenceKeepsItsRequestedRate()throws Exception{
        EditedMediaItem item=item(12,83);
        assertEquals(12,item.frameRate);assertEquals(83,item.mediaItem.localConfiguration.imageDurationMs);
    }
    @Test public void rationalCinematicDurationsReachTheActualMedia3SequenceClock()throws Exception{
        assertEquals(16_666,item(60,17,true,0).durationUs);
        assertEquals(16_667,item(60,16,true,1).durationUs);
    }
    @Test public void humanExtendedHoldDoesNotInheritSingleFrameDuration()throws Exception{
        assertEquals(100_000,item(60,100,true,0).durationUs);
    }
    @Test public void newTaggedLegacyRigFramesUseTheSameRationalEncoderClock()throws Exception{
        assertEquals(33_333,item(30,34,true,1,"generated_skinned_2d").durationUs);
    }
    @Test public void newFullSceneCinematicFramesReachTheRationalSixtyFpsEncoderClock()throws Exception{
        assertEquals(16_666,item(60,17,true,0,"generated_cinematic_3d").durationUs);
        assertEquals(16_667,item(60,16,true,1,"generated_cinematic_3d").durationUs);
    }
    private EditedMediaItem item(int fps,long duration) throws Exception {
        return item(fps,duration,false,0);
    }
    private EditedMediaItem item(int fps,long duration,boolean cinematic,int index) throws Exception {
        return item(fps,duration,cinematic,index,"multigenre_storyboard_motion");
    }
    private EditedMediaItem item(int fps,long duration,boolean cinematic,int index,String role) throws Exception {
        ProjectStore.Asset asset=new ProjectStore.Asset(); asset.id="timing-source";
        asset.uri="file:///synthetic-timing.png"; asset.mime="image/png";
        ProjectStore.Clip clip=new ProjectStore.Clip(); clip.assetId=asset.id;
        clip.inMs=0;clip.outMs=duration;clip.effects.put("poseInbetween",true);clip.effects.put("poseFps",fps);
        if(cinematic){asset.generated=true;asset.role=role;
            asset.generationMetadata.put("frameClock","rational-authored-v1").put("frameIndex",index).put("fps",fps);
            clip.effects.put("frameClock","rational-authored-v1").put("frameIndex",index);}
        Method build=NativeRenderEngine.class.getDeclaredMethod("buildItem",ProjectStore.Asset.class,
                ProjectStore.Clip.class,String.class,String.class,boolean.class);
        build.setAccessible(true);
        return (EditedMediaItem)build.invoke(new NativeRenderEngine(RuntimeEnvironment.getApplication()),
                asset,clip,"9:16","720p",true);
    }
}
