package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;
import static org.junit.Assert.*;

/** Synthetic decoder metadata only; no uploaded video or GPU analysis occurs in these tests. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class NativeMediaAnalyzerOrientationTest {
    private JSONObject analyse(String rotation,String duration,int decodedWidth,int decodedHeight) throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="synthetic_orientation";asset.name="Synthetic decoder evidence";asset.mime="video/mp4";asset.uri="content://synthetic/decoder";
        DataSource source=DataSource.toDataSource(context,Uri.parse(asset.uri));ShadowMediaMetadataRetriever.addMetadata(source,MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,"960");ShadowMediaMetadataRetriever.addMetadata(source,MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,"540");
        if(rotation!=null)ShadowMediaMetadataRetriever.addMetadata(source,MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,rotation);
        if(duration!=null)ShadowMediaMetadataRetriever.addMetadata(source,MediaMetadataRetriever.METADATA_KEY_DURATION,duration);
        long span=duration==null||"0".equals(duration)?1:1000;
        for(int i=0;i<6;i++){long time=(long)(i/5d*span);ShadowMediaMetadataRetriever.addFrame(source,time*1000,Bitmap.createBitmap(decodedWidth,decodedHeight,Bitmap.Config.ARGB_8888));}
        return new NativeMediaAnalyzer(context).analyse(asset,6,0,0);
    }
    @Test public void codedDisplayAndDecodedBitmapDimensionsRemainDistinct() throws Exception {
        JSONObject result=analyse("90","1000",270,480);assertEquals(960,result.getInt("width"));assertEquals(540,result.getInt("height"));assertEquals(960,result.getInt("codedWidth"));assertEquals(540,result.getInt("codedHeight"));
        assertTrue(result.getBoolean("rotationMetadataAvailable"));assertEquals(90,result.getInt("rotationDegrees"));assertEquals(540,result.getInt("displayWidth"));assertEquals(960,result.getInt("displayHeight"));assertTrue(result.getBoolean("durationMetadataAvailable"));
        assertEquals("closest_sync",result.getString("samplingMode"));assertFalse(result.getBoolean("exactFrameTimes"));assertEquals(6,result.getJSONArray("sampledFrames").length());JSONObject first=result.getJSONArray("sampledFrames").getJSONObject(0);assertEquals(270,first.getInt("width"));assertEquals(480,first.getInt("height"));assertEquals(0,first.getLong("requestedTimeMs"));assertEquals(1000,result.getJSONArray("sampledFrames").getJSONObject(5).getLong("requestedTimeMs"));assertEquals(600,result.getJSONObject("contactSheet").getInt("width"));assertEquals(660,result.getJSONObject("contactSheet").getInt("height"));
    }
    @Test public void missingRotationAndDurationDoNotBecomeVerifiedZeroMeasurements() throws Exception {
        JSONObject result=analyse(null,null,128,72);assertFalse(result.getBoolean("rotationMetadataAvailable"));assertTrue(result.isNull("rotationDegrees"));assertTrue(result.isNull("displayWidth"));assertTrue(result.isNull("displayHeight"));assertFalse(result.getBoolean("durationMetadataAvailable"));assertEquals(0,result.getLong("durationMs"));assertFalse(result.getBoolean("exactFrameTimes"));
    }
    @Test public void malformedRotationAndZeroDurationStayExplicitlyUnavailable() throws Exception {
        JSONObject result=analyse("invalid","0",128,72);assertFalse(result.getBoolean("rotationMetadataAvailable"));assertTrue(result.isNull("rotationDegrees"));assertFalse(result.getBoolean("durationMetadataAvailable"));
    }
}
