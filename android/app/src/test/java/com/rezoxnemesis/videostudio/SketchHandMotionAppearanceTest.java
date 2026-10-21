package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Independent fixed-forearm artwork oracle from the actual failed motion review. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchHandMotionAppearanceTest {
    @Test public void curlTransitionsCannotRepaintTheStationaryForearmAsCreaseInkOrOneFlatBand()throws Exception {
        JSONArray examples=SketchCapabilities.handAnimation().getJSONArray("studyExamples");JSONObject example=null;
        for(int i=0;i<examples.length();i++)if(examples.getJSONObject(i).getString("id").equals("open_close"))example=examples.getJSONObject(i);
        assertNotNull(example);
        JSONObject operation=SketchDocument.copy(example.getJSONObject("operation"));operation.getJSONObject("settings").put("view","left");
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","motion_appearance").put("width",example.getInt("width")).put("height",example.getInt("height"))
                .put("frameCount",example.getInt("frameCount")).put("fps",example.getInt("fps")).put("background","#FFF2DFCB"));
        SketchEditOps.apply(doc,operation);
        String evidenceRoot=System.getProperty("videostudio.handappearance.output");
        java.io.File evidence=evidenceRoot==null?null:new java.io.File(evidenceRoot);
        if(evidence!=null){
            assertFalse("Preserve earlier native review generations",evidence.exists());assertTrue(evidence.mkdirs());
            java.nio.file.Files.write(new java.io.File(evidence,"operation.json").toPath(),operation.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(new java.io.File(evidence,"document.json").toPath(),doc.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        // In this original app recipe only the wrist/digits move. This interior
        // strip lies well below their contact/occlusion region. The reviewed
        // normal neighboring frames establish broad continuous forearm tones.
        int[] frames={23,24,25,75,76,77,90,91,92};StringBuilder failures=new StringBuilder();
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
            for(int frame:frames){
                Bitmap bitmap=renderer.render(frame);try{
                    if(evidence!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(evidence,String.format(java.util.Locale.ROOT,"frame-%04d.png",frame)))){
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));
                    }
                    int crease=0,base=0,light=0;
                    for(int y=450;y<=600;y++)for(int x=620;x<=655;x++){
                        int color=bitmap.getPixel(x,y);
                        if(color==0xFF8F6B50)crease++;
                        if(color==0xFFD6A078)base++;
                        if(color==0xFFE4AB80)light++;
                    }
                    if(bitmap.getPixel(630,500)!=0xFFE4AB80||crease!=0||light<800||base<800)
                        failures.append("frame ").append(frame).append(" stationary forearm pixel=").append(Integer.toHexString(bitmap.getPixel(630,500)))
                                .append(" creasePixels=").append(crease).append(" lightPixels=").append(light).append(" basePixels=").append(base).append('\n');
                }finally{bitmap.recycle();}
            }
        }
        assertEquals("Native intermediate frames must retain the reviewed forearm lighting, with no crease flood or disappearing broad tone:\n"+failures,"",failures.toString());
    }
}
