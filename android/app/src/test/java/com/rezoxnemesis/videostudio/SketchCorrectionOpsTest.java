package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Exercises actual SQLite, native QA/preview and ordinary atomic history. No mock critic. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchCorrectionOpsTest {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 @After public void stop(){worker.shutdownNow();}
 private JSONObject call(String action,JSONObject args)throws Exception{
  Future<JSONObject> f=worker.submit(()->{Class<?> type;try{type=Class.forName("com.rezoxnemesis.videostudio.SketchCorrectionOps");}catch(ClassNotFoundException e){throw new AssertionError("App-owned correction coordinator is missing",e);}
   try{return (JSONObject)type.getDeclaredMethod("execute",Context.class,String.class,JSONObject.class).invoke(null,RuntimeEnvironment.getApplication(),action,args);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw(Exception)t;if(t instanceof Error)throw(Error)t;throw new RuntimeException(t);}});
  try{return f.get(90,TimeUnit.SECONDS);}catch(ExecutionException e){Throwable t=e.getCause();if(t instanceof Exception)throw(Exception)t;if(t instanceof Error)throw(Error)t;throw new RuntimeException(t);}
 }
 private JSONObject doc()throws Exception{try(SketchProjectStore s=new SketchProjectStore(RuntimeEnvironment.getApplication())){return s.get("drawing");}}
 private void create()throws Exception{
  SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_create",new JSONObject().put("sketchId","drawing").put("operationId","create").put("width",1280).put("height",720).put("frameCount",24).put("fps",24));
  JSONObject path=new JSONObject().put("id","figure").put("kind","path").put("frame",0).put("exposure",24).put("fill",true).put("color","#FF344657").put("commands",commands(.25));
  SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_edit",new JSONObject().put("sketchId","drawing").put("operationId","draw").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",path))));
 }
 private JSONArray commands(double x)throws Exception{return new JSONArray().put(new JSONArray().put("M").put(x).put(.2)).put(new JSONArray().put("L").put(x+.16).put(.2)).put(new JSONArray().put("L").put(x+.16).put(.7)).put(new JSONArray().put("L").put(x).put(.7)).put(new JSONArray().put("Z"));}
 private JSONObject id()throws Exception{return new JSONObject().put("sketchId","drawing").put("reviewId","review");}
 private JSONObject startArgs()throws Exception{return id().put("expectedRevision",2).put("target","Correct placement while preserving timing").put("frameOffset",0).put("frameLimit",2).put("frameStep",1).put("motionRequired",false);}
 private JSONObject start()throws Exception{return call("sketch_correction_start",startArgs());}
 private JSONObject note()throws Exception{return call("sketch_correction_note",id().put("expectedReviewRevision",1).put("noteId","placement").put("outcome","NEEDS_REWORK").put("note","Move the selected original drawing right").put("objectId","figure").put("frameStart",0).put("frameEnd",23));}
 private JSONObject proposalArgs()throws Exception{return id().put("expectedReviewRevision",2).put("proposalId","move").put("summary","Redraw native path at corrected placement").put("noteIds",new JSONArray().put("placement")).put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","figure").put("patch",new JSONObject().put("commands",commands(.47)))));}
 private JSONObject proposed()throws Exception{start();note();return call("sketch_correction_propose",proposalArgs());}
 private JSONObject applyArgs()throws Exception{return id().put("expectedReviewRevision",3).put("proposalId","move").put("expectedRevision",2).put("operationId","apply_move");}
 @Test public void reviewAndProposalPersistWithoutChangingSourceOrClaimingArtAcceptance()throws Exception{
  create();String before=SketchDocument.canonical(doc());JSONObject p=proposed(),state=p.getJSONObject("review");assertEquals(3,state.getInt("revision"));assertEquals("OPEN",state.getString("status"));assertEquals(1,state.getJSONArray("notes").length());assertEquals(1,state.getJSONArray("proposals").length());assertEquals(before,SketchDocument.canonical(doc()));
  assertFalse(state.getJSONObject("audit").getBoolean("visualAcceptanceVerified"));assertEquals(SketchDocument.canonical(state),SketchDocument.canonical(call("sketch_correction_get",id()).getJSONObject("review")));
 }
 @Test public void actualNativeBeforeAfterImagesDifferWhileSourceRemainsIdentical()throws Exception{
  create();proposed();String original=SketchDocument.canonical(doc());JSONObject before=call("sketch_correction_preview",id().put("side","before").put("frameIndex",0)),after=call("sketch_correction_preview",id().put("side","after").put("proposalId","move").put("frameIndex",0));
  assertNotEquals(before.getJSONObject("sketchPreviewImage").getString("sha256"),after.getJSONObject("sketchPreviewImage").getString("sha256"));assertFalse(after.getBoolean("artisticQualityVerified"));assertEquals(original,SketchDocument.canonical(doc()));
 }
 @Test public void oneAtomicApplyIsIdempotentAndNormalUndoRedoRestoreExactArtwork()throws Exception{
  create();JSONArray original=new JSONArray(doc().getJSONArray("layers").toString());proposed();JSONObject result=call("sketch_correction_apply",applyArgs());assertEquals(3,doc().getInt("revision"));assertEquals("APPLIED",result.getJSONObject("review").getString("status"));assertTrue(result.getBoolean("sourceModified"));String fixed=SketchDocument.canonical(doc().getJSONArray("layers"));
  JSONObject replay=call("sketch_correction_apply",applyArgs());assertEquals(3,doc().getInt("revision"));assertEquals(SketchDocument.canonical(result.getJSONObject("edit")),SketchDocument.canonical(replay.getJSONObject("edit")));
  SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_undo",new JSONObject().put("sketchId","drawing").put("expectedRevision",3).put("operationId","undo"));assertEquals(SketchDocument.canonical(original),SketchDocument.canonical(doc().getJSONArray("layers")));
  SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_redo",new JSONObject().put("sketchId","drawing").put("expectedRevision",4).put("operationId","redo"));assertEquals(fixed,SketchDocument.canonical(doc().getJSONArray("layers")));assertEquals(24,doc().getInt("fps"));assertEquals(24,doc().getInt("frameCount"));assertEquals(1280,doc().getInt("width"));assertEquals(720,doc().getInt("height"));
 }
 @Test public void staleSourceAndReviewRevisionsRejectWithoutLosingConcurrentEdits()throws Exception{
  create();proposed();assertThrows(IllegalStateException.class,()->call("sketch_correction_apply",applyArgs().put("expectedReviewRevision",2)));
  SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_edit",new JSONObject().put("sketchId","drawing").put("expectedRevision",2).put("operationId","manual_change").put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","figure").put("patch",new JSONObject().put("color","#FFAB3344")))));
  String latest=SketchDocument.canonical(doc());assertFalse(call("sketch_correction_get",id()).getBoolean("sourceMatches"));assertThrows(IllegalStateException.class,()->call("sketch_correction_apply",applyArgs()));assertEquals(latest,SketchDocument.canonical(doc()));
 }
 @Test public void exactNoteProposalAndStartReplayButChangedPayloadsConflict()throws Exception{
  create();proposed();assertEquals(3,call("sketch_correction_start",startArgs()).getJSONObject("review").getInt("revision"));note();call("sketch_correction_propose",proposalArgs());assertEquals(3,call("sketch_correction_get",id()).getJSONObject("review").getInt("revision"));
  assertThrows(IllegalStateException.class,()->call("sketch_correction_start",startArgs().put("target","Different intent")));assertThrows(IllegalStateException.class,()->call("sketch_correction_propose",proposalArgs().put("summary","Different correction")));
 }
 @Test public void invalidTimingPaintPointersAndUnknownIssueReferencesNeverPublishCandidates()throws Exception{
  create();start();note();String before=SketchDocument.canonical(doc());JSONObject p=proposalArgs();p.put("operations",new JSONArray().put(new JSONObject().put("type","set_canvas").put("frameCount",12)));assertThrows(IllegalArgumentException.class,()->call("sketch_correction_propose",p));
  JSONObject unknown=proposalArgs().put("noteIds",new JSONArray().put("missing"));assertThrows(IllegalArgumentException.class,()->call("sketch_correction_propose",unknown));
  JSONObject paint=proposalArgs().put("operations",new JSONArray().put(new JSONObject().put("type","paint_stroke").put("objectId","figure")));assertThrows(IllegalArgumentException.class,()->call("sketch_correction_propose",paint));assertEquals(before,SketchDocument.canonical(doc()));assertEquals(0,call("sketch_correction_get",id()).getJSONObject("review").getJSONArray("proposals").length());
 }
 @Test public void savedReviewsAreDiscoverableAndCandidateChecksAreActualBoundedNativeAudit()throws Exception{
  create();proposed();JSONObject list=call("sketch_correction_list",new JSONObject().put("sketchId","drawing").put("limit",1));assertEquals("review",list.getJSONArray("reviews").getJSONObject(0).getString("reviewId"));assertFalse(list.getBoolean("sourceModified"));
  JSONObject p=call("sketch_correction_get",id()).getJSONObject("review").getJSONArray("proposals").getJSONObject(0);assertTrue(p.getJSONObject("nativeChecks").getBoolean("clockPreserved"));assertEquals(2,p.getJSONObject("candidateAudit").getInt("sampleCount"));assertFalse(p.getJSONObject("candidateAudit").getBoolean("visualAcceptanceVerified"));
 }
 @Test public void lostCoordinatorApplyAcknowledgmentRecoversFromAuthoritativeReceipt()throws Exception{
  create();proposed();JSONObject before=call("sketch_correction_get",id()).getJSONObject("review");call("sketch_correction_apply",applyArgs());String applied=SketchDocument.canonical(doc());
  // Simulate termination after the artwork transaction and before its coordinator write.
  try(SketchCorrectionStore s=new SketchCorrectionStore(RuntimeEnvironment.getApplication())){android.content.ContentValues v=new android.content.ContentValues();v.put("state",before.toString());v.put("revision",before.getLong("revision"));v.put("status","OPEN");assertEquals(1,s.db.update("reviews",v,"id=?",new String[]{"review"}));}
  JSONObject recovered=call("sketch_correction_apply",applyArgs());assertEquals("APPLIED",recovered.getJSONObject("review").getString("status"));assertEquals(applied,SketchDocument.canonical(doc()));assertEquals(3,doc().getInt("revision"));
 }
 @Test public void terminalReviewMetadataHasReservedSpaceEvenAtItsStorageQuota()throws Exception{
  create();proposed();JSONObject state=call("sketch_correction_get",id()).getJSONObject("review");long used=0;
  try(SketchCorrectionStore s=new SketchCorrectionStore(RuntimeEnvironment.getApplication())){for(String sql:new String[]{"SELECT COALESCE(SUM(length(CAST(source AS BLOB))+length(CAST(state AS BLOB))),0) FROM reviews","SELECT COALESCE(SUM(length(CAST(document AS BLOB))+length(CAST(operations AS BLOB))+length(CAST(metadata AS BLOB))),0) FROM proposals"})try(android.database.Cursor c=s.db.rawQuery(sql,null)){c.moveToFirst();used+=c.getLong(0);}}
  // Constructor changes only this native-store test budget; shipped coordinator uses128MiB.
  try(SketchCorrectionStore s=new SketchCorrectionStore(RuntimeEnvironment.getApplication(),used+2048)){JSONObject growing=SketchDocument.copy(state);growing.getJSONArray("notes").put(new JSONObject().put("noteId","quota_note").put("note",new String(new char[1000]).replace('\0','a')).put("outcome","UNREVIEWED").put("frameStart",0).put("frameEnd",23).put("requestHash",new String(new char[64]).replace('\0','a')));assertThrows(IllegalStateException.class,()->s.write("drawing","review",3,growing));state.put("status","APPLIED").put("appliedProposalId","move").put("appliedOperationId","apply_move").put("appliedRevision",3);s.write("drawing","review",3,state);assertEquals("APPLIED",s.state("drawing","review").getString("status"));}
 }
 @Test public void proposalCannotPublishNewReferenceDependencies()throws Exception{
  create();start();note();String h=new String(new char[64]).replace('\0','a');JSONObject image=new JSONObject().put("id","unowned_image").put("kind","image").put("bounds",new JSONObject().put("x",.1).put("y",.1).put("width",.2).put("height",.2)).put("reference",new JSONObject().put("file",h+".png").put("sha256",h).put("width",1).put("height",1).put("sourceProjectId","p").put("sourceAssetId","a"));
  JSONObject p=proposalArgs().put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",image)));
  assertThrows(Exception.class,()->call("sketch_correction_propose",p));assertEquals(0,call("sketch_correction_get",id()).getJSONObject("review").getJSONArray("proposals").length());assertEquals(2,doc().getInt("revision"));
 }
 @Test public void proposalCannotRevealHiddenGuideDuringOriginalDrawing()throws Exception{
  Context c=RuntimeEnvironment.getApplication();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId","drawing").put("operationId","create").put("width",1280).put("height",720).put("frameCount",24).put("fps",24));
  SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId","drawing").put("operationId","original").put("expectedRevision",1).put("drawingMethod","original_strokes"));
  java.io.File file=new java.io.File(c.getFilesDir(),"unit-policy-guide.png");android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);try{bitmap.eraseColor(android.graphics.Color.RED);try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}}finally{bitmap.recycle();}
  ProjectStore projects=new ProjectStore(c);try{ProjectStore.Project project=projects.create("Unit policy reference");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source";asset.name="Owned guide fixture";asset.mime="image/png";asset.uri=android.net.Uri.fromFile(file).toString();project.assets.add(asset);projects.save(project);
   SketchEditOps.execute(c,"sketch_import_reference",new JSONObject().put("sketchId","drawing").put("operationId","guide").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false));
  }finally{/* ProjectStore has no close surface; ordinary app API owns its helper. */}
  start();call("sketch_correction_note",id().put("expectedReviewRevision",1).put("noteId","placement").put("outcome","NEEDS_REWORK").put("note","Inspect hidden guide policy").put("frameStart",0).put("frameEnd",23));
  JSONObject p=proposalArgs().put("operations",new JSONArray().put(new JSONObject().put("type","set_transform").put("objectId","guide").put("transform",new JSONObject().put("opacity",1))));assertThrows(IllegalStateException.class,()->call("sketch_correction_propose",p));assertEquals(0,call("sketch_correction_get",id()).getJSONObject("review").getJSONArray("proposals").length());assertEquals(0,SketchDocument.object(doc(),"guide").getJSONObject("transform").getDouble("opacity"),0);
 }
 @Test public void ownershipAndAfterPreviewRequirementsAreStrict()throws Exception{
  create();start();assertThrows(IllegalArgumentException.class,()->call("sketch_correction_get",id().put("sketchId","another")));assertThrows(IllegalArgumentException.class,()->call("sketch_correction_preview",id().put("side","after").put("frameIndex",0)));assertThrows(IllegalArgumentException.class,()->call("sketch_correction_preview",id().put("side","before").put("frameIndex",24)));
 }
}
