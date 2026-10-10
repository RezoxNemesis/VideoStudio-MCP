package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Authored future regressions; not executed during the owner's implementation phase. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class AnimationRig2DTest {
    private ProjectStore.Project project()throws Exception{
        ProjectStore.Project project=new ProjectStore.Project();project.id="rig-project";
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="image";asset.mime="image/png";asset.uri="file:///fixture/cutout.png";asset.width=1000;asset.height=1000;project.assets.add(asset);
        ProjectStore.Track track=new ProjectStore.Track();track.id="video";track.type="video";project.tracks.add(track);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip";clip.assetId=asset.id;clip.trackId=track.id;clip.startMs=0;clip.inMs=0;clip.outMs=2000;project.clips.add(clip);
        JSONArray bones=new JSONArray().put(bone("upper","",.2,.5,.5,.5)).put(bone("lower","upper",.5,.5,.8,.5));
        AnimationRigEdits.apply(project,"create_rig",new JSONObject().put("clipId",clip.id).put("bones",bones));return project;
    }
    private JSONObject bone(String id,String parent,double x,double y,double ex,double ey)throws Exception{
        return new JSONObject().put("id",id).put("name",id).put("parentId",parent).put("x",x).put("y",y).put("endX",ex).put("endY",ey);
    }
    private JSONObject rig(ProjectStore.Project project)throws Exception{return project.clip("clip").effects.getJSONObject("rig2d");}
    private void key(ProjectStore.Project project,String bone,long at,double rotation)throws Exception{
        AnimationRigEdits.apply(project,"set_keyframe",new JSONObject().put("clipId","clip").put("keyframe",new JSONObject().put("boneId",bone).put("atMs",at).put("rotation",rotation)));
    }
    private void ik(ProjectStore.Project project,double x,double y)throws Exception{
        AnimationRigEdits.apply(project,"set_ik",new JSONObject().put("clipId","clip").put("ik",new JSONObject().put("id","hand").put("rootBoneId","upper").put("childBoneId","lower").put("targetX",x).put("targetY",y).put("bend",1).put("mix",1)));
    }

    @Test public void inverseBindSkinningPreservesEveryRestVertex()throws Exception{
        AnimationRig2D.Frame frame=AnimationRig2D.compile(rig(project())).sample(0);
        assertEquals(256,frame.positions.length/2);assertEquals(450,frame.triangles.length/3);
        for(int index=0;index<frame.positions.length;index++)assertEquals(frame.textureUvs[index],frame.positions[index],.00001f);
    }
    @Test public void parentRotationCarriesChildHeadAndTail()throws Exception{
        ProjectStore.Project project=project();AnimationRigEdits.apply(project,"set_pose",new JSONObject().put("clipId","clip").put("pose",new JSONObject().put("upper",new JSONObject().put("rotation",90))));
        AnimationRig2D.Frame frame=AnimationRig2D.compile(rig(project)).sample(0);
        assertEquals(.2,frame.bonePositions[4],.00001);assertEquals(.8,frame.bonePositions[5],.00001);
        assertEquals(.2,frame.bonePositions[6],.00001);assertEquals(1.1,frame.bonePositions[7],.00001);
    }
    @Test public void analyticIkReachesTargetWithAspectCorrectBones()throws Exception{
        ProjectStore.Project project=project();ik(project,.5,.8);AnimationRig2D.Frame frame=AnimationRig2D.compile(rig(project),2).sample(0);
        assertFalse(frame.ikClamped[0]);assertEquals(.5,frame.bonePositions[6],.00001);assertEquals(.8,frame.bonePositions[7],.00001);
    }
    @Test public void fkKeyDefaultsStayAuthoredBeforeIkWhileCapturedPoseRemainsSolved()throws Exception{
        ProjectStore.Project project=project();AnimationRigEdits.apply(project,"set_ik",new JSONObject().put("clipId","clip").put("ik",new JSONObject()
                .put("id","hand").put("rootBoneId","upper").put("childBoneId","lower").put("targetX",.2).put("targetY",1.1).put("bend",1).put("mix",.5)));
        AnimationRig2D compiled=AnimationRig2D.compileForClip(project.clip("clip"),1);
        double authored=compiled.authoredPoseClip(0).getJSONObject("upper").getDouble("rotation");
        double solved=compiled.sampleClip(0).poseJson().getJSONObject("upper").getDouble("rotation");
        assertEquals(0,authored,0);assertEquals(45,solved,.001);
        key(project,"upper",0,authored);
        assertEquals(solved,AnimationRig2D.compileForClip(project.clip("clip"),1).sampleClip(0).poseJson().getJSONObject("upper").getDouble("rotation"),.00001);
        AnimationRigEdits.apply(project,"capture_pose",new JSONObject().put("clipId","clip").put("poseId","solved").put("atMs",0));
        assertEquals(solved,rig(project).getJSONArray("poses").getJSONObject(0).getJSONObject("pose").getJSONObject("upper").getDouble("rotation"),.00001);
        key(project,"upper",1000,20);project.clip("clip").effects.put("animationDurationMs",1000).put("animationOffsetMs",500);
        assertEquals(10,AnimationRig2D.compileForClip(project.clip("clip"),1).authoredPoseClip(0).getJSONObject("upper").getDouble("rotation"),.00001);
        project.clip("clip").effects.put("animationOffsetMs",-500);
        assertEquals(0,AnimationRig2D.compileForClip(project.clip("clip"),1).authoredPoseClip(0).getJSONObject("upper").getDouble("rotation"),0);
    }
    @Test public void scalarGraphChannelsMatchAuthoredCurvesAndRawIkTargetsWithoutSilentUnknownFallback()throws Exception{
        ProjectStore.Project project=project();key(project,"upper",0,0);key(project,"upper",1000,90);ik(project,2,.5);
        AnimationRig2D compiled=AnimationRig2D.compile(rig(project));
        for(long at:new long[]{-1,0,500,1000,AnimationRig2D.MAX_TIME_MS+1}){
            JSONObject authored=compiled.authoredPose(at).getJSONObject("upper");
            for(String channel:new String[]{"rotation","x","y","scaleX","scaleY"})assertEquals(authored.getDouble(channel),compiled.authoredChannel("upper",channel,at),0);
            assertEquals(2,compiled.authoredIkChannel("hand","targetX",at),0);
            assertEquals(.5,compiled.authoredIkChannel("hand","targetY",at),0);
            assertEquals(1,compiled.authoredIkChannel("hand","mix",at),0);
        }
        assertThrows(IllegalArgumentException.class,()->compiled.authoredChannel("missing","rotation",0));
        assertThrows(IllegalArgumentException.class,()->compiled.authoredChannel("upper","translateX",0));
        assertThrows(IllegalArgumentException.class,()->compiled.authoredIkChannel("missing","mix",0));
        assertThrows(IllegalArgumentException.class,()->compiled.authoredIkChannel("hand","rotation",0));
        assertArrayEquals(new double[]{.05,20},AnimationRig2D.channelBounds("bone","scaleX"),0);
        assertArrayEquals(new double[]{0,1},AnimationRig2D.channelBounds("ik","mix"),0);
        assertThrows(IllegalArgumentException.class,()->AnimationRig2D.channelBounds("unknown","mix"));
    }
    @Test public void fixedKeyInfluenceMatchesActualCurveEditsBeforeClampingIncludingBezierOvershoot()throws Exception{
        JSONObject source=rig(project());source.put("keyframes",new JSONArray()
                .put(new JSONObject().put("boneId","upper").put("atMs",1000).put("scaleX",10).put("ease","cubic_bezier").put("bezier",new JSONArray().put(.5).put(4).put(.5).put(4)))
                .put(new JSONObject().put("boneId","upper").put("atMs",2000).put("scaleX",20)));
        AnimationRig2D original=AnimationRig2D.compile(source);JSONObject changed=new JSONObject(source.toString());
        changed.getJSONArray("keyframes").getJSONObject(1).put("scaleX",10);AnimationRig2D edited=AnimationRig2D.compile(changed);
        for(long at:new long[]{0,500,1000,1500,2000,3000}){
            AnimationRig2D.ChannelSample sample=original.authoredChannelSample("upper","scaleX",at,2000);
            assertEquals(edited.authoredChannel("upper","scaleX",at),Math.max(.05,Math.min(20,sample.rawValue+sample.keyInfluence*(10-20))),.00001);
        }
        AnimationRig2D.ChannelSample overshoot=original.authoredChannelSample("upper","scaleX",1500,2000);
        assertTrue(overshoot.rawValue>20);assertTrue(overshoot.keyInfluence>1);
        assertEquals(.5,original.authoredChannelSample("upper","scaleX",500,1000).keyInfluence,0);
        assertEquals(0,original.authoredChannelSample("upper","scaleX",500,-1).keyInfluence,0);
        assertThrows(IllegalArgumentException.class,()->original.authoredChannelSample("upper","rotation",500,1000));
        assertThrows(IllegalArgumentException.class,()->original.authoredChannelSample("upper","scaleX",500,1500));
        ProjectStore.Project project=project();ik(project,2,.5);JSONObject withIk=rig(project);
        withIk.getJSONArray("ik").getJSONObject(0).put("keyframes",new JSONArray()
                .put(new JSONObject().put("atMs",1000).put("targetX",1))
                .put(new JSONObject().put("atMs",2000).put("targetX",2)));
        AnimationRig2D ikOriginal=AnimationRig2D.compile(withIk);JSONObject ikChanged=new JSONObject(withIk.toString());
        ikChanged.getJSONArray("ik").getJSONObject(0).getJSONArray("keyframes").getJSONObject(1).put("targetX",0);
        AnimationRig2D ikEdited=AnimationRig2D.compile(ikChanged);
        AnimationRig2D.ChannelSample ikSample=ikOriginal.authoredIkChannelSample("hand","targetX",1500,2000);
        assertEquals(ikEdited.authoredIkChannel("hand","targetX",1500),Math.max(-2,Math.min(3,ikSample.rawValue+ikSample.keyInfluence*(0-2))),.00001);
        assertThrows(IllegalArgumentException.class,()->ikOriginal.authoredIkChannelSample("hand","mix",1500,2000));
    }
    @Test public void unreachableIkClampsReachAndReportsIt()throws Exception{
        ProjectStore.Project project=project();ik(project,2,.5);AnimationRig2D.Frame frame=AnimationRig2D.compile(rig(project)).sample(0);
        assertTrue(frame.ikClamped[0]);assertEquals(.8,frame.bonePositions[6],.00001);assertEquals(.5,frame.bonePositions[7],.00001);
    }
    @Test public void splitAndHeadExtensionUseSharedSignedAuthoredClock()throws Exception{
        ProjectStore.Project project=project();key(project,"upper",0,0);key(project,"upper",1000,90);ProjectStore.Clip clip=project.clip("clip");
        clip.effects.put("animationDurationMs",1000).put("animationOffsetMs",500);
        assertEquals(45,AnimationRig2D.compileForClip(clip,1).sampleClip(0).poseJson().getJSONObject("upper").getDouble("rotation"),.00001);
        clip.effects.put("animationOffsetMs",-500);AnimationRig2D compiled=AnimationRig2D.compileForClip(clip,1);
        assertEquals(0,compiled.sampleClip(0).poseJson().getJSONObject("upper").getDouble("rotation"),0);
        assertEquals(22.5,compiled.sampleClip(750).poseJson().getJSONObject("upper").getDouble("rotation"),.00001);
        assertEquals(0,compiled.clipAuthoredTimeMs(0));assertEquals(250,compiled.clipAuthoredTimeMs(750));
        assertEquals(1000,compiled.clipAuthoredTimeMs(5000));
        assertEquals(22.5,compiled.authoredChannelClip("upper","rotation",750),.00001);
    }
    @Test public void retimeCollisionMergesSparseChannelsRatherThanDroppingThem()throws Exception{
        ProjectStore.Project project=project();JSONObject rig=rig(project);rig.put("keyframes",new JSONArray()
                .put(new JSONObject().put("boneId","upper").put("atMs",1001).put("rotation",20))
                .put(new JSONObject().put("boneId","upper").put("atMs",1000).put("x",.1)));
        JSONObject retimed=AnimationRig2D.retime(rig,.001);JSONArray keys=retimed.getJSONArray("keyframes");
        assertEquals(1,keys.length());assertEquals(1,keys.getJSONObject(0).getLong("atMs"));
        assertEquals(20,keys.getJSONObject(0).getDouble("rotation"),0);assertEquals(.1,keys.getJSONObject(0).getDouble("x"),0);
    }
    @Test public void retimeCollisionKeepsLaterImplicitLinearEasing()throws Exception{
        JSONObject source=rig(project());source.put("keyframes",new JSONArray()
                .put(new JSONObject().put("boneId","upper").put("atMs",1000).put("x",.1).put("ease","cubic_bezier").put("bezier",new JSONArray().put(.3).put(2).put(.7).put(2)))
                .put(new JSONObject().put("boneId","upper").put("atMs",1001).put("rotation",20)));
        JSONObject key=AnimationRig2D.retime(source,.001).getJSONArray("keyframes").getJSONObject(0);
        assertEquals("linear",key.getString("ease"));assertFalse(key.has("bezier"));
    }
    @Test public void jointDragPreservesConnectedIkAndPaintedWeightsAtomically()throws Exception{
        ProjectStore.Project project=project();ik(project,.5,.8);
        String weights=rig(project).getJSONObject("mesh").getJSONArray("vertices").getJSONObject(0).getJSONArray("influences").toString();
        AnimationRigEdits.apply(project,"set_bone",new JSONObject().put("clipId","clip").put("autoWeights",false).put("preserveConnections",true)
                .put("bone",new JSONObject().put("id","upper").put("endX",.4).put("endY",.6)));
        JSONObject source=rig(project);JSONObject child=source.getJSONArray("bones").getJSONObject(1);
        assertEquals(.4,child.getDouble("x"),0);assertEquals(.6,child.getDouble("y"),0);
        assertEquals(weights,source.getJSONObject("mesh").getJSONArray("vertices").getJSONObject(0).getJSONArray("influences").toString());
    }
    @Test public void sampledIkIdsFollowEvaluationOrderRatherThanAuthorArrayOrder()throws Exception{
        ProjectStore.Project project=project();
        AnimationRigEdits.apply(project,"set_bone",new JSONObject().put("clipId","clip").put("bone",bone("otherUpper","",.1,.1,.4,.1)));
        AnimationRigEdits.apply(project,"set_bone",new JSONObject().put("clipId","clip").put("bone",bone("otherLower","otherUpper",.4,.1,.7,.1)));
        AnimationRigEdits.apply(project,"set_ik",new JSONObject().put("clipId","clip").put("ik",new JSONObject().put("id","other").put("rootBoneId","otherUpper").put("childBoneId","otherLower").put("targetX",.4).put("targetY",.4)));
        ik(project,.5,.8);AnimationRig2D.Frame frame=AnimationRig2D.compile(rig(project)).sample(0);
        assertArrayEquals(new String[]{"hand","other"},frame.ikIds);
        assertEquals("other",rig(project).getJSONArray("ik").getJSONObject(0).getString("id"));
    }
    @Test public void disabledIkAllowsFkStretchAndCubicScaleOvershootClamps()throws Exception{
        ProjectStore.Project project=project();AnimationRigEdits.apply(project,"set_ik",new JSONObject().put("clipId","clip").put("ik",new JSONObject()
                .put("id","hand").put("rootBoneId","upper").put("childBoneId","lower").put("targetX",.5).put("targetY",.8).put("mix",0)));
        JSONObject source=rig(project);source.put("keyframes",new JSONArray()
                .put(new JSONObject().put("boneId","upper").put("atMs",0).put("scaleX",1).put("ease","cubic_bezier").put("bezier",new JSONArray().put(.5).put(4).put(.5).put(4)))
                .put(new JSONObject().put("boneId","upper").put("atMs",1000).put("scaleX",20)));
        assertEquals(20,AnimationRig2D.compile(source).sample(500).poseJson().getJSONObject("upper").getDouble("scaleX"),0);
    }
    @Test public void excessiveHierarchicalScaleRejectsBeforeAnyClipMutation()throws Exception{
        ProjectStore.Project project=project();JSONArray bones=new JSONArray();JSONObject pose=new JSONObject();
        for(int index=0;index<8;index++){String id="bone"+index;bones.put(bone(id,index==0?"":"bone"+(index-1),.1,.1,.2,.1));pose.put(id,new JSONObject().put("scaleX",20).put("scaleY",20));}
        JSONObject candidate=new JSONObject(rig(project).toString()).put("bones",bones).put("pose",pose);JSONArray vertices=candidate.getJSONObject("mesh").getJSONArray("vertices");
        for(int index=0;index<vertices.length();index++)vertices.getJSONObject(index).put("influences",new JSONArray().put(new JSONObject().put("boneId","bone7").put("weight",1)));
        String original=rig(project).toString();assertThrows(IllegalArgumentException.class,()->AnimationRigEdits.apply(project,"apply_rig",new JSONObject().put("clipId","clip").put("rig",candidate)));
        assertEquals(original,rig(project).toString());
    }
    @Test public void cyclesAndUnnormalizedWeightsFailClosed()throws Exception{
        JSONObject source=rig(project());JSONObject cyclic=new JSONObject(source.toString());cyclic.getJSONArray("bones").getJSONObject(0).put("parentId","lower");
        assertThrows(IllegalArgumentException.class,()->AnimationRig2D.compile(cyclic));
        JSONObject weights=new JSONObject(source.toString());weights.getJSONObject("mesh").getJSONArray("vertices").getJSONObject(0)
                .put("influences",new JSONArray().put(new JSONObject().put("boneId","upper").put("weight",.25)));
        assertThrows(IllegalArgumentException.class,()->AnimationRig2D.compile(weights));
    }
    @Test public void locksAndRejectedBoneEditLeaveOriginalRigUnchanged()throws Exception{
        ProjectStore.Project project=project();String original=rig(project).toString();project.track("video").locked=true;
        assertThrows(IllegalStateException.class,()->AnimationRigEdits.apply(project,"clear_rig",new JSONObject().put("clipId","clip")));
        project.track("video").locked=false;
        assertThrows(IllegalArgumentException.class,()->AnimationRigEdits.apply(project,"set_bone",new JSONObject().put("clipId","clip").put("bone",new JSONObject().put("id","upper").put("parentId","lower"))));
        assertEquals(original,rig(project).toString());
    }
}
