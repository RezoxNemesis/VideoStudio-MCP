package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import static org.junit.Assert.*;

/**
 * Public native analyse() behavior with a deterministic decoder boundary.
 * Synthetic fixture bitmaps exercise native contact-sheet Canvas/JPEG only;
 * these tests do not decode uploaded footage, execute a GPU or certify editing quality.
 * The actual APK six-of-eight failure is preserved separately by root.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=NativeMediaAnalyzerCoverageTest.Decoder.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeMediaAnalyzerCoverageTest {
    private Context context;
    private ProjectStore.Asset asset;
    private File source;
    private byte[] originalBytes;
    private String originalAsset;

    @Before public void setup()throws Exception {
        Thread.interrupted();Decoder.resetFixture();context=RuntimeEnvironment.getApplication();
        source=new File(context.getFilesDir(),"sampler_boundary_"+UUID.randomUUID()+".mp4");
        originalBytes=new byte[]{1,3,5,7,11,13};Files.write(source.toPath(),originalBytes);
        asset=new ProjectStore.Asset();asset.id="sampler_"+UUID.randomUUID();asset.name="Synthetic decoder boundary, not uploaded footage";
        asset.mime="video/mp4";asset.uri=Uri.fromFile(source).toString();asset.durationMs=7000;asset.sizeBytes=originalBytes.length;asset.seekable=true;
        originalAsset=asset.toJson().toString();
    }

    @After public void cleanup()throws Exception {
        Thread.interrupted();
        for(Bitmap frame:Decoder.produced)if(!frame.isRecycled())frame.recycle();
        if(source!=null)Files.deleteIfExists(source.toPath());
    }

    @Test public void twoInitialNullsRetryTheOpeningTimeAndRecoverTheWholeRequestedSchedule()throws Exception {
        Decoder.mode=Mode.TWO_INITIAL_NULLS;
        JSONObject result=analyse(8,0,7000);
        assertEquals("The opening samples must not silently disappear",8,result.getJSONObject("contactSheet").getInt("frameCount"));
        coverage(result,8,8,0,true);
        assertEquals(List.of(0L,0L,0L),Decoder.timesUs.subList(0,3));
        assertEquals(10,Decoder.timesUs.size());
        JSONArray sampled=result.getJSONArray("sampledFrames");
        for(int i=0;i<8;i++)assertEquals(i*1000L,sampled.getJSONObject(i).getLong("requestedTimeMs"));
        assertAllClosestSync();assertEveryReturnedBitmapRecycled();assertOwnInputUnchanged();assertAllRetrieversReleased();
    }

    @Test public void permanentNullIsAVisiblePartialResultWithBoundedSameTimeRetries()throws Exception {
        Decoder.mode=Mode.ONE_PERMANENT_NULL;Decoder.problemTimeUs=1000000L;
        JSONObject result=analyse(8,0,7000);coverage(result,8,7,1,false);
        JSONObject failure=result.getJSONObject("coverage").getJSONArray("failures").getJSONObject(0);
        assertEquals(1000,failure.getLong("requestedTimeMs"));assertEquals(3,failure.getInt("attempts"));
        assertEquals("decoder_returned_no_frame",failure.getString("reason"));
        assertEquals(3,(int)Decoder.callsByTime.get(1000000L));assertEquals(10,Decoder.timesUs.size());
        assertEquals(7,result.getJSONObject("contactSheet").getInt("frameCount"));
        JSONArray sampled=result.getJSONArray("sampledFrames");
        assertEquals(0,sampled.getJSONObject(0).getLong("requestedTimeMs"));assertEquals(2000,sampled.getJSONObject(1).getLong("requestedTimeMs"));
        for(int i=0;i<sampled.length();i++)assertNotEquals("A prior frame cannot be relabelled as the missing sample",1000,sampled.getJSONObject(i).getLong("requestedTimeMs"));
        assertTrue("ok retains its request-level meaning; coverage carries incompleteness",result.getBoolean("ok"));
        assertFalse(result.getBoolean("exactFrameTimes"));assertEquals("closest_sync",result.getString("samplingMode"));
        assertAllClosestSync();assertEveryReturnedBitmapRecycled();assertOwnInputUnchanged();assertAllRetrieversReleased();
    }

    @Test public void recoverableDecoderExceptionsAreReportedAndDoNotDiscardOtherSamples()throws Exception {
        Decoder.mode=Mode.ONE_CODEC_EXCEPTION;Decoder.problemTimeUs=2000000L;
        JSONObject result=analyse(8,0,7000);coverage(result,8,7,1,false);
        JSONObject failure=result.getJSONObject("coverage").getJSONArray("failures").getJSONObject(0);
        assertEquals(2000,failure.getLong("requestedTimeMs"));assertEquals(3,failure.getInt("attempts"));
        assertEquals("decoder_exception",failure.getString("reason"));
        assertEquals(3,(int)Decoder.callsByTime.get(2000000L));assertEquals(10,Decoder.timesUs.size());
        assertEquals(7,result.getJSONArray("sampledFrames").length());assertAllClosestSync();
        assertEveryReturnedBitmapRecycled();assertOwnInputUnchanged();assertAllRetrieversReleased();
    }

    @Test public void alreadyInterruptedRequestDoesNotOpenOrDecodeAndPreservesTheFlag()throws Exception {
        Thread.currentThread().interrupt();
        assertThrows(InterruptedIOException.class,()->analyse(8,0,7000));
        assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,Decoder.opened);assertTrue(Decoder.timesUs.isEmpty());
        Thread.interrupted();assertOwnInputUnchanged();
    }

    @Test public void interruptionAfterReturningABitmapRecyclesCurrentAndRetainedFrames()throws Exception {
        Decoder.mode=Mode.INTERRUPT_AFTER_RETURN;Decoder.problemTimeUs=1000000L;
        assertThrows(InterruptedIOException.class,()->analyse(8,0,7000));
        assertTrue(Thread.currentThread().isInterrupted());assertEquals(2,Decoder.produced.size());assertEquals(2,Decoder.timesUs.size());
        assertEveryReturnedBitmapRecycled();assertAllRetrieversReleased();
        Thread.interrupted();assertOwnInputUnchanged();
    }

    @Test public void permissionFailureRecyclesEarlierFramesWithoutRetryingDeniedAccess()throws Exception {
        Decoder.mode=Mode.SECURITY_AFTER_FIRST;Decoder.problemTimeUs=1000000L;
        SecurityException failure=assertThrows(SecurityException.class,()->analyse(8,0,7000));
        assertTrue(failure.getMessage().contains("fixture denied"));assertEquals(2,Decoder.timesUs.size());
        assertEquals(1,Decoder.produced.size());assertEveryReturnedBitmapRecycled();assertAllRetrieversReleased();assertOwnInputUnchanged();
    }

    @Test public void allNullFramesStopAtThreeAttemptsPerTimeAndStillFailActionably()throws Exception {
        Decoder.mode=Mode.ALL_NULL;
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->analyse(8,0,7000));
        assertTrue(failure.getMessage().toLowerCase().contains("decode"));
        assertEquals("The finite request must have a finite retry budget",24,Decoder.timesUs.size());
        for(int i=0;i<8;i++)assertEquals(3,(int)Decoder.callsByTime.get(i*1000000L));
        assertTrue(Decoder.produced.isEmpty());assertAllClosestSync();assertAllRetrieversReleased();assertOwnInputUnchanged();
    }

    @Test public void healthyDecoderKeepsTheLegacyMetadataTimesAndNoRetryPath()throws Exception {
        JSONObject result=analyse(8,0,7000);
        assertTrue(result.getBoolean("ok"));assertEquals(asset.id,result.getString("assetId"));assertEquals(asset.name,result.getString("name"));
        assertEquals(7000,result.getLong("durationMs"));assertTrue(result.getBoolean("durationMetadataAvailable"));
        assertEquals(64,result.getInt("codedWidth"));assertEquals(36,result.getInt("codedHeight"));
        assertTrue(result.getBoolean("rotationMetadataAvailable"));assertEquals(0,result.getInt("rotationDegrees"));
        assertEquals(64,result.getInt("displayWidth"));assertEquals(36,result.getInt("displayHeight"));
        assertEquals("closest_sync",result.getString("samplingMode"));assertFalse(result.getBoolean("exactFrameTimes"));
        assertEquals(8,result.getJSONArray("sampledFrames").length());assertEquals(8,result.getJSONObject("contactSheet").getInt("frameCount"));
        assertEquals(600,result.getJSONObject("contactSheet").getInt("width"));assertEquals(880,result.getJSONObject("contactSheet").getInt("height"));
        assertEquals("image/jpeg",result.getJSONObject("contactSheet").getString("mimeType"));assertFalse(result.getJSONObject("contactSheet").getString("base64").isEmpty());
        assertEquals(8,Decoder.timesUs.size());for(int i=0;i<8;i++)assertEquals(i*1000000L,(long)Decoder.timesUs.get(i));
        assertAllClosestSync();assertEveryReturnedBitmapRecycled();assertAllRetrieversReleased();assertOwnInputUnchanged();
    }

    @Test public void coverageReflectsTheExistingSixFrameMinimumAndRequestedWindow()throws Exception {
        JSONObject result=analyse(1,1000,6000);coverage(result,6,6,0,true);
        assertEquals(6,Decoder.timesUs.size());for(int i=0;i<6;i++)assertEquals((i+1)*1000000L,(long)Decoder.timesUs.get(i));
        assertEveryReturnedBitmapRecycled();assertAllRetrieversReleased();assertOwnInputUnchanged();
    }

    @Test public void invalidAssetsFailBeforeCreatingDecoderWork()throws Exception {
        asset.mime="image/png";
        assertThrows(IllegalArgumentException.class,()->analyse(8,0,7000));
        assertThrows(IllegalArgumentException.class,()->new NativeMediaAnalyzer(context).analyse(null,8,0,7000));
        assertEquals(0,Decoder.opened);assertEquals(0,Decoder.released);assertTrue(Decoder.timesUs.isEmpty());
    }

    private JSONObject analyse(int count,long from,long to)throws Exception{return new NativeMediaAnalyzer(context).analyse(asset,count,from,to);}
    private void coverage(JSONObject result,int requested,int decoded,int failed,boolean complete)throws Exception {
        JSONObject coverage=result.getJSONObject("coverage");assertEquals(requested,coverage.getInt("requestedFrames"));assertEquals(decoded,coverage.getInt("decodedFrames"));
        assertEquals(failed,coverage.getInt("failedFrames"));assertEquals(complete,coverage.getBoolean("complete"));assertEquals(failed,coverage.getJSONArray("failures").length());
        assertEquals(requested,decoded+failed);
    }
    private void assertOwnInputUnchanged()throws Exception {assertArrayEquals(originalBytes,Files.readAllBytes(source.toPath()));assertEquals(originalAsset,asset.toJson().toString());}
    private static void assertAllClosestSync(){for(int option:Decoder.options)assertEquals("Retries cannot silently change sampling semantics",MediaMetadataRetriever.OPTION_CLOSEST_SYNC,option);}
    private static void assertEveryReturnedBitmapRecycled(){for(Bitmap frame:Decoder.produced)assertTrue("Native analyser owns/recycles every returned decoder bitmap even on failure",frame.isRecycled());}
    private static void assertAllRetrieversReleased(){assertTrue(Decoder.opened>0);assertEquals(Decoder.opened,Decoder.released);}

    enum Mode { HEALTHY,TWO_INITIAL_NULLS,ONE_PERMANENT_NULL,ONE_CODEC_EXCEPTION,INTERRUPT_AFTER_RETURN,SECURITY_AFTER_FIRST,ALL_NULL }

    /** Unit decoder protocol only: no real input codec and no manufactured app output claim. */
    @Implements(MediaMetadataRetriever.class)
    public static class Decoder extends ShadowMediaMetadataRetriever {
        static Mode mode;static long problemTimeUs;static int opened,released;
        static final List<Long> timesUs=new ArrayList<>();static final List<Integer> options=new ArrayList<>();
        static final List<Bitmap> produced=new ArrayList<>();static final Map<Long,Integer> callsByTime=new HashMap<>();
        private boolean closed,configured;
        static void resetFixture(){mode=Mode.HEALTHY;problemTimeUs=-1;opened=0;released=0;timesUs.clear();options.clear();produced.clear();callsByTime.clear();}
        @Implementation @Override protected void setDataSource(Context context,Uri uri){if(!configured){opened++;configured=true;}}
        @Implementation @Override protected String extractMetadata(int key){
            if(key==MediaMetadataRetriever.METADATA_KEY_DURATION)return "7000";
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)return "64";
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)return "36";
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)return "0";
            return null;
        }
        @Implementation @Override protected Bitmap getFrameAtTime(long timeUs,int option){
            timesUs.add(timeUs);options.add(option);callsByTime.put(timeUs,callsByTime.getOrDefault(timeUs,0)+1);
            if(mode==Mode.ALL_NULL||(mode==Mode.TWO_INITIAL_NULLS&&timesUs.size()<=2)||(mode==Mode.ONE_PERMANENT_NULL&&timeUs==problemTimeUs))return null;
            if(mode==Mode.ONE_CODEC_EXCEPTION&&timeUs==problemTimeUs)throw new IllegalStateException("recoverable codec fixture unavailable");
            if(mode==Mode.SECURITY_AFTER_FIRST&&timeUs==problemTimeUs)throw new SecurityException("fixture denied source access");
            Bitmap bitmap=Bitmap.createBitmap(64,36,Bitmap.Config.ARGB_8888);int sample=(int)(timeUs/1000000L);
            bitmap.eraseColor(0xff000000|((sample*31&255)<<16)|((sample*67&255)<<8)|(sample*103&255));produced.add(bitmap);
            if(mode==Mode.INTERRUPT_AFTER_RETURN&&timeUs==problemTimeUs)Thread.currentThread().interrupt();
            return bitmap;
        }
        @Implementation protected void release(){if(closed)throw new AssertionError("Retriever released twice");closed=true;released++;}
    }
}
