package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import androidx.media3.common.util.UnstableApi;
import static org.junit.Assert.*;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioAudioTracksTest {
    private static ProjectStore.Project example() {
        ProjectStore.Project p=new ProjectStore.Project();
        p.id="safe-project-123";
        p.name="Audio mixture";
        ProjectStore.Asset video=new ProjectStore.Asset();
        video.id="video_1";video.name="video.mp4";video.uri="file:///video.mp4";
        video.mime="video/mp4";video.durationMs=6000;
        ProjectStore.Asset sound=new ProjectStore.Asset();
        sound.id="audio_2";sound.name="music.mp3";sound.uri="file:///music.mp3";
        sound.mime="audio/mpeg";sound.durationMs=8000;
        p.assets.add(video);p.assets.add(sound);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="first_clip";clip.assetId="video_1";
        clip.inMs=0;clip.outMs=6000;p.clips.add(clip);
        return p;
    }

    @Test public void buildsRealTimedMedia3AudioSequence() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore.Project p=example();
        StudioTrackLayout tracks=StudioTrackEditor.get(p);
        tracks.track("audio-1").add(new StudioTrackLayout.Item(
                "music_occ","audio_2",1500,0,2000,1f));
        p.trackLayout=tracks.toJson();
        assertEquals(1,new NativeRenderEngine(context).buildProjectAudioTracks(p).size());
        assertEquals(1,StudioTrackEditor.audioClipCount(tracks));
    }

    @Test public void supportsIndependentOverlappingAudioTracks() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore.Project p=example();
        StudioTrackLayout tracks=StudioTrackEditor.get(p);
        tracks.track("audio-1").add(new StudioTrackLayout.Item(
                "music_occ","audio_2",1000,0,2400,1f));
        tracks.addTrack("audio-2",StudioTrackLayout.Kind.AUDIO)
                .add(new StudioTrackLayout.Item("second_occ","audio_2",2000,0,2000,1f));
        p.trackLayout=tracks.toJson();
        assertEquals(2,new NativeRenderEngine(context).buildProjectAudioTracks(p).size());
    }

    @Test(expected=IllegalArgumentException.class)
    public void doesNotSilentlyIgnoreUnsupportedGain() throws Exception {
        ProjectStore.Project p=example();
        StudioTrackLayout tracks=StudioTrackEditor.get(p);
        tracks.track("audio-1").add(new StudioTrackLayout.Item(
                "sound","audio_2",1000,0,1800,.4f));
        p.trackLayout=tracks.toJson();
        new NativeRenderEngine(RuntimeEnvironment.getApplication())
                .buildProjectAudioTracks(p);
    }

    @Test public void trackPersistenceNeverRemovesNativeGeneratedAsset() throws Exception {
        Context ctx=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(ctx);
        ProjectStore.Project project=store.create("Human edited audio");
        ProjectStore.Asset generated=new ProjectStore.Asset();
        generated.id="generated_1";generated.name="Ashes.mp4";
        generated.mime="video/mp4";generated.uri="file:///ashes.mp4";generated.generated=true;
        project.assets.add(generated);store.save(project);
        StudioTrackLayout layout=StudioTrackEditor.get(project);
        layout.addTrack("audio-2",StudioTrackLayout.Kind.AUDIO);
        ProjectStore.Project result=StudioTrackEditor.commit(store,project,layout);
        assertEquals(1,result.assets.size());
        assertTrue(result.assets.get(0).generated);
        assertEquals(6,StudioTrackEditor.get(store.get(project.id)).tracks().size());
    }
}
