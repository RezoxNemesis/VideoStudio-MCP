package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Manual entry/worker requests only. Core/service suites separately verify native PNG RGBA. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SketchStillPngUiTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void fileMenuOffersReadOnlySelectedFrameSummaryAndCloseCreatesNoProject()throws Exception{
        String id=create();String before=document(id);int projects=projectCount();
        try(ActivityController<SketchStudioActivity> controller=open(id,1)){
            AlertDialog dialog=png(controller.get());View root=dialog.getWindow().getDecorView();
            assertNotNull("Selected zero-based frame1 must be shown as human frame2",text(root,"Frame 2 of 3"));
            assertNotNull(button(root,"Save PNG"));assertNotNull(button(root,"Retry saved PNG"));
            assertEquals("Opening PNG controls cannot publish output",projects,projectCount());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();awaitIo(controller.get());
            assertNull(nextSketchExport());assertEquals(before,document(id));assertEquals(projects,projectCount());
        }
    }

    @Test public void saveQueuesPinnedSelectedFrameOnWorkerAndReopenedRetryKeepsExactRequest()throws Exception{
        String id=create();String before=document(id);JSONObject first;
        try(ActivityController<SketchStudioActivity> controller=open(id,1)){
            SketchStudioActivity activity=controller.get();AlertDialog dialog=png(activity);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService io=(ExecutorService)field(activity,"io");
            io.execute(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{
                click(dialog.getWindow().getDecorView(),"Save PNG");assertNull("The UI thread must not submit around its queued save checkpoint",nextSketchExport());
            }finally{release.countDown();}
            awaitIo(activity);Intent intent=nextSketchExport();assertNotNull("Manual save must use the existing durable native export service route",intent);
            first=new JSONObject(intent.getStringExtra("requestJson"));
            assertEquals(id,first.getString("sketchId"));assertEquals(1,first.getInt("frame"));assertEquals(2,first.getLong("expectedRevision"));assertEquals("png",first.getString("format"));
            assertFalse(first.has("aspect"));assertFalse(first.has("quality"));assertFalse(first.has("bookId"));assertFalse(first.getString("operationId").isEmpty());
            assertEquals(before,document(id));dialog.dismiss();
        }
        try(ActivityController<SketchStudioActivity> controller=open(id,2)){
            AlertDialog dialog=png(controller.get());Button retry=button(dialog.getWindow().getDecorView(),"Retry saved PNG");assertNotNull(retry);assertTrue("Exact retry must survive Activity replacement",retry.isEnabled());retry.performClick();awaitIo(controller.get());
            Intent repeated=nextSketchExport();assertNotNull(repeated);assertEquals("Retry must not retarget the new current frame or revision",first.toString(),new JSONObject(repeated.getStringExtra("requestJson")).toString());
            assertEquals(before,document(id));dialog.dismiss();
        }
    }

    @Test public void navigatingBeforeWorkerAdmissionCannotExportOldOrNewDrawingAccidentally()throws Exception{
        String first=create(),second=create();String firstBefore=document(first),secondBefore=document(second);int projects=projectCount();
        try(ActivityController<SketchStudioActivity> controller=open(first,1)){
            SketchStudioActivity activity=controller.get();AlertDialog dialog=png(activity);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            ((ExecutorService)field(activity,"io")).execute(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{click(dialog.getWindow().getDecorView(),"Save PNG");invoke(activity,"loadSketch",new Class<?>[]{String.class,int.class},second,0);}finally{release.countDown();}
            awaitIo(activity);assertNull("A navigation-stale initial save cannot queue an export for either source",nextSketchExport());
            assertEquals(firstBefore,document(first));assertEquals(secondBefore,document(second));assertEquals(projects,projectCount());dialog.dismiss();
        }
    }

    @Test public void closingBeforeWorkerAdmissionDoesNotQueueAnInvisibleSave()throws Exception{
        String id=create();String before=document(id);int projects=projectCount();
        try(ActivityController<SketchStudioActivity> controller=open(id,1)){
            SketchStudioActivity activity=controller.get();AlertDialog dialog=png(activity);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            ((ExecutorService)field(activity,"io")).execute(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{click(dialog.getWindow().getDecorView(),"Save PNG");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();}finally{release.countDown();}
            awaitIo(activity);assertNull(nextSketchExport());assertEquals(before,document(id));assertEquals(projects,projectCount());
        }
    }

    private String create()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();try{return worker.submit(()->{
            String id="png_ui_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("name","PNG selected frame").put("width",512).put("height",256).put("frameCount",3).put("background","#00000000"));
            JSONArray operations=new JSONArray();for(int frame=0;frame<2;frame++)operations.put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","ink_"+frame).put("kind","path").put("frame",frame).put("exposure",1).put("color",frame==0?"#FFCC3311":"#FF2244CC").put("fill",true).put("commands",new JSONArray("[['M',.25,.25],['L',.75,.25],['L',.75,.75],['L',.25,.75],['Z']]"))));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","draw").put("expectedRevision",1).put("operations",operations));return id;
        }).get(30,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
    }
    private ActivityController<SketchStudioActivity> open(String id,int frame)throws Exception{
        ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id));
        controller.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).remove("lastBookId").commit();controller.create().start().resume().visible();awaitIo(controller.get());invoke(controller.get(),"setFrame",new Class<?>[]{int.class},frame);ShadowLooper.idleMainLooper();drainServices();return controller;
    }
    private AlertDialog png(SketchStudioActivity activity)throws Exception{
        click(activity.getWindow().getDecorView(),"File");AlertDialog menu=ShadowAlertDialog.getLatestAlertDialog();ListView list=menu.getListView();assertNotNull(list);boolean found=false;
        for(int i=0;i<list.getCount();i++)if("Save current frame as PNG".equals(String.valueOf(list.getAdapter().getItem(i)))){found=true;list.performItemClick(list.getAdapter().getView(i,null,list),i,i);break;}
        assertTrue("Sketch File menu must expose Save current frame as PNG",found);awaitIo(activity);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(menu,dialog);assertTrue(dialog.isShowing());ShadowLooper.idleMainLooper();return dialog;
    }
    private String document(String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return SketchDocument.canonical(store.get(id));}}
    private int projectCount()throws Exception{ProjectStore store=new ProjectStore(context);try{return store.list().size();}finally{Field f=ProjectStore.class.getDeclaredField("db");f.setAccessible(true);((android.database.sqlite.SQLiteDatabase)f.get(store)).close();}}
    private static void awaitIo(SketchStudioActivity activity)throws Exception{for(int i=0;i<3;i++){((ExecutorService)field(activity,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void invoke(Object o,String name,Class<?>[] types,Object... values)throws Exception{java.lang.reflect.Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,values);}
    private static void drainServices(){while(Shadows.shadowOf((Application)RuntimeEnvironment.getApplication()).getNextStartedService()!=null){}}
    private static Intent nextSketchExport(){Intent next;while((next=Shadows.shadowOf((Application)RuntimeEnvironment.getApplication()).getNextStartedService())!=null)if(ControlService.ACTION_LOCAL_SKETCH_EXPORT.equals(next.getAction()))return next;return null;}
    private static void click(View root,String label){Button button=button(root,label);assertNotNull("Missing reachable manual control: "+label,button);button.performClick();}
    private static TextView text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=text(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static Button button(View root,String label){TextView found=text(root,label);return found instanceof Button?(Button)found:null;}
}
