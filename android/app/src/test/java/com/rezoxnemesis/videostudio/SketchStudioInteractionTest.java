package com.rezoxnemesis.videostudio;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStudioInteractionTest {

    @Test @Config(qualifiers="w320dp-h640dp-mdpi") public void compactWorkspaceLeavesCanvasSpaceAndTouchTargetsAtLargeFont() throws Exception {
        Context c=RuntimeEnvironment.getApplication();android.content.res.Configuration config=c.getResources().getConfiguration();config.fontScale=1.5f;c.getResources().updateConfiguration(config,c.getResources().getDisplayMetrics());
        String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);View root=studio.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));root.layout(0,0,320,640);
            assertTrue("Canvas should occupy at least half a narrow phone",((View)field(studio,"canvas")).getHeight()>=320);
            for(String name:new String[]{"File","Draw myself","Undo","Redo","Export","Brush / Text","More","Play"}){Button b=button(root,name);assertNotNull(name,b);assertTrue(name+" touch height",b.getHeight()>=48);assertTrue(name+" touch width",b.getWidth()>=48);assertNotNull(b.getLayout());for(int line=0;line<b.getLayout().getLineCount();line++)assertEquals(name+" label must not be truncated",0,b.getLayout().getEllipsisCount(line));assertTrue(name+" label must fit vertically",b.getLayout().getHeight()<=b.getHeight()-b.getCompoundPaddingTop()-b.getCompoundPaddingBottom());int[] pos=new int[2];b.getLocationOnScreen(pos);assertTrue(name+" visible",pos[0]>=0&&pos[0]+b.getWidth()<=320);}
            assertTrue(((View)field(studio,"filmstrip")).getHeight()>=48);
        }
    }
    @Test public void groupedMenusKeepProfessionalOperationsAndManualLayerChangesReachable() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);button(studio.getWindow().getDecorView(),"Draw myself").performClick();waitIo(studio);
            button(studio.getWindow().getDecorView(),"More").performClick();choose("Layers");assertMenu("Select layer","+ Layer","Layer settings","Up","Down");choose("+ Layer");((SketchEditorSession)field(studio,"session")).awaitIdle(10000);ShadowLooper.idleMainLooper();
            assertEquals(2,((JSONObject)field(studio,"document")).getJSONArray("layers").length());
            button(studio.getWindow().getDecorView(),"More").performClick();choose("Frames / Cels");assertMenu("Timing / canvas","Exposure","Copy → next","Cel tools","Onion skin");
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();button(studio.getWindow().getDecorView(),"Brush / Text").performClick();assertMenu("Brush","Colours","Text","Finish path","Transform / key","Delete object","Reference image");
        }
    }
    @Test public void browsingColourLayerAndFrameDialogsPreservesLiveUntilAnActualSelection() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);startLive(studio);Object player=field(studio,"livePlayer");
            button(studio.getWindow().getDecorView(),"Brush / Text").performClick();choose("Colours");waitIo(studio);assertSame("Opening colours must preserve live player",player,field(studio,"livePlayer"));org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().cancel();
            button(studio.getWindow().getDecorView(),"More").performClick();choose("Layers");choose("Select layer");assertSame(player,field(studio,"livePlayer"));org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().cancel();
            button(studio.getWindow().getDecorView(),"More").performClick();choose("Frames / Cels");choose("Go to frame");assertSame(player,field(studio,"livePlayer"));org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().cancel();assertTrue((boolean)field(studio,"watching"));
            button(studio.getWindow().getDecorView(),"Brush / Text").performClick();choose("Colours");description(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(),"Colour #FFFFFFFF").performClick();waitIo(studio);
            assertFalse((boolean)field(studio,"watching"));assertNull(field(studio,"livePlayer"));assertEquals("#FFFFFFFF",field(studio,"brushColor"));assertNotNull(SketchDocument.object((JSONObject)field(studio,"document"),"agent_ink"));
        }
    }
    @Test public void layerChooserKeepsSnapshotIdentityWhenLayersReorder() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);button(studio.getWindow().getDecorView(),"Draw myself").performClick();waitIo(studio);
            studio.onOperation(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","target").put("name","Target layer")));((SketchEditorSession)field(studio,"session")).awaitIdle(10000);ShadowLooper.idleMainLooper();
            button(studio.getWindow().getDecorView(),"More").performClick();choose("Layers");choose("Select layer");
            studio.onOperation(new JSONObject().put("type","reorder_layer").put("layerId","target").put("index",0));choose("Target layer");
            assertEquals("Selected row must keep its original identity despite metadata reorder","target",((SketchCanvasView)field(studio,"canvas")).getLayerId());
        }
    }
    @Test public void layerChooserRejectsRemovedSnapshotAfterMetadataShrinks() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);button(studio.getWindow().getDecorView(),"Draw myself").performClick();waitIo(studio);
            studio.onOperation(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","target").put("name","Target layer")));((SketchEditorSession)field(studio,"session")).awaitIdle(10000);ShadowLooper.idleMainLooper();
            button(studio.getWindow().getDecorView(),"More").performClick();choose("Layers");choose("Select layer");
            studio.onOperation(new JSONObject().put("type","delete_layer").put("layerId","target"));choose("Target layer");
            assertEquals("layer_1",((SketchCanvasView)field(studio,"canvas")).getLayerId());assertEquals("That layer is no longer available. Open Layers to choose again.",org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void layerChooserValidatesSnapshotAgainAfterAuthoritativeLiveAdoption() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","add").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","target").put("name","Target layer")))));
        SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","live").put("expectedRevision",2));
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);startLive(studio);assertNotNull(field(studio,"livePlayer"));button(studio.getWindow().getDecorView(),"More").performClick();choose("Layers");choose("Select layer");
            SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","remote-delete").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","delete_layer").put("layerId","target"))));choose("Target layer");waitIo(studio);
            assertFalse((boolean)field(studio,"watching"));assertEquals("layer_1",((SketchCanvasView)field(studio,"canvas")).getLayerId());assertEquals("That layer is no longer available. Open Layers to choose again.",org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        }
    }
    private static View description(View view,String text){if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=description(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static void assertMenu(String... names){android.widget.ListAdapter adapter=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getListView().getAdapter();java.util.List<String> actual=new java.util.ArrayList<>();for(int i=0;i<adapter.getCount();i++)actual.add(String.valueOf(adapter.getItem(i)));for(String name:names)assertTrue(name,actual.contains(name));}
    private static void choose(String name){android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();android.widget.ListView list=dialog.getListView();for(int i=0;i<list.getCount();i++)if(name.equals(String.valueOf(list.getAdapter().getItem(i)))){list.performItemClick(list.getAdapter().getView(i,null,list),i,i);return;}fail("Missing menu item "+name);}
    @Test public void focusedCanvasKeepsTheLivePlayerAndRestoresToolsWithoutLosingGeometry() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);startLive(studio);Object player=field(studio,"livePlayer");assertNotNull(player);
            button(studio.getWindow().getDecorView(),"More").performClick();assertSame("Browsing menus preserves live playback",player,field(studio,"livePlayer"));org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
            Button brush=button(studio.getWindow().getDecorView(),"Brush / Text");button(studio.getWindow().getDecorView(),"File").performClick();choose("Canvas view");assertEquals(View.GONE,((View)brush.getParent()).getVisibility());assertSame(player,field(studio,"livePlayer"));
            button(studio.getWindow().getDecorView(),"File").performClick();choose("Canvas view");assertEquals(View.VISIBLE,((View)brush.getParent()).getVisibility());assertSame(player,field(studio,"livePlayer"));
        }
    }
    @Test public void aLaterWatchRequestCancelsAnEarlierLoadingPlayTakeover() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);startLive(studio);button(studio.getWindow().getDecorView(),"Play").performClick();
            assertTrue((boolean)field(studio,"loadingDrawing"));button(studio.getWindow().getDecorView(),"Watch ChatGPT").performClick();waitIo(studio);waitIo(studio);
            assertTrue((boolean)field(studio,"watching"));assertFalse("Later Watch intent must cancel pending Play",(boolean)field(studio,"playing"));assertFalse((boolean)field(studio,"loadingDrawing"));
        }
    }
    @Test public void aLiveBaselineThatFinishesAfterPauseDoesNotStartBackgroundPlayback() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);Method start=SketchStudioActivity.class.getDeclaredMethod("startLivePlayer",String.class);start.setAccessible(true);start.invoke(studio,"live");activity.pause();waitIo(studio);
            assertNull("Paused activities must not resurrect a live player",field(studio,"livePlayer"));activity.resume();waitIo(studio);
        }
    }
    @Test public void anAsyncPlayTakeoverThatFinishesAfterPauseDoesNotStartPlayback() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);startLive(studio);button(studio.getWindow().getDecorView(),"Play").performClick();activity.pause();waitIo(studio);
            assertFalse("A paused activity must not start playback from a late takeover callback",(boolean)field(studio,"playing"));activity.resume();waitIo(studio);assertFalse((boolean)field(studio,"loadingDrawing"));assertTrue(((SketchCanvasView)field(studio,"canvas")).isEnabled());
        }
    }
    @Test public void playTakesOverAnIdleLiveSessionWithoutDiscardingItsStrokes() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);startLive(studio);assertNotNull(field(studio,"livePlayer"));
            button(studio.getWindow().getDecorView(),"Play").performClick();waitIo(studio);
            assertTrue("Play must work without visiting Workspace",(boolean)field(studio,"playing"));
            assertFalse((boolean)field(studio,"watching"));assertNull(field(studio,"livePlayer"));
            assertEquals(2,((JSONObject)field(studio,"document")).getLong("revision"));
            try(SketchProjectStore store=new SketchProjectStore(c)){assertFalse(store.liveSession(id,"live").getBoolean("ended"));assertEquals(1,store.liveEvents(id,0,8).getJSONArray("events").length());}
        }
    }
    @Test public void directDrawMyselfControlLoadsCurrentArtworkBeforeAcceptingHumanInk() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);startLive(studio);
            Button takeover=button(studio.getWindow().getDecorView(),"Draw myself");assertNotNull("Takeover should be visible on the main workspace",takeover);takeover.performClick();waitIo(studio);
            SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");assertTrue(canvas.isEnabled());assertNull(field(studio,"livePlayer"));assertFalse((boolean)field(studio,"watching"));
            studio.onOperation(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","human_ink").put("kind","line").put("points",new JSONArray("[[0.1,0.7,1],[0.9,0.7,1]]"))));
            ((SketchEditorSession)field(studio,"session")).awaitIdle(10000);ShadowLooper.idleMainLooper();
            try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject saved=store.get(id);assertEquals(3,saved.getLong("revision"));assertNotNull(SketchDocument.object(saved,"agent_ink"));assertNotNull(SketchDocument.object(saved,"human_ink"));}
        }
    }
    @Test public void stoppingLiveWhileItsBaselineIsLoadingCannotRestartThePlayer() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);Method start=SketchStudioActivity.class.getDeclaredMethod("startLivePlayer",String.class);start.setAccessible(true);start.invoke(studio,"live");
            Button takeover=button(studio.getWindow().getDecorView(),"Draw myself");assertNotNull(takeover);takeover.performClick();waitIo(studio);waitIo(studio);
            assertNull(field(studio,"livePlayer"));assertTrue(((SketchCanvasView)field(studio,"canvas")).isEnabled());
        }
    }
    private static String createLiveSketch(Context c) throws Exception {
        String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("frameCount",12));
        SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","live").put("expectedRevision",1).put("strokeDurationMs",5000));
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","agent").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","agent_ink").put("kind","line").put("points",new JSONArray("[[0.1,0.3,1],[0.9,0.3,1]]"))))));return id;
    }
    private static void startLive(SketchStudioActivity studio) throws Exception {Method start=SketchStudioActivity.class.getDeclaredMethod("startLivePlayer",String.class);start.setAccessible(true);start.invoke(studio,"live");waitIo(studio);}
    private static Button button(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=button(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    @Test public void humanCurveToolCreatesEditableBezierAndCanvasRemainsUsableOnPhoneLayout() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256));
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");View root=studio.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));root.layout(0,0,720,1440);
            assertTrue("Phone drawing surface should retain usable height",canvas.getHeight()>300);Bitmap ui=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);root.draw(new Canvas(ui));File target=new File("build/sketch-studio-qa");target.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(target,"workspace.png"))){ui.compress(Bitmap.CompressFormat.PNG,100,out);}ui.recycle();
            canvas.setTool("curve");int w=canvas.getWidth(),h=canvas.getHeight();float size=Math.min(w,h),left=(w-size)/2,top=(h-size)/2;for(float[] p:new float[][]{{.2f,.7f},{.5f,.1f},{.8f,.7f}}){long now=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,left+p[0]*size,top+p[1]*size,0),up=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_UP,left+p[0]*size,top+p[1]*size,0);canvas.onTouchEvent(down);canvas.onTouchEvent(up);down.recycle();up.recycle();}
            SketchEditorSession session=(SketchEditorSession)field(studio,"session");session.awaitIdle(10000);ShadowLooper.idleMainLooper();try(SketchProjectStore store=new SketchProjectStore(c)){JSONArray objects=SketchDocument.layer(store.get(id),"layer_1").getJSONArray("objects");assertEquals(1,objects.length());assertEquals("path",objects.getJSONObject(0).getString("kind"));assertEquals("Q",objects.getJSONObject(0).getJSONArray("commands").getJSONArray(1).getString(0));}
        }
    }
    @Test public void brushPresetEraserActuallyRemovesInkInsteadOfErasingAnEmptyOffscreenLayer() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",id).put("width",256).put("height",256).put("frameCount",1));
        JSONObject ink=SketchEditOps.completeObject(new JSONObject().put("id","ink").put("kind","rect").put("fill",true).put("bounds",new JSONObject().put("x",.1).put("y",.1).put("width",.8).put("height",.8)).put("color","#FF000000"));
        JSONObject eraser=SketchEditOps.completeObject(new JSONObject().put("id","erase").put("kind","eraser").put("width",.1).put("points",new JSONArray("[[0.2,0.5,1],[0.8,0.5,1]]")).put("brush",SketchBrushes.style("ink",1,.8,true,0)));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(ink).put(eraser);try(SketchFrameRenderer r=new SketchFrameRenderer(c,doc)){Bitmap b=r.render(0);try{assertEquals(Color.WHITE,b.getPixel(128,128));assertEquals(Color.BLACK,b.getPixel(128,80));}finally{b.recycle();}}
    }
    @Test public void strokeProgressRevealsOnlyTheSubmittedLeadingGeometry() throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString()).put("width",256).put("height",256).put("frameCount",1));JSONObject line=SketchEditOps.completeObject(new JSONObject().put("id","nose").put("kind","stroke").put("width",.04).put("points",new JSONArray("[[0.1,0.5,1],[0.9,0.5,1]]")));doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(line);
        try(SketchFrameRenderer r=new SketchFrameRenderer(c,doc)){r.setPresentation("nose",.25f);Bitmap partial=r.render(0);r.setPresentation(null,1);Bitmap full=r.render(0);try{assertNotEquals(Color.WHITE,partial.getPixel(51,128));assertEquals(Color.WHITE,partial.getPixel(205,128));assertNotEquals(Color.WHITE,full.getPixel(205,128));}finally{partial.recycle();full.recycle();}}
    }
    @Test public void manualGraphitePickerPersistsTheSameBrushOnAnActualTouchStroke()throws Exception{
        Context c=RuntimeEnvironment.getApplication();String id=createLiveSketch(c);
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(c,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);
            button(studio.getWindow().getDecorView(),"Draw myself").performClick();waitIo(studio);
            button(studio.getWindow().getDecorView(),"Brush / Text").performClick();choose("Brush");
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            Spinner picker=spinner(dialog.getWindow().getDecorView());assertNotNull(picker);
            picker.setSelection(java.util.Arrays.asList(SketchBrushes.PRESETS).indexOf("graphite"));
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals("graphite",field(studio,"brushPreset"));
            SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");
            assertEquals("graphite",((JSONObject)field(canvas,"brushStyle")).getString("preset"));
            View root=studio.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));root.layout(0,0,720,1440);Bitmap surface=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);root.draw(new Canvas(surface));surface.recycle();canvas.setTool("stroke");
            long now=android.os.SystemClock.uptimeMillis();
            for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){
                float x=canvas.getWidth()*(action==MotionEvent.ACTION_DOWN?.4f:.6f);
                MotionEvent event=MotionEvent.obtain(now,now+action,action,x,canvas.getHeight()*.5f,0);canvas.onTouchEvent(event);event.recycle();
            }
            ((SketchEditorSession)field(studio,"session")).awaitIdle(10000);ShadowLooper.idleMainLooper();
            try(SketchProjectStore store=new SketchProjectStore(c)){
                JSONArray objects=SketchDocument.layer(store.get(id),"layer_1").getJSONArray("objects");
                assertEquals(2,objects.length());assertEquals("graphite",objects.getJSONObject(1).getJSONObject("brush").getString("preset"));
            }
        }
    }
    private static Spinner spinner(View view){if(view instanceof Spinner)return (Spinner)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Spinner found=spinner(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private static Object field(Object o,String name) throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void waitIo(SketchStudioActivity studio) throws Exception{((ExecutorService)field(studio,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
}
