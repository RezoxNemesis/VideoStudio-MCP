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
public class SketchTextTest {
 @Test public void nativeLetteringIsEditableWrappedAndRetainedAcrossSaveUndoAndReopen() throws Exception {
  Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1));JSONObject text=new JSONObject().put("id","lettering").put("kind","text").put("text","MIGHT PROVE\nUSEFUL").put("fontFamily","sans-serif").put("fontSize",.08).put("fontStyle","italic").put("textAlign","center").put("letterSpacing",.05).put("bounds",new JSONObject().put("x",.1).put("y",.3).put("width",.8).put("height",.4));
  SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","lettering").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",text))));
  try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer renderer=new SketchFrameRenderer(c,store.get(id))){Bitmap b=renderer.render(0);try{int ink=0;for(int y=77;y<180;y++)for(int x=26;x<230;x++)if(b.getPixel(x,y)!=Color.WHITE)ink++;assertTrue("Native text produces real readable glyph geometry",ink>200);assertEquals("MIGHT PROVE\nUSEFUL",SketchDocument.object(store.get(id),"lettering").getString("text"));}finally{b.recycle();}}
  SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","change_text").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","lettering").put("patch",new JSONObject().put("text","EDITABLE").put("fontSize",.06)))));SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo").put("expectedRevision",3));try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals("MIGHT PROVE\nUSEFUL",SketchDocument.object(store.get(id),"lettering").getString("text"));}
 }
 @Test public void fontUrlsAndOversizedTextAreRejectedBeforePersistence() throws Exception {
  JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString()).put("frameCount",1));JSONObject text=SketchEditOps.completeObject(new JSONObject().put("id","invalid_text").put("kind","text").put("text","hello").put("fontFamily","https://unowned/font.ttf").put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)));doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(text);try{SketchDocument.validate(doc);fail("Only native font families are supported");}catch(IllegalArgumentException expected){}
  text.put("fontFamily","sans-serif").put("text","x".repeat(4097));try{SketchDocument.validate(doc);fail("Oversized text accepted");}catch(IllegalArgumentException expected){}
  text.put("text","hello").put("fontFamily",JSONObject.NULL);try{SketchDocument.validate(doc);fail("Null font family accepted");}catch(IllegalArgumentException expected){}
 }
}
