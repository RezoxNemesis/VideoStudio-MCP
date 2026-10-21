package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchCharacterStudyTest {
 static byte[] resource(String name)throws Exception {try(InputStream in=SketchCharacterStudyTest.class.getResourceAsStream("/character/"+name)){if(in==null)throw new FileNotFoundException(name);return in.readAllBytes();}}
 @Test public void originalNativeCharacterViewsAndMaterials()throws Exception {
  Context c=RuntimeEnvironment.getApplication();String evidence=System.getenv("VIDEOSTUDIO_CHARACTER_EVIDENCE");
  String audio=System.getenv("VIDEOSTUDIO_CHARACTER_AUDIO");
  for(String variant:new String[]{"front","left","right","back","high","low","explain_night_front","explain_night_right"}){
   String view=variant.startsWith("explain_night_")?variant.substring(14):variant;
   File root=new File(evidence==null?"build/character-evidence":evidence,variant);root.mkdirs();
   String id=SketchMouthAnimationTest.create(c,180,30);JSONObject op=SketchCharacterAnimationTest.op(view);
   String language=view.equals("right")||view.equals("low")?"hindi":"english";
   if(audio!=null){File ar=new File(audio,language+"/result/outputs");JSONObject timing=new JSONObject(new String(Files.readAllBytes(new File(ar,"02_dialogue.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));op.put("speech",SketchSpeechTiming.fromDialogue(timing,0));Files.copy(new File(ar,"01_dialogue.wav").toPath(),new File(root,"audio.wav").toPath(),StandardCopyOption.REPLACE_EXISTING);Files.copy(new File(ar,"02_dialogue.json").toPath(),new File(root,"dialogue.json").toPath(),StandardCopyOption.REPLACE_EXISTING);}
   else {byte[] timing=resource(language+"-dialogue.json");op.put("speech",SketchSpeechTiming.fromDialogue(new JSONObject(new String(timing,java.nio.charset.StandardCharsets.UTF_8)),0));Files.write(new File(root,"audio.wav").toPath(),resource(language+".wav"));Files.write(new File(root,"dialogue.json").toPath(),timing);}
   if(view.equals("right")||view.equals("high"))op.getJSONObject("settings").put("material","leather").put("build","broad");
   if(view.equals("low"))op.getJSONObject("settings").put("gesture","explain");
   if(variant.startsWith("explain_night_"))op.getJSONObject("settings").put("gesture","explain").put("material","leather").put("coatColor","#FF54455F").put("skinColor","#FFE0B49B").put("hairColor","#FF343A50").put("headTilt",-8).put("armLift",.55);
   JSONObject request=SketchMouthAnimationTest.request(id,op);
   if(variant.startsWith("explain_night_")){JSONArray ops=new JSONArray().put(new JSONObject().put("type","set_canvas").put("background","#FF151E32")).put(op);request.put("operations",ops);}
   SketchEditOps.execute(c,"sketch_edit",request);JSONObject doc;
   try(SketchProjectStore store=new SketchProjectStore(c)){doc=store.get(id);}SketchSpeechStudyTest.text(new File(root,"document.json").toPath(),doc.toString());SketchSpeechStudyTest.text(new File(root,"operation.json").toPath(),op.toString(2));
   JSONArray hashes=new JSONArray();
   if(evidence!=null&&"true".equals(System.getenv("VIDEOSTUDIO_CHARACTER_FULL"))){SketchNativeExport.Prepared ex=SketchNativeExport.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","character_export").put("aspect","original").put("quality","720p"));SketchSpeechStudyTest.text(new File(root,"native-export-manifest.json").toPath(),ex.metadata.toString(2));
    for(int f=0;f<180;f++){File in=new File(ex.file.getParentFile(),String.format(Locale.ROOT,"frame_%04d.png",f)),out=new File(root,String.format(Locale.ROOT,"frames/frame-%04d.png",f));out.getParentFile().mkdirs();Files.copy(in.toPath(),out.toPath(),StandardCopyOption.REPLACE_EXISTING);String sha=SketchProjectStore.fileHash(out);assertEquals(ex.metadata.getJSONArray("frames").getJSONObject(f).getString("sha256"),sha);hashes.put(new JSONObject().put("frame",f).put("sha256",sha));}
   }else try(SketchFrameRenderer r=new SketchFrameRenderer(c,doc)){for(int f:new int[]{0,30,54,72,108,144,179}){Bitmap b=r.render(f);File out=new File(root,String.format(Locale.ROOT,"frames/frame-%04d.png",f));try{SketchActionFilmTest.png(out,b);}finally{b.recycle();}hashes.put(new JSONObject().put("frame",f).put("sha256",SketchProjectStore.fileHash(out)));}}
   SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","character_archive"));Files.copy(archive.file.toPath(),new File(root,"editable.vssketch").toPath(),StandardCopyOption.REPLACE_EXISTING);
   JSONObject restored=SketchPaintArchive.restoreFile(c,archive.file,SketchProjectStore.fileHash(archive.file),"restored_"+variant,"Restored scholar");
   try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer before=new SketchFrameRenderer(c,doc);SketchFrameRenderer after=new SketchFrameRenderer(c,store.get(restored.getString("sketchId")))){for(int f:new int[]{0,54,108,179}){Bitmap a=before.render(f),b=after.render(f);try{assertTrue(a.sameAs(b));}finally{a.recycle();b.recycle();}}}
   SketchSpeechStudyTest.text(new File(root,"native-evidence.json").toPath(),new JSONObject().put("operation",op).put("request",request).put("frames",hashes).put("frameCount",180).put("fps",30).put("width",1280).put("height",720).put("renderer","VideoStudio Android Canvas / workspace Robolectric").put("archivePixelComparisons",4).put("audioEngine","VideoStudio Open Pipeline Kokoro self-hosted CPU, retained fixture bytes").put("forcedAlignmentVerified",false).put("phoneExecution",false).put("androidVideoEncoding",false).toString(2));
  }
 }
}
