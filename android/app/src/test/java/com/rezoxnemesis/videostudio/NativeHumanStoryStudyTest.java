package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
/** Evidence orchestration only; all authoring, sampling and drawing uses public product surfaces. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeHumanStoryStudyTest {
 @Test public void originalShippedStoryPreviewsUseActualSemanticOperationsAndNativeCanvas()throws Exception {
  String evidence=System.getenv("VIDEOSTUDIO_STORY_EVIDENCE");Assume.assumeTrue(evidence!=null);Context c=RuntimeEnvironment.getApplication();File root=new File(evidence);root.mkdirs();
  JSONObject plan;try(InputStream in=c.getAssets().open("animation_stories/the-last-lantern.json");ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] a=new byte[32768];int n;while((n=in.read(a))!=-1)b.write(a,0,n);plan=new JSONObject(b.toString("UTF-8"));}write(new File(root,"plan.json"),plan);JSONArray index=new JSONArray();int global=0,failures=0;
  boolean full="true".equals(System.getenv("VIDEOSTUDIO_STORY_FULL")),bookMode="true".equals(System.getenv("VIDEOSTUDIO_STORY_BOOK"));
  JSONObject built=null;
  if(bookMode){
   // Interrupt after the first actual page, then resume the exact same app request.
   try{SketchStoryPlanOps.create(c,plan,(done,total)->{if(done==1)throw new InterruptedException("Evidence cancellation after first durable cel");});fail("Cancellation hook did not execute");}catch(InterruptedException expected){write(new File(root,"cancelled-after-first-page.json"),new JSONObject().put("cancelled",true).put("reason",expected.getMessage()));}
   try(SketchBookStore books=new SketchBookStore(c)){assertEquals(1,books.get(plan.getString("bookId")).getInt("createdPages"));}
   built=SketchStoryPlanOps.create(c,plan,(done,total)->{if(done%64==0||done==total)write(new File(root,"Book-Progress.json"),new JSONObject().put("completedNativePages",done).put("totalPages",total));});write(new File(root,"Book-Receipt.json"),built);
   try(SketchBookStore books=new SketchBookStore(c)){JSONObject book=books.get(plan.getString("bookId"));assertEquals(2976,book.getInt("createdPages"));assertEquals(24,book.getInt("fps"));write(new File(root,"Book-Metadata.json"),book);}
   JSONObject replay=SketchStoryPlanOps.execute(c,"sketch_story_create",plan);assertEquals(SketchDocument.canonical(built),SketchDocument.canonical(replay));
  }
  for(int s=0;s<plan.getJSONArray("shots").length();s++){
   JSONObject shot=plan.getJSONArray("shots").getJSONObject(s);String id=shot.getString("shotId");int frames=shot.getInt("frameCount");String selected=System.getenv("VIDEOSTUDIO_STORY_SHOTS");if(selected!=null&&!Arrays.asList(selected.split(",")).contains(id)){global+=frames;continue;}JSONObject record=new JSONObject().put("shotId",id).put("label",shot.getString("label")).put("globalStartFrame",global).put("frameCount",frames).put("fps",plan.getInt("fps")).put("visual","UNREVIEWED").put("appVersion",AppProtocol.APP_VERSION);index.put(record);
   try{JSONObject prepared=SketchStoryPlanOps.preparePreview(c,plan,id);write(new File(root,id+"/source-document.json"),prepared.getJSONObject("sourceDocument"));write(new File(root,id+"/program.json"),prepared.getJSONObject("program"));SketchAnimationCompiler.Sampler sampler=SketchAnimationCompiler.prepare(prepared.getJSONObject("sourceDocument"),prepared.getJSONObject("program"));JSONArray hashes=new JSONArray();for(int f=0;f<frames;f++){if(!full&&!Set.of(0,frames/6,frames/3,frames/2,frames*2/3,frames*5/6,frames-1).contains(f))continue;
     boolean compare=f==0||f==frames/2||f==frames-1;JSONObject response=!bookMode||compare?sampler.preview(f,"review_"+id):null;JSONObject doc=response==null?null:response.getJSONObject("document");if(bookMode){try(SketchProjectStore sketches=new SketchProjectStore(c)){doc=sketches.get(plan.getString("bookId")+"_p"+(global+f));}if(f==0||f==frames/2||f==frames-1){try(SketchFrameRenderer a=new SketchFrameRenderer(c,response.getJSONObject("document"));SketchFrameRenderer b=new SketchFrameRenderer(c,doc)){Bitmap x=a.render(0),y=b.render(0);try{assertTrue("Saved book order and native sampled programme agree",x.sameAs(y));}finally{x.recycle();y.recycle();}}}}
     try(SketchFrameRenderer renderer=new SketchFrameRenderer(c,doc)){Bitmap image=renderer.render(0);File out=new File(root,String.format(Locale.ROOT,"%s/frames/frame-%04d.png",id,f));try{SketchActionFilmTest.png(out,image);}finally{image.recycle();}hashes.put(new JSONObject().put("localFrame",f).put("globalFrame",global+f).put("sha256",SketchProjectStore.fileHash(out)));}
     if(f==frames/2)write(new File(root,id+"/sampled-document.json"),doc);
   }record.put("technical","PASS").put("nativeFrames",hashes).put("allFramesRendered",full);
   }catch(Exception|AssertionError e){failures++;record.put("technical","FAIL").put("failure",e.toString());}
   if(bookMode){JSONObject shotMeta=built.getJSONArray("shots").getJSONObject(s);String sourceId=shotMeta.getString("sourceSketchId");SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(c,new JSONObject().put("sketchId",sourceId).put("expectedRevision",1).put("operationId","story_source_archive_"+id));File file=new File(root,id+"/editable-source.vssketch");Files.copy(archive.file.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);JSONObject restored=SketchPaintArchive.restoreFile(c,archive.file,SketchProjectStore.fileHash(archive.file),"restored_"+id+"_"+UUID.randomUUID().toString().replace("-",""),"Restored native story source");try(SketchProjectStore sketches=new SketchProjectStore(c);SketchFrameRenderer a=new SketchFrameRenderer(c,sketches.get(sourceId));SketchFrameRenderer b=new SketchFrameRenderer(c,sketches.get(restored.getString("sketchId")))){for(int f:new int[]{0,frames/2,frames-1}){Bitmap x=a.render(f),y=b.render(f);try{assertTrue("Archive-restored source pixels",x.sameAs(y));}finally{x.recycle();y.recycle();}}}record.put("sourceArchivePixelComparisons",3).put("sourceArchiveSha256",SketchProjectStore.fileHash(file)).put("bookPixelComparisons",3);}
   global+=frames;write(new File(root,"Shot-Index-in-progress.json"),new JSONObject().put("shots",index).put("failures",failures));
  }
  write(new File(root,"Shot-Index.json"),new JSONObject().put("shots",index).put("frameCount",global).put("fps",24).put("durationSeconds",global/24d).put("renderer","VideoStudio native Android Canvas / workspace Robolectric").put("phoneExecution",false).put("androidVideoEncodingVerified",false).put("audioMixedIntoSketch",false).put("technicalFailures",failures).put("renderedFromSavedBook",bookMode).put("uniqueHandDrawnFramesClaimed",false));assertEquals(2976,global);assertEquals(0,failures);
 }
 private static void write(File file,JSONObject data)throws Exception{file.getParentFile().mkdirs();Files.write(file.toPath(),data.toString(2).getBytes(StandardCharsets.UTF_8));}
}
