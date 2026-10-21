package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bounded, source-bound motion recipe inside an optional v2 editable archive. */
final class SketchAnimationArchive {
 static final String ARCHIVE_SCHEMA="videostudio.sketch.archive.v2";
 static final String RECIPE_SCHEMA="videostudio.sketch-animation.recipe.v1";
 static final int MAX_RECIPE_BYTES=544*1024;
 private SketchAnimationArchive(){}
 static String hash(JSONObject value)throws Exception {return SketchDocument.sha256(SketchDocument.canonical(value).getBytes(StandardCharsets.UTF_8));}
 static boolean selected(JSONObject args)throws Exception {
  boolean id=args.has("animationId"),revision=args.has("animationRevision");
  if(id!=revision)throw SketchDocument.bad("Select both animationId and animationRevision");
  if(id){SketchDocument.id(args,"animationId");SketchDocument.integer(args,"animationRevision",1,Long.MAX_VALUE-1);}
  return id;
 }
 static JSONObject snapshot(Context context,JSONObject args)throws Exception {
  JSONObject saved=SketchAnimationOps.load(context,SketchDocument.id(args,"animationId"));
  if(saved.getLong("revision")!=SketchDocument.integer(args,"animationRevision",1,Long.MAX_VALUE-1))throw new IllegalStateException("SKETCH_ANIMATION_REVISION_CONFLICT: saved scene changed");
  if(!saved.getString("sketchId").equals(SketchDocument.id(args,"sketchId"))||saved.getLong("sourceRevision")!=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1))throw SketchDocument.bad("Animation archive must select its frozen source sketch and revision");
  return saved;
 }
 static JSONObject recipe(JSONObject saved)throws Exception {
  return new JSONObject().put("schema",RECIPE_SCHEMA).put("sourceAnimationId",saved.getString("animationId"))
   .put("sourceAnimationRevision",saved.getLong("revision")).put("sourceSketchId",saved.getString("sketchId"))
   .put("sourceRevision",saved.getLong("sourceRevision")).put("sourceSha256",saved.getString("sourceSha256"))
   .put("programSha256",saved.getString("programSha256")).put("program",SketchDocument.copy(saved.getJSONObject("program")));
 }
 static void validate(JSONObject recipe,JSONObject source)throws Exception {
  validateRecipe(recipe);SketchDocument.validate(source);
  if(!recipe.getString("sourceSketchId").equals(source.getString("id"))||recipe.getLong("sourceRevision")!=source.getLong("revision")||!recipe.getString("sourceSha256").equals(hash(source)))throw SketchDocument.bad("Animation recipe source binding differs");
  // Reject malformed programs before publishing a restored sketch or any scene.
  SketchAnimationCompiler.compile(SketchDocument.copy(source),SketchDocument.copy(recipe.getJSONObject("program")),"archive_validation_output");
 }
 static void validateRecipe(JSONObject recipe)throws Exception {
  SketchDocument.fields(recipe,"schema,sourceAnimationId,sourceAnimationRevision,sourceSketchId,sourceRevision,sourceSha256,programSha256,program");
  if(!RECIPE_SCHEMA.equals(recipe.getString("schema")))throw SketchDocument.bad("Unknown animation recipe schema");
  SketchDocument.id(recipe,"sourceAnimationId");SketchDocument.id(recipe,"sourceSketchId");
  SketchDocument.integer(recipe,"sourceAnimationRevision",1,Long.MAX_VALUE-1);SketchDocument.integer(recipe,"sourceRevision",1,Long.MAX_VALUE-1);
  if(!recipe.getString("sourceSha256").matches("[a-f0-9]{64}")||!recipe.getString("programSha256").equals(hash(recipe.getJSONObject("program"))))throw SketchDocument.bad("Animation recipe checksum differs");
  if(recipe.toString().getBytes(StandardCharsets.UTF_8).length>MAX_RECIPE_BYTES)throw SketchDocument.bad("Animation recipe exceeds bounded archive size");
 }
 static String restoredId(String operation){return UUID.nameUUIDFromBytes(("videostudio.animation.restore/"+operation).getBytes(StandardCharsets.UTF_8)).toString();}
 static JSONObject restore(Context context,JSONObject source,JSONObject recipe,String operation)throws Exception {
  validateRecipe(recipe);String id=restoredId(operation);
  return SketchAnimationOps.restoreFrozen(context,source,recipe.getJSONObject("program"),id,"archive_"+SketchDocument.sha256(operation.getBytes(StandardCharsets.UTF_8)));
 }
}
