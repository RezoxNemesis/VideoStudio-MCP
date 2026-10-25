package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real owned PNG imports and native Canvas; only scheduling is gated, never the decoder. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE,qualifiers="w1080dp-h1920dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchPreviewRecoveryTest {
 @Test public void corruptOwnedReferenceShowsPersistentFailureAndOnlyManualRetryRecovers()throws Exception{
  try(Fixture f=new Fixture()){
   String unchanged=f.persisted();byte[] original=Files.readAllBytes(f.owned.toPath());Files.write(f.owned.toPath(),new byte[]{1,2,3});
   ShadowToast.reset();f.draw();f.drain();f.capture("corrupt-reference-ui.png");
   assertTrue("A failed owned reference must leave a visible persistent error with Retry",f.text().contains("Preview unavailable"));
   assertTrue(f.text().contains("Retry"));assertTrue(f.text().contains("SKETCH_REFERENCE_CHANGED"));
   int failedGeneration=(int)field(f.canvas,"rasterGeneration");
   f.play();for(int i=1;i<=20;i++){f.canvas.setFrame(i);f.draw();f.drain();}
   assertEquals("Frame ticks must not submit repeated failures",failedGeneration+1,(int)field(f.canvas,"rasterGeneration"));
   assertEquals("Async errors must not spam transient toasts",0,ShadowToast.shownToastCount());
   Files.write(f.owned.toPath(),original);f.draw();f.drain();assertTrue("Restoring a dependency alone must not clear the latched error",f.text().contains("Preview unavailable"));
   f.pausePlayback();CountDownLatch gate=f.block();try{f.more("Retry preview");f.draw();f.capture("retry-loading-ui.png");assertTrue(f.text().contains("Loading preview"));}finally{gate.countDown();}
   f.settle();assertTrue(f.text(),f.text().contains("Preview ready"));f.capture("retry-recovered-ui.png");assertEquals("Recovered frame must contain the restored owned reference",Color.RED,f.pixel());assertEquals(unchanged,f.persisted());
  }
 }
 @Test public void hashValidButUndecodableOwnedPngIsVisibleAndRetryable()throws Exception{
  try(Fixture f=new Fixture()){
   JSONObject doc=SketchDocument.copy(f.doc),ref=SketchDocument.object(doc,"image").getJSONObject("reference");
   byte[] invalid="A real file that is not a PNG".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=SketchDocument.sha256(invalid);File invalidFile=new File(f.owned.getParentFile(),hash+".png");Files.write(invalidFile.toPath(),invalid);ref.put("sha256",hash).put("file",hash+".png");
   f.present(doc);f.draw();f.drain();f.capture("decode-failure-ui.png");
   assertTrue("Native BitmapFactory decode failure must persist on screen",f.text().contains("Owned reference PNG decode failed"));
   assertTrue(f.text().contains("Preview unavailable"));assertEquals(0,((Map<?,?>)field(f.canvas,"frameCache")).size());
   f.present(f.doc);f.settle();assertEquals(Color.RED,f.pixel());assertTrue(f.text().contains("Preview ready"));
  }
 }
 @Test public void loadingIsVisibleAndRefreshIsAnExplicitMoreAction()throws Exception{
  try(Fixture f=new Fixture()){
   CountDownLatch gate=f.block();try{f.draw();f.capture("initial-loading-ui.png");assertTrue("An in-flight preview needs visible loading status",f.text().contains("Loading preview"));}finally{gate.countDown();}
   f.drain();f.draw();assertTrue(f.text().contains("Preview ready"));
   CountDownLatch refresh=f.block();try{f.more("Refresh preview");f.draw();assertTrue(f.text().contains("Loading preview"));}finally{refresh.countDown();}f.drain();
  }
 }
 @Test public void pauseRejectsLatePublicationAndResumeCanLoadAgain()throws Exception{
  try(Fixture f=new Fixture()){
   CountDownLatch gate=f.block();try{f.draw();f.controller.pause();}finally{gate.countDown();}f.drain();
   assertTrue("A paused activity must reject its in-flight raster",((Map<?,?>)field(f.canvas,"frameCache")).isEmpty());
   f.draw();f.drain();assertTrue(((Map<?,?>)field(f.canvas,"frameCache")).isEmpty());
   f.controller.resume();f.draw();f.drain();f.draw();assertEquals(Color.RED,f.pixel());
  }
 }
 @Test public void viewportChangeRejectsInFlightPublicationDuringPlayback()throws Exception{
  try(Fixture f=new Fixture()){
   f.draw();f.drain();f.play();CountDownLatch gate=f.block();try{f.canvas.setFrame(1);f.draw();set(f.canvas,"zoom",2f);f.draw();}finally{gate.countDown();}f.drain();
   assertTrue("A raster for the old viewport must not publish after zoom",((Map<?,?>)field(f.canvas,"frameCache")).isEmpty());
   f.draw();f.drain();f.draw();assertEquals(Color.RED,f.pixel());assertTrue((long)field(f.canvas,"cacheBytes")<=12L*1024*1024);
  }
 }
 @Test public void newInvitationAndNonstandardStopResetPlaybackMode()throws Exception{
  try(Fixture f=new Fixture()){
   f.play();assertTrue((boolean)field(f.canvas,"playbackPreview"));
   Intent invitation=new Intent(f.context,SketchStudioActivity.class).setAction(SketchLiveNotification.ACTION_WATCH).putExtra(SketchLiveNotification.EXTRA_SKETCH_ID,f.id).putExtra(SketchLiveNotification.EXTRA_SESSION_ID,"test_stream");
   call(f.studio,"onNewIntent",new Class[]{Intent.class},invitation);assertFalse("Invitation must reset the canvas playback mode immediately",(boolean)field(f.canvas,"playbackPreview"));
   f.flushIo();f.play();call(f.studio,"onStop",new Class[]{});assertFalse("Stop without pause must also reset playback",(boolean)field(f.canvas,"playbackPreview"));
  }
 }
 @Test public void disposedViewRejectsLateErrorAndNewDocumentRejectsOldFailure()throws Exception{
  try(Fixture f=new Fixture()){
   Files.write(f.owned.toPath(),new byte[]{3,4,5});CountDownLatch gate=f.block();try{f.draw();JSONObject other=SketchDocument.create(new JSONObject().put("sketchId","different").put("width",1600).put("height",900).put("background","#FF0000FF"));f.present(other);}finally{gate.countDown();}f.drain();f.draw();f.drain();f.draw();assertEquals(Color.BLUE,f.pixel());assertFalse(f.text().contains("unavailable"));
   CountDownLatch disposal=f.block();try{f.canvas.setFrame(1);f.draw();f.canvas.dispose();}finally{disposal.countDown();}assertTrue(f.worker.awaitTermination(15,TimeUnit.SECONDS));ShadowLooper.idleMainLooper();assertTrue(((Map<?,?>)field(f.canvas,"frameCache")).isEmpty());
  }
 }
 @Test public void frameAdvanceFailureKeepsLastCompletedCorrectSourceWithoutRetryStorm()throws Exception{
  try(Fixture f=new Fixture()){
   f.settle();assertEquals(Color.RED,f.pixel());Files.write(f.owned.toPath(),new byte[]{9,8,7});f.play();f.canvas.setFrame(1);f.settle();
   assertTrue(f.text().contains("Preview unavailable"));assertEquals("A failed next frame must retain the last completed current-source image",Color.RED,f.pixel());
   int generation=(int)field(f.canvas,"rasterGeneration");for(int i=2;i<32;i++){f.canvas.setFrame(i);f.draw();}f.drain();assertEquals(generation,(int)field(f.canvas,"rasterGeneration"));f.capture("failed-next-frame-retained-ui.png");
   JSONObject replacement=SketchDocument.copy(f.doc);replacement.put("revision",f.doc.getLong("revision")+1).put("background","#FF0000FF");replacement.getJSONArray("layers").getJSONObject(0).put("objects",new JSONArray());
   f.present(replacement);CountDownLatch gate=f.block();try{f.draw();assertEquals("A changed revision must never display the previous source",Color.BLUE,f.pixel());}finally{gate.countDown();}f.settle();assertEquals(Color.BLUE,f.pixel());
  }
 }
 @Test public void playbackDisablesOnionRenderingAndKeepsTheManualPreference()throws Exception{
  try(Fixture f=new Fixture()){
   f.canvas.setOnion(true);f.settle();f.play();f.canvas.setFrame(2);f.settle();assertTrue("Playback must preserve the manual preference",(boolean)field(f.canvas,"onion"));
   assertEquals("Playback only prepares the selected frame, with no onion neighbors",1,((Map<?,?>)field(f.canvas,"frameCache")).size());
   f.pausePlayback();f.settle();assertTrue((boolean)field(f.canvas,"onion"));
   assertFalse("Playback mode should leave the canvas after tapping Pause",(boolean)field(f.canvas,"playbackPreview"));
   assertTrue("Onion raster not populated: frame="+f.canvas.getFrame()+", cache="+((Map<?,?>)field(f.canvas,"frameCache")).keySet()+", inFlight="+field(f.canvas,"rasterInFlight")+
           ", previewStatus="+f.canvas.previewStatus()+", rasterKey="+field(f.canvas,"rasterKey"),((Map<?,?>)field(f.canvas,"frameCache")).size()>1);
  }
 }
 private static final class Fixture implements AutoCloseable{
  final Context context=RuntimeEnvironment.getApplication();final String id="recovery_"+UUID.randomUUID();final JSONObject doc;final File owned;
  final ActivityController<SketchStudioActivity> controller;final SketchStudioActivity studio;final SketchCanvasView canvas;final ExecutorService worker;
  Fixture()throws Exception{
   SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",1600).put("height",900).put("fps",24).put("frameCount",96).put("background","#FF000000"));
   File file=new File(context.getFilesDir(),UUID.randomUUID()+".png");Bitmap b=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);b.eraseColor(Color.RED);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}b.recycle();
   ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Private native preview fixture");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source";asset.mime="image/png";asset.uri=Uri.fromFile(file).toString();asset.name="Fixture";project.assets.add(asset);projects.save(project);
   SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","reference").put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId","source").put("layerId","layer_1").put("objectId","image").put("frame",0).put("exposure",96));
   try(SketchProjectStore store=new SketchProjectStore(context)){doc=store.get(id);owned=store.referenceFile(id,SketchDocument.object(doc,"image").getJSONObject("reference"));}
   controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id));studio=controller.get();studio.getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).putBoolean("onionEnabled",true).remove("lastBookId").commit();controller.create().start().resume().visible();flushIo();
   canvas=(SketchCanvasView)field(studio,"canvas");worker=(ExecutorService)field(canvas,"rasterWorker");canvas.setOnion(false);layoutUi();
  }
  void flushIo()throws Exception{for(int i=0;i<4;i++){((ExecutorService)field(studio,"io")).submit(()->{}).get(15,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
  void layoutUi(){View decor=studio.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));decor.layout(0,0,1080,1920);}
  void draw(){Bitmap b=Bitmap.createBitmap(canvas.getWidth(),canvas.getHeight(),Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(b));b.recycle();}

  int pixel(){Bitmap b=Bitmap.createBitmap(canvas.getWidth(),canvas.getHeight(),Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(b));try{return b.getPixel(canvas.getWidth()/2,canvas.getHeight()/2);}finally{b.recycle();}}
  void settle()throws Exception{for(int i=0;i<3;i++){draw();drain();}draw();}
  void drain()throws Exception{worker.submit(()->{}).get(15,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
  CountDownLatch block()throws Exception{CountDownLatch gate=new CountDownLatch(1),started=new CountDownLatch(1);worker.execute(()->{started.countDown();try{gate.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(10,TimeUnit.SECONDS));return gate;}
  void play(){Button b=button(studio.getWindow().getDecorView(),"Play");if(b!=null)b.performClick();}
  void pausePlayback()throws Exception{Button b=button(studio.getWindow().getDecorView(),"Pause");assertNotNull("Manual animation Pause must be visible and distinct from live-stroke replay",b);assertEquals(View.VISIBLE,b.getVisibility());b.performClick();call(studio,"updateStatus",new Class[]{});}
  String text(){return texts(studio.getWindow().getDecorView());}
  void more(String label){button(studio.getWindow().getDecorView(),"More").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView list=dialog.getListView();for(int i=0;i<list.getAdapter().getCount();i++)if(label.equals(list.getAdapter().getItem(i).toString())){list.performItemClick(null,i,list.getAdapter().getItemId(i));return;}fail("More must expose "+label);}
  void present(JSONObject doc)throws Exception{call(studio,"present",new Class[]{JSONObject.class},doc);}
  String persisted()throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return SketchDocument.canonical(store.get(id));}}
  void capture(String name)throws Exception{String root=System.getProperty("preview.evidence");if(root==null)return;View decor=studio.getWindow().getDecorView();layoutUi();Bitmap image=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image));File target=new File(root,name);target.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(target)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}}
  public void close(){controller.pause().stop().destroy();}
 }
 private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
 private static Button button(View v,String title){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof Button&&title.contentEquals(((Button)v).getText()))return(Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),title);if(b!=null)return b;}return null;}
 private static String texts(View v){StringBuilder out=new StringBuilder();if(v.getVisibility()!=View.VISIBLE)return "";if(v instanceof TextView)out.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)out.append(texts(((ViewGroup)v).getChildAt(i)));return out.toString();}
}
