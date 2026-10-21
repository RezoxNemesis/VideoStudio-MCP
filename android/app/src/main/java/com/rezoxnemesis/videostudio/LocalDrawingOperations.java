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
   case "local_drawing_models":SketchDocument.fields(p,"");return new LocalDrawingModels(context).status();
   case "local_drawing_install":SketchDocument.fields(p,"packId");if(!LocalDrawingModels.ID.equals(p.getString("packId")))throw SketchDocument.bad("Unknown local drawing model pack");return new LocalDrawingModels(context).install(progress);
   case "local_drawing_generate":return generate(context,p,progress);
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
 private static JSONObject generate(Context context,JSONObject p,LocalDrawingEngine.Progress progress)throws Exception {
  if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local drawing cancelled before preparation");
  LocalDrawingSource.validate(p);LocalDrawingModels models=new LocalDrawingModels(context);String id=p.getString("requestId");File dir=directory(context,id),recordFile=new File(dir,"record.json");
  String modelIdentity=models.identity(),fingerprint=SketchDocument.sha256((modelIdentity+"\n"+SketchDocument.canonical(p)).getBytes(StandardCharsets.UTF_8));
  synchronized(ACCEPT_LOCK){
   if(recordFile.isFile()){JSONObject existing=read(recordFile);if(!fingerprint.equals(existing.getString("requestSha256")))throw new IllegalStateException("LOCAL_DRAWING_REQUEST_ID_CONFLICT: use a new request ID after changing the source or settings");}
   else{
    File parent=dir.getParentFile();File[] retained=parent.listFiles(f->f.isDirectory()&&new File(f,"record.json").isFile());if(retained!=null&&retained.length>=64)throw new IllegalStateException("LOCAL_DRAWING_STUDY_LIMIT: retain at most64 studies");
    if(context.getFilesDir().getUsableSpace()<256L*1024*1024)throw new IllegalStateException("LOCAL_DRAWING_STORAGE_UNAVAILABLE");
    try(SketchProjectStore sketches=new SketchProjectStore(context)){
     JSONObject document=sketches.get(p.getString("sketchId"));Bitmap frozen=LocalDrawingSource.prepare(sketches,document,p);
     try{if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create local drawing study");writePng(frozen,new File(dir,"source.png"));}finally{frozen.recycle();}
     JSONObject reference=SketchDocument.object(document,p.getString("objectId")).getJSONObject("reference");
     JSONObject record=new JSONObject().put("requestId",id).put("request",SketchDocument.copy(p)).put("requestSha256",fingerprint).put("modelIdentity",modelIdentity).put("provider",LocalDrawingModels.ID).put("state","accepted").put("progress",0).put("sourceName",document.getString("name")).put("sourceProcessingBounds",new JSONArray(LocalDrawingSource.processingBounds(reference,p))).put("sourcePreparation","Integer source crop, aspect-preserving512px fit with opaque white letterbox").put("preparedSourceSha256",LocalDrawingModels.hash(new File(dir,"source.png"))).put("originalUploadSha256",reference.optString("sourceSha256","")).put("createdAt",System.currentTimeMillis()).put("visualStatus","UNREVIEWED");save(recordFile,record);
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
    try(LocalDrawingEngine engine=new LocalDrawingEngine()){record.put("runtimeVersion",engine.runtimeVersion()).put("executionProvider","CPU");save(recordFile,record);generated=engine.render(model.directory,source,p.getJSONObject("settings"),new File(dir,"checkpoints"),fingerprint,(n,s)->update(recordFile,record,n,s,progress));}finally{source.recycle();}
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
     asset.generationMetadata.put("provider",LocalDrawingModels.ID).put("requestSha256",fingerprint).put("modelIdentity",modelIdentity).put("hostedInference",false).put("kind","reference-conditioned model-generated raster draft").put("artistStrokesRecovered",false);projects.save(project);
     record.put("outputSha256",sha).put("outputBytes",published.length()).put("outputFile",published.getName()).put("outputProjectId",project.id).put("outputAssetId",asset.id).put("state","completed").put("progress",100).put("stage","Native AI draft ready for visual review");save(recordFile,record);models.verified();progress.report(100,record.getString("stage"));return receipt(record);
    }finally{/* Keep a completed unpublished native candidate for failure/cancellation review. */}
    }
   }catch(Exception error){
    boolean interrupted=Thread.interrupted();try{record.put("state",interrupted||error instanceof InterruptedException?"cancelled":"failed").put("errorCode",error instanceof InterruptedException?"LOCAL_DRAWING_CANCELLED":error.getMessage()==null?error.getClass().getSimpleName():error.getMessage().substring(0,Math.min(240,error.getMessage().length())));save(recordFile,record);}finally{if(interrupted)Thread.currentThread().interrupt();}throw error;
   }
  }finally{GENERATION.release();}
 }
 private static void update(File file,JSONObject record,int n,String stage,LocalDrawingEngine.Progress progress)throws Exception{record.put("progress",n).put("stage",stage);save(file,record);progress.report(n,stage);}
 private static File directory(Context context,String id)throws Exception{SketchDocument.id(new JSONObject().put("id",id),"id");return new File(context.getFilesDir(),"local_drawing/studies/"+id);}
 private static File output(Context context,JSONObject record)throws Exception{String sha=record.getString("outputSha256");if(!sha.matches("[a-f0-9]{64}")||!record.getString("outputFile").equals(sha+".png"))throw new IOException("Invalid output identity");File f=new File(directory(context,record.getString("requestId")),sha+".png");if(!f.isFile()||f.length()!=record.getLong("outputBytes")||f.length()>4L*1024*1024||!sha.equals(LocalDrawingModels.hash(f)))throw new IOException("LOCAL_DRAWING_CANDIDATE_CHANGED");BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),bounds);if(bounds.outWidth!=512||bounds.outHeight!=512||!"image/png".equals(bounds.outMimeType))throw new IOException("LOCAL_DRAWING_CANDIDATE_INVALID");return f;}
 private static JSONObject receipt(JSONObject record)throws Exception{JSONObject out=SketchDocument.copy(record);out.remove("outputFile");return out.put("hostedInference",false).put("outputKind","reference-conditioned model-generated raster draft").put("recoversArtistLayers",false).put("sourceArtworkModified",false).put("creativeQualityCertified",false).put("seedScope","added denoising noise; encoder posterior sample is checkpointed");}
 private static JSONObject read(File file)throws Exception{
  if(!file.isFile()||file.length()>64*1024)throw new IllegalArgumentException("Unknown or invalid local drawing study");JSONObject record=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));JSONObject request=record.getJSONObject("request");LocalDrawingSource.validate(request);
  String model=record.getString("modelIdentity"),id=record.getString("requestId");
  if(!model.matches("[a-f0-9]{64}")||!id.equals(request.getString("requestId"))||!id.equals(file.getParentFile().getName())||!record.getString("requestSha256").equals(SketchDocument.sha256((model+"\n"+SketchDocument.canonical(request)).getBytes(StandardCharsets.UTF_8))))throw new IOException("LOCAL_DRAWING_RECORD_IDENTITY_DIFFERS");
  if(!Arrays.asList("accepted","running","completed","failed","cancelled").contains(record.getString("state")))throw new IOException("Invalid local drawing state");return record;
 }
 private static void save(File file,JSONObject record)throws Exception{record.put("updatedAt",System.currentTimeMillis());OpenPipelineArtifacts.writeAtomic(file,record.toString(2).getBytes(StandardCharsets.UTF_8),64*1024);}
 private static void writePng(Bitmap image,File target)throws Exception{File part=new File(target.getParentFile(),".png_"+UUID.randomUUID());try{try(FileOutputStream out=new FileOutputStream(part)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot encode native AI image");out.getFD().sync();}if(part.length()>4L*1024*1024)throw new IOException("Native AI image exceeds bound");if(!part.renameTo(target))throw new IOException("Cannot freeze native AI image");}finally{if(part.exists())part.delete();}}
}
