package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class CinematicExactOrbitTest {
 static JSONObject orbit(double start,double end,double low,double high)throws Exception{return new JSONObject().put("target",new JSONArray(new double[]{.25,1.1,-.5})).put("radius",5.2).put("startAzimuthDegrees",start).put("endAzimuthDegrees",end).put("startElevationDegrees",low).put("endElevationDegrees",high);}
 static JSONObject shot(JSONObject orbit)throws Exception{JSONObject shot=Cinematic3DTimelineTest.shot("angle_study","IDLE",361,0);shot.getJSONObject("camera").put("mode","orbit").put("shakeIntensity",0).put("orbit",orbit);return shot;}
 @Test public void requestedAnglesHaveAnExactRadiusAndCloseTheLoopWithoutChangingActorTime()throws Exception{
  JSONObject shot=shot(orbit(0,360,0,0));String before=shot.toString();
  for(int angle:new int[]{0,25,30,40,45,60,65,70,75,90,120,150,180,210,240,270,300,330,360}){
   JSONObject sample=Cinematic3DTimeline.sampleShot(shot,angle,60),camera=sample.getJSONObject("camera");JSONArray p=camera.getJSONArray("position"),t=camera.getJSONArray("target");
   assertEquals(angle,camera.getDouble("azimuthDegrees"),0);assertEquals(5.2,Math.hypot(Math.hypot(p.getDouble(0)-t.getDouble(0),p.getDouble(1)-t.getDouble(1)),p.getDouble(2)-t.getDouble(2)),1e-12);
   assertEquals(5.2*Math.sin(Math.toRadians(angle)),p.getDouble(0)-t.getDouble(0),1e-12);assertEquals(5.2*Math.cos(Math.toRadians(angle)),p.getDouble(2)-t.getDouble(2),1e-12);
   assertEquals(angle/361d,sample.getDouble("phase"),0);
  }
  JSONObject a=Cinematic3DTimeline.sampleShot(shot,0,60).getJSONObject("camera"),b=Cinematic3DTimeline.sampleShot(shot,360,60).getJSONObject("camera");assertEquals(a.getJSONArray("position").toString(),b.getJSONArray("position").toString());assertEquals(a.getJSONArray("up").toString(),b.getJSONArray("up").toString());assertEquals(before,shot.toString());
 }
 @Test public void negativeTurnsAndBothPolesKeepAnOrthonormalCamera()throws Exception{
  JSONObject shot=shot(orbit(0,-720,-90,90));
  for(int f=0;f<=360;f++){
   JSONObject c=Cinematic3DTimeline.sampleShot(shot,f,60).getJSONObject("camera");JSONArray p=c.getJSONArray("position"),t=c.getJSONArray("target"),u=c.getJSONArray("up");double dot=0,length=0;
   for(int axis=0;axis<3;axis++){dot+=(t.getDouble(axis)-p.getDouble(axis))*u.getDouble(axis);length+=u.getDouble(axis)*u.getDouble(axis);}
   assertEquals(0,dot,1e-12);assertEquals(1,length,1e-12);assertEquals(-2*f,c.getDouble("azimuthDegrees"),1e-12);
  }
 }
 @Test public void exactOrbitsRejectCoercionUnknownFieldsConflictingKeysAndShake()throws Exception{
  for(Object bad:new Object[]{"5.2",JSONObject.NULL,-1,31}){JSONObject s=shot(orbit(0,360,0,0).put("radius",bad));assertThrows(Exception.class,()->Cinematic3DTimeline.sampleShot(s,0,60));}
  JSONObject unknown=shot(orbit(0,360,0,0).put("strength",1));assertThrows(Exception.class,()->Cinematic3DTimeline.sampleShot(unknown,0,60));
  JSONObject shake=shot(orbit(0,360,0,0));shake.getJSONObject("camera").put("shakeIntensity",.1);assertThrows(Exception.class,()->Cinematic3DTimeline.sampleShot(shake,0,60));
  JSONObject keys=shot(orbit(0,360,0,0));keys.getJSONObject("camera").put("keyframes",new JSONArray().put(new JSONObject().put("phase",0).put("position",new JSONArray(new double[]{0,1,5})).put("target",new JSONArray(new double[]{0,1,0}))));assertThrows(Exception.class,()->Cinematic3DTimeline.sampleShot(keys,0,60));
 }
}
