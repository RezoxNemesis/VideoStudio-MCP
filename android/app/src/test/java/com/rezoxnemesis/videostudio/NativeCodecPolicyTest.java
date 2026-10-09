package com.rezoxnemesis.videostudio;

import android.media.MediaCodecInfo;
import androidx.media3.transformer.VideoEncoderSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class NativeCodecPolicyTest {
    @Test public void conservativeSettingsKeepRequestedBitrateAndDisableBFrames(){
        VideoEncoderSettings settings=NativeCodecPolicy.settings(RenderRetryController.Route.CONSERVATIVE,12_000_000,30);
        assertEquals(12_000_000,settings.bitrate);assertEquals(0,settings.maxBFrames);assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,settings.profile);
    }
    @Test public void softwareSettingsDoNotReduceFinalResolution(){
        assertArrayEquals(new int[]{1920,1080},NativeCodecPolicy.dimensions("16:9","1080p"));
        assertArrayEquals(new int[]{1080,1920},NativeCodecPolicy.dimensions("9:16","1080p"));
        assertArrayEquals(new int[]{720,1280},NativeCodecPolicy.dimensions("9:16","720p"));
        assertEquals(8_000_000,NativeCodecPolicy.settings(RenderRetryController.Route.SOFTWARE_CODECS,8_000_000,30).bitrate);
    }
    @Test public void softwareRouteRequiresOnlyVideoSoftwareDecoder(){assertFalse(NativeCodecPolicy.softwareDecoder(RenderRetryController.Route.CONSERVATIVE,"video/avc"));assertTrue(NativeCodecPolicy.softwareDecoder(RenderRetryController.Route.SOFTWARE_DECODER,"video/avc"));assertFalse(NativeCodecPolicy.softwareDecoder(RenderRetryController.Route.SOFTWARE_DECODER,"audio/mp4a-latm"));}
    @Test public void conservativeEncoderUsesBoundedOperatingRate(){VideoEncoderSettings settings=NativeCodecPolicy.settings(RenderRetryController.Route.CONSERVATIVE,8_000_000,30);assertEquals(30,settings.operatingRate);assertEquals(1,settings.priority);}
    @Test public void squareAndPortraitLevelsCoverActualMacroblocks(){assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel5,NativeCodecPolicy.settings(RenderRetryController.Route.CONSERVATIVE,8_000_000,30,1920,1920).level);assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel5,NativeCodecPolicy.settings(RenderRetryController.Route.SOFTWARE_CODECS,8_000_000,30,1536,1920).level);assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel51,NativeCodecPolicy.settings(RenderRetryController.Route.CONSERVATIVE,50_000_000,60,1920,1920).level);}
    @Test public void exactBitrateSelectionRejectsTooSmallEncodersBeforeFallback(){
        java.util.List<String> codecs=java.util.List.of("low","normal","high");
        java.util.Map<String,android.util.Range<Integer>> ranges=java.util.Map.of("low",new android.util.Range<>(500_000,2_000_000),"normal",new android.util.Range<>(500_000,20_000_000),"high",new android.util.Range<>(1_000_000,50_000_000));
        assertEquals(java.util.List.of("normal","high"),NativeCodecPolicy.selectEncoders(codecs,ranges::get,name->0,8_000_000));
        assertTrue(NativeCodecPolicy.selectEncoders(codecs,ranges::get,name->0,60_000_000).isEmpty());assertEquals(java.util.List.of("high"),NativeCodecPolicy.selectEncoders(codecs,ranges::get,name->0,50_000_000));
    }
    @Test public void configuredCodecCannotSilentlyLowerTargetBitrateOrFrameRate(){
        androidx.media3.common.Format requested=new androidx.media3.common.Format.Builder().setWidth(1280).setHeight(720).setFrameRate(30).build();
        androidx.media3.common.Format actual=requested.buildUpon().setAverageBitrate(8_000_000).build();assertTrue(NativeCodecPolicy.matchesConfiguredFormat(requested,actual,8_000_000));
        assertFalse(NativeCodecPolicy.matchesConfiguredFormat(requested,actual.buildUpon().setAverageBitrate(2_000_000).build(),8_000_000));
        assertFalse(NativeCodecPolicy.matchesConfiguredFormat(requested,actual.buildUpon().setFrameRate(15).build(),8_000_000));
        assertFalse(NativeCodecPolicy.matchesConfiguredFormat(requested,actual.buildUpon().setWidth(720).setHeight(1280).build(),8_000_000));
    }
    @Test public void exportWorkspaceAliasCannotDeleteOriginalMedia()throws Exception{
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();java.io.File source=new java.io.File(context.getFilesDir(),"codec-original.png");java.nio.file.Files.write(source.toPath(),new byte[]{1,2,3});
        ProjectStore.Project p=new ProjectStore.Project();p.ensureTimelineDefaults();ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source";asset.uri=android.net.Uri.fromFile(source).toString();asset.mime="image/png";p.assets.add(asset);ProjectStore.Clip c=new ProjectStore.Clip();c.assetId=asset.id;c.trackId=p.tracks.get(0).id;c.outMs=1000;p.clips.add(c);
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        new NativeRenderEngine(context).export(p,new java.io.File(source.getParentFile(),"./"+source.getName()),"16:9","720p",new NativeRenderEngine.Listener(){public void onProgress(int percent,String detail){}public void onCompleted(java.io.File file,org.json.JSONObject proof){fail("Original used as output");}public void onError(String detail){error.set(detail);}});
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertNotNull(error.get());assertTrue(error.get(),error.get().contains("original source"));assertArrayEquals(new byte[]{1,2,3},java.nio.file.Files.readAllBytes(source.toPath()));
    }
}
