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
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
/** Actual manual preview/save/retry request flow; no optional model or art acceptance. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class LocalDrawingStructureEdgesUiTest {
 static View find(View v,String label){if(v.getContentDescription()!=null&&label.contentEquals(v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
 static JSONObject record(LocalDrawingActivity a,LocalDrawingPairedSourceTest f)throws Exception{String id=(String)LocalDrawingPlusProviderTest.field(a,"lastRequest");assertFalse(id.isEmpty());return new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(f.context.getFilesDir(),"local_drawing/studies/"+id+"/record.json").toPath()),StandardCharsets.UTF_8));}
 @Test public void manualOptionalEdgesPreserveOffRequestsAndSharePreviewGenerateAndSavedRetry()throws Exception{LocalDrawingPairedSourceTest f=new LocalDrawingPairedSourceTest();f.fixture();try(ActivityController<LocalDrawingActivity> c=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(f.context,LocalDrawingActivity.class).putExtra("sketchId",f.frame.id).putExtra("frameIndex",4)).create().start().resume().visible()){LocalDrawingActivity a=c.get();LocalDrawingPlusProviderTest.idle(a);View root=a.getWindow().getDecorView();CheckBox custom=(CheckBox)find(root,"Custom structure edges");assertNotNull("Manual users need the same explicit native edge preparation",custom);assertFalse(custom.isChecked());((Spinner)LocalDrawingPlusProviderTest.field(a,"inputMode")).setSelection(2);ShadowLooper.idleMainLooper();LocalDrawingPlusProviderTest.button(root,"Generate new draft").performClick();LocalDrawingPlusProviderTest.idle(a);assertFalse(record(a,f).getJSONObject("request").getJSONObject("conditioning").has("structureEdges"));custom.setChecked(true);((EditText)find(root,"Low edge threshold (1–1020)")).setText("20");((EditText)find(root,"High edge threshold (1–1020)")).setText("50");LocalDrawingPlusProviderTest.button(root,"Preview source").performClick();LocalDrawingPlusProviderTest.idle(a);assertNotNull(LocalDrawingPlusProviderTest.field(a,"shownStructure"));LocalDrawingPlusProviderTest.button(root,"Generate new draft").performClick();LocalDrawingPlusProviderTest.idle(a);JSONObject saved=record(a,f),edges=saved.getJSONObject("request").getJSONObject("conditioning").getJSONObject("structureEdges");assertEquals(20,edges.getInt("low"));assertEquals(50,edges.getInt("high"));String id=saved.getString("requestId");LocalDrawingPlusProviderTest.button(root,"Resume saved study").performClick();LocalDrawingPlusProviderTest.idle(a);assertEquals(id,record(a,f).getString("requestId"));assertEquals(SketchDocument.canonical(saved.getJSONObject("request")),SketchDocument.canonical(record(a,f).getJSONObject("request")));}}
}
