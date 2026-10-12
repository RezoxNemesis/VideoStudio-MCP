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
    @Test public void rejectsImpactOutsideTheCapturedOutputClock() throws Exception {
        File wav=new File(temp.getRoot(),"outside-output.wav");
        try {
            StoryboardFoleySynth.render(wav,1000,Arrays.asList(
                    new StoryboardFoleySynth.Cue(StoryboardMotionGrammar.Action.IMPACT,
                            StoryboardMotionGrammar.SceneKind.ACTION,1.2f,1f)));
            fail("An inaudible out-of-range impact must not count as synchronized Foley");
        } catch(IllegalArgumentException expected) { }
        assertFalse("Invalid events must reject before publishing an ambience-only track",wav.exists());
    }
    @Test public void zeroEnergyImpactCannotCountAsAnAudibleEvent() throws Exception {
        File wav=new File(temp.getRoot(),"silent-impact.wav");
        try {
            StoryboardFoleySynth.render(wav,1000,Arrays.asList(
                    new StoryboardFoleySynth.Cue(StoryboardMotionGrammar.Action.IMPACT,
                            StoryboardMotionGrammar.SceneKind.ACTION,.5f,0f)));
            fail("Nonzero background atmosphere is not evidence of a silent impact cue");
        } catch(IllegalArgumentException expected) { }
        assertFalse("Invalid inactive cues must not publish a claimed event track",wav.exists());
    }
    @Test public void capturedContactKeepsItsExactClockAndAudibleStemReceipt() throws Exception {
        File wav=new File(temp.getRoot(),"captured-clash.wav");
        List<StoryboardFoleySynth.Cue> cues=StoryboardFoleySynth.cuesFromCapturedEvents(Arrays.asList(
                new StoryboardFoleySynth.CapturedEvent("blade-contact-1",StoryboardMotionGrammar.Action.IMPACT,
                        StoryboardMotionGrammar.SceneKind.ACTION,500_000,1,StoryboardMotionEvidence.Origin.PREDICTED_NATIVE_EVALUATOR)),1_000_000);
        assertEquals(500_000,cues.get(0).timestampUs);
        StoryboardFoleySynth.Result result=StoryboardFoleySynth.render(wav,1000,cues);
        assertEquals(1,result.cues);assertEquals(1,result.events.size());
        StoryboardFoleySynth.EventReceipt event=result.events.get(0);
        assertEquals("blade-contact-1",event.eventId);assertEquals(500_000,event.cueTimestampUs);
        assertTrue(event.onsetTimestampUs>=500_000);assertTrue(event.peakPcmMagnitude>0);
        assertTrue("Impact stem peak remains near captured contact",Math.abs(event.peakTimestampUs-500_000)<33_333);
        assertEquals(StoryboardMotionEvidence.Origin.PREDICTED_NATIVE_EVALUATOR,event.timingOrigin);
    }
    @Test public void atmosphereCannotRescueAnInactiveOrSubPcmCue() throws Exception {
        for(boolean inactive:new boolean[]{true,false}){
            File wav=new File(temp.getRoot(),inactive?"inactive-track.wav":"sub-pcm.wav");
            try{
                StoryboardFoleySynth.render(wav,1000,Arrays.asList(new StoryboardFoleySynth.Cue(
                        inactive?StoryboardMotionGrammar.Action.TRACK:StoryboardMotionGrammar.Action.IMPACT,
                        StoryboardMotionGrammar.SceneKind.ACTION,.5f,inactive?1f:Float.MIN_VALUE)));
                fail("Background atmosphere is not an audible event receipt");
            }catch(IllegalArgumentException expected){}
            assertFalse(wav.exists());
        }
        File empty=new File(temp.getRoot(),"empty-events.wav");
        try{StoryboardFoleySynth.render(empty,1000,java.util.Collections.emptyList());fail();}
        catch(IllegalArgumentException expected){}
        assertFalse(empty.exists());
    }
    @Test public void explicitAmbienceProducesAudiblePcmWithoutClaimingAnyEvent() throws Exception {
        File wav=new File(temp.getRoot(),"ambient-only.wav");
        StoryboardFoleySynth.Result result=StoryboardFoleySynth.renderAmbience(wav,1200);
        assertTrue(result.ambienceOnly);assertEquals(0,result.cues);assertTrue(result.events.isEmpty());
        assertEquals("PROCEDURAL_AMBIENCE_ONLY",result.timingScope);
        assertEquals(StoryboardMotionEvidence.Origin.NONE,result.origin);
        assertFalse(result.eventSynchronizationVerified);assertFalse(result.decodedExportSynchronizationVerified);
        byte[] bytes=Files.readAllBytes(wav.toPath());assertEquals('R',bytes[0]);assertEquals('W',bytes[8]);
        assertEquals(44+2L*(long)Math.ceil(1200d*StoryboardFoleySynth.SAMPLE_RATE/1000),bytes.length);
        int peak=0,audibleSamples=0;
        for(int index=44;index+1<bytes.length;index+=2){
            int value=(short)((bytes[index]&255)|(bytes[index+1]<<8));
            peak=Math.max(peak,Math.abs(value));if(Math.abs(value)>100)audibleSamples++;
        }
        assertTrue("Explicit ambience must be actual audible PCM",peak>100&&audibleSamples>1000);
    }
    @Test public void explicitAmbienceKeepsTheExistingBoundedOutputContract() throws Exception {
        for(long duration:new long[]{99,120_001}){
            File wav=new File(temp.getRoot(),"invalid-ambience-"+duration+".wav");
            try{StoryboardFoleySynth.renderAmbience(wav,duration);fail("Ambience cannot bypass track duration bounds");}
            catch(IllegalArgumentException expected){}
            assertFalse(wav.exists());assertFalse(new File(wav.getPath()+".partial").exists());
        }
        try{StoryboardFoleySynth.renderAmbience(null,1000);fail("Selected output is still required");}
        catch(IllegalArgumentException expected){}
        try{StoryboardFoleySynth.cuesFromCapturedEvents(java.util.Collections.emptyList(),1_000_000);fail("Empty events are not ambience");}
        catch(IllegalArgumentException expected){}
    }
}
