package com.rezoxnemesis.videostudio;

import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportExceptionTestBridge;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

/**
 * Actual NativeRenderEngine -> public Transformer.Builder/listener boundaries.
 * Build deliberately stops before GPU/codec work. Typed failures are injected
 * through the SDK listener registered by production; no natural failure or
 * successful native encoding is claimed by this unit test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=NativeExportOutputWaitTest.BuilderProbe.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeExportOutputWaitTest {
    @Before public void reset(){BuilderProbe.waits.clear();BuilderProbe.listener=null;BuilderProbe.builds=0;}

    @Test public void omittedOptionKeepsTheSdkSetterUncalledAndProjectUnchanged()throws Exception{
        ProjectStore.Project project=project();String before=SketchDocument.canonical(project.toJson());
        probe(project);
        assertEquals(1,BuilderProbe.builds);
        assertTrue("Legacy callers must retain the real SDK default rather than an inserted constant",BuilderProbe.waits.isEmpty());
        assertEquals(before,SketchDocument.canonical(project.toJson()));
    }

    @Test public void typedMuxerTimeoutExplainsTheFiniteWaitAndKeepsItsCode()throws Exception{
        Errors errors=probe(project());errors.messages.clear();
        ExportException timeout=ExportExceptionTestBridge.createForMuxer(
                new IllegalStateException("Abort: no output sample written. DebugTrace: private reference"),
                ExportException.ERROR_CODE_MUXING_TIMEOUT);
        BuilderProbe.listener.onError(null,null,timeout);
        assertEquals(1,errors.messages.size());String message=errors.messages.get(0);
        assertTrue("The existing renderer listener must retain typed timeout code7002",message.contains("7002"));
        assertTrue("Explain output-sample waiting, without claiming a decoder diagnosis",message.toLowerCase(java.util.Locale.ROOT).contains("output"));
        assertTrue("The reported limit must be the actual unchanged finite SDK limit",
                message.contains(Long.toString(Transformer.DEFAULT_MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS/1000)));
    }

    @Test public void nonTimeoutMuxerErrorKeepsItsExistingPublicMessage()throws Exception{
        Errors errors=probe(project());errors.messages.clear();
        ExportException failed=ExportExceptionTestBridge.createForMuxer(new java.io.IOException("owned output write failed"),
                ExportException.ERROR_CODE_MUXING_FAILED);
        BuilderProbe.listener.onError(null,null,failed);
        assertEquals(List.of(failed.getMessage()),errors.messages);
    }

    @Test public void timeoutDoesNotCopyTheRawDebugTraceOrPrivateSourceIntoTheUi()throws Exception{
        Errors errors=probe(project());errors.messages.clear();
        String secret="PRIVATE_DIALOGUE_SENTINEL";
        ExportException timeout=ExportExceptionTestBridge.createForMuxer(new IllegalStateException(
                "Abort: no output sample written. DebugTrace: content://private.example/reference?token=secret "+secret),
                ExportException.ERROR_CODE_MUXING_TIMEOUT);
        BuilderProbe.listener.onError(null,null,timeout);
        assertEquals(1,errors.messages.size());String message=errors.messages.get(0);
        assertFalse(message.contains(secret));assertFalse(message.contains("content://"));
        assertFalse(message.contains("DebugTrace"));assertFalse(message.contains("token="));
        assertTrue(message.length()<512);
    }

    private static Errors probe(ProjectStore.Project project)throws Exception{
        File output=File.createTempFile("native-wait-probe-",".mp4",RuntimeEnvironment.getApplication().getCacheDir());
        Errors errors=new Errors();
        try{
            assertNull(new NativeRenderEngine(RuntimeEnvironment.getApplication())
                    .export(project,output,"9:16","720p",errors));
            assertNotNull("Observe the real SDK listener production registered",BuilderProbe.listener);
            assertEquals(1,BuilderProbe.builds);return errors;
        }finally{if(output.exists())assertTrue(output.delete());}
    }

    private static ProjectStore.Project project(){
        ProjectStore.Project project=new ProjectStore.Project();project.id="wait-probe-project";
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-video";
        asset.uri="file:///not-decoded-output-wait.mp4";asset.mime="video/mp4";asset.durationMs=10_000;
        project.assets.add(asset);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="original-clip";
        clip.assetId=asset.id;clip.inMs=1000;clip.outMs=4000;project.clips.add(clip);return project;
    }

    private static final class Errors implements NativeRenderEngine.Listener {
        final List<String> messages=new ArrayList<>();
        @Override public void onProgress(int progress,String detail){}
        @Override public void onCompleted(File file,JSONObject result){fail("The unit probe cannot produce native encoded output");}
        @Override public void onError(String message){messages.add(message);}
    }

    @Implements(value=Transformer.Builder.class,isInAndroidSdk=false)
    public static final class BuilderProbe {
        @RealObject private Transformer.Builder builder;
        static final List<Long> waits=new ArrayList<>();static Transformer.Listener listener;static int builds;
        @Implementation public Transformer.Builder setMaxDelayBetweenMuxerSamplesMs(long delayMs){
            waits.add(delayMs);return Shadow.directlyOn(builder,Transformer.Builder.class,"setMaxDelayBetweenMuxerSamplesMs",
                    ClassParameter.from(long.class,delayMs));
        }
        @Implementation public Transformer.Builder addListener(Transformer.Listener value){
            listener=value;return Shadow.directlyOn(builder,Transformer.Builder.class,"addListener",
                    ClassParameter.from(Transformer.Listener.class,value));
        }
        @Implementation public Transformer build(){builds++;throw new BuildStoppedBeforeGpu();}
    }
    private static final class BuildStoppedBeforeGpu extends RuntimeException {}
}
