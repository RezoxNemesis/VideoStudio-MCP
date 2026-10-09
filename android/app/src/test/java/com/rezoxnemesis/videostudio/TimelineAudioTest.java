package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class TimelineAudioTest {
    private final TimelineCompositionFactory factory=new TimelineCompositionFactory(RuntimeEnvironment.getApplication());
    private ProjectStore.Project project()throws Exception{
        ProjectStore.Project p=new ProjectStore.Project();p.id="continuous-audio";p.ensureTimelineDefaults();ProjectStore.Asset a=new ProjectStore.Asset();a.id="source";a.uri="file:///original.mp4";a.mime="video/mp4";a.durationMs=15000;a.generationMetadata.put("audioMetadataKnown",true).put("hasAudio",true).put("proxyUri","file:///proxy.mp4");p.assets.add(a);return p;
    }
    private ProjectStore.Clip clip(String id,long start,long in,long out,float speed){ProjectStore.Clip c=new ProjectStore.Clip();c.id=id;c.assetId="source";c.trackId="video-1";c.startMs=start;c.inMs=in;c.outMs=out;c.speed=speed;return c;}
    @Test public void fullClipUsesOneContinuousOriginalSourceAndDspAcrossWindowBoundaries()throws Exception{
        var p=project();var c=clip("program",0,1000,13000,1);c.effects.put("audioDsp",new JSONObject().put("delayMs",100).put("delayWet",.5).put("delayFeedback",.25));p.clips.add(c);String before=p.snapshotJson().toString();Composition audio=factory.buildAudio(p);
        assertEquals(1,audio.sequences.size());assertEquals(1,audio.sequences.get(0).editedMediaItems.size());EditedMediaItem item=audio.sequences.get(0).editedMediaItems.get(0);assertTrue(item.removeVideo);assertFalse(item.removeAudio);assertEquals("file:///original.mp4",item.mediaItem.localConfiguration.uri.toString());assertEquals(1000000,item.mediaItem.clippingConfiguration.startPositionUs);assertEquals(13000000,item.mediaItem.clippingConfiguration.endPositionUs);assertEquals(15000000,item.durationUs);assertEquals(1,item.effects.audioProcessors.size());assertTrue(item.effects.audioProcessors.get(0) instanceof ClipAudioProcessor);assertTrue(item.effects.videoEffects.isEmpty());assertEquals(before,p.snapshotJson().toString());
        // A delay remains audible after a boundary between input buffers instead of a flush at video5s.
        var processor=item.effects.audioProcessors.get(0);processor.configure(new androidx.media3.common.audio.AudioProcessor.AudioFormat(8000,1,C.ENCODING_PCM_FLOAT));processor.flush();ByteBuffer first=ByteBuffer.allocateDirect(5*8000*4).order(ByteOrder.nativeOrder());for(int i=0;i<40000;i++)first.putFloat(i==39999?1:0);first.flip();processor.queueInput(first);processor.getOutput();ByteBuffer second=ByteBuffer.allocateDirect(801*4).order(ByteOrder.nativeOrder());for(int i=0;i<801;i++)second.putFloat(0);second.flip();processor.queueInput(second);ByteBuffer output=processor.getOutput().order(ByteOrder.nativeOrder());float delayed=0;while(output.hasRemaining())delayed=Math.max(delayed,Math.abs(output.getFloat()));assertTrue("Continuous DSP must retain the impulse into the next video window",delayed>.1f);
    }
    @Test public void silentProgramReturnsExplicitlyAbsentAudio()throws Exception{
        var p=project();p.assets.get(0).generationMetadata.put("hasAudio",false);p.clips.add(clip("silent",0,0,12000,1));assertNull(factory.buildAudio(p));p.assets.get(0).mime="image/png";assertNull(factory.buildAudio(p));
    }
    @Test public void hiddenAudibleTrackIsKeptAndMutedTrackIsSkipped()throws Exception{
        var p=project();p.clips.add(clip("sound",0,0,12000,1));p.tracks.get(0).visible=false;assertNotNull(factory.buildAudio(p));p.tracks.get(0).muted=true;assertNull(factory.buildAudio(p));
    }
    @Test public void soloSelectionDoesNotInspectExcludedMissingSources()throws Exception{
        var p=project();p.tracks.get(0).solo=true;p.clips.add(clip("selected",0,0,12000,1));ProjectStore.Track excluded=new ProjectStore.Track();excluded.id="excluded";p.tracks.add(excluded);var missing=clip("missing",0,0,14000,1);missing.trackId=excluded.id;missing.assetId="absent";p.clips.add(missing);assertEquals(1,factory.buildAudio(p).sequences.size());
    }
    @Test public void audioPaddingEndsAtTheExactWholeProgramClock()throws Exception{
        var p=project();for(int i=0;i<300;i++)p.clips.add(clip("fast-"+i,i*333,0,1000,3));ProjectStore.Track audioTrack=new ProjectStore.Track();audioTrack.id="audio-1";audioTrack.type="audio";p.tracks.add(audioTrack);var shortAudio=clip("short",0,0,1000,1);shortAudio.trackId=audioTrack.id;p.clips.add(shortAudio);Composition audio=factory.buildAudio(p);assertEquals(2,audio.sequences.size());var padded=audio.sequences.get(1).editedMediaItems;assertEquals(2,padded.size());assertEquals(98_999_900,padded.get(1).durationUs);
    }
    @Test public void silentRetimedSourcesBecomeExactAudioGaps()throws Exception{
        var p=project();ProjectStore.Asset silent=new ProjectStore.Asset();silent.id="silent";silent.mime="video/mp4";silent.uri="file:///silent.mp4";silent.durationMs=15000;silent.generationMetadata.put("audioMetadataKnown",true).put("hasAudio",false);p.assets.add(silent);p.clips.add(clip("first",0,0,1000,3));var middle=clip("middle",333,0,1000,3);middle.assetId="silent";p.clips.add(middle);p.clips.add(clip("last",666,0,1000,1));Composition audio=factory.buildAudio(p);assertEquals(333333,audio.sequences.get(0).editedMediaItems.get(1).durationUs);assertTrue(audio.sequences.get(0).editedMediaItems.get(0).removeVideo);
    }
}
