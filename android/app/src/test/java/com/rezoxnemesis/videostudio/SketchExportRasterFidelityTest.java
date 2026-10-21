package com.rezoxnemesis.videostudio;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EncoderSelector;
import androidx.media3.transformer.EncoderUtil;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;
import static org.junit.Assert.*;

/** Real product selection and actual Media3 configuration policy; no codec execution claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SketchExportRasterFidelityTest {
    @Before public void resetCodecs() {
        ShadowMediaCodecList.reset();
        EncoderUtil.clearCachedEncoders();
    }

    @Test public void fullRasterSoftwareWinsOverReducedHardwareAtTheActualSelector()throws Exception {
        MediaCodecInfo software=codec("software.full",false,1280,720);
        MediaCodecInfo hardware=codec("hardware.small",true,1024,576);
        assertTrue(software.getCapabilitiesForType(MimeTypes.VIDEO_H264).getVideoCapabilities().areSizeAndRateSupported(1280,720,24));
        assertFalse(hardware.getCapabilitiesForType(MimeTypes.VIDEO_H264).getVideoCapabilities().areSizeAndRateSupported(1280,720,24));
        assertTrue(hardware.getCapabilitiesForType(MimeTypes.VIDEO_H264).getVideoCapabilities().areSizeAndRateSupported(1024,576,24));
        Object selected=select(1280,720,"720p",24);
        assertEquals("Never exchange the requested raster for hardware acceleration",1280,((SketchFrameStreamEncoder.Size)field(selected,"size")).width);
        assertEquals(720,((SketchFrameStreamEncoder.Size)field(selected,"size")).height);
        assertEquals("software.full",((MediaCodecInfo)field(selected,"codec")).getName());
    }

    @Test public void exactRasterHardwareWinsOnlyAmongExactTies()throws Exception {
        codec("software.full",false,1280,720);codec("hardware.full",true,1280,720);
        assertEquals("hardware.full",((MediaCodecInfo)field(select(1280,720,"720p",24),"codec")).getName());
    }

    @Test public void smallerOnlyCodecRejectsWithTheRequestedRasterAndFps()throws Exception {
        codec("hardware.small",true,1024,576);
        IllegalStateException error=assertThrows(IllegalStateException.class,()->select(1280,720,"720p",24));
        assertTrue(error.getMessage(),error.getMessage().contains("1280x720"));
        assertTrue(error.getMessage(),error.getMessage().contains("24"));
    }

    @Test public void exactSizeAtThirtyFpsCannotBecomeSmallerHardwareAtSixtyFps()throws Exception {
        MediaCodecInfo software=codec("software.thirty",false,1280,720);
        org.robolectric.util.ReflectionHelpers.setField(software.getCapabilitiesForType(MimeTypes.VIDEO_H264).getVideoCapabilities(),
                "mFrameRateRange",new android.util.Range<Integer>(1,30));
        codec("hardware.small.sixty",true,1024,576);
        assertTrue(software.getCapabilitiesForType(MimeTypes.VIDEO_H264).getVideoCapabilities().areSizeAndRateSupported(1280,720,30));
        assertFalse(software.getCapabilitiesForType(MimeTypes.VIDEO_H264).getVideoCapabilities().areSizeAndRateSupported(1280,720,60));
        IllegalStateException error=assertThrows(IllegalStateException.class,()->select(1280,720,"720p",60));
        assertTrue(error.getMessage(),error.getMessage().contains("1280x720"));
        assertTrue(error.getMessage(),error.getMessage().contains("60"));
    }

    @Test public void finalEditorDoesNotNegotiateASmallerRasterWhenOnlyThatCodecExists()throws Exception {
        codec("hardware.small",true,1024,576);
        DefaultEncoderFactory factory=actualEditorFactory();
        assertNull("The actual Editor factory must reject instead of negotiating smaller frames",configuration(factory,1280,720));
    }

    @Test public void finalEditorCanUseFullRasterSoftwareInsteadOfReducedHardware()throws Exception {
        codec("hardware.small",true,1024,576);codec("software.full",false,1280,720);
        Object configuration=configuration(actualEditorFactory(),1280,720);
        assertNotNull(configuration);
        Format format=(Format)field(configuration,"supportedFormat");
        assertEquals(1280,format.width);assertEquals(720,format.height);assertEquals(24,format.frameRate,0);
        assertEquals("software.full",((MediaCodecInfo)field(configuration,"encoder")).getName());
    }

    @Test public void editorPublicationRejectsReducedOrDistortedLandscapeRaster() {
        assertTrue(NativeRenderEngine.outputGeometryMatches("16:9",1280,720));
        assertFalse("Correct orientation alone must not certify 720p",NativeRenderEngine.outputGeometryMatches("16:9",1024,576));
        assertFalse("A landscape with the wrong ratio must not be published",NativeRenderEngine.outputGeometryMatches("16:9",1920,800));
    }

    @Test public void actualEditorCompletionRejectsReduced720pAnd1080pOutputs()throws Exception {
        for(String quality:new String[]{"720p","1080p"}){
            Capture capture=new Capture();NativeRenderEngine.Handle handle=editor(capture,"16:9",quality);
            int width=quality.equals("720p")?1024:1280,height=quality.equals("720p")?576:720;
            complete(handle,width,height,0);
            try{
                assertNull("A reduced output must not reach publication",capture.completed);
                assertNotNull(capture.error);
                assertTrue(capture.error,capture.error.contains(quality.equals("720p")?"1280x720":"1920x1080"));
            }finally{cancel(handle);}
        }
    }

    @Test public void actualEditorCompletionKeepsExistingPresetRastersAndDisplayRotation()throws Exception {
        String[] aspects={"16:9","9:16","1:1","4:5"};
        int[][] raster720={{1280,720},{720,1280},{1280,1280},{1024,1280}};
        int[][] raster1080={{1920,1080},{1080,1920},{1920,1920},{1536,1920}};
        for(String quality:new String[]{"720p","1080p"})for(int i=0;i<aspects.length;i++){
            Capture capture=new Capture();NativeRenderEngine.Handle handle=editor(capture,aspects[i],quality);
            int[] raster=quality.equals("720p")?raster720[i]:raster1080[i];boolean rotated=aspects[i].equals("9:16");
            complete(handle,rotated?raster[1]:raster[0],rotated?raster[0]:raster[1],rotated?90:0);
            try{
                assertNull(capture.error);assertNotNull(aspects[i]+"/"+quality,capture.completed);
                assertEquals(raster[0],capture.completed.getInt("encodedWidth"));assertEquals(raster[1],capture.completed.getInt("encodedHeight"));
                assertEquals(124000,capture.completed.getLong("durationMs"));
                assertFalse("Injected metadata is not decoded frame proof",capture.completed.getBoolean("decodedFrameCountVerified"));
            }finally{cancel(handle);}
        }
    }

    @Test public void exactSourceRasterDoesNotUpscaleWithTheQualityPreset()throws Exception {
        Capture capture=new Capture();NativeRenderEngine.Handle handle=editor(capture,"source:768x1024","1080p");
        complete(handle,768,1024,0);
        try{assertNull(capture.error);assertNotNull(capture.completed);assertTrue(capture.completed.getBoolean("sourceRasterVerified"));}
        finally{cancel(handle);}
    }

    @Test public void videoFormatFailureNamesRasterWithoutMisdiagnosingAnAudioFailure()throws Exception {
        for(boolean video:new boolean[]{true,false}){
            Capture capture=new Capture();NativeRenderEngine.Handle handle=editor(capture,"16:9","720p");
            ExportException error=ExportException.createForCodec(new IllegalArgumentException("Unsupported test format"),
                    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,new ExportException.CodecInfo(video?"video/avc":"audio/mp4a-latm",video,false,null));
            listeners(handle).sendEvent(0,listener->listener.onError(null,new ExportResult.Builder().build(),error));
            try{
                assertNotNull(capture.error);assertNull(capture.completed);
                if(video){assertTrue(capture.error,capture.error.contains("1280x720"));assertTrue(capture.error,capture.error.contains("NATIVE_EXPORT_RASTER_UNAVAILABLE"));}
                else assertFalse("AAC failure must not be called a raster failure",capture.error.contains("NATIVE_EXPORT_RASTER_UNAVAILABLE"));
            }finally{cancel(handle);}
        }
    }

    private static MediaCodecInfo codec(String name,boolean hardware,int width,int height) {
        MediaFormat format=MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264,width,height);
        format.setString("frame-rate-range","1-60");
        MediaCodecInfo.CodecProfileLevel profile=new MediaCodecInfo.CodecProfileLevel();
        profile.profile=MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
        profile.level=MediaCodecInfo.CodecProfileLevel.AVCLevel42;
        MediaCodecInfo.CodecCapabilities capabilities=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(format).setIsEncoder(true).setProfileLevels(new MediaCodecInfo.CodecProfileLevel[]{profile})
                .setColorFormats(new int[]{MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface}).build();
        MediaCodecInfo info=MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(true)
                .setIsHardwareAccelerated(hardware).setIsSoftwareOnly(!hardware).setCapabilities(capabilities).build();
        ShadowMediaCodecList.addCodec(info);return info;
    }

    private static Object select(int width,int height,String quality,int fps)throws Exception {
        Method method=SketchFrameStreamEncoder.class.getDeclaredMethod("selectCodec",int.class,int.class,String.class,int.class);
        method.setAccessible(true);
        try{return method.invoke(null,width,height,quality,fps);}
        catch(InvocationTargetException error){throw (Exception)error.getCause();}
    }

    private static Object field(Object target,String name)throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{
            Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(target);
        }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }

    private static DefaultEncoderFactory actualEditorFactory()throws Exception {
        NativeRenderEngine.Handle handle=editor(new Capture(),"16:9","720p");
        Transformer transformer=(Transformer)field(handle,"transformer");
        // The paused main looper never starts the queued export. Inspect the factory actually installed by export().
        DefaultEncoderFactory factory=(DefaultEncoderFactory)field(transformer,"encoderFactory");
        cancel(handle);return factory;
    }

    private static final class Capture implements NativeRenderEngine.Listener {
        String error;org.json.JSONObject completed;
        public void onProgress(int progress,String detail){}
        public void onCompleted(File file,org.json.JSONObject result){completed=result;}
        public void onError(String message){error=message;}
    }

    private static NativeRenderEngine.Handle editor(Capture capture,String aspect,String quality)throws Exception {
        ProjectStore.Project project=new ProjectStore.Project();project.id="raster_policy";
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="picture";asset.mime="video/mp4";
        asset.uri="file:///not-opened-policy-test.mp4";asset.durationMs=124000;project.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="film";clip.assetId=asset.id;clip.outMs=124000;project.clips.add(clip);
        NativeRenderEngine.Handle handle=new NativeRenderEngine(RuntimeEnvironment.getApplication()).export(project,
                new File(RuntimeEnvironment.getApplication().getCacheDir(),"unstarted-policy.mp4"),aspect,quality,capture);
        assertNull(capture.error);assertNotNull(handle);return handle;
    }

    private static void cancel(NativeRenderEngine.Handle handle){
        handle.cancel();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    @SuppressWarnings("unchecked") private static androidx.media3.common.util.ListenerSet<Transformer.Listener> listeners(NativeRenderEngine.Handle handle)throws Exception {
        return (androidx.media3.common.util.ListenerSet<Transformer.Listener>)field(field(handle,"transformer"),"listeners");
    }

    private static void complete(NativeRenderEngine.Handle handle,int width,int height,int rotation)throws Exception {
        String path=new File(RuntimeEnvironment.getApplication().getCacheDir(),"unstarted-policy.mp4").getAbsolutePath();
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(path,android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,String.valueOf(width));
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(path,android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,String.valueOf(height));
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(path,android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,String.valueOf(rotation));
        listeners(handle).sendEvent(0,listener->listener.onCompleted(null,new ExportResult.Builder().build()));
    }

    private static Object configuration(DefaultEncoderFactory factory,int width,int height)throws Exception {
        Format format=new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(width).setHeight(height).setFrameRate(24).build();
        Method resolve=DefaultEncoderFactory.class.getDeclaredMethod("resolveVideoEncoderConfiguration",Format.class,VideoEncoderSettings.class,EncoderSelector.class,boolean.class,boolean.class,boolean.class,boolean.class);
        resolve.setAccessible(true);
        try{return resolve.invoke(null,format,field(factory,"requestedVideoEncoderSettings"),field(factory,"videoEncoderSelector"),field(factory,"enableFallback"),field(factory,"enableFormatFallback"),field(factory,"enableCodecDbLite"),field(factory,"enableCodecDbLiteBitrate"));}
        catch(InvocationTargetException error){throw (Exception)error.getCause();}
    }
}
