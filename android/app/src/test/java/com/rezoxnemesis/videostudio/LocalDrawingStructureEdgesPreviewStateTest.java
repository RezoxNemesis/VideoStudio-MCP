package com.rezoxnemesis.videostudio;
import android.content.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.util.concurrent.*;
import static org.junit.Assert.*;
/** Actual manual input-preview state. Stale pixels must not masquerade as current threshold inputs. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class LocalDrawingStructureEdgesPreviewStateTest {
 static Object field(Object a,String key)throws Exception{return LocalDrawingPlusProviderTest.field(a,key);}
 static Button button(View root,String text){Button b=LocalDrawingPlusProviderTest.button(root,text);assertNotNull(text,b);return b;}
 static CheckBox custom(View root){return(CheckBox)LocalDrawingStructureEdgesUiTest.find(root,"Custom structure edges");}
 static EditText low(View root){return(EditText)LocalDrawingStructureEdgesUiTest.find(root,"Low edge threshold (1–1020)");}
 static EditText high(View root){return(EditText)LocalDrawingStructureEdgesUiTest.find(root,"High edge threshold (1–1020)");}
 static void preview(LocalDrawingActivity a,View root)throws Exception{button(root,"Preview source").performClick();LocalDrawingPlusProviderTest.idle(a);assertNotNull("The actual native structural input must first be displayed",field(a,"shownStructure"));assertNotNull(((ImageView)field(a,"structurePreview")).getDrawable());assertTrue((Boolean)field(a,"showingSourcePreview"));}
 static void invalidated(LocalDrawingActivity a,String edit)throws Exception{assertNull("Changed "+edit+" must release the previous structural input bitmap",field(a,"shownStructure"));assertNull("Changed "+edit+" must remove the previous structural input pixels",((ImageView)field(a,"structurePreview")).getDrawable());}
 @Test public void eachEdgeControlEditInvalidatesDisplayedInputsWithoutChangingSavedStudyRetry()throws Exception{LocalDrawingPairedSourceTest f=new LocalDrawingPairedSourceTest();f.fixture();try(ActivityController<LocalDrawingActivity> c=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(f.context,LocalDrawingActivity.class).putExtra("sketchId",f.frame.id).putExtra("frameIndex",4)).create().start().resume().visible()){
  LocalDrawingActivity a=c.get();LocalDrawingPlusProviderTest.idle(a);View root=a.getWindow().getDecorView();((Spinner)field(a,"inputMode")).setSelection(2);ShadowLooper.idleMainLooper();assertNotNull(custom(root));assertNotNull(low(root));assertNotNull(high(root));button(root,"Generate new draft").performClick();LocalDrawingPlusProviderTest.idle(a);JSONObject saved=LocalDrawingStructureEdgesUiTest.record(a,f);String savedRequest=SketchDocument.canonical(saved.getJSONObject("request")),savedId=saved.getString("requestId"),source=SketchDocument.canonical(f.frame.document());
  preview(a,root);custom(root).setChecked(true);ShadowLooper.idleMainLooper();invalidated(a,"edge enablement");assertTrue("Removing an input preview must not turn it into an eligible generated draft",(Boolean)field(a,"showingSourcePreview"));
  preview(a,root);low(root).setText("40");ShadowLooper.idleMainLooper();invalidated(a,"low threshold");
  preview(a,root);high(root).setText("400");ShadowLooper.idleMainLooper();invalidated(a,"high threshold");
  assertEquals(savedRequest,SketchDocument.canonical(LocalDrawingStructureEdgesUiTest.record(a,f).getJSONObject("request")));button(root,"Resume saved study").performClick();LocalDrawingPlusProviderTest.idle(a);JSONObject resumed=LocalDrawingStructureEdgesUiTest.record(a,f);assertEquals(savedId,resumed.getString("requestId"));assertEquals(savedRequest,SketchDocument.canonical(resumed.getJSONObject("request")));assertEquals(source,SketchDocument.canonical(f.frame.document()));
 }}
 @Test public void pendingOldPreviewCannotAppearAsTheInputForEditedThresholds()throws Exception{LocalDrawingPairedSourceTest f=new LocalDrawingPairedSourceTest();f.fixture();try(ActivityController<LocalDrawingActivity> c=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(f.context,LocalDrawingActivity.class).putExtra("sketchId",f.frame.id).putExtra("frameIndex",4)).create().start().resume().visible()){
  LocalDrawingActivity a=c.get();LocalDrawingPlusProviderTest.idle(a);View root=a.getWindow().getDecorView();((Spinner)field(a,"inputMode")).setSelection(2);custom(root).setChecked(true);ShadowLooper.idleMainLooper();button(root,"Preview source").performClick();((ExecutorService)field(a,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);assertNull("Pause main dispatch after actual native preprocessing completes",field(a,"shownStructure"));
  if(!low(root).isEnabled()){
   // Disabling all3controls while work is pending is an acceptable alternative
   // to a generation token: a real user cannot change the captured parameters.
   assertFalse(custom(root).isEnabled());assertFalse(high(root).isEnabled());ShadowLooper.idleMainLooper();assertNotNull("Unchanged captured inputs may be displayed",field(a,"shownStructure"));assertTrue("Controls must be restored after work finishes",low(root).isEnabled());low(root).setText("40");ShadowLooper.idleMainLooper();invalidated(a,"post-completion threshold");
  }else{
   // Current baseline permits this edit while the old callback is queued.
   low(root).setText("40");ShadowLooper.idleMainLooper();invalidated(a,"threshold changed before old callback");
  }
 }}
}
