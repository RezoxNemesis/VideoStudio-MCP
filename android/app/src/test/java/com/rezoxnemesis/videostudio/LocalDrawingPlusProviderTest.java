package com.rezoxnemesis.videostudio;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Normal native surface selector/preview/receipt proof; synthetic pixels are not artistic evidence. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class LocalDrawingPlusProviderTest {
 static final String PLUS="sd15_ipadapter_plus_controlnet_v1";
 LocalDrawingPairedSourceTest fixture;
 @Before public void prepare()throws Exception {fixture=new LocalDrawingPairedSourceTest();fixture.fixture();}
 JSONObject request(String adapter)throws Exception {JSONObject p=SketchDocument.copy(fixture.request);p.getJSONObject("conditioning").put("adapter",adapter);return p;}
 @Test public void omissionRetainsExistingBasicSelectionAndWireIdentity()throws Exception {
  JSONObject old=SketchDocument.copy(fixture.request);String before=LocalDrawingRequestIdentity.fingerprint("a".repeat(64),old);LocalDrawingSource.validate(old);assertEquals("sd15_ipadapter_controlnet_v1",LocalDrawingModels.forRequest(old));assertEquals(before,LocalDrawingRequestIdentity.fingerprint("a".repeat(64),old));assertFalse(old.getJSONObject("conditioning").has("adapter"));
 }
 @Test public void bothExplicitAdaptersPreviewAndFreezeTheirOwnReceiptWithoutBasicFallback()throws Exception {
  for(String adapter:new String[]{"plus_face","plus"}){JSONObject p=request(adapter).put("requestId","study_"+adapter);JSONObject preview=fixture.execute(SketchDocument.copy(p).put("previewOnly",true));assertEquals(PLUS,preview.getString("packId"));assertFalse(preview.getBoolean("inferenceExecuted"));assertTrue(preview.has("localDrawingAppearancePreviewImage"));assertTrue(preview.has("localDrawingStructurePreviewImage"));assertFalse(new File(fixture.context.getFilesDir(),"local_drawing/studies/"+p.getString("requestId")+"/record.json").exists());
   Exception absent=assertThrows(Exception.class,()->fixture.execute(p));assertTrue(absent.toString(),absent.toString().contains("MODEL_NOT_INSTALLED"));JSONObject receipt=LocalDrawingOperations.execute(fixture.context,"local_drawing_get",new JSONObject().put("requestId",p.getString("requestId")),null);assertEquals(PLUS,receipt.getString("provider"));assertEquals("failed",receipt.getString("state"));JSONObject record=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(fixture.context.getFilesDir(),"local_drawing/studies/"+p.getString("requestId")+"/record.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(adapter,record.getJSONObject("request").getJSONObject("conditioning").getString("adapter"));JSONObject changed=SketchDocument.copy(p);changed.getJSONObject("conditioning").put("adapter",adapter.equals("plus")?"plus_face":"plus");assertTrue(assertThrows(Exception.class,()->fixture.execute(changed)).toString().contains("REQUEST_ID_CONFLICT"));
  }
 }
 @Test public void explicitPackPinsDerivedGraphsAndSeparateHeadsWithoutClaimingInference()throws Exception {
  JSONObject status=LocalDrawingOperations.execute(fixture.context,"local_drawing_models",new JSONObject().put("packId",PLUS),null);assertEquals("4.10.8",status.getString("minimumNativeVersion"));assertFalse(status.getBoolean("inferenceVerified"));assertTrue(status.getLong("requiredAvailableRamMb")>=8192);assertFalse(status.getBoolean("guaranteesAnatomyOrLikeness"));JSONObject catalog=new LocalDrawingModels(fixture.context,PLUS).catalogue();JSONArray files=catalog.getJSONArray("files");long total=0;int derived=0,heads=0;for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);total+=file.getLong("bytes");if(file.has("derivedFrom")){derived++;JSONObject source=file.getJSONObject("derivedFrom");assertTrue(source.getString("sha256").matches("[a-f0-9]{64}"));assertTrue(source.getString("url").contains("/resolve/"));assertNotEquals(source.getString("sha256"),file.getString("sha256"));}if(file.getString("path").startsWith("ip_adapter/"))heads++;}assertEquals(2,derived);assertEquals(2,heads);assertEquals(total,catalog.getLong("modelBytes"));assertEquals(4680923615L,total);
 }
 @Test public void adapterRequiresClosedStrictStringSelection()throws Exception {
  for(Object bad:new Object[]{"basic","PLUS_FACE","",true,1,JSONObject.NULL}){JSONObject p=SketchDocument.copy(fixture.request);p.getJSONObject("conditioning").put("adapter",bad);assertThrows(String.valueOf(bad),Exception.class,()->LocalDrawingSource.validate(p));}
 }
 @Test public void manualAdapterSelectionUsesSamePackAndPreviewInputs()throws Exception {
  try(org.robolectric.android.controller.ActivityController<LocalDrawingActivity> control=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(fixture.context,LocalDrawingActivity.class).putExtra("sketchId",fixture.frame.id).putExtra("frameIndex",4)).create().start().resume().visible()){
   LocalDrawingActivity activity=control.get();idle(activity);View root=activity.getWindow().getDecorView();Spinner adapter=findSpinner(root,"Appearance adapter");assertNotNull("Manual users need the same explicit adapter control",adapter);((Spinner)field(activity,"inputMode")).setSelection(2);adapter.setSelection(1);org.robolectric.shadows.ShadowLooper.idleMainLooper();button(root,"Check local model").performClick();idle(activity);assertTrue(((TextView)field(activity,"status")).getText().toString().contains("Model not installed"));button(root,"Preview source").performClick();idle(activity);assertTrue(((TextView)field(activity,"status")).getText().toString().contains("Source preview"));assertEquals(224,((Bitmap)field(activity,"shownAppearance")).getWidth());assertFalse(new File(fixture.context.getFilesDir(),"local_drawing/studies").exists());
  }
 }
 static Object field(Object target,String name)throws Exception {java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
 static void idle(Object target)throws Exception {((ExecutorService)field(target,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);org.robolectric.shadows.ShadowLooper.idleMainLooper();}
 static Spinner findSpinner(View view,String description){if(view instanceof Spinner&&description.contentEquals(view.getContentDescription()))return(Spinner)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Spinner found=findSpinner(group.getChildAt(i),description);if(found!=null)return found;}}return null;}
 static Button button(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=button(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
