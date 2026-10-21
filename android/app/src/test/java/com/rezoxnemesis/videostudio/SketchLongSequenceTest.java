package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLongSequenceTest {
    @Test public void outputAspectFitsArtWithoutStretching()throws Exception {
        Bitmap portrait=Bitmap.createBitmap(256,512,Bitmap.Config.ARGB_8888);portrait.eraseColor(Color.RED);
        Bitmap landscape=SketchExportFrameSource.fit(portrait,512,256);
        try{assertEquals(512,landscape.getWidth());assertEquals(256,landscape.getHeight());assertEquals(Color.BLACK,landscape.getPixel(100,128));assertEquals(Color.RED,landscape.getPixel(256,128));assertEquals(Color.BLACK,landscape.getPixel(411,128));}finally{landscape.recycle();}
    }
    @Test public void streamedPaintUsesFrozenPixelsAfterOriginalOwnerIsUnavailable()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String book=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",book).put("operationId","new").put("pageCount",1201).put("width",256).put("height",256));
        String id=SketchEditOps.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",book).put("page",6)).getString("sketchId");
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{executor.submit(()->{
            SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","paint").put("operations",new JSONArray().put(new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","paint"))));
            SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","line").put("operations",new JSONArray().put(new JSONObject().put("type","paint_stroke").put("objectId","paint").put("strokeId","first").put("points",new JSONArray("[[0.1,0.5,1],[0.9,0.5,1]]")).put("brush",new JSONObject().put("mode","paint").put("color","#FFFF0000").put("sizePx",16)))));
            SketchNativeExport.Prepared prepared=SketchBookExport.prepare(c,new JSONObject().put("bookId",book).put("expectedRevision",1).put("operationId","long"),(f,n)->{});
            OpenPipelineArtifacts.deleteTree(new File(c.getFilesDir(),"sketch_studio/"+id+"/paint"));
            try(SketchExportFrameSource source=prepared.openFrames()){Bitmap b=source.render(6,256,256);try{assertEquals(Color.RED,b.getPixel(128,128));}finally{b.recycle();}}
            return null;
        }).get(60,java.util.concurrent.TimeUnit.SECONDS);}finally{executor.shutdownNow();}
    }
    @Test public void tenThousandFramesStreamFrozenCelsWithoutIntermediatePngs() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","new").put("pageCount",10000).put("width",256).put("height",256));
        String cel=SketchEditOps.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",9999)).getString("sketchId");
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",cel).put("expectedRevision",1).put("operationId","red").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","red").put("kind","rect").put("fill",true).put("color","#FFFF0000").put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))))));
        JSONObject request=new JSONObject().put("bookId",id).put("expectedRevision",1).put("operationId","long");
        SketchNativeExport.Prepared p=SketchBookExport.prepare(c,request,(f,n)->{});
        assertEquals(10000,p.metadata.getInt("frameCount"));assertEquals(416666666,p.metadata.getLong("durationUs"));
        assertEquals("frozen-book-pages",p.metadata.getString("frameSource"));assertFalse(p.metadata.getBoolean("rasterFramesCaptured"));
        assertEquals(0,p.project.assets.size());assertEquals(0,p.project.clips.size());
        // Later source edits cannot alter the prepared generation, including the last cel.
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",cel).put("expectedRevision",2).put("operationId","blue").put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","red").put("patch",new JSONObject().put("color","#FF0000FF")))));
        SketchNativeExport.Prepared retry=SketchBookExport.prepare(c,request,(f,n)->{});
        try(SketchExportFrameSource frames=retry.openFrames()){
            for(int f=0;f<10000;f++){
                Bitmap b=frames.render(f,256,256);
                try{assertEquals("frame "+f,f==9999?Color.RED:Color.WHITE,b.getPixel(128,128));}finally{b.recycle();}
            }
            try{frames.render(10000,256,256);fail();}catch(IllegalArgumentException expected){}
        }
        File generation=p.file.getParentFile();for(File file:generation.listFiles())assertFalse(file.getName().startsWith("frame_"));
        assertEquals(1,SketchEditOps.execute(c,"sketch_book_get",new JSONObject().put("bookId",id)).getJSONObject("book").getInt("createdPages"));
    }

    @Test public void longSequenceHonoursHoldsAndRejectsChangedFrozenPage() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","new").put("pageCount",1200).put("width",256).put("height",256));
        SketchEditOps.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",0));
        SketchEditOps.execute(c,"sketch_book_edit",new JSONObject().put("bookId",id).put("expectedRevision",1).put("operationId","hold").put("exposures",new JSONArray().put(new JSONObject().put("page",0).put("frames",3))));
        SketchNativeExport.Prepared p=SketchBookExport.prepare(c,new JSONObject().put("bookId",id).put("expectedRevision",2).put("operationId","long"),(f,n)->{});
        assertEquals(1202,p.metadata.getInt("frameCount"));
        java.nio.file.Files.write(new File(p.file.getParentFile(),"pages.db").toPath(),"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(SketchExportFrameSource frames=p.openFrames()){fail("tampered frozen index accepted");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("PAGE_CHANGED"));}
    }
}
