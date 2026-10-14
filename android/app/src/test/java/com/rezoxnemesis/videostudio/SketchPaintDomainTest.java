package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintDomainTest {
    final Context context=RuntimeEnvironment.getApplication();
    String create()throws Exception {String id=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",512).put("frameCount",2).put("background","#00000000"));return id;}
    JSONObject request(String id,String op,long revision,JSONObject... operations)throws Exception {JSONArray edits=new JSONArray();for(JSONObject edit:operations)edits.put(edit);return new JSONObject().put("sketchId",id).put("operationId",op).put("expectedRevision",revision).put("operations",edits);}
    JSONObject cel()throws Exception {return new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","cel").put("frame",0).put("exposure",2);}
    JSONObject stroke(String strokeId)throws Exception {return new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId",strokeId).put("points",new JSONArray().put(new JSONArray().put(.1).put(.5).put(1)).put(new JSONArray().put(.9).put(.5).put(1))).put("brush",new JSONObject().put("mode","paint").put("color","#FFFF4422").put("sizePx",16));}
    JSONObject drawing(String id)throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
    JSONObject paint(String id)throws Exception {return SketchDocument.object(drawing(id),"cel").getJSONObject("paint");}
    long files(File dir){long n=0;File[] children=dir.listFiles();if(children!=null)for(File f:children)n+=f.isDirectory()?files(f):1;return n;}

    @Test public void authoredPixelsUndoRedoAndRecreationRetainImmutableSnapshots()throws Exception {
        String id=create();JSONObject saved=SketchEditOps.execute(context,"sketch_edit",request(id,"paint",1,cel(),stroke("first")));
        assertEquals(2,saved.getLong("revision"));assertTrue(saved.getBoolean("persisted"));JSONObject after=paint(id);
        try(SketchPaintStore assets=new SketchPaintStore(context,id)){assertEquals("#FFFF4422",assets.sample(after,.5,.5));assertEquals(1,assets.journal(after,0,16).length());}
        SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo").put("expectedRevision",2));
        assertEquals(0,SketchDocument.layer(drawing(id),"layer_1").getJSONArray("objects").length());
        SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId",id).put("operationId","redo").put("expectedRevision",3));
        assertEquals(after.getString("sha256"),paint(id).getString("sha256"));
        try(SketchPaintStore reopened=new SketchPaintStore(context,id)){Bitmap image=reopened.render(paint(id),512,null);try{assertEquals(0xffff4422,image.getPixel(256,256));}finally{image.recycle();}}
    }
    @Test public void retryReturnsReceiptBeforeCreatingAnotherStrokeOrFileAndChangedRequestRejects()throws Exception {
        String id=create();JSONObject args=request(id,"once",1,cel(),stroke("stable"));JSONObject first=SketchEditOps.execute(context,"sketch_edit",args);long before=files(new File(context.getFilesDir(),"sketch_studio/"+id+"/paint"));
        JSONObject retry=SketchEditOps.execute(context,"sketch_edit",new JSONObject(args.toString()));assertEquals(first.toString(),retry.toString());assertEquals(before,files(new File(context.getFilesDir(),"sketch_studio/"+id+"/paint")));
        try(SketchPaintStore assets=new SketchPaintStore(context,id);SketchProjectStore store=new SketchProjectStore(context)){assertEquals(1,assets.journal(paint(id),0,16).length());assertEquals(1,store.liveEvents(id,0,8).getJSONArray("events").length());}
        args.getJSONArray("operations").getJSONObject(1).getJSONObject("brush").put("color","#FF00FF00");assertThrows(IllegalStateException.class,()->SketchEditOps.execute(context,"sketch_edit",args));assertEquals(before,files(new File(context.getFilesDir(),"sketch_studio/"+id+"/paint")));
    }
    @Test public void revisionConflictRejectsBeforeAnyPaintAssetsOrAcceptedLiveEvents()throws Exception {
        String id=create();SketchEditOps.execute(context,"sketch_edit",request(id,"rename",1,new JSONObject().put("type","set_canvas").put("name","Changed")));
        assertThrows(IllegalStateException.class,()->SketchEditOps.execute(context,"sketch_edit",request(id,"stale",1,cel(),stroke("bad"))));assertFalse(new File(context.getFilesDir(),"sketch_studio/"+id+"/paint").exists());
        try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(2,store.get(id).getLong("revision"));assertEquals(1,store.liveEvents(id,0,8).getJSONArray("events").length());assertFalse(store.hasOperation(id,"stale"));}
    }
    @Test public void externalReplayPinsCannotBeForgedAndPureApplyCannotResolveExternalStroke()throws Exception {
        String id=create();SketchEditOps.execute(context,"sketch_edit",request(id,"cel",1,cel()));JSONObject original=paint(id);JSONObject forged=stroke("forged").put("afterPaint",original).put("beforePaint",original).put("stroke",new JSONObject());
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(context,"sketch_edit",request(id,"forged",2,forged)));
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(drawing(id),stroke("unprepared")));
        assertEquals(original.toString(),paint(id).toString());try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(2,store.get(id).getLong("revision"));assertFalse(store.hasOperation(id,"forged"));assertEquals(1,store.liveEvents(id,0,8).getJSONArray("events").length());}
    }
    @Test public void copiedPageOwnsAllPaintDependenciesAndMissingTilePreventsHistoryMove()throws Exception {
        String source=create(),target=create();SketchEditOps.execute(context,"sketch_edit",request(source,"paint",1,cel(),stroke("first")));
        try(SketchProjectStore store=new SketchProjectStore(context)){store.copyDrawingToPage(store.get(source),store.get(target));store.verifyReferences(store.get(target));}
        JSONObject descriptor=paint(target);try(SketchPaintStore assets=new SketchPaintStore(context,target)){assertEquals("#FFFF4422",assets.sample(descriptor,.5,.5));File tile=assets.dependencies(descriptor).entrySet().stream().filter(e->e.getKey().startsWith("paint/tiles/")).findFirst().get().getValue();assertTrue(tile.delete());}
        assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_edit",request(target,"rename",2,new JSONObject().put("type","set_canvas").put("name","Must not save"))));assertEquals(2,drawing(target).getLong("revision"));
    }
    @Test public void journalAndSampleQueriesAreRevisionBoundReadOnlyAndPaged()throws Exception {
        String id=create();SketchEditOps.execute(context,"sketch_edit",request(id,"paint",1,cel(),stroke("first")));JSONObject query=new JSONObject().put("sketchId",id).put("expectedRevision",2).put("objectId","cel").put("offset",0).put("limit",1);
        JSONObject journal=SketchEditOps.execute(context,"sketch_paint_journal",query);assertEquals(1,journal.getJSONArray("strokes").length());assertEquals(paint(id).getString("sha256"),journal.getString("snapshotSha256"));
        JSONObject sample=SketchEditOps.execute(context,"sketch_sample_color",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("source","paint").put("objectId","cel").put("x",.5).put("y",.5));assertEquals("#FFFF4422",sample.getString("color"));assertEquals(2,sample.getLong("revision"));
        query.put("expectedRevision",1);assertThrows(IllegalStateException.class,()->SketchEditOps.execute(context,"sketch_paint_journal",query));assertEquals(2,drawing(id).getLong("revision"));
    }
    @Test public void invalidMixedBatchKeepsPriorPixelsHistoryAndLiveReceiptUnchanged()throws Exception {
        String id=create();SketchEditOps.execute(context,"sketch_edit",request(id,"cel",1,cel()));String emptyHash=paint(id).getString("sha256");
        JSONObject wrong=new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","outside").put("kind","stroke").put("points",new JSONArray().put(new JSONArray().put(2).put(.1).put(1))));
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(context,"sketch_edit",request(id,"invalid",2,stroke("not_saved"),wrong)));assertEquals(emptyHash,paint(id).getString("sha256"));
        try(SketchPaintStore assets=new SketchPaintStore(context,id);SketchProjectStore store=new SketchProjectStore(context)){assertEquals("#00000000",assets.sample(paint(id),.5,.5));assertEquals(2,store.get(id).getLong("revision"));assertFalse(store.hasOperation(id,"invalid"));assertEquals(1,store.liveEvents(id,0,8).getJSONArray("events").length());}
        SketchEditOps.execute(context,"sketch_edit",request(id,"retry_after_failed_batch",2,stroke("not_saved")));assertEquals("#FFFF4422",samplePaint(id,.5,.5));
    }
    String samplePaint(String id,double x,double y)throws Exception {try(SketchPaintStore assets=new SketchPaintStore(context,id)){return assets.sample(paint(id),x,y);}}
    @Test public void acceptedStrokeIdCannotBeReusedAfterUndoWhileFailedPreparationNeverReservesIt()throws Exception {
        String id=create();SketchEditOps.execute(context,"sketch_edit",request(id,"cel",1,cel()));JSONObject authored=stroke("stable_stroke");SketchEditOps.execute(context,"sketch_edit",request(id,"first",2,authored));
        SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo").put("expectedRevision",3));assertEquals("#00000000",samplePaint(id,.5,.5));long retained=files(new File(context.getFilesDir(),"sketch_studio/"+id+"/paint"));
        authored.getJSONObject("brush").put("color","#FF33CC11");assertThrows(IllegalStateException.class,()->SketchEditOps.execute(context,"sketch_edit",request(id,"changed_reuse",4,authored)));assertEquals(retained,files(new File(context.getFilesDir(),"sketch_studio/"+id+"/paint")));assertEquals(4,drawing(id).getLong("revision"));
        SketchEditOps.execute(context,"sketch_edit",request(id,"new_stroke",4,stroke("unique_stroke")));assertEquals("#FFFF4422",samplePaint(id,.5,.5));
    }
    @Test public void publicAddObjectAndLayerCannotInjectPreparedPaintPointers()throws Exception {
        String id=create();JSONObject descriptor;try(SketchPaintStore assets=new SketchPaintStore(context,id)){descriptor=assets.empty(512,512);}
        JSONObject object=SketchEditOps.completeObject(new JSONObject().put("id","injected").put("kind","paint").put("paint",descriptor).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)));
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(context,"sketch_edit",request(id,"inject_object",1,new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object))));
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(context,"sketch_edit",request(id,"inject_layer",1,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","injected_layer").put("objects",new JSONArray().put(object))))));
        assertEquals(1,drawing(id).getLong("revision"));
    }
    @Test public void canvasResizeCannotInvalidateExistingPaintSnapshotDimensions()throws Exception {
        String id=create();SketchEditOps.execute(context,"sketch_edit",request(id,"paint",1,cel(),stroke("first")));String before=paint(id).getString("sha256");
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(context,"sketch_edit",request(id,"resize",2,new JSONObject().put("type","set_canvas").put("width",256).put("height",256))));
        assertEquals(2,drawing(id).getLong("revision"));assertEquals(before,paint(id).getString("sha256"));assertEquals("#FFFF4422",samplePaint(id,.5,.5));
    }
    @Test public void legacyVersionFiveDatabaseUpgradesWithoutLosingExistingSketchOrHistory()throws Exception {
        String id=create();SketchEditOps.execute(context,"sketch_edit",request(id,"rename",1,new JSONObject().put("type","set_canvas").put("name","Legacy drawing")));
        android.database.sqlite.SQLiteDatabase legacy=context.openOrCreateDatabase("sketch_studio_v1.db",Context.MODE_PRIVATE,null);try{legacy.execSQL("DROP TABLE sketch_paint_strokes");legacy.setVersion(5);}finally{legacy.close();}
        assertEquals("Legacy drawing",drawing(id).getString("name"));SketchEditOps.execute(context,"sketch_edit",request(id,"paint",2,cel(),stroke("after_upgrade")));assertEquals("#FFFF4422",samplePaint(id,.5,.5));
        SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo").put("expectedRevision",3));assertEquals("Legacy drawing",drawing(id).getString("name"));assertEquals(0,SketchDocument.layer(drawing(id),"layer_1").getJSONArray("objects").length());
    }
    @Test public void copiedPageRetainsAcceptedStrokeIdentityEvenAfterItsCelIsCleared()throws Exception {
        String source=create(),target=create();SketchEditOps.execute(context,"sketch_edit",request(source,"paint",1,cel(),stroke("inherited")));try(SketchProjectStore store=new SketchProjectStore(context)){store.copyDrawingToPage(store.get(source),store.get(target));}
        SketchEditOps.execute(context,"sketch_edit",request(target,"clear",2,new JSONObject().put("type","clear_cel").put("layerId","layer_1").put("frame",0)));
        SketchEditOps.execute(context,"sketch_edit",request(target,"cel",3,cel().put("exposure",1)));
        assertThrows(IllegalStateException.class,()->SketchEditOps.execute(context,"sketch_edit",request(target,"identity_reuse",4,stroke("inherited"))));assertEquals("#00000000",samplePaint(target,.5,.5));assertEquals(4,drawing(target).getLong("revision"));
    }
}
