package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.*;

/** Copies only current, reviewed, app-owned stage marks into named clean parts. */
final class SketchAuthoredStagePromotion {
 private SketchAuthoredStagePromotion(){}
 static boolean requested(JSONObject operation){return "layered_drawing_stage".equals(operation.optString("type"))&&operation.has("promote");}
 static JSONArray sources(JSONObject doc,JSONObject operation)throws Exception {
  if(!requested(operation))return new JSONArray();
  if(!doc.has("layeredDrawing")||!"clean_line".equals(operation.getString("stage")))throw SketchDocument.bad("Reuse authored marks only into the clean-line stage");
  JSONArray mappings=operation.getJSONArray("promote");if(mappings.length()<1||mappings.length()>64)throw SketchDocument.bad("Reuse 1..64 owned authored objects per step");JSONObject workflow=doc.getJSONObject("layeredDrawing"),stages=workflow.getJSONObject("stageLayers");
  Map<String,String> sourceStages=new HashMap<>(),parts=new HashMap<>();for(String name:new String[]{"refinement","details"}){JSONArray layers=stages.getJSONArray(name);for(int i=0;i<layers.length();i++)sourceStages.put(layers.getString(i),name);}JSONArray named=workflow.getJSONArray("parts");for(int i=0;i<named.length();i++){JSONObject part=named.getJSONObject(i);parts.put(part.getString("id"),part.getString("layerId"));}
  Map<String,Integer> drawOrder=new HashMap<>();int order=0;Set<String> used=new HashSet<>(),selected=new HashSet<>(),checked=new HashSet<>();JSONArray layers=doc.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++){String id=objects.getJSONObject(j).getString("id");used.add(id);drawOrder.put(id,order++);}}JSONArray sources=new JSONArray();
  for(int i=0;i<mappings.length();i++){
   if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Authored stage reuse cancelled");JSONObject map=mappings.getJSONObject(i);SketchDocument.fields(map,"sourceObjectId,objectId,partId");String sourceId=SketchDocument.id(map,"sourceObjectId"),part=SketchDocument.id(map,"partId"),id=map.has("objectId")?SketchDocument.id(map,"objectId"):"clean_"+part.substring(0,Math.min(12,part.length()))+"_"+sourceId.substring(0,Math.min(40,sourceId.length()))+"_"+SketchDocument.sha256((part+"\n"+sourceId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,16);if(!selected.add(sourceId)||!used.add(id))throw SketchDocument.bad("This mark is already reused or its target ID exists; inspect the clean part before copying again");
   JSONObject source=SketchDocument.object(doc,sourceId),owner=SketchDocument.owner(doc,sourceId);String stage=sourceStages.get(owner.getString("id")),target=parts.get(part);if(stage==null||target==null)throw SketchDocument.bad("Choose owned refinement/detail marks and a named clean part");SketchEditOps.requireUnlocked(owner);
   if(checked.add(stage)&&!SketchLayeredDrawing.currentReviewAccepted(doc,stage))throw SketchDocument.bad("Review the current source drawing stage before reusing its marks");
   String kind=source.getString("kind");if(!Arrays.asList("stroke","eraser","path","paint").contains(kind)||source.optBoolean("fill")||source.has("reference")||source.has("masterLink")||source.has("gradient")||source.has("human")||source.has("hand")||source.has("styleId"))throw SketchDocument.bad("Only current authored pen, pencil and native paint are reusable");
   JSONObject destination=SketchDocument.layer(doc,target);SketchEditOps.requireUnlocked(destination);if(!stable(owner.getJSONObject("transform")).equals(stable(destination.getJSONObject("transform")))||!stable(owner.getJSONArray("keys")).equals(stable(destination.getJSONArray("keys"))))throw SketchDocument.bad("Match source and clean-part layer transforms before reusing marks");
   sources.put(new JSONObject().put("source",source).put("objectId",id).put("layerId",target).put("sourceOrder",drawOrder.get(sourceId)).put("sourceObjectId",sourceId).put("partId",part));
  }return sources;
 }
 static void apply(JSONObject document,JSONObject operation)throws Exception {
  JSONArray sources=sources(document,operation);List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<sources.length();i++)ordered.add(sources.getJSONObject(i));ordered.sort(Comparator.comparingInt(item->item.optInt("sourceOrder")));for(JSONObject item:ordered){JSONObject copy=SketchDocument.copy(item.getJSONObject("source")).put("id",item.getString("objectId"));SketchDocument.layer(document,item.getString("layerId")).getJSONArray("objects").put(copy);}
 }
 private static String stable(Object value)throws Exception {if(value instanceof JSONObject)return SketchDocument.canonical(new JSONObject(value.toString()));return SketchDocument.canonical(new JSONArray(value.toString()));}
}
