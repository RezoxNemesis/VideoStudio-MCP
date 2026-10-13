package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** Shared schema/clock assertions; JVM evidence does not establish native GPU execution. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class Cinematic3DTimelineTest {
    static JSONObject shot(String id,String action,int frames,int hold)throws Exception {
        return new JSONObject().put("id",id).put("action",action).put("durationFrames",frames).put("hitStopFrames",hold)
            .put("camera",new JSONObject().put("mode","wide").put("fovDegrees",57).put("shakeIntensity",.4))
            .put("vfx",new JSONObject().put("sparkBurst",true).put("slashTrail",true).put("rubble",false));
    }
    static JSONObject timeline()throws Exception {
        return new JSONObject().put("schema","videostudio.cinematic3d.timeline.v1").put("name","Synthetic cinematic")
            .put("fps",30).put("motionProvider","procedural_mannequin").put("shots",new JSONArray().put(shot("clash","CLASH",30,3)));
    }
    static Object call(String method,Class<?>[] types,Object...args)throws Exception {
        try { return Class.forName("com.rezoxnemesis.videostudio.Cinematic3DTimeline").getMethod(method,types).invoke(null,args); }
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw e;}
    }
    @Test public void integerClockHoldsExactlyDeclaredFramesAndNeverSamplesEndpoint()throws Exception {
        JSONObject t=(JSONObject)call("validate",new Class<?>[]{JSONObject.class},timeline());
        JSONObject compiled=(JSONObject)call("compile",new Class<?>[]{JSONObject.class},t);
        assertEquals(30,compiled.getInt("frameCount"));assertEquals(1,compiled.getDouble("durationSeconds"),0);
        JSONObject shot=t.getJSONArray("shots").getJSONObject(0);int holds=0;double held=-1;
        for(int frame=0;frame<30;frame++){
            JSONObject sample=(JSONObject)call("sampleShot",new Class<?>[]{JSONObject.class,int.class,int.class},shot,frame,30);
            if(sample.getBoolean("hitStop")){holds++;if(held<0)held=sample.getDouble("motionSeconds");else assertEquals(held,sample.getDouble("motionSeconds"),0);}
            if(frame==29)assertTrue(sample.getDouble("phase")<1);
        }
        assertEquals(3,holds);
    }
    @Test public void rejectsUnsafeUnknownNonintegerDuplicateAndOverBudgetTimelines()throws Exception {
        JSONObject bad=timeline().put("fps",29);reject(bad);
        bad=timeline().put("extra",1);reject(bad);
        bad=timeline();bad.getJSONArray("shots").getJSONObject(0).put("durationFrames",30.5);reject(bad);
        bad=timeline();bad.getJSONArray("shots").put(shot("clash","CLASH",30,2));reject(bad);
        bad=timeline();bad.getJSONArray("shots").getJSONObject(0).put("action","CHARGE");reject(bad);
        bad=timeline();bad.getJSONArray("shots").getJSONObject(0).put("id","../escape");reject(bad);
        bad=timeline();JSONArray shots=new JSONArray();for(int i=0;i<21;i++)shots.put(shot("shot_"+i,"CHARGE",600,0));bad.put("shots",shots);reject(bad);
    }
    @Test public void orderedRepeatedSourcesRemainLiteralOccurrences()throws Exception {
        JSONObject t=timeline();JSONArray refs=new JSONArray();String hash=new String(new char[64]).replace('\0','a');
        refs.put(new JSONObject().put("assetId","source_a").put("sha256",hash));refs.put(new JSONObject().put("assetId","source_b").put("sha256",hash));refs.put(new JSONObject().put("assetId","source_a").put("sha256",hash));
        t.getJSONArray("shots").getJSONObject(0).put("sourceAnchors",refs);
        JSONObject accepted=(JSONObject)call("validate",new Class<?>[]{JSONObject.class},t);
        assertEquals(refs.toString(),accepted.getJSONArray("shots").getJSONObject(0).getJSONArray("sourceAnchors").toString());
    }
    @Test public void expandedActionsModelsAndStylesAreBoundedAndPreserveLegacyAbsence()throws Exception {
        JSONObject legacy=Cinematic3DTimeline.validate(timeline());assertFalse(legacy.has("actors"));
        for(String action:new String[]{"IDLE","WALK","RUN","JUMP","DODGE","DUCK","PUNCH","KICK","DANCE","WAVE"}) {
            JSONObject t=timeline();t.getJSONArray("shots").getJSONObject(0).put("action",action).put("hitStopFrames",0)
                .put("motionStyle",new JSONObject().put("preset","anime").put("intensity",.8));
            JSONObject black=new JSONObject().put("preset","chibi").put("headScale",1.1),white=new JSONObject().put("preset","robot");
            t.put("actors",new JSONObject().put("black",black).put("white",white));
            JSONObject accepted=Cinematic3DTimeline.validate(t);assertEquals("chibi",accepted.getJSONObject("actors").getJSONObject("black").getString("preset"));
            assertEquals("anime",accepted.getJSONArray("shots").getJSONObject(0).getJSONObject("motionStyle").getString("preset"));
            black.put("heightScale",10);reject(t);black.remove("heightScale");black.put("command","blender");reject(t);
        }
    }
    @Test public void allPythonGoldenPhasesAndCameraSamplesMatchAt24_30_60Fps()throws Exception {
        java.nio.file.Path fixture=null;for(java.nio.file.Path root=Paths.get("").toAbsolutePath();root!=null;root=root.getParent()){java.nio.file.Path candidate=root.resolve("tests/cinematic3d/fixtures/timeline_clock_golden.json");if(Files.isRegularFile(candidate)){fixture=candidate;break;}}
        assertNotNull("The shared Python clock golden fixture must be present",fixture);
        JSONArray cases=new JSONObject(new String(Files.readAllBytes(fixture),StandardCharsets.UTF_8)).getJSONArray("cases");
        assertEquals(12,cases.length());for(int i=0;i<cases.length();i++){
            JSONObject row=cases.getJSONObject(i);JSONArray samples=row.getJSONArray("samples");
            for(int j=0;j<samples.length();j++){
                JSONObject expected=samples.getJSONObject(j),actual=(JSONObject)call("sampleShot",new Class<?>[]{JSONObject.class,int.class,int.class},row.getJSONObject("shot"),expected.getInt("frame"),row.getInt("fps"));
                assertEquals(expected.getDouble("phase"),actual.getDouble("phase"),1e-9);assertEquals(expected.getDouble("motionSeconds"),actual.getDouble("motionSeconds"),1e-9);
                assertEquals(expected.getBoolean("hitStop"),actual.getBoolean("hitStop"));assertEquals(expected.getInt("impactFrame"),actual.getInt("impactFrame"));
                JSONObject camera=expected.getJSONObject("camera"),found=actual.getJSONObject("camera");for(String key:new String[]{"position","target"})for(int k=0;k<3;k++)assertEquals(camera.getJSONArray(key).getDouble(k),found.getJSONArray(key).getDouble(k),1e-9);
                assertEquals(camera.getDouble("fovDegrees"),found.getDouble("fovDegrees"),0);
                if(camera.has("up"))assertEquals(camera.getJSONArray("up").toString(),found.getJSONArray("up").toString());
            }
        }
    }
    @Test public void repeatedControlValidationKeepsBakedQuaternionBytesStable()throws Exception {
        java.util.Random values=new java.util.Random(173);for(int trial=0;trial<100;trial++){
            JSONArray rotations=new JSONArray();for(int j=0;j<16;j++)rotations.put(new JSONArray().put(values.nextDouble()).put(values.nextDouble()).put(values.nextDouble()).put(values.nextDouble()));
            JSONObject actor=new JSONObject().put("root",new JSONArray().put(0).put(0).put(0)).put("rotations",rotations);
            JSONObject t=timeline();t.getJSONArray("shots").getJSONObject(0).put("poseKeyframes",new JSONArray().put(new JSONObject().put("phase",0).put("actors",new JSONObject().put("black",actor).put("white",new JSONObject(actor.toString())))));
            JSONObject first=(JSONObject)call("validate",new Class<?>[]{JSONObject.class},t),second=(JSONObject)call("validate",new Class<?>[]{JSONObject.class},first);
            assertEquals("Camera/FPS controls must retain exact baked pose inputs",first.toString(),second.toString());
        }
    }
    @Test public void reverseChronologicalSameFootContactsNeverAcquirePlanningProof()throws Exception {
        JSONObject t=timeline();JSONArray contacts=new JSONArray();for(double start:new double[]{.6,.1})contacts.put(new JSONObject().put("actor","black").put("foot","leading").put("startPhase",start).put("endPhase",start+.1).put("anchor",new JSONArray().put(0).put(0).put(0)));
        t.getJSONArray("shots").getJSONObject(0).put("footContacts",contacts);reject(t);
    }
    static JSONArray vector(double... values)throws Exception {JSONArray out=new JSONArray();for(double value:values)out.put(value);return out;}
    static JSONObject cameraKey(double phase,double[] position,double[] target,double[] up)throws Exception {
        JSONObject key=new JSONObject().put("phase",phase).put("position",vector(position)).put("target",vector(target));
        if(up!=null)key.put("up",vector(up));return key;
    }
    static JSONObject cameraTrack(JSONArray keys)throws Exception {
        JSONObject t=timeline();t.getJSONArray("shots").getJSONObject(0).getJSONObject("camera").put("keyframes",keys).put("shakeIntensity",0);return t;
    }
    @Test public void authoredCameraTrackInterpolatesWallTimeDuringActorImpactHold()throws Exception {
        JSONArray keys=new JSONArray().put(cameraKey(0,new double[]{2,2,6},new double[]{0,1,0},new double[]{0,1,0}))
            .put(cameraKey(1,new double[]{8,2,6},new double[]{3,1,0},new double[]{0,1,1}));
        JSONObject t=(JSONObject)call("validate",new Class<?>[]{JSONObject.class},cameraTrack(keys));JSONObject shot=t.getJSONArray("shots").getJSONObject(0);
        for(int frame:new int[]{0,13,14,15,29}){
            JSONObject sample=(JSONObject)call("sampleShot",new Class<?>[]{JSONObject.class,int.class,int.class},shot,frame,30),camera=sample.getJSONObject("camera");double p=frame/30.0;
            assertEquals(2+6*p,camera.getJSONArray("position").getDouble(0),1e-12);assertEquals(3*p,camera.getJSONArray("target").getDouble(0),1e-12);assertEquals(p,camera.getJSONArray("up").getDouble(2),1e-12);
            if(frame>=13&&frame<=15){assertTrue(sample.getBoolean("hitStop"));assertEquals(.5,sample.getDouble("phase"),0);}
        }
    }
    @Test public void authoredCameraClampsKeyRangeAndRetainsLiteralOptionalUp()throws Exception {
        JSONArray keys=new JSONArray().put(cameraKey(.25,new double[]{2,2,6},new double[]{0,1,0},null))
            .put(cameraKey(.75,new double[]{8,2,6},new double[]{0,1,0},new double[]{0,1,1}));
        JSONObject accepted=(JSONObject)call("validate",new Class<?>[]{JSONObject.class},cameraTrack(keys));JSONObject shot=accepted.getJSONArray("shots").getJSONObject(0);
        assertFalse(shot.getJSONObject("camera").getJSONArray("keyframes").getJSONObject(0).has("up"));
        JSONObject first=((JSONObject)call("sampleShot",new Class<?>[]{JSONObject.class,int.class,int.class},shot,0,30)).getJSONObject("camera");
        JSONObject last=((JSONObject)call("sampleShot",new Class<?>[]{JSONObject.class,int.class,int.class},shot,29,30)).getJSONObject("camera");
        assertEquals(2,first.getJSONArray("position").getDouble(0),0);assertEquals(8,last.getJSONArray("position").getDouble(0),0);assertEquals(1,first.getJSONArray("up").getDouble(1),0);
    }
    @Test public void rejectsEmptyUnorderedUnknownOrDegenerateCameraKeys()throws Exception {
        reject(cameraTrack(new JSONArray()));
        reject(cameraTrack(new JSONArray().put(cameraKey(.7,new double[]{2,2,6},new double[]{0,1,0},null)).put(cameraKey(.2,new double[]{4,2,6},new double[]{0,1,0},null))));
        reject(cameraTrack(new JSONArray().put(cameraKey(.2,new double[]{2,2,6},new double[]{0,1,0},null)).put(cameraKey(.2,new double[]{4,2,6},new double[]{0,1,0},null))));
        reject(cameraTrack(new JSONArray().put(cameraKey(0,new double[]{2,2,6},new double[]{0,1,0},null).put("executable","forbidden"))));
        reject(cameraTrack(new JSONArray().put(cameraKey(0,new double[]{0,1,0},new double[]{0,1,0},null))));
        reject(cameraTrack(new JSONArray().put(cameraKey(0,new double[]{2,2,6},new double[]{0,1,0},new double[]{0,0,0}))));
        reject(cameraTrack(new JSONArray().put(cameraKey(0,new double[]{0,2,0},new double[]{0,1,0},new double[]{0,1,0}))));
        reject(cameraTrack(new JSONArray().put(cameraKey(0,new double[]{10000.1,2,6},new double[]{0,1,0},null))));
        reject(cameraTrack(new JSONArray().put(cameraKey(0,new double[]{2,2,6},new double[]{0,1,0},new double[]{0,10000.1,0}))));
        reject(cameraTrack(new JSONArray().put(cameraKey(0,new double[]{10000,1,0},new double[]{10000,1,.001},null))));
        JSONArray excess=new JSONArray();for(int i=0;i<129;i++)excess.put(cameraKey(i/128.0,new double[]{2,2,6},new double[]{0,1,0},null));reject(cameraTrack(excess));
    }
    @Test public void interpolatedCameraBasisMustRemainFiniteAndNondegenerate()throws Exception {
        double[][][] pairs={
            {{0,1,0},{0,1,-1},{0,1,0},{0,1,0},{0,1,1},{0,1,0}},
            {{2,2,6},{0,1,0},{0,1,0},{2,2,6},{0,1,0},{0,-1,0}},
            {{0,0,0},{1,0,-1},{0,1,-1},{0,0,0},{-1,0,-1},{0,-1,-1}}
        };
        for(double[][] pair:pairs){
            JSONArray keys=new JSONArray().put(cameraKey(0,pair[0],pair[1],pair[2])).put(cameraKey(1,pair[3],pair[4],pair[5]));
            JSONObject t=cameraTrack(keys),shot=t.getJSONArray("shots").getJSONObject(0);shot.put("action","CHARGE").put("hitStopFrames",0);call("validate",new Class<?>[]{JSONObject.class},t);
            try{call("sampleShot",new Class<?>[]{JSONObject.class,int.class,int.class},shot,15,30);fail("Degenerate interpolated camera basis admitted");}catch(IllegalArgumentException expected){}
        }
    }
    private void reject(JSONObject t)throws Exception {try{call("validate",new Class<?>[]{JSONObject.class},t);fail("Malformed timeline admitted");}catch(IllegalArgumentException expected){}}
}
