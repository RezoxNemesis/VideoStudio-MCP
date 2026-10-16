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
public final class SketchVoiceActivityTimingTest {
    private JSONObject source()throws Exception{
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","voice_source")
                .put("width",320).put("height",480).put("frameCount",1));
        JSONArray art=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        for(String id:new String[]{"closed","open","round","teeth"}){
            art.put(SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","ellipse")
                    .put("bounds",new JSONObject().put("x",.46).put("y",.26)
                            .put("width",.08).put("height",.03))));
        }
        return doc;
    }
    private JSONObject program(JSONObject s)throws Exception{
        JSONObject p=SketchAnimationUiModel.createProgram(s).put("frameCount",64);
        p.getJSONArray("dialogues").put(new JSONObject().put("start",4).put("end",23)
                .put("text","A brave hero speaks out!").put("layerId","layer_1")
                .put("bounds",new JSONObject().put("x",.05).put("y",.85)
                        .put("width",.9).put("height",.10)));
        p.getJSONArray("dialogues").put(new JSONObject().put("start",28).put("end",47)
                .put("text","We will win.").put("layerId","layer_1")
                .put("bounds",new JSONObject().put("x",.05).put("y",.85)
                        .put("width",.9).put("height",.10)));
        return p;
    }
    private static float[] energies(int n,int start,int stop){
        float[] peaks=new float[n];
        for(int i=start;i<=stop;i++)peaks[i]=i%3==0?.85f:.64f;
        return peaks;
    }
    @Test public void realEnergyClosesMouthDuringSilenceButPreservesVoicedArtAndExactFrames()throws Exception{
        JSONObject s=source(),p=program(s);
        String beforeSource=SketchDocument.canonical(s),beforeProgram=SketchDocument.canonical(p);
        JSONObject result=SketchVoiceActivityTiming.apply(s,p,0,"closed","open","round","teeth",energies(20,6,15));
        JSONObject animated=result.getJSONObject("program"),evidence=result.getJSONObject("evidence");
        assertEquals(20,evidence.getInt("audioAnalysisFrames"));
        assertTrue(evidence.getInt("voiceActivityFrames")>0);
        assertTrue(evidence.getInt("quietFrames")>0);
        assertFalse(evidence.getBoolean("recognizedSpeechPhonemes"));
        assertFalse(evidence.getBoolean("audioMixedIntoExport"));
        assertEquals(beforeSource,SketchDocument.canonical(s));
        assertEquals(beforeProgram,SketchDocument.canonical(p));
        int[] count=new int[20];String[] visibility=new String[20];
        JSONArray ranges=animated.getJSONArray("mouthCels");
        for(int j=0;j<ranges.length();j++){
            JSONObject range=ranges.getJSONObject(j);
            for(int f=range.getInt("start");f<=range.getInt("end");f++){
                count[f-4]++;visibility[f-4]=range.getString("objectId");
            }
        }
        boolean expressive=false;
        for(int f=0;f<20;f++){
            assertEquals("Exactly one vector mouth shape per rendered dialogue frame",1,count[f]);
            if(f<=3||f>=18)assertEquals("Silence must visibly close the speaker's mouth","closed",visibility[f]);
            if(f>=7&&f<=15&&!visibility[f].equals("closed"))expressive=true;
        }
        assertTrue("Energy should preserve multiple actual viseme shapes during speech",expressive);
        JSONObject compiled=SketchAnimationCompiler.compile(s,animated,"voice_bake");
        SketchDocument.validate(compiled.getJSONObject("document"));
        for(int frame:new int[]{0,3,24,48,62}){
            JSONObject scene=SketchAnimationCompiler.preview(s,animated,frame,s.getString("id")).getJSONObject("document");
            JSONArray items=scene.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
            for(int n=0;n<items.length();n++){
                String id=items.getJSONObject(n).getString("id");
                assertFalse("Original mouths must never leak outside their authored audio cue",
                        id.equals("closed")||id.equals("open")||id.equals("round")||id.equals("teeth"));
            }
        }
    }
    @Test public void quietOrCorruptOrWrongLengthAudioRejectsAtomically()throws Exception{
        JSONObject s=source(),p=program(s);String unmodified=SketchDocument.canonical(p);
        float[][] samples={new float[20],new float[15],energies(20,0,8)};
        samples[2][9]=Float.NaN;
        for(float[] sample:samples){
            try{SketchVoiceActivityTiming.apply(s,p,0,"closed","open","round","teeth",sample);fail("Invalid audio accepted");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("audio")||expected.getMessage().contains("Audio")||expected.getMessage().contains("speech"));}
            assertEquals(unmodified,SketchDocument.canonical(p));
        }
    }
    @Test public void aSecondDialogueKeepsFirstAudioMouthIntervalsUnchanged()throws Exception{
        JSONObject s=source(),p=program(s);
        JSONObject first=SketchVoiceActivityTiming.apply(s,p,0,"closed","open","round","teeth",energies(20,5,15))
                .getJSONObject("program");
        String initial=SketchDocument.canonical(first);
        JSONArray earlier=SketchDocument.copy(first).getJSONArray("mouthCels");
        JSONObject later=SketchVoiceActivityTiming.apply(s,first,1,"closed","open","round","teeth",energies(20,4,16))
                .getJSONObject("program");
        assertEquals(initial,SketchDocument.canonical(first));
        JSONArray updated=later.getJSONArray("mouthCels");
        assertTrue(updated.length()>earlier.length());
        for(int i=0;i<earlier.length();i++)
            assertEquals(SketchDocument.canonical(earlier.getJSONObject(i)),
                    SketchDocument.canonical(updated.getJSONObject(i)));
        SketchAnimationCompiler.compile(s,later,"both_cues");
    }
}
