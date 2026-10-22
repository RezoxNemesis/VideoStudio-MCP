package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.lang.reflect.Field;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Ordinary controls must author one native shared motion without per-layer compensation. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE,qualifiers="w720dp-h1440dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchLayerGroupMotionUiTest {
    final Context context=RuntimeEnvironment.getApplication();
    String create()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{return worker.submit(()->{String id="group_ui_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("name","Held parts").put("width",512).put("height",256).put("frameCount",48));
            JSONArray operations=new JSONArray();for(int i=0;i<2;i++)operations.put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","part_"+i).put("name","Part "+i).put("transform",SketchDocument.transform().put("x",.1+i*.2)))).put(new JSONObject().put("type","add_object").put("layerId","part_"+i).put("object",new JSONObject().put("id","mark_"+i).put("kind","path").put("frame",0).put("exposure",48).put("commands",new JSONArray("[['M',.2,.2],['L',.3,.4]]"))));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","parts").put("expectedRevision",1).put("operations",operations));return id;}).get(45,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
    }
    ActivityController<SketchStudioActivity> open(String id)throws Exception {
        ActivityController<SketchStudioActivity> c=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id));c.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).commit();c.create().start().resume().visible();waitIo(c.get());return c;
    }
    AlertDialog group(SketchStudioActivity studio)throws Exception {
        click(studio.getWindow().getDecorView(),"More");choose("Frames / Cels");choose("Group motion");waitIo(studio);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertEquals("Move layers together",Shadows.shadowOf(dialog).getTitle().toString());return dialog;
    }
    void author(AlertDialog dialog)throws Exception {
        View root=dialog.getWindow().getDecorView();((CheckBox)description(root,"Group layer part_0")).setChecked(true);((CheckBox)description(root,"Group layer part_1")).setChecked(true);
        edit(root,"Group pivot X (pixels)","256");edit(root,"Group pivot Y (pixels)","128");
        ((Spinner)description(root,"Group pose")).setSelection(1);ShadowLooper.idleMainLooper();
        edit(root,"Group pose frame (1 based)","48");edit(root,"Group delta X (pixels)","51.2");edit(root,"Group delta Y (pixels)","25.6");edit(root,"Group rotation (degrees)","0");click(root,"Add / update pose");
    }
    @Test public void framesMenuSavesBothAnchorsInOnePreparedUndoableEdit()throws Exception {
        String id=create();JSONObject before=doc(id);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityController<SketchStudioActivity> c=open(id)){
            SketchStudioActivity studio=c.get();AlertDialog dialog=group(studio);author(dialog);SketchEditorSession session=(SketchEditorSession)field(studio,"session");
            ((ExecutorService)field(session,"writer")).execute(()->{entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            click(dialog.getWindow().getDecorView(),"Apply group motion");assertEquals("Native planning must be queued instead of running optimistically on the main thread",before.getLong("revision"),session.document().getLong("revision"));assertTrue(session.hasPreparedSaves());
            release.countDown();session.awaitIdle(30000);waitIo(studio);assertNull(session.error());JSONObject saved=doc(id);assertEquals(before.getLong("revision")+1,saved.getLong("revision"));
            for(int i=0;i<2;i++){JSONObject a=SketchDocument.layer(before,"part_"+i),b=SketchDocument.layer(saved,"part_"+i);assertEquals(SketchDocument.canonical(a.getJSONArray("objects")),SketchDocument.canonical(b.getJSONArray("objects")));assertEquals(SketchDocument.transformAt(a,47).getDouble("x")+.1,SketchDocument.transformAt(b,47).getDouble("x"),1e-12);assertEquals(.1,SketchDocument.transformAt(b,47).getDouble("y"),1e-12);}
            click(studio.getWindow().getDecorView(),"Undo");waitIo(studio);waitIo(studio);JSONObject undone=doc(id);for(int i=0;i<2;i++)assertEquals(SketchDocument.canonical(SketchDocument.layer(before,"part_"+i)),SketchDocument.canonical(SketchDocument.layer(undone,"part_"+i)));
        }finally{release.countDown();}
    }
    @Test public void previewUsesTheNativeDraftWithoutPersistingAndCloseCancelsTheDraft()throws Exception {
        String id=create();String before=SketchDocument.canonical(doc(id));try(ActivityController<SketchStudioActivity> c=open(id)){AlertDialog dialog=group(c.get());author(dialog);click(dialog.getWindow().getDecorView(),"Preview group motion");
            Object owner=field(c.get(),"layerGroupMotionDialog");((ExecutorService)field(owner,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();ImageView image=(ImageView)description(dialog.getWindow().getDecorView(),"Group motion preview");assertNotNull("The selected frame is rendered with native artwork",image.getDrawable());assertEquals(before,SketchDocument.canonical(doc(id)));capture(dialog);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(before,SketchDocument.canonical(doc(id)));}
    }
    @Test public void sourceEditWhileDialogIsOpenCannotApplyTheOldGroupDraft()throws Exception {
        String id=create();try(ActivityController<SketchStudioActivity> c=open(id)){SketchStudioActivity studio=c.get();AlertDialog dialog=group(studio);author(dialog);studio.onOperation(new JSONObject().put("type","update_layer").put("layerId","part_0").put("patch",new JSONObject().put("name","Human revision")));SketchEditorSession session=(SketchEditorSession)field(studio,"session");session.awaitIdle(30000);waitIo(studio);long newer=doc(id).getLong("revision");click(dialog.getWindow().getDecorView(),"Apply group motion");session.awaitIdle(30000);waitIo(studio);assertTrue("A stale draft remains available with an explanation",dialog.isShowing());assertEquals(newer,doc(id).getLong("revision"));assertEquals(0,SketchDocument.layer(doc(id),"part_1").getJSONArray("keys").length());}
    }
    JSONObject doc(String id)throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
    static void capture(AlertDialog dialog)throws Exception {
        String output=System.getenv("VIDEOSTUDIO_NATIVE_LAYER_GROUP_MOTION_UI");if(output==null||!output.startsWith("/"))return;
        File directory=new File(output);if(!directory.mkdirs()&&!directory.isDirectory())throw new java.io.IOException("Cannot retain native group motion UI capture");View root=dialog.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));root.layout(0,0,720,1440);View preview=description(root,"Group motion preview");preview.requestRectangleOnScreen(new android.graphics.Rect(0,0,preview.getWidth(),preview.getHeight()),true);Bitmap bitmap=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(new File(directory,"native-group-motion-manual-preview-ui.png"))){root.draw(new Canvas(bitmap));if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("Native UI PNG encoding failed");}finally{bitmap.recycle();}
    }
    static Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void waitIo(SketchStudioActivity studio)throws Exception {for(int n=0;n<3;n++){((ExecutorService)field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    static void edit(View root,String label,String value){View v=description(root,label);assertTrue("Missing numeric control: "+label,v instanceof EditText);((EditText)v).setText(value);}
    static View description(View view,String text){if(text.equals(String.valueOf(view.getContentDescription())))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=description(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    static void click(View root,String text){Button button=button(root,text);assertNotNull("Missing ordinary control: "+text,button);button.performClick();}
    static Button button(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=button(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    static void choose(String label){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(d);ListView list=d.getListView();assertNotNull(list);for(int i=0;i<list.getCount();i++)if(label.equals(String.valueOf(list.getAdapter().getItem(i)))){list.performItemClick(list.getAdapter().getView(i,null,list),i,i);return;}fail("Missing ordinary menu choice: "+label);}
}
