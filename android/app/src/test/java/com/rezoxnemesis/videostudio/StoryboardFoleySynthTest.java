package com.rezoxnemesis.videostudio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public final class StoryboardFoleySynthTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void createsRealSignedPcmWaveformForMotionBeats() throws Exception {
        File wav=new File(temp.getRoot(),"actions.wav");
        List<StoryboardFoleySynth.Cue> cues=Arrays.asList(
                new StoryboardFoleySynth.Cue(
                        StoryboardMotionGrammar.Action.WALK,
                        StoryboardMotionGrammar.SceneKind.COMIC,.3f,.65f),
                new StoryboardFoleySynth.Cue(
                        StoryboardMotionGrammar.Action.IMPACT,
                        StoryboardMotionGrammar.SceneKind.ACTION,.8f,1f));
        StoryboardFoleySynth.Result result=
                StoryboardFoleySynth.render(wav,1700,cues);
        assertEquals(2,result.cues);
        byte[] data=Files.readAllBytes(wav.toPath());
        assertEquals('R',data[0]);
        assertEquals('I',data[1]);
        assertEquals('F',data[2]);
        assertEquals('F',data[3]);
        assertEquals('W',data[8]);
        assertEquals('A',data[9]);
        assertEquals('V',data[10]);
        assertEquals('E',data[11]);
        int nonZero=0;
        for(int i=44;i<data.length;i++)if(data[i]!=0)nonZero++;
        assertTrue("Foley must contain actual audible PCM samples",nonZero>1000);
        assertEquals(44+2L*(long)Math.ceil(1700d*
                StoryboardFoleySynth.SAMPLE_RATE/1000),data.length);
    }
    @Test public void rejectsUnboundedOrUntrustedAudioJobs() throws Exception {
        File wav=new File(temp.getRoot(),"oversize.wav");
        try{StoryboardFoleySynth.render(wav,999999,
                java.util.Collections.emptyList());fail();}
        catch(IllegalArgumentException expected){}
        assertFalse(wav.exists());
    }
}
