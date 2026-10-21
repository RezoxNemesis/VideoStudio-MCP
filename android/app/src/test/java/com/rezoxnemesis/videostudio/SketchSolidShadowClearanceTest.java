package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Native floor-clearance regression; does not claim the missing rigid-body prototype exists. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSolidShadowClearanceTest {
    @Test public void airborneShadowTracksRotatedSupportWithoutChangingTheCentre()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","shadow_clearance")
                .put("width",1280).put("height",720).put("frameCount",120).put("fps",30).put("background","#FFF1ECDD"));
        JSONObject program=SketchSolidPrograms.turn(120).put("size",new JSONArray("[100,100,100]"))
                .put("softShadow",true).put("lighting",true);
        JSONArray keys=program.getJSONArray("keys");
        for(int i=0;i<keys.length();i++)keys.getJSONObject(i).put("elevation",230).put("yaw",0).put("roll",i*180);
        SketchSolidOps.Model model=new SketchSolidOps.Model(doc,program);
        SketchSolidOps.apply(doc,new JSONObject().put("type","add_solid").put("solidId","airborne")
                .put("layerId","layer_1").put("program",program));
        File root=evidence("shadow");root.mkdirs();JSONArray samples=new JSONArray();
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
            for(int frame:new int[]{0,30,60,90,119}){
                double clearance=Double.POSITIVE_INFINITY,centre=0;
                for(double[] v:model.vertices(frame)){clearance=Math.min(clearance,v[1]);centre+=v[1]/8;}
                JSONObject shadow=SketchDocument.object(doc,"airborne_f"+frame+"_shadow");
                samples.put(new JSONObject().put("frame",frame).put("centreY",centre).put("clearance",clearance)
                        .put("opacity",shadow.getJSONObject("transform").getDouble("opacity"))
                        .put("expectedOpacity",.20/(1+clearance/500)));
                Bitmap bitmap=renderer.render(frame);try{SketchActionFilmTest.png(new File(root,"frame-"+frame+".png"),bitmap);}finally{bitmap.recycle();}
            }
        }
        Files.write(new File(root,"samples.json").toPath(),samples.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(int i=0;i<samples.length();i++){
            JSONObject sample=samples.getJSONObject(i);assertEquals("Dense/native centre remains unchanged",280,sample.getDouble("centreY"),1e-9);
            assertEquals("Shadow must use actual rotated floor clearance at frame "+sample.getInt("frame"),
                    sample.getDouble("expectedOpacity"),sample.getDouble("opacity"),1e-10);
        }
        assertEquals(230,samples.getJSONObject(0).getDouble("clearance"),1e-9);
        assertEquals(.20/1.46,samples.getJSONObject(0).getDouble("opacity"),1e-10);
    }
    @Test public void rotatingGroundLockedBoxKeepsContactShadow()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","ground_shadow")
                .put("width",1280).put("height",720).put("frameCount",120));
        JSONObject program=SketchSolidPrograms.turn(120).put("groundLock",true);
        program.getJSONArray("keys").getJSONObject(1).put("yaw",0).put("roll",180);
        SketchSolidOps.apply(doc,new JSONObject().put("type","add_solid").put("solidId","grounded")
                .put("layerId","layer_1").put("program",program));
        for(int frame:new int[]{0,30,60,90,119})assertEquals(.2,SketchDocument.object(doc,"grounded_f"+frame+"_shadow")
                .getJSONObject("transform").getDouble("opacity"),1e-10);
    }
    static File evidence(String study){
        String root=System.getenv("VIDEOSTUDIO_EVIDENCE_ROOT"),generation=System.getenv("VIDEOSTUDIO_GENERATION");
        return new File(root==null?"build/native-animation-continuation":root,(generation==null?"v1":generation)+"/"+study);
    }
}
