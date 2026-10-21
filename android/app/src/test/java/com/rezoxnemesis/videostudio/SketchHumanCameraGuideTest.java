package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** One continuous construction-camera family, not accepted character art or many actions. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanCameraGuideTest {
 private JSONObject settings(double yaw,double pitch)throws Exception{return new JSONObject().put("figureStyle","anatomy_sketch").put("viewYawDegrees",yaw).put("viewPitchDegrees",pitch).put("size",.85).put("hairStyle","long");}
 @Test public void degreeViewChangesProjectionWithoutChangingAnyWorldJointUnderLeanAndHeadTilt()throws Exception {
  JSONObject pose=new JSONObject().put("bodyLean",13).put("headTilt",9).put("nearHandX",75).put("nearHandY",-205);
  SketchHumanFigure.Skeleton base=SketchHumanFigure.skeleton(settings(0,0),pose);
  for(double yaw:new double[]{25,30,40,45,60,65,70,75,90,135,180,225,270,315,360})for(double pitch:new double[]{-55,0,45}){
   SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(settings(yaw,pitch),pose);
   double[][] a={base.hip,base.nearShoulder,base.farShoulder,base.nearHand,base.farHand,base.nearElbow,base.farElbow,base.neck,base.head,base.leftKnee,base.rightKnee,base.leftFoot,base.rightFoot};
   double[][] b={p.hip,p.nearShoulder,p.farShoulder,p.nearHand,p.farHand,p.nearElbow,p.farElbow,p.neck,p.head,p.leftKnee,p.rightKnee,p.leftFoot,p.rightFoot};
   for(int i=0;i<a.length;i++)assertArrayEquals("Camera must not rotate the figure's world pose at yaw "+yaw+", joint "+i,a[i],b[i],1e-9);
  }
  double[] front=base.camera.point(new double[]{40,-100,20}),right=SketchHumanFigure.skeleton(settings(90,0),pose).camera.point(new double[]{40,-100,20});assertArrayEquals(new double[]{40,-100},front,1e-9);assertArrayEquals(new double[]{20,-100},right,1e-9);
 }
 @Test public void fullTurnReturnsExactlyToItsInitialNativeInkAndAllRequestedViewsAreValid()throws Exception {
  String first=null;String output=System.getenv("VIDEOSTUDIO_CAMERA_GUIDE_EVIDENCE");java.io.File root=output==null?null:new java.io.File(output);if(root!=null){assertFalse("Preserve all prior guide generations",root.exists());assertTrue(root.mkdirs());}
  for(double yaw:new double[]{0,25,30,40,45,60,65,70,75,90,135,180,225,270,315,360}){
   JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","guide").put("width",1280).put("height",720).put("frameCount",1).put("fps",24));JSONObject layer=doc.getJSONArray("layers").getJSONObject(0);
   SketchHumanFigure.draw(doc,layer,"guide_actor",settings(yaw,0),new JSONObject(),0);SketchDocument.validate(doc);assertTrue(layer.getJSONArray("objects").length()>30);String ink=SketchDocument.canonical(layer);
   if(root!=null){String name=String.format(java.util.Locale.ROOT,"yaw-%03d",(int)yaw);java.nio.file.Files.write(new java.io.File(root,name+".json").toPath(),doc.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));try(SketchFrameRenderer renderer=new SketchFrameRenderer(org.robolectric.RuntimeEnvironment.getApplication(),doc)){android.graphics.Bitmap image=renderer.render(0);try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(root,name+".png"))){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}}}
   if(yaw==0)first=ink;if(yaw==360)assertEquals("Closed camera revolution must retain exact paths and pose",first,ink);
  }
  if(root!=null)java.nio.file.Files.write(new java.io.File(root,"evidence.json").toPath(),new JSONObject().put("renderer","VideoStudio SketchHumanFigure + SketchFrameRenderer, Android native graphics under Robolectric").put("study","one static procedural construction figure across sixteen camera positions").put("isTargetCharacter",false).put("characterArtStatus","NEEDS_REWORK").put("phoneExecution",false).put("actions",0).put("technicalStatus","PASS").toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 @Test public void numericAnglesAreStrictAndAvailableOnlyForSpatialConstructionFigures()throws Exception {
  for(String key:new String[]{"viewYawDegrees","viewPitchDegrees"})for(Object bad:new Object[]{JSONObject.NULL,"30",true,Double.NaN}){assertThrows(Exception.class,()->SketchHumanFigure.settings(new JSONObject().put("figureStyle","anatomy_sketch").put(key,bad)));}
  assertThrows(Exception.class,()->SketchHumanFigure.settings(new JSONObject().put("viewYawDegrees",0)));
  assertThrows(Exception.class,()->SketchHumanFigure.settings(settings(361,0)));assertThrows(Exception.class,()->SketchHumanFigure.settings(settings(0,76)));
  assertFalse(SketchHumanFigure.settings(new JSONObject()).has("viewYawDegrees"));assertFalse(SketchHumanFigure.settings(new JSONObject()).has("viewPitchDegrees"));assertEquals(13,SketchHumanFigure.defaults().length());
 }
 @Test public void manualNumericCameraControlsReachTheSameSettingsWithoutAddingDefaults()throws Exception {
  try(org.robolectric.android.controller.ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
   JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","manual_guide").put("width",1280).put("height",720).put("frameCount",48).put("fps",24));java.util.List<JSONObject> requests=new java.util.ArrayList<>();
   SketchHumanDialog.show(a.get(),doc,(r,c)->{requests.add(r);c.accept(new IllegalStateException("Keep controls"));});android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();android.view.View root=dialog.getWindow().getDecorView();
   android.widget.CheckBox numeric=(android.widget.CheckBox)find(root,"Numeric camera guide");assertNotNull("Numeric guide controls must be available manually",numeric);assertFalse(numeric.isChecked());
   ((android.widget.Spinner)find(root,"Figure style")).setSelection(1);org.robolectric.shadows.ShadowLooper.idleMainLooper();numeric.setChecked(true);
   ((android.widget.EditText)find(root,"View yaw (degrees)")).setText("35");((android.widget.EditText)find(root,"View pitch (degrees)")).setText("-20");dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
   assertEquals(1,requests.size());JSONArray ops=requests.get(0).getJSONArray("operations");JSONObject selected=null;for(int i=0;i<ops.length();i++)if("add_human_animation".equals(ops.getJSONObject(i).getString("type")))selected=ops.getJSONObject(i).getJSONObject("settings");assertNotNull(selected);assertEquals(35,selected.getDouble("viewYawDegrees"),0);assertEquals(-20,selected.getDouble("viewPitchDegrees"),0);dialog.cancel();
  }
 }
 private static android.view.View find(android.view.View v,String description){if(v.getContentDescription()!=null&&description.contentEquals(v.getContentDescription()))return v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){android.view.View got=find(g.getChildAt(i),description);if(got!=null)return got;}}return null;}
}
