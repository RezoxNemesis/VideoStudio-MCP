package com.rezoxnemesis.videostudio;
import org.json.*;import org.junit.Test;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import java.util.UUID;import java.util.concurrent.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchLivePreparationTest {
 @Test public void expensiveLiveGeometryPreparationRunsOffMainAndLeavesItsBaselineUnchanged() throws Exception {
  JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString()));JSONObject op=new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","new_ink").put("kind","line").put("points",new JSONArray().put(new JSONArray().put(.1).put(.2).put(1)).put(new JSONArray().put(.3).put(.4).put(1))));
  try{SketchLivePreparation.prepare(doc,op);fail("Live parsing/validation must not run on the UI event loop");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("background"));}
  ExecutorService io=Executors.newSingleThreadExecutor();try{JSONObject ready=io.submit(()->SketchLivePreparation.prepare(doc,op)).get(5,TimeUnit.SECONDS);assertEquals("new_ink",SketchDocument.object(ready,"new_ink").getString("id"));assertEquals(0,doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}finally{io.shutdownNow();}
 }
}
