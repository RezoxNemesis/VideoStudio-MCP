package com.rezoxnemesis.videostudio;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Actual manual preview -> saved draft -> key placement -> preview, without Refresh. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class LocalDrawingManualSnapshotTest {
 @Test public void openingPairedDraftAsGuideRetainsVerifiedPortableProvenanceInArchive()throws Exception {
  LocalDrawingPairedKeyframeTest paired=new LocalDrawingPairedKeyframeTest();paired.pairedReceipt();LocalDrawingKeyframeTest fixture=paired.fixture;
  try(org.robolectric.android.controller.ActivityController<LocalDrawingActivity> controller=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(fixture.context,LocalDrawingActivity.class).putExtra("sketchId","animation")).create().start().resume().visible()){
   LocalDrawingActivity activity=controller.get();idle(activity);View root=activity.getWindow().getDecorView();activity.getPreferences(Context.MODE_PRIVATE).edit().putString("lastRequest_animation","key_fixture").commit();button(root,"Refresh references").performClick();idle(activity);
   button(root,"Show saved draft").performClick();idle(activity);assertTrue(status(activity),status(activity).contains("completed"));button(root,"Use draft as a new drawing guide").performClick();idle(activity);
   Intent opened=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull("Manual guide flow opens its new sketch",opened);String id=opened.getStringExtra("sketchId");JSONObject guide;try(SketchProjectStore sketches=new SketchProjectStore(fixture.context)){guide=sketches.get(id);}
   JSONObject object=SketchDocument.object(guide,"ai_generated_reference"),reference=object.getJSONObject("reference");assertEquals(0,object.getInt("frame"));assertEquals(1,object.getInt("exposure"));assertTrue("The manual new-guide route must retain verified native AI provenance",reference.has("localDrawing"));JSONObject provenance=reference.getJSONObject("localDrawing");assertEquals(fixture.record.getString("modelIdentity"),provenance.getString("modelIdentity"));assertEquals(SketchDocument.canonical(fixture.record.getJSONObject("request")),SketchDocument.canonical(provenance.getJSONObject("request")));
   SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(fixture.context,new JSONObject().put("sketchId",id).put("operationId","archive_ai_guide").put("expectedRevision",guide.getLong("revision")));assertTrue(fixture.output.delete());JSONObject restored=SketchPaintArchive.restoreFile(fixture.context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_ai_guide","Restored paired guide");try(SketchProjectStore sketches=new SketchProjectStore(fixture.context)){assertEquals(SketchDocument.canonical(provenance),SketchDocument.canonical(SketchDocument.object(sketches.get(restored.getString("sketchId")),"ai_generated_reference").getJSONObject("reference").getJSONObject("localDrawing")));}
  }
 }
 @Test public void placingKeyKeepsFrameInputCurrentAndPreservesChosenLayersAndCrop()throws Exception {
  LocalDrawingKeyframeTest fixture=new LocalDrawingKeyframeTest();fixture.fixture();
  JSONObject blue=new JSONObject().put("id","authored_blue").put("kind","rect").put("frame",0).put("exposure",24).put("color","#FF0000FF").put("fill",true).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1));
  JSONObject red=SketchDocument.copy(blue).put("id","excluded_red").put("color","#FFFF0000");
  SketchEditOps.execute(fixture.context,"sketch_edit",new JSONObject().put("sketchId","animation").put("operationId","source_layers").put("expectedRevision",1).put("operations",new JSONArray()
   .put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",blue))
   .put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","excluded").put("name","Excluded red")))
   .put(new JSONObject().put("type","add_object").put("layerId","excluded").put("object",red))));
  try(org.robolectric.android.controller.ActivityController<LocalDrawingActivity> controller=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(fixture.context,LocalDrawingActivity.class).putExtra("sketchId","animation").putExtra("frameIndex",8)).create().start().resume().visible()){
   LocalDrawingActivity activity=controller.get();idle(activity);View root=activity.getWindow().getDecorView();
   activity.getPreferences(Context.MODE_PRIVATE).edit().putString("lastRequest_animation","key_fixture").commit();button(root,"Refresh references").performClick();idle(activity);
   ((Spinner)field(activity,"inputMode")).setSelection(1);org.robolectric.shadows.ShadowLooper.idleMainLooper();
   button(root,"Choose frame layers").performClick();android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue("App selection initially includes red",((boolean[])field(activity,"frameLayerSelection"))[1]);org.robolectric.shadows.ShadowLooper.idleMainLooper();View dialogView=dialog.getWindow().getDecorView();dialogView.measure(View.MeasureSpec.makeMeasureSpec(512,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(512,View.MeasureSpec.EXACTLY));dialogView.layout(0,0,512,512);org.robolectric.shadows.ShadowLooper.idleMainLooper();ListView layers=dialog.getListView();assertTrue("Laid-out red layer starts selected",layers.isItemChecked(1));layers.performItemClick(null,1,1);assertFalse("One actual UI click excludes red",layers.isItemChecked(1));dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();org.robolectric.shadows.ShadowLooper.idleMainLooper();assertFalse("Chosen red exclusion is retained",((boolean[])field(activity,"frameLayerSelection"))[1]);
   for(String[] entry:new String[][]{{"x","0.1"},{"y","0.1"},{"w","0.8"},{"h","0.8"},{"keyExposure","4"}})((EditText)field(activity,entry[0])).setText(entry[1]);
   button(root,"Preview source").performClick();idle(activity);assertTrue(status(activity),status(activity).contains("Source preview"));assertEquals(Color.BLUE,((Bitmap)field(activity,"shown")).getPixel(256,256));
   button(root,"Show saved draft").performClick();idle(activity);assertTrue(status(activity),status(activity).contains("completed"));
   button(root,"Place draft as an animation key").performClick();idle(activity);assertEquals(3,fixture.document().getLong("revision"));
   button(root,"Preview source").performClick();idle(activity);
   assertTrue("Preview immediately after own key placement must use the updated snapshot: "+status(activity),status(activity).contains("Source preview · revision 3"));
   assertEquals("Excluded red layer must remain excluded after placement",Color.BLUE,((Bitmap)field(activity,"shown")).getPixel(256,256));
   assertEquals("0.1",((EditText)field(activity,"x")).getText().toString());assertEquals("0.8",((EditText)field(activity,"w")).getText().toString());assertEquals("9",((EditText)field(activity,"sourceFrame")).getText().toString());
   assertEquals(3,fixture.document().getLong("revision"));
  }
 }
 private static String status(Object activity)throws Exception{return((TextView)field(activity,"status")).getText().toString();}
 private static void idle(Object target)throws Exception {((ExecutorService)field(target,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);org.robolectric.shadows.ShadowLooper.idleMainLooper();}
 private static Object field(Object target,String name)throws Exception {java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 private static Button button(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=button(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
