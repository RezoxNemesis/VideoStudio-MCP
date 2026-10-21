package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Synthetic completed raster verifies portable paired provenance; not inference. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDrawingPairedKeyframeTest {
 LocalDrawingKeyframeTest fixture;
 @Before public void pairedReceipt()throws Exception {
  fixture=new LocalDrawingKeyframeTest();fixture.fixture();JSONObject request=fixture.record.getJSONObject("request");request.remove("objectId");request.remove("sourceSha256");request.put("frameSource",new JSONObject().put("frameIndex",5).put("documentSha256","a".repeat(64)).put("layerIds",new JSONArray().put("pose_lines"))).put("appearanceSource",new JSONObject().put("objectId","owned_appearance").put("sourceSha256","b".repeat(64))).put("conditioning",new JSONObject().put("appearanceStrength",.75).put("structureStrength",.8));
  String model=new LocalDrawingModels(fixture.context,LocalDrawingModels.PAIRED_ID).identity();fixture.record.put("modelIdentity",model).put("provider",LocalDrawingModels.PAIRED_ID).put("requestSha256",SketchDocument.sha256((model+"\n"+SketchDocument.canonical(request)).getBytes(StandardCharsets.UTF_8)));fixture.saveRecord();
 }
 @Test public void pairedRequestAndModelSurviveUndoAndEditableArchive()throws Exception {fixture.chosenDraftBecomesOnlyItsExplicitCelAndRetainsProvenanceThroughUndoAndArchive();}
 @Test public void pairedRequestAndModelSurviveCharacterComponentReuse()throws Exception {fixture.characterLibraryReusesTheKeyWithProvenanceAndAnExplicitExposure();}
 @Test public void changingAppearanceIdentityOrStrengthBreaksPortableFingerprint()throws Exception {
  SketchEditOps.execute(fixture.context,"sketch_import_reference",fixture.placement);for(String field:new String[]{"appearance","strength"}){JSONObject doc=fixture.document();JSONObject request=SketchDocument.object(doc,"ai_key").getJSONObject("reference").getJSONObject("localDrawing").getJSONObject("request");if(field.equals("appearance"))request.getJSONObject("appearanceSource").put("sourceSha256","c".repeat(64));else request.getJSONObject("conditioning").put("appearanceStrength",.5);assertThrows(Exception.class,()->SketchDocument.validate(doc));}
 }
}
