package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.Spinner;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchCloneSamplingUiTest {
 Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
 void invoke(Object target,String name)throws Exception{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}
 void idle(SketchStudioActivity studio)throws Exception{((ExecutorService)get(studio,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
 Spinner sampling(View view){if(view instanceof Spinner&&"Clone sampling".equals(String.valueOf(view.getContentDescription())))return (Spinner)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Spinner s=sampling(((ViewGroup)view).getChildAt(i));if(s!=null)return s;}return null;}
 AlertDialog open(SketchStudioActivity studio)throws Exception{invoke(studio,"paintSettings");ShadowLooper.idleMainLooper();AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(d);return d;}
 void select(Spinner spinner,String label){for(int i=0;i<spinner.getCount();i++)if(label.equals(spinner.getItemAtPosition(i).toString())){spinner.setSelection(i);return;}fail("Missing clone sampling choice "+label);}
 String canonical(JSONObject value)throws Exception{return SketchDocument.canonical(new JSONObject(value.toString()));}
 JSONObject source(Context c,String id)throws Exception{
  Bitmap image=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);image.eraseColor(0xffb0b0b0);File folder=new File(c.getFilesDir(),"sketch_studio/"+id+"/references");assertTrue(folder.isDirectory()||folder.mkdirs());File temp=new File(folder,"fixture.png");try(FileOutputStream out=new FileOutputStream(temp)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}String hash=SketchProjectStore.fileHash(temp);assertTrue(temp.renameTo(new File(folder,hash+".png")));
  JSONObject reference=new JSONObject().put("file",hash+".png").put("sha256",hash).put("width",16).put("height",16).put("sourceProjectId","synthetic").put("sourceAssetId","fixture");
  JSONObject bounds=new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1);
  JSONObject object=new JSONObject().put("id","owned").put("kind","image").put("reference",reference).put("bounds",bounds);
  JSONObject add=new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object);
  JSONObject edit=new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","add_owned_reference").put("operations",new JSONArray().put(add));
  SketchEditOps.execute(c,"sketch_edit",edit);
  return new JSONObject().put("objectId","owned").put("sha256",hash).put("offsetX",.17).put("offsetY",-.1).put("scale",.8).put("rotation",12);
 }
 @Test public void manualCloneSamplingPreservesOmissionExplicitChoicesCancelAndOwnedSourceSettings()throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",2));JSONObject original=source(c,id);
  try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
   SketchStudioActivity studio=controller.get();idle(studio);set(studio,"paintCloneSource",SketchDocument.copy(original));set(studio,"paintMode","clone");invoke(studio,"updateBrush");
   AlertDialog dialog=open(studio);Spinner spinner=sampling(dialog.getWindow().getDecorView());assertNotNull("Paint settings need a real Clone sampling control",spinner);assertEquals("Legacy default",spinner.getSelectedItem().toString());assertTrue(spinner.isEnabled());dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(studio);assertEquals(canonical(original),canonical((JSONObject)get(studio,"paintCloneSource")));
   for(String label:new String[]{"Bilinear (native pixels)","Nearest"}){dialog=open(studio);spinner=sampling(dialog.getWindow().getDecorView());select(spinner,label);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(studio);JSONObject source=(JSONObject)get(studio,"paintCloneSource"),expected=SketchDocument.copy(original).put("sampling",label.startsWith("Bilinear")?"bilinear":"nearest");assertEquals(canonical(expected),canonical(source));assertEquals(canonical(source),canonical((JSONObject)get(get(studio,"canvas"),"cloneSource")));dialog=open(studio);spinner=sampling(dialog.getWindow().getDecorView());assertEquals(label,spinner.getSelectedItem().toString());dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle(studio);}
   dialog=open(studio);spinner=sampling(dialog.getWindow().getDecorView());select(spinner,"Bilinear (native pixels)");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle(studio);assertEquals("nearest",((JSONObject)get(studio,"paintCloneSource")).getString("sampling"));
   dialog=open(studio);spinner=sampling(dialog.getWindow().getDecorView());select(spinner,"Legacy default");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(studio);assertEquals(canonical(original),canonical((JSONObject)get(studio,"paintCloneSource")));
   set(studio,"paintCloneSource",null);dialog=open(studio);spinner=sampling(dialog.getWindow().getDecorView());assertNotNull(spinner);assertFalse("Choose an owned source before choosing its sampler",spinner.isEnabled());dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
  }
 }
}
