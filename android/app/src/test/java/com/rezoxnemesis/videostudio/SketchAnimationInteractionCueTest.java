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
public final class SketchAnimationInteractionCueTest {
    private JSONObject source()throws Exception{
        return SketchDocument.create(new JSONObject().put("sketchId","contact_source")
                .put("frameCount",1).put("width",320).put("height",480));
    }
    private JSONObject rig(String id,double x)throws Exception{
        return new JSONObject().put("id",id).put("joints",new JSONArray()
                .put(new JSONObject().put("id","root").put("x",x).put("y",.5))
                .put(new JSONObject().put("id","hand").put("parent","root").put("x",x+.05).put("y",.5)))
                .put("bindings",new JSONArray()).put("ik",new JSONArray())
                .put("poses",new JSONArray().put(new JSONObject().put("frame",0)
                        .put("rootX",0).put("rootY",0))
                        .put(new JSONObject().put("frame",9)
                                .put("rootX",0).put("rootY",0)
                                .put("rotations",new JSONObject().put("root",0))));
    }
    private JSONObject program()throws Exception{
        return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA)
                .put("fps",24).put("frameCount",10)
                .put("rigs",new JSONArray().put(rig("actorA",.3)).put(rig("actorB",.68)));
    }
    private JSONObject cue(int frame,double allowed)throws Exception{
        return new JSONObject().put("frame",frame).put("rigA","actorA").put("jointA","hand")
                .put("rigB","actorB").put("jointB","hand").put("maxGapPx",allowed)
                .put("description","Wrist-to-wrist staged contact");
    }
    @Test public void measuresTrueWorldDistanceOfTwoIndependentRigs()throws Exception{
        JSONObject source=source(),program=program();
        program.put("interactionCues",new JSONArray().put(cue(4,128)));
        String original=SketchDocument.canonical(program);
        JSONObject result=SketchAnimationCompiler.compile(source,program,"contact_result");
        JSONObject report=result.getJSONObject("report");
        assertEquals(1,report.getInt("interCharacterContactCount"));
        assertEquals(.38*320,report.getDouble("maxInterCharacterContactGapPixels"),1e-7);
        assertEquals(.38*320,report.getJSONArray("interCharacterContacts").getJSONObject(0).getDouble("gapPixels"),1e-7);
        assertEquals(original,SketchDocument.canonical(program));
    }
    @Test public void overlyDistantCharactersCannotPassThroughVisualSparkOnly()throws Exception{
        JSONObject source=source(),program=program().put("interactionCues",new JSONArray().put(cue(4,90)));
        String original=SketchDocument.canonical(program);
        try{SketchAnimationCompiler.compile(source,program,"invalid_contact");fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("INTERACTION_CONTACT_MISS"));}
        assertEquals(original,SketchDocument.canonical(program));
    }
    @Test public void mismatchedRigOrJointAndOversizedCueRejectBeforeAnimation()throws Exception{
        JSONObject s=source(),p=program();
        p.put("interactionCues",new JSONArray().put(cue(4,90).put("jointB","not_real")));
        assertInvalid(s,p,"Unknown joint");
        p=program();p.put("interactionCues",new JSONArray().put(cue(4,90).put("rigB","actorA")));
        assertInvalid(s,p,"distinct");
        p=program();p.put("interactionCues",new JSONArray().put(cue(10,90)));
        assertInvalid(s,p,"frame");
        p=program();p.put("interactionCues",new JSONArray().put(cue(4,900)));
        assertInvalid(s,p,"maxGapPx");
    }
    private void assertInvalid(JSONObject source,JSONObject program,String expectedText)throws Exception{
        try{SketchAnimationCompiler.compile(source,program,"invalid_contact");fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(expectedText));}
    }
}
