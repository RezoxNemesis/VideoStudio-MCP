package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchComponentLibraryTest {
    final Context c=RuntimeEnvironment.getApplication();
    String create()throws Exception {String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256));return id;}
    private static <T>T worker(java.util.concurrent.Callable<T> task)throws Exception {java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();try{return executor.submit(task).get(60,java.util.concurrent.TimeUnit.SECONDS);}finally{executor.shutdownNow();}}
    @Test public void paintedComponentOwnsItsPixelsAndPlacedPaintCanBeEdited()throws Exception {worker(()->{
        String source=create(),target=create(),component=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",source).put("expectedRevision",1).put("operationId","paint").put("operations",new JSONArray().put(new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","painted"))));
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",source).put("expectedRevision",2).put("operationId","line").put("operations",new JSONArray().put(new JSONObject().put("type","paint_stroke").put("objectId","painted").put("strokeId","first").put("points",new JSONArray("[[0.1,0.5,1],[0.9,0.5,1]]")).put("brush",new JSONObject().put("mode","paint").put("color","#FFFF0000").put("sizePx",16)))));
        SketchComponentLibrary.execute(c,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",source).put("expectedRevision",3).put("operationId","save").put("category","effect").put("name","Painted arc").put("frameIndex",0));
        OpenPipelineArtifacts.deleteTree(new java.io.File(c.getFilesDir(),"sketch_studio/"+source+"/paint"));
        SketchComponentLibrary.execute(c,"sketch_component_apply",new JSONObject().put("componentId",component).put("sketchId",target).put("expectedRevision",1).put("operationId","place").put("frameIndex",0));
        String object;try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject doc=store.get(target);object=doc.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0).getString("id");try(SketchFrameRenderer renderer=new SketchFrameRenderer(c,doc)){Bitmap b=renderer.render(0);try{assertEquals(Color.RED,b.getPixel(128,128));}finally{b.recycle();}}}
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",target).put("expectedRevision",2).put("operationId","continue_painting").put("operations",new JSONArray().put(new JSONObject().put("type","paint_stroke").put("objectId",object).put("strokeId","new").put("points",new JSONArray("[[0.1,0.5,1],[0.9,0.5,1]]")).put("brush",new JSONObject().put("mode","paint").put("color","#FF0000FF").put("sizePx",16)))));
        try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer renderer=new SketchFrameRenderer(c,store.get(target))){Bitmap b=renderer.render(0);try{assertEquals(Color.BLUE,b.getPixel(128,128));}finally{b.recycle();}}
        return null;});
    }
    @Test public void editableComponentKeepsAppearanceAndDoesNotFollowLaterSourceChanges()throws Exception {
        String source=create(),target=create(),component=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",source).put("expectedRevision",1).put("operationId","red").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","shape").put("kind","ellipse").put("fill",true).put("color","#FFFF0000").put("bounds",new JSONObject().put("x",.2).put("y",.2).put("width",.6).put("height",.6))))));
        JSONObject save=new JSONObject().put("componentId",component).put("sketchId",source).put("expectedRevision",2).put("operationId","save").put("category","expression").put("name","Authored face cel").put("frameIndex",0);
        SketchComponentLibrary.execute(c,"sketch_component_save",save);
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",source).put("expectedRevision",2).put("operationId","blue").put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","shape").put("patch",new JSONObject().put("color","#FF0000FF")))));
        JSONObject apply=new JSONObject().put("componentId",component).put("sketchId",target).put("expectedRevision",1).put("operationId","apply").put("frameIndex",0);
        JSONObject result=SketchComponentLibrary.execute(c,"sketch_component_apply",apply);assertEquals(result.toString(),SketchComponentLibrary.execute(c,"sketch_component_apply",apply).toString());
        try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject doc=store.get(target);assertEquals(2,doc.getLong("revision"));assertEquals(2,doc.getJSONArray("layers").length());JSONObject shape=doc.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0);assertEquals("ellipse",shape.getString("kind"));try(SketchFrameRenderer renderer=new SketchFrameRenderer(c,doc)){Bitmap b=renderer.render(0);try{assertEquals(Color.RED,b.getPixel(128,128));}finally{b.recycle();}}}
        try{SketchComponentLibrary.execute(c,"sketch_component_save",new JSONObject(save.toString()).put("operationId","replace").put("expectedRevision",3));fail();}catch(IllegalStateException expected){}
    }
    @Test public void originalStrokeSessionCannotBeFilledWithLibraryCopies()throws Exception {
        String source=create(),target=create(),component=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",source).put("expectedRevision",1).put("operationId","ink").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","line").put("kind","line").put("points",new JSONArray("[[0,0,1],[1,1,1]]"))))));
        SketchComponentLibrary.execute(c,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",source).put("expectedRevision",2).put("operationId","save").put("name","Hand arc").put("category","pose").put("frameIndex",0));
        SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId",target).put("expectedRevision",1).put("operationId","live").put("drawingMethod","original_strokes"));
        try{SketchComponentLibrary.execute(c,"sketch_component_apply",new JSONObject().put("componentId",component).put("sketchId",target).put("expectedRevision",1).put("operationId","copy").put("frameIndex",0));fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("ORIGINAL"));}
    }
}
