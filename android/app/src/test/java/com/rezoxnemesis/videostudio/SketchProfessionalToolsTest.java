package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchProfessionalToolsTest {
    final Context context=RuntimeEnvironment.getApplication();
    JSONObject create(int frames) throws Exception {
        String id=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",frames).put("background","#FFFFFFFF"));
        try(SketchProjectStore s=new SketchProjectStore(context)){return s.get(id);}
    }
    JSONObject edit(JSONObject doc,JSONObject...ops) throws Exception {
        JSONArray list=new JSONArray();for(JSONObject op:ops)list.put(op);
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",doc.getString("id")).put("expectedRevision",doc.getLong("revision")).put("operationId",UUID.randomUUID().toString()).put("operations",list));
        try(SketchProjectStore s=new SketchProjectStore(context)){return s.get(doc.getString("id"));}
    }
    JSONObject add(JSONObject o) throws Exception {return new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",o);}
    JSONObject dot(String id) throws Exception {return new JSONObject().put("id",id).put("kind","stroke").put("color","#FF000000").put("width",.15).put("points",new JSONArray().put(new JSONArray().put(.5).put(.5).put(1)));}
    @Test public void cubicPathAndGradientProduceCurvedColouredPixels() throws Exception {
        JSONObject path=new JSONObject().put("id","curve").put("kind","path").put("color","#FFFF0000").put("width",.04).put("commands",new JSONArray("[[\"M\",0.1,0.8],[\"C\",0.1,0.1,0.9,0.1,0.9,0.8]]"));
        JSONObject box=new JSONObject().put("id","gradient").put("kind","rect").put("fill",true).put("bounds",new JSONObject().put("x",.1).put("y",.85).put("width",.8).put("height",.1))
                .put("gradient",new JSONObject().put("startColor","#FF0000FF").put("endColor","#FFFF0000").put("x0",.1).put("y0",.9).put("x1",.9).put("y1",.9));
        JSONObject doc=edit(create(1),add(path),add(box));
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){Bitmap b=renderer.render(0);try{assertTrue(Color.red(b.getPixel(128,71))>200);assertTrue(Color.blue(b.getPixel(32,230))>Color.red(b.getPixel(32,230)));assertTrue(Color.red(b.getPixel(224,230))>Color.blue(b.getPixel(224,230)));assertEquals("curve",SketchFrameRenderer.hitTest(doc,0,.5,.275,.02));}finally{b.recycle();}}
    }
    @Test public void airbrushHasSoftEdgesAndOpacityActuallyChangesPixels() throws Exception {
        JSONObject brush=new JSONObject().put("preset","airbrush").put("opacity",.5).put("hardness",.2).put("pressure",true).put("angle",0);
        JSONObject doc=edit(create(1),add(dot("air").put("brush",brush)));
        try(SketchFrameRenderer r=new SketchFrameRenderer(context,doc)){Bitmap b=r.render(0);try{int center=Color.red(b.getPixel(128,128)),edge=Color.red(b.getPixel(143,128));assertTrue("center="+center,center>90&&center<200);assertTrue("edge="+edge+", center="+center,edge>center);assertEquals(Color.WHITE,b.getPixel(155,128));}finally{b.recycle();}}
    }
    @Test public void layerMultiplyIsSharedByPreviewAndExport() throws Exception {
        JSONObject rect=dot("red").put("kind","rect").put("fill",true).put("color","#FFFF0000").put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1));rect.remove("points");
        JSONObject blue=new JSONObject(rect.toString()).put("id","blue").put("color","#FF0000FF");
        JSONObject doc=edit(create(1),add(rect),new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","top").put("blendMode","multiply")),new JSONObject().put("type","add_object").put("layerId","top").put("object",blue));
        try(SketchFrameRenderer r=new SketchFrameRenderer(context,doc)){Bitmap b=r.render(0);try{assertEquals(Color.BLACK,b.getPixel(128,128));}finally{b.recycle();}}
    }
    @Test public void duplicateCelCopiesMoreThan31ObjectsAtomicallyAndUndoRestores() throws Exception {
        JSONObject doc=create(3);JSONArray ops=new JSONArray();for(int i=0;i<45;i++)ops.put(add(dot("s"+i)));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",doc.getString("id")).put("expectedRevision",1).put("operationId","many").put("operations",ops));try(SketchProjectStore s=new SketchProjectStore(context)){doc=s.get(doc.getString("id"));}
        doc=edit(doc,new JSONObject().put("type","duplicate_cel").put("layerId","layer_1").put("fromFrame",0).put("toFrame",1).put("idPrefix","copy"));
        assertEquals(90,SketchDocument.layer(doc,"layer_1").getJSONArray("objects").length());assertEquals(1,SketchDocument.object(doc,"copy_0").getInt("frame"));
        SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",doc.getString("id")).put("expectedRevision",doc.getLong("revision")).put("operationId","undo"));try(SketchProjectStore s=new SketchProjectStore(context)){assertEquals(45,SketchDocument.layer(s.get(doc.getString("id")),"layer_1").getJSONArray("objects").length());}
    }
    @Test public void insertingAndDeletingFramesPreservesExposureAndKeyClock() throws Exception {
        JSONObject doc=edit(create(4),add(dot("held").put("exposure",3)),new JSONObject().put("type","set_keyframe").put("objectId","held").put("frame",2).put("transform",new JSONObject().put("x",.2)));
        doc=edit(doc,new JSONObject().put("type","insert_frame").put("frame",1));assertEquals(5,doc.getInt("frameCount"));assertEquals(4,SketchDocument.object(doc,"held").getInt("exposure"));assertEquals(3,SketchDocument.object(doc,"held").getJSONArray("keys").getJSONObject(0).getInt("frame"));
        doc=edit(doc,new JSONObject().put("type","delete_frame").put("frame",1));assertEquals(4,doc.getInt("frameCount"));assertEquals(3,SketchDocument.object(doc,"held").getInt("exposure"));assertEquals(2,SketchDocument.object(doc,"held").getJSONArray("keys").getJSONObject(0).getInt("frame"));
    }
    @Test public void malformedBezierRejectsEntireBatchWithoutSaving() throws Exception {
        JSONObject doc=create(1);JSONObject broken=new JSONObject().put("id","bad").put("kind","path").put("commands",new JSONArray("[[\"M\",0,0],[\"C\",0.2,0.2,0.4,0.4,2,0.5]]"));
        try{edit(doc,add(dot("valid")),add(broken));fail("Out-of-canvas curve accepted");}catch(IllegalArgumentException expected){}
        try(SketchProjectStore s=new SketchProjectStore(context)){assertEquals(1,s.get(doc.getString("id")).getLong("revision"));assertEquals(0,SketchDocument.layer(s.get(doc.getString("id")),"layer_1").getJSONArray("objects").length());}
    }
}
