package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Synthetic numeric fixtures exercise ledger semantics, not native Test 1 acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StoryboardMotionEvidenceTest {
    private static final String DIGEST="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final StoryboardMotionEvidence.Origin DECODED=StoryboardMotionEvidence.Origin.DECODED_OUTPUT;
    private static final StoryboardMotionEvidence.Origin PREDICTED=StoryboardMotionEvidence.Origin.PREDICTED_NATIVE_EVALUATOR;

    @Test public void absentMetricsHaveNullValuesAndCannotBecomeZeroAsPass() throws Exception {
        StoryboardMotionEvidence.Ledger report=StoryboardMotionEvidence.unavailable(true,"auto-motion-foreground-2d","No decoded joint/weapon traces");
        assertEquals(StoryboardMotionEvidence.Verdict.INCOMPLETE,report.technicalVerdict);
        JSONObject json=report.toJson();assertFalse(json.getBoolean("cinematicAcceptance"));assertFalse(json.getBoolean("decodedOutputVerified"));
        for(int index=0;index<json.getJSONArray("metrics").length();index++){
            JSONObject metric=json.getJSONArray("metrics").getJSONObject(index);
            assertEquals("UNAVAILABLE",metric.getString("evidenceStatus"));assertTrue(metric.isNull("value"));
            assertEquals("INCOMPLETE",metric.getString("verdict"));
        }
    }
    @Test public void predictionDiagnosticsNeverCertifyDecodedOutput() throws Exception {
        StoryboardMotionEvidence.Trace trace=trace(PREDICTED,24,0,0,false,24,true);
        StoryboardMotionEvidence.Ledger report=StoryboardMotionEvidence.inspect(requirements(),trace);
        assertEquals(1,report.metric("T11").value,1e-9);
        assertTrue(report.metric("T11").thresholdSatisfied);
        assertEquals(StoryboardMotionEvidence.Verdict.INCOMPLETE,report.metric("T11").verdict);
        assertEquals(0,report.metric("T12").value,1e-9);
        assertEquals(StoryboardMotionEvidence.Verdict.INCOMPLETE,report.metric("T12").verdict);
        assertFalse(report.toJson().getBoolean("cinematicAcceptance"));
    }
    @Test public void translatedOrCameraMovedPosesFailDespiteLargePixelTravel(){
        StoryboardMotionEvidence.Ledger report=StoryboardMotionEvidence.inspect(requirements(),trace(DECODED,24,0,0,true,24,true));
        assertEquals(0,report.metric("T11").value,1e-9);
        assertEquals(StoryboardMotionEvidence.Verdict.FAIL,report.metric("T11").verdict);
        assertEquals(StoryboardMotionEvidence.Verdict.FAIL,report.technicalVerdict);
    }
    @Test public void fiveAnchorPicturesAreNotSixteenDenseObservedFrames(){
        StoryboardMotionEvidence.Trace complete=trace(DECODED,24,0,0,false,24,true);
        ArrayList<StoryboardMotionEvidence.Sample> five=new ArrayList<>();
        for(int index:new int[]{0,6,12,18,24})five.add(complete.samples.get(index));
        StoryboardMotionEvidence.Trace sparse=new StoryboardMotionEvidence.Trace(DECODED,"synthetic-numeric-fixture",DIGEST,true,false,five,null,null,null,null);
        StoryboardMotionEvidence.Ledger report=StoryboardMotionEvidence.inspect(requirements(),sparse);
        assertEquals(StoryboardMotionEvidence.EvidenceStatus.UNAVAILABLE,report.metric("T11").evidenceStatus);
        assertNull(report.metric("T11").value);assertFalse(report.densePhaseCoverage);
        assertEquals(StoryboardMotionEvidence.EvidenceStatus.UNAVAILABLE,report.metric("T15").evidenceStatus);
    }
    @Test public void seventyFivePercentIsInclusiveAndLowerCoverageFails(){
        StoryboardMotionEvidence.Ledger boundary=StoryboardMotionEvidence.inspect(requirements(),trace(DECODED,24,0,0,false,18,true));
        assertEquals(.75,boundary.metric("T11").value,1e-9);assertTrue(boundary.metric("T11").thresholdSatisfied);
        StoryboardMotionEvidence.Ledger below=StoryboardMotionEvidence.inspect(requirements(),trace(DECODED,24,0,0,false,17,true));
        assertFalse(below.metric("T11").thresholdSatisfied);
        assertEquals(StoryboardMotionEvidence.Verdict.FAIL,below.metric("T11").verdict);
        assertTrue("A long unannotated final hold must remain visible",boundary.metric("T15").value>=4);
    }
    @Test public void footTargetIsStrictWhileHiltTargetIsInclusive(){
        StoryboardMotionEvidence.Ledger inside=StoryboardMotionEvidence.inspect(requirements(),trace(DECODED,24,7.49,10,false,24,true));
        assertTrue(inside.metric("T12").thresholdSatisfied);assertTrue(inside.metric("T13").thresholdSatisfied);
        StoryboardMotionEvidence.Ledger outside=StoryboardMotionEvidence.inspect(requirements(),trace(DECODED,24,7.5,10.01,false,24,true));
        assertEquals(.015,outside.metric("T12").value,1e-12);assertFalse(outside.metric("T12").thresholdSatisfied);
        assertFalse(outside.metric("T13").thresholdSatisfied);
    }
    @Test public void virtualOrMissingHiltDoesNotFabricateMeasuredZero(){
        StoryboardMotionEvidence.Ledger report=StoryboardMotionEvidence.inspect(requirements(),trace(DECODED,24,0,0,false,24,false));
        assertEquals(StoryboardMotionEvidence.EvidenceStatus.UNAVAILABLE,report.metric("T13").evidenceStatus);assertNull(report.metric("T13").value);
    }
    @Test public void cameraComparisonRequiresTheSameObservedFrameClockAndPhases(){
        StoryboardMotionEvidence.Trace enabled=trace(DECODED,24,0,0,false,24,true);
        StoryboardMotionEvidence.Trace matching=cameraPeer(enabled,0,0,0);
        assertEquals(StoryboardMotionEvidence.Verdict.PASS,
                StoryboardMotionEvidence.inspect(requirements(),enabled,matching).metric("T17").verdict);
        // The same source hash cannot make a different shot portion, frame domain or phase comparable.
        for(StoryboardMotionEvidence.Trace peer:new StoryboardMotionEvidence.Trace[]{
                cameraPeer(enabled,1,0,0),cameraPeer(enabled,0,1,0),cameraPeer(enabled,0,0,.001),
                cameraPeer(enabled,50_000,0,0)}){
            StoryboardMotionEvidence.Metric camera=StoryboardMotionEvidence.inspect(requirements(),enabled,peer).metric("T17");
            assertEquals(StoryboardMotionEvidence.EvidenceStatus.UNAVAILABLE,camera.evidenceStatus);
            assertEquals(StoryboardMotionEvidence.Verdict.INCOMPLETE,camera.verdict);assertNull(camera.value);
        }
    }
    @Test public void cameraComparisonCannotUseDifferentAuthoredHoldExclusions(){
        StoryboardMotionEvidence.Trace enabled=trace(DECODED,24,0,0,false,24,true);
        StoryboardMotionEvidence.Trace peer=new StoryboardMotionEvidence.Trace(DECODED,enabled.provider,DIGEST,true,true,
                enabled.samples,Collections.singletonList(new StoryboardMotionEvidence.HoldInterval(true,0,500_000)),
                enabled.stances,null,null);
        assertEquals(StoryboardMotionEvidence.EvidenceStatus.UNAVAILABLE,
                StoryboardMotionEvidence.inspect(requirements(),enabled,peer).metric("T17").evidenceStatus);
    }
    @Test public void audioUsesActualSampleClockAt24ThirtyAnd60Frames(){
        for(int fps:new int[]{24,30,60}){
            long samples=24000+96000/fps;
            StoryboardMotionEvidence.Ledger boundary=audioLedger(fps,1,samples,48000,DECODED,true);
            assertEquals(2,boundary.metric("T19").value,1e-12);assertTrue(boundary.metric("T19").thresholdSatisfied);
            assertEquals(StoryboardMotionEvidence.Verdict.PASS,boundary.metric("T19").verdict);
            assertFalse(audioLedger(fps,1,samples+1,48000,DECODED,true).metric("T19").thresholdSatisfied);
            assertEquals(-2,audioLedger(fps,1,24000-96000/fps,48000,DECODED,true).metric("T19").value,1e-12);
        }
        assertTrue(audioLedger(30000,1001,24000+3203,48000,DECODED,true).metric("T19").thresholdSatisfied);
        assertFalse(audioLedger(30000,1001,24000+3204,48000,DECODED,true).metric("T19").thresholdSatisfied);
    }
    @Test public void missingPeakAndPredictedContactDoNotClaimAudioSynchronization(){
        assertNull(audioLedger(24,1,24000,48000,DECODED,false).metric("T19").value);
        StoryboardMotionEvidence.Metric predicted=audioLedger(24,1,24000,48000,PREDICTED,true).metric("T19");
        assertEquals(0,predicted.value,1e-12);assertEquals(StoryboardMotionEvidence.Verdict.INCOMPLETE,predicted.verdict);
    }
    @Test public void nearTerminalContactCannotUseAPeakOutsideTheActualOutputClock(){
        StoryboardMotionEvidence.ContactEvent contact=new StoryboardMotionEvidence.ContactEvent(
                "terminal-clash",980_000,981_000,0,true,DECODED,DIGEST);
        // 1.035 seconds is only 1.32 frames after contact at24fps, but is outside this one-second output.
        StoryboardMotionEvidence.AudioPeak peak=new StoryboardMotionEvidence.AudioPeak(
                "terminal-clash",49_680,48_000,0,DECODED,DIGEST);
        StoryboardMotionEvidence.Trace observed=new StoryboardMotionEvidence.Trace(DECODED,"synthetic-numeric-audio",DIGEST,
                true,false,null,null,null,Collections.singletonList(contact),Collections.singletonList(peak));
        StoryboardMotionEvidence.Metric audio=StoryboardMotionEvidence.inspect(new StoryboardMotionEvidence.Requirements(
                432,768,24,1,1_000_000,0,true,true,950_000,1_000_000,1),observed).metric("T19");
        assertEquals(StoryboardMotionEvidence.EvidenceStatus.UNAVAILABLE,audio.evidenceStatus);
        assertNull(audio.value);assertEquals(StoryboardMotionEvidence.Verdict.INCOMPLETE,audio.verdict);
    }
    @Test public void bladeWitnessFlagCannotMakeSeparatedSegmentsAContact(){
        for(double separation:new double[]{1,1.01,250}){
            StoryboardMotionEvidence.ContactEvent event=new StoryboardMotionEvidence.ContactEvent(
                    "clash",500_000,501_000,separation,true,DECODED,DIGEST);
            StoryboardMotionEvidence.Trace observed=new StoryboardMotionEvidence.Trace(DECODED,"synthetic-numeric-contact",DIGEST,
                    true,false,null,null,null,Collections.singletonList(event),Collections.singletonList(
                            new StoryboardMotionEvidence.AudioPeak("clash",24_000,48_000,0,DECODED,DIGEST)));
            StoryboardMotionEvidence.Ledger report=StoryboardMotionEvidence.inspect(new StoryboardMotionEvidence.Requirements(
                    432,768,24,1,1_000_000,0,true,true,450_000,550_000,1),observed);
            StoryboardMotionEvidence.Metric contact=report.metric("T14");
            assertEquals(separation<=1?StoryboardMotionEvidence.Verdict.PASS:StoryboardMotionEvidence.Verdict.FAIL,contact.verdict);
            if(separation>1)assertEquals(StoryboardMotionEvidence.EvidenceStatus.UNAVAILABLE,report.metric("T19").evidenceStatus);
        }
    }
    @Test public void traceCopiesInputsAndRejectsRepeatedFrameReceipts(){
        double[][] joints=points();StoryboardMotionEvidence.ActorPose pose=new StoryboardMotionEvidence.ActorPose(joints,500,null,null,true,false);
        double original=pose.joints()[0][0];joints[0][0]=999;double[][] exposed=pose.joints();exposed[0][0]=888;
        assertEquals(original,pose.joints()[0][0],0);
        StoryboardMotionEvidence.Sample sample=new StoryboardMotionEvidence.Sample(0,0,0,pose,pose,DIGEST);
        try{new StoryboardMotionEvidence.Trace(DECODED,"fixture",DIGEST,true,false,java.util.Arrays.asList(sample,sample),null,null,null,null);fail();}
        catch(IllegalArgumentException expected){}
        try{new StoryboardMotionEvidence.Trace(DECODED,"fixture",DIGEST,true,false,Collections.singletonList(
                new StoryboardMotionEvidence.Sample(0,0,0,pose,pose,null)),null,null,null,null);fail("Decoded observations need artifact bindings");}
        catch(IllegalArgumentException expected){}
    }
    private static StoryboardMotionEvidence.Requirements requirements(){
        return new StoryboardMotionEvidence.Requirements(432,768,24,1,1_041_667,25,true,false,-1,-1,.01);
    }
    private static StoryboardMotionEvidence.Trace trace(StoryboardMotionEvidence.Origin origin,int steps,double footShift,double hiltOffset,
                                                        boolean rigid,int changingUntil,boolean independentHilt){
        List<StoryboardMotionEvidence.Sample> samples=new ArrayList<>();
        for(int index=0;index<=steps;index++){
            double[][] joints=points();int poseIndex=Math.min(index,changingUntil);
            if(rigid)for(double[] point:joints)point[0]+=index*4;
            else{
                joints[Rig2DProfile.SWORD_ELBOW][0]+=poseIndex*2;
                joints[Rig2DProfile.SWORD_HAND][1]-=poseIndex*3;
                joints[Rig2DProfile.LEADING_KNEE][0]+=poseIndex;
                joints[Rig2DProfile.FREE_ELBOW][1]+=poseIndex*2;
            }
            joints[Rig2DProfile.LEADING_FOOT][0]+=footShift*Math.min(1,index/(steps*.5));
            double[] hilt=independentHilt?new double[]{joints[Rig2DProfile.SWORD_HAND][0]+hiltOffset,joints[Rig2DProfile.SWORD_HAND][1]}:null;
            StoryboardMotionEvidence.ActorPose pose=new StoryboardMotionEvidence.ActorPose(joints,500,hilt,new double[]{joints[6][0]+50,joints[6][1]},true,independentHilt);
            samples.add(new StoryboardMotionEvidence.Sample(index,Math.round(index*1_000_000d/steps),index/(double)steps,pose,pose,origin==DECODED?DIGEST:null));
        }
        List<StoryboardMotionEvidence.StanceInterval> stances=Collections.singletonList(new StoryboardMotionEvidence.StanceInterval(
                true,Rig2DProfile.LEADING_FOOT,0,500_000,points()[Rig2DProfile.LEADING_FOOT]));
        return new StoryboardMotionEvidence.Trace(origin,"synthetic-numeric-fixture",origin==DECODED?DIGEST:null,true,false,samples,null,stances,null,null);
    }
    private static double[][] points(){
        float[][] normalized=Rig2DProfileTest.joints();double[][] values=new double[16][2];
        for(int index=0;index<16;index++){values[index][0]=normalized[index][0]*432d;values[index][1]=normalized[index][1]*768d;}
        return values;
    }
    private static StoryboardMotionEvidence.Trace cameraPeer(StoryboardMotionEvidence.Trace enabled,long timeShift,int frameShift,double phaseShift){
        List<StoryboardMotionEvidence.Sample> samples=new ArrayList<>();
        for(StoryboardMotionEvidence.Sample sample:enabled.samples)samples.add(new StoryboardMotionEvidence.Sample(
                sample.frameIndex+frameShift,sample.timestampUs+timeShift,Math.min(1,sample.phase+phaseShift),sample.black,sample.white,DIGEST));
        return new StoryboardMotionEvidence.Trace(DECODED,enabled.provider,DIGEST,true,true,samples,enabled.holds,enabled.stances,null,null);
    }
    private static StoryboardMotionEvidence.Ledger audioLedger(int numerator,int denominator,long peakSample,int sampleRate,
                                                               StoryboardMotionEvidence.Origin contactOrigin,boolean peakPresent){
        StoryboardMotionEvidence.ContactEvent contact=new StoryboardMotionEvidence.ContactEvent("clash-1",500_000,501_000,0,true,contactOrigin,contactOrigin==DECODED?DIGEST:null);
        List<StoryboardMotionEvidence.AudioPeak> peaks=peakPresent?Collections.singletonList(new StoryboardMotionEvidence.AudioPeak("clash-1",peakSample,sampleRate,0,DECODED,DIGEST)):Collections.emptyList();
        StoryboardMotionEvidence.Trace trace=new StoryboardMotionEvidence.Trace(DECODED,"synthetic-numeric-audio",DIGEST,true,false,Collections.emptyList(),null,null,Collections.singletonList(contact),peaks);
        StoryboardMotionEvidence.Requirements req=new StoryboardMotionEvidence.Requirements(432,768,numerator,denominator,1_000_000,0,true,true,450_000,550_000,1);
        return StoryboardMotionEvidence.inspect(req,trace);
    }
}
