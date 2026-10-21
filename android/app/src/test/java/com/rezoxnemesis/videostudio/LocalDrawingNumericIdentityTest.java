package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** JSON wire numbers must survive real receipt copying, UI retry, import and archives. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDrawingNumericIdentityTest {
 private static String fingerprint(String model,JSONObject request)throws Exception{return SketchDocument.sha256((model+"\n"+SketchDocument.canonical(request)).getBytes(StandardCharsets.UTF_8));}
 private static void floatingRect(JSONObject rect)throws Exception{rect.put("x",0.0d).put("y",0.0d).put("width",1.0d).put("height",1.0d);}
 @Test public void actualManualWholeDoublePairedRequestSurvivesAdmissionReadAndNumericRetry()throws Exception {
  LocalDrawingPairedSourceTest fixture=new LocalDrawingPairedSourceTest();fixture.fixture();JSONObject request=fixture.request;request.getJSONObject("settings").put("guidance",7.0d).put("strength",1.0d);floatingRect(request.getJSONObject("frameSource").getJSONObject("crop"));floatingRect(request.getJSONObject("appearanceSource").getJSONObject("sourceRect"));request.getJSONObject("conditioning").put("appearanceStrength",1.0d).put("structureStrength",1.0d);
  assertTrue(request.getJSONObject("settings").get("guidance") instanceof Double);Exception failure=assertThrows(Exception.class,()->fixture.execute(request));assertTrue("A valid UI request must reach model admission, not reject its own saved numeric identity: "+failure.getMessage(),failure.getMessage().contains("MODEL_NOT_INSTALLED"));
  JSONObject receipt=LocalDrawingOperations.execute(fixture.context,"local_drawing_get",new JSONObject().put("requestId",request.getString("requestId")),null);assertEquals("failed",receipt.getString("state"));JSONObject wire=new JSONObject(request.toString());String expected=fingerprint(new LocalDrawingModels(fixture.context,LocalDrawingModels.PAIRED_ID).identity(),wire);assertEquals(expected,receipt.getString("requestSha256"));
  Exception retry=assertThrows(Exception.class,()->fixture.execute(wire));assertTrue(retry.getMessage(),retry.getMessage().contains("MODEL_NOT_INSTALLED"));assertEquals(expected,LocalDrawingOperations.execute(fixture.context,"local_drawing_get",new JSONObject().put("requestId",request.getString("requestId")),null).getString("requestSha256"));
 }
 @Test public void validPublishedIntegerFingerprintReplaysWithEquivalentUiDoubleUnchanged()throws Exception {
  LocalDrawingKeyframeTest fixture=new LocalDrawingKeyframeTest();fixture.fixture();String original=fixture.record.getString("requestSha256"),recordBytes=SketchProjectStore.fileHash(new File(fixture.output.getParentFile(),"record.json"));JSONObject manual=SketchDocument.copy(fixture.record.getJSONObject("request"));manual.getJSONObject("settings").put("guidance",7.0d);assertTrue(manual.getJSONObject("settings").get("guidance") instanceof Double);
  JSONObject replay=LocalDrawingOperations.execute(fixture.context,"local_drawing_generate",manual,null);assertTrue(replay.getBoolean("replayed"));assertEquals(original,replay.getString("requestSha256"));assertEquals(recordBytes,SketchProjectStore.fileHash(new File(fixture.output.getParentFile(),"record.json")));
 }
 @Test public void historicalManualDoubleLedgerHashAndIdRemainReplayableWhileNewPortableProofIsWireStable()throws Exception {
  LocalDrawingKeyframeTest fixture=new LocalDrawingKeyframeTest();fixture.fixture();JSONObject raw=fixture.record.getJSONObject("request");raw.getJSONObject("settings").put("guidance",7.0d).put("strength",1.0d);raw.put("sourceRect",new JSONObject());floatingRect(raw.getJSONObject("sourceRect"));String model=fixture.record.getString("modelIdentity"),historical=fingerprint(model,raw);fixture.record.put("requestSha256",historical);fixture.saveRecord();File ledger=new File(fixture.output.getParentFile(),"record.json");String retainedBytes=SketchProjectStore.fileHash(ledger);JSONObject wire=new JSONObject(raw.toString());assertNotEquals("Fixture reproduces actual JSONObject whole-Double loss",historical,fingerprint(model,wire));
  JSONObject saved=LocalDrawingOperations.execute(fixture.context,"local_drawing_get",new JSONObject().put("requestId","key_fixture"),null);assertEquals(historical,saved.getString("requestSha256"));JSONObject replay=LocalDrawingOperations.execute(fixture.context,"local_drawing_generate",wire,null);assertTrue(replay.getBoolean("replayed"));assertEquals(historical,replay.getString("requestSha256"));assertEquals(retainedBytes,SketchProjectStore.fileHash(ledger));
  SketchEditOps.execute(fixture.context,"sketch_import_reference",fixture.placement);JSONObject provenance=SketchDocument.object(fixture.document(),"ai_key").getJSONObject("reference").getJSONObject("localDrawing");assertEquals(SketchDocument.canonical(wire),SketchDocument.canonical(provenance.getJSONObject("request")));assertEquals(fingerprint(model,wire),provenance.getString("requestSha256"));assertEquals(historical,LocalDrawingOperations.execute(fixture.context,"local_drawing_get",new JSONObject().put("requestId","key_fixture"),null).getString("requestSha256"));
  SketchEditOps.execute(fixture.context,"sketch_undo",new JSONObject().put("sketchId","animation").put("operationId","undo_manual_key").put("expectedRevision",2));SketchEditOps.execute(fixture.context,"sketch_redo",new JSONObject().put("sketchId","animation").put("operationId","redo_manual_key").put("expectedRevision",3));SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(fixture.context,new JSONObject().put("sketchId","animation").put("operationId","archive_manual_key").put("expectedRevision",4));JSONObject restored=SketchPaintArchive.restoreFile(fixture.context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_manual_key","Restored manual-number key");try(SketchProjectStore sketches=new SketchProjectStore(fixture.context)){assertEquals(SketchDocument.canonical(provenance),SketchDocument.canonical(SketchDocument.object(sketches.get(restored.getString("sketchId")),"ai_key").getJSONObject("reference").getJSONObject("localDrawing")));}assertEquals(retainedBytes,SketchProjectStore.fileHash(ledger));
 }
 @Test public void numericCompatibilityNeverAcceptsChangedMeaningOrAnUnboundFingerprint()throws Exception {
  LocalDrawingKeyframeTest fixture=new LocalDrawingKeyframeTest();fixture.fixture();fixture.record.getJSONObject("request").getJSONObject("settings").put("strength",.9);fixture.saveRecord();assertThrows(Exception.class,()->LocalDrawingOperations.execute(fixture.context,"local_drawing_get",new JSONObject().put("requestId","key_fixture"),null));
 }
}
