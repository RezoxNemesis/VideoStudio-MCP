package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Run on untouched32faad8 before implementation; source-free original anatomy, not actor likeness. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanHeadFitBaselineStudyTest {
 @Test public void captureOriginalNativeEightYawGeometryAndRepresentativeOmittedPoseCases()throws Exception{
  String path=System.getenv("VIDEOSTUDIO_CAPTURE_HEAD_FIT_BASELINE");Assume.assumeNotNull(path);File output=new File(path);assertFalse("Never overwrite a prior native baseline",output.exists());assertTrue(output.mkdirs());
  JSONArray records=SketchHeadFitTestSupport.cases();for(int i=0;i<records.length();i++){
   JSONObject record=records.getJSONObject(i),doc=SketchHeadFitTestSupport.document(record.getJSONObject("settings"),record.getJSONObject("pose"));String name=record.getString("name");SketchHeadFitTestSupport.write(new File(output,name+".json"),doc.toString(2));
   Bitmap image=SketchHeadFitTestSupport.render(doc);try{byte[] png=SketchHeadFitTestSupport.png(image);Files.write(new File(output,name+".png").toPath(),png);record.put("layerSha256",SketchHeadFitTestSupport.textSha(SketchHeadFitTestSupport.ink(doc))).put("rawPixelsSha256",SketchHeadFitTestSupport.pixels(image)).put("pngSha256",SketchHeadFitTestSupport.sha(png));}finally{image.recycle();}
  }
  SketchHeadFitTestSupport.write(new File(output,"Baseline.json"),new JSONObject().put("sourceCommit",SketchHeadFitTestSupport.BASE).put("fixtureVersion",1).put("nativeGraphics",true).put("sourceFree",true).put("renderer","SketchAnatomyHead + SketchFrameRenderer under Robolectric native graphics").put("acceptedCharacterAngles",0).put("animationScenarios",0).put("limits","Authored generic construction; no target likeness, hidden anatomy recovery, phone or model execution").put("cases",records).toString(2));
 }
}
