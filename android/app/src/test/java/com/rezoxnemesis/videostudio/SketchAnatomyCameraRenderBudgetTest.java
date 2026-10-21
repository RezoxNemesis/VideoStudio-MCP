package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;
/** Actual emitted/validated native guide geometry, not a matrix-only or artistically accepted orbit. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAnatomyCameraRenderBudgetTest {
 @Test public void acceptedCameraAnglesAndHeadTiltsEmitValidBoundedNativeArtwork()throws Exception {
  List<String> failures=new ArrayList<>();int checked=0;
  for(double yaw:new double[]{25,30,40,45,60,65,70,75,90,135,180,225,270,315,360})for(double pitch:new double[]{-55,0,45})for(double tilt:new double[]{0,18}){
   if(tilt!=0&&yaw!=25&&yaw!=90&&yaw!=180&&yaw!=270&&yaw!=360)continue;
   JSONObject settings=new JSONObject().put("figureStyle","anatomy_sketch").put("viewYawDegrees",yaw).put("viewPitchDegrees",pitch).put("hairStyle","long").put("size",.85),pose=new JSONObject().put("bodyLean",13).put("headTilt",tilt).put("nearHandX",75).put("nearHandY",-205);
   JSONObject document=SketchDocument.create(new JSONObject().put("sketchId","native_camera_budget").put("width",1280).put("height",720).put("frameCount",1));
   try{SketchHumanFigure.draw(document,document.getJSONArray("layers").getJSONObject(0),"actor",settings,pose,0);SketchDocument.validate(document);assertTrue("Actual native ink must be emitted",document.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length()>30);checked++;}
   catch(Exception failure){failures.add("yaw="+yaw+" pitch="+pitch+" headTilt="+tilt+": "+failure.getMessage());String location=System.getenv("VIDEOSTUDIO_HEAD_CAMERA_FAILURE_EVIDENCE");if(location!=null){java.io.File root=new java.io.File(location);assertTrue(root.mkdirs()||root.isDirectory());java.io.File target=new java.io.File(root,"yaw-"+(int)yaw+"-pitch-"+(int)pitch+"-tilt-"+(int)tilt+".json");assertFalse("Keep every failed native input",target.exists());java.nio.file.Files.write(target.toPath(),new JSONObject().put("settings",settings).put("pose",pose).put("failure",failure.toString()).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
  }
  assertTrue("Accepted anatomy camera inputs must not exceed native mask/geometry budgets: "+failures,failures.isEmpty());assertEquals(60,checked);
 }
}
