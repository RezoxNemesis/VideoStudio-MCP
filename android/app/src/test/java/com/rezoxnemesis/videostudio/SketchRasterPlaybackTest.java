package com.rezoxnemesis.videostudio;

import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.Button;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import static org.junit.Assert.*;

/** Real native canvas + actual Play control. A gated worker makes slow rendering deterministic. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchRasterPlaybackTest {
 @Test public void playKeepsCompletedArtworkVisibleWhileNextRasterIsStillRendering()throws Exception{
  try(Fixture f=new Fixture()){
   f.ready();String source=f.source();button(f.studio.getWindow().getDecorView(),"Play").performClick();assertTrue((boolean)field(f.studio,"playing"));
   CountDownLatch gate=f.block();try{
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(42));assertEquals(1,f.canvas.getFrame());
    Bitmap image=f.draw();try{evidence("waiting-next-frame.png",image);assertEquals("A slow renderer must retain the last completed artwork instead of showing black",Color.RED,image.getPixel(200,112));assertTrue("Buffered playback must identify the held raster separately from the source clock",((android.widget.TextView)field(f.studio,"previewStatus")).getText().toString().contains("Preview buffered"));}finally{image.recycle();}
   }finally{gate.countDown();f.drain();}
   assertEquals("Preview must not retime or rewrite source animation",source,f.source());
  }
 }
 @Test public void advancingTimelineDoesNotStarveAnInFlightFramePublication()throws Exception{
  try(Fixture f=new Fixture()){
   f.ready();button(f.studio.getWindow().getDecorView(),"Play").performClick();CountDownLatch gate=f.block();
   try{f.canvas.setFrame(1);f.draw().recycle();f.canvas.setFrame(2);}finally{gate.countDown();}
   f.drain();assertTrue("A completed frame from the current source must publish even if the play clock advanced",((Map<?,?>)field(f.canvas,"frameCache")).containsKey(1));
   Bitmap image=f.draw();try{evidence("completed-in-flight-frame.png",image);assertEquals(Color.RED,image.getPixel(200,112));}finally{image.recycle();}
  }
 }
 @Test public void sourceReplacementStillRejectsAnOlderInFlightPublication()throws Exception{
  try(Fixture f=new Fixture()){
   f.ready();button(f.studio.getWindow().getDecorView(),"Play").performClick();CountDownLatch gate=f.block();
   try{f.canvas.setFrame(1);f.draw().recycle();JSONObject other=SketchDocument.create(new JSONObject().put("sketchId","replacement").put("width",1600).put("height",900).put("fps",24).put("frameCount",96).put("background","#FF0000FF"));f.canvas.setDocument(other);}finally{gate.countDown();}
   f.drain();Bitmap first=f.draw();try{assertNotEquals("The previous document must never leak into the new canvas",Color.RED,first.getPixel(200,112));}finally{first.recycle();}
   f.drain();Bitmap ready=f.draw();try{assertEquals(Color.BLUE,ready.getPixel(200,112));}finally{ready.recycle();}
  }
 }
 private static final class Fixture implements AutoCloseable{
  final Context context=RuntimeEnvironment.getApplication();final String id="play_"+UUID.randomUUID();
  final ActivityController<SketchStudioActivity> controller;final SketchStudioActivity studio;final SketchCanvasView canvas;final ExecutorService worker;
  Fixture()throws Exception{
   SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",1600).put("height",900).put("fps",24).put("frameCount",96).put("background","#FF000000"));
   JSONObject object=new JSONObject().put("id","visible_art").put("kind","rect").put("fill",true).put("color","#FFFF0000").put("frame",0).put("exposure",96).put("bounds",new JSONObject().put("x",.25).put("y",.25).put("width",.5).put("height",.5));
   SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","draw").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object))));
   controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id));studio=controller.get();studio.getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).remove("lastBookId").commit();controller.create().start().resume().visible();
   for(int i=0;i<4;i++){((ExecutorService)field(studio,"io")).submit(()->{}).get(15,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
   canvas=(SketchCanvasView)field(studio,"canvas");canvas.setOnion(false);
   // Keep the existing deterministic viewport when status updates trigger a window traversal.
   ViewGroup.LayoutParams viewport=canvas.getLayoutParams();viewport.width=400;viewport.height=225;canvas.setLayoutParams(viewport);canvas.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(225,View.MeasureSpec.EXACTLY));canvas.layout(0,0,400,225);worker=(ExecutorService)field(canvas,"rasterWorker");
  }
  Bitmap draw(){Bitmap b=Bitmap.createBitmap(400,225,Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(b));return b;}
  void ready()throws Exception{draw().recycle();drain();Bitmap b=draw();try{assertEquals("Fixture is visible before playing",Color.RED,b.getPixel(200,112));}finally{b.recycle();}}
  void drain()throws Exception{worker.submit(()->{}).get(15,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
  CountDownLatch block()throws Exception{CountDownLatch gate=new CountDownLatch(1),started=new CountDownLatch(1);worker.execute(()->{started.countDown();try{gate.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(10,TimeUnit.SECONDS));return gate;}
  String source()throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return SketchDocument.canonical(store.get(id));}}
  public void close(){controller.pause().stop().destroy();}
 }
 private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 private static Button button(View v,String text){if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return(Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),text);if(b!=null)return b;}return null;}
 private static void evidence(String name,Bitmap b)throws Exception{String root=System.getProperty("preview.evidence");if(root==null)return;File dir=new File(root);dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
}
