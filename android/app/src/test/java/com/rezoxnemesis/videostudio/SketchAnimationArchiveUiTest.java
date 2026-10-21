package com.rezoxnemesis.videostudio;
import android.content.*;
import android.view.*;
import android.widget.Button;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLooper;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** The actual menu follows the same revision-bound archive path as MCP. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAnimationArchiveUiTest {
 @Test public void manualSceneArchiveFreezesTheSavedRecipeAndRetainsItsTransferRetry()throws Exception {
  Context context=RuntimeEnvironment.getApplication();SketchAnimationArchiveTest fixture=new SketchAnimationArchiveTest();JSONObject source=fixture.sketches.create();fixture.save(source,"manual_scene");
  try(ActivityController<SketchAnimationActivity> controller=Robolectric.buildActivity(SketchAnimationActivity.class,new Intent(context,SketchAnimationActivity.class).putExtra("animationId","manual_scene").putExtra("sketchId",source.getString("id"))).create().start().resume().visible()){
   SketchAnimationActivity activity=controller.get();settle(activity);Button archive=find(activity.findViewById(android.R.id.content),"Archive scene");assertNotNull("Scene packages must have an ordinary manual entry",archive);assertTrue(archive.performClick());settle(activity);
   // This fixture has no linked remote project. Native preparation still runs;
   // the unverified transfer must retain its exact request for a later retry.
   String pending=activity.getPreferences(0).getString("pendingAnimationArchive","");assertFalse(pending.isEmpty());JSONObject args=new JSONObject(pending);
   assertEquals("manual_scene",args.getString("animationId"));assertEquals(1,args.getLong("animationRevision"));assertEquals(source.getString("id"),args.getString("sketchId"));assertEquals(source.getLong("revision"),args.getLong("expectedRevision"));
   File generated=new File(context.getFilesDir(),"sketch_studio/"+args.getString("sketchId")+"/archives/"+args.getString("operationId")+"/completed.json");assertTrue("The real native archive writer must have completed before transfer",generated.isFile());
   JSONObject receipt=SketchPaintArchive.parseOwnedJson(java.nio.file.Files.readAllBytes(generated.toPath()));assertTrue(receipt.getBoolean("animationRecipeIncluded"));assertEquals(SketchAnimationArchive.ARCHIVE_SCHEMA,receipt.getString("schema"));
   assertFalse((Boolean)get(activity,"saving"));assertFalse((Boolean)get(activity,"dirty"));
   assertEquals(1,SketchAnimationOps.load(context,"manual_scene").getLong("revision"));
  }
 }
 static Button find(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
 static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 static void settle(Object target)throws Exception{ExecutorService io=(ExecutorService)get(target,"io");for(int i=0;i<4;i++){io.submit(()->{}).get(20,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
}
