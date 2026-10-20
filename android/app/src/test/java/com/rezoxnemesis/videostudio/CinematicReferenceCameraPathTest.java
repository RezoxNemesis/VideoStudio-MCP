package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual 3D camera keyframes and Android timeline sampling, no GPU claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class CinematicReferenceCameraPathTest {
    @Test public void turntableIsAContinuousFullRevolutionWithIdenticalStartAndEnd()throws Exception{
        double[] center={0,0,-4};
        double base=Math.toRadians(24);
        CinematicReferenceCameraPath.Pose start=CinematicReferenceCameraPath.model(
                CinematicReferenceCameraPath.CAR_TURNTABLE,0,base,4.5,center);
        CinematicReferenceCameraPath.Pose end=CinematicReferenceCameraPath.model(
                CinematicReferenceCameraPath.CAR_TURNTABLE,1,base,4.5,center);
        assertEquals(base,start.azimuthRad,1e-9);
        assertEquals(base+2*Math.PI,end.azimuthRad,1e-9);
        for(int i=0;i<3;i++)assertEquals(start.position[i],end.position[i],1e-8);
        assertEquals(0,start.target[0],0);assertEquals(-4,start.target[2],0);
        double movement=0,lastAngle=start.azimuthRad;
        for(int i=1;i<=120;i++){
            CinematicReferenceCameraPath.Pose p=CinematicReferenceCameraPath.model(
                    CinematicReferenceCameraPath.CAR_TURNTABLE,i/120d,base,4.5,center);
            assertTrue(p.azimuthRad>=lastAngle-1e-9);
            movement+=p.azimuthRad-lastAngle;
            lastAngle=p.azimuthRad;
            assertTrue(p.distance>=2&&p.distance<=12);
        }
        assertEquals(2*Math.PI,movement,1e-9);
    }

    @Test public void allFivePathsCreateNative3dKeysAndPassEveryOutputFrame()throws Exception{
        for(String preset:CinematicReferenceCameraPath.presets()){
            JSONObject before=Cinematic3DStudioActivityTest.timeline();
            String identity=Cinematic3DTimeline.canonical(before);
            JSONArray refs=CinematicReferenceCameraPath.shotKeys(
                    preset,new double[]{0,1.1,0},5.2,.9);
            assertEquals(25,refs.length());
            assertEquals(0,refs.getJSONObject(0).getDouble("phase"),0);
            assertEquals(1,refs.getJSONObject(24).getDouble("phase"),0);
            JSONObject op=new JSONObject().put("type","reference_camera_path")
                    .put("shotId","impact").put("preset",preset)
                    .put("target",new JSONArray(new double[]{0,1.1,0}))
                    .put("radius",5.2).put("strength",.9);
            JSONObject updated=Cinematic3DCameraEditOps.apply(before,new JSONArray().put(op));
            assertEquals(identity,Cinematic3DTimeline.canonical(before));
            JSONArray shots=updated.getJSONArray("shots");
            JSONArray keys=shots.getJSONObject(1).getJSONObject("camera").getJSONArray("keyframes");
            assertEquals(25,keys.length());
            for(int i=0;i<keys.length();i++){
                assertEquals(i/24d,keys.getJSONObject(i).getDouble("phase"),0);
                assertEquals(3,keys.getJSONObject(i).getJSONArray("position").length());
            }
            int fps=updated.getInt("fps"),duration=shots.getJSONObject(1).getInt("durationFrames");
            JSONObject initial=null,finalFrame=null;
            for(int frame=0;frame<duration;frame++){
                JSONObject sampled=Cinematic3DTimeline.sampleShot(shots.getJSONObject(1),frame,fps);
                JSONObject camera=sampled.getJSONObject("camera");
                assertEquals(3,camera.getJSONArray("position").length());
                if(initial==null)initial=camera;
                finalFrame=camera;
            }
            assertNotNull(finalFrame);
            assertNotEquals(initial.getJSONArray("position").toString(),
                    finalFrame.getJSONArray("position").toString());
            // Full scene invariants: actor motion and VFX are NOT rewritten.
            assertEquals(before.getJSONArray("shots").getJSONObject(1).getString("action"),
                    updated.getJSONArray("shots").getJSONObject(1).getString("action"));
            assertEquals("Camera edit must preserve every authored VFX field and value",
                    Cinematic3DTimeline.canonical(before.getJSONArray("shots")
                            .getJSONObject(1).getJSONObject("vfx")),
                    Cinematic3DTimeline.canonical(updated.getJSONArray("shots")
                            .getJSONObject(1).getJSONObject("vfx")));
        }
    }

    @Test public void actionPathMoves3dPerspectiveButNeverClaimsActorPoseChanges(){
        double[] target={0,1.1,0};
        CinematicReferenceCameraPath.Pose a=CinematicReferenceCameraPath.scene(
                CinematicReferenceCameraPath.ACTION_ARC,.15,target,5,1);
        CinematicReferenceCameraPath.Pose b=CinematicReferenceCameraPath.scene(
                CinematicReferenceCameraPath.ACTION_ARC,.65,target,5,1);
        assertTrue(Math.abs(a.azimuthRad-b.azimuthRad)>.3);
        assertTrue(Math.abs(a.distance-b.distance)>.25);
        assertEquals(1.1,a.target[1],1e-9);
        assertEquals(1.1,b.target[1],1e-9);
    }

    @Test public void eachGLBReferencePathStartsAtExactlyTheExistingCameraView(){
        double[] focus={.15,.22,-4};
        double yaw=1.12,pitch=-17.5,distance=6.7;
        for(String preset:CinematicReferenceCameraPath.presets()){
            CinematicReferenceCameraPath.Pose before=
                    CinematicReferenceCameraPath.model(
                        preset,0,yaw,pitch,distance,focus);
            assertEquals(yaw,before.azimuthRad,1e-12);
            assertEquals(pitch,before.pitchDeg,1e-12);
            assertEquals(distance,before.distance,1e-12);
            for(int axis=0;axis<3;axis++)assertEquals(focus[axis],before.target[axis],1e-12);
            CinematicReferenceCameraPath.Pose after=
                    CinematicReferenceCameraPath.model(
                        preset,.45,yaw,pitch,distance,focus);
            assertTrue(Math.abs(after.azimuthRad-yaw)>1e-4);
        }
        CinematicReferenceCameraPath.Pose finish=CinematicReferenceCameraPath.model(
                CinematicReferenceCameraPath.CAR_TURNTABLE,1,yaw,pitch,distance,focus);
        assertEquals(yaw+2*Math.PI,finish.azimuthRad,1e-10);
        assertEquals(pitch,finish.pitchDeg,1e-10);
        assertEquals(distance,finish.distance,1e-10);
    }

    @Test public void malformedCameraPresetsCannotMutateSourceShot()throws Exception{
        JSONObject original=Cinematic3DStudioActivityTest.timeline();
        String before=Cinematic3DTimeline.canonical(original);
        String[] presets={"invalid","../outside",""};
        for(String preset:presets){
            try{Cinematic3DCameraEditOps.apply(original,new JSONArray().put(
                    new JSONObject().put("type","reference_camera_path")
                            .put("shotId","impact").put("preset",preset)
                            .put("target",new JSONArray(new double[]{0,1.1,0}))
                            .put("radius",5).put("strength",1)));
                fail("Unsupported cinematic preset");
            }catch(IllegalArgumentException expected){}
            assertEquals(before,Cinematic3DTimeline.canonical(original));
        }
        try{CinematicReferenceCameraPath.scene("car_turntable",.5,
                new double[]{Double.NaN,0,0},5,1);fail();}
        catch(IllegalArgumentException expected){}
        try{CinematicReferenceCameraPath.scene("car_turntable",.5,
                new double[]{0,0,0},.5,1);fail();}
        catch(IllegalArgumentException expected){}
        try{CinematicReferenceCameraPath.model("car_turntable",.5,0,12.5,
                new double[]{0,0,-4});fail();}
        catch(IllegalArgumentException expected){}
    }
}
