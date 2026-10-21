package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
/** Synthetic completed raster tests normal portable token-provider provenance, not model likeness. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDrawingPlusKeyframeTest {
 LocalDrawingKeyframeTest fixture;
 @Before public void receipt()throws Exception {LocalDrawingPairedKeyframeTest paired=new LocalDrawingPairedKeyframeTest();paired.pairedReceipt();fixture=paired.fixture;JSONObject request=fixture.record.getJSONObject("request");request.getJSONObject("conditioning").put("adapter","plus_face");String model=new LocalDrawingModels(fixture.context,LocalDrawingModels.PLUS_ID).identity();fixture.record.put("modelIdentity",model).put("provider",LocalDrawingModels.PLUS_ID).put("requestSha256",LocalDrawingRequestIdentity.fingerprint(model,request));fixture.saveRecord();}
 @Test public void explicitAdapterAndAllModelPinsRemainInUndoAndRestoredArchive()throws Exception {fixture.chosenDraftBecomesOnlyItsExplicitCelAndRetainsProvenanceThroughUndoAndArchive();}
 @Test public void explicitAdapterProvenanceRemainsInReusableCharacterComponent()throws Exception {fixture.characterLibraryReusesTheKeyWithProvenanceAndAnExplicitExposure();}
 @Test public void changingAdapterAfterBindingFailsPortableIdentity()throws Exception {SketchEditOps.execute(fixture.context,"sketch_import_reference",fixture.placement);JSONObject doc=fixture.document();JSONObject provenance=SketchDocument.object(doc,"ai_key").getJSONObject("reference").getJSONObject("localDrawing");provenance.getJSONObject("request").getJSONObject("conditioning").put("adapter","plus");assertThrows(Exception.class,()->SketchDocument.validate(doc));}
}
