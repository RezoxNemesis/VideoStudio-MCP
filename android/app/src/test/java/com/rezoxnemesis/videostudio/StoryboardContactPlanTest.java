package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StoryboardContactPlanTest {
    private static Rig2DProfile rig(String id,String asset,boolean black,float hipShift) {
        float[][] points=Rig2DProfileTest.joints();
        points[0][0]+=hipShift;points[10][0]+=hipShift;points[13][0]+=hipShift;
        return new Rig2DProfile(id,"project-1111",asset,black,false,points,Rig2DProfileTest.outline(),new float[]{.8f,.5f});
    }
    private static Rig2DProfile[] quartet() {
        return new Rig2DProfile[]{rig("black-from","asset-from",true,0),rig("white-from","asset-from",false,0),
                rig("black-next","asset-next",true,.02f),rig("white-next","asset-next",false,-.01f)};
    }
    private static JSONObject interval(String foot,double start,double end,double u,double v) throws Exception {
        return new JSONObject().put("foot",foot).put("start",start).put("end",end).put("anchor",new JSONArray().put(u).put(v));
    }
    private static JSONObject contacts(Rig2DProfile[] rigs) throws Exception {
        return new JSONObject().put("black",new JSONArray().put(interval("leadingFoot",0,1,rigs[0].joints[12][0],rigs[0].joints[12][1])))
                .put("white",new JSONArray().put(interval("trailingFoot",0,1,rigs[1].joints[15][0],rigs[1].joints[15][1])));
    }
    private static StoryboardContactPlan plan(JSONObject json,Rig2DProfile[] rigs) throws Exception {
        return StoryboardContactPlan.parse(json,rigs[0],rigs[1],rigs[2],rigs[3],720,1280);
    }
    private static double distance(float[] a,float[] b,double aspect) {return Math.hypot((a[0]-b[0])*aspect,a[1]-b[1]);}

    @Test public void authoredPlantKeepsFootFixedAndBothLegLengthsInPortraitMetric() throws Exception {
        Rig2DProfile[] rigs=quartet();StoryboardContactPlan plan=plan(contacts(rigs),rigs);
        for(int frame=0;frame<=40;frame++) {
            double time=frame/40d;
            StoryboardPoseMotion.Frame sample=StoryboardPoseMotion.between(rigs[0],rigs[1],rigs[2],rigs[3],
                    rigs[0].swordTip,rigs[1].swordTip,rigs[2].swordTip,rigs[3].swordTip,time,StoryboardPoseMotion.Action.ADVANCE,plan);
            assertArrayEquals(rigs[0].joints[12],sample.black[12],1e-7f);
            assertArrayEquals(rigs[1].joints[15],sample.white[15],1e-7f);
            assertEquals(2,sample.predictedFootContacts.size());
            for(StoryboardContactPlan.FootSample trace:sample.predictedFootContacts) {
                assertTrue(trace.targetReachable);assertTrue(trace.active);assertEquals(0,trace.errorSourceHeight,1e-7);
            }
            double upper=distance(rigs[0].joints[10],rigs[0].joints[11],plan.sourceAspect())*(1-time)
                    +distance(rigs[2].joints[10],rigs[2].joints[11],plan.sourceAspect())*time;
            double lower=distance(rigs[0].joints[11],rigs[0].joints[12],plan.sourceAspect())*(1-time)
                    +distance(rigs[2].joints[11],rigs[2].joints[12],plan.sourceAspect())*time;
            assertEquals(upper,distance(sample.black[10],sample.black[11],plan.sourceAspect()),1e-6);
            assertEquals(lower,distance(sample.black[11],sample.black[12],plan.sourceAspect()),1e-6);
        }
    }
    @Test public void contactBoundariesHaveContinuousSourceFootPaths() throws Exception {
        Rig2DProfile[] rigs=quartet();
        JSONObject json=new JSONObject().put("black",new JSONArray().put(interval("leadingFoot",.3,.7,.555,.88))).put("white",new JSONArray());
        StoryboardContactPlan p=plan(json,rigs);
        float[][] before=p.solve(rigs[0].joints,rigs[1].joints,.3-1e-6,0,0).black;
        float[][] at=p.solve(rigs[0].joints,rigs[1].joints,.3,0,0).black;
        float[][] after=p.solve(rigs[0].joints,rigs[1].joints,.7+1e-6,0,0).black;
        assertEquals(0,distance(before[12],at[12],p.sourceAspect()),1e-6);
        assertEquals(0,distance(after[12],at[12],p.sourceAspect()),1e-6);
    }
    @Test public void absentIntervalsDoNotBecomeAnInferredContactCertificate() throws Exception {
        Rig2DProfile[] rigs=quartet();StoryboardContactPlan p=plan(new JSONObject().put("black",new JSONArray()).put("white",new JSONArray()),rigs);
        assertFalse(p.hasContacts(true));assertFalse(p.hasContacts(false));
        assertTrue(p.solve(rigs[0].joints,rigs[1].joints,.5,.5,.5).predictedFootContacts.isEmpty());
    }
    @Test(expected=IllegalArgumentException.class) public void unreachableActivePlantFailsInsteadOfClampingAwayDrift() throws Exception {
        Rig2DProfile[] rigs=quartet();StoryboardContactPlan p=plan(new JSONObject().put("black",new JSONArray().put(interval("leadingFoot",.4,.6,.98,.01))).put("white",new JSONArray()),rigs);
        p.solve(rigs[0].joints,rigs[1].joints,.5,.5,.5);
    }
    @Test public void preflightIncludesNarrowContactInteriorsAndTheActualFloatFrameClock() throws Exception {
        Rig2DProfile[] rigs=quartet();StoryboardContactPlan p=plan(new JSONObject().put("black",new JSONArray().put(interval("leadingFoot",.002,.003,.98,.01))).put("white",new JSONArray()),rigs);
        java.util.List<Double> phases=p.validationPhases(24);
        assertTrue(phases.contains(.002));assertTrue(phases.contains(.003));
        assertTrue(phases.contains(.002+(.003-.002)*.5));assertTrue(phases.contains((double)(1/(float)24)));
        boolean rejected=false;
        for(double phase:phases)try{p.solve(rigs[0].joints,rigs[1].joints,phase,phase,phase);}
        catch(IllegalArgumentException unreachable){rejected=true;break;}
        assertTrue("A narrow unreachable authored plant must be found before output allocation",rejected);
    }
    @Test(expected=IllegalArgumentException.class) public void endpointPlantCannotOverrideTheActualSourceFoot() throws Exception {
        Rig2DProfile[] rigs=quartet();plan(new JSONObject().put("black",new JSONArray().put(interval("leadingFoot",0,.5,.8,.8))).put("white",new JSONArray()),rigs);
    }
    @Test(expected=IllegalArgumentException.class) public void twoOverlappingPlantsOnOneFootAreRejected() throws Exception {
        Rig2DProfile[] rigs=quartet();plan(new JSONObject().put("black",new JSONArray().put(interval("leadingFoot",.2,.6,.55,.9)).put(interval("leadingFoot",.5,.8,.55,.9))).put("white",new JSONArray()),rigs);
    }
    @Test public void frozenProofIncludesAuthoredContactsAndExactEndpointRigBytes() throws Exception {
        Rig2DProfile[] rigs=quartet();JSONObject authored=contacts(rigs);StoryboardContactPlan p=plan(authored,rigs);
        JSONObject proof=p.bindingProof();String frozen=Rig2DProjectStore.canonicalJson(proof);
        authored.getJSONArray("black").getJSONObject(0).getJSONArray("anchor").put(0,.7);
        assertEquals(frozen,Rig2DProjectStore.canonicalJson(p.bindingProof()));
        JSONObject changed=p.toJson();changed.getJSONArray("black").getJSONObject(0).put("end",.9);
        StoryboardContactPlan other=plan(changed,rigs);
        try{other.requireBindingProof(proof);fail("Changed contacts must invalidate proof");}catch(IllegalStateException expected){}
        rigs[0].joints[0][0]+=.001f;
        try{p.requireRigBindings(rigs[0],rigs[1],rigs[2],rigs[3]);fail("Changed rig must invalidate proof");}catch(IllegalStateException expected){}
        assertEquals(frozen,Rig2DProjectStore.canonicalJson(p.bindingProof()));
    }
    @Test public void independentBeatPhasesRetainBothExactEndpointsAndProgramContactClock() throws Exception {
        Rig2DProfile[] rigs=quartet();StoryboardContactPlan p=plan(contacts(rigs),rigs);
        for(double time:new double[]{0,.35,1}) {
            StoryboardPoseMotion.Frame frame=StoryboardPoseMotion.between(rigs[0],rigs[1],rigs[2],rigs[3],
                    rigs[0].swordTip,rigs[1].swordTip,rigs[2].swordTip,rigs[3].swordTip,time,StoryboardChoreography.Beat.BLACK_STEP,p);
            assertEquals(time,frame.progress,0);assertEquals(2,frame.predictedFootContacts.size());
            assertEquals(rigs[0].joints[0][0]+(rigs[2].joints[0][0]-rigs[0].joints[0][0])*
                    StoryboardChoreography.phase(StoryboardChoreography.Beat.BLACK_STEP,true,time),frame.black[0][0],1e-7);
            assertEquals(rigs[1].joints[0][0]+(rigs[3].joints[0][0]-rigs[1].joints[0][0])*
                    StoryboardChoreography.phase(StoryboardChoreography.Beat.BLACK_STEP,false,time),frame.white[0][0],1e-7);
            if(time==0||time==1)for(int i=0;i<16;i++) {
                assertArrayEquals(rigs[time==0?0:2].joints[i],frame.black[i],0);
                assertArrayEquals(rigs[time==0?1:3].joints[i],frame.white[i],0);
            }
        }
    }
    private static Rig2DProfile weapon(String id,String asset,boolean black,float shift,float[] hilt,float[] tip) {
        float[][] joints=Rig2DProfileTest.joints();for(float[] point:joints)point[0]+=shift;
        return new Rig2DProfile(id,"project-1111",asset,black,false,joints,Rig2DProfileTest.outline(),tip,hilt,null);
    }
    @Test public void actualLateSourceBladeIntersectionIsNotSuppressedByAMidpointEnvelope() throws Exception {
        Rig2DProfile bf=weapon("black-from","asset-from",true,0,new float[]{.65f,.54f},new float[]{.7f,.54f});
        Rig2DProfile wf=weapon("white-from","asset-from",false,.2f,new float[]{.85f,.54f},new float[]{.9f,.54f});
        Rig2DProfile bt=weapon("black-next","asset-next",true,0,new float[]{.65f,.54f},new float[]{.8f,.54f});
        Rig2DProfile wt=weapon("white-next","asset-next",false,.2f,new float[]{.83f,.54f},new float[]{.72f,.54f});
        StoryboardContactPlan p=StoryboardContactPlan.parse(new JSONObject().put("black",new JSONArray()).put("white",new JSONArray()),bf,wf,bt,wt,720,1280);
        StoryboardPoseMotion.Frame start=StoryboardPoseMotion.between(bf,wf,bt,wt,bf.swordTip,wf.swordTip,bt.swordTip,wt.swordTip,0,StoryboardChoreography.Beat.CLASH,p);
        StoryboardPoseMotion.Frame end=StoryboardPoseMotion.between(bf,wf,bt,wt,bf.swordTip,wf.swordTip,bt.swordTip,wt.swordTip,1,StoryboardChoreography.Beat.CLASH,p);
        assertEquals(0,start.contact,0);assertEquals(1,end.contact,1e-8);
        assertNotNull(end.bladeWitness);assertEquals(0,end.bladeWitness.separation,1e-12);
        assertArrayEquals(bt.swordHilt,end.blackBladeHilt,1e-7f);assertArrayEquals(wt.swordHilt,end.whiteBladeHilt,1e-7f);
    }
    @Test public void handSegmentCrossingCannotInventContactOutsideThePaintedBlade() {
        Rig2DProfile bf=weapon("black-from","asset-from",true,0,new float[]{.78f,.54f},new float[]{.9f,.54f});
        Rig2DProfile wf=weapon("white-from","asset-from",false,.2f,new float[]{.7f,.6f},new float[]{.7f,.5f});
        Rig2DProfile bt=weapon("black-next","asset-next",true,0,bf.swordHilt,bf.swordTip);
        Rig2DProfile wt=weapon("white-next","asset-next",false,.2f,wf.swordHilt,wf.swordTip);
        StoryboardPoseMotion.Frame sample=StoryboardPoseMotion.between(bf,wf,bt,wt,bf.swordTip,wf.swordTip,bt.swordTip,wt.swordTip,.5,StoryboardPoseMotion.Action.IMPACT,720d/1280);
        assertNotNull(sample.bladeWitness);assertTrue(sample.bladeWitness.separation>.04);
        assertEquals(0,sample.contact,0);
    }
}
