package com.rezoxnemesis.videostudio;
import android.app.ActivityManager;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Explicit public-weight download and verified private activation; no inference network calls. */
final class LocalDrawingModels {
 static final String ID="sd15_onnx_v1";
 private static final java.util.concurrent.Semaphore INSTALL=new java.util.concurrent.Semaphore(1,true);
 private final Context context;
 LocalDrawingModels(Context c){context=c.getApplicationContext();}
 JSONObject catalogue()throws Exception {
  try(InputStream in=context.getAssets().open("local_drawing/sd15-onnx-v1.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>64*1024)throw new IOException("Model catalogue exceeds bound");bytes.write(buffer,0,n);}return new JSONObject(bytes.toString("UTF-8"));}
 }
 String identity()throws Exception{return SketchDocument.sha256(SketchDocument.canonical(catalogue()).getBytes(StandardCharsets.UTF_8));}
 File directory(){return new File(context.getFilesDir(),"creative_workspace/models/installed/"+ID);}
 JSONObject status()throws Exception {
  JSONObject c=catalogue();boolean installed;try{installed=structurallyInstalled(c);}catch(Exception invalid){installed=false;}boolean bit64=android.os.Process.is64Bit();
  return new JSONObject().put("packId",ID).put("minimumNativeVersion","4.10.2").put("runtimeAdapter",ID).put("runtimeIncluded",true).put("modelInstalled",installed).put("requires64BitProcess",true).put("process64Bit",bit64).put("runtimeAvailable",installed&&bit64)
   .put("modelIdentity",identity()).put("modelBytes",c.getLong("modelBytes")).put("requiredAvailableRamMb",c.getLong("requiredAvailableRamMb")).put("availableRamMb",availableRamMb())
   .put("inferenceVerified",identity().equals(context.getSharedPreferences("local_drawing",Context.MODE_PRIVATE).getString("verifiedModel","")))
   .put("hostedInference",false).put("weightsDownloadedSeparately",true).put("width",512).put("height",512).put("outputKind","reference-conditioned model-generated raster draft").put("recoversArtistLayers",false).put("guaranteesAnatomyOrLikeness",false).put("license",c.getString("license")).put("sourceRepository",c.getString("sourceRepository")).put("sourceRevision",c.getString("sourceRevision"));
 }
 private boolean structurallyInstalled(JSONObject c)throws Exception {
  File root=directory(),manifest=new File(root,"manifest.json");if(Files.isSymbolicLink(root.toPath())||!manifest.isFile()||manifest.length()>64*1024)return false;
  JSONObject m=new JSONObject(new String(Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));if(!identity().equals(m.optString("pinnedCatalogueSha256"))||!ID.equals(m.optString("runtimeAdapter")))return false;
  JSONArray files=c.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject item=files.getJSONObject(i);File file=new File(root,item.getString("path"));if(!safe(root,file)||!file.isFile()||file.length()!=item.getLong("bytes"))return false;}return true;
 }
 Lease require(LocalDrawingEngine.Progress progress)throws Exception {
  JSONObject c=catalogue();if(!structurallyInstalled(c))throw new IllegalStateException("LOCAL_DRAWING_MODEL_NOT_INSTALLED");
  if(!android.os.Process.is64Bit())throw new IllegalStateException("LOCAL_DRAWING_REQUIRES_64_BIT_PROCESS");
  if(availableRamMb()<c.getLong("requiredAvailableRamMb"))throw new IllegalStateException("LOCAL_DRAWING_MEMORY_UNAVAILABLE: this model needs at least "+c.getLong("requiredAvailableRamMb")+"MiB currently available RAM");
  File frozen=new File(context.getFilesDir(),"local_drawing/inference_model_"+java.util.UUID.randomUUID());if(!frozen.mkdirs())throw new IOException("Cannot freeze native model files");Lease lease=new Lease(frozen);
  try{
   JSONArray files=c.getJSONArray("files");for(int i=0;i<files.length();i++){
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Model verification cancelled");JSONObject item=files.getJSONObject(i);progress.report(1,"Freezing and verifying local model file "+(i+1)+" of "+files.length());
    File original=new File(directory(),item.getString("path")),target=new File(frozen,item.getString("path"));if(!safe(frozen,target)||!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("Cannot freeze model directory");
    try{Files.createLink(target.toPath(),original.toPath());}catch(IOException|UnsupportedOperationException unavailable){
     if(frozen.getUsableSpace()<original.length()+256L*1024*1024)throw new IOException("LOCAL_DRAWING_SNAPSHOT_STORAGE_UNAVAILABLE");
     try(InputStream in=new FileInputStream(original);OutputStream out=new FileOutputStream(target)){byte[] b=new byte[256*1024];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Model snapshot cancelled");out.write(b,0,n);}}
    }
    if(target.length()!=item.getLong("bytes")||!item.getString("sha256").equals(hash(target)))throw new IOException("LOCAL_DRAWING_MODEL_CHECKSUM_DIFFERS: "+item.getString("path"));
   }return lease;
  }catch(Exception error){lease.close();throw error;}
 }
 JSONObject install(LocalDrawingEngine.Progress progress)throws Exception {
  while(!INSTALL.tryAcquire(100,java.util.concurrent.TimeUnit.MILLISECONDS)){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local model installation cancelled");}
  try{return installInner(progress);}catch(Exception error){if(Thread.currentThread().isInterrupted()){InterruptedException cancelled=new InterruptedException("Local model download cancelled; saved download checkpoints are kept");cancelled.initCause(error);throw cancelled;}throw error;}finally{INSTALL.release();}
 }
 private JSONObject installInner(LocalDrawingEngine.Progress progress)throws Exception {
  if(!android.os.Process.is64Bit())throw new IllegalStateException("LOCAL_DRAWING_REQUIRES_64_BIT_PROCESS");
  JSONObject c=catalogue();if(structurallyInstalled(c))return status();
  File existing=new File(directory(),"manifest.json");if(directory().exists()){
   JSONObject prior;try{prior=new JSONObject(new String(Files.readAllBytes(existing.toPath()),StandardCharsets.UTF_8));}catch(Exception unreadable){throw new IOException("LOCAL_DRAWING_PACK_ID_CONFLICT: existing pack is preserved");}
   if(!identity().equals(prior.optString("pinnedCatalogueSha256")))throw new IOException("LOCAL_DRAWING_PACK_ID_CONFLICT: existing pack is preserved");
  }
  long reserve=Math.max(512L*1024*1024,context.getFilesDir().getTotalSpace()/20),required=c.getLong("modelBytes")*2+reserve;if(context.getFilesDir().getUsableSpace()<required)throw new IllegalStateException("LOCAL_DRAWING_STORAGE_UNAVAILABLE: download/activation requires "+((required+1073741823L)/1073741824L)+"GiB free app storage");
  ModelPackManager manager=new ModelPackManager(context);File stage=new File(context.getFilesDir(),"creative_workspace/models/cloud_restore/"+ID+"_native_download");if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("Cannot stage optional model");stage.setLastModified(System.currentTimeMillis());
  JSONArray files=c.getJSONArray("files");long done=0,total=c.getLong("modelBytes");
  for(int i=0;i<files.length();i++){
   if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local model download cancelled");JSONObject item=files.getJSONObject(i);File target=new File(stage,item.getString("path"));if(!safe(stage,target))throw new IOException("Unsafe pinned model path");long prefix=done;
   if(!target.isFile()||target.length()!=item.getLong("bytes")||!item.getString("sha256").equals(hash(target))){
    new ResumableTransferManager(new TransferJournal(context)).download(new ResumableTransferManager.Request("local_model_"+i,c.getString("baseUrl")+item.getString("path"),target,item.getLong("bytes"),item.getString("sha256")),LocalDrawingModels::open,(bytes,expected)->{
     try{progress.report((int)Math.min(85,85d*(prefix+bytes)/total),"Downloading optional model weights; inference stays offline");}catch(Exception cancelled){if(cancelled instanceof InterruptedException)Thread.currentThread().interrupt();throw new ProgressFailure(cancelled);}
    });
   }
   done+=item.getLong("bytes");stage.setLastModified(System.currentTimeMillis());
  }
  JSONObject hashes=new JSONObject();for(int i=0;i<files.length();i++)hashes.put(files.getJSONObject(i).getString("path"),files.getJSONObject(i).getString("sha256"));
  try(InputStream license=context.getAssets().open("local_drawing/LICENSE-OpenRAIL-M.txt");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=license.read(buffer))!=-1){if(bytes.size()+n>64*1024)throw new IOException("Model license exceeds bound");bytes.write(buffer,0,n);}byte[] text=bytes.toByteArray();OpenPipelineArtifacts.writeAtomic(new File(stage,"MODEL_LICENSE.txt"),text,64*1024);hashes.put("MODEL_LICENSE.txt",SketchDocument.sha256(text));}
  JSONObject manifest=new JSONObject().put("id",ID).put("version","1").put("license",c.getString("license")).put("capabilities",new JSONArray().put("image.generate.neural").put("image.refine.neural")).put("category","generation").put("backend","onnxruntime-cpu").put("runtimeAdapter",ID).put("pinnedCatalogueSha256",identity()).put("files",hashes).put("sourceRepository",c.getString("sourceRepository")).put("sourceRevision",c.getString("sourceRevision")).put("hostedInference",false);
  OpenPipelineArtifacts.writeAtomic(new File(stage,"manifest.json"),manifest.toString(2).getBytes(StandardCharsets.UTF_8),64*1024);
  manager.activateRestoredDirectory(stage,"pinned-public-model-download",(n,d)->progress.report(85+n*15/100,d));return status();
 }
 void verified()throws Exception {if(!context.getSharedPreferences("local_drawing",Context.MODE_PRIVATE).edit().putString("verifiedModel",identity()).commit())throw new IOException("Cannot retain native inference receipt");}
 long availableRamMb(){ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);if(manager==null)return 0;ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();manager.getMemoryInfo(info);return info.availMem/(1024L*1024L);}
 static String hash(File file)throws Exception {java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[256*1024];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Model verification cancelled");digest.update(b,0,n);}}return OpenPipelineArtifacts.hex(digest.digest());}
 private static boolean safe(File root,File file)throws Exception{return file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)&&!Files.isSymbolicLink(file.toPath());}
 private static HttpURLConnection open(String address,long offset,String etag,String modified)throws Exception {
  URL url=new URL(address);for(int redirects=0;redirects<6;redirects++){
   String host=url.getHost();if(!"https".equals(url.getProtocol())||!(host.equals("huggingface.co")||host.endsWith(".huggingface.co")||host.endsWith(".hf.co"))||url.getUserInfo()!=null)throw new IOException("Pinned model download redirect rejected");
   HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept-Encoding","identity");c.setConnectTimeout(20000);c.setReadTimeout(30000);if(offset>0){c.setRequestProperty("Range","bytes="+offset+"-");if(!etag.isEmpty())c.setRequestProperty("If-Range",etag);else if(!modified.isEmpty())c.setRequestProperty("If-Range",modified);}int code=c.getResponseCode();
   if(code>=300&&code<400){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("Missing model redirect");url=new URL(url,location);continue;}return c;
  }throw new IOException("Too many model download redirects");
 }
 private static final class ProgressFailure extends RuntimeException{ProgressFailure(Exception e){super(e);}}
 static final class Lease implements AutoCloseable{
  final File directory;Lease(File file){directory=file;}
  @Override public void close(){remove(directory);}
  private static void remove(File file){if(!Files.isSymbolicLink(file.toPath())&&file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);}file.delete();}
 }
}
