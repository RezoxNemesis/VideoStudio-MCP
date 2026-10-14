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
import org.robolectric.shadows.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintHumanToolsTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private String blank() throws Exception {
        String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",2));
        return id;
    }
    private JSONObject document(String id) throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
    private JSONObject cel() throws Exception {return new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","paint_cel").put("frame",0).put("exposure",2);}
    private JSONObject stroke(String id) throws Exception {return new JSONObject().put("type","paint_stroke").put("objectId","paint_cel").put("strokeId",id).put("points",new JSONArray("[[0.2,0.5,0.5],[0.8,0.5,1]]")).put("brush",new JSONObject().put("mode","paint").put("color","#FFFF0040").put("sizePx",20));}
    private void prepared(SketchEditorSession session,JSONArray ops) throws Exception {
        Method method=SketchEditorSession.class.getMethod("submitPrepared",JSONArray.class);method.invoke(session,ops);
    }
    @Test public void preparedHumanWriterPersistsRealPixelsAndSurvivesSessionRestart() throws Exception {
        String id=blank();
        try(SketchEditorSession session=new SketchEditorSession(context,document(id))){
            prepared(session,new JSONArray().put(cel()));session.awaitIdle(10000);
            prepared(session,new JSONArray().put(stroke("human_stroke")));session.awaitIdle(10000);
            assertNull(session.error());assertEquals(3,session.document().getLong("revision"));
        }
        JSONObject saved=document(id);JSONObject paint=SketchDocument.object(saved,"paint_cel").getJSONObject("paint");
        Bitmap image=renderWorker(saved);try{assertEquals("Touch writer must persist actual painted coverage",Color.rgb(255,0,64),image.getPixel(128,128));assertEquals(Color.WHITE,image.getPixel(128,50));}finally{image.recycle();}
        try(SketchEditorSession restarted=new SketchEditorSession(context,saved)){assertNull(restarted.error());assertEquals(paint.getString("sha256"),SketchDocument.object(restarted.document(),"paint_cel").getJSONObject("paint").getString("sha256"));}
    }
    @Test public void preparedConflictRetainsStrokeIntentAndRecoveryReplaysItAsASeparateSketch() throws Exception {
        String id=blank();SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1).put("operations",new JSONArray().put(cel())));
        JSONObject original=document(id);
        try(SketchEditorSession session=new SketchEditorSession(context,original)){
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","other").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","set_canvas").put("background","#FF00FF00"))));
            prepared(session,new JSONArray().put(stroke("retained_stroke")));session.awaitIdle(10000);assertNotNull(session.error());
        }
        String recovered=SketchEditorSession.recoverAsCopy(context,id);assertNotEquals(id,recovered);
        Bitmap image=renderWorker(document(recovered));try{assertEquals(Color.rgb(255,0,64),image.getPixel(128,128));}finally{image.recycle();}
        assertEquals("Conflicting source edit must stay unchanged",3,document(id).getLong("revision"));
    }
    @Test public void paintControlsAreReachableWithoutDiscardingExistingVectorTools() throws Exception {
        String id=blank();
        try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);button(studio.getWindow().getDecorView(),"Brush / Text").performClick();
            choose("Paint studio");ListAdapter adapter=ShadowAlertDialog.getLatestAlertDialog().getListView().getAdapter();Set<String> labels=new HashSet<>();for(int i=0;i<adapter.getCount();i++)labels.add(String.valueOf(adapter.getItem(i)));
            for(String label:new String[]{"New paint cel","Paint brush","Erase paint","Clone brush","Paint settings","Clone source"})assertTrue(label,labels.contains(label));
            choose("New paint cel");((SketchEditorSession)field(studio,"session")).awaitIdle(10000);ShadowLooper.idleMainLooper();
            JSONObject doc=document(id);assertEquals("paint",SketchDocument.layer(doc,"layer_1").getJSONArray("objects").getJSONObject(0).getString("kind"));
        }
    }
    @Test public void paintGestureUsesTheSamePressurePointsAndStableStrokeIdsAsTheAgent() throws Exception {
        String id=blank();SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1).put("operations",new JSONArray().put(cel())));
        SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);canvas.setDocument(document(id));canvas.layout(0,0,256,256);canvas.setTool("paint");canvas.selectObject("paint_cel");
        Method brush=SketchCanvasView.class.getMethod("setPaintBrush",JSONObject.class,boolean.class,JSONObject.class,JSONObject.class);brush.invoke(canvas,new JSONObject().put("mode","paint").put("color","#FF4488CC").put("sizePx",12),false,null,null);
        List<JSONObject> operations=new ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){operations.add(op);}public void onSelection(String id){}public void onMessage(String msg){fail(msg);}});
        Bitmap preview=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(preview));preview.recycle();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(1,1+action,action,action==MotionEvent.ACTION_DOWN?40:200,128,0);canvas.onTouchEvent(event);event.recycle();}
        assertEquals(1,operations.size());JSONObject operation=operations.get(0);assertEquals("paint_stroke",operation.getString("type"));assertEquals("paint_cel",operation.getString("objectId"));assertFalse(operation.getString("strokeId").isEmpty());assertEquals("#FF4488CC",operation.getJSONObject("brush").getString("color"));assertTrue(operation.getJSONArray("points").length()>=2);assertEquals(1,operation.getJSONArray("points").getJSONArray(0).getDouble(2),0);canvas.dispose();
    }
    @Test public void editableArchiveControlsRemainAccessibleInTheFileMenu()throws Exception {
        String id=blank();try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);button(studio.getWindow().getDecorView(),"File").performClick();
            ListAdapter rows=ShadowAlertDialog.getLatestAlertDialog().getListView().getAdapter();Set<String> actual=new HashSet<>();for(int i=0;i<rows.getCount();i++)actual.add(String.valueOf(rows.getItem(i)));
            assertTrue("Editable archive must be a working human surface",actual.contains("Archive sketch"));assertTrue(actual.contains("Restore archive"));
        }
    }
    @Test public void largeLazyBookPreparesOnlyAdjacentOnionPagesAndBoundedViewportImages()throws Exception {
        String book=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_book_create",new JSONObject().put("bookId",book).put("operationId","book").put("pageCount",1001).put("width",720).put("height",1280).put("fps",24));
        JSONObject first=SketchEditOps.execute(context,"sketch_book_open_page",new JSONObject().put("bookId",book).put("page",0));String firstId=first.getString("sketchId");
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",firstId).put("operationId","ink").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","ink").put("kind","line").put("color","#FFFF0000").put("width",.1).put("points",new JSONArray("[[0.1,0.5,1],[0.9,0.5,1]]"))))));
        android.os.Bundle state=new android.os.Bundle();state.putString("bookId",book);state.putInt("bookPage",1);
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class)).create(state).start().resume().visible()){
            SketchStudioActivity studio=controller.get();waitIo(studio);waitIo(studio);waitIo(studio);SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");canvas.layout(0,0,512,512);Bitmap image=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
            try{canvas.draw(new Canvas(image));((ExecutorService)field(canvas,"rasterWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();canvas.draw(new Canvas(image));assertNotEquals("Adjacent book-page onion ink must be visible",Color.WHITE,image.getPixel(256,256));assertTrue((long)field(canvas,"cacheBytes")<=12L*1024*1024);}finally{image.recycle();}
            Method open=SketchStudioActivity.class.getDeclaredMethod("openBookPage",String.class,int.class);open.setAccessible(true);open.invoke(studio,book,1000);waitIo(studio);waitIo(studio);waitIo(studio);
            try(SketchProjectStore store=new SketchProjectStore(context)){JSONArray docs=store.list();int pages=0;for(int i=0;i<docs.length();i++)if(docs.getJSONObject(i).getString("sketchId").startsWith(book+"_p"))pages++;assertEquals("A thousand-page book must never eagerly create every page",3,pages);}
        }
    }
    @Test public void thinLongTouchGestureSplitsIntoNativeWorkBoundedStrokesRatherThanLosingTheLine()throws Exception {
        String id=blank();SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1).put("operations",new JSONArray().put(cel())));
        SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);canvas.setDocument(document(id));canvas.layout(0,0,256,256);canvas.setTool("paint");canvas.selectObject("paint_cel");
        Method brush=SketchCanvasView.class.getMethod("setPaintBrush",JSONObject.class,boolean.class,JSONObject.class,JSONObject.class);brush.invoke(canvas,new JSONObject().put("mode","paint").put("color","#FF4488CC").put("sizePx",.5).put("spacing",.05).put("pressure",false),false,null,null);
        List<JSONObject> strokes=new ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){strokes.add(op);}public void onSelection(String id){}public void onMessage(String msg){fail(msg);}});Bitmap preview=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(preview));preview.recycle();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(1,1+action,action,action==MotionEvent.ACTION_DOWN?5:251,128,0);canvas.onTouchEvent(event);event.recycle();}
        assertTrue("A long fine line needs bounded native stroke chunks",strokes.size()>1);Set<String> ids=new HashSet<>();for(JSONObject operation:strokes){assertTrue(ids.add(operation.getString("strokeId")));assertTrue(SketchPaintBrush.plan(operation,256,256).dabCount<=8192);}canvas.dispose();
    }
    @Test public void liveDrawingAllowsPanWithoutAcceptingHumanPaint()throws Exception {
        String id=blank();SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);canvas.setDocument(document(id));canvas.layout(0,0,256,256);canvas.setTool("pan");canvas.setEnabled(false);Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(image));image.recycle();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(1,1+action,action,action==MotionEvent.ACTION_DOWN?128:160,128,0);assertTrue("Live viewers can navigate the canvas",canvas.onTouchEvent(event));event.recycle();}
        assertNotEquals(0,(float)field(canvas,"panX"),0);canvas.dispose();
    }
    @Test public void cloneTouchPinsTheVisibleFrameAndMapsTransformedCelAndReferenceAccurately()throws Exception {
        String id=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",12));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1).put("operations",new JSONArray().put(cel().put("exposure",12))));
        Bitmap texture=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);texture.eraseColor(Color.BLUE);Paint fill=new Paint();fill.setColor(Color.RED);new Canvas(texture).drawRect(0,0,256,128,fill);java.io.File file=new java.io.File(context.getFilesDir(),"clone-ui-"+UUID.randomUUID()+".png");try(java.io.FileOutputStream stream=new java.io.FileOutputStream(file)){texture.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{texture.recycle();}
        ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Owned clone UI fixture");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.name="Clone texture";asset.mime="image/png";asset.uri=android.net.Uri.fromFile(file).toString();project.assets.add(asset);projects.save(project);
        SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","reference").put("expectedRevision",2).put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false));
        JSONObject transformed=new JSONObject().put("type","set_keyframe").put("objectId","paint_cel").put("frame",5).put("transform",SketchDocument.transform().put("x",.1)).put("interpolation","linear");
        JSONObject referencePose=new JSONObject().put("type","set_keyframe").put("objectId","guide").put("frame",5).put("transform",SketchDocument.transform().put("x",-.15).put("rotation",90).put("opacity",0)).put("interpolation","linear");
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","pose").put("expectedRevision",3).put("operations",new JSONArray().put(transformed).put(referencePose)));
        JSONObject drawing=document(id);String hash=SketchDocument.object(drawing,"guide").getJSONObject("reference").getString("sha256");
        SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);canvas.setDocument(drawing);canvas.layout(0,0,256,256);canvas.setFrame(5);canvas.setTool("clone");canvas.selectObject("paint_cel");canvas.setPaintBrush(new JSONObject().put("mode","clone").put("color","#FFFFFFFF").put("sizePx",16).put("flow",1),false,null,new JSONObject().put("objectId","guide").put("sha256",hash));List<JSONObject> operations=new ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){operations.add(op);}public void onSelection(String value){}public void onMessage(String message){fail(message);}});Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(image));image.recycle();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(1,1+action,action,128,128,0);canvas.onTouchEvent(event);event.recycle();}assertEquals(1,operations.size());JSONObject operation=operations.get(0);assertEquals(5,operation.getInt("frameIndex"));assertEquals(.4,operation.getJSONArray("points").getJSONArray(0).getDouble(0),.0001);
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","human_clone").put("expectedRevision",4).put("operations",new JSONArray().put(operation)));
        try(SketchPaintStore store=new SketchPaintStore(context,id)){String expected=store.sampleReference(drawing,"guide",hash,.5,.5,5);assertEquals("The transformed visible source must differ from its frame-zero sample","#FFFF0000",expected);assertEquals(expected,store.sample(SketchDocument.object(document(id),"paint_cel").getJSONObject("paint"),.4,.5));}canvas.dispose();
    }
    @Test public void confirmedPaintChunkDoesNotClearTheUnfinishedTouchGesture()throws Exception {
        String id=blank();SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1).put("operations",new JSONArray().put(cel())));
        SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);canvas.setDocument(document(id));canvas.layout(0,0,256,256);canvas.setTool("paint");canvas.selectObject("paint_cel");canvas.setPaintBrush(new JSONObject().put("mode","paint").put("color","#FF4488CC").put("sizePx",12),false,null,null);List<JSONObject> operations=new ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){operations.add(op);}public void onSelection(String value){}public void onMessage(String message){fail(message);}});Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);canvas.draw(new Canvas(image));image.recycle();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE}){MotionEvent event=MotionEvent.obtain(1,1+action,action,action==MotionEvent.ACTION_DOWN?40:100,128,0);canvas.onTouchEvent(event);event.recycle();}
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","confirmed_chunk").put("expectedRevision",2).put("operations",new JSONArray().put(stroke("confirmed"))));canvas.setDocument(document(id));
        assertTrue("A confirmed chunk must not discard the finger's unfinished pressure points",((JSONArray)field(canvas,"gesture")).length()>=2);
        MotionEvent up=MotionEvent.obtain(1,4,MotionEvent.ACTION_UP,160,128,0);canvas.onTouchEvent(up);up.recycle();assertEquals(40/256d,operations.get(0).getJSONArray("points").getJSONArray(0).getDouble(0),.0001);canvas.dispose();
    }
    @Test public void twoThousandPixelPaintCanvasAndOnionUseOnlyBoundedViewportBitmaps()throws Exception {
        String id=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",2048).put("height",2048).put("frameCount",3));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","paint").put("expectedRevision",1).put("operations",new JSONArray().put(cel().put("exposure",3)).put(stroke("native_paint"))));
        SketchCanvasView canvas=new SketchCanvasView(context);canvas.setDocument(document(id));canvas.layout(0,0,480,640);canvas.setFrame(1);Bitmap image=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888);
        try{canvas.draw(new Canvas(image));((ExecutorService)field(canvas,"rasterWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();canvas.draw(new Canvas(image));assertEquals("The bound must not discard the actual paint",Color.rgb(255,0,64),image.getPixel(240,320));assertTrue((long)field(canvas,"cacheBytes")<=12L*1024*1024);java.util.Map<?,?> frames=(java.util.Map<?,?>)field(canvas,"frameCache");assertTrue(frames.size()>=1);for(Object frame:frames.values()){Bitmap bitmap=(Bitmap)field(frame,"bitmap");assertTrue(bitmap.getWidth()<=1024);assertTrue(bitmap.getHeight()<=1024);}}
        finally{image.recycle();canvas.dispose();}
    }
    @Test public void clonePreviewShowsActualSourceTextureBeforeFingerLiftWithoutSavingAStroke()throws Exception {
        String id=blank();SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1).put("operations",new JSONArray().put(cel())));
        Bitmap texture=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);texture.eraseColor(Color.RED);java.io.File file=new java.io.File(context.getFilesDir(),"live-clone-"+UUID.randomUUID()+".png");try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){texture.compress(Bitmap.CompressFormat.PNG,100,out);}finally{texture.recycle();}
        ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Owned live clone fixture");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.name="Red texture";asset.mime="image/png";asset.uri=android.net.Uri.fromFile(file).toString();project.assets.add(asset);projects.save(project);SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","guide").put("expectedRevision",2).put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false));
        JSONObject doc=document(id),descriptor=SketchDocument.object(doc,"paint_cel").getJSONObject("paint");String hash=SketchDocument.object(doc,"guide").getJSONObject("reference").getString("sha256");SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);canvas.setDocument(doc);canvas.layout(0,0,256,256);canvas.setTool("clone");canvas.selectObject("paint_cel");canvas.setPaintBrush(new JSONObject().put("mode","clone").put("color","#FFFFFFFF").put("sizePx",24).put("flow",1),false,null,new JSONObject().put("objectId","guide").put("sha256",hash));canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){fail("Finger-down preview must not publish a saved stroke");}public void onSelection(String value){}public void onMessage(String message){fail(message);}});
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);try{canvas.draw(new Canvas(image));MotionEvent down=MotionEvent.obtain(1,1,MotionEvent.ACTION_DOWN,128,128,0);canvas.onTouchEvent(down);down.recycle();((ExecutorService)field(canvas,"rasterWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();canvas.draw(new Canvas(image));assertEquals("Human clone preview must show sampled texture, not the brush's white marker",Color.RED,image.getPixel(128,128));try(SketchPaintStore store=new SketchPaintStore(context,id)){assertEquals(0,store.journal(descriptor,0,8).length());assertEquals("#00000000",store.sample(descriptor,.5,.5));}assertEquals(3,document(id).getLong("revision"));}finally{image.recycle();canvas.dispose();}
    }
    @Test public void visibleAgentHandHasAReachableDisplayToggleWithoutTakingOverTheDrawing()throws Exception {
        String id=blank();try(ActivityController<SketchStudioActivity> activity=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=activity.get();waitIo(studio);button(studio.getWindow().getDecorView(),"More").performClick();choose("Workspace");ListAdapter rows=ShadowAlertDialog.getLatestAlertDialog().getListView().getAdapter();boolean found=false;for(int i=0;i<rows.getCount();i++)if("ChatGPT hand".equals(String.valueOf(rows.getItem(i))))found=true;assertTrue("Hand overlay must remain an optional real display control",found);
            choose("ChatGPT hand");assertTrue("A display preference must preserve watching",(boolean)field(studio,"watching"));assertFalse((boolean)field((SketchCanvasView)field(studio,"canvas"),"agentHandEnabled"));
        }
    }
    @Test public void secondFingerNavigationCancelsOnlyUncommittedPreviewAndNeverLeavesPaintOnTheCanvas()throws Exception {
        String id=blank();SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1).put("operations",new JSONArray().put(cel())));
        SketchCanvasView canvas=new SketchCanvasView(context);FrameLayout parent=new FrameLayout(context);parent.addView(canvas);canvas.setDocument(document(id));canvas.layout(0,0,256,256);canvas.setTool("paint");canvas.selectObject("paint_cel");canvas.setPaintBrush(new JSONObject().put("mode","paint").put("color","#FF4488CC").put("sizePx",24).put("flow",1),false,null,null);List<JSONObject> admitted=new ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){admitted.add(op);}public void onSelection(String value){}public void onMessage(String message){fail(message);}});
        Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);try{canvas.draw(new Canvas(image));MotionEvent down=MotionEvent.obtain(1,1,MotionEvent.ACTION_DOWN,128,128,0);canvas.onTouchEvent(down);down.recycle();((ExecutorService)field(canvas,"rasterWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();canvas.draw(new Canvas(image));assertNotEquals("The finger-down preview should exist before navigation",Color.WHITE,image.getPixel(128,128));
            MotionEvent.PointerProperties[] properties={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};MotionEvent.PointerCoords[] coordinates={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};for(int i=0;i<2;i++){properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coordinates[i].x=128+i*64;coordinates[i].y=128;coordinates[i].pressure=1;coordinates[i].size=1;}
            MotionEvent second=MotionEvent.obtain(1,2,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);canvas.onTouchEvent(second);second.recycle();MotionEvent up=MotionEvent.obtain(1,3,MotionEvent.ACTION_UP,128,128,0);canvas.onTouchEvent(up);up.recycle();((ExecutorService)field(canvas,"rasterWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();canvas.draw(new Canvas(image));assertEquals("Pinch/pan cancellation must detach the uncommitted actual-pixel preview",Color.WHITE,image.getPixel(128,128));assertTrue(admitted.isEmpty());assertEquals(2,document(id).getLong("revision"));assertFalse(canvas.isDrawing());
        }finally{image.recycle();canvas.dispose();}
    }
    private Bitmap renderWorker(JSONObject doc)throws Exception {ExecutorService worker=Executors.newSingleThreadExecutor();try{return worker.submit(()->{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){return renderer.render(0);}}).get(10,TimeUnit.SECONDS);}finally{worker.shutdown();}}
    private static Object field(Object o,String name) throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void waitIo(SketchStudioActivity studio) throws Exception{((ExecutorService)field(studio,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static Button button(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){Button b=button(g.getChildAt(i),text);if(b!=null)return b;}}return null;}
    private static void choose(String title){android.app.AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView list=dialog.getListView();for(int i=0;i<list.getCount();i++)if(title.equals(String.valueOf(list.getAdapter().getItem(i)))){list.performItemClick(list.getAdapter().getView(i,null,list),i,i);return;}fail("Missing functional tool: "+title);}
}
