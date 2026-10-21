package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Shared phone/MCP surface. New immutable AI drafts never replace existing drawings. */
final class LocalDrawingOperations {
 private static final Semaphore GENERATION=new Semaphore(1,true);
 private static final Object ACCEPT_LOCK=new Object();
 static JSONObject execute(Context context,String action,JSONObject p,LocalDrawingEngine.Progress report)throws Exception {
  LocalDrawingEngine.Progress progress=report==null?(n,s)->{}:report;
  switch(action){
   case "local_drawing_models":SketchDocument.fields(p,"packId");return new LocalDrawingModels(context,LocalDrawingModels.selected(p)).status();
   case "local_drawing_install":SketchDocument.fields(p,"packId");p.getString("packId");return new LocalDrawingModels(context,LocalDrawingModels.selected(p)).install(progress);
   case "local_drawing_generate":if(p.has("previewOnly")){SketchDocument.bool(p,"previewOnly");if(p.getBoolean("previewOnly"))return previewSource(context,p);}return generate(context,p,progress);
   case "local_drawing_get":{
    SketchDocument.fields(p,"requestId,includePreview");String id=SketchDocument.id(p,"requestId");if(p.has("includePreview"))SketchDocument.bool(p,"includePreview");JSONObject record=read(new File(context.getFilesDir(),"local_drawing/studies/"+id+"/record.json"));
    JSONObject result=receipt(record);
    if(p.optBoolean("includePreview",false)&&"completed".equals(record.getString("state"))){File output=output(context,record);byte[] bytes=Files.readAllBytes(output.toPath());result.put("localDrawingPreviewImage",new JSONObject().put("mimeType","image/png").put("width",512).put("height",512).put("bytes",bytes.length).put("sha256",record.getString("outputSha256")).put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)));}
    return result;
   }
   default:throw SketchDocument.bad("Unknown local drawing action");
  }
 }
 static JSONObject completed(Context context,String id)throws Exception {
  JSONObject record=read(new File(directory(context,id),"record.json"));
  if(!"completed".equals(record.getString("state")))throw new IllegalStateException("LOCAL_DRAWING_KEY_NOT_COMPLETED: finish and review the draft first");
  output(context,record);return SketchDocument.copy(record);
 }
 private static JSONObject previewSource(Context context,JSONObject p)throws Exception {
  LocalDrawingSource.validate(p);if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local drawing preview cancelled");if(p.has("appearanceSource"))return previewPaired(context,p);
  try(SketchProjectStore sketches=new SketchProjectStore(context)){
   JSONObject document=sketches.get(p.getString("sketchId"));Bitmap frozen=LocalDrawingSource.prepare(context,sketches,document,p);
   try{
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!frozen.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IOException("Cannot encode native source preview");byte[] png=bytes.toByteArray();if(png.length>4*1024*1024)throw new IOException("Native source preview exceeds bound");if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local drawing preview cancelled");
    JSONObject result=LocalDrawingSource.sourceMetadata(document,p).put("requestId",p.getString("requestId")).put("sketchId",document.getString("id")).put("sourceRevision",document.getLong("revision")).put("sourceDocumentSha256",SketchDocument.sha256(SketchDocument.canonical(document).getBytes(StandardCharsets.UTF_8))).put("sourcePreviewOnly",true).put("inferenceExecuted",false).put("sourceArtworkModified",false).put("visualStatus","UNREVIEWED").put("localDrawingSourcePreviewImage",new JSONObject().put("mimeType","image/png").put("width",512).put("height",512).put("bytes",png.length).put("sha256",SketchDocument.sha256(png)).put("base64",Base64.encodeToString(png,Base64.NO_WRAP)));
    if(!p.has("frameSource"))result.put("sourceKind","reference_image").put("sourceObjectId",p.getString("objectId")).put("sourceReferenceSha256",p.getString("sourceSha256"));return result;
   }finally{frozen.recycle();}
  }
 }
 private static JSONObject previewPaired(Context context,JSONObject request)throws Exception {
  try(SketchProjectStore store=new SketchProjectStore(context)){
   JSONObject document=store.get(request.getString("sketchId"));try(LocalDrawingPairedSource.Inputs inputs=LocalDrawingPairedSource.prepare(context,store,document,request)){
    return LocalDrawingSource.sourceMetadata(document,request).put("requestId",request.getString("requestId")).put("sketchId",document.getString("id")).put("packId",LocalDrawingModels.forRequest(request)).put("sourcePreviewOnly",true).put("inferenceExecuted",false).put("sourceArtworkModified",false).put("visualStatus","UNREVIEWED").put("appearanceSourceIdentity",LocalDrawingPairedSource.identity(document,request)).put("structurePreprocessing",LocalDrawingPairedSource.EDGE_ALGORITHM).put("localDrawingSourcePreviewImage",previewPng(inputs.primary)).put("localDrawingAppearancePreviewImage",previewPng(inputs.appearance)).put("localDrawingStructurePreviewImage",previewPng(inputs.structure));
   }
  }
 }
 private static JSONObject previewPng(Bitmap image)throws Exception {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Paired preview cancelled");ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!image.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IOException("Cannot encode paired input preview");byte[] png=bytes.toByteArray();if(png.length>4*1024*1024)throw new IOException("Paired preview exceeds bound");return new JSONObject().put("mimeType","image/png").put("width",image.getWidth()).put("height",image.getHeight()).put("bytes",png.length).put("sha256",SketchDocument.sha256(png)).put("base64",Base64.encodeToString(png,Base64.NO_WRAP));}
 private static JSONObject generate(Context context,JSONObject p,LocalDrawingEngine.Progress progress)throws Exception {
  if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local drawing cancelled before preparation");
  LocalDrawingSource.validateStored(p);String pack=LocalDrawingModels.forRequest(p);LocalDrawingModels models=new LocalDrawingModels(context,pack);String id=p.getString("requestId");File dir=directory(context,id),recordFile=new File(dir,"record.json");
  String modelIdentity=models.identity(),fingerprint=LocalDrawingRequestIdentity.fingerprint(modelIdentity,p);
  synchronized(ACCEPT_LOCK){
   if(recordFile.isFile()){JSONObject existing=read(recordFile);if(!modelIdentity.equals(existing.getString("modelIdentity"))||!LocalDrawingRequestIdentity.sameWireRequest(p,existing.getJSONObject("request")))throw new IllegalStateException("LOCAL_DRAWING_REQUEST_ID_CONFLICT: use a new request ID after changing the source or settings");fingerprint=existing.getString("requestSha256");}
   else{
    File parent=dir.getParentFile();File[] retained=parent.listFiles(f->f.isDirectory()&&new File(f,"record.json").isFile());if(retained!=null&&retained.length>=64)throw new IllegalStateException("LOCAL_DRAWING_STUDY_LIMIT: retain at most64 studies");
    if(context.getFilesDir().getUsableSpace()<256L*1024*1024)throw new IllegalStateException("LOCAL_DRAWING_STORAGE_UNAVAILABLE");
    try(SketchProjectStore sketches=new SketchProjectStore(context)){
     JSONObject document=sketches.get(p.getString("sketchId"));
     if(p.has("appearanceSource")){try(LocalDrawingPairedSource.Inputs inputs=LocalDrawingPairedSource.prepare(context,sketches,document,p)){if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create local drawing study");writePng(inputs.primary,new File(dir,"source.png"));writePng(inputs.appearance,new File(dir,"appearance.png"));writePng(inputs.structure,new File(dir,"structure.png"));}}
     else{Bitmap frozen=LocalDrawingSource.prepare(context,sketches,document,p);try{if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create local drawing study");writePng(frozen,new File(dir,"source.png"));}finally{frozen.recycle();}}
     JSONObject record=new JSONObject().put("requestId",id).put("request",SketchDocument.copy(p)).put("requestSha256",fingerprint).put("modelIdentity",modelIdentity).put("provider",pack).put("state","accepted").put("progress",0).put("sourceName",document.getString("name")).put("preparedSourceSha256",LocalDrawingModels.hash(new File(dir,"source.png"))).put("createdAt",System.currentTimeMillis()).put("visualStatus","UNREVIEWED");JSONObject source=LocalDrawingSource.sourceMetadata(document,p);for(java.util.Iterator<String> keys=source.keys();keys.hasNext();){String key=keys.next();record.put(key,source.get(key));}if(p.has("appearanceSource"))record.put("appearanceSourceIdentity",LocalDrawingPairedSource.identity(document,p)).put("structurePreprocessing",LocalDrawingPairedSource.EDGE_ALGORITHM).put("preparedAppearanceSha256",LocalDrawingModels.hash(new File(dir,"appearance.png"))).put("preparedStructureSha256",LocalDrawingModels.hash(new File(dir,"structure.png")));save(recordFile,record);
    }
   }
  }
  while(!GENERATION.tryAcquire(100,TimeUnit.MILLISECONDS)){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local drawing cancelled while queued");}
  try{
   JSONObject record=read(recordFile);if("completed".equals(record.getString("state"))){output(context,record);return receipt(record).put("replayed",true);}
   try{
    record.put("state","running");record.remove("errorCode");save(recordFile,record);
    try(LocalDrawingModels.Lease model=models.require((n,s)->update(recordFile,record,n,s,progress))){
    File input=new File(dir,"source.png");if(!record.getString("preparedSourceSha256").equals(LocalDrawingModels.hash(input)))throw new IllegalStateException("LOCAL_DRAWING_FROZEN_SOURCE_CHANGED");
    Bitmap source=BitmapFactory.decodeFile(input.getPath());if(source==null||source.getWidth()!=512||source.getHeight()!=512){if(source!=null)source.recycle();throw new IOException("LOCAL_DRAWING_FROZEN_SOURCE_INVALID");}
    Bitmap generated;
    try{
     if(p.has("appearanceSource")){Bitmap appearance=frozenImage(new File(dir,"appearance.png"),record.getString("preparedAppearanceSha256"),224),structure=null;try{structure=frozenImage(new File(dir,"structure.png"),record.getString("preparedStructureSha256"),512);if(LocalDrawingModels.PLUS_ID.equals(pack)){try(LocalDrawingPlusEngine engine=new LocalDrawingPlusEngine()){record.put("runtimeVersion",engine.runtimeVersion()).put("executionProvider","CPU");save(recordFile,record);generated=engine.render(model.directory,source,appearance,structure,p.getJSONObject("settings"),p.getJSONObject("conditioning"),new File(dir,"checkpoints"),fingerprint,(n,s)->update(recordFile,record,n,s,progress));record.put("pairedPerformance",engine.performance());}}else try(LocalDrawingPairedEngine engine=new LocalDrawingPairedEngine()){record.put("runtimeVersion",engine.runtimeVersion()).put("executionProvider","CPU");save(recordFile,record);generated=engine.render(model.directory,source,appearance,structure,p.getJSONObject("settings"),p.getJSONObject("conditioning"),new File(dir,"checkpoints"),fingerprint,(n,s)->update(recordFile,record,n,s,progress));record.put("pairedPerformance",engine.performance());}}finally{appearance.recycle();if(structure!=null)structure.recycle();}}
     else{try(LocalDrawingEngine engine=new LocalDrawingEngine()){record.put("runtimeVersion",engine.runtimeVersion()).put("executionProvider","CPU");save(recordFile,record);generated=engine.render(model.directory,source,p.getJSONObject("settings"),new File(dir,"checkpoints"),fingerprint,(n,s)->update(recordFile,record,n,s,progress));}}
    }finally{source.recycle();}
    File temporary=new File(dir,"unpublished-candidate.png");
    try{
     try{writePng(generated,temporary);}finally{generated.recycle();}
     if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local drawing cancelled before candidate publication");
     String sha=LocalDrawingModels.hash(temporary);File published=new File(dir,sha+".png");
     if(published.exists()){if(!sha.equals(LocalDrawingModels.hash(published)))throw new IOException("LOCAL_DRAWING_CANDIDATE_CHANGED");}
     else if(!temporary.renameTo(published))throw new IOException("Cannot atomically publish native AI candidate");
     // Content-addressed published bytes are retained even if later registration fails.
     ProjectStore projects=new ProjectStore(context);String outputId=UUID.nameUUIDFromBytes(("local-drawing:"+id+":"+fingerprint).getBytes(StandardCharsets.UTF_8)).toString();ProjectStore.Project project=projects.get(outputId);
     if(project==null){project=new ProjectStore.Project();project.id=outputId;project.name="AI draft · "+record.getString("sourceName");project.sourcePrompt=p.getJSONObject("settings").getString("prompt");projects.save(project);}
     ProjectStore.Asset asset=projects.registerGeneratedAsset(project,Uri.fromFile(published),"Local_AI_"+id+".png","local_ai_draft",false);
     asset.generationMetadata.put("provider",pack).put("requestSha256",fingerprint).put("modelIdentity",modelIdentity).put("hostedInference",false).put("kind",p.has("appearanceSource")?"appearance-and-structure conditioned model-generated raster draft":p.has("frameSource")?"native-frame-conditioned model-generated raster draft":"reference-conditioned model-generated raster draft").put("artistStrokesRecovered",false);projects.save(project);
     record.put("outputSha256",sha).put("outputBytes",published.length()).put("outputFile",published.getName()).put("outputProjectId",project.id).put("outputAssetId",asset.id).put("state","completed").put("progress",100).put("stage","Native AI draft ready for visual review");save(recordFile,record);models.verified();progress.report(100,record.getString("stage"));return receipt(record);
    }finally{/* Keep a completed unpublished native candidate for failure/cancellation review. */}
    }
   }catch(Exception error){
    boolean interrupted=Thread.interrupted();try{record.put("state",interrupted||error instanceof InterruptedException?"cancelled":"failed").put("errorCode",error instanceof InterruptedException?"LOCAL_DRAWING_CANCELLED":error.getMessage()==null?error.getClass().getSimpleName():error.getMessage().substring(0,Math.min(240,error.getMessage().length())));save(recordFile,record);}finally{if(interrupted)Thread.currentThread().interrupt();}throw error;
   }
  }finally{GENERATION.release();}
 }
 private static Bitmap frozenImage(File file,String sha,int edge)throws Exception {if(!file.isFile()||file.length()>4L*1024*1024||!sha.equals(LocalDrawingModels.hash(file)))throw new IOException("LOCAL_DRAWING_FROZEN_SOURCE_CHANGED");BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);if(bounds.outWidth!=edge||bounds.outHeight!=edge||!"image/png".equals(bounds.outMimeType))throw new IOException("LOCAL_DRAWING_FROZEN_SOURCE_INVALID");Bitmap bitmap=BitmapFactory.decodeFile(file.getPath());if(bitmap==null)throw new IOException("LOCAL_DRAWING_FROZEN_SOURCE_INVALID");return bitmap;}
 private static void update(File file,JSONObject record,int n,String stage,LocalDrawingEngine.Progress progress)throws Exception{record.put("progress",n).put("stage",stage);save(file,record);progress.report(n,stage);}
 private static File directory(Context context,String id)throws Exception{SketchDocument.id(new JSONObject().put("id",id),"id");return new File(context.getFilesDir(),"local_drawing/studies/"+id);}
 private static File output(Context context,JSONObject record)throws Exception{String sha=record.getString("outputSha256");if(!sha.matches("[a-f0-9]{64}")||!record.getString("outputFile").equals(sha+".png"))throw new IOException("Invalid output identity");File f=new File(directory(context,record.getString("requestId")),sha+".png");if(!f.isFile()||f.length()!=record.getLong("outputBytes")||f.length()>4L*1024*1024||!sha.equals(LocalDrawingModels.hash(f)))throw new IOException("LOCAL_DRAWING_CANDIDATE_CHANGED");BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),bounds);if(bounds.outWidth!=512||bounds.outHeight!=512||!"image/png".equals(bounds.outMimeType))throw new IOException("LOCAL_DRAWING_CANDIDATE_INVALID");return f;}
 private static JSONObject receipt(JSONObject record)throws Exception{JSONObject out=SketchDocument.copy(record);out.remove("outputFile");return out.put("hostedInference",false).put("outputKind",record.getJSONObject("request").has("appearanceSource")?"appearance-and-structure conditioned model-generated raster draft":record.getJSONObject("request").has("frameSource")?"native-frame-conditioned model-generated raster draft":"reference-conditioned model-generated raster draft").put("recoversArtistLayers",false).put("sourceArtworkModified",false).put("creativeQualityCertified",false).put("seedScope","added denoising noise; encoder posterior sample is checkpointed");}
 private static JSONObject read(File file)throws Exception{
  if(!file.isFile()||file.length()>64*1024)throw new IllegalArgumentException("Unknown or invalid local drawing study");JSONObject record=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));JSONObject request=record.getJSONObject("request");LocalDrawingSource.validateStored(request);
  String model=record.getString("modelIdentity"),id=record.getString("requestId");
  if(!model.matches("[a-f0-9]{64}")||!id.equals(request.getString("requestId"))||!id.equals(file.getParentFile().getName())||!LocalDrawingRequestIdentity.verifiesStored(model,request,record.getString("requestSha256")))throw new IOException("LOCAL_DRAWING_RECORD_IDENTITY_DIFFERS");
  if(!Arrays.asList("accepted","running","completed","failed","cancelled").contains(record.getString("state")))throw new IOException("Invalid local drawing state");return record;
 }
 private static void save(File file,JSONObject record)throws Exception{record.put("updatedAt",System.currentTimeMillis());OpenPipelineArtifacts.writeAtomic(file,record.toString(2).getBytes(StandardCharsets.UTF_8),64*1024);}
 private static void writePng(Bitmap image,File target)throws Exception{File part=new File(target.getParentFile(),".png_"+UUID.randomUUID());try{try(FileOutputStream out=new FileOutputStream(part)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot encode native AI image");out.getFD().sync();}if(part.length()>4L*1024*1024)throw new IOException("Native AI image exceeds bound");if(!part.renameTo(target))throw new IOException("Cannot freeze native AI image");}finally{if(part.exists())part.delete();}}
}
