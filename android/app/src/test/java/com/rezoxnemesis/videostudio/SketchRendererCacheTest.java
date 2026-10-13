package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchRendererCacheTest {
    @Test public void warmedGeometryChangesAfterEditsAndExposureIndexPreservesFrameOrder() throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString()).put("width",256).put("height",256).put("frameCount",192));
        for(int frame=0;frame<192;frame++)SketchEditOps.apply(doc,new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","cel_"+frame).put("kind","path").put("commands",new JSONArray().put(new JSONArray().put("M").put(.1).put(.1)).put(new JSONArray().put("L").put(.9).put(.1)).put(new JSONArray().put("L").put(.9).put(.9)).put(new JSONArray().put("L").put(.1).put(.9)).put(new JSONArray().put("Z"))).put("fill",true).put("color",frame%2==0?"#FFFF0000":"#FF0000FF").put("frame",frame)));
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
            for(int frame:new int[]{0,1,191,0}){Bitmap b=renderer.render(frame);try{assertEquals(frame%2==0?Color.RED:Color.BLUE,b.getPixel(128,128));}finally{b.recycle();}}
            SketchEditOps.apply(doc,new JSONObject().put("type","update_object").put("objectId","cel_0").put("patch",new JSONObject().put("color","#FF00FF00").put("commands",new JSONArray().put(new JSONArray().put("M").put(.1).put(.1)).put(new JSONArray().put("L").put(.2).put(.1)).put(new JSONArray().put("L").put(.2).put(.2)).put(new JSONArray().put("Z")))));
            renderer.updateDocument(RuntimeEnvironment.getApplication(),doc);Bitmap b=renderer.render(0);try{assertEquals(Color.WHITE,b.getPixel(128,128));assertEquals(Color.GREEN,b.getPixel(45,30));}finally{b.recycle();}
        }
    }
    @Test public void agentCanPageAllCurveCommandsWithoutLosingTheirSourceOrder() throws Exception {
        android.content.Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create"));JSONArray commands=new JSONArray().put(new JSONArray().put("M").put(.1).put(.2));for(int n=1;n<1300;n++)commands.put(new JSONArray().put("L").put(n/1500d).put(.2));SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","curve").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","curve").put("kind","path").put("commands",commands)))));
        JSONArray collected=new JSONArray();for(int offset=0;offset<1300;offset+=512){JSONObject page=SketchEditOps.execute(c,"sketch_get",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("objectId","curve").put("commandOffset",offset).put("commandLimit",512));assertEquals(1300,page.getInt("commandCount"));JSONArray points=page.getJSONObject("object").getJSONArray("commands");for(int n=0;n<points.length();n++)collected.put(points.getJSONArray(n));}assertEquals(commands.toString(),collected.toString());
    }

}
