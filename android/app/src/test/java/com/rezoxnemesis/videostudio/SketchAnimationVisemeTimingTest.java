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
public final class SketchAnimationVisemeTimingTest {
    private JSONObject source()throws Exception{
        JSONObject document=SketchDocument.create(new JSONObject().put("sketchId","speech_source")
            .put("width",320).put("height",480).put("frameCount",1));
        JSONArray art=document.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        for(String id:new String[]{"closed","open","round","teeth"}){
            art.put(SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","ellipse")
                .put("bounds",new JSONObject().put("x",.48).put("y",.27).put("width",.04).put("height",.02))));
        }
        return document;
    }
    private JSONObject program(JSONObject source)throws Exception{
        JSONObject value=SketchAnimationUiModel.createProgram(source).put("frameCount",48);
        value.getJSONArray("dialogues").put(new JSONObject().put("start",4).put("end",35).put("text","My brave voice will rise.")
            .put("layerId","layer_1").put("bounds",new JSONObject().put("x",.05).put("y",.81).put("width",.9).put("height",.14)));
        return value;
    }
    @Test public void textMouthTimingHasExactlyOneMouthPerSpeakingFrameAndNoOutsideLeak()throws Exception {
        JSONObject source=source(),input=program(source);
        JSONObject out=SketchAnimationVisemeTiming.apply(source,input,0,"closed","open","round","teeth");
        assertFalse(input.has("hiddenArtwork"));
        JSONArray ranges=out.getJSONArray("mouthCels");assertTrue(ranges.length()>3);
        int[] coverage=new int[48];
        for(int i=0;i<ranges.length();i++){
            JSONObject r=ranges.getJSONObject(i);
            for(int f=r.getInt("start");f<=r.getInt("end");f++)coverage[f]++;
        }
        for(int f=0;f<48;f++)assertEquals("frame "+f,f>=4&&f<=35?1:0,coverage[f]);
        JSONObject compiled=SketchAnimationCompiler.compile(source,out,"speech_baked");
        JSONObject baked=compiled.getJSONObject("document");
        for(String id:new String[]{"closed","open","round","teeth"}){
            for(int f:new int[]{0,3,36,47}){
                JSONObject preview=SketchAnimationCompiler.preview(source,out,f,"speech_preview").getJSONObject("document");
                try{assertNotNull(SketchDocument.object(source,id));}
                catch(Exception e){fail(e.toString());}
                JSONArray rendered=preview.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
                for(int j=0;j<rendered.length();j++)
                    assertFalse("Mouth must be hidden outside dialogue",id.equals(rendered.getJSONObject(j).getString("id")));
            }
        }
        assertTrue(baked.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length()>2);
        assertEquals(4,compiled.getJSONObject("report").getInt("hiddenArtworkCount"));
    }
    @Test public void duplicateMouthIdAndOverlappingDialogueRejectWithoutMutation()throws Exception {
        JSONObject s=source(),p=program(s);String baseline=SketchDocument.canonical(p);
        try{SketchAnimationVisemeTiming.apply(s,p,0,"closed","open","open","teeth");fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("different"));}
        assertEquals(baseline,SketchDocument.canonical(p));
        JSONObject applied=SketchAnimationVisemeTiming.apply(s,p,0,"closed","open","round","teeth");
        try{SketchAnimationVisemeTiming.apply(s,applied,0,"closed","open","round","teeth");fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("already speaks"));}
    }
    @Test public void celsRequirePreparedVectorGeometry()throws Exception {
        JSONObject s=source(),p=program(s);
        s.getJSONArray("layers").getJSONObject(0).getJSONArray("objects")
            .put(SketchEditOps.completeObject(new JSONObject().put("id","flat_text").put("kind","text")
                .put("text","x").put("bounds",new JSONObject().put("x",.1).put("y",.1).put("width",.2).put("height",.1))));
        try{SketchAnimationVisemeTiming.apply(s,p,0,"flat_text","open","round","teeth");fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("vector"));}
    }
}
