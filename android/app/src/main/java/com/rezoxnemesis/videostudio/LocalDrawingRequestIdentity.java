package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Local drawing request identity only. Global document/request canonicalization is unchanged.
 * JSON has one number domain: hash the same wire object that is actually persisted.
 */
final class LocalDrawingRequestIdentity {
 private LocalDrawingRequestIdentity(){}
 static String fingerprint(String model,JSONObject request)throws Exception {return raw(model,SketchDocument.copy(request));}
 static boolean sameWireRequest(JSONObject first,JSONObject second)throws Exception {return SketchDocument.canonical(SketchDocument.copy(first)).equals(SketchDocument.canonical(SketchDocument.copy(second)));}
 static boolean verifiesStored(String model,JSONObject request,String fingerprint)throws Exception {
  if(fingerprint.equals(fingerprint(model,request)))return true;
  // Published manual code parsed these fields as Double before hashing, then lost
  // whole-number types during JSONObject.copy/save. Verify that exact known UI
  // representation; never accept an arbitrary unbound historical digest.
  JSONObject legacy=SketchDocument.copy(request);
  doubles(legacy.getJSONObject("settings"),"strength","guidance");
  if(legacy.has("sourceRect"))rectangle(legacy.getJSONObject("sourceRect"));
  if(legacy.has("frameSource")&&legacy.getJSONObject("frameSource").has("crop"))rectangle(legacy.getJSONObject("frameSource").getJSONObject("crop"));
  if(legacy.has("appearanceSource")&&legacy.getJSONObject("appearanceSource").has("sourceRect"))rectangle(legacy.getJSONObject("appearanceSource").getJSONObject("sourceRect"));
  if(legacy.has("conditioning"))doubles(legacy.getJSONObject("conditioning"),"appearanceStrength","structureStrength");
  return fingerprint.equals(raw(model,legacy));
 }
 private static String raw(String model,JSONObject request)throws Exception {return SketchDocument.sha256((model+"\n"+SketchDocument.canonical(request)).getBytes(StandardCharsets.UTF_8));}
 private static void rectangle(JSONObject rectangle)throws Exception {doubles(rectangle,"x","y","width","height");}
 private static void doubles(JSONObject object,String...keys)throws Exception {for(String key:keys)object.put(key,object.getDouble(key));}
}
