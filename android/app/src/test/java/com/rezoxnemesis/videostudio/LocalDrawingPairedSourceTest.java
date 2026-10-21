package com.rezoxnemesis.videostudio;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Shared native paired inputs using synthetic authored/reference pixels, not artistic evidence. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class LocalDrawingPairedSourceTest {
 LocalDrawingFrameSourceTest frame;Context context;JSONObject request;
 @Before public void fixture()throws Exception {
  frame=new LocalDrawingFrameSourceTest();frame.authoredLayers();context=frame.context;
  File original=new File(context.getFilesDir(),"paired_reference.png");Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.RED);Paint paint=new Paint();paint.setColor(Color.GREEN);canvas.drawRect(128,0,256,256,paint);try(FileOutputStream out=new FileOutputStream(original)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
  ProjectStore projects=new ProjectStore(context);ProjectStore.Project owner=projects.create("Synthetic appearance reference");ProjectStore.Asset asset=projects.registerGeneratedAsset(owner,Uri.fromFile(original),"paired_reference.png","test_fixture",false);
  SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",frame.id).put("operationId","appearance").put("expectedRevision",2).put("projectId",owner.id).put("sourceProjectUpdatedAt",projects.get(owner.id).updatedAt).put("assetId",asset.id).put("layerId","excluded").put("objectId","appearance_guide"));
  JSONObject document=frame.document();request=SketchDocument.copy(frame.request).put("requestId","paired_study").put("expectedRevision",document.getLong("revision"));request.getJSONObject("frameSource").put("documentSha256",SketchDocument.sha256(SketchDocument.canonical(document).getBytes(StandardCharsets.UTF_8)));
  request.put("appearanceSource",new JSONObject().put("objectId","appearance_guide").put("sourceSha256",SketchDocument.object(document,"appearance_guide").getJSONObject("reference").getString("sha256")).put("sourceRect",new JSONObject().put("x",.25).put("y",.25).put("width",.5).put("height",.5))).put("conditioning",new JSONObject().put("appearanceStrength",.75).put("structureStrength",.8));
 }
 JSONObject execute(JSONObject parameters)throws Exception{return frame.execute(parameters);}
 private Bitmap image(JSONObject result,String field,int edge)throws Exception {JSONObject png=result.getJSONObject(field);byte[] bytes=android.util.Base64.decode(png.getString("base64"),0);assertEquals("image/png",png.getString("mimeType"));assertEquals(bytes.length,png.getInt("bytes"));assertEquals(SketchDocument.sha256(bytes),png.getString("sha256"));assertEquals(edge,png.getInt("width"));assertEquals(edge,png.getInt("height"));Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);assertEquals(edge,bitmap.getWidth());assertEquals(edge,bitmap.getHeight());return bitmap;}
 @Test public void previewShowsActualSeparateAppearanceAndCannyInputsWithoutModelOrReceipt()throws Exception {
  String before=SketchDocument.canonical(frame.document());JSONObject preview=execute(SketchDocument.copy(request).put("previewOnly",true));assertTrue(preview.getBoolean("sourcePreviewOnly"));assertFalse(preview.getBoolean("inferenceExecuted"));assertEquals("sd15_ipadapter_controlnet_v1",preview.getString("packId"));
  Bitmap primary=image(preview,"localDrawingSourcePreviewImage",512),appearance=image(preview,"localDrawingAppearancePreviewImage",224),structure=image(preview,"localDrawingStructurePreviewImage",512);
  try{assertEquals(Color.BLUE,primary.getPixel(256,256));assertEquals(Color.RED,appearance.getPixel(10,100));assertEquals(Color.GREEN,appearance.getPixel(210,100));assertEquals(Color.BLACK,structure.getPixel(10,10));int white=0;for(int y=0;y<512;y++)for(int x=0;x<512;x++){int color=structure.getPixel(x,y);assertTrue("Canny input is actual binary opaque guide",color==Color.WHITE||color==Color.BLACK);if(color==Color.WHITE)white++;}assertTrue("Authored rectangle has structural edges",white>100&&white<10000);}finally{primary.recycle();appearance.recycle();structure.recycle();}
  assertEquals(request.getJSONObject("appearanceSource").getString("sourceSha256"),preview.getJSONObject("appearanceSourceIdentity").getString("sourceReferenceSha256"));assertEquals(before,SketchDocument.canonical(frame.document()));assertFalse(new File(context.getFilesDir(),"local_drawing/studies").exists());
 }
 @Test public void absentPairedModelFreezesBothInputsAndRetryIdentityWithoutFallback()throws Exception {
  Exception failure=assertThrows(Exception.class,()->execute(request));assertTrue(failure.getMessage(),failure.getMessage().contains("MODEL_NOT_INSTALLED"));JSONObject receipt=LocalDrawingOperations.execute(context,"local_drawing_get",new JSONObject().put("requestId","paired_study"),null);assertEquals("failed",receipt.getString("state"));assertEquals("sd15_ipadapter_controlnet_v1",receipt.getString("provider"));
  File root=new File(context.getFilesDir(),"local_drawing/studies/paired_study");assertEquals(receipt.getString("preparedAppearanceSha256"),SketchProjectStore.fileHash(new File(root,"appearance.png")));assertEquals(receipt.getString("preparedStructureSha256"),SketchProjectStore.fileHash(new File(root,"structure.png")));String source=SketchProjectStore.fileHash(new File(root,"source.png"));
  JSONObject changed=SketchDocument.copy(request);changed.getJSONObject("conditioning").put("appearanceStrength",.5);Exception conflict=assertThrows(Exception.class,()->execute(changed));assertTrue(conflict.getMessage(),conflict.getMessage().contains("REQUEST_ID_CONFLICT"));assertEquals(source,SketchProjectStore.fileHash(new File(root,"source.png")));
 }
 @Test public void staleAppearanceAndStrictModeErrorsNeverAdmitAStudy()throws Exception {
  for(String variant:new String[]{"stale","missing_frame","missing_controls","missing_appearance","null_source","coerced_strength","unknown_control","bad_crop"}){JSONObject invalid=SketchDocument.copy(request).put("requestId","bad_"+variant);if(variant.equals("stale"))invalid.getJSONObject("appearanceSource").put("sourceSha256","a".repeat(64));if(variant.equals("missing_frame"))invalid.remove("frameSource");if(variant.equals("missing_controls"))invalid.remove("conditioning");if(variant.equals("missing_appearance"))invalid.remove("appearanceSource");if(variant.equals("null_source"))invalid.put("appearanceSource",JSONObject.NULL);if(variant.equals("coerced_strength"))invalid.getJSONObject("conditioning").put("structureStrength","0.8");if(variant.equals("unknown_control"))invalid.getJSONObject("conditioning").put("guaranteesIdentity",true);if(variant.equals("bad_crop"))invalid.getJSONObject("appearanceSource").getJSONObject("sourceRect").put("x",.9);assertThrows(variant,Exception.class,()->execute(invalid));assertFalse(new File(context.getFilesDir(),"local_drawing/studies/"+invalid.getString("requestId")+"/record.json").exists());}
 }
 @Test public void explicitPairedPackCatalogueIsSeparateAndDoesNotClaimInference()throws Exception {
  JSONObject status=LocalDrawingOperations.execute(context,"local_drawing_models",new JSONObject().put("packId","sd15_ipadapter_controlnet_v1"),null);assertEquals("sd15_ipadapter_controlnet_v1",status.getString("packId"));assertFalse(status.getBoolean("modelInstalled"));assertFalse(status.getBoolean("inferenceVerified"));assertTrue(status.getLong("requiredAvailableRamMb")>=8192);assertTrue(status.getLong("modelBytes")>3752836130L);
 }
 @Test public void manualPairedPreviewUsesTheSameThreePreparedNativeImages()throws Exception {
  try(org.robolectric.android.controller.ActivityController<LocalDrawingActivity> controller=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(context,LocalDrawingActivity.class).putExtra("sketchId",frame.id).putExtra("frameIndex",4)).create().start().resume().visible()){
   LocalDrawingActivity activity=controller.get();idle(activity);((android.widget.Spinner)field(activity,"inputMode")).setSelection(2);org.robolectric.shadows.ShadowLooper.idleMainLooper();android.view.View root=activity.getWindow().getDecorView();button(root,"Choose frame layers").performClick();android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();android.widget.ListView layers=dialog.getListView();layers.setItemChecked(1,false);layers.performItemClick(null,1,1);dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();for(String[] pair:new String[][]{{"x","0.1"},{"y","0.2"},{"w","0.4"},{"h","0.4"},{"appearanceX","0.25"},{"appearanceY","0.25"},{"appearanceW","0.5"},{"appearanceH","0.5"}})((android.widget.EditText)field(activity,pair[0])).setText(pair[1]);button(root,"Preview source").performClick();idle(activity);assertNotNull(field(activity,"shownAppearance"));assertNotNull(field(activity,"shownStructure"));assertEquals(224,((Bitmap)field(activity,"shownAppearance")).getWidth());assertEquals(512,((Bitmap)field(activity,"shownStructure")).getWidth());assertTrue(((android.widget.TextView)field(activity,"status")).getText().toString().contains("Source preview"));assertFalse(new File(context.getFilesDir(),"local_drawing/studies").exists());
  }
 }
 private static Object field(Object target,String name)throws Exception{java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
 private static void idle(Object target)throws Exception{((ExecutorService)field(target,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);org.robolectric.shadows.ShadowLooper.idleMainLooper();}
 private static android.widget.Button button(android.view.View view,String text){if(view instanceof android.widget.Button&&text.contentEquals(((android.widget.Button)view).getText()))return(android.widget.Button)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.widget.Button found=button(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
