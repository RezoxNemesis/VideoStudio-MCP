package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
/** Opt-in actual native source guide screenshots. One static hairstyle family, not8actions or accepted actor views. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchCenterPartFringeStudyTest {
 static void png(File file,Bitmap bitmap)throws Exception{assertFalse("Preserve all prior native guide trials",file.exists());try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
 @Test public void nativeHeadsAndFullPortraitsAreWrittenForActualVisualReview()throws Exception{
  String location=System.getenv("VIDEOSTUDIO_CENTER_PART_STUDY_EVIDENCE");Assume.assumeTrue(location!=null&&!location.isEmpty());File root=new File(location);assertFalse("Each study must keep its own immutable output directory",root.exists());assertTrue(root.mkdirs());JSONArray views=new JSONArray();
  for(String view:SketchCenterPartFringeTest.VIEWS){JSONObject head=SketchCenterPartFringeTest.head(view,"center_part");Bitmap face=SketchCenterPartFringeTest.render(head);try{png(new File(root,view+"-head.png"),face);}finally{face.recycle();}java.nio.file.Files.write(new File(root,view+"-head.json").toPath(),head.toString(2).getBytes(StandardCharsets.UTF_8));JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","parted_portrait").put("width",1280).put("height",1600).put("frameCount",1));JSONObject s=SketchRearHairOwnerOrderTest.settings(view,"long").put("fringeStyle","center_part");SketchHumanFigure.draw(d,d.getJSONArray("layers").getJSONObject(0),"actor",s,new JSONObject(),0);try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){Bitmap portrait=r.render(0);try{png(new File(root,view+"-portrait.png"),portrait);}finally{portrait.recycle();}}java.nio.file.Files.write(new File(root,view+"-portrait.json").toPath(),d.toString(2).getBytes(StandardCharsets.UTF_8));views.put(new JSONObject().put("view",view).put("settings",s));}
  java.nio.file.Files.write(new File(root,"evidence.json").toPath(),new JSONObject().put("views",views).put("renderer","VideoStudio native Java + Android Canvas under Robolectric").put("sourcePixelsCopied",false).put("phoneExecution",false).put("modelInferenceExecuted",false).put("study","One static original construction guide hairstyle across8cameras").put("visualStatus","UNREVIEWED").put("actorLikenessEstablished",false).toString(2).getBytes(StandardCharsets.UTF_8));
 }
}
