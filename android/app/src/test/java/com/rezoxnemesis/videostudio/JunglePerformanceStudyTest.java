package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class JunglePerformanceStudyTest {
    @Test public void actionForegroundDoesNotInheritTheDiscardedMacroCut()throws Exception {
        for(int frame:new int[]{81,82,119,120})assertTrue("The continuous action study keeps its foreground at frame "+frame,SketchDocument.layer(JunglePerformanceStudy.drawCel("continuous",frame,0),"foreground").getBoolean("visible"));
    }
    @Test public void studyScoreHasARealTwentySecondStereoClock()throws Exception {
        java.io.File f=java.io.File.createTempFile("acting_score_",".wav");f.delete();try{
            JSONObject proof=JungleFilmSoundtrack.writeStudy(f);assertNotNull("The study needs actual native PCM, not a music cue placeholder",proof);
            JSONObject measured=PcmWaveFile.inspect(f);assertEquals(20000,measured.getInt("durationMs"));assertEquals(2,measured.getInt("channels"));assertTrue(proof.getDouble("peak")>.05);assertTrue(proof.getDouble("peak")<1);
        }finally{f.delete();}
    }
    @Test public void swordAndClawContoursActuallyMeetAndBootsStayPlanted()throws Exception {
        JSONObject first=JunglePerformanceStudy.drawCel("planted",0,0);JSONObject blade=find(first,"sword_blade_0");
        assertNotNull("The new exchange needs an independently drawn weapon contour",blade);
        JSONArray left=find(first,"boot_left_0").getJSONArray("commands"),right=find(first,"boot_right_0").getJSONArray("commands");
        String held=null;
        for(int frame:new int[]{0,24,42,43,44,45,60,90,143}){
            JSONObject doc=JunglePerformanceStudy.drawCel("pose",frame,0);
            for(String foot:new String[]{"boot_left_0","boot_right_0"}){
                JSONArray actual=find(doc,foot).getJSONArray("commands"),expected=foot.equals("boot_left_0")?left:right;
                for(int n=0;n<actual.length();n++)for(int p=1;p<actual.getJSONArray(n).length();p++)assertEquals(expected.getJSONArray(n).getDouble(p),actual.getJSONArray(n).getDouble(p),1e-9);
            }
            if(frame>=43&&frame<=45){
                JSONObject weapon=find(doc,"sword_blade_0");JSONArray tip=weapon.getJSONArray("commands").getJSONArray(2),nail=find(doc,"guard_claw_0").getJSONArray("commands").getJSONArray(1);
                assertEquals(tip.getDouble(1)*1280,nail.getDouble(3)*1280,.1);assertEquals(tip.getDouble(2)*720,nail.getDouble(4)*720,.1);
                if(held==null)held=weapon.getJSONArray("commands").toString();else assertEquals(held,weapon.getJSONArray("commands").toString());
            }
        }
    }
    @Test public void recognitionGazePrecedesTheHeadAndShameFollowsRecognition() {
        JungleActingDrawing.Acting first=JungleActingDrawing.sample(0),eyes=JungleActingDrawing.sample(.35),turn=JungleActingDrawing.sample(1.4),disgust=JungleActingDrawing.sample(7),shame=JungleActingDrawing.sample(11);
        assertTrue(Math.abs(eyes.gaze-first.gaze)>.5);assertEquals(first.yaw,eyes.yaw,1e-9);assertTrue(Math.abs(turn.yaw-first.yaw)>.4);
        assertTrue(disgust.disgust>.9);assertTrue(disgust.shame<.01);assertTrue(shame.shame>.9);assertTrue(shame.pitch>disgust.pitch);assertTrue(shame.hand>.9);
    }
    static JSONObject find(JSONObject doc,String id)throws Exception {JSONArray layers=doc.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++)if(id.equals(objects.getJSONObject(j).getString("id")))return objects.getJSONObject(j);}return null;}
    @Test public void revisedShotsRemainOriginalEditableNativeGeometry()throws Exception {
        for(int frame:new int[]{0,44,100,150,220,320,390,479}){
            JSONObject doc=JunglePerformanceStudy.drawCel("performance_"+frame,frame,0);
            assertNotNull("A native performance cel is required",doc);SketchDocument.validate(doc);
            assertEquals(1,doc.getInt("frameCount"));
            JSONArray layers=doc.getJSONArray("layers");boolean paths=false;
            for(int i=0;i<layers.length();i++)for(int j=0;j<layers.getJSONObject(i).getJSONArray("objects").length();j++){
                JSONObject o=layers.getJSONObject(i).getJSONArray("objects").getJSONObject(j);
                assertFalse(o.has("reference"));assertFalse(o.has("paint"));assertNotEquals("image",o.getString("kind"));paths|=o.getString("kind").equals("path");
            }assertTrue(paths);
        }
    }
    @Test public void studyResumePreservesArtistCelsAndTheOldFilm()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id="study_"+java.util.UUID.randomUUID();
        JSONObject old=SketchJungleFilm.populateRange(c,"old_"+id,0,1,null);assertNotNull(old);
        JSONObject result=JunglePerformanceStudy.populateRange(c,id,0,2,null);assertNotNull("Study book creation is required",result);assertEquals(480,result.getInt("frameCount"));
        String cel=id+"_p0";JSONObject before;try(SketchProjectStore s=new SketchProjectStore(c)){before=s.get(cel);}
        String object=before.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0).getString("id");
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",cel).put("expectedRevision",1).put("operationId","artist").put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId",object).put("patch",new JSONObject().put("color","#FF334455")))));
        SketchBookStore.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",2));
        JunglePerformanceStudy.populateRange(c,id,0,4,null);
        try(SketchProjectStore s=new SketchProjectStore(c)){assertEquals("#FF334455",SketchDocument.object(s.get(cel),object).getString("color"));assertEquals(1,s.get(id+"_p2").getInt("revision"));assertNotNull(s.get(id+"_p3"));assertEquals(1,s.get("old_"+id+"_p0").getInt("revision"));}
    }
}
