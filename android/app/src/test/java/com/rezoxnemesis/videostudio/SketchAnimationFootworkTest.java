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
public final class SketchAnimationFootworkTest {
    private JSONObject source()throws Exception{
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId","gait_source")
            .put("width",320).put("height",480).put("frameCount",1));
        JSONArray items=source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        for(String id:new String[]{"left_foot_art","right_foot_art","left_sleeve","right_sleeve"}){
            double x=id.contains("left")?.42:.55,y=id.contains("foot")?.87:.46;
            items.put(SketchEditOps.completeObject(new JSONObject().put("id",id)
                .put("kind","ellipse").put("bounds",new JSONObject()
                .put("x",x).put("y",y).put("width",.04).put("height",.045))));
        }
        SketchDocument.validate(source);
        return source;
    }
    private JSONObject rigged(JSONObject source,int start,int duration,String motion)throws Exception{
        JSONObject p=SketchAnimationUiModel.createProgram(source).put("frameCount",96);
        JSONArray bindings=SketchAnimationUiModel.rig(p,"character_1").getJSONArray("bindings");
        bindings.put(new JSONObject().put("objectId","left_foot_art").put("jointId","foot_left"))
                .put(new JSONObject().put("objectId","right_foot_art").put("jointId","foot_right"))
                .put(new JSONObject().put("objectId","left_sleeve").put("jointId","arm_left"))
                .put(new JSONObject().put("objectId","right_sleeve").put("jointId","arm_right"));
        return SketchAnimationMotionLibrary.insert(p,"character_1",start,duration,motion);
    }
    private static JSONObject joint(JSONObject sampled,String name)throws Exception{
        JSONArray parts=sampled.getJSONArray("joints");
        for(int i=0;i<parts.length();i++){
            JSONObject v=parts.getJSONObject(i);
            if("character_1".equals(v.getString("rigId"))&&name.equals(v.getString("jointId")))return v;
        }
        throw new AssertionError("Joint not found: "+name);
    }
    @Test public void authoredWalkGainsFourTrueContactIntervalsWithPixelAccurateSoles()throws Exception{
        JSONObject source=source(),input=rigged(source,8,48,"walk");
        String initial=SketchDocument.canonical(input),sourceHash=SketchDocument.canonical(source);
        JSONObject planted=SketchAnimationFootwork.plant(source,input,"character_1",8,48,"walk");
        assertEquals("Do not mutate the edited program",initial,SketchDocument.canonical(input));
        assertEquals("Do not rewrite source artwork",sourceHash,SketchDocument.canonical(source));
        JSONArray plants=SketchAnimationUiModel.rig(planted,"character_1").getJSONArray("ik");
        assertEquals(4,plants.length());
        JSONArray frames=SketchAnimationCompiler.sampleFrames(source,planted,8,55);
        int evidence=0;boolean left=false,right=false;
        for(int n=0;n<plants.length();n++){
            JSONObject p=plants.getJSONObject(n);String side=p.getString("ankle");
            if("foot_left".equals(side))left=true;else if("foot_right".equals(side))right=true;else fail(side);
            assertTrue("Hard plant has ramp entry",p.getInt("blendInFrames")>0);
            assertTrue("Hard plant has ramp exit",p.getInt("blendOutFrames")>0);
            for(int f=p.getInt("start");f<=p.getInt("end");f++){
                JSONObject ankle=joint(frames.getJSONObject(f-8),side);
                assertEquals("World-space planted X",p.getDouble("x"),ankle.getDouble("x"),1e-7);
                assertEquals("World-space planted Y",p.getDouble("y"),ankle.getDouble("y"),1e-7);
                assertEquals("Foot sole rotation locked",0,ankle.getDouble("rotation"),1e-6);
                evidence++;
            }
        }
        assertTrue(left&&right);assertTrue(evidence>=12);
        JSONObject result=SketchAnimationCompiler.compile(source,planted,"gait_baked");
        JSONObject report=result.getJSONObject("report");
        assertEquals(4,report.getInt("contactCount"));
        assertEquals(4,report.getInt("blendedContactCount"));
        assertTrue(report.getDouble("maxFootContactErrorPixels")<.02);
        SketchDocument.validate(result.getJSONObject("document"));
    }
    @Test public void contactRampBeginsAtUntouchedPoseAndEasesIntoHardLock()throws Exception{
        JSONObject source=source(),authored=rigged(source,8,48,"walk");
        JSONObject planted=SketchAnimationFootwork.plant(source,authored,"character_1",8,48,"walk");
        JSONArray contacts=SketchAnimationUiModel.rig(planted,"character_1").getJSONArray("ik");
        JSONObject first=contacts.getJSONObject(0);
        int entry=first.getInt("start")-first.getInt("blendInFrames");
        JSONObject without=SketchAnimationCompiler.sample(source,authored,entry),
                easing=SketchAnimationCompiler.sample(source,planted,entry);
        JSONObject unchanged=joint(without,"foot_left"),actual=joint(easing,"foot_left");
        assertEquals(unchanged.getDouble("x"),actual.getDouble("x"),1e-10);
        assertEquals(unchanged.getDouble("y"),actual.getDouble("y"),1e-10);
        assertEquals("Soft ramp must not be falsely reported as a hard contact",0,easing.getJSONArray("contactErrors").length());
        JSONObject locked=SketchAnimationCompiler.sample(source,planted,first.getInt("start"));
        assertEquals(first.getDouble("x"),joint(locked,"foot_left").getDouble("x"),1e-7);
        assertEquals(first.getDouble("y"),joint(locked,"foot_left").getDouble("y"),1e-7);
        assertEquals(1,locked.getJSONArray("contactErrors").length());
    }
    @Test public void runningFootworkCompilesWithoutConfusingCameraWithActualFootTargets()throws Exception{
        JSONObject s=source(),p=rigged(s,8,48,"run");
        p.put("camera",new JSONArray().put(new JSONObject().put("frame",0).put("zoom",1.15).put("rotation",7)));
        JSONObject ready=SketchAnimationFootwork.plant(s,p,"character_1",8,48,"run");
        assertTrue(SketchAnimationCompiler.compile(s,ready,"running_baked").getJSONObject("report")
                .getDouble("maxFootContactErrorPixels")<.02);
        JSONObject sample=SketchAnimationCompiler.sample(s,ready,20);
        JSONObject ankle=joint(sample,"foot_left");
        assertNotEquals("Contact uses world, not screen camera position",ankle.getDouble("displayX"),ankle.getDouble("x"),1e-4);
    }
    @Test public void unsupportedOrOccupiedContactsRejectWithoutChangingArtistWork()throws Exception{
        JSONObject s=source(),base=rigged(s,8,48,"walk"),missing=SketchDocument.copy(base);
        JSONArray bindings=SketchAnimationUiModel.rig(missing,"character_1").getJSONArray("bindings");
        JSONArray stripped=new JSONArray();for(int i=0;i<bindings.length();i++){
            JSONObject v=bindings.getJSONObject(i);if(!"foot_left".equals(v.getString("jointId")))stripped.put(v);
        }
        SketchAnimationUiModel.rig(missing,"character_1").put("bindings",stripped);
        try{SketchAnimationFootwork.plant(s,missing,"character_1",8,48,"walk");fail("A flat limb should not silently be planted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Bind"));}
        JSONObject planted=SketchAnimationFootwork.plant(s,base,"character_1",8,48,"walk"),snapshot=SketchDocument.copy(planted);
        try{SketchAnimationFootwork.plant(s,planted,"character_1",8,48,"walk");fail("Second pass must reject duplicate ownership");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("already owns"));}
        assertEquals(SketchDocument.canonical(snapshot),SketchDocument.canonical(planted));
        try{SketchAnimationFootwork.plant(s,base,"character_1",83,48,"walk");fail("Out of range planted animation admitted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("inside"));}
    }
}
