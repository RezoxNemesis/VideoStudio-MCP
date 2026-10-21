package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.function.LongConsumer;

/** Normal optional-pack installation helper. Only app-private source temporaries are cleaned. */
final class LocalDrawingModelFileInstall {
 interface Fetcher {void fetch(JSONObject descriptor,File destination)throws Exception;}
 static JSONObject prepare(File stage,JSONObject item,Fetcher fetch,LongConsumer progress)throws Exception {
  if(Thread.currentThread().isInterrupted())throw new InterruptedException("Model preparation cancelled");
  File target=new File(stage,item.getString("path"));safe(stage,target);JSONObject source=item.getJSONObject("derivedFrom");
  File privateRoot=new File(stage,".native_sources");if(!privateRoot.isDirectory()&&!privateRoot.mkdirs())throw new IOException("Cannot stage private conversion source");safe(stage,privateRoot);
  File original=new File(privateRoot,source.getString("sha256")+".onnx");safe(stage,original);
  if(valid(target,item)){if(valid(original,source)&&!original.delete())throw new IOException("Cannot clean verified private model source");File[] pending=privateRoot.listFiles();if(pending!=null&&pending.length==0)privateRoot.delete();return new JSONObject().put("reused",true).put("resultSha256",item.getString("sha256"));}
  if(!valid(original,source))fetch.fetch(source,original);
  if(!valid(original,source))throw new IOException("PLUS_INSTALL_SOURCE_CHECKSUM_DIFFERS");
  File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot prepare derived model directory");
  JSONObject proof=LocalDrawingPlusGraphConverter.convert(source.getString("recipe"),original,target,source.getString("sha256"),item.getString("sha256"),progress);
  if(!valid(target,item))throw new IOException("PLUS_INSTALL_RESULT_CHECKSUM_DIFFERS");
  // A published pack is never removed. This source belongs only to this serialized private writer.
  if(!original.delete())throw new IOException("Cannot clean verified private model source");File[] pending=privateRoot.listFiles();if(pending!=null&&pending.length==0)privateRoot.delete();return proof;
 }
 private static boolean valid(File file,JSONObject descriptor)throws Exception {return file.isFile()&&!Files.isSymbolicLink(file.toPath())&&file.length()==descriptor.getLong("bytes")&&descriptor.getString("sha256").equals(LocalDrawingModels.hash(file));}
 private static void safe(File root,File file)throws Exception {if(Files.isSymbolicLink(file.toPath())||!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Unsafe private model path");}
}
