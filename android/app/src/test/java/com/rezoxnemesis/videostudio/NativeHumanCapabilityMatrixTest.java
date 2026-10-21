package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Distinct native animation scenarios; a case is not a frame or a repeated assertion. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeHumanCapabilityMatrixTest {
 private static final String[] VIEWS={"front","left","right","back","high","low"};
 private final Context context=RuntimeEnvironment.getApplication();private final JSONArray records=new JSONArray();private File root;private int failures;
 @Test public void executeDistinctHandBodyAndAudibleActingScenariosThroughNativeOperations()throws Exception {
  boolean full="true".equals(System.getenv("VIDEOSTUDIO_HUMAN_MATRIX"));String evidence=System.getenv("VIDEOSTUDIO_HUMAN_EVIDENCE");root=new File(evidence==null?"build/human-capability-cases":evidence);root.mkdirs();
  String actionSelection=System.getenv("VIDEOSTUDIO_HUMAN_ACTIONS");String[] actions=actionSelection!=null?actionSelection.split(","):full?SketchHumanPresets.NAMES:new String[]{"explain","walk"};int actionIndex=0;
  for(String action:actions){for(String view:VIEWS){JSONObject op=SketchHumanAnimationTest.op(action,view).put("fit",true);JSONObject s=op.getJSONObject("settings").put("size",1.25).put("floorY",.85).put("faceStyle",new String[]{"graphic","angular","soft"}[actionIndex%3]).put("shading",actionIndex%3==0?"cel":"soft").put("hairStyle",actionIndex%3==0?"long":"short").put("build",actionIndex%2==0?"slender":"broad");
    if(actionIndex%3==1)s.put("skinColor","#FFA46F55").put("coatColor","#FF75654F").put("hairColor","#FF272A32");if(actionIndex%3==2)s.put("skinColor","#FFE3B89A").put("coatColor","#FF526D67").put("trouserColor","#FF35354E");run("body_"+action+"_"+view,op,96,null);
  }actionIndex++;}
  if(full)for(String pattern:new String[]{"open_close","grasp","finger_cascade","thumb_opposition"})for(String hand:new String[]{"right","left"})for(String view:VIEWS){JSONObject op=SketchHandAnimationTest.op(view);op.getJSONObject("settings").put("handedness",hand).put("size",.95);if(!pattern.equals("open_close"))op.put("poses",handPoses(pattern));run("hand_"+pattern+"_"+hand+"_"+view,op,96,null);}
  String audioRoot=System.getenv("VIDEOSTUDIO_HUMAN_AUDIO");if(full&&audioRoot!=null)for(String voice:new String[]{"am_adam","af_heart","hm_omega","hf_alpha"})for(String context:new String[]{"listen","explain"})for(String view:VIEWS){File source=new File(audioRoot,voice);JSONObject timing=new JSONObject(new String(Files.readAllBytes(new File(source,"02_dialogue.json").toPath()),StandardCharsets.UTF_8));JSONObject op=SketchHumanAnimationTest.op(context,view).put("fit",true).put("speech",SketchSpeechTiming.fromDialogue(timing,0));op.getJSONObject("settings").put("size",1.2).put("faceStyle",voice.startsWith("af")||voice.startsWith("hf")?"soft":"angular").put("shading","soft").put("hairStyle",voice.startsWith("af")||voice.startsWith("hf")?"long":"short");run("speech_"+voice+"_"+context+"_"+view,op,144,new File(source,"01_dialogue.wav"));}
  write(new File(root,"Case-Index.json"),new JSONObject().put("cases",records).put("distinctCases",records.length()).put("technicalFailures",failures).put("visualReview","UNREVIEWED until screenshots and motion are inspected").put("countSemantics","Each case is a distinct native operation/settings/pose/view scenario; frames/assertions and retries are not new cases").toString(2));assertEquals("Inspect failed scenario records; never remove failed cases to make the count green",0,failures);if(full&&audioRoot!=null)assertEquals(240,records.length());
 }
 private void run(String label,JSONObject operation,int frames,File audio)throws Exception {
  File folder=new File(root,label);folder.mkdirs();JSONObject record=new JSONObject().put("caseId",label).put("frameCount",frames).put("fps",24).put("durationSeconds",frames/24d).put("width",1280).put("height",720).put("renderer","VideoStudio Android Canvas / workspace Robolectric").put("visual","UNREVIEWED").put("manualMcpParity","UNVERIFIED for this case").put("phoneExecution",false).put("appVersion",AppProtocol.APP_VERSION);records.put(record);write(new File(folder,"operation.json"),operation.toString(2));
  try{String id=SketchMouthAnimationTest.create(context,frames,24);JSONObject request=SketchMouthAnimationTest.request(id,operation);SketchEditOps.execute(context,"sketch_edit",request);JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(context)){doc=store.get(id);}write(new File(folder,"document.json"),doc.toString());write(new File(folder,"request.json"),request.toString(2));assertEquals(frames,doc.getInt("frameCount"));assertEquals(24,doc.getInt("fps"));
   String selection=System.getenv("VIDEOSTUDIO_HUMAN_MOTION_CASES");boolean allMotion=selection!=null&&Arrays.asList(selection.split(",")).contains(label);JSONArray hashes=new JSONArray();try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){for(int frame=0;frame<frames;frame++){if(!allMotion&&!Set.of(0,frames/6,frames/3,frames/2,frames*2/3,frames*5/6,frames-1).contains(frame))continue;Bitmap b=renderer.render(frame);File png=new File(folder,String.format(Locale.ROOT,"frames/frame-%04d.png",frame));try{String baselineRoot=System.getenv("VIDEOSTUDIO_HUMAN_BASELINE");if(baselineRoot!=null){File oldPng=new File(new File(baselineRoot,label),String.format(Locale.ROOT,"frames/frame-%04d.png",frame));if(oldPng.isFile()){Bitmap old=BitmapFactory.decodeFile(oldPng.getAbsolutePath());assertNotNull(old);try{assertTrue("Previously successful native artwork changed: "+label+" frame "+frame,old.sameAs(b));record.put("baselinePixelComparisons",record.optInt("baselinePixelComparisons",0)+1);}finally{old.recycle();}}}SketchActionFilmTest.png(png,b);}finally{b.recycle();}hashes.put(new JSONObject().put("frame",frame).put("sha256",SketchProjectStore.fileHash(png)));}}
   SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","case_archive"));Files.copy(archive.file.toPath(),new File(folder,"editable.vssketch").toPath(),StandardCopyOption.REPLACE_EXISTING);
   JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"matrix_restored_"+UUID.randomUUID().toString().replace("-",""),"Restored capability case");try(SketchProjectStore store=new SketchProjectStore(context);SketchFrameRenderer a=new SketchFrameRenderer(context,doc);SketchFrameRenderer b=new SketchFrameRenderer(context,store.get(restored.getString("sketchId")))){for(int frame:new int[]{frames/2,frames-1}){Bitmap x=a.render(frame),y=b.render(frame);try{assertTrue("Actual archive-restored native pixels",x.sameAs(y));}finally{x.recycle();y.recycle();}}}
   if(audio!=null){Files.copy(audio.toPath(),new File(folder,"audio.wav").toPath(),StandardCopyOption.REPLACE_EXISTING);record.put("audioSha256",SketchProjectStore.fileHash(audio)).put("audioEngine","Actual retained Open Pipeline Kokoro model output").put("speechIntelligibility","UNREVIEWED").put("forcedAlignmentVerified",false);}
   record.put("technical","PASS").put("screenshots",hashes).put("allFramesRendered",allMotion).put("archivePixelComparisons",2).put("allFrameValidation","Native constructor/document validation checks the entire unchanged pose clock, reach and bounds; sparse screenshots are probes unless allFramesRendered=true");
  }catch(Exception|AssertionError e){failures++;record.put("technical","FAIL").put("failure",e.getClass().getSimpleName()+": "+e.getMessage());}
  write(new File(folder,"case-evidence.json"),record.toString(2));write(new File(root,"Case-Index-in-progress.json"),new JSONObject().put("cases",records).put("distinctCases",records.length()).put("technicalFailures",failures).toString(2));
 }
 private static JSONArray handPoses(String pattern)throws Exception {
  JSONArray a=new JSONArray();int[] frames={0,18,36,60,78,95};for(int n=0;n<frames.length;n++){double amount=n==0||n==5?.3:n==1?.65:n<4?.9:.25;JSONObject p=new JSONObject().put("frame",frames[n]).put("interpolation","smooth").put("spread",.25).put("thumbOpposition",amount*.75).put("thumbCurl",amount*.7).put("wristFlex",n==0||n==5?0:5);
   String[] fingers={"indexCurl","middleCurl","ringCurl","littleCurl"};for(int f=0;f<4;f++){double curl=amount;if(pattern.equals("finger_cascade"))curl=n>f&&n<5?.08:.8;if(pattern.equals("thumb_opposition"))curl=f==0?amount*.6:.25;p.put(fingers[f],curl);}a.put(p);}return a;
 }
 private static void write(File file,String text)throws Exception {file.getParentFile().mkdirs();Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8));}
}
