package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Synthetic completed output tests portable frame-source identity, not inference. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDrawingFrameKeyframeTest {
 LocalDrawingKeyframeTest fixture;
 @Before public void frameReceipt()throws Exception {
  fixture=new LocalDrawingKeyframeTest();fixture.fixture();JSONObject request=fixture.record.getJSONObject("request");request.remove("objectId");request.remove("sourceSha256");
  request.put("frameSource",new JSONObject().put("frameIndex",5).put("documentSha256","a".repeat(64)).put("layerIds",new JSONArray().put("pose_lines").put("face_details")).put("crop",new JSONObject().put("x",.1).put("y",.1).put("width",.8).put("height",.8)));
  fixture.record.put("requestSha256",SketchDocument.sha256((fixture.record.getString("modelIdentity")+"\n"+SketchDocument.canonical(request)).getBytes(StandardCharsets.UTF_8)));fixture.saveRecord();
 }
 @Test public void explicitFrameSourceSurvivesUndoAndEditableArchive()throws Exception {fixture.chosenDraftBecomesOnlyItsExplicitCelAndRetainsProvenanceThroughUndoAndArchive();}
 @Test public void explicitFrameSourceSurvivesCharacterComponentReuse()throws Exception {fixture.characterLibraryReusesTheKeyWithProvenanceAndAnExplicitExposure();}
 @Test public void changedSourceFrameOrPreviewCannotBecomeValidPortableProvenance()throws Exception {
  SketchEditOps.execute(fixture.context,"sketch_import_reference",fixture.placement);JSONObject document=fixture.document();JSONObject request=SketchDocument.object(document,"ai_key").getJSONObject("reference").getJSONObject("localDrawing").getJSONObject("request");request.getJSONObject("frameSource").put("frameIndex",6);assertThrows(Exception.class,()->SketchDocument.validate(document));
  JSONObject preview=SketchDocument.copy(fixture.record.getJSONObject("request")).put("previewOnly",true);assertThrows(Exception.class,()->LocalDrawingSource.validateStored(preview));
 }
}
