package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** A portable scene must restore its original motion recipe, not merely baked keys. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAnimationArchiveTest {
 final Context context=RuntimeEnvironment.getApplication();
 final SketchPaintArchiveTest sketches=new SketchPaintArchiveTest();
 JSONObject program()throws Exception {
  return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",96).put("fps",24)
   .put("rigs",new JSONArray().put(new JSONObject().put("id","actor")
    .put("joints",new JSONArray().put(new JSONObject().put("id","root").put("x",.5).put("y",.5)))
    .put("bindings",new JSONArray().put(new JSONObject().put("objectId","red").put("jointId","root")))
    .put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("rotations",new JSONObject().put("root",0)))
      .put(new JSONObject().put("frame",95).put("rotations",new JSONObject().put("root",60))))));
 }
 JSONObject save(JSONObject source,String id)throws Exception {
  return SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject().put("animationId",id)
   .put("sketchId",source.getString("id")).put("sourceRevision",source.getLong("revision"))
   .put("expectedRevision",0).put("operationId","save").put("program",program()));
 }
 JSONObject request(JSONObject source,String animation)throws Exception {
  return sketches.args(source,"scene_archive").put("animationId",animation).put("animationRevision",1);
 }
 JSONObject zipJson(File file,String path)throws Exception {
  try(ZipFile z=new ZipFile(file);InputStream in=z.getInputStream(z.getEntry(path))){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
 }
 @Test public void restoresEditableMotionAndCanRebakeTheSameAuthored96Frames()throws Exception {
  JSONObject source=sketches.create();String id="scene";JSONObject saved=save(source,id);
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,request(source,id));
  assertEquals("videostudio.sketch.archive.v2",archive.metadata.getString("schema"));
  JSONObject recipe=zipJson(archive.file,"animation.json");assertEquals(saved.getString("programSha256"),recipe.getString("programSha256"));
  JSONObject result=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_scene","Restored scene");
  assertTrue(result.getBoolean("animationRestored"));assertNotEquals(id,result.getString("animationId"));
  JSONObject restored=SketchAnimationOps.load(context,result.getString("animationId"));
  assertEquals(result.getString("sketchId"),restored.getString("sketchId"));assertEquals(1,restored.getLong("sourceRevision"));
  assertEquals(SketchDocument.canonical(program()),SketchDocument.canonical(restored.getJSONObject("program")));
  JSONObject baked=SketchAnimationOps.execute(context,"sketch_animation_bake",new JSONObject().put("animationId",result.getString("animationId")).put("expectedRevision",1).put("operationId","rebake").put("outputSketchId","restored_output"));
  try(SketchProjectStore store=new SketchProjectStore(context)){
   JSONObject output=store.get(baked.getString("outputSketchId"));assertEquals(96,output.getInt("frameCount"));
   assertEquals(60,SketchDocument.transformAt(SketchDocument.object(output,"red"),95).getDouble("rotation"),1e-8);
   assertEquals(SketchDocument.canonical(source),SketchDocument.canonical(store.get(source.getString("id"))));
  }
 }
 @Test public void omittedRecipeKeepsLegacyArchiveAndDoesNotCreateAScene()throws Exception {
  JSONObject source=sketches.create();save(source,"scene");SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,sketches.args(source,"plain"));
  assertEquals(SketchPaintArchive.SCHEMA,archive.metadata.getString("schema"));try(ZipFile z=new ZipFile(archive.file)){assertNull(z.getEntry("animation.json"));}
  JSONObject r=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_plain",null);assertFalse(r.has("animationId"));
  assertEquals(1,SketchAnimationOps.execute(context,"sketch_animation_list",new JSONObject()).getJSONArray("animations").length());
 }
 @Test public void rejectsStaleMismatchedAndIncompleteRecipeSelection()throws Exception {
  JSONObject source=sketches.create(),other=sketches.create();save(source,"scene");
  for(JSONObject args:new JSONObject[]{request(source,"scene").put("animationRevision",2),request(other,"scene"),sketches.args(source,"missing_revision").put("animationId","scene"),sketches.args(source,"missing_id").put("animationRevision",1),request(source,"scene").put("animationRevision","1"),request(source,"scene").put("animationId",JSONObject.NULL)})
   assertThrows(Exception.class,()->SketchPaintArchive.prepare(context,args));
 }
 @Test public void checksumBoundRecipeTamperIsRejectedBeforePublishingAnySketch()throws Exception {
  JSONObject source=sketches.create();save(source,"scene");SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,request(source,"scene"));
  JSONObject recipe=zipJson(archive.file,"animation.json");recipe.getJSONObject("program").put("fps",30);
  byte[] changed=SketchDocument.canonical(recipe).getBytes(StandardCharsets.UTF_8);JSONObject inventory=zipJson(archive.file,"archive.json");JSONArray files=inventory.getJSONArray("files");
  for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);if("animation.json".equals(row.getString("path")))row.put("bytes",changed.length).put("sha256",SketchDocument.sha256(changed));}
  File bad=sketches.altered(sketches.altered(archive.file,"animation.json",changed),"archive.json",SketchDocument.canonical(inventory).getBytes(StandardCharsets.UTF_8));int before=sketches.count();
  assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,bad,SketchProjectStore.fileHash(bad),"tampered_recipe",null));assertEquals(before,sketches.count());
 }
 @Test public void frozenSourceExportAndInterruptedReceiptRecoveryKeepLaterHumanEdits()throws Exception {
  JSONObject source=sketches.create();save(source,"scene");
  rename(source.getString("id"),source.getLong("revision"),"Later source drawing");
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,request(source,"scene"));String hash=SketchProjectStore.fileHash(archive.file);
  JSONObject first=SketchPaintArchive.restoreFile(context,archive.file,hash,"recover_scene",null);String restoredId=first.getString("sketchId"),animation=first.getString("animationId");
  rename(restoredId,1,"Human changed restored drawing");
  JSONObject update=program().put("fps",30);SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject().put("animationId",animation).put("sketchId",restoredId).put("sourceRevision",1).put("expectedRevision",1).put("operationId","human_update").put("program",update));
  assertTrue(new File(context.getFilesDir(),"sketch_archive_receipts/recover_scene.completed.json").delete());assertTrue(archive.file.delete());
  JSONObject replay=SketchPaintArchive.restoreFile(context,archive.file,hash,"recover_scene",null);assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(replay));
  assertEquals(2,SketchAnimationOps.load(context,animation).getLong("revision"));assertEquals(30,SketchAnimationOps.load(context,animation).getJSONObject("program").getInt("fps"));
  try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals("Human changed restored drawing",store.get(restoredId).getString("name"));assertEquals("Later source drawing",store.get(source.getString("id")).getString("name"));}
 }
 @Test public void crashBetweenTheTwoDatabaseCommitsRecoversFrozenMotionAfterDrawingEdits()throws Exception {
  JSONObject source=sketches.create();save(source,"scene");SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,request(source,"scene"));String sha=SketchProjectStore.fileHash(archive.file);
  JSONObject first=SketchPaintArchive.restoreFile(context,archive.file,sha,"between_commits",null);String animation=first.getString("animationId");
  // Reproduce the durable state after sketch commit but before animation commit.
  try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("sketch_animation_v1.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
   db.delete("animation_operations","animation_id=?",new String[]{animation});db.delete("animations","id=?",new String[]{animation});
  }
  assertTrue(new File(context.getFilesDir(),"sketch_archive_receipts/between_commits.completed.json").delete());
  rename(first.getString("sketchId"),1,"Drawing edited during interrupted restore");assertTrue(archive.file.delete());
  JSONObject retry=SketchPaintArchive.restoreFile(context,archive.file,sha,"between_commits",null);
  assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(retry));
  JSONObject restored=SketchAnimationOps.load(context,animation);assertEquals(1,restored.getLong("sourceRevision"));assertEquals(SketchDocument.canonical(program()),SketchDocument.canonical(restored.getJSONObject("program")));
  try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals("Drawing edited during interrupted restore",store.get(first.getString("sketchId")).getString("name"));}
 }
 @Test public void interruptedExportReusesTheFrozenSceneEvenAfterTheSavedProgramChanges()throws Exception {
  JSONObject source=sketches.create();save(source,"scene");SketchPaintArchive.Prepared first=SketchPaintArchive.prepare(context,request(source,"scene"));String sha=SketchProjectStore.fileHash(first.file);
  JSONObject update=program().put("fps",30);SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject().put("animationId","scene").put("sketchId",source.getString("id")).put("sourceRevision",source.getLong("revision")).put("expectedRevision",1).put("operationId","later_scene").put("program",update));
  File generation=first.file.getParentFile();for(String name:new String[]{"completed.json","document.json","animation.json"})assertTrue(new File(generation,name).delete());assertTrue(first.file.delete());
  SketchPaintArchive.Prepared retry=SketchPaintArchive.prepare(context,request(source,"scene"));assertEquals(sha,SketchProjectStore.fileHash(retry.file));assertEquals(24,zipJson(retry.file,"animation.json").getJSONObject("program").getInt("fps"));
 }
 void rename(String id,long revision,String name)throws Exception {SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",UUID.randomUUID().toString()).put("operations",new JSONArray().put(new JSONObject().put("type","set_canvas").put("name",name))));}
}
