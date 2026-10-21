package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLocalArchiveTest {
    @Test public void localArchiveKeepsTheAnimationClockGeometryAndRenderedFrames()throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject source=source(c);SketchPaintArchive.Prepared packed=pack(c,source);Uri uri=Uri.parse("content://owned.documents/animation.vssketch");org.robolectric.Shadows.shadowOf(c.getContentResolver()).registerInputStream(uri,new FileInputStream(packed.file));
        JSONObject result=SketchLocalArchive.restore(c,uri,"restore_local",null);assertNotNull("A downloaded editable animation must restore natively",result);
        try(SketchProjectStore store=new SketchProjectStore(c)){
            JSONObject restored=store.get(result.getString("sketchId"));assertEquals(325,restored.getInt("frameCount"));assertEquals(30,restored.getInt("fps"));assertEquals(2,SketchDocument.object(restored,"moving").getJSONArray("shapeKeys").length());
            try(SketchFrameRenderer a=new SketchFrameRenderer(c,source);SketchFrameRenderer b=new SketchFrameRenderer(c,restored)){
                for(int frame:new int[]{0,75,100,198,324}){Bitmap x=a.render(frame),y=b.render(frame);try{assertTrue("Restored native pixels differ at frame "+frame,x.sameAs(y));}finally{x.recycle();y.recycle();}}
            }
            assertEquals(1,store.get(source.getString("id")).getInt("revision"));
        }
    }
    @Test public void retryKeepsAnArtistsChangesToTheImportedProject()throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject source=source(c);SketchPaintArchive.Prepared packed=pack(c,source);Uri uri=Uri.parse("content://owned.documents/retry.vssketch");
        org.robolectric.Shadows.shadowOf(c.getContentResolver()).registerInputStream(uri,new FileInputStream(packed.file));JSONObject first=SketchLocalArchive.restore(c,uri,"retry_local",null);assertNotNull(first);String id=first.getString("sketchId");
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","artist").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","palette_upsert").put("styleId","ink").put("name","Artist ink").put("color","#FF226688"))));
        org.robolectric.Shadows.shadowOf(c.getContentResolver()).registerInputStream(uri,new FileInputStream(packed.file));assertEquals(id,SketchLocalArchive.restore(c,uri,"retry_local",null).getString("sketchId"));try(SketchProjectStore s=new SketchProjectStore(c)){assertEquals(2,s.get(id).getInt("revision"));assertEquals(Integer.valueOf(0xff226688),SketchPaletteStyles.indexed(s.get(id)).get("ink"));}
    }
    @Test public void malformedSelectionDoesNotCreateOrReplaceASketch()throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject original=source(c);int before;try(SketchProjectStore store=new SketchProjectStore(c)){before=store.list().length();}
        Uri uri=Uri.parse("content://owned.documents/bad.vssketch");org.robolectric.Shadows.shadowOf(c.getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(new byte[]{1,2,3}));assertThrows(Exception.class,()->SketchLocalArchive.restore(c,uri,"bad_local",null));
        try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(before,store.list().length());assertEquals(1,store.get(original.getString("id")).getInt("revision"));}
    }
    static SketchPaintArchive.Prepared pack(Context c,JSONObject doc)throws Exception{return SketchPaintArchive.prepare(c,new JSONObject().put("sketchId",doc.getString("id")).put("expectedRevision",1).put("operationId","native_archive"));}
    static JSONObject source(Context c)throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","source_"+java.util.UUID.randomUUID()).put("width",320).put("height",320).put("frameCount",325).put("fps",30));
        JSONArray p=commands(.12),end=commands(.63);JSONObject o=new JSONObject().put("id","moving").put("kind","path").put("frame",0).put("exposure",325).put("fill",true).put("color","#FF113366").put("styleId","ink").put("width",.01).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("commands",p).put("shapeKeys",new JSONArray().put(new JSONObject().put("frame",0).put("commands",p).put("interpolation","linear")).put(new JSONObject().put("frame",100).put("commands",end).put("interpolation","linear")));
        d.put("paletteStyles",new JSONArray().put(new JSONObject().put("id","ink").put("name","Ink").put("color","#FF113366")));d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(o);try(SketchProjectStore store=new SketchProjectStore(c)){store.importOwnedDocument(d,"original");return store.get(d.getString("id"));}
    }
    static JSONArray commands(double x)throws Exception {return new JSONArray().put(new JSONArray().put("M").put(x).put(.2)).put(new JSONArray().put("L").put(x+.15).put(.2)).put(new JSONArray().put("L").put(x+.15).put(.4)).put(new JSONArray().put("L").put(x).put(.4)).put(new JSONArray().put("Z"));}
}
