package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** One original four-second cutout mechanics study; all artwork authored through native Sketch ops. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchReferenceLayersVisualTest {
 private final Context context=RuntimeEnvironment.getApplication();
 private final JSONArray authored=new JSONArray(),calls=new JSONArray();
 private File root;
 private JSONObject box(double x,double y,double w,double h)throws Exception{return new JSONObject().put("x",x).put("y",y).put("width",w).put("height",h);}
 private void shape(String id,String kind,String color,double x,double y,double w,double h)throws Exception{authored.put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id",id).put("kind",kind).put("color",color).put("fill",true).put("bounds",box(x,y,w,h))));}
 private void line(String id,String color,double width,double...p)throws Exception{JSONArray points=new JSONArray();for(int i=0;i<p.length;i+=2)points.put(new JSONArray().put(p[i]).put(p[i+1]).put(1));authored.put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id",id).put("kind","stroke").put("color",color).put("width",width).put("points",points)));}
 private JSONObject call(String action,JSONObject request)throws Exception{JSONObject reply=SketchEditOps.execute(context,action,request);calls.put(new JSONObject().put("action",action).put("request",SketchDocument.copy(request)).put("receipt",SketchDocument.copy(reply)));return reply;}
 private static JSONArray ring(double l,double t,double r,double b)throws Exception{return new JSONArray().put(new JSONArray().put(l).put(t)).put(new JSONArray().put(r).put(t)).put(new JSONArray().put(r).put(b)).put(new JSONArray().put(l).put(b));}
 private JSONObject mask(String id,String name,double l,double t,double r,double b)throws Exception{return new JSONObject().put("maskId",id).put("layerId",id+"_layer").put("objectId",id+"_image").put("name",name).put("rings",new JSONArray().put(ring(l,t,r,b)));}
 private void png(Bitmap image,String name)throws Exception{try(FileOutputStream out=new FileOutputStream(new File(root,name))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}}
 private void json(String name,Object value)throws Exception{try(FileOutputStream out=new FileOutputStream(new File(root,name))){out.write(value.toString().getBytes(StandardCharsets.UTF_8));}}
 private void key(JSONArray operations,String id,int frame,double angle,double x,double y)throws Exception{operations.put(new JSONObject().put("type","set_keyframe").put("objectId",id).put("frame",frame).put("interpolation","ease_in_out").put("transform",SketchDocument.transform().put("anchorX",x).put("anchorY",y).put("rotation",angle)));}
 @Test public void originalRobotPartsRemainEditableAcrossFullNativeMotionAndArchive()throws Exception{
  ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try{study();return null;}catch(Exception e){throw new RuntimeException(e);}}).get(180,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
 private void study()throws Exception{
  root=new File(System.getProperty("preview.evidence","1".equals(System.getenv("VIDEOSTUDIO_REFERENCE_LAYERS_PROOF"))?new File("build/reference-layers-proof",Long.toString(System.nanoTime())).getPath():new File(context.getCacheDir(),"layers-visual").getPath()));assertTrue(root.isDirectory()||root.mkdirs());
  // Original brass courier: a deliberately mechanical cutout test, not a human-art quality pass.
  line("antenna","#FF90B4BD",.012,.5,.145,.5,.075);shape("beacon","ellipse","#FFFFD268",.478,.055,.044,.044);
  shape("head_shadow","ellipse","#FF573E32",.335,.135,.33,.255);shape("head_shell","ellipse","#FFC99B5C",.345,.125,.31,.25);shape("head_inset","ellipse","#FFE6C285",.36,.14,.28,.21);
  shape("visor","rect","#FF203841",.378,.21,.244,.07);shape("eye_left","ellipse","#FF56DACA",.397,.217,.055,.055);shape("eye_right","ellipse","#FF56DACA",.548,.217,.055,.055);shape("pupil_left","ellipse","#FFFCF8D6",.414,.23,.021,.024);shape("pupil_right","ellipse","#FFFCF8D6",.564,.23,.021,.024);
  line("mouth","#FF62432F",.008,.46,.312,.5,.322,.54,.312);line("forehead_seam","#FF91673F",.006,.5,.149,.5,.19);shape("head_bolt_l","ellipse","#FF805E43",.36,.257,.018,.018);shape("head_bolt_r","ellipse","#FF805E43",.622,.257,.018,.018);
  shape("neck","rect","#FF78979C",.46,.374,.08,.048);shape("torso_back","ellipse","#FF674B35",.346,.394,.308,.34);shape("torso","ellipse","#FFC49557",.36,.391,.28,.32);shape("belly","ellipse","#FF36565E",.395,.43,.21,.225);shape("core","ellipse","#FF85DBD0",.451,.49,.098,.098);shape("core_inner","ellipse","#FFD9F1D9",.477,.515,.046,.046);
  line("body_rib_l","#FFE3BA77",.012,.378,.48,.37,.56,.387,.63);line("body_rib_r","#FF8D673D",.012,.622,.48,.63,.56,.613,.63);
  for(int i=0;i<3;i++){shape("vent_"+i,"rect","#FF634D38",.46+i*.026,.66,.015,.024);}
  shape("left_hip","ellipse","#FF77949B",.39,.715,.075,.08);shape("right_hip","ellipse","#FF77949B",.535,.715,.075,.08);shape("left_leg","rect","#FFB28A53",.405,.765,.05,.08);shape("right_leg","rect","#FFB28A53",.545,.765,.05,.08);shape("left_boot","ellipse","#FF7E989C",.365,.824,.12,.06);shape("right_boot","ellipse","#FF7E989C",.515,.824,.12,.06);
  shape("left_shoulder","ellipse","#FF7A9B9F",.265,.406,.065,.065);shape("left_upper","rect","#FFBA935E",.255,.451,.057,.10);shape("left_elbow","ellipse","#FF47666F",.247,.535,.072,.072);shape("left_forearm","rect","#FFD2A76A",.257,.59,.055,.095);shape("left_hand","ellipse","#FF82A5A9",.24,.66,.087,.075);line("left_fingers","#FF36555E",.007,.264,.698,.296,.698);
  shape("right_shoulder","ellipse","#FF7A9B9F",.67,.406,.065,.065);shape("right_upper","rect","#FFBA935E",.688,.451,.057,.10);shape("right_elbow","ellipse","#FF47666F",.681,.535,.072,.072);shape("right_forearm","rect","#FFD2A76A",.688,.59,.055,.095);shape("right_hand","ellipse","#FF82A5A9",.673,.66,.087,.075);line("right_fingers","#FF36555E",.007,.70,.698,.73,.698);
  String originalId="lantern_robot_original";
  call("sketch_create",new JSONObject().put("sketchId",originalId).put("operationId","original_create").put("width",512).put("height",512).put("frameCount",1).put("background","#00000000"));
  call("sketch_edit",new JSONObject().put("sketchId",originalId).put("expectedRevision",1).put("operationId","original_draw").put("operations",authored));
  JSONObject source;try(SketchProjectStore store=new SketchProjectStore(context)){source=store.get(originalId);}
  JSONObject preparation=new JSONObject().put("sketchId",originalId).put("expectedRevision",2).put("operationId","original_frames").put("aspect","original").put("quality","720p");
  SketchNativeExport.Prepared nativeSource=SketchNativeExport.prepare(context,preparation);
  calls.put(new JSONObject().put("nativePreparation","SketchNativeExport.prepare").put("publicQueuedAction","sketch_render").put("request",preparation).put("codecExecuted",false).put("projectId",nativeSource.project.id));
  ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.get(nativeSource.project.id);ProjectStore.Asset asset=project.asset("sketch_frame_0");assertNotNull(asset);
  File file=new File(Uri.parse(asset.uri).getPath());java.nio.file.Files.copy(file.toPath(),new File(root,"original-native-source.png").toPath());
  String id="lantern_robot_layers";call("sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",512).put("fps",24).put("frameCount",96).put("background","#FF142C36"));
  call("sketch_import_reference",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","source").put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","source_image").put("bounds",box(.075,.045,.85,.85)));
  JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(context)){doc=store.get(id);}JSONObject ref=SketchDocument.object(doc,"source_image").getJSONObject("reference");
  JSONObject request=new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","separate").put("sourceObjectId","source_image").put("expectedReferenceSha256",ref.getString("sha256")).put("expectedSourceSha256",ref.getString("sourceSha256")).put("frame",0).put("exposure",96).put("hideSource",true).put("masks",new JSONArray().put(mask("head","Head and eyes",.33,.04,.67,.39)).put(mask("left_arm","Left arm cutout",.235,.40,.335,.75)).put(mask("right_arm","Right arm cutout",.665,.40,.765,.75))).put("background",new JSONObject().put("layerId","body_layer").put("objectId","body_image").put("name","Fixed body and legs"));
  JSONObject preview=call("sketch_reference_layers_preview",request);byte[] overlay=android.util.Base64.decode(preview.getJSONObject("sketchPreviewImage").getString("base64"),android.util.Base64.DEFAULT);try(FileOutputStream out=new FileOutputStream(new File(root,"native-mask-preview.png"))){out.write(overlay);}call("sketch_import_reference_layers",request);
  JSONArray animation=new JSONArray();
  // Author exposed joint housings with ordinary app geometry; no guessed source pixels.
  for(String side:new String[]{"left","right"}){
   double sx=side.equals("left")?.297:.573;
   animation.put(new JSONObject().put("type","add_object").put("layerId","body_layer").put("object",new JSONObject().put("id",side+"_fixed_bridge").put("kind","rect").put("frame",0).put("exposure",96).put("fill",true).put("color","#FF526E76").put("bounds",box(.075+sx*.85,.045+.425*.85,.13*.85,.027*.85))));
  }
  int[] frames={0,12,30,42,54,66,80,95};double[] right={0,10,-85,-65,-95,-65,-15,0},left={0,-5,8,10,10,8,2,0},head={0,0,-6,-6,-6,-3,0,0};for(int i=0;i<frames.length;i++){key(animation,"right_arm_image",frames[i],right[i],.075+.703*.85,.045+.438*.85);key(animation,"left_arm_image",frames[i],left[i],.075+.297*.85,.045+.438*.85);key(animation,"head_image",frames[i],head[i],.5,.045+.38*.85);}
  call("sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("operationId","wave").put("operations",animation));
  try(SketchProjectStore store=new SketchProjectStore(context)){doc=store.get(id);store.verifyReferences(doc);}assertEquals(96,doc.getInt("frameCount"));assertEquals(24,doc.getInt("fps"));
  JSONArray hashes=new JSONArray();Set<String> distinct=new HashSet<>();try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){for(int i=0;i<96;i++){Bitmap image=renderer.render(i);try{String name=String.format(Locale.ROOT,"frame-%04d.png",i);png(image,name);
    int connectorY=(int)Math.round((.045+.4385*.85)*512),background=Color.rgb(20,44,54);
    for(int edge=0;edge<512;edge++){
     assertEquals("Artwork must fit inside the native canvas at frame "+i,background,image.getPixel(edge,0));
     assertEquals(background,image.getPixel(edge,511));assertEquals(background,image.getPixel(0,edge));assertEquals(background,image.getPixel(511,edge));
    }
    for(double[] span:new double[][]{{.3,.42},{.58,.7}})for(int sample=0;sample<=20;sample++){
     int connectorX=(int)Math.round((.075+(span[0]+(span[1]-span[0])*sample/20)*.85)*512);
     assertNotEquals("Shoulder attachment must remain present at frame "+i,background,image.getPixel(connectorX,connectorY));
    }
    String hash=SketchProjectStore.fileHash(new File(root,name));hashes.put(new JSONObject().put("frame",i).put("sha256",hash));distinct.add(hash);}finally{image.recycle();}}}assertTrue("Real motion must change the native rasters",distinct.size()>60);assertEquals("The return to neutral must preserve the complete pose",hashes.getJSONObject(0).getString("sha256"),hashes.getJSONObject(95).getString("sha256"));
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","archive"));java.nio.file.Files.copy(archive.file.toPath(),new File(root,"Lantern-Robot.vssketch").toPath());JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore","Restored lantern robot");JSONObject copy;try(SketchProjectStore store=new SketchProjectStore(context)){copy=store.get(restored.getString("sketchId"));}try(SketchFrameRenderer a=new SketchFrameRenderer(context,doc);SketchFrameRenderer b=new SketchFrameRenderer(context,copy)){for(int frame:new int[]{0,30,54,95}){Bitmap x=a.render(frame),y=b.render(frame);try{assertTrue(x.sameAs(y));}finally{x.recycle();y.recycle();}}}
  json("Native-Authored-Source.json",source);json("Native-Source-Operations.json",authored);json("Native-Calls.json",calls);json("Native-Document.json",doc);json("Frame-Hashes.json",hashes);json("Study.json",new JSONObject().put("technical","PASS").put("nativeProtocolVersion",AppProtocol.APP_VERSION).put("visual","UNREVIEWED").put("creativeScenarios",1).put("fps",24).put("frameCount",96).put("durationSeconds",4).put("width",512).put("height",512).put("distinctFrameHashes",distinct.size()).put("authoring","VideoStudio native SketchEditOps shapes and strokes; no external artwork").put("animation","VideoStudio source-assisted rigid named parts and editable native transform keys; fixed shoulder bridges authored with app rect objects").put("renderer","SketchFrameRenderer, Robolectric SDK33 native Canvas in workspace").put("audio","Silent mechanical study; no speech or audio pass").put("manualMcpParity","UNVERIFIED end-to-end; shared product operations").put("archiveRestore","PASS four sampled native frames").put("archiveSha256",SketchProjectStore.fileHash(new File(root,"Lantern-Robot.vssketch"))).put("apkOrPhoneOrAndroidCodec",false));
 }
}
