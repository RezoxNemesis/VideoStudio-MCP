package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Audio timing cannot trick the app into scanning Gallery or fetching arbitrary URLs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StudioAudioWaveformsOwnedAudioTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void remoteAudioUrlCannotBeDecodedEvenWhenStoredAsImportedMetadata()throws Exception{
        ProjectStore store=new ProjectStore(context);
        ProjectStore.Project project=store.create("Owner audio "+UUID.randomUUID());
        ProjectStore.Asset chosen=store.registerImportedAsset(project.id,
                Uri.parse("https://tracking.invalid/voice.mp3"),"Bad audio", "audio/mpeg",1200,100);
        ProjectStore.Project refreshed=store.get(project.id);
        assertNotNull(refreshed.asset(chosen.id));
        rejectAudio(refreshed,chosen,0,900,24,"local");
    }
    @Test public void forgedUriAndUnownedAssetFailBeforeMediaAccess()throws Exception{
        ProjectStore store=new ProjectStore(context);
        ProjectStore.Project project=store.create("Voice identity "+UUID.randomUUID());
        ProjectStore.Asset real=store.registerImportedAsset(project.id,Uri.parse("file:///owned/voice.wav"),
                "Explicit owned WAV", "audio/wav",2000,32000);
        ProjectStore.Asset forged=new ProjectStore.Asset();
        forged.id=real.id;forged.mime=real.mime;forged.uri="file:///sdcard/private-audio.wav";
        rejectAudio(store.get(project.id),forged,0,1000,24,"owned");
        forged.id=UUID.randomUUID().toString();forged.uri=real.uri;
        rejectAudio(store.get(project.id),forged,0,1000,24,"owned");
        rejectAudio(store.get(project.id),real,1600,900,24,"recording");
    }
    @Test public void audioAnalysisBoundsAreEnforcedBeforeAnyDecoderIsStarted()throws Exception{
        ProjectStore store=new ProjectStore(context);
        ProjectStore.Project project=store.create("Audio bounds "+UUID.randomUUID());
        ProjectStore.Asset asset=store.registerImportedAsset(project.id,Uri.parse("content://test.provider/voice"),
                "Owner selected", "audio/wav",90000,1000);
        rejectAudio(store.get(project.id),asset,0,60000,24,"Bounded");
        rejectAudio(store.get(project.id),asset,0,1000,1000,"Bounded");
    }
    private void rejectAudio(ProjectStore.Project project,ProjectStore.Asset asset,long sourceMs,
                             long durationMs,int buckets,String detail)throws Exception {
        ExecutorService io=Executors.newSingleThreadExecutor();
        try{
            Future<?> future=io.submit(()->{
                try{
                    StudioAudioWaveforms.analyzeOwnedDialogue(context,project,asset,sourceMs,durationMs,buckets);
                    fail("Audio source metadata bypassed owner-only decoding policy");
                }catch(IllegalArgumentException expected){
                    assertTrue(expected.getMessage(),expected.getMessage().toLowerCase(java.util.Locale.ROOT)
                            .contains(detail.toLowerCase(java.util.Locale.ROOT)));
                }catch(Exception wrongFailure){throw new AssertionError("Owner policy must fail before media decoding",wrongFailure);}
            });
            future.get(10,TimeUnit.SECONDS);
        }finally{io.shutdownNow();}
    }
}
