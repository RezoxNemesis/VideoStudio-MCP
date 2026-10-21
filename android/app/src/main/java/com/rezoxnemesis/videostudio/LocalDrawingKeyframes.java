package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Verified AI raster provenance; ordinary image cels retain their usual editing semantics. */
final class LocalDrawingKeyframes {
 private LocalDrawingKeyframes(){}
 static JSONObject bind(Context context,JSONObject binding,ProjectStore.Project project,ProjectStore.Asset asset)throws Exception {
  SketchDocument.fields(binding,"requestId,outputSha256");String id=SketchDocument.id(binding,"requestId"),hash=hash(binding,"outputSha256");
  JSONObject study=LocalDrawingOperations.completed(context,id);
  if(asset==null||!project.id.equals(study.getString("outputProjectId"))||!asset.id.equals(study.getString("outputAssetId"))||!hash.equals(study.getString("outputSha256")))throw new IllegalStateException("LOCAL_DRAWING_KEY_SOURCE_DIFFERS");
  return new JSONObject().put("version",1).put("kind","local_ai_raster").put("request",SketchDocument.copy(study.getJSONObject("request"))).put("requestSha256",LocalDrawingRequestIdentity.fingerprint(study.getString("modelIdentity"),study.getJSONObject("request"))).put("modelIdentity",study.getString("modelIdentity")).put("outputSha256",hash);
 }
 static void validate(JSONObject reference)throws Exception {
  JSONObject source=reference.getJSONObject("localDrawing");SketchDocument.fields(source,"version,kind,request,requestSha256,modelIdentity,outputSha256");
  SketchDocument.integer(source,"version",1,1);if(!"local_ai_raster".equals(source.get("kind")))throw SketchDocument.bad("Unknown local drawing provenance kind");
  JSONObject request=source.getJSONObject("request");LocalDrawingSource.validateStored(request);String model=hash(source,"modelIdentity"),fingerprint=hash(source,"requestSha256"),output=hash(source,"outputSha256");
  if(!fingerprint.equals(LocalDrawingRequestIdentity.fingerprint(model,request))||!output.equals(reference.optString("sourceSha256","")))throw SketchDocument.bad("Local AI provenance differs from its source identity");
 }
 private static String hash(JSONObject value,String field)throws Exception {Object v=value.get(field);if(!(v instanceof String)||!((String)v).matches("[a-f0-9]{64}"))throw SketchDocument.bad("Exact local drawing "+field+" required");return(String)v;}
}
