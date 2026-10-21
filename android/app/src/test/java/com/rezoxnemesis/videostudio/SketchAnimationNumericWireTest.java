package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Animation persistence must hash the program representation it actually stores. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAnimationNumericWireTest {
 private final Context context=RuntimeEnvironment.getApplication();private final ExecutorService worker=Executors.newSingleThreadExecutor();
 @After public void close(){worker.shutdownNow();}
 private void run(Callable<Void> task)throws Exception{try{worker.submit(task).get(180,TimeUnit.SECONDS);}catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw new RuntimeException(cause);}}
 private String hash(JSONObject object)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(object).getBytes(StandardCharsets.UTF_8));}
 private JSONObject source()throws Exception{SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId","numeric_source").put("operationId","new").put("width",256).put("height",256).put("frameCount",24));SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId","numeric_source").put("expectedRevision",1).put("operationId","art").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","part").put("kind","rect").put("fill",true).put("frame",0).put("exposure",24).put("bounds",new JSONObject().put("x",.4).put("y",.4).put("width",.2).put("height",.2))))));try(SketchProjectStore store=new SketchProjectStore(context)){return store.get("numeric_source");}}
 private JSONObject program(boolean doubles)throws Exception{JSONArray poses=new JSONArray();int[] frames={0,12,23},angles={0,2,0};for(int i=0;i<frames.length;i++){JSONObject angle=new JSONObject();if(doubles)angle.put("root",(double)angles[i]);else angle.put("root",angles[i]);poses.put(new JSONObject().put("frame",frames[i]).put("rotations",angle));}return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",24).put("fps",24).put("rigs",new JSONArray().put(new JSONObject().put("id","actor").put("joints",new JSONArray().put(new JSONObject().put("id","root").put("x",.5).put("y",.5))).put("bindings",new JSONArray().put(new JSONObject().put("objectId","part").put("jointId","root"))).put("poses",poses)));}
 private void persist(boolean doubles,boolean uiSnapshot)throws Exception{
  JSONObject source=source(),input=program(doubles),program=uiSnapshot?SketchDocument.copy(input):input;String id=doubles?(uiSnapshot?"ui_double_program":"double_program"):"integer_program";JSONObject args=new JSONObject().put("animationId",id).put("sketchId",source.getString("id")).put("sourceRevision",source.getLong("revision")).put("expectedRevision",0).put("operationId","save").put("program",program);JSONObject saved=SketchAnimationOps.execute(context,"sketch_animation_save",args),record;String fingerprint;
  try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("sketch_animation_v1.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);Cursor result=db.rawQuery("SELECT json FROM animations WHERE id=?",new String[]{id});Cursor receipt=db.rawQuery("SELECT fingerprint FROM animation_operations WHERE animation_id=? AND operation_id=?",new String[]{id,"save"})){assertTrue(result.moveToFirst());record=new JSONObject(result.getString(0));assertTrue(receipt.moveToFirst());fingerprint=receipt.getString(0);}
  JSONObject evidence=new JSONObject().put("inputRotationJavaType",input.getJSONArray("rigs").getJSONObject(0).getJSONArray("poses").getJSONObject(1).getJSONObject("rotations").get("root").getClass().getSimpleName()).put("usesUiSnapshot",uiSnapshot).put("inputProgramCanonical",SketchDocument.canonical(program)).put("wireProgramCanonical",SketchDocument.canonical(new JSONObject(program.toString()))).put("savedProgramHash",record.getString("programSha256")).put("recomputedPersistedProgramHash",hash(record.getJSONObject("program"))).put("savedSourceHash",record.getString("sourceSha256")).put("recomputedPersistedSourceHash",hash(record.getJSONObject("sourceDocument"))).put("savedOperationFingerprint",fingerprint).put("originalRequestFingerprint",SketchDocument.sha256(("save\n"+SketchDocument.canonical(args)).getBytes(StandardCharsets.UTF_8)));
  String destination=System.getenv("VIDEOSTUDIO_NUMERIC_WIRE_EVIDENCE");if(destination!=null){File root=new File(destination);assertTrue(root.isDirectory()||root.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(root,id+".json"))){out.write(evidence.toString().getBytes(StandardCharsets.UTF_8));}}
  assertEquals("Source snapshot is not the reported program-number problem",record.getString("sourceSha256"),hash(record.getJSONObject("sourceDocument")));assertEquals("Do not rewrite unrelated request fingerprints",evidence.getString("originalRequestFingerprint"),fingerprint);
  JSONObject reopened=SketchAnimationOps.execute(context,"sketch_animation_get",new JSONObject().put("animationId",id).put("expectedRevision",1));assertEquals(SketchDocument.canonical(new JSONObject(program.toString())),SketchDocument.canonical(reopened.getJSONObject("program")));SketchAnimationOps.execute(context,"sketch_animation_bake",new JSONObject().put("animationId",id).put("expectedRevision",1).put("operationId","bake").put("outputSketchId",id+"_baked"));assertEquals(saved.toString(),SketchAnimationOps.execute(context,"sketch_animation_save",args).toString());
 }
 @Test public void integerPoseProgramsSaveReopenBakeAndReplay()throws Exception{run(()->{persist(false,false);return null;});}
 @Test public void nativeDoublePoseProgramsSaveReopenBakeAndReplay()throws Exception{run(()->{persist(true,false);return null;});}
 @Test public void currentUiSnapshotProtectsWholeDoublePosesBeforeSave()throws Exception{run(()->{persist(true,true);return null;});}
 @Test public void nativeDuelExampleMenuCreatesAReopenableAnimation()throws Exception{run(()->{JSONObject saved=SketchDuelAnimationExample.create(context);SketchAnimationOps.load(context,saved.getString("animationId"));return null;});}
}
