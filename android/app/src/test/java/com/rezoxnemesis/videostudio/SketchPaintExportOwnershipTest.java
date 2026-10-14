package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintExportOwnershipTest {
    final Context context=RuntimeEnvironment.getApplication();
    private static <T>T worker(Callable<T> action)throws Exception {ExecutorService executor=Executors.newSingleThreadExecutor();try{return executor.submit(action).get(60,TimeUnit.SECONDS);}finally{executor.shutdownNow();}}
    JSONObject paint(String id)throws Exception {
        try(SketchProjectStore store=new SketchProjectStore(context);SketchPaintStore paints=new SketchPaintStore(context,id)){
            JSONObject doc=store.get(id);doc.put("background","#00000000");doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","cel").put("kind","paint").put("paint",paints.empty(256,256)).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
            JSONObject op=new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId","native_line").put("points",new JSONArray().put(new JSONArray().put(.1).put(.5).put(1)).put(new JSONArray().put(.9).put(.5).put(1))).put("brush",new JSONObject().put("mode","paint").put("color","#FF33AA77").put("sizePx",16));
            SketchDocument.object(doc,"cel").put("paint",paints.prepare(doc,op).descriptor);store.mutate(id,"save_paint",doc.getLong("revision"),"native_fixture",ignored->doc);return store.get(id);
        }
    }
    @Test public void rasterExportReadsFrozenPaintEvenIfSourceOwnerTileDisappearsAfterCapture()throws Exception {
        String id=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",1));JSONObject doc=paint(id);File tile;
        try(SketchPaintStore store=new SketchPaintStore(context,id)){tile=store.dependencies(SketchDocument.object(doc,"cel").getJSONObject("paint")).entrySet().stream().filter(e->e.getKey().startsWith("paint/tiles/")).findFirst().get().getValue();}
        SketchNativeExport.Prepared result=worker(()->SketchNativeExport.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("operationId","paint_export"),(frame,count)->assertTrue(tile.delete())));
        Bitmap actual=BitmapFactory.decodeFile(Uri.parse(result.project.assets.get(0).uri).getPath());try{assertEquals(Color.rgb(51,170,119),actual.getPixel(128,128));assertEquals(0,actual.getPixel(128,20));}finally{actual.recycle();}
    }
    @Test public void tenThousandPageBookPaintExportFreezesOnlySelectedAllocatedPage()throws Exception {
        String book=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_book_create",new JSONObject().put("bookId",book).put("operationId","new").put("pageCount",10000).put("width",256).put("height",256));String id=SketchEditOps.execute(context,"sketch_book_open_page",new JSONObject().put("bookId",book).put("page",6)).getString("sketchId");JSONObject doc=paint(id);File tile;
        try(SketchPaintStore store=new SketchPaintStore(context,id)){tile=store.dependencies(SketchDocument.object(doc,"cel").getJSONObject("paint")).entrySet().stream().filter(e->e.getKey().startsWith("paint/tiles/")).findFirst().get().getValue();}
        SketchNativeExport.Prepared result=worker(()->SketchBookExport.prepare(context,new JSONObject().put("bookId",book).put("expectedRevision",1).put("operationId","paint_book").put("pageStart",5).put("pageCount",2),(frame,count)->{assertEquals(2,count);if(frame==0)assertTrue(tile.delete());}));
        assertEquals(2,result.project.assets.size());Bitmap actual=BitmapFactory.decodeFile(Uri.parse(result.project.assets.get(1).uri).getPath());try{assertEquals(Color.rgb(51,170,119),actual.getPixel(128,128));}finally{actual.recycle();}assertEquals(1,SketchEditOps.execute(context,"sketch_book_get",new JSONObject().put("bookId",book)).getJSONObject("book").getInt("createdPages"));
    }
}
